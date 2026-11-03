package vn.banklab.core.realtime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.ledger.application.LedgerStreamAccess;
import vn.banklab.core.realtime.application.AccountEventStreams;
import vn.banklab.core.realtime.application.AccountStreamState;
import vn.banklab.core.shared.application.BusinessException;
import static org.assertj.core.api.Assertions.*;

class AccountEventStreamsTest {
    private static final AccountStreamState.Version VERSION = new AccountStreamState.Version(
            new AccountFinancialAccess.ReadVersion(0,false),new LedgerStreamAccess.Revision(0,0,java.math.BigDecimal.ZERO),0);
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); MDC.clear(); }

    @Test void defaultsStartAndInvalidResourceLimitsFailStartup() {
        var runner = new ApplicationContextRunner().withUserConfiguration(AccountEventStreams.class)
                .withBean(AccountStreamState.class, () -> new AccountStreamState(null,null,null));
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(AccountEventStreams.class));
        for (String property : List.of("bank.sse.max-connections=0", "bank.sse.max-connections=129",
                "bank.sse.max-per-actor=33", "bank.sse.poll-millis=0", "bank.sse.lifetime-millis=0")) {
            runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test void workerGetsOnlyItsCapturedSecurityAndCorrelationContextAndFailureReleasesAdmission() throws Exception {
        var a = UUID.randomUUID(); var b = UUID.randomUUID();
        var observed = new LinkedBlockingQueue<String>();
        var fail = new AtomicReference<UUID>();
        var source = new AccountStreamState(null,null,null) {
            @Override public Version read(UUID accountId) {
                if (Thread.currentThread().getName().startsWith("account-sse-")) {
                    var auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
                    observed.add(auth.getToken().getSubject()+":"+MDC.get("correlationId"));
                    if (accountId.equals(fail.get())) throw new BusinessException("ACCOUNT_NOT_FOUND",BusinessException.Kind.NOT_FOUND);
                }
                return VERSION;
            }
        };
        var streams = new AccountEventStreams(source,100,2000,1,1);
        try {
            var authA = authenticate("owner-a"); MDC.put("correlationId","caller-only"); fail.set(a);
            streams.open(a,authA,"corr-a");
            assertThat(observed.poll(3,TimeUnit.SECONDS)).isEqualTo("owner-a:corr-a");
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authA);
            assertThat(MDC.get("correlationId")).isEqualTo("caller-only");
            var authB = authenticate("owner-b");
            long until = System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while (true) {
                try { streams.open(b,authB,"corr-b"); break; }
                catch (BusinessException full) { if(System.nanoTime()>until)throw full; Thread.sleep(20); }
            }
            assertThat(observed.poll(3,TimeUnit.SECONDS)).isEqualTo("owner-b:corr-b");
        } finally { streams.close(); }
    }

    @Test void shutdownInterruptsWorkerAndRejectsNewStreams() throws Exception {
        var entered = new CountDownLatch(1); var exited = new CountDownLatch(1);
        var source = new AccountStreamState(null,null,null) {
            @Override public Version read(UUID id) {
                if(Thread.currentThread().getName().startsWith("account-sse-")) {
                    entered.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch(InterruptedException stop) { Thread.currentThread().interrupt(); }
                    finally { exited.countDown(); }
                }
                return VERSION;
            }
        };
        var streams = new AccountEventStreams(source,100,2000,1,1);
        try {
            var auth = authenticate("owner"); streams.open(UUID.randomUUID(),auth,"shutdown-test");
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            streams.close(); assertThat(exited.await(3,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> streams.open(UUID.randomUUID(),auth,"after-shutdown"))
                    .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("SSE_CONNECTION_LIMIT"));
        } finally { streams.close(); }
    }

    private JwtAuthenticationToken authenticate(String subject) {
        var jwt = Jwt.withTokenValue("synthetic-unit-token").header("alg","RS256").issuer("https://unit.invalid")
                .subject(subject).issuedAt(Instant.now().minusSeconds(1)).expiresAt(Instant.now().plusSeconds(30)).build();
        var auth = new JwtAuthenticationToken(jwt);
        auth.setAuthenticated(true); SecurityContextHolder.getContext().setAuthentication(auth); return auth;
    }
}
