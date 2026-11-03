package vn.banklab.core.external.api;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.banklab.core.external.application.*;
import vn.banklab.core.shared.web.ApiErrorResponses;
import vn.banklab.core.shared.web.ApiProblem;

@RestController
@RequestMapping(value="/api/v1/external-payments",produces="application/json")
@ApiErrorResponses
public class ExternalPaymentController {
    public record ExternalRequest(@NotNull UUID accountId,@NotNull @Pattern(regexp="SIM_[A-Z0-9_-]{1,40}") String beneficiary,
            @NotNull @Pattern(regexp="[1-9][0-9]{0,19}") @JsonDeserialize(using=ExternalAmountDeserializer.class) @Schema(type="string",example="1000") String amount,
            @NotNull @Pattern(regexp="VND") String currency){
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("Unknown field");}
    }
    public record PaymentView(UUID id,UUID accountId,String beneficiary,String amount,String currency,String state,long version,
            String reservedAmount,UUID journalId,UUID partnerInstance,long partnerVersion,String partnerState,String problem,
            String riskOutcome,String riskPolicy,Instant createdAt,Instant updatedAt){
        static PaymentView from(ExternalPayment p){return new PaymentView(p.id(),p.accountId(),p.beneficiary(),p.amount(),"VND",p.state(),p.version(),
                p.state().equals("PREPARED")||p.state().equals("UNKNOWN")?p.reservedAmount():"0",p.journalId(),p.partnerInstance(),p.partnerVersion(),p.partnerState(),p.problem(),p.riskOutcome(),p.riskPolicy(),p.createdAt(),p.updatedAt());}
    }
    private final ExternalPaymentService service;
    private final ExternalPaymentStates states;
    public ExternalPaymentController(ExternalPaymentService service,ExternalPaymentStates states){this.service=service;this.states=states;}
    @PostMapping(consumes="application/json")
    @Operation(operationId="prepareExternalPayment",summary="Prepare a simulated external command and reserve funds",
            description="CUSTOMER source ownership; configured local partner and synthetic clearing account. Idempotency-Key scoped to verified issuer/subject; same payload returns existing current command before network/risk. New command checks risk then atomically reserves principal plus quoted fee.201 does not mean partner submission or settlement. Explicit dispatch required. Pending daily usage is not consumed; current fees/limits are checked again at settlement.")
    @ApiResponse(responseCode="201",description="PREPARED or existing command; Location supports status lookup")
    @ApiResponse(responseCode="409",description="IDEMPOTENCY_CONFLICT, ACCOUNT_STATE_INVALID or RISK_APPROVAL_EXPIRED")
    @ApiResponse(responseCode="422",description="Invalid intent, risk rejection, insufficient available funds, provisioning or outgoing limit violation")
    @ApiResponse(responseCode="503",description="EXTERNAL_DISABLED, EXTERNAL_PARTNER_UNAVAILABLE or risk preflight failure; new command not prepared",
            content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblem.class)))
    public ResponseEntity<PaymentView> create(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ExternalRequest request){
        var p=service.create(key,request.accountId(),request.beneficiary(),request.amount(),request.currency());
        return ResponseEntity.created(URI.create("/api/v1/external-payments/"+p.id())).cacheControl(CacheControl.noStore()).body(PaymentView.from(p));
    }
    @GetMapping("/{id}")
    @Operation(operationId="getMyExternalPayment",summary="Read your durable external command",
            description="CUSTOMER original issuer/subject only. Another actor/missing command404; OPERATOR-only403. UNKNOWN retains funds without expiry and is not a failed payment. No network call.")
    public ResponseEntity<PaymentView> get(@PathVariable UUID id){return response(states.get(id));}
    @PostMapping("/{id}/dispatch")
    @Operation(operationId="dispatchMyExternalPayment",summary="Claim one partner submission or query an ambiguous existing command",
            description="PREPARED→UNKNOWN commits before HTTP. Only the claim winner may PUT, pinned to the stored partner instance. UNKNOWN retries only GET; no blind resubmission. Timeout/404/restart/malformed evidence retains UNKNOWN/reservation.200 terminal,202 unresolved. A crash between claim and HTTP can require manual reconciliation; this API provides no forced resolution.")
    public ResponseEntity<PaymentView> dispatch(@PathVariable UUID id){return response(service.dispatch(id));}
    @PostMapping("/{id}/reconcile")
    @Operation(operationId="reconcileMyExternalPayment",summary="Query matching evidence and retry local settlement once",
            description="No partner submission. Matching terminal success atomically posts the clearing transfer/current fee and updates command; duplicate observations cannot post again. Definitive failure releases reservation. Frozen account/changed limits/SQL fault preserves UNKNOWN and funds with EXTERNAL_SETTLEMENT_BLOCKED. Wrong epoch/binding or stale/conflicting evidence cannot release money.")
    public ResponseEntity<PaymentView> reconcile(@PathVariable UUID id){return response(service.reconcile(id));}
    @PostMapping("/{id}/cancel")
    @Operation(operationId="cancelPreparedExternalPayment",summary="Cancel only a never-dispatched PREPARED command",
            description="Serialized with dispatch. UNKNOWN cannot be canceled or expired; another cancel on CANCELLED is idempotent. Invalid state409.")
    public ResponseEntity<PaymentView> cancel(@PathVariable UUID id){return response(states.cancel(id));}
    @GetMapping("/{id}/history")
    @Operation(operationId="getMyExternalPaymentHistory",summary="Read latest immutable command state history",
            description="Owned command; newest version first, limit1..100 default50. Version gaps reflect internal atomic settlement steps. No raw partner payload, key, JWT or identity fields.")
    public ResponseEntity<List<ExternalPaymentRepository.History>> history(@PathVariable UUID id,@RequestParam(defaultValue="50") int limit){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(states.history(id,limit));
    }
    private ResponseEntity<PaymentView> response(ExternalPayment p){return ResponseEntity.status(p.terminal()||p.state().equals("PREPARED")?200:202).cacheControl(CacheControl.noStore()).body(PaymentView.from(p));}
}
