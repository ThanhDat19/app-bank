package vn.banklab.core.account.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.banklab.core.account.application.AccountService;
import vn.banklab.core.account.domain.AccountAction;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value = "/api/v1/operations/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Account Operations", description = "OPERATOR metadata; state changes require the maker-checker requests API")
@ApiErrorResponses
public class AccountOperationsController {
    private final AccountService accounts;

    public AccountOperationsController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getAccountForOperations", summary = "Get account metadata for operations")
    @ApiResponse(responseCode = "200", description = "Account metadata")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND")
    public ResponseEntity<AccountResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AccountResponse.from(accounts.forOperations(id)));
    }

    @PostMapping(value = "/{id}/freeze", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "freezeAccount", summary = "Legacy direct freeze is disabled", deprecated = true,
            description = "Returns OPERATION_APPROVAL_REQUIRED. Create FREEZE at /api/v1/operations/requests, then a different operator approves.")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "OPERATION_APPROVAL_REQUIRED")
    @ApiResponse(responseCode = "422", description = "ACCOUNT_REASON_INVALID or ACCOUNT_SELF_OPERATION_FORBIDDEN")
    public ResponseEntity<AccountResponse> freeze(@PathVariable UUID id, @Valid @RequestBody AccountStateRequest request) {
        return mutate(id, request, AccountAction.FREEZE);
    }

    @PostMapping(value = "/{id}/unfreeze", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "unfreezeAccount", summary = "Legacy direct unfreeze is disabled", deprecated = true,
            description = "Returns OPERATION_APPROVAL_REQUIRED. Use UNFREEZE maker-checker request with REVIEW_CLEARED.")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "OPERATION_APPROVAL_REQUIRED")
    @ApiResponse(responseCode = "422", description = "ACCOUNT_REASON_INVALID or ACCOUNT_SELF_OPERATION_FORBIDDEN")
    public ResponseEntity<AccountResponse> unfreeze(@PathVariable UUID id, @Valid @RequestBody AccountStateRequest request) {
        return mutate(id, request, AccountAction.UNFREEZE);
    }

    private ResponseEntity<AccountResponse> mutate(UUID id, AccountStateRequest request, AccountAction action) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AccountResponse.from(
                accounts.operate(id, request.expectedVersion(), action, request.reason())));
    }
}
