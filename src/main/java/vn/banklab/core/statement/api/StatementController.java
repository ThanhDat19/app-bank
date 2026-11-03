package vn.banklab.core.statement.api;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import vn.banklab.core.statement.application.StatementRepository;
import vn.banklab.core.statement.application.StatementService;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
@ApiErrorResponses
public class StatementController {
    public record StatementRequest(@NotNull LocalDate fromDate,@NotNull LocalDate toDate,@NotBlank String timezone) { }
    private final StatementService service;
    public StatementController(StatementService service){this.service=service;}
    @PostMapping(value="/history-snapshots",produces="application/json")
    @Operation(summary="Create an immutable snapshot of your committed account history",description="CUSTOMER ownership. No body. One row per POSTED posting, deterministic time/journal/posting ordering. REPEATABLE READ materialization; later/backdated commits do not change pages. Frozen/closed readable; unprovisioned 422. No financial writes. Capped entry count; each POST creates a new snapshot.")
    @ApiResponse(responseCode="201",description="READY snapshot header with exact integer-string totals and Location")
    public ResponseEntity<StatementRepository.Header> history(@PathVariable UUID accountId){return created(accountId,service.history(accountId));}
    @PostMapping(value="/statements",consumes="application/json",produces="application/json")
    @Operation(summary="Create a dated statement in an explicit timezone",description="Local date interval [fromDate,toDate), 1–366 days. Java ZoneId start-of-day (DST-aware); effective end capped at database snapshot time, future-start periods rejected. Interim=true if requested end is future. Signed exact VND opening/running/closing; totals include separate fee/reversal journals, not holds/available balance.")
    @ApiResponse(responseCode="201",description="Immutable statement header; subsequent reads recheck current account ownership")
    public ResponseEntity<StatementRepository.Header> statement(@PathVariable UUID accountId,@Valid @RequestBody StatementRequest request){return created(accountId,service.statement(accountId,request.fromDate(),request.toDate(),request.timezone()));}
    @GetMapping(value="/statements/{id}",produces="application/json")
    @Operation(summary="Read your immutable history/statement header",description="CUSTOMER ownership checked for account and snapshot binding. Unknown/cross-account snapshot 404 ACCOUNT_SNAPSHOT_NOT_FOUND.")
    public ResponseEntity<StatementRepository.Header> get(@PathVariable UUID accountId,@PathVariable UUID id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(accountId,id));}
    @GetMapping(value="/statements/{id}/entries",produces="application/json")
    @Operation(summary="Read a stable page of immutable entries",description="afterSequence >=0, limit 1–200/default 100; pass nextAfterSequence to continue. Entries are account-side postings only; no counterparty identity or current balance. 422 HISTORY_PAGE_INVALID on bounds.")
    public ResponseEntity<StatementService.Page> entries(@PathVariable UUID accountId,@PathVariable UUID id,
            @RequestParam(defaultValue="0") long afterSequence,@RequestParam(defaultValue="100") int limit){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.entries(accountId,id,afterSequence,limit));}
    @GetMapping(value="/statements/{id}/export.csv",produces="text/csv")
    @Operation(summary="Download your immutable entry rows as UTF-8 CSV",description="CUSTOMER ownership rechecked; fixed UUID filename and fixed columns, no user-provided text/formulas. Metadata/opening/closing available through header route. No certified/PDF statement claim.")
    public ResponseEntity<byte[]> csv(@PathVariable UUID accountId,@PathVariable UUID id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("statement-"+id+".csv").build().toString())
                .body(service.csv(accountId,id).getBytes(StandardCharsets.UTF_8));
    }
    private ResponseEntity<StatementRepository.Header> created(UUID accountId,StatementRepository.Header header){
        return ResponseEntity.created(URI.create("/api/v1/accounts/"+accountId+"/statements/"+header.id())).cacheControl(CacheControl.noStore()).body(header);
    }
}
