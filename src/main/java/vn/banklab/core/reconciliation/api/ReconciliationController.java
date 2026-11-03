package vn.banklab.core.reconciliation.api;

import java.net.URI;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import vn.banklab.core.reconciliation.application.ReconciliationService;
import vn.banklab.core.reconciliation.application.ReconciliationRepository;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value="/api/v1/operations/reconciliation/runs",produces="application/json")
@ApiErrorResponses
public class ReconciliationController {
    private final ReconciliationService service;
    public ReconciliationController(ReconciliationService service) {this.service=service;}
    @PostMapping
    @Operation(summary="Run snapshot reconciliation",description="OPERATOR, no request body. Persist report/findings/audit atomically in REPEATABLE READ. ERROR means structural mismatch; WARNING includes aged eventual-delivery backlog. No repair or financial mutation. Concurrent run returns 409 RECONCILIATION_IN_PROGRESS.")
    @ApiResponse(responseCode="201",description="Immutable CLEAN, WARNING or MISMATCH report; each invocation creates a distinct run")
    public ResponseEntity<ReconciliationService.Report> run() {
        var r=service.run();return ResponseEntity.created(URI.create("/api/v1/operations/reconciliation/runs/"+r.run().id())).cacheControl(CacheControl.noStore()).body(r);
    }
    @GetMapping("/{id}")
    @Operation(summary="Read a completed immutable report",description="OPERATOR only; 404 RECONCILIATION_NOT_FOUND. Status does not change after later deliveries or repairs.")
    public ResponseEntity<ReconciliationService.Report> get(@PathVariable UUID id) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));}
    @GetMapping("/{id}/findings")
    @Operation(summary="Read bounded findings for one immutable report",description="OPERATOR, limit 1–200/default 100. Pass nextCursor as afterId for the next page. 422 RECONCILIATION_PAGE_INVALID for invalid bounds.")
    public ResponseEntity<ReconciliationService.Page<ReconciliationRepository.Finding>> findings(@PathVariable UUID id,
            @RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="100") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.findings(id,afterId,limit));
    }
}
