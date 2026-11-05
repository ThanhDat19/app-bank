package vn.banklab.core.observability.application;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
@PreAuthorize("hasRole('OPERATOR')")
public class MonitoringService {
    private final RecentTraces traces;
    private final OperationalSnapshots snapshots;
    public MonitoringService(RecentTraces traces, OperationalSnapshots snapshots) { this.traces = traces; this.snapshots = snapshots; }
    public List<RecentTraces.Trace> traces(int limit) { return traces.latest(limit); }
    public OperationalSnapshots.Snapshot snapshot() { return snapshots.current(); }
    public OperationalSnapshots.Snapshot refresh() { return snapshots.refresh(); }
}
