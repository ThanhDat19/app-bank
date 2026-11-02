package vn.banklab.core.transfer.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.ledger.application.LedgerTransferAccess;
import vn.banklab.core.shared.application.BusinessException;

@Service
@PreAuthorize("hasRole('CUSTOMER')")
public class TransferService {
    private final TransferRepository transfers;
    private final CurrentActorProvider actors;
    private final LedgerTransferAccess ledger;

    public TransferService(TransferRepository transfers, CurrentActorProvider actors, LedgerTransferAccess ledger) {
        this.transfers = transfers;
        this.actors = actors;
        this.ledger = ledger;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferReceipt create(String key, UUID sourceId, UUID destinationId, String amount, String currency) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new BusinessException("IDEMPOTENCY_KEY_INVALID", BusinessException.Kind.RULE_VIOLATION);
        }
        if (sourceId == null || destinationId == null || amount == null || !amount.matches("[1-9][0-9]{0,19}") || !"VND".equals(currency)) {
            throw new BusinessException("TRANSFER_REQUEST_INVALID", BusinessException.Kind.RULE_VIOLATION);
        }
        if (sourceId.equals(destinationId)) {
            throw new BusinessException("TRANSFER_ACCOUNTS_INVALID", BusinessException.Kind.RULE_VIOLATION);
        }
        var actor = actors.currentActor();
        var hash = hash("transfer:v1\n" + sourceId + "\n" + destinationId + "\nVND\n" + amount);
        var claim = transfers.claim(actor, key, hash, sourceId, destinationId, new BigDecimal(amount));
        if (!claim.requestHash().equals(hash)) {
            throw new BusinessException("IDEMPOTENCY_CONFLICT", BusinessException.Kind.CONFLICT);
        }
        // A committed transfer remains replayable after balances or account states change.
        if (claim.receipt() != null) return claim.receipt();
        var journalId = ledger.post(sourceId, destinationId, claim.id(), new BigDecimal(amount));
        return transfers.complete(claim.id(), journalId);
    }

    @Transactional(readOnly = true)
    public TransferReceipt get(UUID id) {
        return transfers.findOwned(id, actors.currentActor()).orElseThrow(() ->
                new BusinessException("TRANSFER_NOT_FOUND", BusinessException.Kind.NOT_FOUND));
    }

    /** Internal operator reversal eligibility; sender-only HTTP lookup remains unchanged. */
    @PreAuthorize("hasRole('OPERATOR')")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean hasPostedJournal(UUID id) {
        return transfers.hasPostedJournal(id);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
