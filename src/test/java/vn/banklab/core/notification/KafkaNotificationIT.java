package vn.banklab.core.notification;

import java.util.*;
import java.time.*;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import vn.banklab.core.support.SignedTokenServer;
import vn.banklab.core.messaging.infrastructure.*;
import vn.banklab.core.notification.application.*;
import vn.banklab.core.outbox.application.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class KafkaNotificationIT {
    static final SignedTokenServer TOKENS;
    static { try { TOKENS=new SignedTokenServer(); } catch(Exception e) {throw new ExceptionInInitializerError(e);} }
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16.14-alpine");
    @Container static final KafkaContainer KAFKA=new KafkaContainer("apache/kafka-native:4.2.2");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("bank.iam.issuer-uri",TOKENS::issuer);r.add("bank.iam.jwk-set-uri",TOKENS::jwks);r.add("bank.iam.audience",()->"bank-api");
        r.add("bank.kafka.enabled",()->false);r.add("bank.outbox.relay.enabled",()->false);
    }
    @AfterAll static void closeTokens() {TOKENS.close();}
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxRelay relay;
    @Autowired NotificationService notifications;
    @Autowired NotificationConsumer processor;
    @Autowired NotificationRepository repository;
    @LocalServerPort int port;
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient http=HttpClient.newHttpClient();
    private KafkaTransport transport;
    private KafkaNotificationWorker worker;
    private Admin admin;
    private String topic,dlq,group;
    private record Owner(UUID account,UUID ledger,String token) { }
    private static final UUID CLEARING=UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach void setup() throws Exception {
        List<OutboxRepository.Claim> ready;
        do {ready=outbox.claim(100,30000);ready.forEach(c->outbox.acknowledge(c.event().eventId(),c.token()));} while(!ready.isEmpty());
        topic="journal-"+UUID.randomUUID();dlq="dlq-"+UUID.randomUUID();group="notifications-"+UUID.randomUUID();
        admin=Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers()));
        admin.createTopics(List.of(new NewTopic(topic,3,(short)1),new NewTopic(dlq,1,(short)1))).all().get(15,TimeUnit.SECONDS);
        transport=new KafkaTransport(KAFKA.getBootstrapServers(),topic,dlq);
        worker=new KafkaNotificationWorker(KAFKA.getBootstrapServers(),group,topic,processor,transport);
    }
    @AfterEach void closeClients() { if(worker!=null)worker.close();if(transport!=null)transport.close();if(admin!=null)admin.close(Duration.ofSeconds(2)); }

    @Test void realOutboxKafkaNotificationIsOwnedAndEventuallyVisible() throws Exception {
        var owner=owner();var event=credit(owner);
        var result=relay.deliverBatch(e->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();transport.publish(e);},1,30000);
        assertThat(result.delivered()).isEqualTo(1);
        await(()->count(owner)==1);
        var response=get(owner.account(),owner.token(),50);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var list=json.readTree(response.body());assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("eventId").asString()).isEqualTo(event.eventId().toString());
        assertThat(response.body()).doesNotContain("issuer","subject","token","accountNumber","displayName");
        assertThat(get(owner.account(),null,50).statusCode()).isEqualTo(401);
        var other=owner();assertThat(get(owner.account(),other.token(),50).statusCode()).isEqualTo(404);
        String operator=TOKENS.token(b->b.subject("operator").claim("resource_access",Map.of("bank-api",Map.of("roles",List.of("OPERATOR")))));
        assertThat(get(owner.account(),operator,50).statusCode()).isEqualTo(403);
        assertThat(get(owner.account(),owner.token(),0).statusCode()).isEqualTo(422);
        assertThat(get(owner.account(),owner.token(),101).statusCode()).isEqualTo(422);
    }

    @Test void duplicatesAndRestartAfterDatabaseCommitDoNotDuplicateEffects() throws Exception {
        var owner=owner();var event=credit(owner);
        // Simulate DB commit before process crash/offset commit. Kafka still delivers the record.
        notifications.accept(source(event,0));
        transport.publish(event);transport.publish(event);
        await(()->offsets()>=2);assertThat(count(owner)).isEqualTo(1);
        worker.close();worker=new KafkaNotificationWorker(KAFKA.getBootstrapServers(),group,topic,processor,transport);
        transport.publish(event);await(()->offsets()>=3);
        assertThat(count(owner)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM banking.notification_inbox WHERE event_id=:id").param("id",event.eventId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test void brokerOutageDoesNotLoseMoneyOrOutboxAndRecovers() throws Exception {
        var owner=owner();var event=credit(owner);
        var docker= DockerClientFactory.instance().client();docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            assertThat(relay.deliverBatch(transport,1,30000).failed()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT banking.ledger_balance(:id)").param("id",owner.ledger()).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("1");
            assertThat(jdbc.sql("SELECT state FROM banking.outbox_deliveries WHERE event_id=:id").param("id",event.eventId()).query(String.class).single()).isEqualTo("PENDING");
        } finally {docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();}
        Thread.sleep(1100);
        assertThat(relay.deliverBatch(transport,1,30000).delivered()).isEqualTo(1);
        await(()->count(owner)==1);
    }

    @Test void malformedRecordGoesToSanitizedKafkaDlqBeforeSourceOffsetCommit() throws Exception {
        raw("invalid","{\"token\":\"do-not-copy-secret\"}");
        await(()->offsets()>=1);
        var letter=readDlq();assertThat(letter).contains("payloadHash","INVALID_EVENT").doesNotContain("do-not-copy-secret","token");
        assertThat(jdbc.sql("SELECT count(*) FROM banking.notification_failures WHERE source_topic=:topic AND quarantined").param("topic",topic).query(Long.class).single()).isEqualTo(1);
    }

    @Test void conflictingAndUnsupportedEventsDoNotPoisonValidLaterRecords() throws Exception {
        var owner=owner();var event=credit(owner);transport.publish(event);await(()->count(owner)==1);
        var bad=new OutboxEvent(event.eventId(),event.aggregateId(),event.aggregateType(),1,event.eventType(),2,event.occurredAt(),event.payload());
        raw(event.aggregateId().toString(),json.writeValueAsString(bad));
        var changed=new OutboxEvent(event.eventId(),event.aggregateId(),event.aggregateType(),1,event.eventType(),1,event.occurredAt(),"{}");
        raw(event.aggregateId().toString(),json.writeValueAsString(changed));
        var next=credit(owner);transport.publish(next);await(()->offsets()>=4);
        assertThat(count(owner)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM banking.notification_failures WHERE source_topic=:topic AND quarantined").param("topic",topic).query(Long.class).single()).isEqualTo(2);
    }

    @Test void notificationFaultRollsBackInboxThenDurableBackoffAllowsRetry() throws Exception {
        var owner=owner();var event=credit(owner);var record=source(event,42);
        block(owner);
        try {
            assertThat(processor.process(record,transport)).isFalse();assertThat(repository.failure(record).attempts()).isEqualTo(1);
            assertThat(processor.process(record,transport)).isFalse();assertThat(repository.failure(record).attempts()).isEqualTo(1);
            assertThat(count(owner)).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM banking.notification_inbox WHERE event_id=:id").param("id",event.eventId()).query(Long.class).single()).isZero();
        } finally {unblock();}
        Thread.sleep(600);
        assertThat(processor.process(record,transport)).isTrue();assertThat(count(owner)).isEqualTo(1);
    }

    @Test void exhaustedDatabaseFailureQuarantinesAndAllowsPartitionProgress() throws Exception {
        var owner=owner();var event=credit(owner);block(owner);
        try {
            transport.publish(event);await(()->offsets()>=1);
            assertThat(count(owner)).isZero();
            assertThat(jdbc.sql("SELECT attempts FROM banking.notification_failures WHERE source_topic=:topic AND quarantined").param("topic",topic).query(Integer.class).single()).isEqualTo(3);
            assertThat(readDlq()).contains("PROCESSING_FAILED");
        } finally {unblock();}
        var next=credit(owner);transport.publish(next);await(()->count(owner)==1);
    }

    @Test void failedDeadLetterPublishDoesNotQuarantineOrAuthorizeOffsetCommit() throws Exception {
        var record=new SourceRecord(topic,0,99,"bad","invalid secret payload");
        assertThatThrownBy(()->processor.process(record,e->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();throw new java.io.IOException("broker unavailable");})).isInstanceOf(java.io.IOException.class);
        assertThat(repository.failure(record).quarantined()).isFalse();
        assertThat(processor.process(record,transport)).isTrue();assertThat(repository.failure(record).quarantined()).isTrue();
        assertThat(readDlq()).doesNotContain("invalid secret payload");
        assertThat(processor.process(record,e->{throw new AssertionError("Should not publish quarantined record again");})).isTrue();
    }

    @Test void lateJournalDoesNotOverwriteNewerNotificationOrBalance() throws Exception {
        var owner=owner();var first=credit(owner);var second=credit(owner);
        transport.publish(second);transport.publish(first);await(()->count(owner)==2);
        var list=json.readTree(get(owner.account(),owner.token(),50).body());
        assertThat(list.get(0).path("eventId").asString()).isEqualTo(second.eventId().toString());
        assertThat(jdbc.sql("SELECT banking.ledger_balance(:id)").param("id",owner.ledger()).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("2");
    }

    @Test void concurrentDuplicatesCommitOnlyOneInboxAndImmutableNotification() throws Exception {
        var owner=owner();var event=credit(owner);var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(int n=0;n<8;n++) futures.add(pool.submit(()->{start.await();notifications.accept(source(event,0));return null;}));
            start.countDown();for(var future:futures)future.get(20,TimeUnit.SECONDS);
        }
        assertThat(count(owner)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.notifications SET kind='REVERSAL' WHERE event_id=:id").param("id",event.eventId()).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.notification_inbox SET payload_hash=:hash WHERE event_id=:id").param("hash","0".repeat(64)).param("id",event.eventId()).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var other=owner();
        assertThatThrownBy(()->jdbc.sql("INSERT INTO banking.notifications(event_id,account_id,journal_id,kind,occurred_at) SELECT event_id,:account,journal_id,kind,occurred_at FROM banking.notifications WHERE event_id=:id")
                .param("account",other.account()).param("id",event.eventId()).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void publicTransferNotifiesBothParticipantsThroughRealKafka() throws Exception {
        var sender=owner();var recipient=owner();credit(sender);
        String body="{\"sourceAccountId\":\""+sender.account()+"\",\"destinationAccountId\":\""+recipient.account()+"\",\"amount\":\"1\",\"currency\":\"VND\"}";
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/transfers"))
                .header("Authorization","Bearer "+sender.token()).header("Idempotency-Key",UUID.randomUUID().toString()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(relay.deliverBatch(transport,10,30000).delivered()).isEqualTo(2);
        await(()->count(sender)==2 && count(recipient)==1);
        String journal=json.readTree(response.body()).path("journalId").asString();
        assertThat(get(sender.account(),sender.token(),50).body()).contains(journal,"INTERNAL_TRANSFER");
        assertThat(get(recipient.account(),recipient.token(),50).body()).contains(journal,"INTERNAL_TRANSFER");
        assertThat(jdbc.sql("SELECT banking.ledger_balance(:id)").param("id",sender.ledger()).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT banking.ledger_balance(:id)").param("id",recipient.ledger()).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("1");
    }

    private long count(Owner o) {return jdbc.sql("SELECT count(*) FROM banking.notifications WHERE account_id=:id").param("id",o.account()).query(Long.class).single();}
    private long offsets() {
        try {return admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS).values().stream().mapToLong(OffsetAndMetadata::offset).sum();}
        catch(Exception e) {return 0;}
    }
    private void await(BooleanSupplier done) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<deadline) {worker.pollOnce();if(done.getAsBoolean())return;Thread.sleep(50);}
        assertThat(done.getAsBoolean()).as("Kafka/notification condition within 30 seconds").isTrue();
    }
    private SourceRecord source(OutboxEvent e,long offset) {return new SourceRecord(topic,0,offset,e.aggregateId().toString(),json.writeValueAsString(e));}
    private void raw(String key,String value) throws Exception {
        try(var producer=new KafkaProducer<String,String>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers(),ProducerConfig.ACKS_CONFIG,"all"),new StringSerializer(),new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic,key,value)).get(10,TimeUnit.SECONDS);
        }
    }
    private String readDlq() throws Exception {
        try(var consumer=new KafkaConsumer<String,String>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers(),ConsumerConfig.GROUP_ID_CONFIG,"probe-"+UUID.randomUUID(),ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest",ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false),new StringDeserializer(),new StringDeserializer())) {
            consumer.subscribe(List.of(dlq));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<deadline) {var records=consumer.poll(Duration.ofMillis(200));if(!records.isEmpty())return records.iterator().next().value();}
            throw new AssertionError("Missing Kafka dead letter");
        }
    }
    private void block(Owner o) {jdbc.sql("ALTER TABLE banking.notifications ADD CONSTRAINT test_notification_failure CHECK(account_id<>'"+o.account()+"'::uuid)").update();}
    private void unblock() {jdbc.sql("ALTER TABLE banking.notifications DROP CONSTRAINT test_notification_failure").update();}
    private HttpResponse<String> get(UUID account,String token,int limit) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/accounts/"+account+"/notifications?limit="+limit));
        if(token!=null)request.header("Authorization","Bearer "+token);
        return http.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private Owner owner() throws Exception {
        UUID customer=UUID.randomUUID(),bank=UUID.randomUUID(),ledger=UUID.randomUUID();
        new TransactionTemplate(transactions).execute(s->{
            jdbc.sql("INSERT INTO banking.customers(id,identity_issuer,identity_subject,display_name,created_at,updated_at) VALUES(:id,:issuer,:subject,'Synthetic Notifications',now(),now())").param("id",customer).param("issuer",TOKENS.issuer()).param("subject",customer.toString()).update();
            jdbc.sql("INSERT INTO banking.accounts(id,customer_id,currency,created_at,updated_at) VALUES(:id,:customer,'VND',now(),now())").param("id",bank).param("customer",customer).update();
            jdbc.sql("INSERT INTO banking.ledger_accounts(id,code,name,account_type,normal_side,currency,bank_account_id,created_at) VALUES(:id,:code,'Synthetic Notifications','LIABILITY','CREDIT','VND',:bank,now())").param("id",ledger).param("code",ledger.toString()).param("bank",bank).update();
            jdbc.sql("UPDATE banking.accounts SET ledger_provisioned=true WHERE id=:id").param("id",bank).update();return null;
        });
        return new Owner(bank,ledger,TOKENS.token(b->b.subject(customer.toString())));
    }
    private OutboxEvent credit(Owner o) {
        UUID journal=UUID.randomUUID();return new TransactionTemplate(transactions).execute(s->{
            jdbc.sql("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES(:id,:ref,'ADJUSTMENT','VND','DRAFT','sql-fixture','operator',now())").param("id",journal).param("ref",UUID.randomUUID()).update();
            for(var entry:Map.of(CLEARING,"DEBIT",o.ledger(),"CREDIT").entrySet())
                jdbc.sql("INSERT INTO banking.ledger_postings(id,journal_id,ledger_account_id,currency,side,amount) VALUES(:id,:journal,:ledger,'VND',:side,1)").param("id",UUID.randomUUID()).param("journal",journal).param("ledger",entry.getKey()).param("side",entry.getValue()).update();
            jdbc.sql("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id=:id").param("id",journal).update();
            UUID eventId=jdbc.sql("SELECT event_id FROM banking.outbox_events WHERE aggregate_id=:id").param("id",journal).query(UUID.class).single();
            return outbox.find(eventId).orElseThrow();
        });
    }
}
