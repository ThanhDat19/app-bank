package vn.banklab.core.observability.infrastructure;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import vn.banklab.core.observability.application.HttpTelemetry;

public class TelemetryFilter extends OncePerRequestFilter {
    public static final String TRACE_HEADER = "X-Trace-ID";
    private final HttpTelemetry telemetry;
    private final HttpRouteCatalog routes;
    private final AtomicLong collectionFailures = new AtomicLong();
    public TelemetryFilter(HttpTelemetry telemetry, HttpRouteCatalog routes, MeterRegistry meters) {
        this.telemetry = telemetry; this.routes = routes;
        Gauge.builder("bank.monitoring.telemetry.failures", collectionFailures, AtomicLong::get).register(meters);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpTelemetry.Exchange exchange;
        try { exchange = telemetry.start(request.getMethod()); }
        catch (RuntimeException unavailable) { collectionFailures.incrementAndGet(); chain.doFilter(request, response); return; }
        String oldTrace = MDC.get("traceId"), oldSpan = MDC.get("spanId");
        response.setHeader(TRACE_HEADER, exchange.span().getSpanContext().getTraceId());
        boolean failed = false;
        try (var scope = exchange.span().makeCurrent()) {
            MDC.put("traceId", exchange.span().getSpanContext().getTraceId()); MDC.put("spanId", exchange.span().getSpanContext().getSpanId());
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) { failed = true; throw failure; }
        finally {
            restore("traceId", oldTrace); restore("spanId", oldSpan);
            String route = route(request);
            if (request.isAsyncStarted() && !failed) {
                var listener = new AsyncListener() {
                    private final AtomicBoolean timeout = new AtomicBoolean(), error = new AtomicBoolean();
                    public void onComplete(AsyncEvent event) { finish(exchange, route, response.getStatus(), timeout.get(), error.get()); }
                    public void onTimeout(AsyncEvent event) { timeout.set(true); }
                    public void onError(AsyncEvent event) { error.set(true); }
                    public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                };
                try { request.getAsyncContext().addListener(listener); }
                catch (IllegalStateException completed) { finish(exchange, route, response.getStatus(), false, false); }
            } else finish(exchange, route, response.getStatus(), false, failed);
        }
    }
    private String route(HttpServletRequest request) {
        try { return routes.safe(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)); }
        catch (RuntimeException unavailable) { collectionFailures.incrementAndGet(); return HttpRouteCatalog.UNMAPPED; }
    }
    private void finish(HttpTelemetry.Exchange exchange, String route, int status, boolean timeout, boolean failed) {
        try { exchange.finish(route, status, timeout, failed); }
        catch (RuntimeException unavailable) { collectionFailures.incrementAndGet(); }
    }
    private static void restore(String key, String value) { if (value == null) MDC.remove(key); else MDC.put(key, value); }
}
