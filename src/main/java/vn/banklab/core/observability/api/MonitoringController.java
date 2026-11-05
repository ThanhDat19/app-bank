package vn.banklab.core.observability.api;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.banklab.core.observability.application.*;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value="/api/v1/operations/monitoring",produces="application/json")
@Tag(name="Monitoring",description="OPERATOR-only safe aggregate diagnostics; never financial mutation")
@ApiErrorResponses
public class MonitoringController {
    private final MonitoringService service;
    public MonitoringController(MonitoringService service) { this.service = service; }
    @GetMapping("/traces")
    @Operation(operationId="getRecentSafeTraces",summary="Latest sampled server spans; bounded volatile buffer without identities or payloads")
    public ResponseEntity<List<RecentTraces.Trace>> traces(@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) { return ok(service.traces(limit)); }
    @GetMapping("/snapshot")
    @Operation(operationId="getOperationalSnapshot",summary="Cached last-success aggregate counts, availability and staleness; no DB query on this GET")
    public ResponseEntity<OperationalSnapshots.Snapshot> snapshot() { return ok(service.snapshot()); }
    @PostMapping("/snapshot/refresh")
    @Operation(operationId="refreshOperationalSnapshot",summary="Refresh read-only operational counts with statement timeout",
            description="Returns200 diagnostic state even on read failure: available=false, stale=true, previous counts preserved. Concurrent refresh409. No write to financial/schema data; pool acquisition uses datasource limits.")
    public ResponseEntity<OperationalSnapshots.Snapshot> refresh() { return ok(service.refresh()); }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
