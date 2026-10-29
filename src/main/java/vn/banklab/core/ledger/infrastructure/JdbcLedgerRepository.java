package vn.banklab.core.ledger.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.banklab.core.iam.application.Actor;
import vn.banklab.core.ledger.application.LedgerPostingCommand;
import vn.banklab.core.ledger.application.LedgerRepository;
import vn.banklab.core.ledger.domain.JournalPlan;

@Repository
public class JdbcLedgerRepository implements LedgerRepository {
    private static final RowMapper<LedgerAccount> MAPPER = (row, index) -> new LedgerAccount(
            row.getObject("id", UUID.class), row.getString("code"), row.getObject("bank_account_id", UUID.class),
            row.getString("currency"), row.getString("account_type"), row.getString("normal_side"));
    private final JdbcClient jdbc;

    public JdbcLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LedgerAccount> findForBank(UUID id) {
        return jdbc.sql("SELECT * FROM banking.ledger_accounts WHERE bank_account_id=:id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public Optional<LedgerAccount> findCashClearing() {
        return jdbc.sql("SELECT * FROM banking.ledger_accounts WHERE code='VND_CASH_CLEARING'").query(MAPPER).optional();
    }

    @Override
    public LedgerAccount insertCustomer(UUID id, String currency, Instant now) {
        return jdbc.sql("""
                        INSERT INTO banking.ledger_accounts (id,code,name,account_type,normal_side,currency,bank_account_id,created_at)
                        VALUES (:id,:code,'Customer deposit','LIABILITY','CREDIT',:currency,:bank,:now) RETURNING *
                        """).param("id", UUID.randomUUID()).param("code", "CUSTOMER_" + id.toString().replace("-", ""))
                .param("currency", currency).param("bank", id).param("now", Timestamp.from(now)).query(MAPPER).single();
    }

    @Override
    public List<LedgerAccount> findAccounts(Set<UUID> ids, boolean lock) {
        return jdbc.sql("SELECT * FROM banking.ledger_accounts WHERE id IN (:ids) ORDER BY id" + (lock ? " FOR NO KEY UPDATE" : ""))
                .param("ids", ids).query(MAPPER).list();
    }

    @Override
    public BigDecimal balance(UUID id) {
        return jdbc.sql("SELECT banking.ledger_balance(:id)").param("id", id).query(BigDecimal.class).single();
    }

    @Override
    public boolean referenceExists(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM banking.ledger_journals WHERE reference_id=:id)").param("id", id).query(Boolean.class).single();
    }

    @Override
    public Optional<Journal> lockJournal(UUID id) {
        // Compatible with FK KEY SHARE. FOR UPDATE can deadlock competing reversal claims.
        return jdbc.sql("SELECT id,kind,status FROM banking.ledger_journals WHERE id=:id FOR NO KEY UPDATE")
                .param("id", id).query((row, index) -> new Journal(row.getObject("id", UUID.class),
                        LedgerPostingCommand.Kind.valueOf(row.getString("kind")), row.getString("status"))).optional();
    }

    @Override
    public List<LedgerPostingCommand.Posting> postings(UUID id) {
        return jdbc.sql("SELECT ledger_account_id,side,amount FROM banking.ledger_postings WHERE journal_id=:id ORDER BY id")
                .param("id", id).query((row, index) -> new LedgerPostingCommand.Posting(row.getObject("ledger_account_id", UUID.class),
                        LedgerPostingCommand.Side.valueOf(row.getString("side")), row.getBigDecimal("amount"))).list();
    }

    @Override
    public void insertJournal(UUID id, LedgerPostingCommand command, Actor actor, Instant now) {
        jdbc.sql("""
                        INSERT INTO banking.ledger_journals (id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at)
                        VALUES (:id,:reference,:kind,'VND','DRAFT',:issuer,:subject,:now)
                        """).param("id", id).param("reference", command.referenceId()).param("kind", command.kind().name())
                .param("issuer", actor.issuer()).param("subject", actor.subject()).param("now", Timestamp.from(now)).update();
    }

    @Override
    public void insertPostings(UUID id, JournalPlan plan) {
        for (var line : plan.entries()) {
            jdbc.sql("""
                            INSERT INTO banking.ledger_postings (id,journal_id,ledger_account_id,currency,side,amount)
                            VALUES (:id,:journal,:ledger,'VND',:side,:amount)
                            """).param("id", UUID.randomUUID()).param("journal", id).param("ledger", line.ledgerAccountId())
                    .param("side", line.side().name()).param("amount", line.amount()).update();
        }
    }

    @Override
    public void finalizeJournal(UUID id, Instant now) {
        int updated = jdbc.sql("UPDATE banking.ledger_journals SET status='POSTED',posted_at=:now WHERE id=:id AND status='DRAFT'")
                .param("now", Timestamp.from(now)).param("id", id).update();
        if (updated != 1) {
            throw new IllegalStateException("Draft journal required");
        }
    }
}
