package vn.banklab.core.observability.infrastructure;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import vn.banklab.core.observability.application.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelemetryPolicyTest {
    @Test void zeroSamplingKeepsMetricsAndIdsButRetainsNoSpans() {
        var config=new TelemetryConfiguration();var buffer=new RecentTraces(2);
        try(var provider=config.localTracer(buffer,0);var registry=new TestRegistry()) {
            var telemetry=new HttpTelemetry(registry,config.safeTracer(provider));var exchange=telemetry.start("GET");
            assertThat(exchange.span().getSpanContext().isValid()).isTrue();exchange.finish("UNMAPPED",200,false,false);
            assertThat(buffer.latest(100)).isEmpty();assertThat(registry.get(HttpTelemetry.DURATION).timer().count()).isEqualTo(1);
        }
    }
    @Test void selectedFieldPipelineIgnoresParentAndEvictsOldSpans() {
        var config=new TelemetryConfiguration();var buffer=new RecentTraces(2);
        try(var provider=config.localTracer(buffer,1);var registry=new TestRegistry()) {
            var telemetry=new HttpTelemetry(registry,config.safeTracer(provider));
            var parent=Span.wrap(SpanContext.create("11111111111111111111111111111111","1111111111111111",TraceFlags.getSampled(),TraceState.getDefault()));
            try(var scope=parent.makeCurrent()) {
                for(int i=0;i<3;i++){var exchange=telemetry.start("GET");exchange.finish("UNMAPPED",200,false,false);}
            }
            assertThat(buffer.latest(100)).hasSize(2).allSatisfy(t->{assertThat(t.traceId()).isNotEqualTo(parent.getSpanContext().getTraceId());assertThat(t.route()).isEqualTo("UNMAPPED");assertThat(t.durationMillis()).isGreaterThanOrEqualTo(0);});
        }
        for(double probability:new double[]{-0.1,1.1,Double.NaN,Double.POSITIVE_INFINITY})
            assertThatThrownBy(()->config.localTracer(buffer,probability)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void exportFilterRejectsUnknownMetersTagsAndRawRouteValues() {
        var catalog=mock(HttpRouteCatalog.class);when(catalog.safe(any())).thenReturn("UNMAPPED");
        try(var registry=new TestRegistry()) {
            registry.config().meterFilter(new TelemetryConfiguration().safeMeters(catalog));
            registry.counter("http.server.requests","uri","PRIVATE_PATH").increment();
            Gauge.builder("bank.runtime.heap.used",new AtomicInteger(),AtomicInteger::get).tag("actor","PRIVATE_ACTOR").register(registry);
            Timer.builder(HttpTelemetry.DURATION).tags("method","GET","route","PRIVATE_UUID","status","2xx","outcome","SUCCESS").register(registry).record(()->{});
            Timer.builder(HttpTelemetry.DURATION).tags("method","GET","route","UNMAPPED","status","2xx","actor","PRIVATE_ACTOR").register(registry).record(()->{});
            Timer.builder(HttpTelemetry.DURATION).tags("method","GET","route","UNMAPPED","status","2xx","outcome","SUCCESS").register(registry).record(()->{});
            assertThat(registry.getMeters()).hasSize(1);
            assertThat(registry.getMeters().getFirst().getId().getTags().toString()).doesNotContain("PRIVATE");
        }
    }
    static class TestRegistry extends SimpleMeterRegistry implements AutoCloseable { }
}
