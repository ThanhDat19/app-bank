package vn.banklab.core.statement.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.banklab.core.statement.application.StatementRepository;

@Repository
public class JdbcStatementRepository implements StatementRepository {
    private final JdbcClient jdbc;
    public JdbcStatementRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    public Instant snapshotTime(){return jdbc.sql("SELECT transaction_timestamp()").query(Timestamp.class).single().toInstant();}
    public void start(UUID id,UUID accountId,UUID ledgerId,String mode,String zone,LocalDate fromDate,LocalDate toDate,Instant from,Instant to){
        jdbc.sql("""
                INSERT INTO banking.account_history_snapshots(id,account_id,ledger_account_id,mode,currency,timezone,from_date,to_date,
                    from_at,requested_to_at,effective_to_at,snapshot_at,status,opening_balance,closing_balance)
                SELECT :id,:account,:ledger,:mode,'VND',:zone,CAST(:fromDate AS DATE),CAST(:toDate AS DATE),CAST(:fromAt AS TIMESTAMPTZ),
                    CAST(:toAt AS TIMESTAMPTZ),CASE WHEN :mode='HISTORY' THEN NULL ELSE least(CAST(:toAt AS TIMESTAMPTZ),transaction_timestamp()) END,
                    transaction_timestamp(),'BUILDING',b.opening,b.opening
                FROM(SELECT COALESCE(sum(CASE WHEN p.side='CREDIT' THEN p.amount ELSE -p.amount END),0) opening
                    FROM banking.ledger_postings p JOIN banking.ledger_journals j ON j.id=p.journal_id
                    WHERE p.ledger_account_id=:ledger AND j.status='POSTED' AND :mode='STATEMENT' AND j.posted_at<CAST(:fromAt AS TIMESTAMPTZ)) b
                """).param("id",id).param("account",accountId).param("ledger",ledgerId).param("mode",mode).param("zone",zone)
                .param("fromDate",fromDate).param("toDate",toDate).param("fromAt",from==null?null:Timestamp.from(from)).param("toAt",to==null?null:Timestamp.from(to)).update();
    }
    public long copy(UUID id,int maximum){
        jdbc.sql("""
                WITH lines AS(SELECT p.id posting_id,p.journal_id,j.reference_id,j.kind,j.posted_at,p.side,p.amount,
                    CASE WHEN p.side=l.normal_side THEN p.amount ELSE -p.amount END delta,s.opening_balance
                    FROM banking.account_history_snapshots s JOIN banking.ledger_accounts l ON l.id=s.ledger_account_id
                    JOIN banking.ledger_postings p ON p.ledger_account_id=l.id JOIN banking.ledger_journals j ON j.id=p.journal_id
                    WHERE s.id=:id AND j.status='POSTED' AND(s.mode='HISTORY' OR(j.posted_at>=s.from_at AND j.posted_at<s.effective_to_at)))
                INSERT INTO banking.account_history_entries(snapshot_id,sequence,posting_id,journal_id,reference_id,kind,posted_at,side,amount,delta,running_balance)
                SELECT :id,row_number() OVER w,posting_id,journal_id,reference_id,kind,posted_at,side,amount,delta,opening_balance+sum(delta) OVER w
                    FROM lines WINDOW w AS(ORDER BY posted_at,journal_id,posting_id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
                    ORDER BY posted_at,journal_id,posting_id LIMIT :maximum
                """).param("id",id).param("maximum",maximum+1).update();
        return jdbc.sql("SELECT count(*) FROM banking.account_history_entries WHERE snapshot_id=:id").param("id",id).query(Long.class).single();
    }
    public void complete(UUID id){
        jdbc.sql("""
                WITH totals AS(SELECT count(*) n,COALESCE(sum(amount) FILTER(WHERE side='CREDIT'),0) credits,
                    COALESCE(sum(amount) FILTER(WHERE side='DEBIT'),0) debits FROM banking.account_history_entries WHERE snapshot_id=:id)
                UPDATE banking.account_history_snapshots s SET status='READY',entry_count=t.n,credit_total=t.credits,debit_total=t.debits,
                    closing_balance=s.opening_balance+t.credits-t.debits,completed_at=clock_timestamp()
                    FROM totals t WHERE s.id=:id AND s.status='BUILDING'
                """).param("id",id).update();
    }
    public Optional<Header> find(UUID accountId,UUID id){
        return jdbc.sql("SELECT * FROM banking.account_history_snapshots WHERE id=:id AND account_id=:account AND status='READY'")
                .param("id",id).param("account",accountId).query((r,n)->new Header(id,accountId,r.getString("mode"),r.getString("currency"),r.getString("timezone"),
                        r.getObject("from_date",LocalDate.class),r.getObject("to_date",LocalDate.class),instant(r,"from_at"),instant(r,"requested_to_at"),instant(r,"effective_to_at"),
                        instant(r,"snapshot_at"),r.getString("status"),money(r,"opening_balance"),money(r,"credit_total"),money(r,"debit_total"),money(r,"closing_balance"),
                        r.getLong("entry_count"),instant(r,"completed_at"),r.getString("mode").equals("STATEMENT")&&r.getTimestamp("requested_to_at").toInstant().isAfter(r.getTimestamp("snapshot_at").toInstant()))).optional();
    }
    public List<Entry> entries(UUID id,long after,int limit){
        return jdbc.sql("SELECT * FROM banking.account_history_entries WHERE snapshot_id=:id AND sequence>:after ORDER BY sequence LIMIT :limit")
                .param("id",id).param("after",after).param("limit",limit).query((r,n)->new Entry(r.getLong("sequence"),r.getObject("posting_id",UUID.class),
                        r.getObject("journal_id",UUID.class),r.getObject("reference_id",UUID.class),r.getString("kind"),instant(r,"posted_at"),r.getString("side"),
                        money(r,"amount"),money(r,"delta"),money(r,"running_balance"))).list();
    }
    private static Instant instant(ResultSet r,String key)throws SQLException{var value=r.getTimestamp(key);return value==null?null:value.toInstant();}
    private static String money(ResultSet r,String key)throws SQLException{return r.getBigDecimal(key).setScale(0).toPlainString();}
}
