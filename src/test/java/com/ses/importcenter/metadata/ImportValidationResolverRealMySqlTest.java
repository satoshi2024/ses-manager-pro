package com.ses.importcenter.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.service.DocumentService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SYNTH-only resolver候補検索。tenant分離・production natural key・target bindingを証明しない。 */
@Tag("mysql")
@Testcontainers
class ImportValidationResolverRealMySqlTest {
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("nf06_resolver").withUsername("root").withPassword("ses");

    private static final String TENANT = "synthetic-resolver-tenant";
    private static final String LEGAL_ENTITY = "77";
    private final ImportAuthorizedContext context = new ImportAuthorizedContext(TENANT, 41L, 77L);
    private JdbcTemplate jdbc;
    private ImportValidationLifecycleService lifecycle;
    private ImportMetadataService metadata;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        ImportValidationSealRealMySqlTest.createSchema(jdbc);
        createSyntheticTargetSchema(jdbc);
        jdbc.update("DELETE FROM t_import_validation_row");
        jdbc.update("DELETE FROM t_import_validation_run");
        jdbc.update("DELETE FROM t_import_job");
        jdbc.update("DELETE FROM t_import_mapping");
        jdbc.update("DELETE FROM t_document_link");
        jdbc.update("DELETE FROM t_document_version");
        jdbc.update("DELETE FROM t_document");
        jdbc.update("DELETE FROM t_import_source_system");
        jdbc.update("DELETE FROM m_customer");
        jdbc.update("DELETE FROM t_engineer");
        jdbc.update("DELETE FROM t_project_position");
        jdbc.update("DELETE FROM t_project");
        jdbc.update("DELETE FROM m_organization_unit");
        jdbc.update("DELETE FROM m_cost_center");
        jdbc.update("INSERT INTO m_customer(id,legal_entity_id,company_name,deleted_flag) VALUES(1,77,'SYNTH-CUSTOMER-1',0),(2,77,'SYNTH-CUSTOMER-DEL',1),(3,88,'SYNTH-CUSTOMER-OTHER',0),(4,77,'SYNTH-CUSTOMER-DUP',0),(5,77,'SYNTH-CUSTOMER-DUP',0)");
        jdbc.update("INSERT INTO t_engineer(id,legal_entity_id,full_name,deleted_flag) VALUES(1,77,'SYNTH-ENGINEER-1',0)");
        jdbc.update("INSERT INTO t_project(id,legal_entity_id,project_name,customer_id,deleted_flag) VALUES(1,77,'SYNTH-PROJECT-1',1,0),(2,88,'SYNTH-PROJECT-OTHER',1,0)");
        jdbc.update("INSERT INTO t_project_position(id,project_id,role_name,version,deleted_flag) VALUES(1,1,'SYNTH-POSITION-1',3,0)");
        jdbc.update("INSERT INTO m_organization_unit(id,tenant_id,legal_entity_id,code,name,type,valid_from,status,version,deleted_flag) VALUES(1,NULL,77,'SYNTH-ORG-1','Org','部','2020-01-01','有効',2,0)");
        jdbc.update("INSERT INTO m_cost_center(id,legal_entity_id,code,name,valid_from,status,version,deleted_flag) VALUES(1,77,'SYNTH-COST-1','Cost','2020-01-01','有効',4,0)");
        ImportMetadataAuthorizationService auth = mock(ImportMetadataAuthorizationService.class);
        when(auth.requireCurrentAuthorization()).thenReturn(context);
        when(auth.requireCurrentAuthorizationForWrite(context)).thenReturn(context);
        lifecycle = new ImportValidationLifecycleService(jdbc, auth, mock(DocumentService.class), new DataSourceTransactionManager(ds));
        metadata = mock(ImportMetadataService.class);
    }

    @Test
    void exactSyntheticLookupClassifiesReferencesAndRecordsSelectOnlySql() {
        SyntheticJdbcImportResolver resolver = new SyntheticJdbcImportResolver(jdbc, TENANT, LEGAL_ENTITY);
        assertEquals(com.ses.importcenter.foundation.LookupStatus.UNIQUE_ACTIVE,
                resolver.resolve(com.ses.importcenter.foundation.ReferenceType.CUSTOMER, TENANT, LEGAL_ENTITY, "SYNTH-CUSTOMER-1").status());
        assertEquals(com.ses.importcenter.foundation.LookupStatus.LOGICALLY_DELETED,
                resolver.resolve(com.ses.importcenter.foundation.ReferenceType.CUSTOMER, TENANT, LEGAL_ENTITY, "SYNTH-CUSTOMER-DEL").status());
        assertEquals(com.ses.importcenter.foundation.LookupStatus.AMBIGUOUS,
                resolver.resolve(com.ses.importcenter.foundation.ReferenceType.CUSTOMER, TENANT, LEGAL_ENTITY, "SYNTH-CUSTOMER-DUP").status());
        assertEquals(com.ses.importcenter.foundation.LookupStatus.OUT_OF_SCOPE,
                resolver.resolve(com.ses.importcenter.foundation.ReferenceType.CUSTOMER, TENANT, LEGAL_ENTITY, "SYNTH-CUSTOMER-OTHER").status());
        assertEquals(com.ses.importcenter.foundation.LookupStatus.OUT_OF_SCOPE,
                resolver.resolve(com.ses.importcenter.foundation.ReferenceType.PROJECT, "other-tenant", LEGAL_ENTITY, "SYNTH-PROJECT-1").status());
        assertEquals(com.ses.importcenter.foundation.LookupStatus.UNIQUE_ACTIVE,
                resolver.resolve(com.ses.importcenter.foundation.ReferenceType.ENGINEER, TENANT, LEGAL_ENTITY, "SYNTH-ENGINEER-1").status());
        assertEquals(com.ses.importcenter.foundation.LookupStatus.UNIQUE_ACTIVE,
                resolver.resolve(com.ses.importcenter.foundation.ReferenceType.ORGANIZATION, TENANT, LEGAL_ENTITY, "SYNTH-ORG-1").status());
        assertEquals("SYNTH-CUSTOMER-1", resolver.resolve(com.ses.importcenter.foundation.ReferenceType.PROJECT,
                TENANT, LEGAL_ENTITY, "SYNTH-PROJECT-1").candidates().get(0).relationKeys().get("customerExternalKey"));
        assertEquals("SYNTH-PROJECT-1", resolver.resolve(com.ses.importcenter.foundation.ReferenceType.POSITION,
                TENANT, LEGAL_ENTITY, "SYNTH-POSITION-1").candidates().get(0).relationKeys().get("projectExternalKey"));
        assertEquals(com.ses.importcenter.foundation.LookupStatus.OUT_OF_SCOPE,
                resolver.resolve(com.ses.importcenter.foundation.ReferenceType.COST_CENTER,
                        TENANT, "999", "SYNTH-COST-1").status());
        assertEquals(com.ses.importcenter.foundation.LookupStatus.NOT_FOUND,
                resolver.resolve(com.ses.importcenter.foundation.ReferenceType.CUSTOMER, TENANT, LEGAL_ENTITY, "SYNTH-MISSING").status());
        assertFalse(resolver.statements().isEmpty());
        assertTrue(resolver.statements().stream().allMatch(sql -> sql.stripLeading().regionMatches(true, 0, "SELECT", 0, 6)));
    }

    @Test
    void injectedResolverIsSharedByAppendAndSealedRecountWhileDefaultRemainsFailClosed() throws Exception {
        String csv = "externalKey,projectName,customerExternalKey\nSYNTH-ROW-1,SYNTH-NEW-PROJECT,SYNTH-CUSTOMER-1\n";
        long jobId = prepareProjectJob(csv);
        Map<String, String> domainBefore = domainSnapshot();
        String linkageBefore = tableSnapshot("t_document_link");
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(bytes);
        when(metadata.openSource(jobId)).thenAnswer(ignored -> new ImportSourceContent(new ByteArrayInputStream(bytes),
                "synthetic.csv", ImportEncoding.UTF8, ImportEntity.PROJECT, HexFormat.of().formatHex(sha)));
        SyntheticJdbcImportResolver resolver = new SyntheticJdbcImportResolver(jdbc, TENANT, LEGAL_ENTITY);
        ImportValidationAppendService append = new ImportValidationAppendService(jdbc, metadata, lifecycle,
                new ObjectMapper(), new com.ses.importcenter.foundation.ImportValidationPipeline(
                new com.ses.importcenter.foundation.SesCsvParser(), new com.ses.importcenter.foundation.ImportValidationService()), resolver);
        var lease = lifecycle.start(lifecycle.beginStart(jobId));
        var completion = append.appendRemainingWithSealCapability(lease);
        assertEquals(2, resolver.statements().size(), "pre-scanとappend scanで同一resolverを使う");
        var rowBefore = rowSnapshot(completion.lease().jobId(), completion.lease().runId());
        var sealed = lifecycle.seal(completion.capability());
        var recount = append.recountSealed(sealed);
        assertEquals(3, resolver.statements().size(), "sealed recountも同一resolverを使う");
        assertEquals(rowBefore, rowSnapshot(jobId, sealed.runId()));
        assertEquals(completion.capability().rowSetSha256(), recount.rowSetSha256());
        assertEquals(completion.capability().counters().sourceRows(), recount.counters().sourceRows());
        assertEquals("ACCEPTED", rowBefore.disposition);
        assertEquals(domainBefore, domainSnapshot(), "domain row hashとmax IDは不変");
        assertEquals(linkageBefore, tableSnapshot("t_document_link"), "document linkageは不変");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('t_import_identity_registry','t_import_id_map','t_import_checkpoint')", Integer.class));
        assertEquals("SEALED", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?", String.class, TENANT, jobId, sealed.runId()));
        assertEquals(1, jdbc.queryForObject("SELECT source_row_count FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?", Integer.class, TENANT, jobId, sealed.runId()));

        long failClosedJob = prepareProjectJob("externalKey,projectName,customerExternalKey\nSYNTH-ROW-2,SYNTH-NEW-PROJECT-2,SYNTH-CUSTOMER-1\n");
        byte[] failBytes = "externalKey,projectName,customerExternalKey\nSYNTH-ROW-2,SYNTH-NEW-PROJECT-2,SYNTH-CUSTOMER-1\n".getBytes(StandardCharsets.UTF_8);
        byte[] failSha = MessageDigest.getInstance("SHA-256").digest(failBytes);
        when(metadata.openSource(failClosedJob)).thenAnswer(ignored -> new ImportSourceContent(new ByteArrayInputStream(failBytes),
                "synthetic.csv", ImportEncoding.UTF8, ImportEntity.PROJECT, HexFormat.of().formatHex(failSha)));
        ImportValidationAppendService defaultAppend = new ImportValidationAppendService(jdbc, metadata, lifecycle, new ObjectMapper());
        var failedLease = lifecycle.start(lifecycle.beginStart(failClosedJob));
        var failed = defaultAppend.appendRemainingWithSealCapability(failedLease);
        assertEquals("REJECTED", rowSnapshot(failClosedJob, failed.lease().runId()).disposition);
        assertTrue(new String(rowSnapshot(failClosedJob, failed.lease().runId()).findingCodes, StandardCharsets.US_ASCII)
                .contains("REFERENCE_LOOKUP_UNKNOWN"));
    }

    private long prepareProjectJob(String csv) throws Exception {
        long job = Math.abs(System.nanoTime()) + 1000;
        long sourceId = job + 1, documentId = job + 2, versionId = job + 3, mappingId = job + 4;
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(bytes);
        Map<String, String> mapping = Map.of("externalKey", "externalKey", "projectName", "projectName", "customerExternalKey", "customerExternalKey");
        byte[] mappingBytes = ImportMetadataService.canonicalMapping(mapping);
        byte[] mappingSha = MessageDigest.getInstance("SHA-256").digest(mappingBytes);
        jdbc.update("INSERT INTO t_import_source_system VALUES(?,?,?,?,?)", TENANT, sourceId, 1L, "SYNTH-SOURCE", "ACTIVE");
        jdbc.update("INSERT INTO t_document VALUES(?,?,?,?,?,?)", TENANT, documentId, LEGAL_ENTITY, 0, null, 0);
        jdbc.update("INSERT INTO t_document_version VALUES(?,?,?,?,?,?,?)", TENANT, documentId, versionId, 1,
                HexFormat.of().formatHex(sha), "CLEAN", 0);
        jdbc.update("INSERT INTO t_document_link(tenant_id,document_id,target_type,target_id,deleted_flag) VALUES(?,?,?,?,0)", TENANT, documentId, "IMPORT_JOB", job);
        jdbc.update("INSERT INTO t_import_mapping VALUES(?,?,?,?,?,?,?,?)", TENANT, mappingId, sourceId,
                "PROJECT", "ses-import-v1", 1, mappingSha, mappingBytes);
        jdbc.update("""INSERT INTO t_import_job(tenant_id,id,source_system_id,source_version,entity_type,schema_version,
                source_encoding,source_sha256,legal_entity_id,document_id,document_version_id,document_version_no,
                document_sha256,mapping_id,mapping_version,mapping_sha256,state,version_no,lease_generation)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'MAPPED',1,0)""", TENANT, job, sourceId, 1L, "PROJECT",
                "ses-import-v1", "UTF8", sha, LEGAL_ENTITY, documentId, versionId, 1, sha, mappingId, 1, mappingSha);
        return job;
    }

    private RowSnapshot rowSnapshot(long jobId, long runId) {
        return jdbc.queryForObject("SELECT disposition,finding_codes,row_commit_sha256 FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=1",
                (rs, n) -> new RowSnapshot(rs.getString(1), rs.getBytes(2), HexFormat.of().formatHex(rs.getBytes(3))), TENANT, jobId, runId);
    }

    private Map<String, String> domainSnapshot() {
        return Map.of("m_customer", tableSnapshot("m_customer"), "t_engineer", tableSnapshot("t_engineer"),
                "t_project", tableSnapshot("t_project"), "t_project_position", tableSnapshot("t_project_position"),
                "m_organization_unit", tableSnapshot("m_organization_unit"), "m_cost_center", tableSnapshot("m_cost_center"));
    }

    private String tableSnapshot(String table) {
        if (!List.of("m_customer", "t_engineer", "t_project", "t_project_position", "m_organization_unit",
                "m_cost_center", "t_document_link").contains(table)) throw new IllegalArgumentException("fixture table only");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            jdbc.query("SELECT * FROM " + table + " ORDER BY id", rs -> {
                var md = rs.getMetaData();
                while (rs.next()) for (int i = 1; i <= md.getColumnCount(); i++) {
                    Object value = rs.getObject(i);
                    byte[] bytes = value instanceof byte[] b ? b : String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                    digest.update((byte) (value == null ? 0 : 1));
                    digest.update(bytes);
                    digest.update((byte) 0xff);
                }
                return null;
            });
            Long maxId = jdbc.queryForObject("SELECT MAX(id) FROM " + table, Long.class);
            digest.update(String.valueOf(maxId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) { throw new IllegalStateException("synthetic row snapshot failed", failure); }
    }

    private static void createSyntheticTargetSchema(JdbcTemplate j) {
        j.execute("CREATE TABLE IF NOT EXISTS m_customer(id BIGINT PRIMARY KEY,legal_entity_id BIGINT,company_name VARCHAR(200),deleted_flag TINYINT DEFAULT 0)");
        j.execute("CREATE TABLE IF NOT EXISTS t_engineer(id BIGINT PRIMARY KEY,legal_entity_id BIGINT,full_name VARCHAR(100),deleted_flag TINYINT DEFAULT 0)");
        j.execute("CREATE TABLE IF NOT EXISTS t_project(id BIGINT PRIMARY KEY,legal_entity_id BIGINT,project_name VARCHAR(200),customer_id BIGINT,deleted_flag TINYINT DEFAULT 0)");
        j.execute("CREATE TABLE IF NOT EXISTS t_project_position(id BIGINT PRIMARY KEY,project_id BIGINT,role_name VARCHAR(200),version INT,deleted_flag TINYINT DEFAULT 0)");
        j.execute("CREATE TABLE IF NOT EXISTS m_organization_unit(id BIGINT PRIMARY KEY,tenant_id BIGINT NULL,legal_entity_id BIGINT,code VARCHAR(50),name VARCHAR(200),type VARCHAR(20),valid_from DATE,valid_to DATE,status VARCHAR(20),version INT,deleted_flag TINYINT DEFAULT 0)");
        j.execute("CREATE TABLE IF NOT EXISTS m_cost_center(id BIGINT PRIMARY KEY,legal_entity_id BIGINT,code VARCHAR(50),name VARCHAR(200),valid_from DATE,valid_to DATE,status VARCHAR(20),version INT,deleted_flag TINYINT DEFAULT 0)");
    }

    private record RowSnapshot(String disposition, byte[] findingCodes, String commitment) {
        @Override public boolean equals(Object other) {
            return other instanceof RowSnapshot that && disposition.equals(that.disposition)
                    && java.util.Arrays.equals(findingCodes, that.findingCodes) && commitment.equals(that.commitment);
        }
        @Override public int hashCode() { return java.util.Objects.hash(disposition, java.util.Arrays.hashCode(findingCodes), commitment); }
    }
}
