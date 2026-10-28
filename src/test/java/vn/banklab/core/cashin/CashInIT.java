package vn.banklab.core.cashin;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
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
class CashInIT {
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
    @AfterAll
    static void closeTokens() { TOKENS.close(); }
    @LocalServerPort
    int port;
    @Autowired
    JdbcClient jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String ROUTE = "/api/v1/operations/cash-ins";
    private record Owner(String token, UUID accountId) { }

    @Test
    void postsExactBalancedJournalAndPrivateImmutableReceipt() throws Exception {
        var owner = owner(true);
        var operatorSubject = UUID.randomUUID().toString();
        var op = token(operatorSubject, "OPERATOR");
        var key = key();
        var response = cashIn(owner, op, key, "500000");
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var receipt = tree(response);
        assertThat(receipt.path("amount").asString()).isEqualTo("500000");
        assertThat(receipt.path("currency").asString()).isEqualTo("VND");
        assertThat(receipt.path("status").asString()).isEqualTo("POSTED");
        assertThat(receipt.has("actor_subject")).isFalse();
        var path = response.headers().firstValue("Location").orElseThrow();
        assertThat(tree(call("GET", path, op, null, null))).isEqualTo(receipt);
        problem(call("GET", path, token(key(), "OPERATOR"), null, null), 404, "CASH_IN_NOT_FOUND");
        problem(call("GET", ROUTE + "/" + UUID.randomUUID(), op, null, null), 404, "CASH_IN_NOT_FOUND");
        problem(call("GET", path, owner.token(), null, null), 403, "ACCESS_DENIED");
        // A renewed JWT for the same issuer/subject retains the namespace.
        assertThat(tree(cashIn(owner, token(operatorSubject, "OPERATOR"), key, "500000"))).isEqualTo(receipt);
        assertThat(balance(owner)).isEqualByComparingTo("500000");
        assertThat(commandCount(key)).isEqualTo(1);
        var journal = UUID.fromString(receipt.path("journalId").asString());
        assertThat(jdbc.sql("SELECT actor_subject FROM banking.ledger_journals WHERE id=:id").param("id", journal)
                .query(String.class).single()).isEqualTo(operatorSubject);
        assertThat(jdbc.sql("SELECT sum(CASE WHEN side='DEBIT' THEN amount ELSE -amount END) FROM banking.ledger_postings WHERE journal_id=:id")
                .param("id", journal).query(BigDecimal.class).single()).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM banking.ledger_postings p JOIN banking.ledger_accounts a ON a.id=p.ledger_account_id WHERE p.journal_id=:id AND p.side='DEBIT' AND a.code='VND_CASH_CLEARING'")
                .param("id", journal).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void retryAfterLostResponseAndFreezeReplaysButChangedPayloadConflicts() throws Exception {
        var owner = owner(true);
        var other = owner(true);
        var op = token(key(), "OPERATOR");
        var key = key();
        var original = tree(cashIn(owner, op, key, "100"));
        assertThat(freeze(owner, op).statusCode()).isEqualTo(200);
        var replay = cashIn(owner, op, key, "100");
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(tree(replay)).isEqualTo(original);
        problem(cashIn(owner, op, key, "101"), 409, "IDEMPOTENCY_CONFLICT");
        problem(cashIn(other, op, key, "100"), 409, "IDEMPOTENCY_CONFLICT");
        problem(cashIn(owner, op, key(), "100"), 409, "ACCOUNT_STATE_INVALID");
        assertThat(balance(owner)).isEqualByComparingTo("100");
        assertThat(balance(other)).isEqualByComparingTo("0");
    }

    @Test
    void concurrentDuplicateRequestsReturnOneJournalAndIdenticalReceipts() throws Exception {
        var owner = owner(true);
        var op = token(key(), "OPERATOR");
        var key = key();
        var replies = concurrent(8, () -> cashIn(owner, op, key, "75"));
        assertThat(replies).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
        assertThat(replies.stream().map(this::tree).distinct().count()).isEqualTo(1);
        assertThat(balance(owner)).isEqualByComparingTo("75");
        assertThat(commandCount(key)).isEqualTo(1);
        assertThat(journalCount(owner)).isEqualTo(1);
    }

    @Test
    void concurrentDifferentPayloadsWithSameKeyHaveExactlyOneWinner() throws Exception {
        var owner = owner(true);
        var op = token(key(), "OPERATOR");
        var key = key();
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        var replies = concurrent(2, () -> cashIn(owner, op, key, sequence.getAndIncrement() == 0 ? "70" : "80"));
        assertThat(replies.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201, 409);
        var winner = replies.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow();
        assertThat(balance(owner)).isEqualByComparingTo(tree(winner).path("amount").asString());
        assertThat(commandCount(key)).isEqualTo(1);
        problem(replies.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(), 409, "IDEMPOTENCY_CONFLICT");
    }

    @Test
    void distinctCommandsSerializeWithoutLosingCreditsAndActorsHaveSeparateNamespaces() throws Exception {
        var owner = owner(true);
        var op = token(key(), "OPERATOR");
        var replies = concurrent(8, () -> cashIn(owner, op, key(), "10"));
        assertThat(replies).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
        var key = key();
        assertThat(cashIn(owner, op, key, "1").statusCode()).isEqualTo(201);
        assertThat(cashIn(owner, token(key(), "OPERATOR"), key, "2").statusCode()).isEqualTo(201);
        assertThat(balance(owner)).isEqualByComparingTo("83");
        assertThat(journalCount(owner)).isEqualTo(10);
    }

    @Test
    void requiresVerifiedOperatorAndDoesNotReserveKeysForRejectedRequests() throws Exception {
        var owner = owner(true);
        var key = key();
        problem(cashIn(owner, null, key, "1"), 401, "UNAUTHENTICATED");
        problem(cashIn(owner, "invalid", key, "1"), 401, "UNAUTHENTICATED");
        problem(cashIn(owner, owner.token(), key, "1"), 403, "ACCESS_DENIED");
        assertThat(commandCount(key)).isZero();
        assertThat(balance(owner)).isEqualByComparingTo("0");
    }

    @Test
    void rejectsUnprovisionedMissingClosedAndFrozenAccountsWithoutRetainingClaim() throws Exception {
        var op = token(key(), "OPERATOR");
        var key = key();
        var owner = owner(false);
        problem(cashIn(owner, op, key, "1"), 422, "LEDGER_NOT_PROVISIONED");
        assertThat(commandCount(key)).isZero();
        assertThat(call("POST", "/api/v1/ledger/accounts/" + owner.accountId() + "/provision", owner.token(), null, null).statusCode()).isEqualTo(200);
        assertThat(cashIn(owner, op, key, "1").statusCode()).isEqualTo(201);
        var missing = new Owner(owner.token(), UUID.randomUUID());
        var missingKey = key();
        problem(cashIn(missing, op, missingKey, "1"), 404, "ACCOUNT_NOT_FOUND");
        assertThat(commandCount(missingKey)).isZero();
        var frozen = owner(true);
        assertThat(freeze(frozen, op).statusCode()).isEqualTo(200);
        var frozenKey = key();
        problem(cashIn(frozen, op, frozenKey, "1"), 409, "ACCOUNT_STATE_INVALID");
        assertThat(commandCount(frozenKey)).isZero();
        var closed = owner(true);
        assertThat(close(closed).statusCode()).isEqualTo(200);
        var closedKey = key();
        problem(cashIn(closed, op, closedKey, "1"), 409, "ACCOUNT_STATE_INVALID");
        assertThat(commandCount(closedKey)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "1.0", "01", "1e3", "NaN", "Infinity", "100000000000000000000", " 1", ""})
    void rejectsNonCanonicalOrOutOfRangeAmounts(String amount) throws Exception {
        var key = key();
        var body = "{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":\"" + amount + "\",\"currency\":\"VND\"}";
        problem(call("POST", ROUTE, token(key(), "OPERATOR"), key, body), 400, "VALIDATION_FAILED");
        assertThat(commandCount(key)).isZero();
    }

    @Test
    void validatesHeadersCurrencyAndJsonTypesAndExposesSwaggerContract() throws Exception {
        var owner = owner(true);
        var op = token(key(), "OPERATOR");
        problem(cashIn(owner, op, null, "1"), 400, "INVALID_REQUEST");
        problem(cashIn(owner, op, "bad key", "1"), 400, "VALIDATION_FAILED");
        problem(cashIn(owner, op, "x".repeat(129), "1"), 400, "VALIDATION_FAILED");
        var valid = body(owner, "1");
        problem(call("POST", ROUTE, op, key(), valid.replace("\"VND\"", "\"USD\"")), 400, "VALIDATION_FAILED");
        problem(call("POST", ROUTE, op, key(), valid.replace("\"amount\":\"1\"", "\"amount\":1")), 400, "INVALID_REQUEST");
        problem(call("POST", ROUTE, op, key(), "{}"), 400, "VALIDATION_FAILED");
        var docs = tree(call("GET", "/v3/api-docs", null, null, null));
        var operation = docs.path("paths").path(ROUTE).path("post");
        assertThat(operation.path("operationId").asString()).isEqualTo("createSimulatedCashIn");
        assertThat(operation.path("parameters").toString()).contains("Idempotency-Key");
        assertThat(operation.path("responses").has("409")).isTrue();
        assertThat(docs.path("components").path("schemas").path("CashInRequest").path("properties").path("amount").path("type").asString()).isEqualTo("string");
        assertThat(balance(owner)).isEqualByComparingTo("0");
    }

    @Test
    void maximumAmountIsExactAndBalanceOverflowRollsBackNewClaim() throws Exception {
        var owner = owner(true);
        var op = token(key(), "OPERATOR");
        var maximum = "99999999999999999999";
        var response = cashIn(owner, op, key(), maximum);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"amount\":\"" + maximum + "\"");
        var failedKey = key();
        problem(cashIn(owner, op, failedKey, "1"), 422, "LEDGER_BALANCE_LIMIT");
        assertThat(commandCount(failedKey)).isZero();
        assertThat(journalCount(owner)).isEqualTo(1);
        assertThat(balance(owner)).isEqualByComparingTo(maximum);
    }

