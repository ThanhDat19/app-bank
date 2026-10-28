package vn.banklab.core.transfer;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
class TransferIT {
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
    private static final String ROUTE = "/api/v1/transfers";
    private record Owner(String subject, String token, UUID id) { }

    @Test
    void postsOneBalancedJournalAndOnlySenderCanReadReceipt() throws Exception {
        var a = owner(true); var b = owner(true);
        fund(a, "500000");
        var r = transfer(a, b, key(), "100000");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
        var receipt = tree(r);
        assertThat(receipt.path("status").asString()).isEqualTo("POSTED");
        assertThat(receipt.path("amount").asString()).isEqualTo("100000");
        assertThat(receipt.path("currency").asString()).isEqualTo("VND");
        var path = r.headers().firstValue("Location").orElseThrow();
        assertThat(tree(call("GET", path, a.token(), null, null))).isEqualTo(receipt);
        problem(call("GET", path, b.token(), null, null), 404, "TRANSFER_NOT_FOUND");
        problem(call("GET", path, token(key(), "OPERATOR"), null, null), 403, "ACCESS_DENIED");
        problem(call("GET", ROUTE + "/" + UUID.randomUUID(), a.token(), null, null), 404, "TRANSFER_NOT_FOUND");
        var journal = UUID.fromString(receipt.path("journalId").asString());
        assertThat(jdbc.sql("SELECT actor_subject FROM banking.ledger_journals WHERE id=:id AND kind='INTERNAL_TRANSFER'")
                .param("id", journal).query(String.class).single()).isEqualTo(a.subject());
        assertThat(jdbc.sql("SELECT sum(CASE WHEN side='DEBIT' THEN amount ELSE -amount END) FROM banking.ledger_postings WHERE journal_id=:id")
                .param("id", journal).query(BigDecimal.class).single()).isEqualByComparingTo("0");
        for (var side : Map.of(a.id(), "DEBIT", b.id(), "CREDIT").entrySet()) {
            assertThat(jdbc.sql("SELECT p.amount FROM banking.ledger_postings p JOIN banking.ledger_accounts l ON l.id=p.ledger_account_id WHERE p.journal_id=:journal AND l.bank_account_id=:bank AND p.side=:side")
                    .param("journal", journal).param("bank", side.getKey()).param("side", side.getValue()).query(BigDecimal.class).single()).isEqualByComparingTo("100000");
        }
        balances(a, "400000", b, "100000");
    }

    @Test
    void lostResponseAndRenewedTokenReplayAfterSourceCloseAndDestinationFreeze() throws Exception {
        var a = owner(true); var b = owner(true); var c = owner(true);
        fund(a, "100");
        var key = key();
        var original = transfer(a, b, key, "100");
        assertThat(original.statusCode()).isEqualTo(201);
        assertThat(close(a).statusCode()).isEqualTo(200);
        assertThat(freeze(b).statusCode()).isEqualTo(200);
        var renewed = token(a.subject(), "CUSTOMER");
        var replay = call("POST", ROUTE, renewed, key, body(a, b, "100"));
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(tree(replay)).isEqualTo(tree(original));
        problem(transfer(a, b, key, "99"), 409, "IDEMPOTENCY_CONFLICT");
        problem(transfer(a, c, key, "100"), 409, "IDEMPOTENCY_CONFLICT");
        problem(call("POST", ROUTE, a.token(), key, body(c, b, "100")), 409, "IDEMPOTENCY_CONFLICT");
        problem(transfer(a, b, key(), "100"), 409, "ACCOUNT_STATE_INVALID");
        balances(a, "0", b, "100");
    }

