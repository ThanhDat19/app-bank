package vn.banklab.core.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import vn.banklab.core.observability.application.*;
import vn.banklab.core.observability.infrastructure.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelemetryTest {
    @Test void bufferIsBoundedAndInvalidLimitsFail() {
        var buffer=new RecentTraces(2);
        for(int n=0;n<3;n++)buffer.add(new RecentTraces.Trace("trace"+n,"span","GET","UNMAPPED",200,"SUCCESS",Instant.EPOCH,n));
        assertThat(buffer.latest(100)).extracting(RecentTraces.Trace::traceId).containsExactly("trace2","trace1");
        assertThatThrownBy(()->buffer.latest(0)).isInstanceOf(vn.banklab.core.shared.application.BusinessException.class);
        assertThatThrownBy(()->new RecentTraces(2001)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cacheNeverInventsHealthyZeroAndPreservesLastSuccessOnFailure() {
        var clock=new MutableClock();var fail=new AtomicBoolean();
        SnapshotQuery query=()->{if(fail.get())throw new IllegalStateException("PRIVATE_SQL");return counts(7);};
        try(var registry=new TestRegistry()) {
            var cache=new OperationalSnapshots(query,registry,clock,1000);
            assertThat(cache.current().available()).isFalse();assertThat(cache.current().stale()).isTrue();
            assertThat(registry.get("bank.backlog.external.unknown").gauge().value()).isNaN();
            var success=cache.refresh();assertThat(success.available()).isTrue();assertThat(success.stale()).isFalse();
            clock.now=clock.now.plusSeconds(2);assertThat(cache.current().stale()).isTrue();assertThat(registry.get("bank.monitoring.snapshot.age").gauge().value()).isEqualTo(2);
            fail.set(true);var failed=cache.refresh();assertThat(failed.lastSuccessAt()).isEqualTo(success.lastSuccessAt());
            assertThat(failed.available()).isFalse();assertThat(failed.counts()).isEqualTo(success.counts());
            assertThat(failed.problem()).isEqualTo("MONITORING_SNAPSHOT_UNAVAILABLE");
            assertThat(registry.get("bank.monitoring.available").gauge().value()).isZero();
            assertThat(registry.get("bank.backlog.external.unknown").gauge().value()).isEqualTo(7);
        }
    }
    @Test void requestScopeRestoresExistingMdcAndFinishesExactlyOnce() throws Exception {
        try(var registry=new TestRegistry();var provider=SdkTracerProvider.builder().setSampler(Sampler.alwaysOff()).build()) {
            var telemetry=new HttpTelemetry(registry,provider.get("test"));var catalog=mock(HttpRouteCatalog.class);when(catalog.safe(any())).thenReturn("UNMAPPED");
            var filter=new TelemetryFilter(telemetry,catalog,registry);var request=new MockHttpServletRequest("GET","/secret-user");var response=new MockHttpServletResponse();
            MDC.put("traceId","outer-trace");MDC.put("spanId","outer-span");
            try {
                filter.doFilter(request,response,(r,s)->assertThat(MDC.get("traceId")).matches("[a-f0-9]{32}"));
                assertThat(MDC.get("traceId")).isEqualTo("outer-trace");assertThat(MDC.get("spanId")).isEqualTo("outer-span");
            } finally {MDC.clear();}
            assertThat(response.getHeader("X-Trace-ID")).matches("[a-f0-9]{32}");
            var exchange=telemetry.start("TRACE");exchange.finish("UNMAPPED",403,false,false);exchange.finish("UNMAPPED",500,false,true);
            assertThat(registry.get("bank.http.server.duration").tag("method","OTHER").timer().count()).isEqualTo(1);
            assertThat(registry.get("bank.http.server.active").gauge().value()).isZero();
        }
    }
    @Test void telemetryCollectionFailureCannotReplaceBusinessResponseOrLeakInFlight() throws Exception {
        try(var registry=new TestRegistry();var provider=SdkTracerProvider.builder().setSampler(Sampler.alwaysOff()).build()) {
            var telemetry=new HttpTelemetry(registry,provider.get("test"));var catalog=mock(HttpRouteCatalog.class);when(catalog.safe(any())).thenThrow(new IllegalStateException("PRIVATE_ROUTE"));
            var response=new MockHttpServletResponse();new TelemetryFilter(telemetry,catalog,registry).doFilter(new MockHttpServletRequest("GET","/secret"),response,(r,s)->((jakarta.servlet.http.HttpServletResponse)s).setStatus(201));
            assertThat(response.getStatus()).isEqualTo(201);assertThat(registry.get("bank.monitoring.telemetry.failures").gauge().value()).isEqualTo(1);
            assertThat(registry.get("bank.http.server.active").gauge().value()).isZero();
        }
    }
    @Test void exceptionPathStillRestoresScopeWithoutStoringExceptionPayload() {
        try(var registry=new TestRegistry();var provider=SdkTracerProvider.builder().build()) {
            var catalog=mock(HttpRouteCatalog.class);when(catalog.safe(any())).thenReturn("UNMAPPED");
            var filter=new TelemetryFilter(new HttpTelemetry(registry,provider.get("test")),catalog,registry);
            assertThatThrownBy(()->filter.doFilter(new MockHttpServletRequest("GET","/secret"),new MockHttpServletResponse(),(r,s)->{throw new java.io.IOException("PRIVATE_EXCEPTION");})).isInstanceOf(java.io.IOException.class);
            assertThat(MDC.get("traceId")).isNull();assertThat(registry.get("bank.http.server.active").gauge().value()).isZero();
            assertThat(registry.get("bank.http.server.duration").tag("outcome","SERVER_ERROR").timer().count()).isEqualTo(1);
        }
    }
    @Test void invalidSnapshotCannotOverwritePreviouslyVerifiedCounts() {
        try(var registry=new TestRegistry()) {
            var valid=new AtomicBoolean(true);var cache=new OperationalSnapshots(()->valid.get()?counts(2):Map.of("externalUnknown",999L),registry,new MutableClock(),1000);
            cache.refresh();valid.set(false);var invalid=cache.refresh();assertThat(invalid.available()).isFalse();assertThat(invalid.counts().get("externalUnknown")).isEqualTo(2);
        }
    }
    private static Map<String,Long> counts(long value){return OperationalSnapshots.METRICS.keySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(k->k,k->value));}
    static class TestRegistry extends SimpleMeterRegistry implements AutoCloseable { }
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-11-05T00:00:00Z");
        public java.time.ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return now;}
    }
}
