package vn.banklab.core.customer.infrastructure.persistence;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.banklab.core.customer.application.CustomerRepository;
import vn.banklab.core.customer.domain.Customer;
import vn.banklab.core.customer.domain.KycReason;
import vn.banklab.core.customer.domain.KycStatus;
import vn.banklab.core.iam.application.Actor;

@Repository
public class JdbcCustomerRepository implements CustomerRepository {
    private static final String SELECT = "SELECT * FROM banking.customers ";
    private static final RowMapper<Customer> MAPPER = (row, index) -> new Customer(
            row.getObject("id", UUID.class), row.getString("identity_issuer"), row.getString("identity_subject"),
            row.getString("display_name"), KycStatus.valueOf(row.getString("kyc_status")), row.getLong("version"),
            row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    private final JdbcClient jdbc;

    public JdbcCustomerRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean insert(Customer customer) {
        return jdbc.sql("""
                INSERT INTO banking.customers
                    (id, identity_issuer, identity_subject, display_name, kyc_status, version, created_at, updated_at)
                VALUES (:id, :issuer, :subject, :name, 'PENDING', 0, :now, :now)
                ON CONFLICT (identity_issuer, identity_subject) DO NOTHING
                """)
                .param("id", customer.id()).param("issuer", customer.identityIssuer())
                .param("subject", customer.identitySubject()).param("name", customer.displayName())
                .param("now", Timestamp.from(customer.createdAt())).update() == 1;
    }

    @Override
    public Optional<Customer> findByIdentity(String issuer, String subject) {
        return jdbc.sql(SELECT + "WHERE identity_issuer = :issuer AND identity_subject = :subject")
                .param("issuer", issuer).param("subject", subject).query(MAPPER).optional();
    }

    @Override
    public Optional<Customer> findByIdentityForUpdate(String issuer, String subject) {
        return jdbc.sql(SELECT + "WHERE identity_issuer = :issuer AND identity_subject = :subject FOR UPDATE")
                .param("issuer", issuer).param("subject", subject).query(MAPPER).optional();
    }

    @Override
    public Optional<Customer> findOwned(UUID id, String issuer, String subject) {
        return jdbc.sql(SELECT + "WHERE id = :id AND identity_issuer = :issuer AND identity_subject = :subject")
                .param("id", id).param("issuer", issuer).param("subject", subject).query(MAPPER).optional();
    }

    @Override
    public Optional<Customer> findById(UUID id) {
        return jdbc.sql(SELECT + "WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public boolean updateDecision(Customer before, Customer after) {
        return jdbc.sql("""
                UPDATE banking.customers SET kyc_status = :status, version = version + 1, updated_at = :now
                WHERE id = :id AND version = :expected AND kyc_status = 'PENDING'
                """)
                .param("status", after.kycStatus().name()).param("now", Timestamp.from(after.updatedAt()))
                .param("id", before.id()).param("expected", before.version()).update() == 1;
    }

    @Override
    public void appendDecision(Customer after, Actor reviewer, KycReason reason) {
        jdbc.sql("""
                INSERT INTO banking.kyc_decisions
                  (id, customer_id, from_status, to_status, reason, reviewer_issuer, reviewer_subject,
                   customer_version, decided_at)
                VALUES (:id, :customer, 'PENDING', :status, :reason, :issuer, :subject, :version, :now)
                """)
                .param("id", UUID.randomUUID()).param("customer", after.id()).param("status", after.kycStatus().name())
                .param("reason", reason.name()).param("issuer", reviewer.issuer()).param("subject", reviewer.subject())
                .param("version", after.version()).param("now", Timestamp.from(after.updatedAt())).update();
    }
}