    @Test
    void verifiedCustomerMustOwnSourceBeforeDestinationStateIsDisclosed() throws Exception {
        var a = owner(true); var b = owner(true);
        fund(a, "100");
        assertThat(freeze(b).statusCode()).isEqualTo(200);
        var key = key();
        problem(call("POST", ROUTE, null, key, body(a, b, "10")), 401, "UNAUTHENTICATED");
        problem(call("POST", ROUTE, "invalid", key, body(a, b, "10")), 401, "UNAUTHENTICATED");
        problem(call("POST", ROUTE, token(key(), "OPERATOR"), key, body(a, b, "10")), 403, "ACCESS_DENIED");
        problem(call("POST", ROUTE, token(key(), "CUSTOMER"), key, body(a, b, "10")), 404, "ACCOUNT_NOT_FOUND");
        problem(call("POST", ROUTE, b.token(), key, body(a, b, "10")), 404, "ACCOUNT_NOT_FOUND");
        var missing = new Owner(a.subject(), a.token(), UUID.randomUUID());
        problem(transfer(missing, b, key, "10"), 404, "ACCOUNT_NOT_FOUND");
        problem(transfer(a, missing, key, "10"), 404, "ACCOUNT_NOT_FOUND");
        assertThat(claimCount(key)).isZero();
        balances(a, "100", b, "0");
    }

