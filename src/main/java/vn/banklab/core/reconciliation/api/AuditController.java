package vn.banklab.core.reconciliation.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import vn.banklab.core.reconciliation.application.ReconciliationService;
import vn.banklab.core.reconciliation.application.ReconciliationRepository;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value="/api/v1/operations/audit-events",produces="application/json")
@ApiErrorResponses
public class AuditController {
    private final ReconciliationService service;
    public AuditController(ReconciliationService service) {this.service=service;}
    @GetMapping
    @Operation(summary="Browse actor/action/time audit records",description="OPERATOR only, no-store. Journals/reconciliation from V12 and operation request/decision history from V16; no historical identity inference. Limit 1–200/default 100; nextCursor is beforeId for older IDs. Concurrent commits may arrive out of ID order: this is browsing, not a reliable incremental export or tamper-proof audit stream.")
    public ResponseEntity<ReconciliationService.Page<ReconciliationRepository.Audit>> list(
            @RequestParam(defaultValue="9223372036854775807") long beforeId,@RequestParam(defaultValue="100") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.audit(beforeId,limit));
    }
}
