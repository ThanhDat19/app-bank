package vn.banklab.core.observability.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class HttpTelemetry {
    public static final String DURATION = "bank.http.server.duration";
    public static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "OTHER");
    public static final Set<String> OUTCOMES = Set.of("SUCCESS", "CLIENT_ERROR", "SERVER_ERROR", "TIMEOUT");
    private final MeterRegistry meters;
    private final Tracer tracer;
    private final AtomicInteger active = new AtomicInteger();
    public HttpTelemetry(MeterRegistry meters, Tracer tracer) {
        this.meters = meters; this.tracer = tracer;
        Gauge.builder("bank.http.server.active", active, AtomicInteger::get).register(meters);
    }
    public Exchange start(String method) {
        var span = tracer.spanBuilder("bank.http.request").setSpanKind(SpanKind.SERVER).setNoParent().startSpan();
        active.incrementAndGet();
        return new Exchange(METHODS.contains(method) ? method : "OTHER", span);
    }
    public final class Exchange {
        private final String method;
        private final Span span;
        private final long started = System.nanoTime();
        private final AtomicBoolean finished = new AtomicBoolean();
        private Exchange(String method, Span span) { this.method = method; this.span = span; }
        public Span span() { return span; }
        public void finish(String route, int responseStatus, boolean timedOut, boolean failed) {
            if (!finished.compareAndSet(false, true)) return;
            int status = failed ? 500 : responseStatus;
            if (status < 100 || status > 599) status = 500;
            String outcome = timedOut ? "TIMEOUT" : status >= 500 ? "SERVER_ERROR" : status >= 400 ? "CLIENT_ERROR" : "SUCCESS";
            try {
                span.setAttribute("http.request.method", method).setAttribute("http.route", route)
                        .setAttribute("http.response.status_code", status).setAttribute("bank.outcome", outcome);
                if (status >= 500 || timedOut) span.setStatus(StatusCode.ERROR);
                Timer.builder(DURATION).tags("method", method, "route", route, "status", (status / 100) + "xx", "outcome", outcome)
                        .serviceLevelObjectives(Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25),
                                Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
                                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10),
                                Duration.ofSeconds(30), Duration.ofSeconds(120))
                        .register(meters).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            } finally { active.decrementAndGet(); span.end(); }
        }
    }
}
