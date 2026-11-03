package vn.banklab.core.ledger.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Trusted consumer contract; HTTP ownership belongs to notification reads. */
@Service
public class LedgerNotificationAccess {
    private final LedgerRepository ledger;
    public LedgerNotificationAccess(LedgerRepository ledger) { this.ledger=ledger; }
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public List<UUID> bankAccounts(UUID journalId) {
        Set<UUID> ids=ledger.postings(journalId).stream().map(LedgerPostingCommand.Posting::ledgerAccountId).collect(Collectors.toSet());
        return ledger.findAccounts(ids,false).stream().map(LedgerRepository.LedgerAccount::bankAccountId)
                .filter(java.util.Objects::nonNull).distinct().toList();
    }
}
