package vn.banklab.core.reversal.api;

import java.net.URI;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.banklab.core.reversal.application.ReversalReceipt;
import vn.banklab.core.reversal.application.ReversalService;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value = "/api/v1/operations/reversals", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Full Reversals", description = "OPERATOR only; one immutable full inverse of a posted cash-in or internal transfer")
@ApiErrorResponses
public class ReversalController {
    private final ReversalService reversals;
    public ReversalController(ReversalService reversals) { this.reversals = reversals; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createFullReversal", summary = "Fully reverse a public posted cash-in or transfer once",
            description = "No amount/sides from caller. Original remains immutable. Same actor/key/payload replays 201; changed payload conflicts. A different key/operator cannot reverse the original twice. Requires ACTIVE provisioned accounts and sufficient balances; no overdraft or state override.")
    @ApiResponse(responseCode = "201", description = "POSTED inverse receipt with Location; same status/body on replay")
    @ApiResponse(responseCode = "404", description = "REVERSAL_ORIGINAL_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT, REVERSAL_ALREADY_EXISTS or ACCOUNT_STATE_INVALID")
    @ApiResponse(responseCode = "422", description = "REVERSAL_NOT_SUPPORTED, LEDGER_INSUFFICIENT_FUNDS, LEDGER_BALANCE_LIMIT or inconsistent linkage")
    public ResponseEntity<ReversalReceipt> create(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}")
            @Parameter(description = "Reuse the same key and payload after timeout; retained per verified operator identity and reversal operation",
                    schema = @Schema(pattern = "^[A-Za-z0-9._:-]{1,128}$", maxLength = 128)) String key,
            @Valid @RequestBody ReversalRequest request) {
        var result = reversals.create(key, request.originalJournalId(), request.reason());
        return ResponseEntity.created(URI.create("/api/v1/operations/reversals/" + result.id()))
                .cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getMyFullReversal", summary = "Read the original receipt created by this operator identity")
    @ApiResponse(responseCode = "200", description = "Original POSTED reversal receipt")
    @ApiResponse(responseCode = "404", description = "REVERSAL_NOT_FOUND, including another operator's receipt")
    public ResponseEntity<ReversalReceipt> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reversals.get(id));
    }
}
