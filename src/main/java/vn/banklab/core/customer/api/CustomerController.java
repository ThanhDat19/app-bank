package vn.banklab.core.customer.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.banklab.core.customer.application.CustomerService;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value = "/api/v1/customers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Customer", description = "Own synthetic profile; requires CUSTOMER client role")
@ApiErrorResponses
public class CustomerController {
    private final CustomerService customers;

    public CustomerController(CustomerService customers) { this.customers = customers; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createMyCustomer", summary = "Create your synthetic customer profile",
            description = "CUSTOMER only. Owner comes from verified JWT. Initial status PENDING, version 0. Unknown fields rejected.")
    @ApiResponse(responseCode = "201", description = "Profile created; Location identifies the owned resource")
    @ApiResponse(responseCode = "400", description = "Invalid displayName or unknown request fields")
    @ApiResponse(responseCode = "409", description = "CUSTOMER_ALREADY_EXISTS: this identity already has a profile; GET /customers/me")
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request) {
        var customer = customers.createMine(request.displayName());
        return ResponseEntity.created(URI.create("/api/v1/customers/" + customer.id())).cacheControl(CacheControl.noStore())
                .body(CustomerResponse.from(customer));
    }

    @GetMapping("/me")
    @Operation(operationId = "getMyCustomer", summary = "Get your synthetic customer profile")
    @ApiResponse(responseCode = "200", description = "Own profile; CUSTOMER only")
    @ApiResponse(responseCode = "404", description = "CUSTOMER_NOT_FOUND: no profile for this identity")
    public ResponseEntity<CustomerResponse> mine() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CustomerResponse.from(customers.getMine()));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getOwnedCustomer", summary = "Get a customer profile you own",
            description = "CUSTOMER only. A missing profile and a profile owned by another identity both return 404.")
    @ApiResponse(responseCode = "200", description = "Own profile")
    @ApiResponse(responseCode = "404", description = "CUSTOMER_NOT_FOUND within ownership scope")
    public ResponseEntity<CustomerResponse> owned(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CustomerResponse.from(customers.getOwned(id)));
    }
}
