package vn.banklab.core.observability.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.banklab.core.shared.application.BusinessException;

@Service
public class OperationalSnapshots {
    public record Snapshot(Instant lastAttemptAt, Instant lastSuccessAt, boolean available, boolean stale,
                           String problem, Map<String, Long> counts) { }
    private record Cached(Instant attempt, Instant success, boolean available, Map<String, Long> counts) { }
    public static final Map<String,String> METRICS = Map.of(
            "outboxPending", "bank.backlog.outbox.pending", "outboxLeased", "bank.backlog.outbox.leased",
            "outboxOldestSeconds", "bank.backlog.outbox.oldest", "notificationRetries", "bank.backlog.notifications.retry",
            "notificationQuarantined", "bank.backlog.notifications.quarantined", "externalUnknown", "bank.backlog.external.unknown",
            "externalPrepared", "bank.backlog.external.prepared", "operationsPending", "bank.backlog.operations.pending");
    private final SnapshotQuery query;
    private final Clock clock;
    private final long maxAgeMillis;
    private final ReentrantLock refresh = new ReentrantLock();
    private volatile Cached cached = new Cached(null, null, false, Map.of());
    public OperationalSnapshots(SnapshotQuery query, MeterRegistry meters, @Qualifier("monitoringClock") Clock clock,
                                @Value("${bank.monitoring.snapshot-max-age-millis:60000}") long maxAgeMillis) {
        if (maxAgeMillis < 100 || maxAgeMillis > 3600000) throw new IllegalArgumentException("Invalid snapshot max age");
        this.query = query; this.clock = clock; this.maxAgeMillis = maxAgeMillis;
        Gauge.builder("bank.monitoring.available", this, s -> s.cached.available ? 1 : 0).register(meters);
        Gauge.builder("bank.monitoring.snapshot.age", this, s -> s.ageSeconds()).register(meters);
        METRICS.forEach((key, metric) -> Gauge.builder(metric, this, s -> s.value(key)).register(meters));
    }
    public Snapshot current() {
        var c = cached;
        boolean stale = !c.available || c.success == null || Duration.between(c.success, clock.instant()).toMillis() > maxAgeMillis;
        return new Snapshot(c.attempt, c.success, c.available, stale, c.available ? null : "MONITORING_SNAPSHOT_UNAVAILABLE", c.counts);
    }
    public Snapshot refresh() {
        if (!refresh.tryLock()) throw new BusinessException("MONITORING_REFRESH_BUSY", BusinessException.Kind.CONFLICT);
        Instant attempted = clock.instant();
        try {
            var counts = Map.copyOf(query.read());
            if (!counts.keySet().equals(METRICS.keySet()) || counts.values().stream().anyMatch(v -> v < 0))
                throw new IllegalStateException("Invalid monitoring snapshot");
            cached = new Cached(attempted, clock.instant(), true, counts);
        } catch (RuntimeException unavailable) {
            var previous = cached;
            cached = new Cached(attempted, previous.success, false, previous.counts);
        } finally { refresh.unlock(); }
        return current();
    }
    private double value(String key) { var c = cached; return c.success == null ? Double.NaN : c.counts.get(key); }
    private double ageSeconds() { var c = cached; return c.success == null ? Double.NaN : Math.max(0, Duration.between(c.success, clock.instant()).toMillis()) / 1000d; }
}
