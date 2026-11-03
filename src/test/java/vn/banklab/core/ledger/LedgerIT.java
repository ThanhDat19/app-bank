package vn.banklab.core.ledger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.banklab.core.ledger.application.LedgerPostingCommand;
import vn.banklab.core.ledger.application.LedgerPostingService;
import vn.banklab.core.shared.application.BusinessException;
import vn.banklab.core.support.SignedTokenServer;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerIT {
    static final SignedTokenServer TOKENS;

    static {
        try {
            TOKENS = new SignedTokenServer();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
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
    static void closeTokens() {
        TOKENS.close();
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    LedgerPostingService posting;
    @Autowired
    vn.banklab.core.ledger.application.OutgoingPolicyRepository outgoing;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private static final UUID CLEARING = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private record Owner(String subject, String token, UUID accountId, UUID ledgerId) {
    }

    @Test
    void provisioningIsOwnedAtomicAndCreatesNoMoney() throws Exception {
        var owner = opened();
        problem(balanceHttp(owner), 422, "LEDGER_NOT_PROVISIONED");
        var first = provision(owner);
        assertThat(first.statusCode()).isEqualTo(200);
        var second = provision(owner);
        assertThat(tree(second)).isEqualTo(tree(first));
        assertThat(tree(first).path("accountType").asString()).isEqualTo("LIABILITY");
        assertThat(tree(first).path("normalSide").asString()).isEqualTo("CREDIT");
        assertThat(tree(balanceHttp(owner)).path("postedBalance").asLong()).isZero();
        assertThat(jdbc.sql("SELECT ledger_provisioned FROM banking.accounts WHERE id=:id").param("id", owner.accountId()).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM banking.account_state_changes WHERE account_id=:id").param("id", owner.accountId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void securityAndOwnershipDoNotLeakOtherBalances() throws Exception {
        var owner = provisioned();
        String other = token(UUID.randomUUID().toString(), "CUSTOMER");
        problem(call("GET", path(owner, "balance"), null, null), 401, "UNAUTHENTICATED");
        problem(call("GET", path(owner, "balance"), other, null), 404, "ACCOUNT_NOT_FOUND");
        problem(call("POST", path(owner, "provision"), other, null), 404, "ACCOUNT_NOT_FOUND");
        problem(call("POST", path(owner, "provision"), operator(), null), 403, "ACCESS_DENIED");
        problem(call("GET", "/api/v1/operations/ledger/accounts/" + owner.accountId() + "/balance", owner.token(), null), 403, "ACCESS_DENIED");
        assertThat(call("GET", "/api/v1/operations/ledger/accounts/" + owner.accountId() + "/balance", operator(), null).statusCode()).isEqualTo(200);
    }

    @Test
    void frozenOrClosedCannotProvision() throws Exception {
        var owner = opened();
        String operator = operator();
        assertThat(state(owner, "freeze", operator, 0, "SECURITY_REVIEW", true).statusCode()).isEqualTo(200);
        problem(provision(owner), 409, "ACCOUNT_STATE_INVALID");
        var closed = opened();
        assertThat(close(closed).statusCode()).isEqualTo(200);
        problem(provision(closed), 409, "ACCOUNT_STATE_INVALID");
    }

    @Test
    void concurrentProvisioningReturnsOneStableLink() throws Exception {
        var owner = opened();
        var responses = concurrent(8, () -> provision(owner));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsOnly(200);
        assertThat(responses.stream().map(r -> tree(r).path("ledgerAccountId").asString()).distinct().count()).isEqualTo(1);
        assertThat(linkCount(owner)).isEqualTo(1);
    }

    @Test
    void markerFailureRollsBackInsertedLedgerLink() throws Exception {
        var owner = opened();
        jdbc.sql("ALTER TABLE banking.accounts ADD CONSTRAINT test_marker_failure CHECK (id <> '" + owner.accountId() + "'::uuid OR NOT ledger_provisioned)").update();
        try {
            problem(provision(owner), 500, "INTERNAL_ERROR");
            assertThat(linkCount(owner)).isZero();
            assertThat(jdbc.sql("SELECT ledger_provisioned FROM banking.accounts WHERE id=:id").param("id", owner.accountId()).query(Boolean.class).single()).isFalse();
        } finally {
            jdbc.sql("ALTER TABLE banking.accounts DROP CONSTRAINT test_marker_failure").update();
        }
    }

    @Test
    void balancedPostingDerivesCustomerCreditAndAssetDebitBalances() throws Exception {
        var owner = provisioned();
        UUID journal = credit(owner, "500000");
        assertThat(balance(owner)).isEqualByComparingTo("500000");
        assertThat(tree(balanceHttp(owner)).path("postedBalance").asLong()).isEqualTo(500000);
        assertThat(balanceHttp(owner).body()).contains("\"postedBalance\":\"500000\"");
        assertThat(jdbc.sql("SELECT status FROM banking.ledger_journals WHERE id=:id").param("id", journal).query(String.class).single()).isEqualTo("POSTED");
        assertThat(jdbc.sql("SELECT sum(CASE WHEN side='DEBIT' THEN amount ELSE -amount END) FROM banking.ledger_postings WHERE journal_id=:id")
                .param("id", journal).query(BigDecimal.class).single()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbc.sql("SELECT actor_subject FROM banking.ledger_journals WHERE id=:id").param("id", journal).query(String.class).single()).isEqualTo("trusted-posting-fixture");
        problem(close(owner), 422, "ACCOUNT_BALANCE_NOT_ZERO");
        debit(owner, "500000");
        assertThat(balance(owner)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(close(owner).statusCode()).isEqualTo(200);
    }

    @Test
    void postingNeedsOuterTransactionAndVerifiedActorContext() throws Exception {
        var owner = provisioned();
        var command = creditCommand(owner, "1", UUID.randomUUID());
        assertThatThrownBy(() -> posting.post(command)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx().execute(s -> posting.post(command))).isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
        assertThat(balance(owner)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void invalidAndUnbalancedJavaCommandsCannotPersistPartialJournal() throws Exception {
        var owner = provisioned();
        UUID reference = UUID.randomUUID();
        var invalid = new LedgerPostingCommand(reference, LedgerPostingCommand.Kind.ADJUSTMENT, List.of(
                line(CLEARING, LedgerPostingCommand.Side.DEBIT, "2"), line(owner.ledgerId(), LedgerPostingCommand.Side.CREDIT, "1")));
        assertBusiness("LEDGER_JOURNAL_UNBALANCED", () -> post(invalid));
        assertThat(journalCount(reference)).isZero();
        assertThat(balance(owner)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void duplicateReferenceDoesNotPostTwice() throws Exception {
        var owner = provisioned();
        UUID ref = UUID.randomUUID();
        var command = creditCommand(owner, "100", ref);
        post(command);
        assertBusiness("LEDGER_REFERENCE_EXISTS", () -> post(command));
        assertThat(journalCount(ref)).isEqualTo(1);
        assertThat(balance(owner)).isEqualByComparingTo("100");
    }

    @Test
    void finalizedWriteFailureRollsBackAllLinesAndBalance() throws Exception {
        var owner = provisioned();
        UUID ref = UUID.randomUUID();
        jdbc.sql("ALTER TABLE banking.ledger_journals ADD CONSTRAINT test_finalize_failure CHECK (reference_id <> '" + ref + "'::uuid OR status <> 'POSTED')").update();
        try {
            expectSqlGuard(() -> post(creditCommand(owner, "100", ref)));
            assertThat(journalCount(ref)).isZero();
            assertThat(balance(owner)).isEqualByComparingTo(BigDecimal.ZERO);
        } finally {
            jdbc.sql("ALTER TABLE banking.ledger_journals DROP CONSTRAINT test_finalize_failure").update();
        }
    }

    @Test
    void customerCannotOverspendEvenWithConcurrentCommands() throws Exception {
        var owner = provisioned();
        credit(owner, "100");
        var results = concurrent(8, () -> {
            try {
                debit(owner, "30");
                return "POSTED";
            } catch (BusinessException e) {
                return e.getCode();
            }
        });
        assertThat(results).containsOnly("POSTED", "LEDGER_INSUFFICIENT_FUNDS").filteredOn("POSTED"::equals).hasSize(3);
        assertThat(balance(owner)).isEqualByComparingTo("10");
    }

    @Test
    void databaseAloneSerializesConcurrentOverspending() throws Exception {
        var owner = provisioned();
        credit(owner, "100");
        var results = concurrent(2, () -> {
            try {
                rawPost(owner, true, "80");
                return "POSTED";
            } catch (Exception e) {
                return sqlState(e);
            }
        });
        assertThat(results).containsExactlyInAnyOrder("POSTED", "23514");
        assertThat(balance(owner)).isEqualByComparingTo("20");
    }

    @Test
    void zeroProvisionedBalanceCanCloseAndNonzeroSqlCloseIsRejected() throws Exception {
        var zero = provisioned();
        assertThat(close(zero).statusCode()).isEqualTo(200);
        var funded = provisioned();
        credit(funded, "1");
        expectSqlGuard(() -> jdbc.sql("UPDATE banking.accounts SET status='CLOSED' WHERE id=:id").param("id", funded.accountId()).update());
        assertThat(accountState(funded)).isEqualTo("ACTIVE");
    }

    @Test
    void provisioningAndCloseRacePreservesMarkerLinkConsistency() throws Exception {
        var owner = opened();
        var turn = new AtomicInteger();
        var responses = concurrent(2, () -> turn.getAndIncrement() == 0 ? provision(owner) : close(owner));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).allMatch(Set.of(200, 409)::contains);
        assertThat(accountState(owner)).isEqualTo("CLOSED");
        boolean marker = jdbc.sql("SELECT ledger_provisioned FROM banking.accounts WHERE id=:id").param("id", owner.accountId()).query(Boolean.class).single();
        assertThat(marker).isEqualTo(linkCount(owner) == 1);
        problem(provision(owner), 409, "ACCOUNT_STATE_INVALID");
    }

    @Test
    void postingAndCloseRaceCannotLeaveFundedClosedAccount() throws Exception {
        var owner = provisioned();
        var turn = new AtomicInteger();
        var results = concurrent(2, () -> {
            if (turn.getAndIncrement() == 0) {
                try {
                    credit(owner, "100");
                    return "POSTED";
                } catch (BusinessException e) {
                    return e.getCode();
                }
            }
            return "CLOSE_" + close(owner).statusCode();
        });
        if (results.contains("POSTED")) {
            assertThat(results).containsExactlyInAnyOrder("POSTED", "CLOSE_422");
            assertThat(accountState(owner)).isEqualTo("ACTIVE");
            assertThat(balance(owner)).isEqualByComparingTo("100");
        } else {
            assertThat(results).containsExactlyInAnyOrder("ACCOUNT_STATE_INVALID", "CLOSE_200");
            assertThat(accountState(owner)).isEqualTo("CLOSED");
            assertThat(balance(owner)).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void postingAndFreezeRaceHasConsistentFinalBalance() throws Exception {
        var owner = provisioned();
        String operator = operator();
        var turn = new AtomicInteger();
        var results = concurrent(2, () -> {
            if (turn.getAndIncrement() == 0) {
                try {
                    credit(owner, "100");
                    return "POSTED";
                } catch (BusinessException e) {
                    return e.getCode();
                }
            }
            return "FREEZE_" + state(owner, "freeze", operator, 0, "SECURITY_REVIEW", true).statusCode();
        });
        assertThat(results).contains("FREEZE_200").allMatch(Set.of("FREEZE_200", "POSTED", "ACCOUNT_STATE_INVALID")::contains);
        assertThat(accountState(owner)).isEqualTo("FROZEN");
        assertThat(balance(owner)).isEqualByComparingTo(results.contains("POSTED") ? "100" : "0");
        assertBusiness("ACCOUNT_STATE_INVALID", () -> credit(owner, "1"));
        expectSqlGuard(() -> rawPost(owner, false, "1"));
    }

    @Test
    void draftCannotSurviveCommitAndUnbalancedSqlCannotFinalize() throws Exception {
        expectSqlGuard(() -> tx().executeWithoutResult(s -> insertDraft(UUID.randomUUID())));
        var owner = provisioned();
        expectSqlGuard(() -> tx().executeWithoutResult(s -> {
            UUID id = insertDraft(UUID.randomUUID());
            insertLine(id, CLEARING, "DEBIT", "2");
            insertLine(id, owner.ledgerId(), "CREDIT", "1");
            finish(id);
        }));
        assertThat(balance(owner)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest(name = "database invalid amount {index}")
    @ValueSource(strings = {"0", "-1", "0.5", "1.5", "100000000000000000000", "NaN", "Infinity"})
    void databaseRejectsRawInvalidAmountsAtInsertBeforeAnyRounding(String amount) {
        tx().executeWithoutResult(s -> {
            UUID id = insertDraft(UUID.randomUUID());
            expectSqlGuard(() -> insertLine(id, CLEARING, "DEBIT", amount));
            s.setRollbackOnly();
        });
    }

    @Test
    void postedRecordsAndLinksCannotBeMutatedOrExtended() throws Exception {
        var owner = provisioned();
        UUID id = credit(owner, "1");
        expectSqlGuard(() -> jdbc.sql("UPDATE banking.ledger_journals SET kind=kind WHERE id=:id").param("id", id).update());
        expectSqlGuard(() -> jdbc.sql("DELETE FROM banking.ledger_journals WHERE id=:id").param("id", id).update());
        expectSqlGuard(() -> jdbc.sql("UPDATE banking.ledger_postings SET amount=amount WHERE journal_id=:id").param("id", id).update());
        expectSqlGuard(() -> jdbc.sql("DELETE FROM banking.ledger_postings WHERE journal_id=:id").param("id", id).update());
        expectSqlGuard(() -> insertLine(id, CLEARING, "DEBIT", "1"));
        expectSqlGuard(() -> jdbc.sql("UPDATE banking.ledger_accounts SET currency=currency WHERE id=:id").param("id", owner.ledgerId()).update());
        expectSqlGuard(() -> jdbc.sql("UPDATE banking.accounts SET ledger_provisioned=FALSE WHERE id=:id").param("id", owner.accountId()).update());
        // Include dependent snapshot rows so PostgreSQL reaches the append-only trigger rather than FK preflight.
        expectSqlGuard(() -> jdbc.sql("TRUNCATE banking.ledger_postings CASCADE").update());
        assertThat(balance(owner)).isEqualByComparingTo("1");
    }

    @Test
    void doubleEntryTransferAndNormalSidesPreserveTotal() throws Exception {
        var a = provisioned();
        var b = provisioned();
        credit(a, "100");
        post(new LedgerPostingCommand(UUID.randomUUID(), LedgerPostingCommand.Kind.INTERNAL_TRANSFER, List.of(
                line(a.ledgerId(), LedgerPostingCommand.Side.DEBIT, "40"), line(b.ledgerId(), LedgerPostingCommand.Side.CREDIT, "40"))));
        assertThat(balance(a)).isEqualByComparingTo("60");
        assertThat(balance(b)).isEqualByComparingTo("40");
    }

    @Test
    void unsupportedIsolationFailsClosedAtDatabaseFinalization() throws Exception {
        var owner = provisioned();
        var template = tx();
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        expectSqlGuard(() -> template.executeWithoutResult(s -> {
            UUID id = insertDraft(UUID.randomUUID());
            insertLine(id, CLEARING, "DEBIT", "1");
            insertLine(id, owner.ledgerId(), "CREDIT", "1");
            finish(id);
        }));
        assertThat(balance(owner)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void migrationFromV3PreservesAccountAndSeedsOnlyChartMetadata() throws Exception {
        String url = upgradeDatabase("ledger_upgrade", false);
        var flyway = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(12);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword()); var s = c.createStatement();
             var r = s.executeQuery("SELECT (SELECT count(*) FROM banking.accounts), (SELECT count(*) FROM banking.ledger_accounts), (SELECT count(*) FROM banking.ledger_journals)")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getLong(1)).isEqualTo(1);
            assertThat(r.getLong(2)).isEqualTo(2);
            assertThat(r.getLong(3)).isZero();
        }
    }

    @Test
    void migrationRefusesExistingUnbackedProvisioningMarker() throws Exception {
        String url = upgradeDatabase("ledger_bad_marker", true);
        assertThatThrownBy(() -> Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate())
                .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
    }

    @Test
    void openApiHasReadAndProvisionButNoArbitraryJournalWrite() throws Exception {
        var d = tree(call("GET", "/v3/api-docs", null, null));
        var paths = d.path("paths");
        assertThat(paths.has("/api/v1/ledger/accounts/{accountId}/provision")).isTrue();
        assertThat(paths.has("/api/v1/ledger/accounts/{accountId}/balance")).isTrue();
        assertThat(paths.has("/api/v1/operations/ledger/accounts/{accountId}/balance")).isTrue();
        assertThat(paths.has("/api/v1/ledger/journals")).isFalse();
    }

    @Test
    void cumulativeBalanceLimitRejectsOverflowWithoutLosingPrecision() throws Exception {
        var owner = provisioned();
        credit(owner, "99999999999999999999");
        assertBusiness("LEDGER_BALANCE_LIMIT", () -> credit(owner, "1"));
        expectSqlGuard(() -> rawPost(owner, false, "1"));
        assertThat(balance(owner)).isEqualByComparingTo("99999999999999999999");
        assertThat(balanceHttp(owner).body()).contains("\"postedBalance\":\"99999999999999999999\"");
    }

    @Test
    void databaseRejectsEmptySameAccountAndMissingPostedTimestampJournals() throws Exception {
        expectSqlGuard(() -> tx().executeWithoutResult(s -> finish(insertDraft(UUID.randomUUID()))));
        expectSqlGuard(() -> tx().executeWithoutResult(s -> {
            UUID id = insertDraft(UUID.randomUUID());
            insertLine(id, CLEARING, "DEBIT", "1");
            insertLine(id, CLEARING, "CREDIT", "1");
            finish(id);
        }));
        var owner = provisioned();
        expectSqlGuard(() -> tx().executeWithoutResult(s -> {
            UUID id = insertDraft(UUID.randomUUID());
            insertLine(id, CLEARING, "DEBIT", "1");
            insertLine(id, owner.ledgerId(), "CREDIT", "1");
            jdbc.sql("UPDATE banking.ledger_journals SET status='POSTED',posted_at=NULL WHERE id=:id").param("id", id).update();
        }));
    }

    @Test
    void holdsReserveReleaseAndReplayWithoutPostingMoney() throws Exception {
        var source = provisioned(); var destination = provisioned();
        credit(source, "100");
        var expiry = Instant.now().plusSeconds(600).toString();
        String key = UUID.randomUUID().toString();
        var first = placeHold(source, destination, "60", expiry, key);
        assertThat(first.statusCode()).isEqualTo(201);
        String id = tree(first).path("id").asString();
        assertThat(tree(placeHold(source, destination, "60", expiry, key))).isEqualTo(tree(first));
        problem(placeHold(source, destination, "61", expiry, key), 409, "IDEMPOTENCY_CONFLICT");
        assertBalances(source, "100", "60", "40");
        assertBusiness("LEDGER_INSUFFICIENT_FUNDS", () -> debit(source, "41"));
        assertThat(holdAction(source, id, "release").statusCode()).isEqualTo(200);
        assertThat(tree(holdAction(source, id, "release")).path("state").asString()).isEqualTo("RELEASED");
        assertBalances(source, "100", "0", "100");
        assertThat(tree(placeHold(source, destination, "60", expiry, key)).path("state").asString()).isEqualTo("RELEASED");
    }

    @Test
    void fullCaptureIsAtomicAndIdempotentAcrossConcurrentRequests() throws Exception {
        var source = provisioned(); var destination = provisioned();
        credit(source, "100");
        var first = placeHold(source, destination, "60", Instant.now().plusSeconds(600).toString(), UUID.randomUUID().toString());
        String id = tree(first).path("id").asString();
        var results = concurrent(8, () -> holdAction(source, id, "capture"));
        assertThat(results).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
        assertThat(results.stream().map(r -> tree(r).path("journalId").asString()).distinct().count()).isEqualTo(1);
        assertBalances(source, "40", "0", "40");
        assertBalances(destination, "60", "0", "60");
        problem(holdAction(source, id, "release"), 409, "HOLD_STATE_INVALID");
        assertThat(journalCount(UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void concurrentReservationsCannotOverbookAvailableMoney() throws Exception {
        var source = provisioned(); var destination = provisioned(); credit(source, "100");
        String expiry = Instant.now().plusSeconds(600).toString();
        var results = concurrent(8, () -> placeHold(source, destination, "30", expiry, UUID.randomUUID().toString()));
        assertThat(results.stream().filter(r -> r.statusCode()==201).count()).isEqualTo(3);
        assertThat(results.stream().filter(r -> r.statusCode()==422).count()).isEqualTo(5);
        assertBalances(source, "100", "90", "10");
    }

    @Test
    void captureFailureRestoresReservationAndCanBeRetried() throws Exception {
        var source = provisioned(); var destination = provisioned(); credit(source, "100");
        String id = tree(placeHold(source, destination, "60", Instant.now().plusSeconds(600).toString(), UUID.randomUUID().toString())).path("id").asString();
        jdbc.sql("ALTER TABLE banking.holds ADD CONSTRAINT test_hold_failure CHECK (id<>'"+id+"'::uuid OR journal_id IS NULL)").update();
        try {
            problem(holdAction(source, id, "capture"), 500, "INTERNAL_ERROR");
            assertBalances(source, "100", "60", "40");
            assertBalances(destination, "0", "0", "0");
            assertThat(journalCount(UUID.fromString(id))).isZero();
        } finally { jdbc.sql("ALTER TABLE banking.holds DROP CONSTRAINT test_hold_failure").update(); }
        assertThat(holdAction(source, id, "capture").statusCode()).isEqualTo(200);
    }

    @Test
    void expiryAutomaticallyFreesMoneyAndCannotBeCaptured() throws Exception {
        var source = provisioned(); var destination = provisioned(); credit(source, "100");
        var r = placeHold(source, destination, "60", Instant.now().plusSeconds(2).toString(), UUID.randomUUID().toString());
        assertThat(r.statusCode()).isEqualTo(201);
        String id = tree(r).path("id").asString();
        jdbc.sql("SELECT pg_sleep(2.1)").query(Object.class).single();
        assertBalances(source, "100", "0", "100");
        assertThat(tree(call("GET", "/api/v1/holds/" + id, source.token(), null)).path("state").asString()).isEqualTo("EXPIRED");
        problem(holdAction(source, id, "capture"), 409, "HOLD_EXPIRED");
        assertThat(tree(holdAction(source, id, "release")).path("state").asString()).isEqualTo("EXPIRED");
        debit(source, "100");
    }

    @Test
    void holdsEnforceRolesOwnershipAndFrozenReleasePolicy() throws Exception {
        var source = provisioned(); var destination = provisioned(); credit(source, "100");
        String id = tree(placeHold(source, destination, "60", Instant.now().plusSeconds(600).toString(), UUID.randomUUID().toString())).path("id").asString();
        problem(call("GET", "/api/v1/holds/" + id, null, null), 401, "UNAUTHENTICATED");
        problem(call("GET", "/api/v1/holds/" + id, operator(), null), 403, "ACCESS_DENIED");
        problem(call("GET", "/api/v1/holds/" + id, destination.token(), null), 404, "HOLD_NOT_FOUND");
        problem(holdAction(destination, id, "capture"), 404, "HOLD_NOT_FOUND");
        assertThat(state(source, "freeze", operator(), 0, "SECURITY_REVIEW", true).statusCode()).isEqualTo(200);
        problem(holdAction(source, id, "capture"), 409, "ACCOUNT_STATE_INVALID");
        assertThat(holdAction(source, id, "release").statusCode()).isEqualTo(200);
        assertBalances(source, "100", "0", "100");
    }

    @Test
    void sqlCannotSpendReservationsOrCommitAnUnbackedCapture() throws Exception {
        var source = provisioned(); var destination = provisioned(); credit(source, "100");
        String id = tree(placeHold(source, destination, "60", Instant.now().plusSeconds(600).toString(), UUID.randomUUID().toString())).path("id").asString();
        assertThatThrownBy(() -> tx().execute(s -> {
            UUID journal = insertDraft(UUID.randomUUID());
            insertLine(journal, source.ledgerId(), "DEBIT", "50");
            insertLine(journal, CLEARING, "CREDIT", "50");
            jdbc.sql("UPDATE banking.ledger_journals SET status='POSTED',posted_at=now() WHERE id=:id").param("id", journal).update();
            return null;
        })).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> tx().execute(s -> {
            jdbc.sql("UPDATE banking.holds SET state='CAPTURED' WHERE id=:id").param("id", UUID.fromString(id)).update();
            return null;
        })).isInstanceOf(Exception.class);
        assertBalances(source, "100", "60", "40");
        assertThatThrownBy(() -> jdbc.sql("UPDATE banking.holds SET amount=1 WHERE id=:id").param("id", UUID.fromString(id)).update()).isInstanceOf(Exception.class);
    }

    @Test
    void failedPlacementDoesNotConsumeKeyAndNumericAmountsAreRejected() throws Exception {
        var source = provisioned(); var destination = provisioned();
        String key = UUID.randomUUID().toString(), expiry = Instant.now().plusSeconds(600).toString();
        problem(placeHold(source, destination, "60", expiry, key), 422, "LEDGER_INSUFFICIENT_FUNDS");
        credit(source, "100");
        assertThat(placeHold(source, destination, "60", expiry, key).statusCode()).isEqualTo(201);
        problem(placeHold(source, destination, "1", Instant.now().minusSeconds(10).toString(), UUID.randomUUID().toString()), 422, "HOLD_EXPIRY_INVALID");
        var absent = new Owner("absent", "", UUID.randomUUID(), null);
        problem(placeHold(source, absent, "1", expiry, UUID.randomUUID().toString()), 404, "ACCOUNT_NOT_FOUND");
        var body = "{\"accountId\":\""+source.accountId()+"\",\"destinationAccountId\":\""+destination.accountId()+"\",\"amount\":1,\"expiresAt\":\""+expiry+"\"}";
        assertThat(holdRequest(body, source.token(), UUID.randomUUID().toString()).statusCode()).isEqualTo(400);
    }

    @Test
    void duplicateAndOppositeReservationsDoNotDeadlock() throws Exception {
        var a = provisioned(); var b = provisioned(); credit(a, "100"); credit(b, "100");
        String expiry = Instant.now().plusSeconds(600).toString(), key = UUID.randomUUID().toString();
        var duplicates = concurrent(8, () -> placeHold(a, b, "10", expiry, key));
        assertThat(duplicates).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(201));
        assertThat(duplicates.stream().map(r -> tree(r).path("id").asString()).distinct().count()).isEqualTo(1);
        var direction = new AtomicInteger();
        var opposite = concurrent(8, () -> direction.getAndIncrement()%2==0
                ? placeHold(a, b, "10", expiry, UUID.randomUUID().toString())
                : placeHold(b, a, "10", expiry, UUID.randomUUID().toString()));
        assertThat(opposite).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(201));
        assertBalances(a, "100", "50", "50"); assertBalances(b, "100", "40", "60");
    }

    @Test
    void captureAndReleaseRaceHasOnlyOneTerminalEffect() throws Exception {
        var a = provisioned(); var b = provisioned(); credit(a, "100");
        String id = tree(placeHold(a, b, "60", Instant.now().plusSeconds(600).toString(), UUID.randomUUID().toString())).path("id").asString();
        var direction = new AtomicInteger();
        var replies = concurrent(8, () -> holdAction(a, id, direction.getAndIncrement()%2==0 ? "capture" : "release"));
        assertThat(replies).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isIn(200,409));
        var state = tree(call("GET", "/api/v1/holds/"+id, a.token(), null)).path("state").asString();
        if (state.equals("CAPTURED")) {
            assertBalances(a, "40", "0", "40"); assertBalances(b, "60", "0", "60");
            assertThat(journalCount(UUID.fromString(id))).isEqualTo(1);
        } else {
            assertThat(state).isEqualTo("RELEASED");
            assertBalances(a, "100", "0", "100"); assertBalances(b, "0", "0", "0");
            assertThat(journalCount(UUID.fromString(id))).isZero();
        }
    }

    @Test
    void transferFeeAndGrossDailyLimitAreAtomicAndReplayDoesNotChargeAgain() throws Exception {
        var a=provisioned(); var b=provisioned(); credit(a,"200"); policy(a,"100","100","2","Asia/Ho_Chi_Minh");
        String key=UUID.randomUUID().toString();
        var first=transfer(a,b,"30",key); assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        var journal=tree(first).path("journalId").asString();
        assertThat(tree(transfer(a,b,"30",key))).isEqualTo(tree(first));
        var assessment=call("GET",chargePath(a,journal),a.token(),null);
        assertThat(assessment.statusCode()).isEqualTo(200);
        assertThat(tree(assessment).path("feeAmount").asString()).isEqualTo("2");
        assertThat(tree(assessment).path("totalDebit").asString()).isEqualTo("32");
        assertBalances(a,"168","0","168"); assertBalances(b,"30","0","30");
        assertThat(transfer(a,b,"66",UUID.randomUUID().toString()).statusCode()).isEqualTo(201);
        problem(transfer(a,b,"1",UUID.randomUUID().toString()),422,"TRANSFER_DAILY_LIMIT");
        assertBalances(a,"100","0","100"); assertBalances(b,"96","0","96");
        problem(call("GET",chargePath(a,journal),b.token(),null),404,"ACCOUNT_NOT_FOUND");
        problem(call("GET",chargePath(a,journal),operator(),null),403,"ACCESS_DENIED");
        // Completed receipts keep the original fee snapshot when policy changes.
        jdbc.sql("UPDATE banking.outgoing_policies SET fee=3 WHERE account_id=:id").param("id",a.accountId()).update();
        assertThat(tree(transfer(a,b,"30",key))).isEqualTo(tree(first));
        assertThat(tree(call("GET",chargePath(a,journal),a.token(),null))).isEqualTo(tree(assessment));
    }

    @Test
    void concurrentGrossDebitsCannotExceedDailyLimit() throws Exception {
        var a=provisioned(); var b=provisioned(); credit(a,"500"); policy(a,"100","100","2","Asia/Ho_Chi_Minh");
        var replies=concurrent(8,()->transfer(a,b,"30",UUID.randomUUID().toString()));
        assertThat(replies.stream().filter(r->r.statusCode()==201).count()).isEqualTo(3);
        assertThat(replies.stream().filter(r->r.statusCode()==422).count()).isEqualTo(5);
        assertBalances(a,"404","0","404"); assertBalances(b,"90","0","90");
        assertThat(jdbc.sql("SELECT sum(amount+fee) FROM banking.outgoing_charges WHERE account_id=:id").param("id",a.accountId()).query(BigDecimal.class).single()).isEqualByComparingTo("96");
    }

    @Test
    void feeOrAssessmentFailureRollsBackPrincipalAndPreservesKeyForRetry() throws Exception {
        var a=provisioned(); var b=provisioned(); credit(a,"30"); policy(a,"100","100","2","Asia/Ho_Chi_Minh");
        String key=UUID.randomUUID().toString();
        problem(transfer(a,b,"30",key),422,"LEDGER_INSUFFICIENT_FUNDS");
        assertBalances(a,"30","0","30"); assertBalances(b,"0","0","0");
        credit(a,"10");
        jdbc.sql("ALTER TABLE banking.outgoing_charges ADD CONSTRAINT test_charge_failure CHECK(account_id<>'"+a.accountId()+"'::uuid)").update();
        try {
            problem(transfer(a,b,"30",key),500,"INTERNAL_ERROR");
            assertBalances(a,"40","0","40"); assertBalances(b,"0","0","0");
        } finally { jdbc.sql("ALTER TABLE banking.outgoing_charges DROP CONSTRAINT test_charge_failure").update(); }
        assertThat(transfer(a,b,"30",key).statusCode()).isEqualTo(201);
        assertBalances(a,"8","0","8");
        assertThat(jdbc.sql("SELECT count(*) FROM banking.outgoing_charges WHERE account_id=:id").param("id",a.accountId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void holdCaptureUsesGrossLimitsAndRequiresAvailableFeeOutsideTheHold() throws Exception {
        var a=provisioned(); var b=provisioned(); credit(a,"60"); policy(a,"61","100","2","Asia/Ho_Chi_Minh");
        String id=tree(placeHold(a,b,"60",Instant.now().plusSeconds(600).toString(),UUID.randomUUID().toString())).path("id").asString();
        problem(holdAction(a,id,"capture"),422,"TRANSFER_TRANSACTION_LIMIT");
        assertBalances(a,"60","60","0");
        jdbc.sql("UPDATE banking.outgoing_policies SET transaction_limit=100 WHERE account_id=:id").param("id",a.accountId()).update();
        problem(holdAction(a,id,"capture"),422,"LEDGER_INSUFFICIENT_FUNDS");
        assertBalances(a,"60","60","0");
        credit(a,"2");
        var capture=holdAction(a,id,"capture"); assertThat(capture.statusCode()).as(capture.body()).isEqualTo(200);
        assertThat(tree(holdAction(a,id,"capture"))).isEqualTo(tree(capture));
        assertBalances(a,"0","0","0"); assertBalances(b,"60","0","60");
    }

    @Test
    void databaseRejectsPolicyBypassAndAssessmentMutation() throws Exception {
        var a=provisioned(); var b=provisioned(); credit(a,"100"); policy(a,"50","50","2","Asia/Ho_Chi_Minh");
        // Even a raw posted principal cannot commit without the configured assessment.
        assertThatThrownBy(()->tx().execute(s->{
            UUID journal=UUID.randomUUID();
            jdbc.sql("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES(:id,:ref,'INTERNAL_TRANSFER','VND','DRAFT','sql-fixture','sql-fixture',now())")
                    .param("id",journal).param("ref",UUID.randomUUID()).update();
            insertLine(journal,a.ledgerId(),"DEBIT","10"); insertLine(journal,b.ledgerId(),"CREDIT","10"); finish(journal); return null;
        })).isInstanceOf(Exception.class);
        var first=transfer(a,b,"10",UUID.randomUUID().toString()); assertThat(first.statusCode()).isEqualTo(201);
        var journal=UUID.fromString(tree(first).path("journalId").asString());
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.outgoing_charges SET fee=0 WHERE journal_id=:id").param("id",journal).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.outgoing_policies SET timezone='UTC' WHERE account_id=:id").param("id",a.accountId()).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.sql("UPDATE banking.outgoing_policies SET fee=-1 WHERE account_id=:id").param("id",a.accountId()).update()).isInstanceOf(Exception.class);
        assertBalances(a,"88","0","88"); assertBalances(b,"10","0","10");
    }

    @Test
    void policyDateUsesDatabaseJournalTimeAndLocalMidnight() throws Exception {
        var a=provisioned(); credit(a,"1");
        // Deterministic journal fixture crosses the UTC+7 business-day boundary by one microsecond.
        tx().execute(s->{
            for (String instant:List.of("2026-11-01T16:59:59.999999Z","2026-11-01T17:00:00Z")) {
                UUID journal=UUID.randomUUID();
                jdbc.sql("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES(:id,:ref,'ADJUSTMENT','VND','DRAFT','sql-fixture','sql-fixture',:time)")
                        .param("id",journal).param("ref",UUID.randomUUID()).param("time",java.sql.Timestamp.from(Instant.parse(instant))).update();
                insertLine(journal,CLEARING,"DEBIT","1"); insertLine(journal,a.ledgerId(),"CREDIT","1");
                jdbc.sql("UPDATE banking.ledger_journals SET status='POSTED',posted_at=created_at WHERE id=:id").param("id",journal).update();
                String date=outgoing.businessDate(journal,"Asia/Ho_Chi_Minh").toString();
                assertThat(date).isEqualTo(instant.contains("16:59")?"2026-11-01":"2026-11-02");
            }
            return null;
        });
    }

    @Test
    void priorBusinessDayUsageDoesNotConsumeTodaysLimit() throws Exception {
        var a=provisioned(); var b=provisioned(); credit(a,"200"); policy(a,"100","50","0","Asia/Ho_Chi_Minh");
        tx().execute(s->{
            UUID journal=UUID.randomUUID();
            jdbc.sql("INSERT INTO banking.ledger_journals(id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at) VALUES(:id,:ref,'INTERNAL_TRANSFER','VND','DRAFT','sql-fixture','sql-fixture',now()-interval '2 days')")
                    .param("id",journal).param("ref",UUID.randomUUID()).update();
            insertLine(journal,a.ledgerId(),"DEBIT","50"); insertLine(journal,b.ledgerId(),"CREDIT","50");
            jdbc.sql("UPDATE banking.ledger_journals SET status='POSTED',posted_at=created_at WHERE id=:id").param("id",journal).update();
            outgoing.record(a.accountId(),journal,new BigDecimal("50"),outgoing.policy(a.accountId()),outgoing.businessDate(journal,"Asia/Ho_Chi_Minh"),null);
            return null;
        });
        assertThat(transfer(a,b,"30",UUID.randomUUID().toString()).statusCode()).isEqualTo(201);
        problem(transfer(a,b,"30",UUID.randomUUID().toString()),422,"TRANSFER_DAILY_LIMIT");
        assertBalances(a,"120","0","120"); assertBalances(b,"80","0","80");
    }

    @Test
    void principalReversalRetainsFeeAndDailyUsage() throws Exception {
        var a=provisioned(); var b=provisioned(); credit(a,"100"); policy(a,"32","32","2","Asia/Ho_Chi_Minh");
        var original=transfer(a,b,"30",UUID.randomUUID().toString()); assertThat(original.statusCode()).isEqualTo(201);
        String body="{\"originalJournalId\":\""+tree(original).path("journalId").asString()+"\",\"reason\":\"OPERATOR_ERROR\"}";
        var reversal=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/operations/reversals"))
                .header("Authorization","Bearer "+operator()).header("Idempotency-Key",UUID.randomUUID().toString()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(reversal.statusCode()).as(reversal.body()).isEqualTo(201);
        assertBalances(a,"98","0","98"); assertBalances(b,"0","0","0");
        problem(transfer(a,b,"1",UUID.randomUUID().toString()),422,"TRANSFER_DAILY_LIMIT");
    }

    private void policy(Owner a,String transaction,String daily,String fee,String zone) {
        jdbc.sql("INSERT INTO banking.outgoing_policies(account_id,transaction_limit,daily_limit,fee,timezone) VALUES(:id,:tx,:daily,:fee,:zone)")
                .param("id",a.accountId()).param("tx",new BigDecimal(transaction)).param("daily",new BigDecimal(daily)).param("fee",new BigDecimal(fee)).param("zone",zone).update();
    }

    private HttpResponse<String> transfer(Owner a,Owner b,String amount,String key) throws Exception {
        String body="{\"sourceAccountId\":\""+a.accountId()+"\",\"destinationAccountId\":\""+b.accountId()+"\",\"amount\":\""+amount+"\",\"currency\":\"VND\"}";
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/transfers"))
                .header("Authorization","Bearer "+a.token()).header("Idempotency-Key",key).header("Content-Type","application/json")
                .header("X-Correlation-ID","ledger-006").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }

    private static String chargePath(Owner a,String journal) { return "/api/v1/ledger/accounts/"+a.accountId()+"/outgoing-charges/"+journal; }

    private HttpResponse<String> placeHold(Owner source, Owner destination, String amount, String expiry, String key) throws Exception {
        return holdRequest("{\"accountId\":\""+source.accountId()+"\",\"destinationAccountId\":\""+destination.accountId()+"\",\"amount\":\""+amount+"\",\"expiresAt\":\""+expiry+"\"}", source.token(), key);
    }

    private HttpResponse<String> holdRequest(String body, String token, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/holds"))
                .header("Authorization", "Bearer "+token).header("Content-Type", "application/json")
                .header("Idempotency-Key", key).header("X-Correlation-ID", "ledger-006")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> holdAction(Owner owner, String id, String action) throws Exception {
        return call("POST", "/api/v1/holds/"+id+"/"+action, owner.token(), null);
    }

    private void assertBalances(Owner owner, String posted, String reserved, String available) throws Exception {
        var b = tree(balanceHttp(owner));
        assertThat(b.path("postedBalance").asString()).isEqualTo(posted);
        assertThat(b.path("reservedBalance").asString()).isEqualTo(reserved);
        assertThat(b.path("availableBalance").asString()).isEqualTo(available);
    }

    private Owner opened() throws Exception {
        String subject = UUID.randomUUID().toString(), token = token(subject, "CUSTOMER");
        var customer = call("POST", "/api/v1/customers", token, "{\"displayName\":\"Ledger Demo\"}");
        assertThat(customer.statusCode()).isEqualTo(201);
        String customerId = tree(customer).path("id").asString();
        assertThat(call("POST", "/api/v1/operations/customers/" + customerId + "/kyc-decisions", operator(),
                "{\"expectedVersion\":0,\"decision\":\"VERIFIED\",\"reason\":\"DOCUMENTS_MATCH\"}").statusCode()).isEqualTo(200);
        var account = call("POST", "/api/v1/accounts", token, "{\"currency\":\"VND\"}");
        assertThat(account.statusCode()).isEqualTo(201);
        return new Owner(subject, token, UUID.fromString(tree(account).path("id").asString()), null);
    }

    private Owner provisioned() throws Exception {
        var owner = opened();
        var r = provision(owner);
        assertThat(r.statusCode()).isEqualTo(200);
        return new Owner(owner.subject(), owner.token(), owner.accountId(), UUID.fromString(tree(r).path("ledgerAccountId").asString()));
    }

    private HttpResponse<String> provision(Owner o) throws Exception {
        return call("POST", path(o, "provision"), o.token(), null);
    }

    private HttpResponse<String> balanceHttp(Owner o) throws Exception {
        return call("GET", path(o, "balance"), o.token(), null);
    }

    private static String path(Owner o, String end) {
        return "/api/v1/ledger/accounts/" + o.accountId() + "/" + end;
    }

    private HttpResponse<String> close(Owner o) throws Exception {
        return state(o, "close", o.token(), 0, "CUSTOMER_REQUEST", false);
    }

    private HttpResponse<String> state(Owner o, String action, String token, long version, String reason, boolean operations) throws Exception {
        return call("POST", "/api/v1/" + (operations ? "operations/" : "") + "accounts/" + o.accountId() + "/" + action, token,
                "{\"expectedVersion\":" + version + ",\"reason\":\"" + reason + "\"}");
    }

    private BigDecimal balance(Owner o) {
        return jdbc.sql("SELECT banking.ledger_balance(:id)").param("id", o.ledgerId()).query(BigDecimal.class).single();
    }

    private long linkCount(Owner o) {
        return jdbc.sql("SELECT count(*) FROM banking.ledger_accounts WHERE bank_account_id=:id").param("id", o.accountId()).query(Long.class).single();
    }

    private String accountState(Owner o) {
        return jdbc.sql("SELECT status FROM banking.accounts WHERE id=:id").param("id", o.accountId()).query(String.class).single();
    }

    private long journalCount(UUID reference) {
        return jdbc.sql("SELECT count(*) FROM banking.ledger_journals WHERE reference_id=:id").param("id", reference).query(Long.class).single();
    }

    private UUID credit(Owner o, String amount) {
        return post(creditCommand(o, amount, UUID.randomUUID()));
    }

    private UUID debit(Owner o, String amount) {
        return post(new LedgerPostingCommand(UUID.randomUUID(), LedgerPostingCommand.Kind.ADJUSTMENT, List.of(
                line(o.ledgerId(), LedgerPostingCommand.Side.DEBIT, amount), line(CLEARING, LedgerPostingCommand.Side.CREDIT, amount))));
    }

    private static LedgerPostingCommand creditCommand(Owner o, String amount, UUID ref) {
        return new LedgerPostingCommand(ref, LedgerPostingCommand.Kind.ADJUSTMENT, List.of(
                line(CLEARING, LedgerPostingCommand.Side.DEBIT, amount), line(o.ledgerId(), LedgerPostingCommand.Side.CREDIT, amount)));
    }

    private static LedgerPostingCommand.Posting line(UUID id, LedgerPostingCommand.Side side, String amount) {
        return new LedgerPostingCommand.Posting(id, side, new BigDecimal(amount));
    }

    private UUID post(LedgerPostingCommand command) {
        // Trusted caller fixture for internal service integration, not a JWT validation/security test.
        var jwt = Jwt.withTokenValue("internal-component-fixture").header("alg", "RS256").issuer(TOKENS.issuer())
                .subject("trusted-posting-fixture").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
        try {
            return tx().execute(s -> posting.post(command));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private TransactionTemplate tx() {
        var t = new TransactionTemplate(transactions);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return t;
    }

    private UUID insertDraft(UUID reference) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO banking.ledger_journals (id,reference_id,kind,currency,status,actor_issuer,actor_subject,created_at)
                VALUES (:id,:ref,'ADJUSTMENT','VND','DRAFT','sql-fixture','sql-fixture',now())
                """).param("id", id).param("ref", reference).update();
        return id;
    }

    private void insertLine(UUID journal, UUID ledger, String side, String amount) {
        jdbc.sql("INSERT INTO banking.ledger_postings (id,journal_id,ledger_account_id,currency,side,amount) VALUES (:id,:journal,:ledger,'VND',:side,CAST(:amount AS NUMERIC))")
                .param("id", UUID.randomUUID()).param("journal", journal).param("ledger", ledger).param("side", side).param("amount", amount).update();
    }

    private void finish(UUID id) {
        jdbc.sql("UPDATE banking.ledger_journals SET status='POSTED',posted_at=clock_timestamp() WHERE id=:id").param("id", id).update();
    }

    private void rawPost(Owner o, boolean debit, String amount) {
        tx().executeWithoutResult(s -> {
            UUID id = insertDraft(UUID.randomUUID());
            insertLine(id, debit ? o.ledgerId() : CLEARING, "DEBIT", amount);
            insertLine(id, debit ? CLEARING : o.ledgerId(), "CREDIT", amount);
            finish(id);
        });
    }

    private static String sqlState(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause())
            if (current instanceof SQLException sql) return sql.getSQLState();
        return "NO_SQL_STATE";
    }

    private static void expectSqlGuard(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
    }

    private static void assertBusiness(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    private String upgradeDatabase(String name, boolean marker) throws Exception {
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        String url = POSTGRES.getJdbcUrl().replace("/test", "/" + name);
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).target("3").load().migrate();
        UUID customer = UUID.randomUUID(), account = UUID.randomUUID();
        try (var c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword()); var s = c.createStatement()) {
            s.execute("INSERT INTO banking.customers (id,identity_issuer,identity_subject,display_name,created_at,updated_at) VALUES ('" + customer + "','issuer','preserved','Preserved Demo',now(),now())");
            s.execute("INSERT INTO banking.accounts (id,customer_id,currency,ledger_provisioned,created_at,updated_at) VALUES ('" + account + "','" + customer + "','VND'," + marker + ",now(),now())");
        }
        return url;
    }

    private static String token(String subject, String... roles) throws Exception {
        return TOKENS.token(b -> b.subject(subject).claim("resource_access", Map.of("bank-api", Map.of("roles", List.of(roles)))));
    }

    private static String operator() throws Exception {
        return token(UUID.randomUUID().toString(), "OPERATOR");
    }

    private JsonNode tree(HttpResponse<String> r) {
        return json.readTree(r.body());
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30)).header("X-Correlation-ID", "ledger-006");
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json");
        return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void problem(HttpResponse<String> r, int status, String code) {
        assertThat(r.statusCode()).isEqualTo(status);
        assertThat(tree(r).path("code").asString()).isEqualTo(code);
        assertThat(tree(r).path("correlationId").asString()).isEqualTo("ledger-006");
    }

    private static <T> List<T> concurrent(int count, Callable<T> call) throws Exception {
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var fs = new ArrayList<Future<T>>();
            for (int i = 0; i < count; i++)
                fs.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(15, TimeUnit.SECONDS);
                    return call.call();
                }));
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var result = new ArrayList<T>();
            for (var f : fs) result.add(f.get(60, TimeUnit.SECONDS));
            return result;
        }
    }
}
