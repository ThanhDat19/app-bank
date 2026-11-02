package vn.banklab.core.ledger.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.shared.application.BusinessException;

@Service
public class OutgoingPolicyService {
    private final OutgoingPolicyRepository policies;
    private final LedgerPostingService posting;
    private final AccountFinancialAccess accounts;
    public OutgoingPolicyService(OutgoingPolicyRepository policies, LedgerPostingService posting, AccountFinancialAccess accounts) {
        this.policies=policies; this.posting=posting; this.accounts=accounts;
    }

    /** Caller already holds the complete bank pair. Principal, fee and usage roll back together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assess(UUID accountId, UUID ledgerId, UUID journalId, BigDecimal amount) {
        var policy=policies.policy(accountId);
        var total=amount.add(policy.fee());
        if (total.compareTo(policy.transactionLimit())>0) throw rule("TRANSFER_TRANSACTION_LIMIT");
        var day=policies.businessDate(journalId, policy.timezone());
        if (policies.used(accountId,day).add(total).compareTo(policy.dailyLimit())>0) throw rule("TRANSFER_DAILY_LIMIT");
        UUID feeJournal=null;
        if (policy.fee().signum()>0) {
            feeJournal=posting.post(new LedgerPostingCommand(UUID.randomUUID(), LedgerPostingCommand.Kind.ADJUSTMENT, List.of(
                    new LedgerPostingCommand.Posting(ledgerId,LedgerPostingCommand.Side.DEBIT,policy.fee()),
                    new LedgerPostingCommand.Posting(policies.feeIncomeAccount(),LedgerPostingCommand.Side.CREDIT,policy.fee()))));
        }
        policies.record(accountId,journalId,amount,policy,day,feeJournal);
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly=true)
    public OutgoingPolicyRepository.Charge mine(UUID accountId, UUID journalId) {
        accounts.owned(accountId);
        return policies.find(accountId,journalId).orElseThrow(() -> new BusinessException("TRANSFER_CHARGE_NOT_FOUND",BusinessException.Kind.NOT_FOUND));
    }

    private static BusinessException rule(String code) { return new BusinessException(code,BusinessException.Kind.RULE_VIOLATION); }
}
