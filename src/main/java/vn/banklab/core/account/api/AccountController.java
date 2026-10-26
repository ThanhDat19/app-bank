package vn.banklab.core.account.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.banklab.core.account.application.AccountService;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Account", description = "Own VND account lifecycle; CUSTOMER role required")
@ApiErrorResponses
public class AccountController {
    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "openMyAccount", summary = "Open your VND account",
            description = "Requires own VERIFIED Customer. One account per customer/currency, including closed accounts. No initial balance.")
    @ApiResponse(responseCode = "201", description = "ACTIVE/version 0; Location points to the owned account")
    @ApiResponse(responseCode = "400", description = "Invalid currency or unknown fields")
    @ApiResponse(responseCode = "404", description = "CUSTOMER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "ACCOUNT_ALREADY_EXISTS; query /accounts/me")
    @ApiResponse(responseCode = "422", description = "KYC_NOT_VERIFIED")
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        var account = accounts.open(request.currency());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.id())).cacheControl(CacheControl.noStore())
                .body(AccountResponse.from(account));
    }

    @GetMapping("/me")
    @Operation(operationId = "getMyAccounts", summary = "List your accounts", description = "At most one VND account in this MVP; absent profile/account returns an empty array.")
    @ApiResponse(responseCode = "200", description = "Owned accounts, including CLOSED")
    public ResponseEntity<List<AccountResponse>> mine() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.mine().stream().map(AccountResponse::from).toList());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getOwnedAccount", summary = "Get an account you own")
    @ApiResponse(responseCode = "200", description = "Owned account metadata")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND within ownership scope")
    public ResponseEntity<AccountResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AccountResponse.from(accounts.owned(id)));
    }

    @PostMapping(value = "/{id}/close", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "closeMyAccount", summary = "Close your ACTIVE account",
            description = "CUSTOMER_REQUEST only. FROZEN cannot close. Only accounts without ledger linkage can close in BL-005. "
                    + "Provisioned accounts are rejected until authoritative balance/hold/pending-payment checks are integrated.")
    @ApiResponse(responseCode = "200", description = "CLOSED, version incremented; terminal")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "ACCOUNT_VERSION_CONFLICT or ACCOUNT_STATE_INVALID")
    @ApiResponse(responseCode = "422", description = "ACCOUNT_REASON_INVALID or ACCOUNT_CLOSURE_UNAVAILABLE")
    public ResponseEntity<AccountResponse> close(@PathVariable UUID id, @Valid @RequestBody AccountStateRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(AccountResponse.from(accounts.close(id, request.expectedVersion(), request.reason())));
    }
}
