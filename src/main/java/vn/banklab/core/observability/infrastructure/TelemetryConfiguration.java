package vn.banklab.core.observability.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.banklab.core.observability.application.HttpTelemetry;
import vn.banklab.core.observability.application.RecentTraces;

@Configuration(proxyBeanMethods = false)
public class TelemetryConfiguration {
    @Bean java.time.Clock monitoringClock() { return java.time.Clock.systemUTC(); }
    @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<TelemetryFilter> telemetryFilter(
            HttpTelemetry telemetry, HttpRouteCatalog routes, io.micrometer.core.instrument.MeterRegistry meters) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new TelemetryFilter(telemetry, routes, meters));
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 1);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST);
        return registration;
    }
    private static final Set<String> GAUGES = Set.of("bank.http.server.active", "bank.runtime.heap.used", "bank.runtime.heap.max",
            "bank.runtime.threads", "bank.monitoring.available", "bank.monitoring.snapshot.age", "bank.monitoring.telemetry.failures", "bank.backlog.outbox.pending",
            "bank.backlog.outbox.leased", "bank.backlog.outbox.oldest", "bank.backlog.notifications.retry", "bank.backlog.notifications.quarantined",
            "bank.backlog.external.unknown", "bank.backlog.external.prepared", "bank.backlog.operations.pending");
    @Bean MeterFilter safeMeters(HttpRouteCatalog catalog) {
        return new MeterFilter() {
            @Override public MeterFilterReply accept(Meter.Id id) {
                if (GAUGES.contains(id.getName())) return id.getTags().isEmpty() ? MeterFilterReply.NEUTRAL : MeterFilterReply.DENY;
                if (!HttpTelemetry.DURATION.equals(id.getName()) || id.getTags().size() != 4) return MeterFilterReply.DENY;
                String route = id.getTag("route");
                return id.getTag("method") != null && id.getTag("outcome") != null && id.getTag("status") != null
                        && HttpTelemetry.METHODS.contains(id.getTag("method")) && HttpTelemetry.OUTCOMES.contains(id.getTag("outcome"))
                        && Set.of("1xx","2xx","3xx","4xx","5xx").contains(id.getTag("status"))
                        && route != null && (HttpRouteCatalog.UNMAPPED.equals(route) || route.equals(catalog.safe(route)))
                        ? MeterFilterReply.NEUTRAL : MeterFilterReply.DENY;
            }
        };
    }
    @Bean(destroyMethod = "close") SdkTracerProvider localTracer(RecentTraces recent,
            @Value("${bank.monitoring.trace-sampling:0.1}") double sampling) {
        if (!Double.isFinite(sampling) || sampling < 0 || sampling > 1) throw new IllegalArgumentException("Invalid trace sampling");
        return SdkTracerProvider.builder().setSampler(Sampler.traceIdRatioBased(sampling))
                .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "core-banking-service")))
                .setSpanLimits(SpanLimits.builder().setMaxNumberOfAttributes(4).setMaxAttributeValueLength(200).build())
                .addSpanProcessor(new SpanProcessor() {
                    @Override public void onStart(Context parent, ReadWriteSpan span) { }
                    @Override public boolean isStartRequired() { return false; }
                    @Override public boolean isEndRequired() { return true; }
                    @Override public void onEnd(ReadableSpan span) {
                        if (!span.getSpanContext().isSampled()) return;
                        var data = span.toSpanData(); var a = data.getAttributes();
                        recent.add(new RecentTraces.Trace(data.getTraceId(), data.getSpanId(), a.get(AttributeKey.stringKey("http.request.method")),
                                a.get(AttributeKey.stringKey("http.route")), Math.toIntExact(a.get(AttributeKey.longKey("http.response.status_code"))),
                                a.get(AttributeKey.stringKey("bank.outcome")), Instant.ofEpochSecond(0, data.getEndEpochNanos()),
                                (data.getEndEpochNanos() - data.getStartEpochNanos()) / 1_000_000d));
                    }
                }).build();
    }
    @Bean Tracer safeTracer(SdkTracerProvider provider) { return provider.get("bank-observability"); }
    @Bean io.micrometer.core.instrument.binder.MeterBinder runtimeGauges() {
        var memory = java.lang.management.ManagementFactory.getMemoryMXBean();
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        return registry -> {
            Gauge.builder("bank.runtime.heap.used", memory, m -> m.getHeapMemoryUsage().getUsed()).strongReference(true).register(registry);
            Gauge.builder("bank.runtime.heap.max", memory, m -> m.getHeapMemoryUsage().getMax()).strongReference(true).register(registry);
            Gauge.builder("bank.runtime.threads", threads, t -> t.getThreadCount()).strongReference(true).register(registry);
        };
    }
}
