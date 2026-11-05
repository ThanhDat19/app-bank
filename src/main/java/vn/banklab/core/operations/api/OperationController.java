package vn.banklab.core.operations.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.banklab.core.operations.application.*;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value = "/api/v1/operations/requests", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Maker-checker", description = "Mandatory two-person OPERATOR controls; immutable target, approval and result")
@ApiErrorResponses
public class OperationController {
    public record Create(@NotNull @Pattern(regexp = "FREEZE|UNFREEZE|REVERSAL") String type, @NotNull UUID targetId,
                         @PositiveOrZero Long expectedAccountVersion, @NotNull @Size(max=32) String reason) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown operation field"); }
    }
    public record Approve(@NotNull @PositiveOrZero Long expectedVersion) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown approval field"); }
    }
    public record Decide(@NotNull @PositiveOrZero Long expectedVersion, @NotNull @Size(max=32) String reason) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown decision field"); }
    }
    private final OperationService service;
    public OperationController(OperationService service) { this.service = service; }
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createOperationRequest", summary = "Prepare a fixed operation without changing account state or money",
            description = "OPERATOR maker; Idempotency-Key scoped to issuer/subject. FREEZE/UNFREEZE require expectedAccountVersion; REVERSAL requires it absent. Different operator must approve.")
    @ApiResponse(responseCode = "201", description = "Durable intent; replay returns its current state")
    public ResponseEntity<OperationRequest> create(@RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9._:-]{1,128}") String key,
                                                  @Valid @RequestBody Create body) {
        var r = service.create(key, body.type(), body.targetId(), body.expectedAccountVersion(), body.reason());
        return ResponseEntity.created(URI.create("/api/v1/operations/requests/" + r.id())).cacheControl(CacheControl.noStore()).body(r);
    }
    @GetMapping("/{id}")
    @Operation(operationId="getOperationRequest", summary="Inspect an operational intent and its durable result; OPERATOR only")
    public ResponseEntity<OperationRequest> get(@PathVariable UUID id) { return ok(service.get(id)); }
    @GetMapping
    @Operation(operationId="listOperationRequests", summary="Latest bounded operational queue; newest first, OPERATOR only")
    public ResponseEntity<List<OperationRequest>> list(@RequestParam(defaultValue="PENDING") String status,
                                                      @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) { return ok(service.list(status, limit)); }
    @GetMapping("/{id}/history")
    @Operation(operationId="getOperationHistory", summary="Latest immutable decision history without raw identities")
    public ResponseEntity<List<OperationRepository.History>> history(@PathVariable UUID id,
                      @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) { return ok(service.history(id, limit)); }
    @PostMapping(value="/{id}/approve", consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="approveOperationRequest", summary="A distinct operator approves and executes atomically",
            description="Use pending version0. Failure rolls approval back to PENDING. Same checker retry after lost response returns stored result; another checker cannot claim the approval. Current account state/version, reservations and reversal financial rules remain authoritative.")
    @ApiResponse(responseCode="200", description="APPROVED with immutable execution result")
    @ApiResponse(responseCode="409", description="OPERATION_VERSION_CONFLICT, OPERATION_ALREADY_DECIDED or current account/reversal conflict")
    @ApiResponse(responseCode="422", description="Self approval/ownership or current financial rule rejected")
    public ResponseEntity<OperationRequest> approve(@PathVariable UUID id, @Valid @RequestBody Approve body) { return ok(service.approve(id, body.expectedVersion())); }
    @PostMapping(value="/{id}/reject", consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="rejectOperationRequest", summary="A distinct operator rejects with NOT_JUSTIFIED")
    public ResponseEntity<OperationRequest> reject(@PathVariable UUID id, @Valid @RequestBody Decide body) { return ok(service.decide(id, body.expectedVersion(), false, body.reason())); }
    @PostMapping(value="/{id}/cancel", consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="cancelOperationRequest", summary="Maker withdraws a pending request with MAKER_WITHDREW")
    public ResponseEntity<OperationRequest> cancel(@PathVariable UUID id, @Valid @RequestBody Decide body) { return ok(service.decide(id, body.expectedVersion(), true, body.reason())); }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