    @Test
    void concurrentDuplicatesPostOnlyOneDebitAndCredit() throws Exception {
        var a = owner(true); var b = owner(true);
        fund(a, "100");
        var key = key();
        var replies = concurrent(8, () -> transfer(a, b, key, "75"));
        assertThat(replies).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(201));
        assertThat(replies.stream().map(this::tree).distinct().count()).isEqualTo(1);
        assertThat(claimCount(key)).isEqualTo(1);
        assertThat(journalCount(a)).isEqualTo(1);
        balances(a, "25", b, "75");
    }

    @Test
    void concurrentConflictingPayloadsHaveExactlyOneWinner() throws Exception {
        var a = owner(true); var b = owner(true);
        fund(a, "100");
        var key = key();
        var sequence = new AtomicInteger();
        var replies = concurrent(2, () -> transfer(a, b, key, sequence.getAndIncrement() == 0 ? "70" : "80"));
        assertThat(replies.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201, 409);
        var amount = new BigDecimal(tree(replies.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow()).path("amount").asString());
        assertThat(balance(a)).isEqualByComparingTo(new BigDecimal("100").subtract(amount));
        assertThat(balance(b)).isEqualByComparingTo(amount);
        problem(replies.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(claimCount(key)).isEqualTo(1);
    }

    @Test
    void competingDebitsNeverOverspend() throws Exception {
        var a = owner(true); var b = owner(true);
        fund(a, "100");
        var replies = concurrent(8, () -> transfer(a, b, key(), "30"));
        assertThat(replies.stream().filter(r -> r.statusCode() == 201).count()).isEqualTo(3);
        assertThat(replies.stream().filter(r -> r.statusCode() != 201).toList()).hasSize(5)
                .allSatisfy(r -> problem(r, 422, "LEDGER_INSUFFICIENT_FUNDS"));
        balances(a, "10", b, "90");
        assertThat(journalCount(a)).isEqualTo(3);
    }

    @Test
    void oppositeDirectionTransfersUseOneLockOrderAndConserveMoney() throws Exception {
        var a = owner(true); var b = owner(true);
        fund(a, "100"); fund(b, "100");
        var sequence = new AtomicInteger();
        var replies = concurrent(8, () -> sequence.getAndIncrement() % 2 == 0 ? transfer(a, b, key(), "5") : transfer(b, a, key(), "5"));
        assertThat(replies).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(201));
        balances(a, "100", b, "100");
        assertThat(journalCount(a) + journalCount(b)).isEqualTo(8);
    }

    @Test
    void concurrentIncomingTransfersDoNotLoseCredits() throws Exception {
        var target = owner(true);
        var senders = new ArrayList<Owner>();
        for (int i = 0; i < 4; i++) { var source = owner(true); fund(source, "10"); senders.add(source); }
        var sequence = new AtomicInteger();
        var replies = concurrent(4, () -> transfer(senders.get(sequence.getAndIncrement()), target, key(), "10"));
        assertThat(replies).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(201));
        assertThat(balance(target)).isEqualByComparingTo("40");
        assertThat(senders).allSatisfy(a -> assertThat(balance(a)).isEqualByComparingTo("0"));
    }

    @Test
    void exactMaximumTransferAndDestinationOverflowAreAtomic() throws Exception {
        var a = owner(true); var b = owner(true);
        var max = "99999999999999999999";
        fund(a, max);
        var r = transfer(a, b, key(), max);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(r.body()).contains("\"amount\":\"" + max + "\"");
        balances(a, "0", b, max);
        fund(a, "1");
        var key = key();
        problem(transfer(a, b, key, "1"), 422, "LEDGER_BALANCE_LIMIT");
        assertThat(claimCount(key)).isZero();
        balances(a, "1", b, max);
    }

    @Test
    void insufficientFundsDoesNotRetainKeyAndFundingAllowsSameRequestToRetry() throws Exception {
        var a = owner(true); var b = owner(true);
        var key = key();
        problem(transfer(a, b, key, "50"), 422, "LEDGER_INSUFFICIENT_FUNDS");
        assertThat(claimCount(key)).isZero();
        fund(a, "100");
        assertThat(transfer(a, b, key, "50").statusCode()).isEqualTo(201);
        balances(a, "50", b, "50");
    }

    @ParameterizedTest
    @ValueSource(strings = {"source-frozen", "destination-frozen", "source-closed", "destination-closed", "source-unprovisioned", "destination-unprovisioned"})
    void bothAccountsMustBeActiveAndProvisioned(String scenario) throws Exception {
        var a = owner(!scenario.equals("source-unprovisioned"));
        var b = owner(!scenario.equals("destination-unprovisioned"));
        if (!scenario.startsWith("source-closed") && !scenario.equals("source-unprovisioned")) fund(a, "10");
        var target = scenario.startsWith("source-") ? a : b;
        if (scenario.endsWith("frozen")) assertThat(freeze(target).statusCode()).isEqualTo(200);
        if (scenario.endsWith("closed")) assertThat(close(target).statusCode()).isEqualTo(200);
        var key = key();
        problem(transfer(a, b, key, "1"), scenario.endsWith("unprovisioned") ? 422 : 409,
                scenario.endsWith("unprovisioned") ? "LEDGER_NOT_PROVISIONED" : "ACCOUNT_STATE_INVALID");
        assertThat(claimCount(key)).isZero();
        assertThat(journalCount(a)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "1.0", "01", "1e3", "NaN", "Infinity", "100000000000000000000", " 1", ""})
    void rejectsInvalidAmountsBeforeReservingAKey(String amount) throws Exception {
        var key = key();
        var a = new Owner("unused", token(key(), "CUSTOMER"), UUID.randomUUID());
        var b = new Owner("unused", a.token(), UUID.randomUUID());
        problem(transfer(a, b, key, amount), 400, "VALIDATION_FAILED");
        assertThat(claimCount(key)).isZero();
    }

    @Test
    void validatesSameAccountHeadersCurrencyJsonTypesAndSwagger() throws Exception {
        var a = owner(true); var b = owner(true);
        problem(transfer(a, a, key(), "1"), 422, "TRANSFER_ACCOUNTS_INVALID");
        problem(transfer(a, b, null, "1"), 400, "INVALID_REQUEST");
        problem(transfer(a, b, "bad key", "1"), 400, "VALIDATION_FAILED");
        problem(transfer(a, b, "x".repeat(129), "1"), 400, "VALIDATION_FAILED");
        var body = body(a, b, "1");
        problem(call("POST", ROUTE, a.token(), key(), body.replace("\"VND\"", "\"USD\"")), 400, "VALIDATION_FAILED");
        problem(call("POST", ROUTE, a.token(), key(), body.replace("\"amount\":\"1\"", "\"amount\":1")), 400, "INVALID_REQUEST");
        problem(call("POST", ROUTE, a.token(), key(), "{}"), 400, "VALIDATION_FAILED");
        var docs = tree(call("GET", "/v3/api-docs", null, null, null));
        assertThat(docs.path("paths").path(ROUTE).path("post").path("operationId").asString()).isEqualTo("createInternalTransfer");
        assertThat(docs.path("paths").path(ROUTE).path("post").path("parameters").toString()).contains("Idempotency-Key");
        assertThat(docs.path("components").path("schemas").path("TransferRequest").path("properties").path("amount").path("type").asString()).isEqualTo("string");
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal", "receipt", "commit"})
    void failuresAtEachWriteBoundaryRollBackBothBalancesAndKey(String fault) throws Exception {
        var a = owner(true); var b = owner(true);
        fund(a, "10");
        var key = key();
        if (fault.equals("journal")) jdbc.sql("ALTER TABLE banking.ledger_journals ADD CONSTRAINT test_transfer_finalize CHECK (actor_subject <> '" + a.subject() + "' OR status <> 'POSTED')").update();
        if (fault.equals("receipt")) jdbc.sql("ALTER TABLE banking.transfers ADD CONSTRAINT test_transfer_receipt CHECK (idempotency_key <> '" + key + "' OR journal_id IS NULL)").update();
        if (fault.equals("commit")) {
            jdbc.sql("CREATE FUNCTION banking.test_transfer_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected commit failure' USING ERRCODE='23514'; END $$").update();
            jdbc.sql("CREATE CONSTRAINT TRIGGER test_transfer_commit AFTER INSERT ON banking.transfers DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION banking.test_transfer_commit()").update();
        }
        try {
            problem(transfer(a, b, key, "5"), 500, "INTERNAL_ERROR");
            balances(a, "10", b, "0");
            assertThat(claimCount(key)).isZero();
            assertThat(journalCount(a)).isZero();
        } finally {
            if (fault.equals("journal")) jdbc.sql("ALTER TABLE banking.ledger_journals DROP CONSTRAINT test_transfer_finalize").update();
            if (fault.equals("receipt")) jdbc.sql("ALTER TABLE banking.transfers DROP CONSTRAINT test_transfer_receipt").update();
            if (fault.equals("commit")) {
                jdbc.sql("DROP TRIGGER test_transfer_commit ON banking.transfers").update();
                jdbc.sql("DROP FUNCTION banking.test_transfer_commit()").update();
            }
        }
        assertThat(transfer(a, b, key, "5").statusCode()).isEqualTo(201);
        balances(a, "5", b, "5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"balance", "state", "provision"})
    void rechecksCommittedBalanceAndStateAfterWaitingForAccountLock(String changed) throws Exception {
        var a = owner(true); var b = owner(!changed.equals("provision"));
        fund(a, "100");
        var key = key();
        try (var blocker = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var sql = blocker.createStatement(); var pool = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            var heldAccount = changed.equals("provision") ? b.id() : a.id();
            sql.execute("SELECT id FROM banking.accounts WHERE id='" + heldAccount + "' FOR UPDATE");
            var pending = pool.submit(() -> transfer(a, b, key, "80"));
            try {
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%banking.accounts%' AND query LIKE '%FOR UPDATE%')")
                            .query(Boolean.class).single();
                    if (waiting) break;
                    Thread.sleep(25);
                }
                assertThat(waiting).as("Transfer really waited behind the held bank-account lock").isTrue();
                if (changed.equals("balance")) {
                    // Trusted SQL fixture spends to clearing within the blocking transaction.
                    var journal = UUID.randomUUID();
                    sql.execute("INSERT INTO banking.ledger_journals (id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES ('" + journal + "','" + UUID.randomUUID() + "','ADJUSTMENT','VND','DRAFT','sql-fixture','sql-fixture',now())");
                    sql.execute("INSERT INTO banking.ledger_postings (id,journal_id,ledger_account_id,currency,side,amount) SELECT '" + UUID.randomUUID() + "','" + journal + "',id,'VND','DEBIT',80 FROM banking.ledger_accounts WHERE bank_account_id='" + a.id() + "'");
                    sql.execute("INSERT INTO banking.ledger_postings (id,journal_id,ledger_account_id,currency,side,amount) VALUES ('" + UUID.randomUUID() + "','" + journal + "','00000000-0000-0000-0000-000000000001','VND','CREDIT',80)");
                    sql.execute("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id='" + journal + "'");
                } else if (changed.equals("provision")) {
                    var ledger = UUID.randomUUID();
                    sql.execute("INSERT INTO banking.ledger_accounts (id,code,name,account_type,normal_side,currency,bank_account_id,created_at) VALUES ('" + ledger + "','TEST_" + ledger + "','Synthetic provision race','LIABILITY','CREDIT','VND','" + b.id() + "',now())");
                    sql.execute("UPDATE banking.accounts SET ledger_provisioned=true WHERE id='" + b.id() + "'");
                } else {
                    // Trusted state fixture with matching history; the HTTP JWT gates are tested separately.
                    sql.execute("UPDATE banking.accounts SET status='FROZEN',version=1,updated_at=clock_timestamp() WHERE id='" + a.id() + "'");
                    sql.execute("INSERT INTO banking.account_state_changes (id,account_id,from_status,to_status,reason,actor_issuer,actor_subject,account_version,changed_at) VALUES ('" + UUID.randomUUID() + "','" + a.id() + "','ACTIVE','FROZEN','SECURITY_REVIEW','sql-fixture','sql-fixture',1,clock_timestamp())");
                }
                blocker.commit();
            } finally { blocker.rollback(); }
            var result = pending.get(40, TimeUnit.SECONDS);
            if (changed.equals("provision")) assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
            else problem(result, changed.equals("balance") ? 422 : 409,
                    changed.equals("balance") ? "LEDGER_INSUFFICIENT_FUNDS" : "ACCOUNT_STATE_INVALID");
        }
        assertThat(claimCount(key)).isEqualTo(changed.equals("provision") ? 1 : 0);
        balances(a, changed.equals("state") ? "100" : "20", b, changed.equals("provision") ? "80" : "0");
    }

    @Test
    void freezeAndCloseRacesHaveOnlySerializableFinancialOutcomes() throws Exception {
        var a = owner(true); var b = owner(true);
        fund(a, "100");
        var sequence = new AtomicInteger();
        var freezing = concurrent(2, () -> sequence.getAndIncrement() == 0 ? freeze(a) : transfer(a, b, key(), "10"));
        assertThat(freezing.stream().map(HttpResponse::statusCode).toList()).contains(200).allMatch(s -> s == 200 || s == 201 || s == 409);
        balances(a, freezing.stream().anyMatch(r -> r.statusCode() == 201) ? "90" : "100", b,
                freezing.stream().anyMatch(r -> r.statusCode() == 201) ? "10" : "0");
        var c = owner(true); var d = owner(true);
        fund(c, "100"); sequence.set(0);
        var closing = concurrent(2, () -> sequence.getAndIncrement() == 0 ? close(d) : transfer(c, d, key(), "10"));
        var statuses = closing.stream().map(HttpResponse::statusCode).toList();
        if (statuses.contains(201)) {
            assertThat(statuses).containsExactlyInAnyOrder(201, 422);
            balances(c, "90", d, "10");
        } else {
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            balances(c, "100", d, "0");
        }
    }

    @Test
    void keysAreScopedToActorAndOperationAndUuidHashIsCanonical() throws Exception {
        var a = owner(true, "CUSTOMER", "OPERATOR"); var b = owner(true);
        var key = key();
        assertThat(call("POST", "/api/v1/operations/cash-ins", a.token(), key,
                "{\"accountId\":\"" + a.id() + "\",\"amount\":\"100\",\"currency\":\"VND\"}").statusCode()).isEqualTo(201);
        var first = transfer(a, b, key, "20");
        assertThat(first.statusCode()).isEqualTo(201);
        var reordered = "{ \"currency\":\"VND\", \"amount\":\"20\", \"destinationAccountId\":\"" + b.id().toString().toUpperCase(Locale.ROOT) + "\", \"sourceAccountId\":\"" + a.id() + "\" }";
        assertThat(tree(call("POST", ROUTE, a.token(), key, reordered))).isEqualTo(tree(first));
        assertThat(transfer(b, a, key, "5").statusCode()).isEqualTo(201);
        balances(a, "85", b, "15");
        assertThat(claimCount(key)).isEqualTo(2);
    }

    private Owner owner(boolean provision, String... roles) throws Exception {
        var subject = key();
        var customerToken = token(subject, roles.length == 0 ? new String[]{"CUSTOMER"} : roles);
        var customer = call("POST", "/api/v1/customers", customerToken, null, "{\"displayName\":\"Transfer Demo\"}");
        assertThat(customer.statusCode()).isEqualTo(201);
        assertThat(call("POST", "/api/v1/operations/customers/" + tree(customer).path("id").asString() + "/kyc-decisions",
                token(key(), "OPERATOR"), null, "{\"expectedVersion\":0,\"decision\":\"VERIFIED\",\"reason\":\"DOCUMENTS_MATCH\"}").statusCode()).isEqualTo(200);
        var account = call("POST", "/api/v1/accounts", customerToken, null, "{\"currency\":\"VND\"}");
        assertThat(account.statusCode()).isEqualTo(201);
        var owner = new Owner(subject, customerToken, UUID.fromString(tree(account).path("id").asString()));
        if (provision) assertThat(call("POST", "/api/v1/ledger/accounts/" + owner.id() + "/provision", customerToken, null, null).statusCode()).isEqualTo(200);
        return owner;
    }
    private void fund(Owner a, String amount) throws Exception {
        assertThat(call("POST", "/api/v1/operations/cash-ins", token(key(), "OPERATOR"), key(),
                "{\"accountId\":\"" + a.id() + "\",\"amount\":\"" + amount + "\",\"currency\":\"VND\"}").statusCode()).isEqualTo(201);
    }
    private HttpResponse<String> transfer(Owner a, Owner b, String key, String amount) throws Exception { return call("POST", ROUTE, a.token(), key, body(a, b, amount)); }
    private static String body(Owner a, Owner b, String amount) { return "{\"sourceAccountId\":\"" + a.id() + "\",\"destinationAccountId\":\"" + b.id() + "\",\"amount\":\"" + amount + "\",\"currency\":\"VND\"}"; }
    private HttpResponse<String> freeze(Owner a) throws Exception { return call("POST", "/api/v1/operations/accounts/" + a.id() + "/freeze", token(key(), "OPERATOR"), null, "{\"expectedVersion\":0,\"reason\":\"SECURITY_REVIEW\"}"); }
    private HttpResponse<String> close(Owner a) throws Exception { return call("POST", "/api/v1/accounts/" + a.id() + "/close", a.token(), null, "{\"expectedVersion\":0,\"reason\":\"CUSTOMER_REQUEST\"}"); }
    private BigDecimal balance(Owner a) { return jdbc.sql("SELECT banking.ledger_balance(id) FROM banking.ledger_accounts WHERE bank_account_id=:id").param("id", a.id()).query(BigDecimal.class).single(); }
    private void balances(Owner a, String expectedA, Owner b, String expectedB) { assertThat(balance(a)).isEqualByComparingTo(expectedA); assertThat(balance(b)).isEqualByComparingTo(expectedB); }
    private long claimCount(String key) { return jdbc.sql("SELECT count(*) FROM banking.transfers WHERE idempotency_key=:key").param("key", key).query(Long.class).single(); }
    private long journalCount(Owner a) { return jdbc.sql("SELECT count(*) FROM banking.ledger_journals WHERE actor_subject=:subject AND kind='INTERNAL_TRANSFER'").param("subject", a.subject()).query(Long.class).single(); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static String token(String subject, String... roles) throws Exception { return TOKENS.token(b -> b.subject(subject).claim("resource_access", Map.of("bank-api", Map.of("roles", List.of(roles))))); }
    private JsonNode tree(HttpResponse<String> r) { return json.readTree(r.body()); }
    private HttpResponse<String> call(String method, String path, String token, String key, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(path.startsWith("/v3/api-docs") ? 60 : 40)).header("X-Correlation-ID", "transfer-008");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private void problem(HttpResponse<String> r, int status, String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(tree(r).path("code").asString()).isEqualTo(code);
        assertThat(tree(r).path("correlationId").asString()).isEqualTo("transfer-008");
        assertThat(r.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
    }
    private static <T> List<T> concurrent(int count, Callable<T> action) throws Exception {
        var ready = new CountDownLatch(count); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> { ready.countDown(); if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout"); return action.call(); }));
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var result = new ArrayList<T>(); for (var future : futures) result.add(future.get(60, TimeUnit.SECONDS)); return result;
        }
    }
}
