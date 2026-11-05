package vn.banklab.core.external.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.banklab.core.external.application.*;
import vn.banklab.core.iam.application.Actor;
import vn.banklab.core.risk.application.TransferRiskGate;

@Repository
public class JdbcExternalPaymentRepository implements ExternalPaymentRepository {
    private static final RowMapper<ExternalPayment> MAPPER=(r,n)->new ExternalPayment(r.getObject("id",UUID.class),r.getString("request_hash"),
            r.getObject("account_id",UUID.class),r.getObject("settlement_account_id",UUID.class),r.getString("beneficiary"),
            r.getBigDecimal("amount").setScale(0).toPlainString(),r.getBigDecimal("reserved_amount").setScale(0).toPlainString(),r.getObject("partner_instance",UUID.class),
            r.getString("risk_outcome"),r.getString("risk_policy"),r.getString("state"),r.getLong("version"),r.getLong("partner_version"),
            r.getString("partner_state"),r.getString("problem"),r.getObject("journal_id",UUID.class),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    private final JdbcClient jdbc;
    public JdbcExternalPaymentRepository(JdbcClient jdbc){this.jdbc=jdbc;}
    public Optional<ExternalPayment> findKey(Actor actor,String key){
        return jdbc.sql("SELECT * FROM banking.external_payments WHERE actor_issuer=:issuer AND actor_subject=:subject AND idempotency_key=:key")
                .param("issuer",actor.issuer()).param("subject",actor.subject()).param("key",key).query(MAPPER).optional();
    }
    public Optional<ExternalPayment> owned(Actor actor,UUID id,boolean lock){
        return jdbc.sql("SELECT * FROM banking.external_payments WHERE id=:id AND actor_issuer=:issuer AND actor_subject=:subject"+(lock?" FOR NO KEY UPDATE":""))
                .param("id",id).param("issuer",actor.issuer()).param("subject",actor.subject()).query(MAPPER).optional();
    }
    public ExternalPayment claim(Actor actor,String key,String hash,UUID account,UUID settlement,String beneficiary,String amount,UUID instance,TransferRiskGate.Approval a){
        jdbc.sql("""
                INSERT INTO banking.external_payments(id,actor_issuer,actor_subject,idempotency_key,request_hash,account_id,settlement_account_id,
                    beneficiary,amount,reserved_amount,partner_instance,risk_outcome,risk_policy,risk_reason,risk_assessed_at,risk_expires_at,state)
                VALUES(:id,:issuer,:subject,:key,:hash,:account,:settlement,:beneficiary,:amount,:amount,:instance,:outcome,:policy,:reason,:assessed,:expires,'CREATING')
                ON CONFLICT(actor_issuer,actor_subject,idempotency_key) DO NOTHING
                """).param("id",UUID.randomUUID()).param("issuer",actor.issuer()).param("subject",actor.subject()).param("key",key).param("hash",hash)
                .param("account",account).param("settlement",settlement).param("beneficiary",beneficiary).param("amount",new BigDecimal(amount)).param("instance",instance)
                .param("outcome",a.outcome()).param("policy",a.policyVersion()).param("reason",a.reason()).param("assessed",Timestamp.from(a.assessedAt()))
                .param("expires",a.expiresAt()==null?null:Timestamp.from(a.expiresAt()),Types.TIMESTAMP).update();
        return jdbc.sql("SELECT * FROM banking.external_payments WHERE actor_issuer=:issuer AND actor_subject=:subject AND idempotency_key=:key FOR NO KEY UPDATE")
                .param("issuer",actor.issuer()).param("subject",actor.subject()).param("key",key).query(MAPPER).single();
    }
    public ExternalPayment update(UUID id,String state,BigDecimal reserved,long partnerVersion,String partnerState,String problem,UUID journal){
        return jdbc.sql("""
                UPDATE banking.external_payments SET state=:state,version=version+1,reserved_amount=COALESCE(:reserved,reserved_amount),
                    partner_version=:pv,partner_state=:ps,problem=:problem,journal_id=:journal,updated_at=clock_timestamp() WHERE id=:id RETURNING *
                """).param("id",id).param("state",state).param("reserved",reserved,Types.NUMERIC).param("pv",partnerVersion)
                .param("ps",partnerState,Types.VARCHAR).param("problem",problem,Types.VARCHAR).param("journal",journal,Types.OTHER).query(MAPPER).single();
    }
    public List<History> history(UUID id,int limit){
        return jdbc.sql("SELECT * FROM banking.external_payment_history WHERE payment_id=:id ORDER BY version DESC LIMIT :limit").param("id",id).param("limit",limit)
                .query((r,n)->new History(r.getLong("version"),r.getString("state"),r.getLong("partner_version"),r.getString("partner_state"),r.getString("problem"),r.getTimestamp("occurred_at").toInstant())).list();
    }
}
