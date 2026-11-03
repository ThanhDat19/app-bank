package vn.banklab.core.realtime.application;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.banklab.core.shared.application.BusinessException;

@Service
public class AccountEventStreams {
    public record Resync(UUID accountId, String reason) { }
    private record Identity(String issuer, String subject) { }
    private final AccountStreamState state;
    private final long pollMillis;
    private final long lifetimeMillis;
    private final int maxConnections;
    private final int perActor;
    private final ThreadPoolExecutor workers;
    private final Map<Identity, Integer> admitted = new HashMap<>();
    private int connections;
    private boolean shutdown;

    public AccountEventStreams(AccountStreamState state,
            @Value("${bank.sse.poll-millis:2000}") long pollMillis,
            @Value("${bank.sse.lifetime-millis:120000}") long lifetimeMillis,
            @Value("${bank.sse.max-connections:32}") int maxConnections,
            @Value("${bank.sse.max-per-actor:2}") int perActor) {
        if (pollMillis < 100 || pollMillis > 30000 || lifetimeMillis < 500 || lifetimeMillis > 300000
                || maxConnections < 1 || maxConnections > 128 || perActor < 1 || perActor > maxConnections) {
            throw new IllegalArgumentException("Invalid SSE limits");
        }
        this.state = state; this.pollMillis = pollMillis; this.lifetimeMillis = lifetimeMillis;
        this.maxConnections = maxConnections; this.perActor = perActor;
        var sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(0, maxConnections, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), task -> {
                    var thread = new Thread(task, "account-sse-" + sequence.incrementAndGet());
                    thread.setDaemon(true); return thread;
                });
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    public SseEmitter open(UUID accountId, Authentication authentication, String correlationId) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()
                || token.getToken().getExpiresAt() == null
                || !Instant.now().isBefore(token.getToken().getExpiresAt())) {
            throw new BadCredentialsException("Unexpired verified access token required");
        }
        var initial = state.read(accountId); // Errors stay ordinary Problem Details before SSE begins.
        var identity = new Identity(token.getToken().getIssuer().toString(), token.getToken().getSubject());
        admit(identity);
        var deadline = Instant.now().plusMillis(lifetimeMillis);
        if (token.getToken().getExpiresAt().isBefore(deadline)) deadline = token.getToken().getExpiresAt();
        var session = new Session(accountId, identity, initial, deadline, correlationId);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        try {
            workers.execute(new DelegatingSecurityContextRunnable(session, context));
        } catch (RejectedExecutionException failure) {
            release(identity);
            throw capacity();
        }
        return session.emitter;
    }

    private synchronized void admit(Identity identity) {
        if (shutdown || connections >= maxConnections || admitted.getOrDefault(identity, 0) >= perActor) {
            throw capacity();
        }
        admitted.merge(identity, 1, Integer::sum); connections++;
    }

    private synchronized void release(Identity identity) {
        admitted.computeIfPresent(identity, (key, count) -> count == 1 ? null : count - 1);
        connections--;
    }

    private static BusinessException capacity() {
        return new BusinessException("SSE_CONNECTION_LIMIT", BusinessException.Kind.CONFLICT);
    }

    @PreDestroy
    public synchronized void close() {
        shutdown = true;
        workers.shutdownNow();
    }

    private final class Session implements Runnable {
        private final UUID accountId;
        private final Identity identity;
        private final Instant deadline;
        private final String correlationId;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final SseEmitter emitter;
        private AccountStreamState.Version previous;
        private volatile Thread thread;

        Session(UUID accountId, Identity identity, AccountStreamState.Version initial,
                Instant deadline, String correlationId) {
            this.accountId = accountId; this.identity = identity; this.previous = initial;
            this.deadline = deadline; this.correlationId = correlationId;
            emitter = new SseEmitter(lifetimeMillis + 1000);
            emitter.onCompletion(this::stop);
            emitter.onTimeout(this::stop);
            emitter.onError(error -> stop());
        }

        private synchronized void stop() {
            stopped.set(true);
            var running = thread;
            if (running != null && running != Thread.currentThread()) running.interrupt();
        }

        private boolean live() { return !stopped.get() && Instant.now().isBefore(deadline); }

        private void resync(String reason) throws IOException {
            // No id: Last-Event-ID is deliberately never treated as a reliable replay position.
            emitter.send(SseEmitter.event().name("resync").reconnectTime(5000)
                    .data(new Resync(accountId, reason), MediaType.APPLICATION_JSON));
        }

        @Override
        public void run() {
            thread = Thread.currentThread();
            var previousMdc = MDC.getCopyOfContextMap();
            MDC.clear();
            if (correlationId != null) MDC.put("correlationId", correlationId);
            try {
                if (live()) resync("CONNECTED");
                while (live()) {
                    long remaining = java.time.Duration.between(Instant.now(), deadline).toMillis();
                    if (remaining <= 0) break;
                    Thread.sleep(Math.min(pollMillis, remaining));
                    if (!live()) break;
                    var current = state.read(accountId);
                    if (!live()) break; // A slow query must not cause a new frame after token expiry.
                    if (!current.equals(previous)) {
                        resync("STATE_CHANGED"); previous = current;
                    } else {
                        emitter.send(SseEmitter.event().comment("keepalive"));
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException failure) {
                // Already-streaming errors close the channel. Never serialize SQL/JWT/ownership failures.
                LoggerFactory.getLogger(AccountEventStreams.class).debug("Account SSE closed type={}", failure.getClass().getName());
            } finally {
                stopped.set(true);
                // Late servlet callbacks must never interrupt this pool thread after it is reused.
                synchronized (this) { thread = null; }
                try { emitter.complete(); }
                finally {
                    // Hold admission until this worker exits, even if a slow transport blocks completion.
                    release(identity);
                    if (previousMdc == null) MDC.clear(); else MDC.setContextMap(previousMdc);
                }
            }
        }
    }
}
