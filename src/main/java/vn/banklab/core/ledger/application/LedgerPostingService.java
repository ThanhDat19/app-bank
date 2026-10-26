package vn.banklab.core.ledger.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.ledger.domain.JournalPlan;
import vn.banklab.core.ledger.domain.LedgerRuleViolation;
import vn.banklab.core.ledger.domain.PostingSide;
import vn.banklab.core.shared.application.BusinessException;

import static vn.banklab.core.shared.application.BusinessException.Kind.*;

/**
 * Internal posting primitive. Cash-in/transfer services own authorization, idempotency and the outer transaction.
 */
@Service
public class LedgerPostingService {
    private final LedgerRepository ledger;
    private final AccountFinancialAccess accounts;
    private final CurrentActorProvider actors;

    public LedgerPostingService(LedgerRepository ledger, AccountFinancialAccess accounts, CurrentActorProvider actors) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.actors = actors;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(LedgerPostingCommand command) {
        var actor = actors.currentActor();
        if (command == null || command.referenceId() == null || command.kind() == null) {
            throw new BusinessException("LEDGER_REQUEST_INVALID", RULE_VIOLATION);
        }
        JournalPlan plan;
        try {
            plan = new JournalPlan(command.postings() == null ? null : command.postings().stream().map(line -> line == null ? null :
                    new JournalPlan.Entry(line.ledgerAccountId(), line.side() == null ? null : PostingSide.valueOf(line.side().name()), line.amount())).toList());
        } catch (LedgerRuleViolation failure) {
            throw new BusinessException(switch (failure.rule()) {
                case INVALID_POSTINGS -> "LEDGER_POSTINGS_INVALID";
                case INVALID_AMOUNT -> "LEDGER_AMOUNT_INVALID";
                case UNBALANCED -> "LEDGER_JOURNAL_UNBALANCED";
            }, RULE_VIOLATION);
        }
        Set<UUID> ids = plan.entries().stream().map(JournalPlan.Entry::ledgerAccountId).collect(Collectors.toSet());
        var metadata = ledger.findAccounts(ids, false);
        if (metadata.size() != ids.size()) {
            throw new BusinessException("LEDGER_ACCOUNT_NOT_FOUND", NOT_FOUND);
        }
        Set<UUID> bankIds = metadata.stream().map(LedgerRepository.LedgerAccount::bankAccountId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        var lockedBank = accounts.lockActiveForPosting(bankIds);
        if (lockedBank.stream().anyMatch(account -> !account.ledgerProvisioned())) {
            throw new BusinessException("LEDGER_LINK_INCONSISTENT", RULE_VIOLATION);
        }
        var lockedLedger = ledger.findAccounts(ids, true);
        for (var account : lockedLedger) {
            if (!"VND".equals(account.currency())) {
                throw new BusinessException("LEDGER_CURRENCY_INVALID", RULE_VIOLATION);
            }
            if (account.bankAccountId() != null) {
                var delta = plan.entries().stream().filter(line -> line.ledgerAccountId().equals(account.id()))
                        .map(line -> line.side().name().equals(account.normalSide()) ? line.amount() : line.amount().negate())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                var resulting = ledger.balance(account.id()).add(delta);
                if (resulting.signum() < 0) {
                    throw new BusinessException("LEDGER_INSUFFICIENT_FUNDS", RULE_VIOLATION);
                }
                if (resulting.compareTo(JournalPlan.MAX_AMOUNT) > 0) {
                    throw new BusinessException("LEDGER_BALANCE_LIMIT", RULE_VIOLATION);
                }
            }
        }
        if (ledger.referenceExists(command.referenceId())) {
            throw new BusinessException("LEDGER_REFERENCE_EXISTS", CONFLICT);
        }
        UUID id = UUID.randomUUID();
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        try {
            ledger.insertJournal(id, command, actor, now);
        } catch (DuplicateKeyException failure) {
            throw new BusinessException("LEDGER_REFERENCE_EXISTS", CONFLICT);
        }
        ledger.insertPostings(id, plan);
        ledger.finalizeJournal(id, now);
        return id;
    }
}
