package vn.banklab.core.ledger.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.banklab.core.ledger.application.OutgoingPolicyRepository;

@Repository
public class JdbcOutgoingPolicyRepository implements OutgoingPolicyRepository {
    public java.time.LocalDate currentBusinessDate(String timezone){
        return jdbc.sql("SELECT (clock_timestamp() AT TIME ZONE :zone)::date").param("zone",timezone).query(java.time.LocalDate.class).single();
    }
    private final JdbcClient jdbc;
    public JdbcOutgoingPolicyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Policy policy(UUID accountId) {
        return jdbc.sql("SELECT * FROM banking.outgoing_policies WHERE account_id=:id FOR SHARE")
                .param("id", accountId).query((r,n) -> new Policy(r.getBigDecimal("transaction_limit"),
                        r.getBigDecimal("daily_limit"),r.getBigDecimal("fee"),r.getString("timezone"))).optional()
                .orElse(new Policy(new BigDecimal("99999999999999999999"), new BigDecimal("99999999999999999999"), BigDecimal.ZERO, "Asia/Ho_Chi_Minh"));
    }

    public LocalDate businessDate(UUID journalId, String timezone) {
        return jdbc.sql("SELECT (posted_at AT TIME ZONE :zone)::date FROM banking.ledger_journals WHERE id=:id")
                .param("zone", timezone).param("id", journalId).query(LocalDate.class).single();
    }

    public BigDecimal used(UUID accountId, LocalDate day) {
        return jdbc.sql("SELECT COALESCE(sum(amount+fee),0) FROM banking.outgoing_charges WHERE account_id=:id AND business_date=:day")
                .param("id", accountId).param("day", day).query(BigDecimal.class).single();
    }

    public UUID feeIncomeAccount() {
        return jdbc.sql("SELECT id FROM banking.ledger_accounts WHERE code='VND_TRANSFER_FEE_INCOME'").query(UUID.class).single();
    }

    public void record(UUID accountId, UUID journalId, BigDecimal amount, Policy p, LocalDate day, UUID feeJournalId) {
        jdbc.sql("""
                INSERT INTO banking.outgoing_charges(journal_id,account_id,amount,fee,transaction_limit,daily_limit,timezone,business_date,fee_journal_id)
                VALUES(:journal,:account,:amount,:fee,:transactionLimit,:dailyLimit,:zone,:day,:feeJournal)
                """).param("journal", journalId).param("account", accountId).param("amount", amount).param("fee",p.fee())
                .param("transactionLimit",p.transactionLimit()).param("dailyLimit",p.dailyLimit()).param("zone",p.timezone())
                .param("day",day).param("feeJournal",feeJournalId).update();
    }

    public Optional<Charge> find(UUID accountId, UUID journalId) {
        return jdbc.sql("SELECT * FROM banking.outgoing_charges WHERE account_id=:account AND journal_id=:journal")
                .param("account",accountId).param("journal",journalId).query((r,n) -> new Charge(journalId,
                        r.getBigDecimal("amount"), r.getBigDecimal("fee"), r.getBigDecimal("amount").add(r.getBigDecimal("fee")),
                        r.getObject("business_date",LocalDate.class),r.getString("timezone"),r.getObject("fee_journal_id",UUID.class))).optional();
    }
}