    @Test
    void journalAndReceiptFailuresRollBackAllMoneyAndSameKeyCanRetry() throws Exception {
        var owner = owner(true);
        var opSubject = key();
        var op = token(opSubject, "OPERATOR");
        var key = key();
        jdbc.sql("ALTER TABLE banking.ledger_journals ADD CONSTRAINT test_cash_in_finalize CHECK (actor_subject <> '" + opSubject + "' OR kind <> 'SIMULATED_CASH_IN' OR status <> 'POSTED')").update();
        try {
            problem(cashIn(owner, op, key, "10"), 500, "INTERNAL_ERROR");
            assertEmpty(owner, key);
        } finally { jdbc.sql("ALTER TABLE banking.ledger_journals DROP CONSTRAINT test_cash_in_finalize").update(); }
        // Fail after journal finalization, during receipt completion.
        jdbc.sql("ALTER TABLE banking.cash_ins ADD CONSTRAINT test_cash_in_receipt CHECK (idempotency_key <> '" + key + "' OR journal_id IS NULL)").update();
        try {
            problem(cashIn(owner, op, key, "10"), 500, "INTERNAL_ERROR");
            assertEmpty(owner, key);
        } finally { jdbc.sql("ALTER TABLE banking.cash_ins DROP CONSTRAINT test_cash_in_receipt").update(); }
        assertThat(cashIn(owner, op, key, "10").statusCode()).isEqualTo(201);
        assertThat(balance(owner)).isEqualByComparingTo("10");
        assertThat(journalCount(owner)).isEqualTo(1);
    }

