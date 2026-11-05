package vn.banklab.core.observability.application;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.banklab.core.shared.application.BusinessException;

/** Bounded, volatile, selected-field trace buffer; never accepts arbitrary attributes or payloads. */
@Component
public class RecentTraces {
    public record Trace(String traceId, String spanId, String method, String route, int status,
                        String outcome, Instant completedAt, double durationMillis) { }
    private final int capacity;
    private final ArrayDeque<Trace> traces = new ArrayDeque<>();
    public RecentTraces(@Value("${bank.monitoring.trace-capacity:200}") int capacity) {
        if (capacity < 1 || capacity > 2000) throw new IllegalArgumentException("Invalid trace capacity");
        this.capacity = capacity;
    }
    public synchronized void add(Trace trace) {
        if (traces.size() == capacity) traces.removeLast();
        traces.addFirst(trace);
    }
    public synchronized List<Trace> latest(int limit) {
        if (limit < 1 || limit > 100) throw new BusinessException("MONITORING_LIMIT_INVALID", BusinessException.Kind.RULE_VIOLATION);
        return traces.stream().limit(limit).toList();
    }
}
