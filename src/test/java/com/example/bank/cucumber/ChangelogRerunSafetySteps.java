package com.example.bank.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * Regression cover for the Liquibase changelog-identity bug.
 *
 * <p>These scenarios manage RAW database state, so they do NOT reuse the Spring context's
 * {@code @ServiceConnection} container. Each scenario gets a DEDICATED Testcontainers
 * PostgreSQL instance (same image as {@code compose.yml}: {@code postgres:18-alpine3.23}),
 * started in the {@code Given} step and stopped in an {@code @After} hook — every scenario
 * is hermetic (fresh container, closed after). Per-scenario container startup is the simplest
 * way to guarantee isolation; on this machine a postgres:18-alpine container boots in ~1-2s,
 * which is acceptable for three scenarios.
 *
 * <p>The legacy database is reconstructed by JDBC-applying the frozen
 * {@code legacy-changelog/} SQL fixtures in order AND seeding the {@code DATABASECHANGELOG}
 * table with the rows the old {@code .sql} changelogs recorded (FILENAME =
 * {@code db/changelog/changes/<NNN>.sql}). That bookkeeping is what makes the file-rename
 * collision real: the current XML changesets carry the same {@code (id, author)} under a
 * {@code .xml} filename, so without the precondition fix Liquibase treats them as NEW and
 * re-runs their DDL.
 *
 * <p>The current changelog is then run programmatically via the Liquibase core API against
 * the container, using a {@link ClassLoaderResourceAccessor} pointed at the REAL
 * {@code db/changelog/db.changelog-master.xml} on the classpath.
 */
public class ChangelogRerunSafetySteps {

    /** Same image as compose.yml. */
    private static final String POSTGRES_IMAGE = "postgres:18-alpine3.23";

    /** The real, shipped master changelog (classpath resource). */
    private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.xml";

    /** Legacy fixture SQL files, in apply order, by the highest legacy index they reach. */
    private static final List<String> LEGACY_FILES = List.of(
            "001-create-accounts-table.sql",
            "002-create-transactions-table.sql",
            "003-create-idempotency-records-table.sql",
            "004-add-card-number-to-accounts.sql",
            "005-normalize-cards.sql");

    private PostgreSQLContainer<?> postgres;
    private Connection connection;
    private int lastRunExecutedCount = -1;

