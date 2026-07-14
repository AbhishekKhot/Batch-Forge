package com.batchforge.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.flywaydb.core.api.MigrationVersion;

@Testcontainers
class V1MigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    private static Flyway flyway;

    @BeforeAll
    static void applyMigrations() {
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("1.5"))   // this test validates the V1 baseline only
                .load();
        flyway.migrate();
    }

    @Test
    void appliesVersion1() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1.5");
    }

    @Test
    void createsCoreTables() throws SQLException {
        assertThat(tableExists("organizations")).isTrue();
        assertThat(tableExists("users")).isTrue();
        assertThat(tableExists("jobs")).isTrue();
    }

    @Test
    void jobsHasResultErrorAndCheckpointColumns() throws SQLException {
        assertThat(columnExists("jobs", "result_object_key")).isTrue();
        assertThat(columnExists("jobs", "error_report_object_key")).isTrue();
        assertThat(columnExists("jobs", "last_processed_row")).isTrue();
        assertThat(columnExists("jobs", "version")).isTrue();
    }

    @Test
    void enforcesCaseInsensitiveEmailUniqueness() throws SQLException {
        UUID orgId = insertOrganization("Acme");
        insertUser(orgId, "user@example.com");
        assertThatThrownBy(() -> insertUser(orgId, "USER@example.com"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void rejectsUnknownRole() throws SQLException {
        UUID orgId = insertOrganization("Globex");
        assertThatThrownBy(() -> exec(
                "INSERT INTO users (id, email, password_hash, org_id, role) VALUES ('%s', 'bad-role@example.com', 'hash', '%s', 'SUPERADMIN')"
                        .formatted(UUID.randomUUID(), orgId)))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void rejectsUserWithMissingOrganization() {
        assertThatThrownBy(() -> exec(
                "INSERT INTO users (id, email, password_hash, org_id, role) VALUES ('%s', 'orphan@example.com', 'hash', '%s', 'MEMBER')"
                        .formatted(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void insertsJobWithServerDefaults() throws SQLException {
        UUID orgId = insertOrganization("Initech");
        UUID userId = insertUser(orgId, "submitter@example.com");
        UUID jobId = UUID.randomUUID();
        exec("INSERT INTO jobs (id, org_id, submitted_by, source_object_key) VALUES ('%s', '%s', '%s', 'uploads/data.csv')"
                .formatted(jobId, orgId, userId));

        try (Connection c = POSTGRES.createConnection("");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, job_type, processed_rows, failed_rows, last_processed_row, retry_count, version FROM jobs WHERE id = ?")) {
            ps.setObject(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("PENDING");
                assertThat(rs.getString("job_type")).isEqualTo("CSV_IMPORT");
                assertThat(rs.getLong("processed_rows")).isZero();
                assertThat(rs.getLong("failed_rows")).isZero();
                assertThat(rs.getLong("last_processed_row")).isZero();
                assertThat(rs.getInt("retry_count")).isZero();
                assertThat(rs.getLong("version")).isZero();
            }
        }
    }

    private static boolean tableExists(String table) throws SQLException {
        try (Connection c = POSTGRES.createConnection("");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean columnExists(String table, String column) throws SQLException {
        try (Connection c = POSTGRES.createConnection("");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static UUID insertOrganization(String name) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = POSTGRES.createConnection("");
             PreparedStatement ps = c.prepareStatement("INSERT INTO organizations (id, name) VALUES (?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID insertUser(UUID orgId, String email) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = POSTGRES.createConnection("");
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users (id, email, password_hash, org_id, role) VALUES (?, ?, 'hash', ?, 'MEMBER')")) {
            ps.setObject(1, id);
            ps.setString(2, email);
            ps.setObject(3, orgId);
            ps.executeUpdate();
        }
        return id;
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = POSTGRES.createConnection("");
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}