package vn.banklab.core.transfer.api;

import java.net.URI;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.banklab.core.shared.web.ApiErrorResponses;
import vn.banklab.core.shared.web.ApiProblem;
import vn.banklab.core.transfer.application.TransferReceipt;
import vn.banklab.core.transfer.application.TransferService;
import vn.banklab.core.transfer.application.TransferRiskDecision;

@RestController
@RequestMapping(value = "/api/v1/transfers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Internal Transfers", description = "CUSTOMER transfers from an owned account; atomic VND debit and credit")
@ApiErrorResponses
public class TransferController {
    private final TransferService transfers;
    public TransferController(TransferService transfers) { this.transfers = transfers; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createInternalTransfer", summary = "Transfer VND from your account or replay its receipt",
            description = "Both accounts must be distinct, ACTIVE and provisioned. Source ownership, available balance and configured gross transaction/daily limits are checked under ordered locks. Any configured flat VND fee commits atomically with principal. Fee defaults to zero. Read /api/v1/ledger/accounts/{sourceAccountId}/outgoing-charges/{journalId} for the immutable fee snapshot. Key scoped to verified issuer/subject, retained indefinitely. Same key/payload returns original receipt (201) without another fee or daily usage; changed payload returns 409. No automatic provisioning. Optional local risk simulation evaluates principal before a new posting transaction; timeout/unavailable/invalid response fails closed with 503. Risk defaults to disabled, explicitly recorded as BYPASSED. GET /transfers/{id}/risk-decision reads the original immutable decision; committed replay needs no new RPC.")
    @ApiResponse(responseCode = "201", description = "Immutable POSTED receipt; Location supports lookup after a lost response")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND, including a source account outside ownership scope")
    @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT, ACCOUNT_STATE_INVALID or RISK_APPROVAL_EXPIRED; retry original key/payload")
    @ApiResponse(responseCode = "503", description = "RISK_TIMEOUT, RISK_UNAVAILABLE or RISK_RESPONSE_INVALID; no new money posted by this attempt; retry original key after backoff",
            content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class)))
    @ApiResponse(responseCode = "422", description = "RISK_REJECTED, TRANSFER_TRANSACTION_LIMIT, TRANSFER_DAILY_LIMIT, TRANSFER_ACCOUNTS_INVALID, LEDGER_NOT_PROVISIONED, LEDGER_LINK_INCONSISTENT, LEDGER_INSUFFICIENT_FUNDS or LEDGER_BALANCE_LIMIT")
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

    @GetMapping("/{id}/risk-decision")
    @Operation(operationId="getMyTransferRiskDecision",summary="Read the immutable risk policy receipt for your transfer",
            description="Only the verified sender identity can read. APPROVED binds the local gRPC simulation result; BYPASSED explicitly records disabled mode. No retroactive risk decisions for pre-V14 transfers. Not a production AML/fraud certificate.")
    @ApiResponse(responseCode="200",description="Exact decision retained with the original POSTED receipt")
    @ApiResponse(responseCode="404",description="TRANSFER_RISK_DECISION_NOT_FOUND, including another sender or legacy transfer",
            content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class)))
    public ResponseEntity<TransferRiskDecision> riskDecision(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(transfers.riskDecision(id));
    }
}