    @Test
    void deferredCommitFailureDoesNotReturnSuccessOrLeaveClaimOrJournal() throws Exception {
        var owner = owner(true);
        var op = token(key(), "OPERATOR");
        var key = key();
        jdbc.sql("CREATE FUNCTION banking.test_cash_in_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected commit failure' USING ERRCODE='23514'; END $$").update();
        jdbc.sql("CREATE CONSTRAINT TRIGGER test_cash_in_commit AFTER INSERT ON banking.cash_ins DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.test_cash_in_commit()").update();
        try {
            problem(cashIn(owner, op, key, "10"), 500, "INTERNAL_ERROR");
            assertEmpty(owner, key);
        } finally {
            jdbc.sql("DROP TRIGGER test_cash_in_commit ON banking.cash_ins").update();
            jdbc.sql("DROP FUNCTION banking.test_cash_in_commit()").update();
        }
        assertThat(cashIn(owner, op, key, "10").statusCode()).isEqualTo(201);
    }

    @Test
    void databaseRejectsPendingClaimsAndReceiptMutationOrKeyDeletion() throws Exception {
        var owner = owner(true);
        assertSqlGuard(() -> jdbc.sql("""
                INSERT INTO banking.cash_ins (id,actor_issuer,actor_subject,idempotency_key,request_hash,account_id,amount)
                VALUES (:id,'sql-fixture','sql-fixture',:key,:hash,:account,1)
                """).param("id", UUID.randomUUID()).param("key", key()).param("hash", "a".repeat(64)).param("account", owner.accountId()).update());
        var receipt = tree(cashIn(owner, token(key(), "OPERATOR"), key(), "10"));
        var id = UUID.fromString(receipt.path("id").asString());
        for (String column : List.of("amount=11", "idempotency_key='changed'", "request_hash='" + "b".repeat(64) + "'", "journal_id=NULL,posted_at=NULL")) {
            assertSqlGuard(() -> jdbc.sql("UPDATE banking.cash_ins SET " + column + " WHERE id=:id").param("id", id).update());
        }
        assertSqlGuard(() -> jdbc.sql("DELETE FROM banking.cash_ins WHERE id=:id").param("id", id).update());
        assertSqlGuard(() -> jdbc.sql("TRUNCATE banking.cash_ins").update());
        // A new receipt cannot claim someone else's valid journal/reference/actor.
        assertSqlGuard(() -> jdbc.sql("""
                INSERT INTO banking.cash_ins (id,actor_issuer,actor_subject,idempotency_key,request_hash,account_id,amount,journal_id,posted_at)
                SELECT :id,actor_issuer,actor_subject,:key,request_hash,account_id,amount,journal_id,posted_at
                FROM banking.cash_ins WHERE id=:original
                """).param("id", UUID.randomUUID()).param("key", key()).param("original", id).update(), "23505");
    }

