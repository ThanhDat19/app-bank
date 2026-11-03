package vn.banklab.core.statement;

import java.util.*;
import java.math.BigDecimal;
import java.time.*;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.banklab.core.support.SignedTokenServer;
import vn.banklab.core.statement.application.StatementRepository;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatementIT {
    static final SignedTokenServer TOKENS;
    static {try{TOKENS=new SignedTokenServer();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16.14-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("bank.iam.issuer-uri",TOKENS::issuer);r.add("bank.iam.jwk-set-uri",TOKENS::jwks);r.add("bank.iam.audience",()->"bank-api");r.add("bank.statements.max-entries",()->5);
    }
    @AfterAll static void closeTokens(){TOKENS.close();}
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired StatementRepository repository;
    @LocalServerPort int port;
    private final HttpClient http=HttpClient.newHttpClient();
    private final JsonMapper json=JsonMapper.builder().build();
    private static final UUID CLEARING=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private record Owner(UUID account,UUID ledger,String token){}
    private String root(Owner o){return "/api/v1/accounts/"+o.account();}
    private JsonNode tree(HttpResponse<String> r){return json.readTree(r.body());}
    private JsonNode history(Owner o)throws Exception{var r=call("POST",root(o)+"/history-snapshots",o.token(),null);assertThat(r.statusCode()).as(r.body()).isEqualTo(201);return tree(r);}
    private JsonNode statement(Owner o,String from,String to,String zone)throws Exception{var r=statementResponse(o,from,to,zone);assertThat(r.statusCode()).as(r.body()).isEqualTo(201);return tree(r);}
    private HttpResponse<String> statementResponse(Owner o,String from,String to,String zone)throws Exception{return call("POST",root(o)+"/statements",o.token(),"{\"fromDate\":\""+from+"\",\"toDate\":\""+to+"\",\"timezone\":\""+zone+"\"}");}

    @Test void stableKeysetPagesExcludeLaterAndBackdatedCommitsIncludingTies()throws Exception{
        var o=owner(true);Instant time=Instant.parse("2026-10-20T00:00:00Z");
        post(o,List.of("1"),time,true);post(o,List.of("2","3"),time,true);post(o,List.of("4"),time,true);
        var header=history(o);String id=header.path("id").asString();assertThat(header.path("entryCount").asLong()).isEqualTo(4);
        assertThat(header.path("closingBalance").asString()).isEqualTo("10");
        var expected=jdbc.sql("SELECT p.id FROM banking.ledger_postings p JOIN banking.ledger_journals j ON j.id=p.journal_id WHERE p.ledger_account_id=:id ORDER BY j.posted_at,j.id,p.id")
                .param("id",o.ledger()).query(UUID.class).list();
        var first=tree(call("GET",root(o)+"/statements/"+id+"/entries?limit=1",o.token(),null));
        post(o,List.of("5"),time.minusSeconds(3600),true);
        var ids=new ArrayList<UUID>();ids.add(UUID.fromString(first.path("items").get(0).path("postingId").asString()));long cursor=first.path("nextAfterSequence").asLong();
        while(true){var page=tree(call("GET",root(o)+"/statements/"+id+"/entries?limit=1&afterSequence="+cursor,o.token(),null));
            for(var row:page.path("items"))ids.add(UUID.fromString(row.path("postingId").asString()));
            if(page.path("nextAfterSequence").isNull())break;cursor=page.path("nextAfterSequence").asLong();}
        assertThat(ids).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(tree(call("GET",root(o)+"/statements/"+id,o.token(),null))).isEqualTo(header);
        var fresh=history(o);assertThat(fresh.path("entryCount").asLong()).isEqualTo(5);assertThat(fresh.path("closingBalance").asString()).isEqualTo("15");
    }
    @Test void timezoneCutoffOpeningRunningClosingAndEmptyPeriodAreExact()throws Exception{
        var o=owner(true);post(o,List.of("100"),Instant.parse("2026-10-20T16:59:59.999999Z"),true);
        post(o,List.of("20"),Instant.parse("2026-10-20T17:00:00Z"),true);post(o,List.of("5"),Instant.parse("2026-10-21T01:00:00Z"),false);
        post(o,List.of("7"),Instant.parse("2026-10-21T17:00:00Z"),true);
        var h=statement(o,"2026-10-21","2026-10-22","Asia/Ho_Chi_Minh");String path=root(o)+"/statements/"+h.path("id").asString();
        assertThat(h.path("openingBalance").asString()).isEqualTo("100");assertThat(h.path("creditTotal").asString()).isEqualTo("20");assertThat(h.path("debitTotal").asString()).isEqualTo("5");assertThat(h.path("closingBalance").asString()).isEqualTo("115");
        assertThat(h.path("fromAt").asString()).isEqualTo("2026-10-20T17:00:00Z");assertThat(h.path("effectiveToAt").asString()).isEqualTo("2026-10-21T17:00:00Z");assertThat(h.path("interim").asBoolean()).isFalse();
        var entries=tree(call("GET",path+"/entries",o.token(),null)).path("items");assertThat(entries.size()).isEqualTo(2);
        assertThat(entries.get(0).path("runningBalance").asString()).isEqualTo("120");assertThat(entries.get(1).path("delta").asString()).isEqualTo("-5");assertThat(entries.get(1).path("runningBalance").asString()).isEqualTo("115");
        var empty=statement(o,"2026-10-23","2026-10-24","Asia/Ho_Chi_Minh");assertThat(empty.path("entryCount").asLong()).isZero();assertThat(empty.path("openingBalance").asString()).isEqualTo("122");assertThat(empty.path("closingBalance").asString()).isEqualTo("122");
        assertThat(call("GET",root(o)+"/statements/"+empty.path("id").asString()+"/export.csv",o.token(),null).body().split("\r\n")).hasSize(1);
    }
    @Test void localMidnightUsesDst23And25HourDays()throws Exception{
        var o=owner(true);var spring=statement(o,"2026-03-08","2026-03-09","America/New_York");var autumn=statement(o,"2026-11-01","2026-11-02","America/New_York");
        assertThat(Duration.between(Instant.parse(spring.path("fromAt").asString()),Instant.parse(spring.path("requestedToAt").asString())).toHours()).isEqualTo(23);
        assertThat(Duration.between(Instant.parse(autumn.path("fromAt").asString()),Instant.parse(autumn.path("requestedToAt").asString())).toHours()).isEqualTo(25);
        assertThat(spring.path("entryCount").asLong()).isZero();
    }
    @Test void interimEndIsCappedAndInvalidRangesAndPagesAreRejected()throws Exception{
        var o=owner(true);post(o,List.of("1"),Instant.now().minusSeconds(1),true);LocalDate today=LocalDate.now(ZoneOffset.UTC);
        var h=statement(o,today.toString(),today.plusDays(1).toString(),"UTC");assertThat(h.path("interim").asBoolean()).isTrue();assertThat(h.path("effectiveToAt").asString()).isEqualTo(h.path("snapshotAt").asString());
        assertThat(statementResponse(o,today.plusDays(2).toString(),today.plusDays(3).toString(),"UTC").statusCode()).isEqualTo(422);
        assertThat(statementResponse(o,"2026-10-01","2026-10-01","UTC").statusCode()).isEqualTo(422);
        assertThat(statementResponse(o,"2025-01-01","2026-10-01","UTC").statusCode()).isEqualTo(422);
        assertThat(statementResponse(o,"2026-10-01","2026-10-02","bad/zone").statusCode()).isEqualTo(422);
        assertThat(statementResponse(o,"2026-02-30","2026-03-01","UTC").statusCode()).isEqualTo(400);
        assertThat(statementResponse(o,"0000-01-01","0000-01-02","UTC").statusCode()).isEqualTo(422);
        String path=root(o)+"/statements/"+h.path("id").asString();
        assertThat(call("GET",path+"/entries?limit=201",o.token(),null).statusCode()).isEqualTo(422);assertThat(call("GET",path+"/entries?afterSequence=-1",o.token(),null).statusCode()).isEqualTo(422);
        assertThat(call("GET",root(o)+"/statements/"+UUID.randomUUID(),o.token(),null).statusCode()).isEqualTo(404);
    }
    @Test void everyReadAndCsvRechecksOwnershipAndPreservesPrivacy()throws Exception{
        var a=owner(true);var b=owner(true);post(a,List.of("1"),Instant.parse("2026-10-20T00:00:00Z"),true);var h=history(a);String id=h.path("id").asString();
        String op=operator();
        for(String suffix:List.of("","/entries","/export.csv")){
            assertThat(call("GET",root(a)+"/statements/"+id+suffix,null,null).statusCode()).isEqualTo(401);
            assertThat(call("GET",root(a)+"/statements/"+id+suffix,op,null).statusCode()).isEqualTo(403);
            assertThat(call("GET",root(a)+"/statements/"+id+suffix,b.token(),null).statusCode()).isEqualTo(404);
            assertThat(call("GET",root(b)+"/statements/"+id+suffix,b.token(),null).statusCode()).isEqualTo(404);
        }
        assertThat(call("POST",root(a)+"/history-snapshots",op,null).statusCode()).isEqualTo(403);
        var csv=call("GET",root(a)+"/statements/"+id+"/export.csv",a.token(),null);assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Disposition").orElseThrow()).contains("attachment","statement-"+id+".csv");
        assertThat(csv.headers().firstValue("Cache-Control")).contains("no-store");assertThat(csv.headers().firstValue("Content-Type").orElseThrow()).contains("text/csv","UTF-8");
        assertThat(csv.body()).startsWith("sequence,postedAt,journalId,referenceId,kind,side,amount,delta,runningBalance\r\n").doesNotContain("issuer","subject","accountNumber","displayName","reason","token");
        var spec=tree(call("GET","/v3/api-docs",a.token(),null));
        String statementRef=spec.path("paths").path("/api/v1/accounts/{accountId}/statements").path("post").path("requestBody").path("content").path("application/json").path("schema").path("$ref").asString();
        String holdRef=spec.path("paths").path("/api/v1/holds").path("post").path("requestBody").path("content").path("application/json").path("schema").path("$ref").asString();
        assertThat(statementRef).isNotBlank().isNotEqualTo(holdRef);
        var schemas=spec.path("components").path("schemas");
        assertThat(schemas.path(statementRef.substring(statementRef.lastIndexOf('/')+1)).path("properties").has("fromDate")).isTrue();
        assertThat(schemas.path(holdRef.substring(holdRef.lastIndexOf('/')+1)).path("properties").has("accountId")).isTrue();
    }
    @Test void frozenAndClosedAccountsRemainReadableAndUnprovisionedIsRejected()throws Exception{
        var o=owner(true);var old=history(o);String op=operator();
        assertThat(call("POST","/api/v1/operations/accounts/"+o.account()+"/freeze",op,"{\"expectedVersion\":0,\"reason\":\"SECURITY_REVIEW\"}").statusCode()).isEqualTo(200);history(o);
        assertThat(call("POST","/api/v1/operations/accounts/"+o.account()+"/unfreeze",op,"{\"expectedVersion\":1,\"reason\":\"REVIEW_CLEARED\"}").statusCode()).isEqualTo(200);
        assertThat(call("POST",root(o)+"/close",o.token(),"{\"expectedVersion\":2,\"reason\":\"CUSTOMER_REQUEST\"}").statusCode()).isEqualTo(200);history(o);
        assertThat(call("GET",root(o)+"/statements/"+old.path("id").asString(),o.token(),null).statusCode()).isEqualTo(200);
        var unlinked=owner(false);assertThat(call("POST",root(unlinked)+"/history-snapshots",unlinked.token(),null).statusCode()).isEqualTo(422);
    }
    @Test void cappedSnapshotsAndPartialSqlSnapshotsCannotCommit()throws Exception{
        var o=owner(true);for(int n=0;n<6;n++)post(o,List.of("1"),Instant.parse("2026-10-20T00:00:00Z"),true);
        assertThat(call("POST",root(o)+"/history-snapshots",o.token(),null).statusCode()).isEqualTo(422);
        assertThat(jdbc.sql("SELECT count(*) FROM banking.account_history_snapshots WHERE account_id=:id").param("id",o.account()).query(Long.class).single()).isZero();
        UUID partial=UUID.randomUUID();assertThatThrownBy(()->snapshotTx().execute(s->{repository.start(partial,o.account(),o.ledger(),"HISTORY","UTC",null,null,null,null);repository.copy(partial,0);repository.complete(partial);return null;})).isInstanceOf(Exception.class);
        assertThat(jdbc.sql("SELECT count(*) FROM banking.account_history_entries WHERE snapshot_id=:id").param("id",partial).query(Long.class).single()).isZero();
        UUID unfinished=UUID.randomUUID();assertThatThrownBy(()->snapshotTx().execute(s->{repository.start(unfinished,o.account(),o.ledger(),"HISTORY","UTC",null,null,null,null);return null;})).isInstanceOf(Exception.class);
    }
    @Test void immutableRowsAndCopyFailureLeaveNoPartialSnapshot()throws Exception{
        var o=owner(true);post(o,List.of("2"),Instant.parse("2026-10-20T00:00:00Z"),true);var h=history(o);UUID id=UUID.fromString(h.path("id").asString());
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.account_history_snapshots SET closing_balance=0 WHERE id=:id").param("id",id).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.sql("DELETE FROM banking.account_history_entries WHERE snapshot_id=:id").param("id",id).update()).isInstanceOf(Exception.class);
        jdbc.sql("ALTER TABLE banking.account_history_entries ADD CONSTRAINT test_snapshot_fault CHECK(snapshot_id='"+id+"'::uuid) NOT VALID").update();
        try {assertThat(call("POST",root(o)+"/history-snapshots",o.token(),null).statusCode()).isEqualTo(500);}
        finally {jdbc.sql("ALTER TABLE banking.account_history_entries DROP CONSTRAINT test_snapshot_fault").update();}
        assertThat(jdbc.sql("SELECT count(*) FROM banking.account_history_snapshots WHERE account_id=:id").param("id",o.account()).query(Long.class).single()).isEqualTo(1);
        assertThat(history(o).path("closingBalance").asString()).isEqualTo("2");
    }
    @Test void repeatableSnapshotExcludesConcurrentCommitWithoutBlockingFinancialWriter()throws Exception{
        var o=owner(true);post(o,List.of("1"),Instant.parse("2026-10-20T00:00:00Z"),true);
        jdbc.sql("CREATE FUNCTION banking.test_history_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(1515,99);RETURN NULL;END $$").update();
        jdbc.sql("CREATE TRIGGER test_history_pause AFTER INSERT ON banking.account_history_snapshots FOR EACH ROW EXECUTE FUNCTION banking.test_history_pause()").update();
        try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var pool=Executors.newSingleThreadExecutor()){
            c.setAutoCommit(false);try(var s=c.createStatement()){s.execute("SELECT pg_advisory_xact_lock(1515,99)");}
            var future=pool.submit(()->call("POST",root(o)+"/history-snapshots",o.token(),null));
            try{
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean waiting=false;
                while(System.nanoTime()<deadline){waiting=jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event='advisory' AND query LIKE '%INSERT INTO banking.account_history_snapshots%')").query(Boolean.class).single();if(waiting)break;Thread.sleep(20);}
                assertThat(waiting).isTrue();post(o,List.of("2"),Instant.parse("2026-10-19T00:00:00Z"),true);
            }finally{c.commit();}
            var response=future.get(20,TimeUnit.SECONDS);assertThat(response.statusCode()).as(response.body()).isEqualTo(201);assertThat(tree(response).path("entryCount").asLong()).isEqualTo(1);
        }finally{jdbc.sql("DROP TRIGGER test_history_pause ON banking.account_history_snapshots").update();jdbc.sql("DROP FUNCTION banking.test_history_pause()").update();}
        assertThat(history(o).path("entryCount").asLong()).isEqualTo(2);
    }
    @Test void publicCashInTransferFeeAndReversalStaySeparateAndOriginalIsPreserved()throws Exception{
        var a=owner(true);var b=owner(true);String op=operator();
        var cash=withKey("/api/v1/operations/cash-ins",op,"{\"accountId\":\""+a.account()+"\",\"amount\":\"100\",\"currency\":\"VND\"}");assertThat(cash.statusCode()).isEqualTo(201);
        jdbc.sql("INSERT INTO banking.outgoing_policies(account_id,transaction_limit,daily_limit,fee,timezone) VALUES(:id,500,500,2,'Asia/Ho_Chi_Minh')").param("id",a.account()).update();
        var transfer=withKey("/api/v1/transfers",a.token(),"{\"sourceAccountId\":\""+a.account()+"\",\"destinationAccountId\":\""+b.account()+"\",\"amount\":\"30\",\"currency\":\"VND\"}");assertThat(transfer.statusCode()).isEqualTo(201);
        String original=tree(transfer).path("journalId").asString();var reversal=withKey("/api/v1/operations/reversals",op,"{\"originalJournalId\":\""+original+"\",\"reason\":\"OPERATOR_ERROR\"}");assertThat(reversal.statusCode()).as(reversal.body()).isEqualTo(201);
        var h=history(a);assertThat(h.path("entryCount").asLong()).isEqualTo(4);assertThat(h.path("creditTotal").asString()).isEqualTo("130");assertThat(h.path("debitTotal").asString()).isEqualTo("32");assertThat(h.path("closingBalance").asString()).isEqualTo("98");
        String entries=call("GET",root(a)+"/statements/"+h.path("id").asString()+"/entries",a.token(),null).body();assertThat(entries).contains(original,"SIMULATED_CASH_IN","INTERNAL_TRANSFER","ADJUSTMENT","REVERSAL");
        assertThat(history(b).path("closingBalance").asString()).isEqualTo("0");
    }
    @Test void twentyDigitAmountsAndNegativeHistoricalPrefixesAreNotRoundedOrHidden()throws Exception{
        var a=owner(true);String max="99999999999999999999";post(a,List.of(max),Instant.parse("2026-10-20T00:00:00Z"),true);var h=history(a);
        assertThat(h.path("closingBalance").asString()).isEqualTo(max);assertThat(call("GET",root(a)+"/statements/"+h.path("id").asString()+"/export.csv",a.token(),null).body()).contains(max).doesNotContain("E+");
        var b=owner(true);post(b,List.of("2"),Instant.parse("2026-10-20T00:00:00Z"),true);post(b,List.of("1"),Instant.parse("2026-10-19T00:00:00Z"),false);
        var statement=statement(b,"2026-10-20","2026-10-21","UTC");assertThat(statement.path("openingBalance").asString()).isEqualTo("-1");assertThat(statement.path("closingBalance").asString()).isEqualTo("1");
    }

    private TransactionTemplate tx(){return new TransactionTemplate(transactions);}
    private TransactionTemplate snapshotTx(){var t=new TransactionTemplate(transactions);t.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return t;}
    private String operator()throws Exception{return TOKENS.token(b->b.subject("statement-operator").claim("resource_access",Map.of("bank-api",Map.of("roles",List.of("OPERATOR")))));}
    private HttpResponse<String> call(String method,String path,String token,String body)throws Exception{
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(40));if(token!=null)r.header("Authorization","Bearer "+token);if(body!=null)r.header("Content-Type","application/json");
        return http.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> withKey(String path,String token,String body)throws Exception{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    private Owner owner(boolean provisioned)throws Exception{
        UUID customer=UUID.randomUUID(),bank=UUID.randomUUID(),ledger=UUID.randomUUID();tx().execute(s->{
            jdbc.sql("INSERT INTO banking.customers(id,identity_issuer,identity_subject,display_name,created_at,updated_at) VALUES(:id,:issuer,:subject,'Synthetic Statement',now(),now())").param("id",customer).param("issuer",TOKENS.issuer()).param("subject",customer.toString()).update();
            jdbc.sql("INSERT INTO banking.accounts(id,customer_id,currency,created_at,updated_at) VALUES(:id,:customer,'VND',now(),now())").param("id",bank).param("customer",customer).update();
            if(provisioned){jdbc.sql("INSERT INTO banking.ledger_accounts(id,code,name,account_type,normal_side,currency,bank_account_id,created_at) VALUES(:id,:code,'Synthetic Statement','LIABILITY','CREDIT','VND',:bank,now())").param("id",ledger).param("code",ledger.toString()).param("bank",bank).update();jdbc.sql("UPDATE banking.accounts SET ledger_provisioned=true WHERE id=:id").param("id",bank).update();}return null;
        });return new Owner(bank,provisioned?ledger:null,TOKENS.token(b->b.subject(customer.toString())));
    }
    private void post(Owner o,List<String> amounts,Instant at,boolean credit){
        UUID journal=UUID.randomUUID();BigDecimal total=amounts.stream().map(BigDecimal::new).reduce(BigDecimal.ZERO,BigDecimal::add);
        tx().execute(s->{
            jdbc.sql("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES(:id,:ref,'ADJUSTMENT','VND','DRAFT','fixture','statement-fixture',:at)").param("id",journal).param("ref",UUID.randomUUID()).param("at",Timestamp.from(at)).update();
            line(journal,CLEARING,credit?"DEBIT":"CREDIT",total);for(String amount:amounts)line(journal,o.ledger(),credit?"CREDIT":"DEBIT",new BigDecimal(amount));
            jdbc.sql("UPDATE banking.ledger_journals SET status='POSTED',posted_at=created_at WHERE id=:id").param("id",journal).update();return null;
        });
    }
    private void line(UUID journal,UUID ledger,String side,BigDecimal amount){jdbc.sql("INSERT INTO banking.ledger_postings(id,journal_id,ledger_account_id,currency,side,amount) VALUES(:id,:journal,:ledger,'VND',:side,:amount)").param("id",UUID.randomUUID()).param("journal",journal).param("ledger",ledger).param("side",side).param("amount",amount).update();}
}
