package vn.banklab.core.ledger.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface OutgoingPolicyRepository {
    record Policy(BigDecimal transactionLimit, BigDecimal dailyLimit, BigDecimal fee, String timezone) { }
    record Charge(UUID journalId, BigDecimal amount, BigDecimal fee, BigDecimal totalDebit,
                  LocalDate businessDate, String timezone, UUID feeJournalId) { }
    Policy policy(UUID accountId);
    LocalDate businessDate(UUID journalId, String timezone);
    BigDecimal used(UUID accountId, LocalDate day);
    UUID feeIncomeAccount();
    void record(UUID accountId, UUID journalId, BigDecimal amount, Policy policy, LocalDate day, UUID feeJournalId);
    Optional<Charge> find(UUID accountId, UUID journalId);
}
