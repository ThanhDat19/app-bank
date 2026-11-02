package vn.banklab.core.ledger.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.shared.application.BusinessException;

@Service
@PreAuthorize("hasRole('CUSTOMER')")
@Transactional(isolation = Isolation.READ_COMMITTED)
public class HoldService {
    private final HoldRepository holds;
    private final LedgerRepository ledger;
    private final AccountFinancialAccess accounts;
    private final CurrentActorProvider actors;
    private final LedgerTransferAccess transfers;

    public HoldService(HoldRepository holds, LedgerRepository ledger, AccountFinancialAccess accounts,
                       CurrentActorProvider actors, LedgerTransferAccess transfers) {
        this.holds = holds;
        this.ledger = ledger;
        this.accounts = accounts;
        this.actors = actors;
        this.transfers = transfers;
    }

    public HoldRepository.Hold place(String key, UUID accountId, UUID destinationId, String amount, Instant expiry) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw rule("IDEMPOTENCY_KEY_INVALID");
        if (accountId == null || destinationId == null || accountId.equals(destinationId) || amount == null
                || !amount.matches("[1-9][0-9]{0,19}") || expiry == null) throw rule("HOLD_REQUEST_INVALID");
        var expiresAt = expiry.truncatedTo(ChronoUnit.MICROS);
        var hold = holds.claim(actors.currentActor(), key, accountId, destinationId, new BigDecimal(amount), expiresAt);
        if (!hold.accountId().equals(accountId) || !hold.destinationAccountId().equals(destinationId)
                || hold.amount().compareTo(new BigDecimal(amount)) != 0 || !hold.expiresAt().equals(expiresAt)) {
            throw conflict("IDEMPOTENCY_CONFLICT");
        }
        if (!hold.state().equals("PENDING")) return effective(hold);
        var pair = accounts.lockOwnedTransferPair(accountId, destinationId);
        var account = pair.stream().filter(a -> a.id().equals(accountId)).findFirst().orElseThrow();
        var link = ledger.findForBank(accountId).orElseThrow(() -> rule("LEDGER_NOT_PROVISIONED"));
        if (!account.ledgerProvisioned()) throw rule("LEDGER_LINK_INCONSISTENT");
        var now = holds.now(); // Read after waiting for the account lock.
        if (!expiresAt.isAfter(now)) throw rule("HOLD_EXPIRY_INVALID");
        if (ledger.balance(link.id()).subtract(holds.reserved(accountId)).compareTo(hold.amount()) < 0)
            throw rule("LEDGER_INSUFFICIENT_FUNDS");
        holds.transition(hold.id(), "ACTIVE", null);
        return get(hold.id());
    }

    @Transactional(readOnly = true)
    public HoldRepository.Hold get(UUID id) { return effective(owned(id, false)); }

    public HoldRepository.Hold release(UUID id) {
        var hold = owned(id, true);
        if (hold.state().equals("RELEASED") || hold.state().equals("EXPIRED")) return hold;
        if (!hold.state().equals("ACTIVE")) throw conflict("HOLD_STATE_INVALID");
        // Release is safe even when frozen; the account lock serializes it with all money writers.
        accounts.lockForReservationRelease(hold.accountId());
        holds.transition(id, hold.expiresAt().isAfter(holds.now()) ? "RELEASED" : "EXPIRED", null);
        return get(id);
    }

    public HoldRepository.Hold capture(UUID id) {
        var hold = owned(id, true);
        if (hold.state().equals("CAPTURED")) return hold;
        if (!hold.state().equals("ACTIVE")) throw conflict("HOLD_STATE_INVALID");
        // Lock the complete pair before changing the reservation. Never lock source first.
        accounts.lockOwnedTransferPair(hold.accountId(), hold.destinationAccountId());
        if (!hold.expiresAt().isAfter(holds.now())) throw conflict("HOLD_EXPIRED");
        // The intermediate state cannot commit: deferred SQL validation requires the exact posted journal.
        holds.transition(id, "CAPTURED", null);
        UUID journal = transfers.post(hold.accountId(), hold.destinationAccountId(), id, hold.amount());
        holds.transition(id, "CAPTURED", journal);
        return get(id);
    }

    private HoldRepository.Hold owned(UUID id, boolean lock) {
        return holds.owned(id, actors.currentActor(), lock).orElseThrow(() ->
                new BusinessException("HOLD_NOT_FOUND", BusinessException.Kind.NOT_FOUND));
    }

    private HoldRepository.Hold effective(HoldRepository.Hold hold) {
        if (hold.state().equals("ACTIVE") && !hold.expiresAt().isAfter(holds.now()))
            return new HoldRepository.Hold(hold.id(), hold.accountId(), hold.destinationAccountId(), hold.amount(),
                    hold.expiresAt(), "EXPIRED", null);
        return hold;
    }

    private static BusinessException rule(String code) { return new BusinessException(code, BusinessException.Kind.RULE_VIOLATION); }
    private static BusinessException conflict(String code) { return new BusinessException(code, BusinessException.Kind.CONFLICT); }
}
