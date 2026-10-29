package vn.banklab.core.ledger.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import vn.banklab.core.iam.application.Actor;
import vn.banklab.core.ledger.domain.JournalPlan;

public interface LedgerRepository {
    record Journal(UUID id, LedgerPostingCommand.Kind kind, String status) { }

    record LedgerAccount(UUID id, String code, UUID bankAccountId, String currency, String accountType,
                         String normalSide) {
    }

    Optional<LedgerAccount> findForBank(UUID accountId);

    Optional<LedgerAccount> findCashClearing();

    LedgerAccount insertCustomer(UUID accountId, String currency, Instant now);

    List<LedgerAccount> findAccounts(Set<UUID> ids, boolean lock);

    BigDecimal balance(UUID ledgerId);

    boolean referenceExists(UUID referenceId);

    Optional<Journal> lockJournal(UUID journalId);

    List<LedgerPostingCommand.Posting> postings(UUID journalId);

    void insertJournal(UUID id, LedgerPostingCommand command, Actor actor, Instant now);

    void insertPostings(UUID journalId, JournalPlan plan);

    void finalizeJournal(UUID id, Instant now);
}
