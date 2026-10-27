package vn.banklab.core.ledger.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.banklab.core.ledger.application.LedgerProvisioningService;
import vn.banklab.core.ledger.application.LedgerQueryService;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value = "/api/v1/ledger/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Ledger", description = "Own ledger linkage and authoritative posted balance; CUSTOMER required")
@ApiErrorResponses
public class LedgerController {
    private final LedgerProvisioningService provisioning;
    private final LedgerQueryService queries;

    public LedgerController(LedgerProvisioningService provisioning, LedgerQueryService queries) {
        this.provisioning = provisioning;
        this.queries = queries;
    }

    @PostMapping("/{accountId}/provision")
    @Operation(operationId = "provisionMyLedger", summary = "Link your ACTIVE account to the ledger",
            description = "No request body. Idempotent provisioning, not cash-in. Link and marker commit atomically; no opening money is created.")
    @ApiResponse(responseCode = "200", description = "Existing or newly created VND LIABILITY/CREDIT ledger link")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND within ownership scope")
    @ApiResponse(responseCode = "409", description = "ACCOUNT_STATE_INVALID: FROZEN/CLOSED cannot provision")
    @ApiResponse(responseCode = "422", description = "LEDGER_LINK_INCONSISTENT")
    public ResponseEntity<LedgerAccountResponse> provision(@PathVariable UUID accountId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(LedgerAccountResponse.from(provisioning.provisionMine(accountId)));
    }

    @GetMapping("/{accountId}/balance")
    @Operation(operationId = "getMyPostedBalance", summary = "Get your authoritative posted ledger balance",
            description = "Derived from POSTED entries. No available balance is claimed until holds are implemented.")
    @ApiResponse(responseCode = "200", description = "Posted balance in VND minor units")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND within ownership scope")
    @ApiResponse(responseCode = "422", description = "LEDGER_NOT_PROVISIONED or LEDGER_LINK_INCONSISTENT")
    public ResponseEntity<LedgerBalanceResponse> balance(@PathVariable UUID accountId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(LedgerBalanceResponse.from(queries.mine(accountId)));
    }
}
