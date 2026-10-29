package vn.banklab.core.reversal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.cashin.application.CashInService;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.ledger.application.LedgerReversalAccess;
import vn.banklab.core.shared.application.BusinessException;
import vn.banklab.core.transfer.application.TransferService;

@Service
@PreAuthorize("hasRole('OPERATOR')")
public class ReversalService {
    private static final Set<String> REASONS = Set.of("OPERATOR_ERROR", "DUPLICATE_PROCESSING");
    private final ReversalRepository reversals;
    private final CurrentActorProvider actors;
    private final LedgerReversalAccess ledger;
    private final CashInService cashIns;
    private final TransferService transfers;

    public ReversalService(ReversalRepository reversals, CurrentActorProvider actors, LedgerReversalAccess ledger,
                           CashInService cashIns, TransferService transfers) {
        this.reversals = reversals; this.actors = actors; this.ledger = ledger;
        this.cashIns = cashIns; this.transfers = transfers;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReversalReceipt create(String key, UUID originalId, String reason) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new BusinessException("IDEMPOTENCY_KEY_INVALID", BusinessException.Kind.RULE_VIOLATION);
        }
        if (originalId == null || reason == null || !REASONS.contains(reason)) {
            throw new BusinessException("REVERSAL_REQUEST_INVALID", BusinessException.Kind.RULE_VIOLATION);
        }
        var actor = actors.currentActor();
        var hash = hash("reversal:v1\n" + originalId + "\n" + reason);
        var claim = reversals.claim(actor, key, hash, originalId, reason);
        if (!claim.requestHash().equals(hash)) {
            throw new BusinessException("IDEMPOTENCY_CONFLICT", BusinessException.Kind.CONFLICT);
        }
        if (claim.receipt() != null) return claim.receipt();
        // Serialize ALL keys/operators for this original before taking bank/ledger locks.
        var original = ledger.lockOriginal(originalId);
        boolean eligible = "SIMULATED_CASH_IN".equals(original.kind())
                ? cashIns.hasPostedJournal(originalId) : transfers.hasPostedJournal(originalId);
        if (!eligible) throw new BusinessException("REVERSAL_NOT_SUPPORTED", BusinessException.Kind.RULE_VIOLATION);
        if (reversals.completedForOriginal(originalId)) {
            throw new BusinessException("REVERSAL_ALREADY_EXISTS", BusinessException.Kind.CONFLICT);
        }
        var journal = ledger.postInverse(originalId, claim.id());
        return reversals.complete(claim.id(), journal, original.kind(), original.amount());
    }

    @Transactional(readOnly = true)
    public ReversalReceipt get(UUID id) {
        return reversals.findOwned(id, actors.currentActor()).orElseThrow(() ->
                new BusinessException("REVERSAL_NOT_FOUND", BusinessException.Kind.NOT_FOUND));
    }

    private static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
