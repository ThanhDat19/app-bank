package vn.banklab.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.banklab.core.account.application.AccountClosureGuard;
import vn.banklab.core.account.domain.ClosureCheck;
import vn.banklab.core.ledger.application.LedgerQueryService;

/**
 * Composition root keeps account -> ledger -> account dependency cycles out of domain modules.
 */
@Configuration(proxyBeanMethods = false)
public class LedgerClosureConfiguration {
    @Bean
    AccountClosureGuard ledgerClosureGuard(LedgerQueryService ledger) {
        return account -> switch (ledger.assessClosure(account.id(), account.ledgerProvisioned())) {
            case CLEAR -> ClosureCheck.CLEAR;
            case NON_ZERO -> ClosureCheck.NON_ZERO_BALANCE;
            case UNAVAILABLE -> ClosureCheck.UNAVAILABLE;
        };
    }
}
