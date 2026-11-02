package vn.banklab.core.ledger.api;

import java.time.Instant;
import java.net.URI;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.banklab.core.ledger.application.HoldRepository;
import vn.banklab.core.ledger.application.HoldService;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value = "/api/v1/holds", produces = "application/json")
@Tag(name = "Holds", description = "CUSTOMER reservations and full capture to a fixed internal destination")
@ApiErrorResponses
public class HoldController {
    public record Request(@NotNull UUID accountId, @NotNull UUID destinationAccountId,
                          @NotNull @Pattern(regexp = "[1-9][0-9]{0,19}")
                          @JsonDeserialize(using = HoldAmountDeserializer.class) String amount,
                          @NotNull Instant expiresAt) { }
    public record Response(UUID id, UUID accountId, UUID destinationAccountId, String amount, String currency,
                           Instant expiresAt, String state, UUID journalId) {
        static Response from(HoldRepository.Hold h) {
            return new Response(h.id(), h.accountId(), h.destinationAccountId(), h.amount().toPlainString(), "VND",
                    h.expiresAt(), h.state(), h.journalId());
        }
    }

    private final HoldService service;
    public HoldController(HoldService service) { this.service = service; }

    @PostMapping
    @ApiResponse(responseCode = "201", description = "Existing or newly placed hold; Location identifies the hold")
    @Operation(summary = "Reserve VND without posting money", description = "Requires Idempotency-Key. Same key and payload replay; changed payload returns 409. ACTIVE owned provisioned source required. Amount is an integer string.")
    public ResponseEntity<Response> place(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Request request) {
        var hold = service.place(key, request.accountId(), request.destinationAccountId(), request.amount(), request.expiresAt());
        return ResponseEntity.created(URI.create("/api/v1/holds/" + hold.id())).cacheControl(CacheControl.noStore()).body(Response.from(hold));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read your hold", description = "Unknown or another actor's hold returns 404. Expired reservations cease reducing available balance automatically.")
    public ResponseEntity<Response> get(@PathVariable UUID id) { return response(service.get(id)); }

    @PostMapping("/{id}/release")
    @Operation(summary = "Release your reservation", description = "Idempotent release, including on a frozen account. Captured hold returns 409.")
    public ResponseEntity<Response> release(@PathVariable UUID id) { return response(service.release(id)); }

    @PostMapping("/{id}/capture")
    @Operation(summary = "Capture the full hold once", description = "Transfers the reserved amount to the fixed destination atomically with any configured fee and gross daily-limit usage. Fee must be available outside this hold. Both accounts must be ACTIVE and provisioned. Expired hold returns 409; retry returns the same journal without another fee. Read the source account outgoing-charges/{journalId} for the fee snapshot. Partial capture and reversal of captures are excluded.")
    public ResponseEntity<Response> capture(@PathVariable UUID id) { return response(service.capture(id)); }

    private ResponseEntity<Response> response(HoldRepository.Hold hold) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.from(hold));
    }
}
