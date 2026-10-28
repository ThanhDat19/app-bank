package vn.banklab.core.cashin.api;

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
import vn.banklab.core.cashin.application.CashInReceipt;
import vn.banklab.core.cashin.application.CashInService;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value = "/api/v1/operations/cash-ins", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Simulated Cash-in", description = "OPERATOR only; synthetic money with an explicit clearing counterpart")
@ApiErrorResponses
public class CashInController {
    private final CashInService cashIns;

    public CashInController(CashInService cashIns) {
        this.cashIns = cashIns;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createSimulatedCashIn", summary = "Post or replay a simulated VND cash-in",
            description = "Key scoped to verified issuer/subject and cash-in operation, retained indefinitely. Same key/payload replays the original receipt (also 201); changed payload returns 409. Failures roll back the key and all postings. No automatic provisioning.")
    @ApiResponse(responseCode = "201", description = "Immutable POSTED receipt; Location supports recovery after a lost response")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT or ACCOUNT_STATE_INVALID")
    @ApiResponse(responseCode = "422", description = "LEDGER_NOT_PROVISIONED, LEDGER_LINK_INCONSISTENT or LEDGER_BALANCE_LIMIT")
    public ResponseEntity<CashInReceipt> create(
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}")
            @Parameter(description = "Unique command key. Reuse this exact key after timeout; a new key creates new money.",
                    schema = @Schema(pattern = "^[A-Za-z0-9._:-]{1,128}$", maxLength = 128)) String key,
            @Valid @RequestBody CashInRequest request) {
        var result = cashIns.create(key, request.accountId(), request.amount(), request.currency());
        return ResponseEntity.created(URI.create("/api/v1/operations/cash-ins/" + result.id()))
                .cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getMySimulatedCashIn", summary = "Read the original receipt created by this operator identity")
    @ApiResponse(responseCode = "200", description = "Original POSTED receipt")
    @ApiResponse(responseCode = "404", description = "CASH_IN_NOT_FOUND, including another operator's receipt")
    public ResponseEntity<CashInReceipt> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cashIns.get(id));
    }
}
