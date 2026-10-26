package vn.banklab.core.ledger.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.banklab.core.ledger.application.LedgerQueryService;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value = "/api/v1/operations/ledger/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Ledger Operations", description = "Read posted balances; OPERATOR required")
@ApiErrorResponses
public class LedgerOperationsController {
    private final LedgerQueryService queries;

    public LedgerOperationsController(LedgerQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/{accountId}/balance")
    @Operation(operationId = "getPostedBalanceForOperations", summary = "Read a customer posted balance for operations")
    @ApiResponse(responseCode = "200", description = "Authoritative posted balance")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND")
    @ApiResponse(responseCode = "422", description = "LEDGER_NOT_PROVISIONED or LEDGER_LINK_INCONSISTENT")
    public ResponseEntity<LedgerBalanceResponse> balance(@PathVariable UUID accountId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(LedgerBalanceResponse.from(queries.forOperations(accountId)));
    }
}
