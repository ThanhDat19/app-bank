package vn.banklab.core.reversal;

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
class ReversalIT {
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
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String ROUTE = "/api/v1/operations/reversals";
    private record Owner(String subject, String token, UUID id) { }

    @Test
    void reversesCashInWithExactInverseAndNeverMutatesOriginal() throws Exception {
        var a = owner(); var op = token(key(), "OPERATOR"); var cashKey = key();
        var cash = cashIn(a, "100", op, cashKey);
        assertThat(cash.statusCode()).isEqualTo(201);
        var original = journal(cash); var snapshot = lines(original);
        var result = reverse(original, op, key());
        assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        var receipt = tree(result);
        assertThat(receipt.path("originalKind").asString()).isEqualTo("SIMULATED_CASH_IN");
        assertThat(receipt.path("amount").asString()).isEqualTo("100");
        assertThat(receipt.path("reason").asString()).isEqualTo("OPERATOR_ERROR");
        assertThat(receipt.path("status").asString()).isEqualTo("POSTED");
        assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
        assertInverse(original, journal(result));
        assertThat(lines(original)).isEqualTo(snapshot);
        assertThat(balance(a)).isEqualByComparingTo("0");
        var lookup = call("GET", result.headers().firstValue("Location").orElseThrow(), op, null, null);
        assertThat(tree(lookup)).isEqualTo(receipt);
        assertThat(tree(cashIn(a, "100", op, cashKey))).isEqualTo(tree(cash));
        assertThat(balance(a)).isEqualByComparingTo("0");
    }

    @Test
    void reversesTransferForOperatorAndRestoresBothBalances() throws Exception {
        var a = owner(); var b = owner(); fund(a, "100");
        var transfer = transfer(a, b, key(), "40");
        assertThat(transfer.statusCode()).isEqualTo(201);
        var snapshot = lines(journal(transfer));
        var opSubject = key();
        var result = reverse(journal(transfer), token(opSubject, "OPERATOR"), key());
        assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
        assertThat(tree(result).path("originalKind").asString()).isEqualTo("INTERNAL_TRANSFER");
        assertInverse(journal(transfer), journal(result));
        assertThat(lines(journal(transfer))).isEqualTo(snapshot);
        assertThat(jdbc.sql("SELECT actor_subject FROM banking.ledger_journals WHERE id=:id").param("id", journal(result)).query(String.class).single()).isEqualTo(opSubject);
        balances(a, "100", b, "0");
    }

