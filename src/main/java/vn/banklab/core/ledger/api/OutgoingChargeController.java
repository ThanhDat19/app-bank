package vn.banklab.core.ledger.api;

import java.util.UUID;
import java.time.LocalDate;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import vn.banklab.core.ledger.application.OutgoingPolicyService;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping(value="/api/v1/ledger/accounts/{accountId}/outgoing-charges",produces="application/json")
@ApiErrorResponses
public class OutgoingChargeController {
    public record Charge(UUID journalId,String amount,String feeAmount,String totalDebit,LocalDate businessDate,String timezone,UUID feeJournalId) { }
    private final OutgoingPolicyService service;
    public OutgoingChargeController(OutgoingPolicyService service) { this.service=service; }

    @GetMapping("/{journalId}")
    @Operation(summary="Read the fee and daily-limit assessment for your outgoing journal",description="CUSTOMER source ownership required. Works for transfers and hold captures. Amounts are exact integer VND strings. Fee is retained on principal reversal; usage is not restored. Historical journals before V9 may have no assessment (404).")
    public ResponseEntity<Charge> get(@PathVariable UUID accountId,@PathVariable UUID journalId) {
        var c=service.mine(accountId,journalId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Charge(c.journalId(),c.amount().setScale(0).toPlainString(),
                c.fee().setScale(0).toPlainString(),c.totalDebit().setScale(0).toPlainString(),c.businessDate(),c.timezone(),c.feeJournalId()));
    }
}
