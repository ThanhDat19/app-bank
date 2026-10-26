package vn.banklab.core.customer.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.banklab.core.customer.application.CustomerService;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value = "/api/v1/operations/customers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Customer Operations", description = "Simulated KYC review; requires OPERATOR client role")
@ApiErrorResponses
public class CustomerOperationsController {
    private final CustomerService customers;

    public CustomerOperationsController(CustomerService customers) { this.customers = customers; }

    @GetMapping("/{id}")
    @Operation(operationId = "getCustomerForReview", summary = "Get a synthetic profile for operator review")
    @ApiResponse(responseCode = "200", description = "Profile; does not expose identity links")
    @ApiResponse(responseCode = "404", description = "CUSTOMER_NOT_FOUND")
    public ResponseEntity<CustomerResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(CustomerResponse.from(customers.getForOperations(id)));
    }

    @PostMapping(value = "/{id}/kyc-decisions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "decideCustomerKyc", summary = "Record a simulated KYC decision",
            description = "OPERATOR only; cannot review own identity. PENDING to VERIFIED or REJECTED once. "
                    + "Send current version. Decision and reviewer history commit atomically. Not real eKYC.")
    @ApiResponse(responseCode = "200", description = "Decision committed; returns profile with incremented version")
    @ApiResponse(responseCode = "400", description = "Invalid enum/version, missing fields or unknown fields")
    @ApiResponse(responseCode = "404", description = "CUSTOMER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "CUSTOMER_VERSION_CONFLICT or KYC_ALREADY_DECIDED; reload profile, do not blindly retry")
    @ApiResponse(responseCode = "422", description = "KYC_SELF_REVIEW_FORBIDDEN or KYC_REASON_INVALID")
    public ResponseEntity<CustomerResponse> decide(@PathVariable UUID id, @Valid @RequestBody KycDecisionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CustomerResponse.from(
                customers.decide(id, request.expectedVersion(), request.decision(), request.reason())));
    }
}
