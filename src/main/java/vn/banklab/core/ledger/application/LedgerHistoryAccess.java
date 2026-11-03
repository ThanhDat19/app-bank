package vn.banklab.core.ledger.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.shared.application.BusinessException;

@Service
public class LedgerHistoryAccess {
    public record Reference(UUID accountId,UUID ledgerId,String currency) { }
    private final LedgerRepository ledger;
    private final AccountFinancialAccess accounts;
    public LedgerHistoryAccess(LedgerRepository ledger,AccountFinancialAccess accounts){this.ledger=ledger;this.accounts=accounts;}
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public Reference owned(UUID accountId){
        var account=accounts.owned(accountId);var link=ledger.findForBank(accountId);
        if(account.ledgerProvisioned()!=link.isPresent())throw new BusinessException("LEDGER_LINK_INCONSISTENT",BusinessException.Kind.RULE_VIOLATION);
        var l=link.orElseThrow(()->new BusinessException("LEDGER_NOT_PROVISIONED",BusinessException.Kind.RULE_VIOLATION));
        return new Reference(accountId,l.id(),l.currency());
    }
}
