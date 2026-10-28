package vn.banklab.core.cashin.application;

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
import vn.banklab.core.ledger.application.LedgerCashInAccess;
import vn.banklab.core.shared.application.BusinessException;

@Service
@PreAuthorize("hasRole('OPERATOR')")
public class CashInService {
    private final CashInRepository cashIns;
    private final CurrentActorProvider actors;
    private final LedgerCashInAccess ledger;

    public CashInService(CashInRepository cashIns, CurrentActorProvider actors, LedgerCashInAccess ledger) {
        this.cashIns = cashIns;
        this.actors = actors;
        this.ledger = ledger;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CashInReceipt create(String key, UUID accountId, String amount, String currency) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new BusinessException("IDEMPOTENCY_KEY_INVALID", BusinessException.Kind.RULE_VIOLATION);
        }
        if (accountId == null || amount == null || !amount.matches("[1-9][0-9]{0,19}") || !"VND".equals(currency)) {
            throw new BusinessException("CASH_IN_REQUEST_INVALID", BusinessException.Kind.RULE_VIOLATION);
        }
        var actor = actors.currentActor();
        var hash = hash("cash-in:v1\n" + accountId + "\nVND\n" + amount);
        // Unique insert waits for a concurrent owner to commit/rollback; never catch a unique violation
        // and continue inside an aborted PostgreSQL transaction.
        var claim = cashIns.claim(actor, key, hash, accountId, new BigDecimal(amount));
        if (!claim.requestHash().equals(hash)) {
            throw new BusinessException("IDEMPOTENCY_CONFLICT", BusinessException.Kind.CONFLICT);
        }
        // Replay precedes account-state checks: the original receipt survives a later freeze/close.
        if (claim.receipt() != null) {
            return claim.receipt();
        }
        var journalId = ledger.post(accountId, claim.id(), new BigDecimal(amount));
        return cashIns.complete(claim.id(), journalId);
    }

    @Transactional(readOnly = true)
    public CashInReceipt get(UUID id) {
        return cashIns.findOwned(id, actors.currentActor()).orElseThrow(() ->
                new BusinessException("CASH_IN_NOT_FOUND", BusinessException.Kind.NOT_FOUND));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
