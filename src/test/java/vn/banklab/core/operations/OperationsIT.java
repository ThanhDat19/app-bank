package vn.banklab.core.operations;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.banklab.core.support.SignedTokenServer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperationsIT {
    static final SignedTokenServer TOKENS;
    static {
        try { TOKENS = new SignedTokenServer(); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.14-alpine");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("bank.iam.issuer-uri", TOKENS::issuer);
        r.add("bank.iam.jwk-set-uri", TOKENS::jwks);
        r.add("bank.iam.audience", () -> "bank-api");
    }
    @AfterAll static void closeTokens() { TOKENS.close(); }
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired vn.banklab.core.account.application.AccountService accountService;
    @Autowired vn.banklab.core.reversal.application.ReversalService reversalService;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String ROUTE = "/api/v1/operations/requests";
    private record Owner(String subject, String token, UUID id) { }

    @Test void prepareDoesNotExecuteAndDifferentCheckerFreezesWithDurablePrivateResult() throws Exception {
        var a=owner(); var maker=operator(); var checkerSubject=key(); var checker=token(checkerSubject,"OPERATOR");
        var prepared=prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",maker,key());
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(201);
        var id=id(prepared); assertThat(status(id)).isEqualTo("PENDING"); account(a,"ACTIVE",0);
        var result=approve(id,checker,0); assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(tree(result).path("status").asString()).isEqualTo("APPROVED");
        assertThat(tree(result).path("result").path("status").asString()).isEqualTo("FROZEN"); account(a,"FROZEN",1);
        assertThat(jdbc.sql("SELECT actor_subject FROM banking.account_state_changes WHERE operation_request_id=:id")
                .param("id",id).query(String.class).single()).isEqualTo(checkerSubject);
        assertThat(jdbc.sql("SELECT status FROM banking.operation_history WHERE request_id=:id ORDER BY version")
                .param("id",id).query(String.class).list()).containsExactly("PENDING","APPROVED");
        assertThat(jdbc.sql("SELECT action FROM banking.audit_events WHERE resource_id=:id ORDER BY id")
                .param("id",id).query(String.class).list()).containsExactly("OPERATION_REQUESTED","OPERATION_APPROVED");
        assertThat(result.body()).doesNotContain("issuer","subject","idempotency","requestHash",checkerSubject);
        assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(tree(call("GET",ROUTE+"/"+id,maker,null,null))).isEqualTo(tree(result));
    }
    @Test void everyEndpointRequiresVerifiedOperator() throws Exception {
        var a=owner(); var made=prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",operator(),key()); var id=id(made);
        for(String actor:List.of("",a.token())) {
            int expected=actor.isEmpty()?401:403; String token=actor.isEmpty()?null:actor;
            assertThat(call("POST",ROUTE,token,key(),intent(a.id(),"FREEZE",0L,"SECURITY_REVIEW")).statusCode()).isEqualTo(expected);
            for(String path:List.of(ROUTE,ROUTE+"/"+id,ROUTE+"/"+id+"/history")) assertThat(call("GET",path,token,null,null).statusCode()).isEqualTo(expected);
            for(String action:List.of("approve","reject","cancel")) assertThat(call("POST",ROUTE+"/"+id+"/"+action,token,null,"{\"expectedVersion\":0,\"reason\":\"NOT_JUSTIFIED\"}").statusCode()).isEqualTo(expected);
        }
        account(a,"ACTIVE",0);
    }
    @Test void renewedTokenCannotSelfApproveOrRejectAndOtherOperatorCannotCancel() throws Exception {
        var a=owner();var subject=key();var maker=token(subject,"OPERATOR");var id=id(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",maker,key()));
        problem(approve(id,token(subject,"OPERATOR"),0),422,"OPERATION_SELF_APPROVAL_FORBIDDEN");
        problem(decide(id,"reject",maker,0,"NOT_JUSTIFIED"),422,"OPERATION_SELF_APPROVAL_FORBIDDEN");
        problem(decide(id,"cancel",operator(),0,"MAKER_WITHDREW"),422,"OPERATION_NOT_MAKER");
        assertThat(status(id)).isEqualTo("PENDING");account(a,"ACTIVE",0);
    }
    @Test void makerAndCheckerCannotFreezeOwnCustomerAccount() throws Exception {
        var a=owner();var ownerOperator=token(a.subject(),"CUSTOMER","OPERATOR");
        problem(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",ownerOperator,key()),422,"ACCOUNT_SELF_OPERATION_FORBIDDEN");
        var id=id(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",operator(),key()));
        problem(approve(id,ownerOperator,0),422,"ACCOUNT_SELF_OPERATION_FORBIDDEN");
        assertThat(status(id)).isEqualTo("PENDING");account(a,"ACTIVE",0);
    }
    @Test void sameKeyReplaySkipsChangedTargetStateAndChangedIntentConflicts() throws Exception {
        var a=owner();var maker=operator();var key=key();var first=prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",maker,key);var id=id(first);
        assertThat(tree(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",maker,key))).isEqualTo(tree(first));
        var approved=approve(id,operator(),0);assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(tree(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",maker,key))).isEqualTo(tree(approved));
        problem(prepare(a.id(),"FREEZE",1L,"SECURITY_REVIEW",maker,key),409,"IDEMPOTENCY_CONFLICT");
        problem(prepare(UUID.randomUUID(),"FREEZE",0L,"SECURITY_REVIEW",maker,key),409,"IDEMPOTENCY_CONFLICT");
        assertThat(prepare(a.id(),"FREEZE",1L,"SECURITY_REVIEW",operator(),key).statusCode()).isEqualTo(201);
    }
    @Test void competingSameKeyPreparationReturnsOneIntent() throws Exception {
        var a=owner();var maker=operator();var key=key();var responses=concurrent(8,()->prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",maker,key));
        assertThat(responses).allSatisfy(r->assertThat(r.statusCode()).as(r.body()).isEqualTo(201));
        assertThat(responses.stream().map(this::tree).distinct().count()).isEqualTo(1);account(a,"ACTIVE",0);
    }
    @Test void competingSameCheckerApprovalExecutesOnceAndReplayReturnsStoredResult() throws Exception {
        var a=owner();var maker=operator();var checker=operator();var id=id(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",maker,key()));
        var responses=concurrent(8,()->approve(id,checker,0));
        assertThat(responses).allSatisfy(r->assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
        assertThat(responses.stream().map(this::tree).distinct().count()).isEqualTo(1);account(a,"FROZEN",1);
        assertThat(jdbc.sql("SELECT count(*) FROM banking.account_state_changes WHERE operation_request_id=:id").param("id",id).query(Long.class).single()).isEqualTo(1);
        problem(approve(id,operator(),0),409,"OPERATION_ALREADY_DECIDED");
        problem(approve(id,checker,99),409,"OPERATION_VERSION_CONFLICT");
    }
    @Test void staleAccountVersionRollsApprovalBackAndUnfreezeRequiresNewIntent() throws Exception {
        var a=owner();var maker=operator();var checker=operator();var first=id(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",maker,key()));
        var stale=id(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",maker,key()));
        var receipt=approve(first,checker,0);assertThat(receipt.statusCode()).as(receipt.body()).isEqualTo(200);
        problem(approve(stale,checker,0),409,"ACCOUNT_VERSION_CONFLICT");assertThat(status(stale)).isEqualTo("PENDING");
        var unfreeze=id(prepare(a.id(),"UNFREEZE",1L,"REVIEW_CLEARED",maker,key()));
        assertThat(approve(unfreeze,checker,0).statusCode()).isEqualTo(200);account(a,"ACTIVE",2);
        assertThat(tree(approve(first,checker,0))).isEqualTo(tree(receipt));
    }
    @Test void rejectionAndMakerCancellationAreTerminalAndAudited() throws Exception {
        var a=owner();var maker=operator();var checker=operator();
        var rejected=id(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",maker,key()));
        problem(decide(rejected,"reject",checker,3,"NOT_JUSTIFIED"),409,"OPERATION_VERSION_CONFLICT");
        problem(decide(rejected,"reject",checker,0,"free text"),422,"OPERATION_REASON_INVALID");
        var r=decide(rejected,"reject",checker,0,"NOT_JUSTIFIED");assertThat(r.statusCode()).isEqualTo(200);
        assertThat(tree(decide(rejected,"reject",checker,0,"NOT_JUSTIFIED"))).isEqualTo(tree(r));
        problem(approve(rejected,checker,1),409,"OPERATION_ALREADY_DECIDED");
        var cancelled=id(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",maker,key()));
        var c=decide(cancelled,"cancel",maker,0,"MAKER_WITHDREW");assertThat(c.statusCode()).isEqualTo(200);
        assertThat(tree(decide(cancelled,"cancel",maker,0,"MAKER_WITHDREW"))).isEqualTo(tree(c));
        problem(approve(cancelled,checker,1),409,"OPERATION_ALREADY_DECIDED");account(a,"ACTIVE",0);
        assertThat(jdbc.sql("SELECT count(*) FROM banking.operation_history WHERE request_id IN (:a,:b)").param("a",rejected).param("b",cancelled).query(Long.class).single()).isEqualTo(4);
    }
    @Test void approveVersusCancelHasOneTerminalWinner() throws Exception {
        var a=owner();var maker=operator();var checker=operator();var id=id(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",maker,key()));
        var sequence=new AtomicInteger();var rs=concurrent(2,()->sequence.getAndIncrement()==0?approve(id,checker,0):decide(id,"cancel",maker,0,"MAKER_WITHDREW"));
        assertThat(rs.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200,409);
        String state=status(id);assertThat(state).isIn("APPROVED","CANCELLED");account(a,state.equals("APPROVED")?"FROZEN":"ACTIVE",state.equals("APPROVED")?1:0);
    }
    @Test void reversalApprovalPostsExactInverseOnceWithCheckerActorAuditAndOutbox() throws Exception {
        var a=owner();var original=journal(fund(a,"100"));var maker=operator();var checkerSubject=key();var checker=token(checkerSubject,"OPERATOR");
        var id=id(prepare(original,"REVERSAL",null,"OPERATOR_ERROR",maker,key()));assertThat(balance(a)).isEqualByComparingTo("100");
        var rs=concurrent(6,()->approve(id,checker,0));assertThat(rs).allSatisfy(r->assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
        var result=tree(rs.getFirst()).path("result");var inverse=UUID.fromString(result.path("journalId").asString());
        assertInverse(original,inverse);assertThat(balance(a)).isEqualByComparingTo("0");assertThat(completed(original)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT actor_subject FROM banking.ledger_journals WHERE id=:id").param("id",inverse).query(String.class).single()).isEqualTo(checkerSubject);
        assertThat(jdbc.sql("SELECT count(*) FROM banking.audit_events WHERE resource_id=:id").param("id",inverse).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM banking.outbox_events WHERE aggregate_id=:id").param("id",inverse).query(Long.class).single()).isEqualTo(1);
    }
    @Test void insufficientFundsRetainsPendingIntentAndCanRetryAfterFunding() throws Exception {
        var a=owner();var b=owner();var original=journal(fund(a,"100"));assertThat(transfer(a,b,key(),"60").statusCode()).isEqualTo(201);
        var checker=operator();var id=id(prepare(original,"REVERSAL",null,"OPERATOR_ERROR",operator(),key()));
        problem(approve(id,checker,0),422,"LEDGER_INSUFFICIENT_FUNDS");assertThat(status(id)).isEqualTo("PENDING");assertThat(completed(original)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM banking.operation_history WHERE request_id=:id").param("id",id).query(Long.class).single()).isEqualTo(1);
        fund(a,"60");assertThat(approve(id,checker,0).statusCode()).isEqualTo(200);assertThat(balance(a)).isEqualByComparingTo("0");
    }
    @Test void differentApprovedRequestsCannotReverseOriginalTwice() throws Exception {
        var a=owner();var original=journal(fund(a,"80"));var maker=operator();var checker=operator();
        var one=id(prepare(original,"REVERSAL",null,"OPERATOR_ERROR",maker,key()));var two=id(prepare(original,"REVERSAL",null,"OPERATOR_ERROR",maker,key()));
        var n=new AtomicInteger();var rs=concurrent(2,()->approve(n.getAndIncrement()==0?one:two,checker,0));
        assertThat(rs.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200,409);
        problem(rs.stream().filter(r->r.statusCode()==409).findFirst().orElseThrow(),409,"REVERSAL_ALREADY_EXISTS");assertThat(completed(original)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"immediate","commit"})
    void operationCompletionFaultRollsBackReversalMoneyHistoryOutboxAndAudit(String phase) throws Exception {
        var a=owner();var original=journal(fund(a,"100"));var checker=operator();var id=id(prepare(original,"REVERSAL",null,"OPERATOR_ERROR",operator(),key()));
        long events=jdbc.sql("SELECT count(*) FROM banking.outbox_events").query(Long.class).single();
        long audit=jdbc.sql("SELECT count(*) FROM banking.audit_events").query(Long.class).single();
        if(phase.equals("immediate")) jdbc.sql("ALTER TABLE banking.operation_requests ADD CONSTRAINT injected_operation_failure CHECK(id<>'"+id+"' OR status<>'APPROVED')").update();
        else {
            jdbc.sql("CREATE FUNCTION banking.injected_operation_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='APPROVED' THEN RAISE EXCEPTION 'Injected commit failure' USING ERRCODE='23514'; END IF; RETURN NULL; END $$").update();
            jdbc.sql("CREATE CONSTRAINT TRIGGER injected_operation_commit AFTER UPDATE ON banking.operation_requests DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.injected_operation_commit()").update();
        }
        try {problem(approve(id,checker,0),500,"INTERNAL_ERROR");}finally{
            if(phase.equals("immediate")) jdbc.sql("ALTER TABLE banking.operation_requests DROP CONSTRAINT injected_operation_failure").update();
            else {jdbc.sql("DROP TRIGGER injected_operation_commit ON banking.operation_requests").update();jdbc.sql("DROP FUNCTION banking.injected_operation_commit()").update();}
        }
        assertThat(status(id)).isEqualTo("PENDING");assertThat(balance(a)).isEqualByComparingTo("100");assertThat(completed(original)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM banking.outbox_events").query(Long.class).single()).isEqualTo(events);
        assertThat(jdbc.sql("SELECT count(*) FROM banking.audit_events").query(Long.class).single()).isEqualTo(audit);
        assertThat(approve(id,checker,0).statusCode()).isEqualTo(200);
    }
    @Test void legacyEndpointsAndApplicationEntryPointsCannotBypassApproval() throws Exception {
        var a=owner();var op=operator();var original=journal(fund(a,"10"));
        problem(call("POST","/api/v1/operations/accounts/"+a.id()+"/freeze",op,null,"{\"expectedVersion\":0,\"reason\":\"SECURITY_REVIEW\"}"),409,"OPERATION_APPROVAL_REQUIRED");
        problem(call("POST","/api/v1/operations/reversals",op,key(),"{\"originalJournalId\":\""+original+"\",\"reason\":\"OPERATOR_ERROR\"}"),409,"OPERATION_APPROVAL_REQUIRED");
        var jwt=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("verified-test-context").header("alg","RS256").issuer(TOKENS.issuer()).subject(key()).build();
        var auth=new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            assertThatThrownBy(()->accountService.operate(a.id(),0,vn.banklab.core.account.domain.AccountAction.FREEZE,vn.banklab.core.account.domain.AccountReason.SECURITY_REVIEW))
                    .isInstanceOf(vn.banklab.core.shared.application.BusinessException.class).extracting("code").isEqualTo("OPERATION_APPROVAL_REQUIRED");
            assertThatThrownBy(()->reversalService.create(key(),original,"OPERATOR_ERROR")).isInstanceOf(vn.banklab.core.shared.application.BusinessException.class).extracting("code").isEqualTo("OPERATION_APPROVAL_REQUIRED");
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
        account(a,"ACTIVE",0);assertThat(balance(a)).isEqualByComparingTo("10");
    }
    @Test void sqlRejectsUnapprovedExecutionTamperingAndIncompleteClaims() throws Exception {
        var a=owner();var id=id(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",operator(),key()));
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.accounts SET status='FROZEN',version=version+1 WHERE id=:id").param("id",a.id()).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for(String sql:List.of("UPDATE banking.operation_requests SET target_id='"+UUID.randomUUID()+"' WHERE id='"+id+"'",
                "UPDATE banking.operation_requests SET status='EXECUTING',checker_issuer=maker_issuer,checker_subject=maker_subject,execution_txid=pg_current_xact_id()::text WHERE id='"+id+"'",
                "UPDATE banking.operation_requests SET status='EXECUTING',checker_issuer='checker',checker_subject='checker',execution_txid=pg_current_xact_id()::text WHERE id='"+id+"'",
                "DELETE FROM banking.operation_history WHERE request_id='"+id+"'",
                "UPDATE banking.operation_history SET reason='forged' WHERE request_id='"+id+"'",
                "DELETE FROM banking.operation_requests WHERE id='"+id+"'")) {
            assertThatThrownBy(()->jdbc.sql(sql).update()).as(sql).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThat(status(id)).isEqualTo("PENDING");account(a,"ACTIVE",0);
    }
    @Test void queueHistoryBoundsValidationAndSwaggerAreExplicit() throws Exception {
        var a=owner();var op=operator();var id=id(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",op,key()));
        var queue=call("GET",ROUTE+"?status=PENDING&limit=1",op,null,null);assertThat(queue.statusCode()).isEqualTo(200);assertThat(tree(queue).size()).isEqualTo(1);
        assertThat(call("GET",ROUTE+"?limit=101",op,null,null).statusCode()).isEqualTo(400);
        assertThat(call("GET",ROUTE+"/"+id+"/history?limit=0",op,null,null).statusCode()).isEqualTo(400);
        problem(call("GET",ROUTE+"?status=EXECUTING",op,null,null),422,"OPERATION_STATUS_INVALID");
        assertThat(prepare(a.id(),"FREEZE",-1L,"SECURITY_REVIEW",op,key()).statusCode()).isEqualTo(400);
        problem(prepare(a.id(),"REVERSAL",0L,"OPERATOR_ERROR",op,key()),422,"OPERATION_REQUEST_INVALID");
        var swagger=tree(call("GET","/v3/api-docs",null,null,null));assertThat(swagger.path("paths").has(ROUTE+"/{id}/approve")).isTrue();
        var history=call("GET",ROUTE+"/"+id+"/history",op,null,null);assertThat(history.body()).doesNotContain("issuer","subject","maker","checker");
    }
    @Test void sqlCannotCommitFakeApprovalAndTerminalReceiptsAreImmutable() throws Exception {
        var a=owner();var id=id(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",operator(),key()));
        try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var s=c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("UPDATE banking.operation_requests SET status='EXECUTING',checker_issuer='forged',checker_subject='checker',execution_txid=pg_current_xact_id()::text WHERE id='"+id+"'");
            s.execute("UPDATE banking.operation_requests SET status='APPROVED',version=1,decision_reason='APPROVED',completed_at=clock_timestamp(),result='{}' WHERE id='"+id+"'");
            assertThatThrownBy(c::commit).isInstanceOf(SQLException.class);c.rollback();
        }
        assertThat(status(id)).isEqualTo("PENDING");account(a,"ACTIVE",0);
        assertThat(approve(id,operator(),0).statusCode()).isEqualTo(200);
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.operation_requests SET result='{}' WHERE id=:id").param("id",id).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.operation_requests SET status='PENDING' WHERE id=:id").param("id",id).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void callerCannotAddIdentityAmountOrExecutionArguments() throws Exception {
        var a=owner();var op=operator();String body=intent(a.id(),"FREEZE",0L,"SECURITY_REVIEW");
        for(String field:List.of("\"maker\":\"someone\"","\"amount\":\"1\"","\"status\":\"APPROVED\"")) {
            problem(call("POST",ROUTE,op,key(),body.replace("}",","+field+"}")),400,"INVALID_REQUEST");
        }
        var id=id(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",op,key()));
        problem(call("POST",ROUTE+"/"+id+"/approve",operator(),null,"{\"expectedVersion\":0,\"targetId\":\""+UUID.randomUUID()+"\"}"),400,"INVALID_REQUEST");
        assertThat(status(id)).isEqualTo("PENDING");account(a,"ACTIVE",0);
    }
    @Test void validSignatureWithUntrustedIssuerCannotBecomeADistinctChecker() throws Exception {
        var a=owner();var subject=key();var maker=token(subject,"OPERATOR");var id=id(prepare(a.id(),"FREEZE",0L,"SECURITY_REVIEW",maker,key()));
        var otherIssuer=TOKENS.token(b->b.issuer(TOKENS.issuer()+"-untrusted").subject(subject).claim("resource_access",Map.of("bank-api",Map.of("roles",List.of("OPERATOR")))));
        problem(approve(id,otherIssuer,0),401,"UNAUTHENTICATED");assertThat(status(id)).isEqualTo("PENDING");account(a,"ACTIVE",0);
    }
    private static String intent(UUID target,String type,Long version,String reason) {return "{\"type\":\""+type+"\",\"targetId\":\""+target+"\",\"expectedAccountVersion\":"+version+",\"reason\":\""+reason+"\"}";}
    private HttpResponse<String> prepare(UUID target,String type,Long version,String reason,String maker,String key) throws Exception {return call("POST",ROUTE,maker,key,intent(target,type,version,reason));}
    private HttpResponse<String> approve(UUID id,String actor,long version) throws Exception{return call("POST",ROUTE+"/"+id+"/approve",actor,null,"{\"expectedVersion\":"+version+"}");}
    private HttpResponse<String> decide(UUID id,String action,String actor,long version,String reason) throws Exception{return call("POST",ROUTE+"/"+id+"/"+action,actor,null,"{\"expectedVersion\":"+version+",\"reason\":\""+reason+"\"}");}
    private UUID id(HttpResponse<String> r){assertThat(r.statusCode()).as(r.body()).isEqualTo(201);return UUID.fromString(tree(r).path("id").asString());}
    private String status(UUID id){return jdbc.sql("SELECT status FROM banking.operation_requests WHERE id=:id").param("id",id).query(String.class).single();}
    private void account(Owner a,String status,long version){assertThat(jdbc.sql("SELECT status,version FROM banking.accounts WHERE id=:id").param("id",a.id()).query().singleRow()).containsEntry("status",status).containsEntry("version",version);}
    private static String operator() throws Exception{return token(key(),"OPERATOR");}
    private Owner owner() throws Exception {
        var subject = key(); var token = token(subject, "CUSTOMER");
        var customer = call("POST", "/api/v1/customers", token, null, "{\"displayName\":\"Reversal Demo\"}"); assertThat(customer.statusCode()).isEqualTo(201);
        assertThat(call("POST", "/api/v1/operations/customers/" + tree(customer).path("id").asString() + "/kyc-decisions", token(key(), "OPERATOR"), null,
                "{\"expectedVersion\":0,\"decision\":\"VERIFIED\",\"reason\":\"DOCUMENTS_MATCH\"}").statusCode()).isEqualTo(200);
        var account = call("POST", "/api/v1/accounts", token, null, "{\"currency\":\"VND\"}"); assertThat(account.statusCode()).isEqualTo(201);
        var owner = new Owner(subject, token, UUID.fromString(tree(account).path("id").asString()));
        assertThat(call("POST", "/api/v1/ledger/accounts/" + owner.id() + "/provision", token, null, null).statusCode()).isEqualTo(200);
        return owner;
    }
    private HttpResponse<String> cashIn(Owner a, String amount, String op, String key) throws Exception { return call("POST", "/api/v1/operations/cash-ins", op, key, "{\"accountId\":\"" + a.id() + "\",\"amount\":\"" + amount + "\",\"currency\":\"VND\"}"); }
    private HttpResponse<String> fund(Owner a, String amount) throws Exception { var r = cashIn(a, amount, token(key(), "OPERATOR"), key()); assertThat(r.statusCode()).as(r.body()).isEqualTo(201); return r; }
    private HttpResponse<String> transfer(Owner a, Owner b, String key, String amount) throws Exception { return call("POST", "/api/v1/transfers", a.token(), key, transferBody(a, b, amount)); }
    private static String transferBody(Owner a, Owner b, String amount) { return "{\"sourceAccountId\":\"" + a.id() + "\",\"destinationAccountId\":\"" + b.id() + "\",\"amount\":\"" + amount + "\",\"currency\":\"VND\"}"; }
    private BigDecimal balance(Owner a) { return jdbc.sql("SELECT banking.ledger_balance(id) FROM banking.ledger_accounts WHERE bank_account_id=:id").param("id", a.id()).query(BigDecimal.class).single(); }
    private void balances(Owner a, String expectedA, Owner b, String expectedB) { assertThat(balance(a)).isEqualByComparingTo(expectedA); assertThat(balance(b)).isEqualByComparingTo(expectedB); }
    private long completed(UUID original) { return jdbc.sql("SELECT count(*) FROM banking.reversals WHERE original_journal_id=:id AND journal_id IS NOT NULL").param("id", original).query(Long.class).single(); }
    private long claimCount(String key) { return jdbc.sql("SELECT count(*) FROM banking.reversals WHERE idempotency_key=:key").param("key", key).query(Long.class).single(); }
    private String lines(UUID journal) { return jdbc.sql("SELECT string_agg(ledger_account_id::text || ':' || side || ':' || amount::text,',' ORDER BY id) FROM banking.ledger_postings WHERE journal_id=:id").param("id", journal).query(String.class).single(); }
    private void assertInverse(UUID original, UUID inverse) {
        assertThat(jdbc.sql("SELECT count(*) FROM banking.ledger_postings o JOIN banking.ledger_postings r ON r.ledger_account_id=o.ledger_account_id AND r.amount=o.amount AND r.currency=o.currency AND r.side<>o.side WHERE o.journal_id=:original AND r.journal_id=:inverse")
                .param("original", original).param("inverse", inverse).query(Long.class).single()).isEqualTo(2);
    }
    private UUID journal(HttpResponse<String> r) { return UUID.fromString(tree(r).path("journalId").asString()); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static String token(String subject, String... roles) throws Exception { return TOKENS.token(b -> b.subject(subject).claim("resource_access", Map.of("bank-api", Map.of("roles", List.of(roles))))); }
    private JsonNode tree(HttpResponse<String> r) { return json.readTree(r.body()); }
    private HttpResponse<String> call(String method, String path, String token, String key, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(path.startsWith("/v3/api-docs") ? 60 : 40)).header("X-Correlation-ID", "reversal-010");
        if (token != null) builder.header("Authorization", "Bearer " + token); if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private void problem(HttpResponse<String> r, int status, String code) { assertThat(r.statusCode()).as(r.body()).isEqualTo(status); assertThat(tree(r).path("code").asString()).isEqualTo(code); assertThat(tree(r).path("correlationId").asString()).isEqualTo("reversal-010"); }
    private static <T> List<T> concurrent(int count, Callable<T> action) throws Exception {
        var ready = new CountDownLatch(count); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> { ready.countDown(); if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout"); return action.call(); }));
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue(); start.countDown(); var result = new ArrayList<T>(); for (var f : futures) result.add(f.get(60, TimeUnit.SECONDS)); return result;
        }
    }
}