    @After
    public void tearDown() throws Exception {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // best-effort
            }
            connection = null;
        }
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
    }

    // ---------------------------------------------------------------- Given

    @Given("a fresh empty database")
    public void aFreshEmptyDatabase() throws Exception {
        startContainer();
    }

    @Given("a database left by the legacy SQL changelogs {int} through {int}")
    public void aDatabaseLeftByTheLegacySqlChangelogs(final int from, final int to) throws Exception {
        startContainer();
        seedLegacyDatabase(to);
    }

    // ---------------------------------------------------------------- When

    @When("the current Liquibase changelog is applied")
    public void theCurrentLiquibaseChangelogIsApplied() throws Exception {
        applyCurrentChangelog();
    }

    @When("the current Liquibase changelog is applied again")
    public void theCurrentLiquibaseChangelogIsAppliedAgain() throws Exception {
        final long before = countChangelogRows();
        applyCurrentChangelog();
        final long after = countChangelogRows();
        // Re-run safety: a no-op update adds no new DATABASECHANGELOG rows.
        lastRunExecutedCount = (int) (after - before);
    }

    // ---------------------------------------------------------------- Then

    private Exception updateError;

    @Then("the update completes without error")
    public void theUpdateCompletesWithoutError() {
        assertThat(updateError)
                .as("Liquibase update must not throw (the changelog-identity bug threw "
                        + "\"relation \\\"accounts\\\" already exists\")")
                .isNull();
    }

    @Then("changesets {string} are marked as MARK_RAN")
    public void changesetsAreMarkedAsMarkRan(final String csv) throws Exception {
        assertExecType(csv, "MARK_RAN");
    }

    @Then("changesets {string} are marked as EXECUTED")
    public void changesetsAreMarkedAsExecuted(final String csv) throws Exception {
        assertExecType(csv, "EXECUTED");
    }

    @Then("the seeded account data is preserved")
    public void theSeededAccountDataIsPreserved() throws Exception {
        assertThat(queryLong("SELECT COUNT(*) FROM accounts")).isEqualTo(2L);
        assertThat(queryString("SELECT holder_name FROM accounts ORDER BY holder_name LIMIT 1"))
                .isEqualTo("Alice");
    }

    @Then("the accounts table has no card_number column")
    public void theAccountsTableHasNoCardNumberColumn() throws Exception {
        assertThat(columnExists("accounts", "card_number"))
                .as("the 005 normalization drops accounts.card_number")
                .isFalse();
    }

    @Then("the cards table has {int} rows")
    public void theCardsTableHasRows(final int expected) throws Exception {
        assertThat(queryLong("SELECT COUNT(*) FROM cards")).isEqualTo((long) expected);
    }

    @Then("the schema matches the fully-migrated end-state")
    public void theSchemaMatchesTheFullyMigratedEndState() throws Exception {
        // cards exist + populated, accounts.card_number gone, interim index gone.
        assertThat(tableExists("cards")).isTrue();
        assertThat(columnExists("accounts", "card_number")).isFalse();
        assertThat(indexExists("uk_accounts_card_number")).isFalse();
        assertThat(queryLong("SELECT COUNT(*) FROM cards")).isEqualTo(2L);
        // card numbers migrated from the original seed
        assertThat(queryLong(
                "SELECT COUNT(*) FROM cards WHERE card_number IN ('4539148803436467','6011000990139424')"))
                .isEqualTo(2L);
    }

    @Then("the second run executed no changesets")
    public void theSecondRunExecutedNoChangesets() {
        assertThat(lastRunExecutedCount)
                .as("a second update over an up-to-date DB must add no DATABASECHANGELOG rows")
                .isZero();
    }

    // ---------------------------------------------------------------- helpers

    private void startContainer() throws SQLException {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE));
        postgres.start();
        connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        connection.setAutoCommit(true);
    }

    /**
     * Rebuild the database to the state a legacy {@code .sql}-changelog deployment left it:
     * apply the fixture DDL/DML up to {@code throughIndex}, then write the legacy
     * DATABASECHANGELOG rows (FILENAME = {@code db/changelog/changes/<NNN>.sql}).
     */
    private void seedLegacyDatabase(final int throughIndex) throws Exception {
        connection.createStatement().execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        for (int i = 1; i <= throughIndex; i++) {
            final String file = LEGACY_FILES.get(i - 1);
            applyLegacySqlFixture(file);
        }
        seedLegacyDatabaseChangelog(throughIndex);
    }

    /** Execute the forward (non-rollback) statements of a frozen formatted-SQL fixture. */
    private void applyLegacySqlFixture(final String fileName) throws Exception {
        final String body = readResource("legacy-changelog/changes/" + fileName);
        for (final String sql : forwardStatements(body)) {
            try (Statement st = connection.createStatement()) {
                st.execute(sql);
            }
        }
    }

    /**
     * Parse a {@code --liquibase formatted sql} fixture into its forward SQL statements,
     * dropping {@code --rollback} lines and all directive/comment lines. Statements are
     * split on {@code ;} at line granularity (the fixtures use one statement per line group).
     */
    private List<String> forwardStatements(final String body) {
        final StringBuilder current = new StringBuilder();
        final List<String> statements = new ArrayList<>();
        for (final String raw : body.split("\n")) {
            final String line = raw.strip();
            if (line.isEmpty() || line.startsWith("--")) {
                // skip blank lines, --liquibase, --changeset, --rollback and plain comments
                continue;
            }
            current.append(line).append(' ');
            if (line.endsWith(";")) {
                final String stmt = current.toString().strip();
                statements.add(stmt.substring(0, stmt.length() - 1).strip());
                current.setLength(0);
            }
        }
        final String tail = current.toString().strip();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    /**
     * Seed DATABASECHANGELOG exactly as the legacy {@code .sql} changelogs recorded it — same
     * {@code (id, author)} the XML carries, but the legacy {@code .sql} FILENAME. This is the
     * crux: the XML changesets then differ only by filename and count as NEW.
     */
    private void seedLegacyDatabaseChangelog(final int throughIndex) throws Exception {
        liquibaseTableBootstrap();
        // (id, author, filename) per legacy changeset, in apply order, grouped by legacy index.
        final Map<Integer, List<String[]>> byIndex = new LinkedHashMap<>();
        byIndex.put(1, List.of(
                new String[] {"001-create-accounts-table", "db/changelog/changes/001-create-accounts-table.sql"},
                new String[] {"002-seed-accounts", "db/changelog/changes/001-create-accounts-table.sql"}));
        byIndex.put(2, List.of(
                new String[] {"002-create-transactions-table", "db/changelog/changes/002-create-transactions-table.sql"},
                new String[] {"002-index-transactions-account", "db/changelog/changes/002-create-transactions-table.sql"}));
        byIndex.put(3, List.<String[]>of(
                new String[] {"003-create-idempotency-records-table", "db/changelog/changes/003-create-idempotency-records-table.sql"}));
        byIndex.put(4, List.of(
                new String[] {"004-add-card-number-to-accounts", "db/changelog/changes/004-add-card-number-to-accounts.sql"},
                new String[] {"004-unique-card-number", "db/changelog/changes/004-add-card-number-to-accounts.sql"}));
        byIndex.put(5, List.of(
                new String[] {"005-create-cards-table", "db/changelog/changes/005-normalize-cards.sql"},
                new String[] {"005-migrate-card-data", "db/changelog/changes/005-normalize-cards.sql"},
                new String[] {"005-drop-account-card-number", "db/changelog/changes/005-normalize-cards.sql"}));

        int order = 1;
        for (int i = 1; i <= throughIndex; i++) {
            for (final String[] cs : byIndex.get(i)) {
                insertChangelogRow(cs[0], "sean", cs[1], order++);
            }
        }
    }

    /** Create DATABASECHANGELOG with the columns Liquibase 5 expects. */
    private void liquibaseTableBootstrap() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS databasechangelog (
                        id            VARCHAR(255) NOT NULL,
                        author        VARCHAR(255) NOT NULL,
                        filename      VARCHAR(255) NOT NULL,
                        dateexecuted  TIMESTAMP    NOT NULL,
                        orderexecuted INT          NOT NULL,
                        exectype      VARCHAR(10)  NOT NULL,
                        md5sum        VARCHAR(35),
                        description   VARCHAR(255),
                        comments      VARCHAR(255),
                        tag           VARCHAR(255),
                        liquibase     VARCHAR(20),
                        contexts      VARCHAR(255),
                        labels        VARCHAR(255),
                        deployment_id VARCHAR(10)
                    )
                    """);
        }
    }

    private void insertChangelogRow(final String id, final String author, final String filename,
            final int order) throws SQLException {
        try (var ps = connection.prepareStatement("""
                INSERT INTO databasechangelog
                    (id, author, filename, dateexecuted, orderexecuted, exectype, md5sum, liquibase, deployment_id)
                VALUES (?, ?, ?, now(), ?, 'EXECUTED', '8:legacyfixturemd5sum000000000000', '4.99.0', 'legacy')
                """)) {
            ps.setString(1, id);
            ps.setString(2, author);
            ps.setString(3, filename);
            ps.setInt(4, order);
            ps.executeUpdate();
        }
    }

    private void applyCurrentChangelog() {
        updateError = null;
        try (var jdbc = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            final Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(jdbc));
            try (Liquibase liquibase = new Liquibase(
                    MASTER_CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update("");
            }
        } catch (Exception e) {
            updateError = e;
        }
    }

    // -------- raw introspection helpers --------

    private long countChangelogRows() throws SQLException {
        return queryLong("SELECT COUNT(*) FROM databasechangelog");
    }

    private void assertExecType(final String csv, final String expected) throws SQLException {
        for (final String id : csv.split(",")) {
            final String found = changesetExecType(id.strip());
            assertThat(found)
                    .as("changeset '%s' EXECTYPE", id.strip())
                    .isEqualTo(expected);
        }
    }

    private String changesetExecType(final String id) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT exectype FROM databasechangelog WHERE id = ? ORDER BY orderexecuted DESC LIMIT 1")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("changeset '%s' present in DATABASECHANGELOG", id).isTrue();
                return rs.getString(1);
            }
        }
    }

    private boolean columnExists(final String table, final String column) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean tableExists(final String table) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_name = ? AND table_schema = 'public'")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean indexExists(final String index) throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT 1 FROM pg_indexes WHERE indexname = ? AND schemaname = 'public'")) {
            ps.setString(1, index);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private long queryLong(final String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String queryString(final String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private String readResource(final String path) throws Exception {
        try (var in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