    @Test
    void cashInRacesWithFreezeAndCloseRespectTheCommittedAccountState() throws Exception {
        var op = token(key(), "OPERATOR");
        var frozen = owner(true);
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        var replies = concurrent(2, () -> sequence.getAndIncrement() == 0 ? freeze(frozen, op) : cashIn(frozen, op, key(), "10"));
        assertThat(replies.stream().map(HttpResponse::statusCode).toList()).contains(200).allMatch(s -> s == 200 || s == 201 || s == 409);
        assertThat(balance(frozen)).isEqualByComparingTo(replies.stream().anyMatch(r -> r.statusCode() == 201) ? "10" : "0");
        var closed = owner(true);
        sequence.set(0);
        var closing = concurrent(2, () -> sequence.getAndIncrement() == 0 ? close(closed) : cashIn(closed, op, key(), "10"));
        var statuses = closing.stream().map(HttpResponse::statusCode).toList();
        if (statuses.contains(201)) {
            assertThat(statuses).containsExactlyInAnyOrder(201, 422);
            assertThat(balance(closed)).isEqualByComparingTo("10");
        } else {
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(balance(closed)).isEqualByComparingTo("0");
        }
    }

    private Owner owner(boolean provision) throws Exception {
        var customerToken = token(key(), "CUSTOMER");
        var customer = call("POST", "/api/v1/customers", customerToken, null, "{\"displayName\":\"Cash In Demo\"}");
        assertThat(customer.statusCode()).isEqualTo(201);
        var kyc = call("POST", "/api/v1/operations/customers/" + tree(customer).path("id").asString() + "/kyc-decisions",
                token(key(), "OPERATOR"), null, "{\"expectedVersion\":0,\"decision\":\"VERIFIED\",\"reason\":\"DOCUMENTS_MATCH\"}");
        assertThat(kyc.statusCode()).isEqualTo(200);
        var account = call("POST", "/api/v1/accounts", customerToken, null, "{\"currency\":\"VND\"}");
        assertThat(account.statusCode()).isEqualTo(201);
        var owner = new Owner(customerToken, UUID.fromString(tree(account).path("id").asString()));
        if (provision) assertThat(call("POST", "/api/v1/ledger/accounts/" + owner.accountId() + "/provision", customerToken, null, null).statusCode()).isEqualTo(200);
        return owner;
    }
    private HttpResponse<String> cashIn(Owner owner, String op, String key, String amount) throws Exception {
        return call("POST", ROUTE, op, key, body(owner, amount));
    }
    private static String body(Owner o, String amount) {
        return "{\"accountId\":\"" + o.accountId() + "\",\"amount\":\"" + amount + "\",\"currency\":\"VND\"}";
    }
    private HttpResponse<String> freeze(Owner o, String op) throws Exception {
        return call("POST", "/api/v1/operations/accounts/" + o.accountId() + "/freeze", op, null, "{\"expectedVersion\":0,\"reason\":\"SECURITY_REVIEW\"}");
    }
    private HttpResponse<String> close(Owner o) throws Exception {
        return call("POST", "/api/v1/accounts/" + o.accountId() + "/close", o.token(), null, "{\"expectedVersion\":0,\"reason\":\"CUSTOMER_REQUEST\"}");
    }
    private BigDecimal balance(Owner o) {
        return jdbc.sql("SELECT banking.ledger_balance(id) FROM banking.ledger_accounts WHERE bank_account_id=:id")
                .param("id", o.accountId()).query(BigDecimal.class).single();
    }
    private long commandCount(String key) {
        return jdbc.sql("SELECT count(*) FROM banking.cash_ins WHERE idempotency_key=:key").param("key", key).query(Long.class).single();
    }
    private long journalCount(Owner o) {
        return jdbc.sql("SELECT count(DISTINCT p.journal_id) FROM banking.ledger_postings p JOIN banking.ledger_accounts a ON a.id=p.ledger_account_id WHERE a.bank_account_id=:id")
                .param("id", o.accountId()).query(Long.class).single();
    }
    private void assertEmpty(Owner owner, String key) {
        assertThat(commandCount(key)).isZero();
        assertThat(journalCount(owner)).isZero();
        assertThat(balance(owner)).isEqualByComparingTo("0");
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private static String token(String subject, String... roles) throws Exception {
        return TOKENS.token(b -> b.subject(subject).claim("resource_access", Map.of("bank-api", Map.of("roles", List.of(roles)))));
    }
    private JsonNode tree(HttpResponse<String> r) { return json.readTree(r.body()); }
    private HttpResponse<String> call(String method, String path, String token, String key, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(path.startsWith("/v3/api-docs") ? 60 : 30))
                .header("X-Correlation-ID", "cash-in-007");
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (key != null) b.header("Idempotency-Key", key);
        if (body != null) b.header("Content-Type", "application/json");
        return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private void problem(HttpResponse<String> r, int status, String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(tree(r).path("code").asString()).isEqualTo(code);
        assertThat(tree(r).path("correlationId").asString()).isEqualTo("cash-in-007");
        assertThat(r.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
    }
    private static void assertSqlGuard(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) { assertSqlGuard(action, "23514"); }
    private static void assertSqlGuard(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String state) {
        assertThatThrownBy(action).satisfies(e -> {
            Throwable cause = e;
            while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getSQLState()).isEqualTo(state);
        });
    }
    private static <T> List<T> concurrent(int count, Callable<T> call) throws Exception {
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                return call.call();
            }));
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        }
    }
}