    @Test
    void retryUsesVerifiedIdentityAndReplaysAfterCloseButOtherKeysCannotReverseTwice() throws Exception {
        var a = owner(); var original = journal(fund(a, "75"));
        var subject = key(); var op = token(subject, "OPERATOR"); var key = key();
        var first = reverse(original, op, key);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(close(a).statusCode()).isEqualTo(200);
        var replay = reverse(original, token(subject, "OPERATOR"), key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(tree(replay)).isEqualTo(tree(first));
        problem(call("POST", ROUTE, op, key, body(original, "DUPLICATE_PROCESSING")), 409, "IDEMPOTENCY_CONFLICT");
        problem(reverse(UUID.randomUUID(), op, key), 409, "IDEMPOTENCY_CONFLICT");
        problem(reverse(original, op, key()), 409, "REVERSAL_ALREADY_EXISTS");
        problem(reverse(original, token(key(), "OPERATOR"), key()), 409, "REVERSAL_ALREADY_EXISTS");
        var location = first.headers().firstValue("Location").orElseThrow();
        problem(call("GET", location, token(key(), "OPERATOR"), null, null), 404, "REVERSAL_NOT_FOUND");
        problem(call("GET", location, a.token(), null, null), 403, "ACCESS_DENIED");
        assertThat(completed(original)).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyReturnsOneReceiptAndInverseJournal() throws Exception {
        var a = owner(); var original = journal(fund(a, "100"));
        var op = token(key(), "OPERATOR"); var key = key();
        var replies = concurrent(8, () -> reverse(original, op, key));
        assertThat(replies).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(201));
        assertThat(replies.stream().map(this::tree).distinct().count()).isEqualTo(1);
        assertThat(completed(original)).isEqualTo(1);
        assertThat(balance(a)).isEqualByComparingTo("0");
    }

    @Test
    void differentKeysAndOperatorsStillHaveOneReversalWinnerWithoutDeadlock() throws Exception {
        var a = owner(); var original = journal(fund(a, "100"));
        var operators = new ArrayList<String>();
        for (int i = 0; i < 8; i++) operators.add(token(key(), "OPERATOR"));
        var sequence = new AtomicInteger();
        var replies = concurrent(8, () -> reverse(original, operators.get(sequence.getAndIncrement()), key()));
        assertThat(replies.stream().filter(r -> r.statusCode() == 201).count()).isEqualTo(1);
        assertThat(replies.stream().filter(r -> r.statusCode() != 201).toList()).hasSize(7)
                .allSatisfy(r -> problem(r, 409, "REVERSAL_ALREADY_EXISTS"));
        assertThat(completed(original)).isEqualTo(1);
        assertThat(balance(a)).isEqualByComparingTo("0");
    }

    @Test
    void sameKeyDifferentOriginalsConflictsAtomically() throws Exception {
        var a = owner(); var b = owner(); var first = journal(fund(a, "50")); var second = journal(fund(b, "75"));
        var op = token(key(), "OPERATOR"); var key = key(); var sequence = new AtomicInteger();
        var replies = concurrent(2, () -> reverse(sequence.getAndIncrement() == 0 ? first : second, op, key));
        assertThat(replies.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201, 409);
        problem(replies.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(completed(first) + completed(second)).isEqualTo(1);
        if (completed(first) == 1) balances(a, "0", b, "75"); else balances(a, "50", b, "0");
    }

    @Test
    void cashInReversalRejectsInsufficientFundsThenSameKeyCanRetryAfterFunding() throws Exception {
        var a = owner(); var b = owner(); var original = journal(fund(a, "100"));
        assertThat(transfer(a, b, key(), "40").statusCode()).isEqualTo(201);
        var op = token(key(), "OPERATOR"); var key = key();
        problem(reverse(original, op, key), 422, "LEDGER_INSUFFICIENT_FUNDS");
        assertThat(claimCount(key)).isZero(); balances(a, "60", b, "40");
        fund(a, "40");
        assertThat(reverse(original, op, key).statusCode()).isEqualTo(201);
        balances(a, "0", b, "40");
    }

    @Test
    void recipientSpendBlocksTransferReversalUntilMoneyIsReturned() throws Exception {
        var a = owner(); var b = owner(); var c = owner(); fund(a, "100");
        var original = journal(transfer(a, b, key(), "80"));
        assertThat(transfer(b, c, key(), "80").statusCode()).isEqualTo(201);
        var op = token(key(), "OPERATOR"); var key = key();
        problem(reverse(original, op, key), 422, "LEDGER_INSUFFICIENT_FUNDS");
        assertThat(claimCount(key)).isZero(); balances(a, "20", b, "0");
        assertThat(transfer(c, b, key(), "80").statusCode()).isEqualTo(201);
        assertThat(reverse(original, op, key).statusCode()).isEqualTo(201);
        balances(a, "100", b, "0"); assertThat(balance(c)).isEqualByComparingTo("0");
    }

    @Test
    void sourceBalanceOverflowRejectsWholeTransferReversal() throws Exception {
        var a = owner(); var b = owner(); fund(a, "100");
        var original = journal(transfer(a, b, key(), "100"));
        var max = "99999999999999999999"; fund(a, max);
        var key = key(); problem(reverse(original, token(key(), "OPERATOR"), key), 422, "LEDGER_BALANCE_LIMIT");
        assertThat(claimCount(key)).isZero(); balances(a, max, b, "100");
    }

    @Test
    void maximumAmountIsReversedExactlyWithoutFloatOrRounding() throws Exception {
        var a = owner(); var max = "99999999999999999999";
        var result = reverse(journal(fund(a, max)), token(key(), "OPERATOR"), key());
        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(result.body()).contains("\"amount\":\"" + max + "\"");
        assertThat(balance(a)).isEqualByComparingTo("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cash-frozen", "cash-closed", "transfer-source-frozen", "transfer-destination-frozen", "transfer-source-closed"})
    void refusesFrozenOrClosedAccountsWithoutStateOverride(String scenario) throws Exception {
        var a = owner(); var b = owner(); var cash = journal(fund(a, "100"));
        UUID original = cash;
        if (scenario.startsWith("transfer") || scenario.equals("cash-closed")) {
            var transfer = transfer(a, b, key(), "100"); assertThat(transfer.statusCode()).isEqualTo(201);
            if (scenario.startsWith("transfer")) original = journal(transfer);
        }
        var target = scenario.contains("destination") ? b : a;
        if (scenario.endsWith("frozen")) assertThat(freeze(target).statusCode()).isEqualTo(200);
        else assertThat(close(target).statusCode()).isEqualTo(200);
        var key = key(); problem(reverse(original, token(key(), "OPERATOR"), key), 409, "ACCOUNT_STATE_INVALID");
        assertThat(claimCount(key)).isZero(); assertThat(completed(original)).isZero();
        boolean moved = scenario.startsWith("transfer") || scenario.equals("cash-closed");
        balances(a, moved ? "0" : "100", b, moved ? "100" : "0");
    }

    @Test
    void verifiesOperatorRoleAndOriginalEligibility() throws Exception {
        var a = owner(); var original = journal(fund(a, "100")); var key = key();
        problem(reverse(original, null, key), 401, "UNAUTHENTICATED");
        problem(reverse(original, "invalid", key), 401, "UNAUTHENTICATED");
        problem(reverse(original, a.token(), key), 403, "ACCESS_DENIED");
        var op = token(key(), "OPERATOR");
        problem(reverse(UUID.randomUUID(), op, key), 404, "REVERSAL_ORIGINAL_NOT_FOUND");
        problem(reverse(rawCredit(a, "ADJUSTMENT"), op, key), 422, "REVERSAL_NOT_SUPPORTED");
        problem(reverse(rawCredit(a, "SIMULATED_CASH_IN"), op, key), 422, "REVERSAL_NOT_SUPPORTED");
        var inverse = reverse(original, op, key);
        assertThat(inverse.statusCode()).isEqualTo(201);
        problem(reverse(journal(inverse), op, key()), 422, "REVERSAL_NOT_SUPPORTED");
        problem(call("GET", ROUTE + "/" + UUID.randomUUID(), op, null, null), 404, "REVERSAL_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "UNKNOWN", "operator_error"})
    void validatesReasonWithoutReservingKey(String reason) throws Exception {
        var key = key();
        problem(call("POST", ROUTE, token(key(), "OPERATOR"), key, body(UUID.randomUUID(), reason)), 400, "VALIDATION_FAILED");
        assertThat(claimCount(key)).isZero();
    }

    @Test
    void rejectsPartialOrArbitraryPostingInputsAndDocumentsSwagger() throws Exception {
        var a = owner(); var original = journal(fund(a, "100")); var op = token(key(), "OPERATOR");
        problem(reverse(original, op, null), 400, "INVALID_REQUEST");
        problem(reverse(original, op, "bad key"), 400, "VALIDATION_FAILED");
        problem(reverse(original, op, "x".repeat(129)), 400, "VALIDATION_FAILED");
        problem(call("POST", ROUTE, op, key(), "{}"), 400, "VALIDATION_FAILED");
        for (var extra : List.of("\"amount\":\"1\"", "\"postings\":[]", "\"actor\":\"someone\"")) {
            problem(call("POST", ROUTE, op, key(), body(original, "OPERATOR_ERROR").replace("}", "," + extra + "}")), 400, "INVALID_REQUEST");
        }
        assertThat(balance(a)).isEqualByComparingTo("100");
        var docs = tree(call("GET", "/v3/api-docs", null, null, null));
        assertThat(docs.path("paths").path(ROUTE).path("post").path("operationId").asString()).isEqualTo("createFullReversal");
        assertThat(docs.path("paths").path(ROUTE).path("post").path("parameters").toString()).contains("Idempotency-Key");
        assertThat(docs.path("components").path("schemas").path("ReversalReceipt").path("properties").path("amount").path("type").asString()).isEqualTo("string");
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal", "receipt", "commit"})
    void eachWriteFailureRollsBackInverseAndClaimBeforeSafeRetry(String fault) throws Exception {
        var a = owner(); var original = journal(fund(a, "100"));
        var subject = key(); var op = token(subject, "OPERATOR"); var key = key();
        if (fault.equals("journal")) jdbc.sql("ALTER TABLE banking.ledger_journals ADD CONSTRAINT test_reversal_finalize CHECK (actor_subject <> '" + subject + "' OR kind <> 'REVERSAL' OR status <> 'POSTED')").update();
        if (fault.equals("receipt")) jdbc.sql("ALTER TABLE banking.reversals ADD CONSTRAINT test_reversal_receipt CHECK (idempotency_key <> '" + key + "' OR journal_id IS NULL)").update();
        if (fault.equals("commit")) {
            jdbc.sql("CREATE FUNCTION banking.test_reversal_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected commit failure' USING ERRCODE='23514'; END $$").update();
            jdbc.sql("CREATE CONSTRAINT TRIGGER test_reversal_commit AFTER INSERT ON banking.reversals DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.test_reversal_commit()").update();
        }
        try {
            problem(reverse(original, op, key), 500, "INTERNAL_ERROR");
            assertThat(balance(a)).isEqualByComparingTo("100"); assertThat(claimCount(key)).isZero(); assertThat(completed(original)).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM banking.ledger_journals WHERE kind='REVERSAL' AND actor_subject=:subject").param("subject", subject).query(Long.class).single()).isZero();
        } finally {
            if (fault.equals("journal")) jdbc.sql("ALTER TABLE banking.ledger_journals DROP CONSTRAINT test_reversal_finalize").update();
            if (fault.equals("receipt")) jdbc.sql("ALTER TABLE banking.reversals DROP CONSTRAINT test_reversal_receipt").update();
            if (fault.equals("commit")) { jdbc.sql("DROP TRIGGER test_reversal_commit ON banking.reversals").update(); jdbc.sql("DROP FUNCTION banking.test_reversal_commit()").update(); }
        }
        assertThat(reverse(original, op, key).statusCode()).isEqualTo(201);
        assertThat(balance(a)).isEqualByComparingTo("0");
    }

    @Test
    void spendAndReversalRaceCannotOverspendOrPartiallyReverse() throws Exception {
        var a = owner(); var b = owner(); var original = journal(fund(a, "100")); var op = token(key(), "OPERATOR"); var sequence = new AtomicInteger();
        var replies = concurrent(2, () -> sequence.getAndIncrement() == 0 ? reverse(original, op, key()) : transfer(a, b, key(), "80"));
        assertThat(replies.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201, 422);
        if (completed(original) == 1) balances(a, "0", b, "0"); else balances(a, "20", b, "80");
        problem(replies.stream().filter(r -> r.statusCode() == 422).findFirst().orElseThrow(), 422, "LEDGER_INSUFFICIENT_FUNDS");
    }

    @Test
    void freezeAndCloseRacesRespectLedgerAndAccountStates() throws Exception {
        var a = owner(); var cash = journal(fund(a, "100")); var op = token(key(), "OPERATOR"); var sequence = new AtomicInteger();
        var freezing = concurrent(2, () -> sequence.getAndIncrement() == 0 ? freeze(a) : reverse(cash, op, key()));
        assertThat(freezing.stream().map(HttpResponse::statusCode).toList()).contains(200).allMatch(s -> s == 200 || s == 201 || s == 409);
        assertThat(balance(a)).isEqualByComparingTo(completed(cash) == 1 ? "0" : "100");
        var b = owner(); var c = owner(); fund(b, "100"); var original = journal(transfer(b, c, key(), "100")); sequence.set(0);
        var closing = concurrent(2, () -> sequence.getAndIncrement() == 0 ? close(b) : reverse(original, op, key()));
        var statuses = closing.stream().map(HttpResponse::statusCode).toList();
        if (completed(original) == 1) { assertThat(statuses).containsExactlyInAnyOrder(201, 422); balances(b, "100", c, "0"); }
        else { assertThat(statuses).containsExactlyInAnyOrder(200, 409); balances(b, "0", c, "100"); }
    }

    @Test
    void mixedCashInAndOppositeTransfersConserveAllMoneyUnderContention() throws Exception {
        var a = owner(); var b = owner(); fund(a, "1000"); fund(b, "1000");
        var op = token(key(), "OPERATOR"); var sequence = new AtomicInteger();
        var replies = concurrent(24, () -> switch (sequence.getAndIncrement() % 3) {
            case 0 -> cashIn(a, "25", op, key());
            case 1 -> transfer(a, b, key(), "10");
            default -> transfer(b, a, key(), "10");
        });
        assertThat(replies).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(201));
        balances(a, "1200", b, "1000");
        assertThat(balance(a).add(balance(b))).isEqualByComparingTo("2200");
    }

    @ParameterizedTest
    @ValueSource(strings = {"transfer", "reversal"})
    void responseLostAfterBackendCommitStillRetriesExactlyOnce(String operation) throws Exception {
        var a = owner(); var b = owner(); fund(a, "100");
        var key = key(); String route, actor, payload;
        if (operation.equals("transfer")) { route = "/api/v1/transfers"; actor = a.token(); payload = transferBody(a, b, "40"); }
        else { var original = journal(transfer(a, b, key(), "40")); route = ROUTE; actor = token(key(), "OPERATOR"); payload = body(original, "OPERATOR_ERROR"); }
        var committed = new CountDownLatch(1); var receipt = new AtomicReference<JsonNode>(); var failure = new AtomicReference<Throwable>();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newCachedThreadPool()) {
            proxy.setExecutor(executor);
            proxy.createContext("/drop", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    var backend = call("POST", route, actor, key, payload);
                    if (backend.statusCode() != 201) throw new AssertionError(backend.body());
                    receipt.set(tree(backend)); committed.countDown();
                    // Intentionally close the socket AFTER real backend commit, without response headers.
                } catch (Throwable error) { failure.set(error); committed.countDown(); }
                finally { exchange.close(); }
            });
            proxy.start();
            try {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxy.getAddress().getPort() + "/drop"))
                        .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                assertThatThrownBy(() -> http.send(request, HttpResponse.BodyHandlers.ofString())).isInstanceOf(java.io.IOException.class);
                assertThat(committed.await(15, TimeUnit.SECONDS)).isTrue(); assertThat(failure.get()).isNull();
                var retry = call("POST", route, actor, key, payload);
                assertThat(retry.statusCode()).isEqualTo(201); assertThat(tree(retry)).isEqualTo(receipt.get());
                if (operation.equals("transfer")) balances(a, "60", b, "40"); else balances(a, "100", b, "0");
            } finally { proxy.stop(0); }
        }
    }

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
    private HttpResponse<String> reverse(UUID original, String op, String key) throws Exception { return call("POST", ROUTE, op, key, body(original, "OPERATOR_ERROR")); }
    private static String body(UUID original, String reason) { return "{\"originalJournalId\":\"" + original + "\",\"reason\":\"" + reason + "\"}"; }
    private HttpResponse<String> freeze(Owner a) throws Exception { return call("POST", "/api/v1/operations/accounts/" + a.id() + "/freeze", token(key(), "OPERATOR"), null, "{\"expectedVersion\":0,\"reason\":\"SECURITY_REVIEW\"}"); }
    private HttpResponse<String> close(Owner a) throws Exception { return call("POST", "/api/v1/accounts/" + a.id() + "/close", a.token(), null, "{\"expectedVersion\":0,\"reason\":\"CUSTOMER_REQUEST\"}"); }
    private BigDecimal balance(Owner a) { return jdbc.sql("SELECT banking.ledger_balance(id) FROM banking.ledger_accounts WHERE bank_account_id=:id").param("id", a.id()).query(BigDecimal.class).single(); }
    private void balances(Owner a, String expectedA, Owner b, String expectedB) { assertThat(balance(a)).isEqualByComparingTo(expectedA); assertThat(balance(b)).isEqualByComparingTo(expectedB); }
    private long completed(UUID original) { return jdbc.sql("SELECT count(*) FROM banking.reversals WHERE original_journal_id=:id AND journal_id IS NOT NULL").param("id", original).query(Long.class).single(); }
    private long claimCount(String key) { return jdbc.sql("SELECT count(*) FROM banking.reversals WHERE idempotency_key=:key").param("key", key).query(Long.class).single(); }
    private String lines(UUID journal) { return jdbc.sql("SELECT string_agg(ledger_account_id::text || ':' || side || ':' || amount::text,',' ORDER BY id) FROM banking.ledger_postings WHERE journal_id=:id").param("id", journal).query(String.class).single(); }
    private void assertInverse(UUID original, UUID inverse) {
        assertThat(jdbc.sql("SELECT count(*) FROM banking.ledger_postings o JOIN banking.ledger_postings r ON r.ledger_account_id=o.ledger_account_id AND r.amount=o.amount AND r.currency=o.currency AND r.side<>o.side WHERE o.journal_id=:original AND r.journal_id=:inverse")
                .param("original", original).param("inverse", inverse).query(Long.class).single()).isEqualTo(2);
    }
    private UUID rawCredit(Owner a, String kind) throws Exception {
        // Trusted SQL fixture checks unsupported internal originals; not JWT/security evidence.
        var journal = UUID.randomUUID();
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); var s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("INSERT INTO banking.ledger_journals (id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES ('" + journal + "','" + UUID.randomUUID() + "','" + kind + "','VND','DRAFT','sql-fixture','sql-fixture',now())");
            s.execute("INSERT INTO banking.ledger_postings (id,journal_id,ledger_account_id,currency,side,amount) VALUES ('" + UUID.randomUUID() + "','" + journal + "','00000000-0000-0000-0000-000000000001','VND','DEBIT',1)");
            s.execute("INSERT INTO banking.ledger_postings (id,journal_id,ledger_account_id,currency,side,amount) SELECT '" + UUID.randomUUID() + "','" + journal + "',id,'VND','CREDIT',1 FROM banking.ledger_accounts WHERE bank_account_id='" + a.id() + "'");
            s.execute("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id='" + journal + "'"); c.commit();
        }
        return journal;
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
