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
@Tag(name = "Account Operations", description = "OPERATOR lifecycle controls; cannot change own account state")
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
    @Operation(operationId = "freezeAccount", summary = "Freeze an ACTIVE account",
            description = "OPERATOR other than owner. Reason CUSTOMER_REQUEST or SECURITY_REVIEW. Future debits and credits must both reject FROZEN.")
    @ApiResponse(responseCode = "200", description = "FROZEN; version incremented")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "ACCOUNT_VERSION_CONFLICT or ACCOUNT_STATE_INVALID")
    @ApiResponse(responseCode = "422", description = "ACCOUNT_REASON_INVALID or ACCOUNT_SELF_OPERATION_FORBIDDEN")
    public ResponseEntity<AccountResponse> freeze(@PathVariable UUID id, @Valid @RequestBody AccountStateRequest request) {
        return mutate(id, request, AccountAction.FREEZE);
    }

    @PostMapping(value = "/{id}/unfreeze", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "unfreezeAccount", summary = "Unfreeze a FROZEN account", description = "OPERATOR other than owner; REVIEW_CLEARED reason required.")
    @ApiResponse(responseCode = "200", description = "ACTIVE; version incremented")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "ACCOUNT_VERSION_CONFLICT or ACCOUNT_STATE_INVALID")
    @ApiResponse(responseCode = "422", description = "ACCOUNT_REASON_INVALID or ACCOUNT_SELF_OPERATION_FORBIDDEN")
    public ResponseEntity<AccountResponse> unfreeze(@PathVariable UUID id, @Valid @RequestBody AccountStateRequest request) {
        return mutate(id, request, AccountAction.UNFREEZE);
    }

    private ResponseEntity<AccountResponse> mutate(UUID id, AccountStateRequest request, AccountAction action) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AccountResponse.from(
                accounts.operate(id, request.expectedVersion(), action, request.reason())));
    }
}
