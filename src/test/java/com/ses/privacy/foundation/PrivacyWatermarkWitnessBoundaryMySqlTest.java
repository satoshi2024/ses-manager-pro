package com.ses.privacy.foundation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** W2b Slice A: 3独立MySQLとwitness ACL境界を確認する。 */
@Tag("mysql")
@Testcontainers
class PrivacyWatermarkWitnessBoundaryMySqlTest {
    private static final String PASSWORD = UUID.randomUUID().toString();
    @Container
    static final MySQLContainer<?> JOURNAL = mysql("sfj_");
    @Container
    static final MySQLContainer<?> APPLICATION = mysql("sfwa_");
    @Container
    static final MySQLContainer<?> WITNESS = mysql("sfww_");

    private static AnnotationConfigApplicationContext context;
    private static TemporaryW2bHarness harness;
    private static JdbcTemplate witnessAdmin;
    private static JdbcTemplate attestor;
    private static JdbcTemplate reader;
    private static JdbcTemplate appProjection;
    private static JdbcTemplate journalPrincipalOnWitness;
    private static JdbcTemplate appProjectionPrincipalOnWitness;

    @BeforeAll
    static void initialize() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("test");
        context.refresh();
        harness = TemporaryW2bHarness.fromOwnedContainers(context.getEnvironment(), JOURNAL, APPLICATION, WITNESS);
        harness.initializeSchemas();
        witnessAdmin = new JdbcTemplate(harness.witness().adminDataSource());
        appProjection = new JdbcTemplate(harness.application().createApplicationProjectionDataSource());
        attestor = new JdbcTemplate(harness.witness().createWitnessAttestorDataSource());
        reader = new JdbcTemplate(harness.witness().createWitnessReaderDataSource());
        journalPrincipalOnWitness = new JdbcTemplate(harness.witness().createUnprivilegedJournalDataSourceOnWitness());
        appProjectionPrincipalOnWitness = new JdbcTemplate(harness.witness().createUnprivilegedApplicationDataSourceOnWitness());
        System.out.printf("SF_W2B_SLICE_A journalContainer=%s journalSchemaSha256=%s appContainer=%s appSchemaSha256=%s witnessContainer=%s witnessSchemaSha256=%s%n",
                harness.journal().containerId(), harness.journal().schemaHash(),
                harness.application().containerId(), harness.application().schemaHash(),
                harness.witness().containerId(), harness.witness().schemaHash());
        assertNotEquals(harness.journal().containerId(), harness.application().containerId());
        assertNotEquals(harness.journal().containerId(), harness.witness().containerId());
        assertNotEquals(harness.application().containerId(), harness.witness().containerId());
    }

    @AfterAll
    static void closeContext() { if (context != null) context.close(); }

    @Test
    void applicationProjection権限は専用DB内のprojection行に限定される() {
        assertEquals(1, appProjection.update("INSERT INTO sf_w2b_projection(tenant_id, subject_ref, last_applied_revision, event_hash, projected_state) VALUES (?, ?, ?, ?, ?)",
                "tenant-synthetic", "subject-opaque", 1L, "a".repeat(64), "HELD"));
        assertEquals(1, appProjection.update("UPDATE sf_w2b_projection SET last_applied_revision = ? WHERE tenant_id = ? AND subject_ref = ? AND last_applied_revision = ?",
                2L, "tenant-synthetic", "subject-opaque", 1L));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> appProjection.update("DELETE FROM sf_w2b_projection"));
        assertEquals(0, witnessAdmin.queryForObject("SELECT COUNT(*) FROM sf_w2b_checkpoint_log", Integer.class));
    }

    @Test
    void witnessLogはappendOnlyでlatestCASだけが更新できreaderはreadOnly() {
        attestor.update("INSERT INTO sf_w2b_checkpoint_log(journal_id, tenant_id, subject_ref, checkpoint_epoch, event_revision, event_hash, checkpoint_hash, key_id, signature_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "journal-synthetic", "tenant-synthetic", "subject-opaque", 1L, 4L, "a".repeat(64),
                "b".repeat(64), "key-synthetic", "c".repeat(64));
        assertEquals(1, attestor.update("INSERT INTO sf_w2b_latest_checkpoint(journal_id, tenant_id, subject_ref, checkpoint_epoch, event_revision, event_hash, checkpoint_hash) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "journal-synthetic", "tenant-synthetic", "subject-opaque", 1L, 4L, "a".repeat(64), "b".repeat(64)));
        assertEquals(1, attestor.update("UPDATE sf_w2b_latest_checkpoint SET checkpoint_epoch = ?, event_revision = ? WHERE journal_id = ? AND tenant_id = ? AND subject_ref = ? AND checkpoint_epoch = ?",
                2L, 5L, "journal-synthetic", "tenant-synthetic", "subject-opaque", 1L));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> attestor.update("UPDATE sf_w2b_checkpoint_log SET event_revision = 9 WHERE journal_id = ?", "journal-synthetic"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> attestor.update("DELETE FROM sf_w2b_checkpoint_log WHERE journal_id = ?", "journal-synthetic"));
        assertEquals(1, reader.queryForObject("SELECT COUNT(*) FROM sf_w2b_checkpoint_log", Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> reader.update("INSERT INTO sf_w2b_checkpoint_log(journal_id, tenant_id, subject_ref, checkpoint_epoch, event_revision, event_hash, checkpoint_hash, key_id, signature_digest) VALUES ('j','t','s',1,1,?,?,?,?)",
                        "a".repeat(64), "b".repeat(64), "key", "c".repeat(64)));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> journalPrincipalOnWitness.queryForObject("SELECT COUNT(*) FROM sf_w2b_latest_checkpoint", Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> journalPrincipalOnWitness.update("DELETE FROM sf_w2b_checkpoint_log"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> appProjectionPrincipalOnWitness.queryForObject("SELECT COUNT(*) FROM sf_w2b_latest_checkpoint", Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> appProjectionPrincipalOnWitness.update("UPDATE sf_w2b_latest_checkpoint SET checkpoint_epoch = 99"));
        assertEquals(2L, witnessAdmin.queryForObject("SELECT checkpoint_epoch FROM sf_w2b_latest_checkpoint", Long.class));
    }

    private static MySQLContainer<?> mysql(String prefix) {
        String db = prefix + UUID.randomUUID().toString().replace("-", "");
        return new MySQLContainer<>("mysql:8.0").withDatabaseName(db).withUsername("root").withPassword(PASSWORD)
                .withCommand("--log-bin=mysql-bin", "--sync-binlog=1", "--innodb-flush-log-at-trx-commit=1");
    }
}
