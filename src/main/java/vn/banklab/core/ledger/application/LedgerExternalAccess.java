package vn.banklab.core.ledger.application;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.shared.application.BusinessException;

@Service
public class LedgerExternalAccess {
    private final AccountFinancialAccess accounts;
    private final LedgerRepository ledger;
    private final HoldRepository holds;
    private final OutgoingPolicyRepository policies;
    public LedgerExternalAccess(AccountFinancialAccess accounts,LedgerRepository ledger,HoldRepository holds,OutgoingPolicyRepository policies){
        this.accounts=accounts;this.ledger=ledger;this.holds=holds;this.policies=policies;
    }
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(propagation=Propagation.MANDATORY)
    public BigDecimal prepare(UUID source,UUID settlement,BigDecimal amount){
        return check(source,settlement,amount,BigDecimal.ZERO);
    }
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(propagation=Propagation.MANDATORY)
    public void dispatch(UUID source,UUID settlement,BigDecimal amount,BigDecimal reservation){
        check(source,settlement,amount,reservation);
    }
    private BigDecimal check(UUID source,UUID settlement,BigDecimal amount,BigDecimal ownReservation){
        for(var account:accounts.lockOwnedTransferPair(source,settlement)){
            var link=ledger.findForBank(account.id());
            if(!account.ledgerProvisioned()||link.isEmpty())throw rule("LEDGER_NOT_PROVISIONED");
        }
        var policy=policies.policy(source);var gross=amount.add(policy.fee());
        if(ownReservation.signum()>0&&gross.compareTo(ownReservation)>0)
            throw new BusinessException("EXTERNAL_RESERVATION_STALE",BusinessException.Kind.CONFLICT);
        if(gross.compareTo(policy.transactionLimit())>0)throw rule("TRANSFER_TRANSACTION_LIMIT");
        var day=policies.currentBusinessDate(policy.timezone());
        if(policies.used(source,day).add(gross).compareTo(policy.dailyLimit())>0)throw rule("TRANSFER_DAILY_LIMIT");
        var link=ledger.findForBank(source).orElseThrow();
        if(ledger.balance(link.id()).subtract(holds.reserved(source)).add(ownReservation).compareTo(gross)<0)throw rule("LEDGER_INSUFFICIENT_FUNDS");
        return gross;
    }
    private static BusinessException rule(String code){return new BusinessException(code,BusinessException.Kind.RULE_VIOLATION);}
}
