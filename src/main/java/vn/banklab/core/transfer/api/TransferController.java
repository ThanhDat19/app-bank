package vn.banklab.core.transfer.api;

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
import vn.banklab.core.shared.web.ApiErrorResponses;
import vn.banklab.core.transfer.application.TransferReceipt;
import vn.banklab.core.transfer.application.TransferService;

@RestController
@RequestMapping(value = "/api/v1/transfers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Internal Transfers", description = "CUSTOMER transfers from an owned account; atomic VND debit and credit")
@ApiErrorResponses
public class TransferController {
    private final TransferService transfers;
    public TransferController(TransferService transfers) { this.transfers = transfers; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createInternalTransfer", summary = "Transfer VND from your account or replay its receipt",
            description = "Both accounts must be distinct, ACTIVE and provisioned. Source ownership and balances are checked under ordered locks. Key scoped to verified issuer/subject and transfer operation, retained indefinitely. Same key/payload returns original receipt (also 201), changed valid payload returns 409. No fee or automatic provisioning.")
    @ApiResponse(responseCode = "201", description = "Immutable POSTED receipt; Location supports lookup after a lost response")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND, including a source account outside ownership scope")
    @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT or ACCOUNT_STATE_INVALID")
    @ApiResponse(responseCode = "422", description = "TRANSFER_ACCOUNTS_INVALID, LEDGER_NOT_PROVISIONED, LEDGER_LINK_INCONSISTENT, LEDGER_INSUFFICIENT_FUNDS or LEDGER_BALANCE_LIMIT")
    public ResponseEntity<TransferReceipt> create(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}")
            @Parameter(description = "Reuse exactly this key and payload after timeout. A new key creates a new transfer.",
                    schema = @Schema(pattern = "^[A-Za-z0-9._:-]{1,128}$", maxLength = 128)) String key,
            @Valid @RequestBody TransferRequest request) {
        var result = transfers.create(key, request.sourceAccountId(), request.destinationAccountId(), request.amount(), request.currency());
        return ResponseEntity.created(URI.create("/api/v1/transfers/" + result.id())).cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getMyInternalTransfer", summary = "Read a transfer receipt created by your verified identity")
    @ApiResponse(responseCode = "200", description = "Original POSTED receipt")
    @ApiResponse(responseCode = "404", description = "TRANSFER_NOT_FOUND, including another sender's transfer")
    public ResponseEntity<TransferReceipt> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(transfers.get(id));
    }
}
