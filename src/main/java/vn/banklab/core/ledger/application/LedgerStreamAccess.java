package vn.banklab.core.ledger.application;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal read hint, not a balance. Caller must authorize the account in the same transaction. */
@Service
public class LedgerStreamAccess {
    public record Revision(long postings, long activeHolds, BigDecimal reserved) { }
    private final LedgerRepository ledger;

    public LedgerStreamAccess(LedgerRepository ledger) { this.ledger = ledger; }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Revision revision(UUID authorizedAccountId) { return ledger.streamRevision(authorizedAccountId); }
}
