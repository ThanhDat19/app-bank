package vn.banklab.core.outbox;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.sql.Timestamp;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import vn.banklab.core.outbox.application.*;
import vn.banklab.core.support.SignedTokenServer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxIT {
    static final SignedTokenServer TOKENS;
    static { try { TOKENS=new SignedTokenServer(); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16.14-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword); r.add("bank.iam.issuer-uri",TOKENS::issuer);
        r.add("bank.iam.jwk-set-uri",TOKENS::jwks); r.add("bank.iam.audience",()->"bank-api");
    }
    @AfterAll static void closeTokens() { TOKENS.close(); }
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OutboxRepository repository;
    @Autowired OutboxRelay relay;
    private static final UUID CLEARING=UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach void drainReady() {
        List<OutboxRepository.Claim> claims;
        do { claims=repository.claim(100,30000); claims.forEach(c->assertThat(repository.acknowledge(c.event().eventId(),c.token())).isTrue()); } while(!claims.isEmpty());
    }

    @Test void journalAndEventBecomeVisibleOnlyAfterCommit() {
        UUID ledger=account(), journal=UUID.randomUUID();
        tx().execute(s->{ post(journal,UUID.randomUUID(),ledger,"ADJUSTMENT"); assertThat(repository.claim(10,30000)).isEmpty(); return null; });
        var claim=repository.claim(10,30000); assertThat(claim).hasSize(1);
        var event=claim.getFirst().event(); assertThat(event.aggregateId()).isEqualTo(journal);
        assertThat(event.aggregateVersion()).isEqualTo(1); assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.eventType()).isEqualTo("LedgerJournalPosted");
        var payload=JsonMapper.builder().build().readTree(event.payload());
        assertThat(payload.path("journalId").asString()).isEqualTo(journal.toString());
        assertThat(payload.path("postings").size()).isEqualTo(2);
        assertThat(payload.path("postings").get(0).path("amount").isString()).isTrue();
        assertThat(event.payload()).doesNotContain("actor","subject","issuer","displayName","reason","token");
        assertThat(event.occurredAt()).isEqualTo(jdbc.sql("SELECT posted_at FROM banking.ledger_journals WHERE id=:id").param("id",journal).query(Timestamp.class).single().toInstant());
        assertThat(repository.acknowledge(event.eventId(),claim.getFirst().token())).isTrue();
    }

    @Test void eventInsertFailureRollsBackMoneyAndSameReferenceCanRetry() {
        UUID ledger=account(), journal=UUID.randomUUID(), ref=UUID.randomUUID();
        jdbc.sql("ALTER TABLE banking.outbox_events ADD CONSTRAINT test_event_failure CHECK(aggregate_id<>'"+journal+"'::uuid)").update();
        try { assertThatThrownBy(()->tx().execute(s->{post(journal,ref,ledger,"ADJUSTMENT");return null;})).isInstanceOf(Exception.class); }
        finally { jdbc.sql("ALTER TABLE banking.outbox_events DROP CONSTRAINT test_event_failure").update(); }
        assertThat(count(journal)).isZero();
        assertThat(jdbc.sql("SELECT banking.ledger_balance(:id)").param("id",ledger).query(java.math.BigDecimal.class).single()).isZero();
        tx().execute(s->{post(journal,ref,ledger,"ADJUSTMENT");return null;});
        assertThat(count(journal)).isEqualTo(1);
    }

    @Test void deferredFinancialReceiptFailureRollsBackEventAndDelivery() {
        UUID ledger=account(), journal=UUID.randomUUID(), receipt=UUID.randomUUID();
        assertThatThrownBy(()->tx().execute(s->{
            jdbc.sql("""
                    INSERT INTO banking.cash_ins(id,actor_issuer,actor_subject,idempotency_key,request_hash,account_id,amount)
                    SELECT :receipt,'sql-issuer','operator',:key,:hash,bank_account_id,1 FROM banking.ledger_accounts WHERE id=:ledger
                    """).param("receipt",receipt).param("key",receipt.toString()).param("hash","a".repeat(64)).param("ledger",ledger).update();
            post(journal,receipt,ledger,"SIMULATED_CASH_IN"); return null;
        })).isInstanceOf(Exception.class);
        assertThat(count(journal)).isZero(); assertThat(repository.claim(10,30000)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM banking.cash_ins WHERE id=:id").param("id",receipt).query(Long.class).single()).isZero();
    }

    @Test void concurrentWorkersClaimDisjointEvents() throws Exception {
        UUID ledger=account();
        for(int n=0;n<16;n++) create(ledger);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1); var futures=new ArrayList<Future<List<OutboxRepository.Claim>>>();
            for(int n=0;n<8;n++) futures.add(pool.submit(()->{start.await();return repository.claim(2,30000);}));
            start.countDown(); var claims=new ArrayList<OutboxRepository.Claim>();
            for(var future:futures) claims.addAll(future.get(30,TimeUnit.SECONDS));
            assertThat(claims).hasSize(16); assertThat(claims.stream().map(c->c.event().eventId()).distinct().count()).isEqualTo(16);
            claims.forEach(c->assertThat(repository.acknowledge(c.event().eventId(),c.token())).isTrue());
        }
    }

    @Test void crashLeaseRecoveryFencesOldWorkerAndPreservesEventIdentity() {
        create(account()); var first=repository.claim(1,200).getFirst(); sleepSql(0.3);
        assertThat(repository.acknowledge(first.event().eventId(),first.token())).isFalse();
        var next=repository.claim(1,30000).getFirst();
        assertThat(next.event()).isEqualTo(first.event()); assertThat(next.token()).isNotEqualTo(first.token());
        assertThat(next.attempt()).isEqualTo(2);
        assertThat(repository.acknowledge(first.event().eventId(),first.token())).isFalse();
        assertThat(repository.retry(first.event().eventId(),first.token(),1)).isFalse();
        assertThat(repository.acknowledge(next.event().eventId(),next.token())).isTrue();
    }

    @Test void publishFailureBacksOffThenRetriesOutsideDatabaseTransaction() {
        create(account()); var calls=new AtomicInteger();
        var first=relay.deliverBatch(event->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); calls.incrementAndGet(); throw new java.io.IOException("synthetic transport failure");
        },1,30000);
        assertThat(first.failed()).isEqualTo(1); assertThat(first.delivered()).isZero();
        assertThat(repository.claim(1,30000)).isEmpty(); sleepSql(1.1);
        var next=relay.deliverBatch(event->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();calls.incrementAndGet();},1,30000);
        assertThat(next.delivered()).isEqualTo(1); assertThat(calls.get()).isEqualTo(2);
        assertThat(repository.claim(1,30000)).isEmpty();
        assertThat(OutboxRelay.retryDelay(1)).isEqualTo(1000); assertThat(OutboxRelay.retryDelay(Integer.MAX_VALUE)).isEqualTo(300000);
    }

    @Test void acceptedPublishBeforeCrashCanRedeliverSameEventId() {
        create(account()); var accepted=new ArrayList<UUID>();
        var first=repository.claim(1,200).getFirst(); accepted.add(first.event().eventId());
        // Transport accepted, process died without persisting ack.
        sleepSql(0.3);
        assertThat(relay.deliverBatch(event->accepted.add(event.eventId()),1,30000).delivered()).isEqualTo(1);
        assertThat(accepted).hasSize(2); assertThat(accepted.get(0)).isEqualTo(accepted.get(1));
    }

    @Test void acknowledgementFailureLeavesLeaseForSafeRedelivery() {
        UUID journal=create(account()); var accepted=new ArrayList<UUID>();
        UUID eventId=jdbc.sql("SELECT event_id FROM banking.outbox_events WHERE aggregate_id=:id").param("id",journal).query(UUID.class).single();
        jdbc.sql("ALTER TABLE banking.outbox_deliveries ADD CONSTRAINT test_ack_failure CHECK(event_id<>'"+eventId+"'::uuid OR state<>'DELIVERED')").update();
        try { assertThatThrownBy(()->relay.deliverBatch(e->accepted.add(e.eventId()),1,200)).isInstanceOf(Exception.class); }
        finally { jdbc.sql("ALTER TABLE banking.outbox_deliveries DROP CONSTRAINT test_ack_failure").update(); }
        sleepSql(0.3);
        assertThat(relay.deliverBatch(e->accepted.add(e.eventId()),1,30000).delivered()).isEqualTo(1);
        assertThat(accepted).hasSize(2); assertThat(accepted.get(0)).isEqualTo(accepted.get(1));
    }

    @Test void eventEnvelopeAndTerminalDeliveryAreImmutable() {
        create(account()); var claim=repository.claim(1,30000).getFirst(); UUID id=claim.event().eventId();
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.outbox_events SET payload='{}' WHERE event_id=:id").param("id",id).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.sql("DELETE FROM banking.outbox_events WHERE event_id=:id").param("id",id).update()).isInstanceOf(Exception.class);
        assertThat(repository.acknowledge(id,claim.token())).isTrue();
        assertThat(repository.acknowledge(id,claim.token())).isFalse();
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.outbox_deliveries SET state='PENDING',delivered_at=NULL WHERE event_id=:id").param("id",id).update()).isInstanceOf(Exception.class);
    }

    @Test void relayRejectsAmbientTransactionAndInvalidLeaseConfiguration() {
        assertThatThrownBy(()->tx().execute(s->relay.deliverBatch(e->{},1,30000))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(()->repository.claim(0,30000)).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class);
        assertThatThrownBy(()->repository.claim(1,0)).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class);
    }

    private UUID create(UUID ledger) { UUID id=UUID.randomUUID();tx().execute(s->{post(id,UUID.randomUUID(),ledger,"ADJUSTMENT");return null;});return id; }
    private long count(UUID journal) { return jdbc.sql("SELECT count(*) FROM banking.outbox_events WHERE aggregate_id=:id").param("id",journal).query(Long.class).single(); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private void sleepSql(double seconds) { jdbc.sql("SELECT pg_sleep(:seconds)").param("seconds",seconds).query(Object.class).single(); }
    private UUID account() {
        UUID customer=UUID.randomUUID(),bank=UUID.randomUUID(),ledger=UUID.randomUUID();
        tx().execute(s->{
            jdbc.sql("INSERT INTO banking.customers(id,identity_issuer,identity_subject,display_name,created_at,updated_at) VALUES(:id,'sql-issuer',:subject,'Synthetic Outbox',now(),now())").param("id",customer).param("subject",customer.toString()).update();
            jdbc.sql("INSERT INTO banking.accounts(id,customer_id,currency,created_at,updated_at) VALUES(:id,:customer,'VND',now(),now())").param("id",bank).param("customer",customer).update();
            jdbc.sql("INSERT INTO banking.ledger_accounts(id,code,name,account_type,normal_side,currency,bank_account_id,created_at) VALUES(:id,:code,'Synthetic Outbox','LIABILITY','CREDIT','VND',:bank,now())").param("id",ledger).param("code",ledger.toString()).param("bank",bank).update();
            jdbc.sql("UPDATE banking.accounts SET ledger_provisioned=true WHERE id=:id").param("id",bank).update(); return null;
        });return ledger;
    }
    private void post(UUID id,UUID reference,UUID ledger,String kind) {
        jdbc.sql("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES(:id,:ref,:kind,'VND','DRAFT','sql-issuer','operator',now())")
                .param("id",id).param("ref",reference).param("kind",kind).update();
        for(var entry:Map.of(CLEARING,"DEBIT",ledger,"CREDIT").entrySet())
            jdbc.sql("INSERT INTO banking.ledger_postings(id,journal_id,ledger_account_id,currency,side,amount) VALUES(:id,:journal,:ledger,'VND',:side,1)")
                    .param("id",UUID.randomUUID()).param("journal",id).param("ledger",entry.getKey()).param("side",entry.getValue()).update();
        jdbc.sql("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id=:id").param("id",id).update();
    }
}
