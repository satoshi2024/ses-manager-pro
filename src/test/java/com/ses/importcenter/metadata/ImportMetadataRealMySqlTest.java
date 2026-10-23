package com.ses.importcenter.metadata;

import com.ses.config.LoginUser;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.SysUser;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.CanonicalImportRowFingerprint;
import com.ses.importcenter.foundation.CanonicalRowFactory;
import com.ses.importcenter.foundation.SourceRow;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.DocumentService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.storage.DocumentStorage;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 実MySQL上でmetadata・document・FileScope・明示ACLを通るF1a最小成功経路を確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
@Import(ImportMetadataRealMySqlTest.TestBeans.class)
class ImportMetadataRealMySqlTest {
    private static final byte[] SOURCE = "externalKey,companyName\ncustomer-1,Example\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final String SOURCE_HASH = sha256(SOURCE);
    private static final String TEST_TENANT = "nf06-real-metadata-test";
    private static final long LEGAL_ENTITY_ID = 7_601_234L;
    private static final String LOOKUP_KEY_VERSION = "nf06-test-key-v1";
    private static final byte[] LOOKUP_KEY = new byte[32];
    private static final AtomicBoolean SOURCE_WAIT_FIXTURE_UNSAFE = new AtomicBoolean();
    private static final int SOURCE_WAIT_LOCK_SECONDS = 10;
    private static final int SOURCE_WAIT_NETWORK_MILLIS = 20_000;
    private static final int SOURCE_WAIT_FUTURE_SECONDS = 25;
    private static final int SOURCE_WAIT_TERMINATION_SECONDS = 5;

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("nf06_import_metadata_real")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("app.security.oidc.tenant-id", () -> TEST_TENANT);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ImportMetadataService metadataService;
    @Autowired private ImportIdentityCandidateService candidateService;
    @Autowired private ImportValidationLeaseService leaseService;
    @Autowired private ImportValidationLifecycleService lifecycleService;
    @Autowired private ImportValidationAppendService validationAppendService;
    @Autowired private DocumentService documentService;
    @Autowired private DocumentMapper documentMapper;
    @Autowired private DocumentVersionMapper documentVersionMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private TestDocumentStorage storage;
    @Autowired private javax.sql.DataSource dataSource;
    @SpyBean private ImportMetadataAuthorizationService metadataAuthorization;
    private final ThreadLocal<AtomicReference<Long>> actualAuthorizationConnection = new ThreadLocal<>();
    private final ThreadLocal<AtomicReference<String>> actualAuthorizationIsolation = new ThreadLocal<>();

    private String tenantId;
    private long actorUserId;
    private long groupId;
    private long foreignGroupId;
    private long denyGroupId;
    private long sourceId;
    private long mappingId;
    private long jobId;
    private long documentId;
    private long documentVersionId;
    private String storageKey;
    private SysUser actorUser;

    @BeforeEach
    void prepareAuthorizedFixture() {
        if (SOURCE_WAIT_FIXTURE_UNSAFE.get()) {
            throw new AssertionError("source lock workerの終了を確認できなかったため、以降のfixtureを安全に開始できません");
        }
        storage.resetOpenCount();
        tenantId = TEST_TENANT;
        AccountingTenantContextHolder.setTenantId(tenantId);
        String testUsername = "nf06-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        actorUser = SysUser.builder().username(testUsername)
                .password("test-only").realName("取込テスト管理者").role("管理者").status(1).tenantId(tenantId).build();
        sysUserMapper.insert(actorUser);
        actorUserId = actorUser.getId();

        jdbc.update("""
                INSERT INTO m_organization_unit(legal_entity_id, code, name, type, valid_from, status, deleted_flag)
                VALUES(?, ?, ?, '法人', ?, '有効', 0)
                """, LEGAL_ENTITY_ID, "NF06-" + tenantId, "取込テスト法人", LocalDate.of(2020, 1, 1));
        jdbc.update("""
                INSERT INTO m_permission_group(tenant_id, group_key, group_name, enabled, deleted_flag)
                VALUES(?, ?, ?, 1, 0)
                """, tenantId, "nf06-import-" + tenantId, "取込metadataテスト");
        groupId = jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id=? AND group_key=?",
                Long.class, tenantId, "nf06-import-" + tenantId);
        jdbc.update("""
                INSERT INTO t_user_permission_group(tenant_id, user_id, group_id, assigned_by, deleted_flag)
                VALUES(?, ?, ?, ?, 0)
                """, tenantId, actorUserId, groupId, actorUserId);
        jdbc.update("""
                INSERT INTO t_permission_group_action(tenant_id, group_id, action_key, deny_flag, deleted_flag)
                VALUES(?, ?, ?, 0, 0)
                """, tenantId, groupId, ImportMetadataAuthorizationService.ACTION);
        var authority = new SimpleGrantedAuthority("ROLE_管理者");
        var principal = new LoginUser(actorUser, List.of(authority), tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "test-only", List.of(authority)));

        Document document = new Document();
        document.setTenantId(tenantId);
        document.setLegalEntityId(Long.toString(LEGAL_ENTITY_ID));
        document.setDocumentType("IMPORT_SOURCE");
        document.setTitle("合成取込source");
        document.setDirection("INTERNAL");
        document.setStatus("CONFIRMED");
        document.setCurrency("JPY");
        document.setRetentionUntil(LocalDate.now().plusYears(1));
        document.setLegalHoldFlag(0);
        document.setVersion(1L);
        document.setCreatedBy(actorUserId);
        documentMapper.insert(document);
        documentId = document.getId();

        storageKey = "nf06-test/" + UUID.randomUUID();
        DocumentVersion version = new DocumentVersion();
        version.setTenantId(tenantId);
        version.setDocumentId(documentId);
        version.setVersionNo(1);
        version.setStorageKey(storageKey);
        version.setOriginalName("synthetic.csv");
        version.setContentType("text/csv; charset=UTF-8");
        version.setSizeBytes((long) SOURCE.length);
        version.setSha256(SOURCE_HASH);
        version.setSourceType("RECEIVED");
        version.setBusinessKey("nf06-source-" + tenantId);
        version.setVersionDiscriminator("v1");
        version.setScanStatus("CLEAN");
        version.setCreatedBy(actorUserId);
        documentVersionMapper.insert(version);
        documentVersionId = version.getId();
        storage.put(storageKey, SOURCE, false);
        storage.resetOpenCount();
    }

    @AfterEach
    void cleanupFixture() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
        if (SOURCE_WAIT_FIXTURE_UNSAFE.get()) return;
        if (tenantId == null) return;
        if (documentId > 0) {
            jdbc.update("DELETE FROM t_document_access_log WHERE document_id=?", documentId);
            jdbc.update("DELETE FROM t_document_link WHERE tenant_id=? AND document_id=?", tenantId, documentId);
            if (sourceId > 0 && documentVersionId > 0) {
                String fixtureJobs = "SELECT id FROM t_import_job WHERE tenant_id=? AND source_system_id=? "
                        + "AND document_id=? AND document_version_id=?";
                Object[] fixtureScope = {tenantId, sourceId, documentId, documentVersionId};
                jdbc.update("DELETE FROM t_import_identity_registry WHERE tenant_id=? AND first_job_id IN (" + fixtureJobs + ")",
                        tenantId, fixtureScope[0], fixtureScope[1], fixtureScope[2], fixtureScope[3]);
                jdbc.update("DELETE FROM t_import_validation_row WHERE tenant_id=? AND job_id IN (" + fixtureJobs + ")",
                        tenantId, fixtureScope[0], fixtureScope[1], fixtureScope[2], fixtureScope[3]);
                jdbc.update("UPDATE t_import_job SET active_validation_run_id=NULL,current_validation_run_id=NULL "
                                + "WHERE tenant_id=? AND source_system_id=? AND document_id=? AND document_version_id=?",
                        fixtureScope);
                jdbc.update("DELETE FROM t_import_validation_run WHERE tenant_id=? AND job_id IN (" + fixtureJobs + ")",
                        tenantId, fixtureScope[0], fixtureScope[1], fixtureScope[2], fixtureScope[3]);
                jdbc.update("DELETE FROM t_import_job WHERE tenant_id=? AND source_system_id=? "
                                + "AND document_id=? AND document_version_id=?",
                        fixtureScope);
            }
            jdbc.update("DELETE FROM t_document_version WHERE tenant_id=? AND id=?", tenantId, documentVersionId);
            jdbc.update("DELETE FROM t_document WHERE tenant_id=? AND id=?", tenantId, documentId);
        }
        if (mappingId > 0) jdbc.update("DELETE FROM t_import_mapping WHERE tenant_id=? AND id=?", tenantId, mappingId);
        if (sourceId > 0) jdbc.update("DELETE FROM t_import_source_system WHERE tenant_id=? AND id=?", tenantId, sourceId);
        if (groupId > 0) {
            jdbc.update("DELETE FROM t_permission_group_action WHERE tenant_id=? AND group_id=?", tenantId, groupId);
            jdbc.update("DELETE FROM t_user_permission_group WHERE tenant_id=? AND group_id=?", tenantId, groupId);
            jdbc.update("DELETE FROM m_permission_group WHERE tenant_id=? AND id=?", tenantId, groupId);
        }
        if (foreignGroupId > 0) {
            jdbc.update("DELETE FROM t_user_permission_group WHERE tenant_id=? AND group_id=?", tenantId, foreignGroupId);
            jdbc.update("DELETE FROM m_permission_group WHERE tenant_id=? AND id=?", tenantId + "-foreign", foreignGroupId);
        }
        if (denyGroupId > 0) {
            jdbc.update("DELETE FROM t_user_permission_group WHERE tenant_id=? AND group_id=?", tenantId, denyGroupId);
            jdbc.update("DELETE FROM t_permission_group_action WHERE tenant_id=? AND group_id=?", tenantId, denyGroupId);
            jdbc.update("DELETE FROM m_permission_group WHERE tenant_id=? AND id=?", tenantId, denyGroupId);
        }
        if (actorUserId > 0) jdbc.update("DELETE FROM sys_user WHERE id=?", actorUserId);
        jdbc.update("DELETE FROM m_organization_unit WHERE legal_entity_id=? AND code=?", LEGAL_ENTITY_ID,
                "NF06-" + tenantId);
        if (storageKey != null) storage.delete(storageKey);
    }

    @Test
    void 実metadataとdocument境界でsource登録からdownload監査まで成功する() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        mappingId = metadataService.createMapping(new ImportMappingRequest(sourceId, ImportEntity.CUSTOMER,
                "ses-import-v1", Map.of("externalKey", "externalKey", "companyName", "companyName")));
        jobId = metadataService.registerJob(new ImportJobRegistrationRequest(sourceId, ImportEntity.CUSTOMER,
                "ses-import-v1", ImportEncoding.UTF8, SOURCE_HASH, documentId, documentVersionId, 1));

        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_document_link
                WHERE tenant_id=? AND document_id=? AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0
                """, Integer.class, tenantId, documentId, jobId));
        assertEquals(2L, metadataService.bindMapping(jobId, 1L, mappingId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_import_job j JOIN t_import_mapping m
                  ON m.tenant_id=j.tenant_id AND m.id=j.mapping_id
                WHERE j.tenant_id=? AND j.id=? AND j.state='MAPPED' AND j.mapping_id=?
                  AND j.source_system_id=? AND j.source_version=1 AND j.entity_type='CUSTOMER'
                  AND j.schema_version='ses-import-v1' AND j.source_encoding='UTF8'
                  AND j.source_sha256=UNHEX(?) AND j.document_id=? AND j.document_version_id=?
                  AND j.document_version_no=1 AND j.document_sha256=UNHEX(?)
                  AND j.mapping_version=m.mapping_version AND j.mapping_sha256=m.mapping_sha256
                """, Integer.class, tenantId, jobId, mappingId, sourceId, SOURCE_HASH, documentId,
                documentVersionId, SOURCE_HASH));

        byte[] read;
        try (ImportSourceContent content = metadataService.openSource(jobId)) {
            assertEquals(ImportEntity.CUSTOMER, content.entity());
            assertEquals(ImportEncoding.UTF8, content.encoding());
            assertEquals(SOURCE_HASH, content.sha256Hex());
            read = content.stream().readAllBytes();
        }
        assertArrayEquals(SOURCE, read);
        assertEquals(1, storage.openCount());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_document_access_log
                WHERE document_id=? AND version_id=? AND user_id=? AND action='DOWNLOAD'
                """, Integer.class, documentId, documentVersionId, actorUserId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_document d JOIN t_document_version v
                  ON v.tenant_id=d.tenant_id AND v.document_id=d.id
                WHERE d.tenant_id=? AND d.id=? AND d.legal_entity_id=? AND d.legal_hold_flag=0
                  AND (d.retention_until IS NULL OR d.retention_until>=CURRENT_DATE())
                  AND v.id=? AND v.scan_status='CLEAN' AND v.sha256=?
                """, Integer.class, tenantId, documentId, Long.toString(LEGAL_ENTITY_ID), documentVersionId,
                SOURCE_HASH));
    }

    @Test
    void 実MySQLでstartはleaseとBUILDINGrunを原子的に作りabortでMAPPEDへ戻す() {
        prepareBoundCustomerJob();
        var operation = lifecycleService.beginStart(jobId);
        var lease = lifecycleService.start(operation);
        assertEquals(lease.runId(), lifecycleService.start(operation).runId(), "同operation retryは同一runへ収束します");
        Map<String, Object> started = leaseSnapshot();
        assertEquals("VALIDATING", started.get("state"));
        assertEquals(lease.generation(), ((Number) started.get("lease_generation")).longValue());
        assertEquals(lease.runId(), ((Number) started.get("active_validation_run_id")).longValue());
        assertEquals(null, started.get("current_validation_run_id"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=? AND state='BUILDING' AND created_lease_generation=? AND last_resumed_lease_generation=? AND active_slot=1 AND published_slot IS NULL AND actor_user_id=? AND source_sha256=UNHEX(?) AND document_sha256=UNHEX(?) AND mapping_sha256=?",
                Integer.class, tenantId, jobId, lease.runId(), lease.generation(), lease.generation(), actorUserId,
                SOURCE_HASH, SOURCE_HASH, jdbc.queryForObject("SELECT mapping_sha256 FROM t_import_mapping WHERE tenant_id=? AND id=?",
                        byte[].class, tenantId, mappingId)));
        assertEquals(0, storage.openCount(), "startはsource streamを開きません");

        lifecycleService.abort(lease);
        Map<String, Object> aborted = leaseSnapshot();
        assertEquals("MAPPED", aborted.get("state"));
        assertEquals(null, aborted.get("lease_owner"));
        assertEquals(null, aborted.get("lease_expires_at"));
        assertEquals(null, aborted.get("active_validation_run_id"));
        assertEquals(null, aborted.get("current_validation_run_id"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=? AND state='ABORTED' AND active_slot IS NULL AND published_slot IS NULL",
                Integer.class, tenantId, jobId, lease.runId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=?",
                Integer.class, tenantId, jobId));
    }

    @Test
    void 実MySQLの再validationstartは旧completed結果を履歴保持しcurrentだけを解除する() {
        long oldRunId = prepareValidatedCustomerJob();
        var operation = lifecycleService.beginStart(jobId);
        var lease = lifecycleService.start(operation);
        Map<String, Object> started = leaseSnapshot();
        assertEquals("VALIDATING", started.get("state"));
        assertEquals(null, started.get("current_validation_run_id"));
        assertEquals(lease.runId(), ((Number) started.get("active_validation_run_id")).longValue());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=? AND state='COMPLETED' AND active_slot IS NULL AND published_slot IS NULL",
                Integer.class, tenantId, jobId, oldRunId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=? AND state='BUILDING' AND active_slot=1 AND published_slot IS NULL",
                Integer.class, tenantId, jobId, lease.runId()));
        lifecycleService.abort(lease);
    }

    @Test
    void 実MySQLtakeoverの期限はDB値と一致しrenew後にabortできる() {
        prepareBoundCustomerJob();
        var initial = lifecycleService.start(lifecycleService.beginStart(jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?",
                tenantId, jobId);
        var takeover = lifecycleService.takeOver(lifecycleService.beginTakeover(jobId, initial.runId()));
        Timestamp storedTakeoverExpiry = jdbc.queryForObject("SELECT lease_expires_at FROM t_import_job WHERE tenant_id=? AND id=?",
                Timestamp.class, tenantId, jobId);
        assertEquals(storedTakeoverExpiry, takeover.expiresAt(), "grant期限はMySQLが保存した実値と一致します");
        var renewed = lifecycleService.renew(takeover);
        Timestamp storedRenewalExpiry = jdbc.queryForObject("SELECT lease_expires_at FROM t_import_job WHERE tenant_id=? AND id=?",
                Timestamp.class, tenantId, jobId);
        assertEquals(storedRenewalExpiry, renewed.expiresAt());
        lifecycleService.abort(renewed);
        Map<String, Object> aborted = leaseSnapshot();
        assertEquals("MAPPED", aborted.get("state"));
        assertEquals("ABORTED", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, tenantId, jobId, initial.runId()));
    }

    @Test
    void 実MySQL同tick相当renewは将来期限を維持し旧grantretryで再更新しない() throws Exception {
        prepareBoundCustomerJob();
        var initial = lifecycleService.start(lifecycleService.beginStart(jobId));
        Timestamp futureExpiry = jdbc.queryForObject("SELECT TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6))",
                Timestamp.class);
        jdbc.update("UPDATE t_import_job SET lease_expires_at=? WHERE tenant_id=? AND id=?",
                futureExpiry, tenantId, jobId);
        var originalGrant = reflectedLease(initial, futureExpiry);
        long beforeVersion = ((Number) leaseSnapshot().get("version_no")).longValue();

        var renewed = lifecycleService.renew(originalGrant);
        Map<String, Object> afterRenew = leaseSnapshot();
        assertEquals(futureExpiry, jdbc.queryForObject(
                        "SELECT lease_expires_at FROM t_import_job WHERE tenant_id=? AND id=?",
                        Timestamp.class, tenantId, jobId),
                "実MySQL GREATEST SQLは候補期限が古い場合に既存期限を維持します");
        assertEquals(beforeVersion + 1, ((Number) afterRenew.get("version_no")).longValue());
        assertEquals(futureExpiry, renewed.expiresAt());

        var recovered = lifecycleService.renew(originalGrant);
        assertEquals(futureExpiry, recovered.expiresAt(), "expiryが同値のcommitも旧grantで復旧できます");
        assertEquals(afterRenew, leaseSnapshot(), "retryはversionも期限も再更新しません");
        lifecycleService.abort(recovered);
    }

    @Test
    void 実MySQLappendはBUILDING行を保存し同一source再検証でnoOp再送する() {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));

        var appendedLease = validationAppendService.appendRemaining(lease);
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_import_validation_row
                WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=1
                """, Integer.class, tenantId, jobId, lease.runId()));
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256
                FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=1
                """, tenantId, jobId, lease.runId());
        assertArrayEquals(HexFormat.of().parseHex(candidateRowHash(List.of("customer-1", "Example"))),
                (byte[]) row.get("source_row_sha256"));
        assertEquals("ACCEPTED", row.get("disposition"));
        assertTrue(((byte[]) row.get("finding_codes")).length <= 4096);
        assertEquals(32, ((byte[]) row.get("canonical_fingerprint")).length);
        assertEquals(32, ((byte[]) row.get("row_commit_sha256")).length);
        assertEquals("BUILDING", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, tenantId, jobId, lease.runId()), "appendはseal/publishしません");
        assertEquals("VALIDATING", leaseSnapshot().get("state"));

        validationAppendService.appendRemaining(appendedLease);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()), "一致するprefixの再検証は重複rowを追加しません");
    }

    @Test
    void 実MySQLappendは250件batchとEOFtailを保持する() {
        prepareBoundCustomerJob();
        StringBuilder csv = new StringBuilder("externalKey,companyName\n");
        for (int row = 1; row <= 251; row++) csv.append(row == 251 ? "customer-1" : "customer-" + row)
                .append(",Example ").append(row).append('\n');
        byte[] source = csv.toString().getBytes(StandardCharsets.UTF_8);
        String sourceHash = sha256(source);
        storage.put(storageKey, source, false);
        jdbc.update("UPDATE t_document_version SET sha256=?,size_bytes=? WHERE tenant_id=? AND document_id=? AND id=?",
                sourceHash, (long) source.length, tenantId, documentId, documentVersionId);
        byte[] digest = HexFormat.of().parseHex(sourceHash);
        jdbc.update("UPDATE t_import_job SET source_sha256=?,document_sha256=? WHERE tenant_id=? AND id=?",
                digest, digest, tenantId, jobId);
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));

        var appended = validationAppendService.appendRemaining(lease);
        assertEquals(251, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()));
        Map<String, Object> bounds = jdbc.queryForMap("SELECT MIN(source_row_number) AS first_row,MAX(source_row_number) AS last_row "
                + "FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                tenantId, jobId, lease.runId());
        assertEquals(1L, ((Number) bounds.get("first_row")).longValue());
        assertEquals(251L, ((Number) bounds.get("last_row")).longValue());
        assertEquals(250, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND disposition='ACCEPTED'",
                Integer.class, tenantId, jobId, lease.runId()));
        assertEquals("REJECTED", jdbc.queryForObject("SELECT disposition FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=251",
                String.class, tenantId, jobId, lease.runId()), "duplicate stateはchunk境界をまたいで再構築します");
        assertEquals("BUILDING", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, tenantId, jobId, lease.runId()));
        validationAppendService.appendRemaining(appended);
        assertEquals(251, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()), "250件を超えるprefixのno-op retryが全行一致する");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 249, 250, 251})
    void 実MySQLappendは空とchunk境界の件数を正確に保存する(int rowCount) {
        prepareBoundCustomerJob();
        StringBuilder csv = new StringBuilder("externalKey,companyName\n");
        for (int row = 1; row <= rowCount; row++) csv.append("edge-").append(row).append(",Example ").append(row).append('\n');
        byte[] source = csv.toString().getBytes(StandardCharsets.UTF_8);
        String sourceHash = sha256(source);
        storage.put(storageKey, source, false);
        jdbc.update("UPDATE t_document_version SET sha256=?,size_bytes=? WHERE tenant_id=? AND document_id=? AND id=?",
                sourceHash, (long) source.length, tenantId, documentId, documentVersionId);
        byte[] digest = HexFormat.of().parseHex(sourceHash);
        jdbc.update("UPDATE t_import_job SET source_sha256=?,document_sha256=? WHERE tenant_id=? AND id=?",
                digest, digest, tenantId, jobId);
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        var completion = validationAppendService.appendRemainingWithSealCapability(lease);
        var capability = completion.capability();
        assertEquals(rowCount, capability.counters().sourceRows());
        assertEquals(0, capability.counters().emptySkipped());
        assertEquals(completion.lease().expiresAt(), capability.expiresAt());
        assertEquals(completion.lease().generation(), capability.generation());
        assertEquals(completion.lease().jobVersion(), capability.jobVersion());
        assertTrue(completion.lease() == capability.lease(), "capabilityは最終renewのleaseそのものに束縛されます");
        Timestamp exposedExpiry = capability.expiresAt();
        exposedExpiry.setTime(0);
        assertTrue(!Timestamp.valueOf("1970-01-01 00:00:00").equals(capability.expiresAt()), "expiryは取得時に複製されます");
        byte[] exposedDocumentSha = capability.documentSha();
        exposedDocumentSha[0] ^= 1;
        assertTrue(!java.util.Arrays.equals(exposedDocumentSha, capability.documentSha()), "証明のbyte列は取得時に複製されます");
        assertTrue(java.lang.reflect.Modifier.isPrivate(ImportValidationAppendService.SealCapability.class
                .getDeclaredConstructors()[0].getModifiers()), "seal capabilityはprivate constructorからのみ発行します");
        assertEquals(rowCount, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()));
        assertEquals(rowSetShaForStoredRows(lease.runId()), capability.rowSetSha256(), "stored prefix全体のordered commitmentを一致させます");
        var retried = validationAppendService.appendRemainingWithSealCapability(completion.lease());
        assertEquals(capability.rowSetSha256(), retried.capability().rowSetSha256(), "同じsource/prefixのretryはordered row-set証明へ収束します");
        assertEquals(capability.counters(), retried.capability().counters());
        assertEquals("BUILDING", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, tenantId, jobId, lease.runId()));
    }

    @Test
    void 実MySQLappendはblankPhysicalRowによるrowNumberGapを許可する() {
        prepareBoundCustomerJob();
        byte[] source = "externalKey,companyName\ncustomer-1,Example 1\n\ncustomer-2,Example 2\n"
                .getBytes(StandardCharsets.UTF_8);
        String sourceHash = sha256(source);
        storage.put(storageKey, source, false);
        jdbc.update("UPDATE t_document_version SET sha256=?,size_bytes=? WHERE tenant_id=? AND document_id=? AND id=?",
                sourceHash, (long) source.length, tenantId, documentId, documentVersionId);
        byte[] digest = HexFormat.of().parseHex(sourceHash);
        jdbc.update("UPDATE t_import_job SET source_sha256=?,document_sha256=? WHERE tenant_id=? AND id=?",
                digest, digest, tenantId, jobId);
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));

        var capability = validationAppendService.appendRemainingWithSealCapability(lease).capability();
        assertEquals(1, capability.counters().emptySkipped());
        assertEquals(3, capability.counters().physicalRows());
        assertEquals(2, capability.counters().sourceRows());
        assertEquals(List.of(1L, 3L), jdbc.query("SELECT source_row_number FROM t_import_validation_row "
                        + "WHERE tenant_id=? AND job_id=? AND validation_run_id=? ORDER BY source_row_number",
                (rs, row) -> rs.getLong(1), tenantId, jobId, lease.runId()));
        assertEquals("BUILDING", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, tenantId, jobId, lease.runId()));
    }

    @Test
    void 実MySQLappendはheader後blank行のみでもEOF証明を発行する() {
        prepareBoundCustomerJob();
        byte[] source = "externalKey,companyName\n\n\n".getBytes(StandardCharsets.UTF_8);
        String sourceHash = sha256(source);
        storage.put(storageKey, source, false);
        jdbc.update("UPDATE t_document_version SET sha256=?,size_bytes=? WHERE tenant_id=? AND document_id=? AND id=?",
                sourceHash, (long) source.length, tenantId, documentId, documentVersionId);
        byte[] digest = HexFormat.of().parseHex(sourceHash);
        jdbc.update("UPDATE t_import_job SET source_sha256=?,document_sha256=? WHERE tenant_id=? AND id=?",
                digest, digest, tenantId, jobId);
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));

        var capability = validationAppendService.appendRemainingWithSealCapability(lease).capability();
        assertEquals(0, capability.counters().sourceRows());
        assertEquals(2, capability.counters().physicalRows());
        assertEquals(2, capability.counters().emptySkipped());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()));
    }

    @Test
    void 実MySQLappendはheaderのない空sourceにEOF証明を発行しない() {
        prepareBoundCustomerJob();
        byte[] source = new byte[0];
        String sourceHash = sha256(source);
        storage.put(storageKey, source, false);
        jdbc.update("UPDATE t_document_version SET sha256=?,size_bytes=? WHERE tenant_id=? AND document_id=? AND id=?",
                sourceHash, 0L, tenantId, documentId, documentVersionId);
        byte[] digest = HexFormat.of().parseHex(sourceHash);
        jdbc.update("UPDATE t_import_job SET source_sha256=?,document_sha256=? WHERE tenant_id=? AND id=?",
                digest, digest, tenantId, jobId);
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));

        assertThrows(BusinessException.class, () -> validationAppendService.appendRemainingWithSealCapability(lease));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()));
    }

    @Test
    void 実MySQLappendのexactSourceHash不一致はrowをwriteしない() {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        storage.put(storageKey, "externalKey,companyName\ncustomer-other,Example\n".getBytes(StandardCharsets.UTF_8), false);

        assertThrows(BusinessException.class, () -> validationAppendService.appendRemainingWithSealCapability(lease));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()));
        assertEquals("BUILDING", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, tenantId, jobId, lease.runId()));
    }

    @Test
    void append前sourceVersionDriftはvalidation行書込なし() {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        jdbc.update("UPDATE t_import_source_system SET version_no=version_no+1 WHERE tenant_id=? AND id=?", tenantId, sourceId);
        assertAppendDriftRejected(lease);
    }

    @Test
    void append前documentSnapshotDriftはvalidation行書込なし() {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        jdbc.update("UPDATE t_document_version SET sha256=? WHERE tenant_id=? AND document_id=? AND id=?",
                new byte[32], tenantId, documentId, documentVersionId);
        assertAppendDriftRejected(lease);
    }

    @Test
    void append前documentLinkDriftはvalidation行書込なし() {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        jdbc.update("DELETE FROM t_document_link WHERE tenant_id=? AND document_id=? AND target_type='IMPORT_JOB' AND target_id=?",
                tenantId, documentId, jobId);
        assertAppendDriftRejected(lease);
    }

    @Test
    void append前mappingFingerprintDriftはvalidation行書込なし() {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        jdbc.update("UPDATE t_import_mapping SET canonical_bytes=? WHERE tenant_id=? AND id=?",
                "{}".getBytes(StandardCharsets.UTF_8), tenantId, mappingId);
        assertAppendDriftRejected(lease);
    }

    @Test
    void append前permissionPolicyDriftは403でvalidation行書込なし() {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        jdbc.update("UPDATE t_permission_group_action SET deny_flag=1 WHERE tenant_id=? AND group_id=? AND action_key=? AND deny_flag=0",
                tenantId, groupId, ImportMetadataAuthorizationService.ACTION);
        BusinessException denied = assertThrows(BusinessException.class, () -> validationAppendService.appendRemaining(lease));
        assertEquals(403, denied.getCode());
        assertNoAppendRows(lease);
    }

    private void assertAppendDriftRejected(ImportValidationLifecycleService.RunLease lease) {
        assertThrows(BusinessException.class, () -> validationAppendService.appendRemaining(lease));
        assertNoAppendRows(lease);
    }

    private void assertNoAppendRows(ImportValidationLifecycleService.RunLease lease) {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()));
        assertEquals("BUILDING", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, tenantId, jobId, lease.runId()));
    }

    @Test
    void 実MySQLwrite再parse中のEOF前read失敗はcommit済prefixを保持しtailを書かない() {
        prepareBoundCustomerJob();
        StringBuilder csv = new StringBuilder("externalKey,companyName\n");
        for (int row = 1; row <= 251; row++) csv.append("customer-").append(row)
                .append(",Example ").append(row).append('\n');
        byte[] source = csv.toString().getBytes(StandardCharsets.UTF_8);
        String sourceHash = sha256(source);
        storage.put(storageKey, source, false);
        jdbc.update("UPDATE t_document_version SET sha256=?,size_bytes=? WHERE tenant_id=? AND document_id=? AND id=?",
                sourceHash, (long) source.length, tenantId, documentId, documentVersionId);
        byte[] digest = HexFormat.of().parseHex(sourceHash);
        jdbc.update("UPDATE t_import_job SET source_sha256=?,document_sha256=? WHERE tenant_id=? AND id=?",
                digest, digest, tenantId, jobId);
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        storage.resetOpenCount();
        storage.failOpenAt(2, source.length - 1);

        assertThrows(BusinessException.class, () -> validationAppendService.appendRemaining(lease));
        assertEquals(250, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()), "失敗前にcommitした完全chunkだけを保持します");
        assertEquals("BUILDING", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, tenantId, jobId, lease.runId()), "EOF未確認ではseal/publishしません");

        validationAppendService.appendRemaining(lease);
        assertEquals(251, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()), "再parse後はtailを一度だけappendします");
        assertEquals("BUILDING", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, tenantId, jobId, lease.runId()));
    }

    @Test
    void 実MySQLwrite再parseのEOF時SHA差分はfullChunkだけ残しtailをcommitしない() {
        prepareBoundCustomerJob();
        StringBuilder csv = new StringBuilder("externalKey,companyName\n");
        for (int row = 1; row <= 251; row++) csv.append("tail-").append(row).append(",Example ").append(row).append('\n');
        byte[] source = csv.toString().getBytes(StandardCharsets.UTF_8);
        String sourceHash = sha256(source);
        storage.put(storageKey, source, false);
        jdbc.update("UPDATE t_document_version SET sha256=?,size_bytes=? WHERE tenant_id=? AND document_id=? AND id=?",
                sourceHash, (long) source.length, tenantId, documentId, documentVersionId);
        byte[] digest = HexFormat.of().parseHex(sourceHash);
        jdbc.update("UPDATE t_import_job SET source_sha256=?,document_sha256=? WHERE tenant_id=? AND id=?",
                digest, digest, tenantId, jobId);
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        storage.resetOpenCount();
        storage.changeAtOpen(2, source.length - 2, (byte) '2');

        assertThrows(BusinessException.class, () -> validationAppendService.appendRemaining(lease));
        assertEquals(250, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()), "EOF SHA不一致でも先行commit済みfull chunkは保持します");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=251",
                Integer.class, tenantId, jobId, lease.runId()), "tail rowはSHA一致前に書きません");
        assertEquals("BUILDING", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, tenantId, jobId, lease.runId()));
    }

    @Test
    void 実MySQLchunk途中のSQL失敗はbatch全体をrollbackしBUILDINGを保持する() {
        prepareBoundCustomerJob();
        byte[] source = "externalKey,companyName\ncustomer-1,Example 1\ncustomer-2,Example 2\n"
                .getBytes(StandardCharsets.UTF_8);
        String sourceHash = sha256(source);
        storage.put(storageKey, source, false);
        jdbc.update("UPDATE t_document_version SET sha256=?,size_bytes=? WHERE tenant_id=? AND document_id=? AND id=?",
                sourceHash, (long) source.length, tenantId, documentId, documentVersionId);
        byte[] digest = HexFormat.of().parseHex(sourceHash);
        jdbc.update("UPDATE t_import_job SET source_sha256=?,document_sha256=? WHERE tenant_id=? AND id=?",
                digest, digest, tenantId, jobId);
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        String trigger = "trg_nf06_append_fail_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON t_import_validation_row "
                + "FOR EACH ROW BEGIN IF NEW.source_row_number=2 THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic append failure'; END IF; END");
        try {
            assertThrows(RuntimeException.class, () -> validationAppendService.appendRemaining(lease));
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()), "chunk内SQL失敗は全rowをrollbackします");
        assertEquals("BUILDING", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, tenantId, jobId, lease.runId()));
        assertEquals("VALIDATING", leaseSnapshot().get("state"));
    }

    @Test
    void 実MySQLrenew先行で旧grantappendを拒否し最新grant再送は同一metadataへ収束する() throws Exception {
        prepareBoundCustomerJob();
        var oldGrant = lifecycleService.start(lifecycleService.beginStart(jobId));
        var appendedGrant = validationAppendService.appendRemaining(oldGrant);
        Map<String, Object> row = jdbc.queryForMap("SELECT source_row_number,source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256 "
                + "FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                tenantId, jobId, oldGrant.runId());
        var metadata = new ImportValidationAppendService.RowMetadata(((Number) row.get("source_row_number")).longValue(),
                (byte[]) row.get("source_row_sha256"), (byte[]) row.get("canonical_fingerprint"),
                (String) row.get("disposition"), (byte[]) row.get("finding_codes"), (byte[]) row.get("row_commit_sha256"));
        Timestamp futureExpiry = jdbc.queryForObject("SELECT TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6))", Timestamp.class);
        jdbc.update("UPDATE t_import_job SET lease_expires_at=? WHERE tenant_id=? AND id=?", futureExpiry, tenantId, jobId);
        var matchingOldGrant = reflectedLease(appendedGrant, futureExpiry);
        var chunk = reflectedChunk(matchingOldGrant, List.of(metadata));
        long versionBeforeRenew = ((Number) leaseSnapshot().get("version_no")).longValue();
        var latestGrant = lifecycleService.renew(matchingOldGrant);
        assertEquals(futureExpiry, latestGrant.expiresAt(), "候補期限が古い場合も将来期限を維持します");
        assertEquals(versionBeforeRenew + 1, ((Number) leaseSnapshot().get("version_no")).longValue(),
                "renewはexpiry同値の場合もversionを厳密に+1します");
        Map<String, Object> beforeStaleReplay = leaseSnapshot();
        Map<String, Object> rowBeforeStaleReplay = jdbc.queryForMap("SELECT source_row_number,HEX(source_row_sha256) AS source_row_sha256,HEX(canonical_fingerprint) AS canonical_fingerprint,disposition,HEX(finding_codes) AS finding_codes,HEX(row_commit_sha256) AS row_commit_sha256 "
                + "FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                tenantId, jobId, oldGrant.runId());
        BusinessException stale = assertThrows(BusinessException.class,
                () -> lifecycleService.appendChunk(matchingOldGrant, chunk));
        assertEquals(409, stale.getCode());
        assertEquals(beforeStaleReplay, leaseSnapshot());
        assertEquals(rowBeforeStaleReplay, jdbc.queryForMap("SELECT source_row_number,HEX(source_row_sha256) AS source_row_sha256,HEX(canonical_fingerprint) AS canonical_fingerprint,disposition,HEX(finding_codes) AS finding_codes,HEX(row_commit_sha256) AS row_commit_sha256 "
                + "FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                tenantId, jobId, oldGrant.runId()), "旧grant拒否後のrow metadataは不変です");
        lifecycleService.appendChunk(latestGrant, reflectedChunk(latestGrant, List.of(metadata)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, oldGrant.runId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT HEX(row_commit_sha256)) FROM t_import_validation_row "
                + "WHERE tenant_id=? AND job_id=? AND validation_run_id=?", Integer.class, tenantId, jobId, oldGrant.runId()));
    }

    @Test
    void 実MySQLappendは再検証時のprefix差分を拒否しrowを追加しない() {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        validationAppendService.appendRemaining(lease);
        jdbc.update("UPDATE t_import_validation_row SET disposition='REJECTED' WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=1",
                tenantId, jobId, lease.runId());
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId());
        assertThrows(BusinessException.class, () -> validationAppendService.appendRemaining(lease));
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()));
        assertEquals("BUILDING", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, tenantId, jobId, lease.runId()));
    }

    @Test
    void 実MySQL同chunk再送は各metadata差分を409で拒否する() throws Exception {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        var appended = validationAppendService.appendRemaining(lease);
        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT source_row_number,source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256
                FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?
                """, tenantId, jobId, lease.runId());
        long rowNumber = ((Number) stored.get("source_row_number")).longValue();
        byte[] sourceHash = (byte[]) stored.get("source_row_sha256");
        byte[] fingerprint = (byte[]) stored.get("canonical_fingerprint");
        String disposition = (String) stored.get("disposition");
        byte[] findings = (byte[]) stored.get("finding_codes");
        byte[] commitment = (byte[]) stored.get("row_commit_sha256");
        List<ImportValidationAppendService.RowMetadata> variants = List.of(
                new ImportValidationAppendService.RowMetadata(rowNumber, changed(sourceHash), fingerprint,
                        disposition, findings, commitment),
                new ImportValidationAppendService.RowMetadata(rowNumber, sourceHash, changed(fingerprint),
                        disposition, findings, commitment),
                new ImportValidationAppendService.RowMetadata(rowNumber, sourceHash, fingerprint,
                        "REJECTED", findings, commitment),
                new ImportValidationAppendService.RowMetadata(rowNumber, sourceHash, fingerprint,
                        disposition, changed(findings), commitment),
                new ImportValidationAppendService.RowMetadata(rowNumber, sourceHash, fingerprint,
                        disposition, findings, changed(commitment)));
        Map<String, Object> beforeLease = leaseSnapshot();
        for (var variant : variants) {
            var chunk = reflectedChunk(appended, List.of(variant));
            assertThrows(BusinessException.class, () -> lifecycleService.appendChunk(appended, chunk));
        }
        assertEquals(beforeLease, leaseSnapshot());
        Map<String, Object> after = jdbc.queryForMap("""
                SELECT source_row_number,source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256
                FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?
                """, tenantId, jobId, lease.runId());
        assertEquals(stored.get("source_row_number"), after.get("source_row_number"));
        assertEquals(stored.get("disposition"), after.get("disposition"));
        assertArrayEquals(sourceHash, (byte[]) after.get("source_row_sha256"));
        assertArrayEquals(fingerprint, (byte[]) after.get("canonical_fingerprint"));
        assertArrayEquals(findings, (byte[]) after.get("finding_codes"));
        assertArrayEquals(commitment, (byte[]) after.get("row_commit_sha256"));
    }

    @Test
    void appendChunkは空超過重複逆順と未知findingを拒否する() throws Exception {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        var sourceRow = new ImportValidationAppendService.RowMetadata(1, new byte[32], new byte[32],
                "ACCEPTED", ImportValidationAppendService.encodeFindingCodes(Set.of()), new byte[32]);
        assertThrows(BusinessException.class, () -> ImportValidationAppendService.encodeFindingCodes(Set.of("UNKNOWN")));
        assertThrows(BusinessException.class, () -> lifecycleService.appendChunk(lease,
                reflectedChunk(lease, List.of())));
        assertThrows(BusinessException.class, () -> lifecycleService.appendChunk(lease,
                reflectedChunk(lease, List.of(sourceRow, sourceRow))));
        var second = new ImportValidationAppendService.RowMetadata(2, new byte[32], new byte[32],
                "ACCEPTED", ImportValidationAppendService.encodeFindingCodes(Set.of()), new byte[32]);
        assertThrows(BusinessException.class, () -> lifecycleService.appendChunk(lease,
                reflectedChunk(lease, List.of(second, sourceRow))));
        List<ImportValidationAppendService.RowMetadata> oversized = new java.util.ArrayList<>();
        for (int row = 1; row <= 251; row++) oversized.add(new ImportValidationAppendService.RowMetadata(row,
                new byte[32], new byte[32], "ACCEPTED", ImportValidationAppendService.encodeFindingCodes(Set.of()), new byte[32]));
        assertThrows(BusinessException.class, () -> lifecycleService.appendChunk(lease,
                reflectedChunk(lease, oversized)));
        var goodSnapshot = lifecycleService.appendSnapshot(lease);
        for (String field : List.of("sourceSha", "documentSha", "mappingSha", "validatorFingerprint")) {
            var changedSnapshot = reflectedSnapshot(goodSnapshot, field);
            assertThrows(BusinessException.class, () -> lifecycleService.appendChunk(lease,
                    reflectedChunk(lease, changedSnapshot, List.of(sourceRow))));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=?",
                Integer.class, tenantId, jobId));
    }

    @Test
    void 実MySQLrowLock待機が期限を越えるとfreshDB時刻で拒否しrow数を保つ() throws Exception {
        prepareBoundCustomerJob();
        var original = lifecycleService.start(lifecycleService.beginStart(jobId));
        var currentGrant = validationAppendService.appendRemaining(original);
        Map<String, Object> stored = jdbc.queryForMap("SELECT id,source_row_number,source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256 "
                + "FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                tenantId, jobId, original.runId());
        var row = new ImportValidationAppendService.RowMetadata(((Number) stored.get("source_row_number")).longValue(),
                (byte[]) stored.get("source_row_sha256"), (byte[]) stored.get("canonical_fingerprint"),
                (String) stored.get("disposition"), (byte[]) stored.get("finding_codes"), (byte[]) stored.get("row_commit_sha256"));
        long persistedRowId = ((Number) stored.get("id")).longValue();
        Map<String, Object> beforeShorten = leaseSnapshot();
        assertEquals(currentGrant.generation(), ((Number) beforeShorten.get("lease_generation")).longValue());
        assertEquals(leaseLongField(currentGrant, "jobVersion"), ((Number) beforeShorten.get("version_no")).longValue());
        assertEquals(currentGrant.runId(), ((Number) beforeShorten.get("active_validation_run_id")).longValue());
        assertEquals(leaseStringField(currentGrant, "workerId"), beforeShorten.get("lease_owner"));
        assertTrue(currentGrant.expiresAt().after(jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class)));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,2,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?",
                tenantId, jobId);
        Timestamp shortExpiry = jdbc.queryForObject("SELECT lease_expires_at FROM t_import_job WHERE tenant_id=? AND id=?",
                Timestamp.class, tenantId, jobId);
        assertTrue(shortExpiry.after(jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class)));
        var shortGrant = reflectedLease(currentGrant, shortExpiry);
        var lockedReplay = reflectedChunk(shortGrant, List.of(row));
        Connection blocker = dataSource.getConnection();
        blocker.setAutoCommit(false);
        AtomicReference<Long> blockerId = new AtomicReference<>();
        AtomicReference<Long> requesterId = new AtomicReference<>();
        AtomicReference<String> appendIsolation = new AtomicReference<>();
        captureActualAuthorizationConnection();
        AtomicReference<Map<String, Object>> stateAtRowWait = new AtomicReference<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> append = null;
        AtomicBoolean expectedAppendDenial = new AtomicBoolean(false);
        Throwable primaryFailure = null;
        try {
            try (var statement = blocker.prepareStatement("SELECT id FROM t_import_validation_row WHERE tenant_id=? AND validation_run_id=? AND source_row_number=? FOR UPDATE")) {
                statement.setString(1, tenantId); statement.setLong(2, original.runId()); statement.setLong(3, row.sourceRowNumber());
                try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); }
            }
            try (var statement = blocker.createStatement(); ResultSet result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(result.next()); blockerId.set(result.getLong(1));
            }
            append = pool.submit(() -> withCapturedAuthorizationConnection(requesterId, appendIsolation, () -> {
                var tx = new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
                tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
                return tx.execute(status -> {
                    assertEquals("READ-COMMITTED", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
                    stateAtRowWait.set(jdbc.query("SELECT lease_owner,lease_generation,version_no,active_validation_run_id,"
                                    + "lease_expires_at,lease_expires_at>CURRENT_TIMESTAMP(6) AS unexpired "
                                    + "FROM t_import_job WHERE tenant_id=? AND id=?",
                            rs -> {
                                if (!rs.next()) return null;
                                Map<String, Object> state = new java.util.HashMap<>();
                                state.put("lease_owner", rs.getString("lease_owner"));
                                state.put("lease_generation", rs.getObject("lease_generation"));
                                state.put("version_no", rs.getObject("version_no"));
                                state.put("active_validation_run_id", rs.getObject("active_validation_run_id"));
                                state.put("lease_expires_at", rs.getTimestamp("lease_expires_at"));
                                state.put("unexpired", rs.getObject("unexpired"));
                                return state;
                            }, tenantId, jobId));
                    lifecycleService.appendChunk(shortGrant, lockedReplay);
                    return null;
                });
            }));
            awaitValidationRowWait(requesterId, blockerId, append, row.sourceRowNumber(), original.runId(), persistedRowId);
            assertEquals("READ-COMMITTED", appendIsolation.get(), "REQUIRES_NEW append transaction isolation");
            Map<String, Object> leaseAtWait = stateAtRowWait.get();
            assertTrue(leaseAtWait != null);
            assertEquals(leaseStringField(shortGrant, "workerId"), leaseAtWait.get("lease_owner"));
            assertEquals(shortGrant.generation(), ((Number) leaseAtWait.get("lease_generation")).longValue());
            assertEquals(leaseLongField(shortGrant, "jobVersion"), ((Number) leaseAtWait.get("version_no")).longValue());
            assertEquals(shortGrant.runId(), ((Number) leaseAtWait.get("active_validation_run_id")).longValue());
            assertEquals(shortExpiry, leaseAtWait.get("lease_expires_at"));
            assertEquals(1, ((Number) leaseAtWait.get("unexpired")).intValue());
            awaitLeaseExpiryByDatabaseClock();
            blocker.commit();
            Future<?> pendingAppend = append;
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> pendingAppend.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
            expectedAppendDenial.set(true);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                    Integer.class, tenantId, jobId, original.runId()), "期限切れの再送は既存rowだけを保持します");
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try { blocker.rollback(); } catch (Exception failure) { if (primaryFailure != null) primaryFailure.addSuppressed(failure); }
            try { blocker.close(); } catch (Exception failure) { if (primaryFailure != null) primaryFailure.addSuppressed(failure); }
            reportCleanupFailures("append row lock wait fixture", awaitFutures(futures(append), pool, false,
                    expectedAppendDenial.get() ? Set.of(append) : Set.of()), primaryFailure);
        }
    }

    private ImportValidationLifecycleService.RunLease reflectedLease(
            ImportValidationLifecycleService.RunLease source, Timestamp expiry) throws Exception {
        var constructor = ImportValidationLifecycleService.RunLease.class.getDeclaredConstructor(
                String.class, source.getClass().getDeclaredField("input").getType(), long.class,
                String.class, long.class, long.class, Timestamp.class);
        constructor.setAccessible(true);
        var inputField = source.getClass().getDeclaredField("input"); inputField.setAccessible(true);
        var instanceField = source.getClass().getDeclaredField("instanceId"); instanceField.setAccessible(true);
        var workerField = source.getClass().getDeclaredField("workerId"); workerField.setAccessible(true);
        var generationField = source.getClass().getDeclaredField("generation"); generationField.setAccessible(true);
        var versionField = source.getClass().getDeclaredField("jobVersion"); versionField.setAccessible(true);
        return constructor.newInstance(instanceField.get(source), inputField.get(source), source.runId(),
                workerField.get(source), generationField.getLong(source), versionField.getLong(source), expiry);
    }

    private long leaseLongField(ImportValidationLifecycleService.RunLease lease, String name) throws Exception {
        var field = lease.getClass().getDeclaredField(name); field.setAccessible(true);
        return field.getLong(lease);
    }

    private String leaseStringField(ImportValidationLifecycleService.RunLease lease, String name) throws Exception {
        var field = lease.getClass().getDeclaredField(name); field.setAccessible(true);
        return (String) field.get(lease);
    }

    private byte[] changed(byte[] value) {
        byte[] changed = value.clone();
        changed[0] ^= 1;
        return changed;
    }

    private ImportValidationAppendService.ValidatedChunk reflectedChunk(
            ImportValidationLifecycleService.RunLease lease,
            List<ImportValidationAppendService.RowMetadata> rows) throws Exception {
        return reflectedChunk(lease, lifecycleService.appendSnapshot(lease), rows);
    }

    private ImportValidationAppendService.ValidatedChunk reflectedChunk(
            ImportValidationLifecycleService.RunLease lease,
            ImportValidationLifecycleService.AppendSnapshot snapshot,
            List<ImportValidationAppendService.RowMetadata> rows) throws Exception {
        var constructor = ImportValidationAppendService.ValidatedChunk.class.getDeclaredConstructor(
                ImportValidationLifecycleService.RunLease.class,
                ImportValidationLifecycleService.AppendSnapshot.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(lease, snapshot, rows);
    }

    private ImportValidationLifecycleService.AppendSnapshot reflectedSnapshot(
            ImportValidationLifecycleService.AppendSnapshot source, String changedField) throws Exception {
        var type = ImportValidationLifecycleService.AppendSnapshot.class;
        var components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            parameterTypes[i] = components[i].getType();
            values[i] = components[i].getAccessor().invoke(source);
            if (components[i].getName().equals(changedField)) ((byte[]) values[i])[0] ^= 1;
        }
        var constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return (ImportValidationLifecycleService.AppendSnapshot) constructor.newInstance(values);
    }

    @Test
    void appendより先にactor変更がcommitすると403でzeroWrite() throws Exception {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        Map<String, Object> before = leaseSnapshot();
        assertMutationWinsBeforeService("sys_user", "SELECT id FROM sys_user WHERE tenant_id=? AND id=? FOR UPDATE",
                new Object[]{tenantId, actorUserId}, "UPDATE sys_user SET status=0 WHERE tenant_id=? AND id=?",
                new Object[]{tenantId, actorUserId}, () -> validationAppendService.appendRemaining(lease), 403);
        assertEquals(before, leaseSnapshot());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=?",
                Integer.class, tenantId, jobId));
    }

    @Test
    void appendより先にpermissionDenyがcommitすると403でzeroWrite() throws Exception {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        long actionId = jdbc.queryForObject("SELECT id FROM t_permission_group_action WHERE tenant_id=? AND group_id=? "
                + "AND action_key=? AND deny_flag=0 AND deleted_flag=0", Long.class, tenantId, groupId,
                ImportMetadataAuthorizationService.ACTION);
        Map<String, Object> before = leaseSnapshot();
        assertMutationWinsBeforeService("t_permission_group_action",
                "SELECT id FROM t_permission_group_action WHERE tenant_id=? AND id=? FOR UPDATE",
                new Object[]{tenantId, actionId}, "UPDATE t_permission_group_action SET deny_flag=1 WHERE tenant_id=? AND id=?",
                new Object[]{tenantId, actionId}, () -> validationAppendService.appendRemaining(lease), 403);
        assertEquals(before, leaseSnapshot());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=?",
                Integer.class, tenantId, jobId));
    }

    @Test
    void appendのwriteGateが先ならactor変更はchunkcommitまで待機する() throws Exception {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        GatePause pause = pauseRealWriteGateAt(3);
        assertServiceCommitsBeforeMutation(pause, "sys_user",
                "UPDATE sys_user SET status=0 WHERE tenant_id=? AND id=?",
                new Object[]{tenantId, actorUserId}, () -> validationAppendService.appendRemaining(lease), true);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM sys_user WHERE tenant_id=? AND id=?", Integer.class, tenantId, actorUserId));
        Map<String, Object> beforeDeniedRetry = leaseSnapshot();
        BusinessException denied = assertThrows(BusinessException.class,
                () -> validationAppendService.appendRemaining(lease));
        assertEquals(403, denied.getCode());
        assertEquals(beforeDeniedRetry, leaseSnapshot());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()));
    }

    @Test
    void appendのwriteGateが先ならpermission変更はchunkcommitまで待機する() throws Exception {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        long actionId = jdbc.queryForObject("SELECT id FROM t_permission_group_action WHERE tenant_id=? AND group_id=? "
                + "AND action_key=? AND deny_flag=0 AND deleted_flag=0", Long.class, tenantId, groupId,
                ImportMetadataAuthorizationService.ACTION);
        GatePause pause = pauseRealWriteGateAt(3);
        assertServiceCommitsBeforeMutation(pause, "t_permission_group_action",
                "UPDATE t_permission_group_action SET deny_flag=1 WHERE tenant_id=? AND id=?",
                new Object[]{tenantId, actionId}, () -> validationAppendService.appendRemaining(lease),
                "PRIMARY", Long.toString(actionId), true);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                Integer.class, tenantId, jobId, lease.runId()));
        assertEquals(1, jdbc.queryForObject("SELECT deny_flag FROM t_permission_group_action WHERE tenant_id=? AND id=?",
                Integer.class, tenantId, actionId));
        Map<String, Object> beforeDeniedRetry = leaseSnapshot();
        BusinessException denied = assertThrows(BusinessException.class,
                () -> validationAppendService.appendRemaining(lease));
        assertEquals(403, denied.getCode());
        assertEquals(beforeDeniedRetry, leaseSnapshot());
    }

    @Test
    void appendがsourceLockとwriteGateを保持中takeoverは待機しcommit後に拒否される() throws Exception {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        var takeover = lifecycleService.beginTakeover(jobId, lease.runId());
        GatePause pause = pauseRealWriteGateAt(3);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Long> takeoverWorkerId = new AtomicReference<>();
        Set<Future<?>> verifiedFailures = new java.util.HashSet<>();
        captureActualAuthorizationConnection();
        Future<ImportValidationLifecycleService.RunLease> append = null;
        Future<?> takeoverFuture = null;
        Throwable primaryFailure = null;
        try {
            append = pool.submit(() -> withActorContext(() -> validationAppendService.appendRemaining(lease)));
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS), "appendのchunk write gateに到達しませんでした");
            assertGateRunsInReadCommittedTransaction(pause);
            takeoverFuture = pool.submit(() -> withCapturedAuthorizationConnection(takeoverWorkerId,
                    () -> lifecycleService.takeOver(takeover)));
            SourceLockWait observed = awaitSourceLockWaitFromBlocker(pause.connectionId.get(), takeoverWorkerId,
                    takeoverFuture, append, "takeover waiting on append");
            logSourceLockWait("takeover waiting on append", observed);
            assertTrue(!append.isDone(), "takeover待機中にappend transactionが完了してはいけません");
            pause.release.countDown();
            append.get(10, TimeUnit.SECONDS);
            Future<?> pendingTakeover = takeoverFuture;
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> pendingTakeover.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
            verifiedFailures.add(takeoverFuture);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                    Integer.class, tenantId, jobId, lease.runId()));
            assertEquals(1L, jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?",
                    Long.class, tenantId, jobId));
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            pause.release.countDown();
            reportCleanupFailures("append/takeover lock wait fixture",
                    awaitFutures(futures(append, takeoverFuture), pool, false, verifiedFailures), primaryFailure);
        }
    }

    @Test
    void appendがsourceLockとwriteGateを保持中abortは待機しstaleGrantとして拒否される() throws Exception {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        GatePause pause = pauseRealWriteGateAt(3);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Long> abortWorkerId = new AtomicReference<>();
        Set<Future<?>> verifiedFailures = new java.util.HashSet<>();
        captureActualAuthorizationConnection();
        Future<ImportValidationLifecycleService.RunLease> append = null;
        Future<?> abortFuture = null;
        Throwable primaryFailure = null;
        try {
            append = pool.submit(() -> withActorContext(() -> validationAppendService.appendRemaining(lease)));
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS), "appendのchunk write gateに到達しませんでした");
            assertGateRunsInReadCommittedTransaction(pause);
            abortFuture = pool.submit(() -> withCapturedAuthorizationConnection(abortWorkerId, () -> {
                lifecycleService.abort(lease);
                return null;
            }));
            SourceLockWait observed = awaitSourceLockWaitFromBlocker(pause.connectionId.get(), abortWorkerId,
                    abortFuture, append, "abort waiting on append");
            logSourceLockWait("abort waiting on append", observed);
            assertTrue(!append.isDone(), "abort待機中にappend transactionが完了してはいけません");
            pause.release.countDown();
            append.get(10, TimeUnit.SECONDS);
            Future<?> pendingAbort = abortFuture;
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> pendingAbort.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
            verifiedFailures.add(abortFuture);
            assertEquals("VALIDATING", leaseSnapshot().get("state"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                    Integer.class, tenantId, jobId, lease.runId()));
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            pause.release.countDown();
            reportCleanupFailures("append/abort lock wait fixture",
                    awaitFutures(futures(append, abortFuture), pool, false, verifiedFailures), primaryFailure);
        }
    }

    @Test
    void takeoverが先行してsourceLockを保持するとappendは待機後409でzeroWrite() throws Exception {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?",
                tenantId, jobId);
        var takeover = lifecycleService.beginTakeover(jobId, lease.runId());
        GatePause pause = pauseRealWriteGateAt(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Long> appendWorkerId = new AtomicReference<>();
        captureActualAuthorizationConnection();
        Future<?> takeoverFuture = null, appendFuture = null;
        Set<Future<?>> verifiedFailures = new java.util.HashSet<>();
        Throwable primaryFailure = null;
        try {
            takeoverFuture = pool.submit(() -> withActorContext(() -> lifecycleService.takeOver(takeover)));
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS), "takeoverの認可gateに到達しませんでした");
            assertGateRunsInReadCommittedTransaction(pause);
            appendFuture = pool.submit(() -> withCapturedAuthorizationConnection(appendWorkerId,
                    () -> validationAppendService.appendRemaining(lease)));
            SourceLockWait wait = awaitSourceLockWaitFromBlocker(pause.connectionId.get(), appendWorkerId, appendFuture,
                    takeoverFuture, "append waiting behind takeover");
            logSourceLockWait("append waiting behind takeover", wait);
            pause.release.countDown();
            takeoverFuture.get(10, TimeUnit.SECONDS);
            Future<?> pendingAppend = appendFuture;
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> pendingAppend.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
            verifiedFailures.add(appendFuture);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=?",
                    Integer.class, tenantId, jobId));
            assertEquals(2L, jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?",
                    Long.class, tenantId, jobId));
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            pause.release.countDown();
            reportCleanupFailures("takeover-first append wait fixture", awaitFutures(futures(takeoverFuture, appendFuture),
                    pool, false, verifiedFailures), primaryFailure);
        }
    }

    @Test
    void abortが先行してsourceLockを保持するとappendは待機後409でzeroWrite() throws Exception {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        GatePause pause = pauseRealWriteGateAt(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Long> appendWorkerId = new AtomicReference<>();
        captureActualAuthorizationConnection();
        Future<?> abortFuture = null, appendFuture = null;
        Set<Future<?>> verifiedFailures = new java.util.HashSet<>();
        Throwable primaryFailure = null;
        try {
            abortFuture = pool.submit(() -> withActorContext(() -> { lifecycleService.abort(lease); return null; }));
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS), "abortの認可gateに到達しませんでした");
            assertGateRunsInReadCommittedTransaction(pause);
            appendFuture = pool.submit(() -> withCapturedAuthorizationConnection(appendWorkerId,
                    () -> validationAppendService.appendRemaining(lease)));
            SourceLockWait wait = awaitSourceLockWaitFromBlocker(pause.connectionId.get(), appendWorkerId, appendFuture,
                    abortFuture, "append waiting behind abort");
            logSourceLockWait("append waiting behind abort", wait);
            pause.release.countDown();
            abortFuture.get(10, TimeUnit.SECONDS);
            Future<?> pendingAppend = appendFuture;
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> pendingAppend.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
            verifiedFailures.add(appendFuture);
            assertEquals("MAPPED", leaseSnapshot().get("state"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=?",
                    Integer.class, tenantId, jobId));
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            pause.release.countDown();
            reportCleanupFailures("abort-first append wait fixture", awaitFutures(futures(abortFuture, appendFuture),
                    pool, false, verifiedFailures), primaryFailure);
        }
    }

    @Test
    void appendがjobFenceを保持するとjob更新はjob行waitGraphで待機する() throws Exception {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        GatePause pause = pauseRealWriteGateAt(3);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Long> writerId = new AtomicReference<>();
        Future<?> append = null, mutation = null;
        Throwable primaryFailure = null;
        try {
            append = pool.submit(() -> withActorContext(() -> validationAppendService.appendRemaining(lease)));
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS), "appendのchunk gateへ到達しませんでした");
            assertGateRunsInReadCommittedTransaction(pause);
            mutation = pool.submit(() -> updateAndCommit("UPDATE t_import_job SET version_no=version_no WHERE tenant_id=? AND id=?",
                    new Object[]{tenantId, jobId}, writerId));
            SourceLockWait wait = awaitAnyTableRecordXWait(writerId, pause.connectionId, append, mutation,
                    "t_import_job", Long.toString(jobId), "job-fence update");
            System.out.printf("NF06 job fence wait確認: requester=%d blocker=%d index=%s key=%s request=%s/%s blocker=%s/%s%n",
                    wait.requesterConnectionId(), wait.blockerConnectionId(), wait.indexName(), wait.lockData(),
                    wait.requestType(), wait.requestMode(), wait.blockerType(), wait.blockerMode());
            pause.release.countDown();
            append.get(10, TimeUnit.SECONDS);
            mutation.get(10, TimeUnit.SECONDS);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                    Integer.class, tenantId, jobId, lease.runId()));
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            pause.release.countDown();
            reportCleanupFailures("append job fence fixture", awaitFutures(futures(append, mutation), pool, false), primaryFailure);
        }
    }

    private SourceLockWait awaitAnyTableRecordXWait(AtomicReference<Long> requester,
                                                    AtomicReference<Long> blocker, Future<?> service,
                                                    Future<?> mutation, String table, String expectedLockData,
                                                    String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFuturePending(service, "append holding " + table);
            assertFuturePending(mutation, label);
            if (requester.get() != null && blocker.get() != null) {
                List<SourceLockWait> waits = jdbc.query("""
                        SELECT wt.PROCESSLIST_ID,bt.PROCESSLIST_ID,req.INDEX_NAME,req.LOCK_DATA,
                          req.LOCK_TYPE,req.LOCK_MODE,req.LOCK_STATUS,blk.LOCK_TYPE,blk.LOCK_MODE,blk.LOCK_STATUS
                        FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME=?
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME=?
                          AND req.INDEX_NAME='PRIMARY' AND blk.INDEX_NAME='PRIMARY'
                          AND req.LOCK_DATA=? AND blk.LOCK_DATA=?
                          AND req.LOCK_TYPE='RECORD' AND req.LOCK_STATUS='WAITING'
                          AND req.LOCK_MODE IN ('X','X,REC_NOT_GAP')
                          AND blk.LOCK_TYPE='RECORD' AND blk.LOCK_STATUS='GRANTED'
                          AND blk.LOCK_MODE IN ('X','X,REC_NOT_GAP')
                        """, (rs, row) -> new SourceLockWait(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10)), requester.get(), blocker.get(), table, table,
                        expectedLockData, expectedLockData);
                if (!waits.isEmpty()) return waits.get(0);
            }
            Thread.sleep(25);
        }
        throw new AssertionError(label + "の" + table + " record X wait graphを確認できませんでした: "
                + "expected PRIMARY key=" + expectedLockData + "; "
                + safeWaitDetails(requester.get(), blocker.get()));
    }

    @Test
    void takeovercommit後の旧grantappendは拒否されrowを作らない() {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?",
                tenantId, jobId);
        lifecycleService.takeOver(lifecycleService.beginTakeover(jobId, lease.runId()));
        Map<String, Object> before = leaseSnapshot();
        BusinessException rejected = assertThrows(BusinessException.class,
                () -> validationAppendService.appendRemaining(lease));
        assertEquals(409, rejected.getCode());
        assertEquals(before, leaseSnapshot());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=?",
                Integer.class, tenantId, jobId));
    }

    @Test
    void abortcommit後の旧grantappendは拒否されrowを作らない() {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        lifecycleService.abort(lease);
        Map<String, Object> before = leaseSnapshot();
        BusinessException rejected = assertThrows(BusinessException.class,
                () -> validationAppendService.appendRemaining(lease));
        assertEquals(409, rejected.getCode());
        assertEquals(before, leaseSnapshot());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=?",
                Integer.class, tenantId, jobId));
    }

    @Test
    void 期限切れrunLeaseでappendは拒否されleaseもrowも変更しない() {
        prepareBoundCustomerJob();
        var lease = lifecycleService.start(lifecycleService.beginStart(jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?",
                tenantId, jobId);
        Map<String, Object> before = leaseSnapshot();
        BusinessException rejected = assertThrows(BusinessException.class,
                () -> validationAppendService.appendRemaining(lease));
        assertEquals(409, rejected.getCode());
        assertEquals(before, leaseSnapshot());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=?",
                Integer.class, tenantId, jobId));
    }

    @Test
    void 同operationのstartretryはgate待機中に期限切れ後409で状態を変更しない() throws Exception {
        prepareBoundCustomerJob();
        var operation = lifecycleService.beginStart(jobId);
        lifecycleService.start(operation);
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,8,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?",
                tenantId, jobId);
        Map<String, Object> before = leaseSnapshot();
        BlockedLease blocked = blockLeaseAtAuthorizationGate(() -> lifecycleService.start(operation));
        try {
            assertLeaseExpiryHasRemainingMargin(3_000_000L);
            awaitLeaseExpiryByDatabaseClock();
            blocked.blocker().commit();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> blocked.service().get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
            assertEquals(before, leaseSnapshot());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=?",
                    Integer.class, tenantId, jobId));
        } finally {
            closeBlockedLease(blocked, 409);
        }
    }

    @Test
    void F1bの同operationtakeoverretryはgate待機中に期限切れ後409で状態を変更しない() throws Exception {
        prepareBoundCustomerJob();
        var initial = lifecycleService.start(lifecycleService.beginStart(jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?",
                tenantId, jobId);
        var operation = lifecycleService.beginTakeover(jobId, initial.runId());
        lifecycleService.takeOver(operation);
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,8,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?",
                tenantId, jobId);
        Map<String, Object> before = leaseSnapshot();
        BlockedLease blocked = blockLeaseAtAuthorizationGate(() -> lifecycleService.takeOver(operation));
        try {
            assertLeaseExpiryHasRemainingMargin(3_000_000L);
            awaitLeaseExpiryByDatabaseClock();
            blocked.blocker().commit();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> blocked.service().get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
            assertEquals(before, leaseSnapshot());
            assertEquals(2L, ((Number) leaseSnapshot().get("lease_generation")).longValue());
        } finally {
            closeBlockedLease(blocked, 409);
        }
    }

    @Test
    void source行待機中に権限を撤回するとregisterJobはlinkも含めて拒否する() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        Connection blocker = null;
        ExecutorService pool = null;
        Future<Long> registration = null;
        var requesterId = new AtomicReference<Long>();
        Throwable primaryFailure = null;
        boolean expectedDenialObserved = false;
        try {
            blocker = dataSource.getConnection();
            blocker.setAutoCommit(false);
            SourcePrimaryRecordLock holderLock = lockExactSourcePrimaryRow(blocker, sourceId);
            pool = Executors.newSingleThreadExecutor();
            Future<Long> submittedRegistration = pool.submit(() -> {
                var authority = new SimpleGrantedAuthority("ROLE_管理者");
                installPrincipal(tenantId, authority);
                AccountingTenantContextHolder.setTenantId(tenantId);
                try {
                    return runSourceWaitWorker(requesterId, () -> metadataService.registerJob(
                            new ImportJobRegistrationRequest(sourceId, ImportEntity.CUSTOMER,
                                    "ses-import-v1", ImportEncoding.UTF8, SOURCE_HASH,
                                    documentId, documentVersionId, 1)));
                } finally {
                    SecurityContextHolder.clearContext();
                    AccountingTenantContextHolder.clear();
                }
            });
            registration = submittedRegistration;
            awaitSourcePrimaryWait(requesterId, holderLock, sourceId, submittedRegistration);
            jdbc.update("UPDATE t_permission_group_action SET deleted_flag=1 WHERE tenant_id=? AND group_id=? AND action_key=? AND deny_flag=0",
                    tenantId, groupId, ImportMetadataAuthorizationService.ACTION);
            blocker.commit();
            var failure = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> submittedRegistration.get(SOURCE_WAIT_FUTURE_SECONDS, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(403, ((BusinessException) failure.getCause()).getCode());
            expectedDenialObserved = true;
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? AND source_system_id=?",
                    Integer.class, tenantId, sourceId));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_link WHERE tenant_id=? AND document_id=? AND target_type='IMPORT_JOB'",
                    Integer.class, tenantId, documentId));
            assertEquals(1, jdbc.queryForObject("SELECT version_no FROM t_import_source_system WHERE tenant_id=? AND id=?",
                    Integer.class, tenantId, sourceId));
            assertEquals(0, storage.openCount());
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            List<Throwable> cleanupFailures = new java.util.ArrayList<>();
            if (!expectedDenialObserved) {
                try {
                    jdbc.update("UPDATE t_permission_group_action SET deleted_flag=1 "
                                    + "WHERE tenant_id=? AND group_id=? AND action_key=? AND deny_flag=0",
                            tenantId, groupId, ImportMetadataAuthorizationService.ACTION);
                } catch (Exception failure) { cleanupFailures.add(failure); }
            }
            if (blocker != null) {
                try { blocker.rollback(); } catch (Exception failure) { cleanupFailures.add(failure); }
                try { blocker.close(); } catch (Exception failure) { cleanupFailures.add(failure); }
            }
            cleanupFailures.addAll(awaitSourceWorkers(registration == null ? List.of() : List.of(registration),
                    pool, requesterId, true));
            reportCleanupFailures("source lock競合fixture", cleanupFailures, primaryFailure);
        }
    }

    @Test
    void sourcePrimaryWaitOracleは誤holderRequester対象と複数RECORDを拒否する() {
        SourcePrimaryRecordLock holder = new SourcePrimaryRecordLock("INNODB", "holder-lock", "holder-trx",
                71L, "nf06_import_metadata_real", "t_import_source_system", "PRIMARY", "RECORD",
                "X,REC_NOT_GAP", "GRANTED", null, 301L);
        SourcePrimaryWaitEdge edge = new SourcePrimaryWaitEdge("INNODB", 72L, "request-lock", "request-trx",
                "nf06_import_metadata_real", "t_import_source_system", "PRIMARY", "RECORD", "X,REC_NOT_GAP",
                "WAITING", null, 71L, "holder-lock", "holder-trx", "nf06_import_metadata_real",
                "t_import_source_system", "PRIMARY", "RECORD", "X,REC_NOT_GAP", "GRANTED", null);

        assertTrue(isExactSourcePrimaryWait(List.of(holder), List.of(edge), 72L, 71L, 301L));
        SourcePrimaryWaitEdge requesterKeyObserved = new SourcePrimaryWaitEdge(edge.engine(), edge.requesterConnectionId(),
                edge.requesterLockId(), edge.requesterTransactionId(), edge.requesterSchema(), edge.requesterTable(),
                edge.requesterIndex(), edge.requesterType(), edge.requesterMode(), edge.requesterStatus(), "301",
                edge.holderConnectionId(), edge.holderLockId(), edge.holderTransactionId(), edge.holderSchema(),
                edge.holderTable(), edge.holderIndex(), edge.holderType(), edge.holderMode(), edge.holderStatus(), null);
        assertTrue(isExactSourcePrimaryWait(List.of(holder), List.of(requesterKeyObserved), 72L, 71L, 301L),
                "requester keyのみ観測できてもexpected sourceなら許可");
        SourcePrimaryWaitEdge holderKeyObserved = new SourcePrimaryWaitEdge(edge.engine(), edge.requesterConnectionId(),
                edge.requesterLockId(), edge.requesterTransactionId(), edge.requesterSchema(), edge.requesterTable(),
                edge.requesterIndex(), edge.requesterType(), edge.requesterMode(), edge.requesterStatus(), null,
                edge.holderConnectionId(), edge.holderLockId(), edge.holderTransactionId(), edge.holderSchema(),
                edge.holderTable(), edge.holderIndex(), edge.holderType(), edge.holderMode(), edge.holderStatus(), "301");
        assertTrue(isExactSourcePrimaryWait(List.of(holder), List.of(holderKeyObserved), 72L, 71L, 301L),
                "holder keyのみ観測できてもexpected sourceなら許可");
        assertTrue(!isExactSourcePrimaryWait(List.of(holder), List.of(edge), 73L, 71L, 301L), "誤ったrequesterを拒否");
        assertTrue(!isExactSourcePrimaryWait(List.of(holder), List.of(edge), 72L, 74L, 301L), "誤ったholderを拒否");
        assertTrue(!isExactSourcePrimaryWait(List.of(holder), List.of(edge), 72L, 71L, 302L), "誤った対象sourceを拒否");
        SourcePrimaryRecordLock secondRecord = new SourcePrimaryRecordLock("INNODB", "holder-lock-2", "holder-trx",
                71L, "nf06_import_metadata_real", "t_import_source_system", "PRIMARY", "RECORD",
                "X,REC_NOT_GAP", "GRANTED", null, 301L);
        assertTrue(!isExactSourcePrimaryWait(List.of(holder, secondRecord), List.of(edge), 72L, 71L, 301L),
                "holderに対象PRIMARYのrecord lockが複数あれば拒否");
        SourcePrimaryWaitEdge wrongTarget = new SourcePrimaryWaitEdge(edge.engine(), edge.requesterConnectionId(),
                edge.requesterLockId(), edge.requesterTransactionId(), edge.requesterSchema(), edge.requesterTable(),
                edge.requesterIndex(), edge.requesterType(), edge.requesterMode(), edge.requesterStatus(), "302",
                edge.holderConnectionId(), edge.holderLockId(), edge.holderTransactionId(), edge.holderSchema(),
                edge.holderTable(), edge.holderIndex(), edge.holderType(), edge.holderMode(), edge.holderStatus(), "302");
        assertTrue(!isExactSourcePrimaryWait(List.of(holder), List.of(wrongTarget), 72L, 71L, 301L),
                "requesterの非NULL lock keyが期待source IDと違えば拒否");
        SourcePrimaryWaitEdge wrongHolderKey = new SourcePrimaryWaitEdge(edge.engine(), edge.requesterConnectionId(),
                edge.requesterLockId(), edge.requesterTransactionId(), edge.requesterSchema(), edge.requesterTable(),
                edge.requesterIndex(), edge.requesterType(), edge.requesterMode(), edge.requesterStatus(), null,
                edge.holderConnectionId(), edge.holderLockId(), edge.holderTransactionId(), edge.holderSchema(),
                edge.holderTable(), edge.holderIndex(), edge.holderType(), edge.holderMode(), edge.holderStatus(), "302");
        assertTrue(!isExactSourcePrimaryWait(List.of(holder), List.of(wrongHolderKey), 72L, 71L, 301L),
                "holderの非NULL lock keyが期待source IDと違えば拒否");
        assertTrue(!isExactSourcePrimaryWait(List.of(holder), List.of(edge, edge), 72L, 71L, 301L),
                "wait edgeが複数なら曖昧として拒否");
    }

    // 以下7件は認可競合coverage候補（未実行）。service transaction自体のsource行待機分岐を検証する。
    @Test
    void source行待機中に権限を撤回するとcreateMappingは新規mappingを作らない() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        assertRevokedWhileSourceLockHeld(() -> metadataService.createMapping(customerMappingRequest()));

        assertEquals(0, mappingCountForSource());
        assertSourceRemainsActiveAtVersionOne();
    }

    @Test
    void source行待機中に権限を撤回するとcreateMappingの再試行も成功returnしない() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        mappingId = metadataService.createMapping(customerMappingRequest());
        assertRevokedWhileSourceLockHeld(() -> metadataService.createMapping(customerMappingRequest()));

        assertEquals(1, mappingCountForSource());
        assertEquals(1, jdbc.queryForObject("SELECT mapping_version FROM t_import_mapping WHERE tenant_id=? AND id=?",
                Integer.class, tenantId, mappingId));
        assertSourceRemainsActiveAtVersionOne();
    }

    @Test
    void source行待機中に権限を撤回するとbindMappingの更新を拒否する() throws Exception {
        prepareUnboundCustomerJob();
        assertRevokedWhileSourceLockHeld(() -> metadataService.bindMapping(jobId, 1, mappingId));

        assertJobBinding("UPLOADED", 1, null);
        assertSourceRemainsActiveAtVersionOne();
    }

    @Test
    void source行待機中に権限を撤回するとbindMappingの冪等returnも拒否する() throws Exception {
        prepareBoundCustomerJob();
        assertRevokedWhileSourceLockHeld(() -> metadataService.bindMapping(jobId, 2, mappingId));

        assertJobBinding("MAPPED", 2, mappingId);
        assertSourceRemainsActiveAtVersionOne();
    }

    @Test
    void source行待機中に権限を撤回するとdisableSourceの更新を拒否する() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        assertRevokedWhileSourceLockHeld(() -> metadataService.disableSource(sourceId, 1, true));

        assertSourceRemainsActiveAtVersionOne();
    }

    @Test
    void source行待機中に権限を撤回するとdisableSourceのnoOpReturnも拒否する() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        assertRevokedWhileSourceLockHeld(() -> metadataService.disableSource(sourceId, 1, false));

        assertSourceRemainsActiveAtVersionOne();
    }

    @Test
    void source行待機中に権限を撤回するとregisterJobの冪等returnとlink確認を拒否する() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        jobId = metadataService.registerJob(customerJobRequest());
        assertRevokedWhileSourceLockHeld(() -> metadataService.registerJob(customerJobRequest()));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? AND source_system_id=?",
                Integer.class, tenantId, sourceId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_link WHERE tenant_id=? AND document_id=? "
                + "AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0",
                Integer.class, tenantId, documentId, jobId));
        assertEquals(1, jdbc.queryForObject("SELECT version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                Integer.class, tenantId, jobId));
        assertSourceRemainsActiveAtVersionOne();
        assertEquals(0, storage.openCount());
    }

    @Test
    void registry行待機中に権限撤回が先にcommitすると既存candidateの冪等returnを拒否する() throws Exception {
        long validationRunId = prepareValidatedCustomerJob();
        long candidateId = candidateService.registerCandidate(jobId, validationRunId, 1);
        storage.resetOpenCount();
        byte[] lookupDigest = jdbc.queryForObject("SELECT lookup_digest FROM t_import_identity_registry WHERE tenant_id=? AND id=?",
                byte[].class, tenantId, candidateId);
        var before = jdbc.queryForMap("SELECT lookup_key_version, lookup_digest, encryption_key_version, nonce, "
                        + "identity_ciphertext, first_job_id, first_validation_run_id FROM t_import_identity_registry "
                        + "WHERE tenant_id=? AND id=?", tenantId, candidateId);
        int jobVersionBefore = jobVersion();

        assertRevokedWhileRegistryLockHeld(validationRunId, lookupDigest);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry WHERE tenant_id=? AND id=?",
                Integer.class, tenantId, candidateId));
        var after = jdbc.queryForMap("SELECT lookup_key_version, lookup_digest, encryption_key_version, nonce, "
                        + "identity_ciphertext, first_job_id, first_validation_run_id FROM t_import_identity_registry "
                        + "WHERE tenant_id=? AND id=?", tenantId, candidateId);
        assertCandidateRegistrySnapshotEquals(before, after);
        assertEquals(jobVersionBefore, jobVersion());
        assertEquals(1, storage.openCount(), "検証済みsourceの候補確認ではsourceを一度だけ実読する");
    }

    @Test
    void job行待機中に権限撤回が先にcommitするとlease取得を拒否し状態を保つ() throws Exception {
        prepareBoundCustomerJob();
        ImportValidationLeaseService.AcquireOperation operation = leaseService.beginAcquire(jobId);
        Map<String, Object> before = leaseSnapshot();
        assertRevokedWhileJobLockHeld(() -> leaseService.acquire(operation));
        assertEquals(before, leaseSnapshot());
    }

    @Test
    void candidate実serviceのgate後は新規deny行がcommitまで待機する() throws Exception {
        long validationRunId = prepareValidatedCustomerJob();
        GatePause pause = pauseRealWriteGate();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch writerMayCommit = new CountDownLatch(1);
        Future<Long> service = null;
        Future<?> mutation = null;
        Throwable primaryFailure = null;
        try {
            service = pool.submit(() -> withActorContext(() ->
                    candidateService.registerCandidate(jobId, validationRunId, 1)));
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS), "実candidate serviceのwrite gateに到達しませんでした");
            assertGateRunsInReadCommittedTransaction(pause);
            AtomicReference<Long> writerId = new AtomicReference<>();
            mutation = pool.submit(() -> insertDenyActionAndWaitForCommit(writerId, writerMayCommit));
            awaitForeignKeyParentWait(writerId, pause.connectionId, service, mutation,
                    "m_permission_group", groupId);
            assertTrue(!service.isDone(), "実candidate transactionがgate保持中に完了しました");
            pause.release.countDown();
            long candidateId = service.get(10, TimeUnit.SECONDS);
            assertTrue(candidateId > 0);
            writerMayCommit.countDown();
            mutation.get(10, TimeUnit.SECONDS);
            Map<String, Object> beforeDeniedRetry = jdbc.queryForMap("SELECT id,lookup_digest,identity_ciphertext "
                    + "FROM t_import_identity_registry WHERE tenant_id=? AND id=?", tenantId, candidateId);
            BusinessException denied = assertThrows(BusinessException.class,
                    () -> candidateService.registerCandidate(jobId, validationRunId, 1));
            assertEquals(403, denied.getCode());
            assertCandidateRegistrySnapshotEquals(beforeDeniedRetry, jdbc.queryForMap("SELECT id,lookup_digest,identity_ciphertext "
                    + "FROM t_import_identity_registry WHERE tenant_id=? AND id=?", tenantId, candidateId));
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            pause.release.countDown();
            writerMayCommit.countDown();
            List<Throwable> cleanup = awaitFutures(futures(service, mutation), pool, false);
            reportCleanupFailures("実candidate gate待機fixture", cleanup, primaryFailure);
        }
    }

    @Test
    void lease実serviceのgate後はdenygroupへのmembership追加がcommitまで待機する() throws Exception {
        prepareBoundCustomerJob();
        String denyGroupKey = "nf06-deny-" + UUID.randomUUID();
        jdbc.update("INSERT INTO m_permission_group(tenant_id,group_key,group_name,enabled,deleted_flag) "
                + "VALUES(?,?,?,1,0)", tenantId, denyGroupKey, "合成deny group");
        denyGroupId = jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id=? AND group_key=?",
                Long.class, tenantId, denyGroupKey);
        jdbc.update("INSERT INTO t_permission_group_action(tenant_id,group_id,action_key,deny_flag,deleted_flag) "
                + "VALUES(?,?, 'import.*',1,0)", tenantId, denyGroupId);
        ImportValidationLeaseService.AcquireOperation operation = leaseService.beginAcquire(jobId);
        GatePause pause = pauseRealWriteGate();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch writerMayCommit = new CountDownLatch(1);
        Future<ImportValidationLeaseService.LeaseGrant> service = null;
        Future<?> mutation = null;
        Throwable primaryFailure = null;
        try {
            service = pool.submit(() -> withActorContext(() -> leaseService.acquire(operation)));
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS), "実lease serviceのwrite gateに到達しませんでした");
            assertGateRunsInReadCommittedTransaction(pause);
            AtomicReference<Long> writerId = new AtomicReference<>();
            mutation = pool.submit(() -> insertMembershipAndWaitForCommit(denyGroupId, writerId, writerMayCommit));
            awaitForeignKeyParentWait(writerId, pause.connectionId, service, mutation, "sys_user", actorUserId);
            assertTrue(!service.isDone(), "実lease transactionがgate保持中に完了しました");
            pause.release.countDown();
            ImportValidationLeaseService.LeaseGrant grant = service.get(10, TimeUnit.SECONDS);
            writerMayCommit.countDown();
            mutation.get(10, TimeUnit.SECONDS);
            Map<String, Object> beforeDeniedRenewal = leaseSnapshot();
            BusinessException denied = assertThrows(BusinessException.class, () -> leaseService.renew(grant));
            assertEquals(403, denied.getCode());
            assertEquals(beforeDeniedRenewal, leaseSnapshot());
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            pause.release.countDown();
            writerMayCommit.countDown();
            reportCleanupFailures("実lease gate待機fixture", awaitFutures(futures(service, mutation), pool, false),
                    primaryFailure);
        }
    }

    @Test
    void gate待機後に失効した同operation取得retryは409でleaseを変更しない() throws Exception {
        prepareBoundCustomerJob();
        var operation = leaseService.beginAcquire(jobId);
        assertTrue(leaseService.acquire(operation) != null);
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,8,CURRENT_TIMESTAMP(6)) "
                + "WHERE tenant_id=? AND id=?", tenantId, jobId);
        Map<String, Object> before = leaseSnapshot();

        BlockedLease blocked = blockLeaseAtAuthorizationGate(() -> leaseService.acquire(operation));
        try {
            assertLeaseExpiryHasRemainingMargin(3_000_000L);
            awaitLeaseExpiryByDatabaseClock();
            blocked.blocker().commit();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> blocked.service().get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
            assertEquals(before, leaseSnapshot());
        } finally {
            closeBlockedLease(blocked, 409);
        }
    }

    @Test
    void gate待機後に失効したrenewは409でleaseを延長しない() throws Exception {
        prepareBoundCustomerJob();
        var grant = leaseService.acquire(leaseService.beginAcquire(jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,8,CURRENT_TIMESTAMP(6)) "
                + "WHERE tenant_id=? AND id=?", tenantId, jobId);
        Map<String, Object> before = leaseSnapshot();

        BlockedLease blocked = blockLeaseAtAuthorizationGate(() -> leaseService.renew(grant));
        try {
            assertLeaseExpiryHasRemainingMargin(3_000_000L);
            awaitLeaseExpiryByDatabaseClock();
            blocked.blocker().commit();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> blocked.service().get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
            assertEquals(before, leaseSnapshot());
        } finally {
            closeBlockedLease(blocked, 409);
        }
    }

    @Test
    void 初回acquireはgate待機後も成功し期限を待機後DB時刻から設定する() throws Exception {
        prepareBoundCustomerJob();
        var operation = leaseService.beginAcquire(jobId);
        int versionBefore = jdbc.queryForObject("SELECT version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                Integer.class, tenantId, jobId);
        BlockedLease blocked = blockLeaseAtAuthorizationGate(() -> leaseService.acquire(operation));
        try {
            Thread.sleep(1_200);
            blocked.blocker().commit();
            var grant = blocked.service().get(10, TimeUnit.SECONDS);
            assertTrue(grant != null);
            assertEquals(1L, (long) leaseSnapshot().get("lease_generation"));
            assertEquals(1, jdbc.queryForObject("SELECT lease_expires_at>CURRENT_TIMESTAMP(6) FROM t_import_job "
                    + "WHERE tenant_id=? AND id=?", Integer.class, tenantId, jobId));
            assertEquals(versionBefore + 1, jdbc.queryForObject("SELECT version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                    Integer.class, tenantId, jobId));
        } finally {
            closeBlockedLease(blocked, 0);
        }
    }

    @Test
    void 期限切れtakeoverはgate待機後に成功してgenerationを一度だけ進める() throws Exception {
        prepareBoundCustomerJob();
        leaseService.acquire(leaseService.beginAcquire(jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) "
                + "WHERE tenant_id=? AND id=?", tenantId, jobId);
        var operation = leaseService.beginTakeover(jobId);
        long generationBefore = (long) leaseSnapshot().get("lease_generation");
        BlockedLease blocked = blockLeaseAtAuthorizationGate(() -> leaseService.takeOver(operation));
        try {
            Thread.sleep(1_200);
            blocked.blocker().commit();
            var grant = blocked.service().get(10, TimeUnit.SECONDS);
            assertTrue(grant != null);
            assertEquals(generationBefore + 1, (long) leaseSnapshot().get("lease_generation"));
            assertEquals(1, jdbc.queryForObject("SELECT lease_expires_at>CURRENT_TIMESTAMP(6) FROM t_import_job "
                    + "WHERE tenant_id=? AND id=?", Integer.class, tenantId, jobId));
        } finally {
            closeBlockedLease(blocked, 0);
        }
    }

    @Test
    void 未期限takeoverはgate待機中に期限を越えた後freshDB時刻で成功する() throws Exception {
        prepareBoundCustomerJob();
        leaseService.acquire(leaseService.beginAcquire(jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,8,CURRENT_TIMESTAMP(6)) "
                + "WHERE tenant_id=? AND id=?", tenantId, jobId);
        var operation = leaseService.beginTakeover(jobId);
        long generationBefore = ((Number) leaseSnapshot().get("lease_generation")).longValue();
        long versionBefore = ((Number) leaseSnapshot().get("version_no")).longValue();
        BlockedLease blocked = blockLeaseAtAuthorizationGate(() -> leaseService.takeOver(operation));
        try {
            assertLeaseExpiryHasRemainingMargin(3_000_000L);
            awaitLeaseExpiryByDatabaseClock();
            blocked.blocker().commit();
            var grant = blocked.service().get(10, TimeUnit.SECONDS);
            assertTrue(grant != null);
            Map<String, Object> after = leaseSnapshot();
            assertEquals(generationBefore + 1, ((Number) after.get("lease_generation")).longValue());
            assertEquals(versionBefore + 1, ((Number) after.get("version_no")).longValue());
            assertEquals(1, jdbc.queryForObject("SELECT lease_expires_at>CURRENT_TIMESTAMP(6) FROM t_import_job "
                    + "WHERE tenant_id=? AND id=?", Integer.class, tenantId, jobId));
        } finally {
            closeBlockedLease(blocked, 0);
        }
    }

    @Test
    void 同operationのtakeoverretryはgate待機中に期限切れ後409で状態を変更しない() throws Exception {
        prepareBoundCustomerJob();
        leaseService.acquire(leaseService.beginAcquire(jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) "
                + "WHERE tenant_id=? AND id=?", tenantId, jobId);
        var operation = leaseService.beginTakeover(jobId);
        assertTrue(leaseService.takeOver(operation) != null);
        assertTrue(leaseService.takeOver(operation) != null, "期限内の同operation retryは冪等成功する");
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,8,CURRENT_TIMESTAMP(6)) "
                + "WHERE tenant_id=? AND id=?", tenantId, jobId);
        Map<String, Object> before = leaseSnapshot();
        BlockedLease blocked = blockLeaseAtAuthorizationGate(() -> leaseService.takeOver(operation));
        try {
            assertLeaseExpiryHasRemainingMargin(3_000_000L);
            awaitLeaseExpiryByDatabaseClock();
            blocked.blocker().commit();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> blocked.service().get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(409, ((BusinessException) failure.getCause()).getCode());
            assertEquals(before, leaseSnapshot());
        } finally {
            closeBlockedLease(blocked, 409);
        }
    }

    @Test
    void source変更先行はcandidate登録を409で拒否する() throws Exception {
        long runId = prepareValidatedCustomerJob();
        int versionBefore = jobVersion();
        assertMutationWinsBeforeService("t_import_source_system", "SELECT id FROM t_import_source_system WHERE tenant_id=? AND id=? FOR UPDATE",
                new Object[]{tenantId, sourceId},
                "UPDATE t_import_source_system SET status='DISABLED',version_no=version_no+1 WHERE tenant_id=? AND id=?",
                new Object[]{tenantId, sourceId},
                () -> candidateService.registerCandidate(jobId, runId, 1), 409);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry WHERE tenant_id=? AND source_system_id=?",
                Integer.class, tenantId, sourceId));
        assertEquals(versionBefore, jobVersion());
    }

    @Test
    void document変更先行はlease取得を409で拒否する() throws Exception {
        prepareBoundCustomerJob();
        var operation = leaseService.beginAcquire(jobId);
        Map<String, Object> before = leaseSnapshot();
        assertMutationWinsBeforeService("t_document", "SELECT id FROM t_document WHERE tenant_id=? AND id=? FOR UPDATE",
                new Object[]{tenantId, documentId},
                "UPDATE t_document SET legal_hold_flag=1 WHERE tenant_id=? AND id=?",
                new Object[]{tenantId, documentId}, () -> leaseService.acquire(operation), 409);
        assertEquals(before, leaseSnapshot());
    }

    @Test
    void actor変更先行はjob待機後のlease認可を403で拒否する() throws Exception {
        prepareBoundCustomerJob();
        var operation = leaseService.beginAcquire(jobId);
        Map<String, Object> before = leaseSnapshot();
        assertMutationWinsBeforeService("t_import_job", "SELECT id FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE",
                new Object[]{tenantId, jobId},
                "UPDATE sys_user SET status=0 WHERE tenant_id=? AND id=?",
                new Object[]{tenantId, actorUserId}, () -> leaseService.acquire(operation), 403);
        assertEquals(before, leaseSnapshot());
    }

    @Test
    void source変更はcandidateのresourceLockとgate保持中に待機する() throws Exception {
        long runId = prepareValidatedCustomerJob();
        GatePause pause = pauseRealWriteGate();
        assertSourceMutationWaitsForCandidate(pause,
                "UPDATE t_import_source_system SET status='DISABLED',version_no=version_no+1 WHERE tenant_id=? AND id=?",
                new Object[]{tenantId, sourceId},
                () -> candidateService.registerCandidate(jobId, runId, 1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry WHERE tenant_id=? AND source_system_id=?",
                Integer.class, tenantId, sourceId));
        BusinessException drift = assertThrows(BusinessException.class,
                () -> candidateService.registerCandidate(jobId, runId, 1));
        assertEquals(409, drift.getCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry WHERE tenant_id=? AND source_system_id=?",
                Integer.class, tenantId, sourceId));
    }

    @Test
    void document変更はleaseのresourceLockとgate保持中に待機する() throws Exception {
        prepareBoundCustomerJob();
        var operation = leaseService.beginAcquire(jobId);
        GatePause pause = pauseRealWriteGate();
        ImportValidationLeaseService.LeaseGrant grant = (ImportValidationLeaseService.LeaseGrant)
                assertServiceCommitsBeforeMutation(pause, "t_document",
                        "UPDATE t_document SET legal_hold_flag=1 WHERE tenant_id=? AND id=?",
                        new Object[]{tenantId, documentId}, () -> leaseService.acquire(operation));
        Map<String, Object> before = leaseSnapshot();
        BusinessException changed = assertThrows(BusinessException.class, () -> leaseService.renew(grant));
        assertEquals(409, changed.getCode());
        assertEquals(before, leaseSnapshot());
    }

    @Test
    void actor変更はwriteGateのactorLock保持中に待機し後続renewを403にする() throws Exception {
        prepareBoundCustomerJob();
        var operation = leaseService.beginAcquire(jobId);
        GatePause pause = pauseRealWriteGate();
        ImportValidationLeaseService.LeaseGrant grant = (ImportValidationLeaseService.LeaseGrant)
                assertServiceCommitsBeforeMutation(pause, "sys_user",
                        "UPDATE sys_user SET status=0 WHERE tenant_id=? AND id=?",
                        new Object[]{tenantId, actorUserId}, () -> leaseService.acquire(operation));
        Map<String, Object> before = leaseSnapshot();
        BusinessException denied = assertThrows(BusinessException.class, () -> leaseService.renew(grant));
        assertEquals(403, denied.getCode());
        assertEquals(before, leaseSnapshot());
    }

    private void assertMutationWinsBeforeService(String table, String lockSql, Object[] lockParams,
                                                String mutationSql, Object[] mutationParams,
                                                java.util.concurrent.Callable<?> serviceAction,
                                                int expectedCode) throws Exception {
        Connection blocker = dataSource.getConnection();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Long> requesterConnectionId = new AtomicReference<>();
        Throwable primaryFailure = null;
        try {
            blocker.setAutoCommit(false);
            executeQueryForUpdate(blocker, lockSql, lockParams);
            long blockerId = connectionId(blocker);
            captureActualAuthorizationConnection();
            Future<?> submitted = pool.submit(() -> withCapturedAuthorizationConnection(requesterConnectionId, serviceAction));
            if ("t_import_source_system".equals(table)) {
                var blockerHeld = new java.util.concurrent.CompletableFuture<Void>();
                SourceLockWait observed = awaitSourceLockWaitFromBlocker(blockerId, requesterConnectionId,
                        submitted, blockerHeld,
                        table + " mutation-first service");
                logSourceLockWait("source mutation committed before service", observed);
            } else {
                String lockData = permissionLockData(blockerId, table, "PRIMARY");
                awaitExactLockWait(requesterConnectionId, blockerId, table, "PRIMARY", lockData, submitted,
                        table + " mutation-first service");
            }
            executeUpdate(blocker, mutationSql, mutationParams);
            blocker.commit();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> submitted.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException,
                    "SQL lock error/timeoutをdomain拒否として扱わない: " + failure.getCause());
            assertEquals(expectedCode, ((BusinessException) failure.getCause()).getCode());
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try { blocker.rollback(); } catch (Exception failure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(failure); else throw failure;
            }
            try { blocker.close(); } catch (Exception failure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(failure); else throw failure;
            }
            pool.shutdownNow();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                AssertionError cleanupFailure = new AssertionError("mutation-first service workerが終了しませんでした");
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure); else throw cleanupFailure;
            }
        }
    }

    private Object assertServiceCommitsBeforeMutation(GatePause pause, String table, String mutationSql,
                                                     Object[] mutationParams,
                                                     java.util.concurrent.Callable<?> serviceAction) throws Exception {
        return assertServiceCommitsBeforeMutation(pause, table, mutationSql, mutationParams, serviceAction,
                "PRIMARY", null, false);
    }

    private Object assertServiceCommitsBeforeMutation(GatePause pause, String table, String mutationSql,
                                                     Object[] mutationParams,
                                                     java.util.concurrent.Callable<?> serviceAction,
                                                     boolean expectPostCommitDenial) throws Exception {
        return assertServiceCommitsBeforeMutation(pause, table, mutationSql, mutationParams, serviceAction,
                "PRIMARY", null, expectPostCommitDenial);
    }

    private Object assertServiceCommitsBeforeMutation(GatePause pause, String table, String mutationSql,
                                                     Object[] mutationParams,
                                                     java.util.concurrent.Callable<?> serviceAction,
                                                     String expectedLockData) throws Exception {
        return assertServiceCommitsBeforeMutation(pause, table, mutationSql, mutationParams, serviceAction,
                "PRIMARY", expectedLockData, false);
    }

    private Object assertServiceCommitsBeforeMutation(GatePause pause, String table, String mutationSql,
                                                     Object[] mutationParams,
                                                     java.util.concurrent.Callable<?> serviceAction,
                                                     String lockIndex, String expectedLockData) throws Exception {
        return assertServiceCommitsBeforeMutation(pause, table, mutationSql, mutationParams, serviceAction,
                lockIndex, expectedLockData, false);
    }

    private Object assertServiceCommitsBeforeMutation(GatePause pause, String table, String mutationSql,
                                                     Object[] mutationParams,
                                                     java.util.concurrent.Callable<?> serviceAction,
                                                     String lockIndex, String expectedLockData,
                                                     boolean expectPostCommitDenial) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Long> writerId = new AtomicReference<>();
        Set<Future<?>> verifiedExpectedFailures = new java.util.HashSet<>();
        Future<?> service = null;
        Future<?> mutation = null;
        Throwable primaryFailure = null;
        try {
            service = pool.submit(() -> withActorContext(serviceAction));
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS), "実serviceのwrite gateに到達しませんでした");
            assertGateRunsInReadCommittedTransaction(pause);
            String lockData = expectedLockData == null
                    ? permissionLockData(pause.connectionId.get(), table, lockIndex) : expectedLockData;
            mutation = pool.submit(() -> updateAndCommit(mutationSql, mutationParams, writerId));
            awaitTargetedWait(writerId, pause.connectionId, service, mutation,
                    table, lockIndex, "X,REC_NOT_GAP", lockData);
            pause.release.countDown();
            Object result;
            try {
                result = service.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException failure) {
                if (!expectPostCommitDenial || !(failure.getCause() instanceof BusinessException denied)
                        || denied.getCode() != 403) throw failure;
                verifiedExpectedFailures.add(service);
                result = null;
            }
            mutation.get(10, TimeUnit.SECONDS);
            return result;
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            pause.release.countDown();
            reportCleanupFailures("service-first resource fixture", awaitFutures(futures(service, mutation), pool, false,
                    verifiedExpectedFailures),
                    primaryFailure);
        }
    }

    private long assertSourceMutationWaitsForCandidate(GatePause pause, String mutationSql,
                                                       Object[] mutationParams,
                                                       java.util.concurrent.Callable<Long> serviceAction) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Long> writerId = new AtomicReference<>();
        Future<Long> service = null;
        Future<?> mutation = null;
        Throwable primaryFailure = null;
        try {
            service = pool.submit(() -> withActorContext(serviceAction));
            assertTrue(pause.entered.await(10, TimeUnit.SECONDS), "実candidate serviceのwrite gateに到達しませんでした");
            assertGateRunsInReadCommittedTransaction(pause);
            mutation = pool.submit(() -> updateAndCommit(mutationSql, mutationParams, writerId));
            SourceLockWait observed = awaitSourceLockWait(writerId, pause.connectionId, service, mutation);
            logSourceLockWait("source mutation waiting on candidate", observed);
            pause.release.countDown();
            long result = service.get(10, TimeUnit.SECONDS);
            mutation.get(10, TimeUnit.SECONDS);
            return result;
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            pause.release.countDown();
            reportCleanupFailures("candidate/source lock wait fixture",
                    awaitFutures(futures(service, mutation), pool, false), primaryFailure);
        }
    }

    private SourceLockWait awaitSourceLockWait(AtomicReference<Long> requester, AtomicReference<Long> blocker,
                                               Future<?> service, Future<?> mutation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFuturePending(service, "candidate service");
            assertFuturePending(mutation, "source mutation");
            Long requesterId = requester.get(), blockerId = blocker.get();
            if (requesterId != null && blockerId != null) {
                List<SourceLockWait> waits = jdbc.query("""
                        SELECT wt.PROCESSLIST_ID, bt.PROCESSLIST_ID, req.INDEX_NAME, req.LOCK_DATA,
                               req.LOCK_TYPE, req.LOCK_MODE, req.LOCK_STATUS,
                               blk.LOCK_TYPE, blk.LOCK_MODE, blk.LOCK_STATUS
                        FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req
                          ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk
                          ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME='t_import_source_system'
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME='t_import_source_system'
                          AND req.INDEX_NAME=blk.INDEX_NAME AND req.LOCK_DATA=blk.LOCK_DATA
                          AND req.LOCK_TYPE='RECORD' AND req.LOCK_STATUS='WAITING'
                          AND req.LOCK_MODE IN ('X','X,REC_NOT_GAP')
                          AND blk.LOCK_TYPE='RECORD' AND blk.LOCK_STATUS='GRANTED'
                          AND blk.LOCK_MODE IN ('X','X,REC_NOT_GAP')
                        """, (rs, row) -> new SourceLockWait(rs.getLong(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)), requesterId, blockerId);
                for (SourceLockWait wait : waits) {
                    if (isExpectedSourceLock(wait)) return wait;
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("candidate gate保持中のsource行ロック待機が確認できませんでした: "
                + "requester/blocker=" + requester.get() + "/" + blocker.get()
                + "; 許可index=PRIMARY(sourceId),uk_import_source_tenant_id(tenant_id,sourceId)");
    }

    private SourceLockWait awaitSourceLockWaitFromBlocker(long blockerId, AtomicReference<Long> requesterConnectionId,
                                                          Future<?> requester,
                                                          Future<?> blocker, String requesterLabel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFuturePending(blocker, "append service");
            assertFuturePending(requester, requesterLabel);
            List<SourceLockWait> waits = jdbc.query("""
                    SELECT wt.PROCESSLIST_ID, bt.PROCESSLIST_ID, req.INDEX_NAME, req.LOCK_DATA,
                           req.LOCK_TYPE, req.LOCK_MODE, req.LOCK_STATUS,
                           blk.LOCK_TYPE, blk.LOCK_MODE, blk.LOCK_STATUS
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                    JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                    JOIN performance_schema.data_locks req
                      ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.data_locks blk
                      ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                    WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                      AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME='t_import_source_system'
                      AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME='t_import_source_system'
                      AND req.INDEX_NAME=blk.INDEX_NAME AND req.LOCK_DATA=blk.LOCK_DATA
                      AND req.LOCK_TYPE='RECORD' AND req.LOCK_STATUS='WAITING'
                      AND req.LOCK_MODE IN ('X','X,REC_NOT_GAP')
                      AND blk.LOCK_TYPE='RECORD' AND blk.LOCK_STATUS='GRANTED'
                      AND blk.LOCK_MODE IN ('X','X,REC_NOT_GAP')
                    """, (rs, row) -> new SourceLockWait(rs.getLong(1), rs.getLong(2),
                    rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                    rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)),
                    requesterConnectionId.get(), blockerId);
            for (SourceLockWait wait : waits) {
                if (wait.requesterConnectionId() == requesterConnectionId.get() && isExpectedSourceLock(wait)) return wait;
            }
            Thread.sleep(25);
        }
        throw new AssertionError(requesterLabel + "のsource行wait graphを確認できませんでした: requester="
                + requesterConnectionId.get() + ", blocker=" + blockerId
                + "; 許可index=PRIMARY(sourceId),uk_import_source_tenant_id(tenant_id,sourceId)");
    }

    private void logSourceLockWait(String label, SourceLockWait observed) {
        String keyShape = "PRIMARY".equals(observed.indexName()) ? "sourceId" : "(tenant_id,sourceId)";
        System.out.printf("NF06 source行lock wait確認: operation=%s requester=%d blocker=%d index=%s keyShape=%s "
                        + "request=%s/%s/%s blockerLock=%s/%s/%s%n",
                label, observed.requesterConnectionId(), observed.blockerConnectionId(), observed.indexName(), keyShape,
                observed.requestType(), observed.requestMode(), observed.requestStatus(), observed.blockerType(),
                observed.blockerMode(), observed.blockerStatus());
    }

    private boolean isExpectedSourceLock(SourceLockWait wait) {
        if (!"WAITING".equals(wait.requestStatus()) || !"GRANTED".equals(wait.blockerStatus())
                || !"RECORD".equals(wait.requestType()) || !"RECORD".equals(wait.blockerType())
                || !("X".equals(wait.requestMode()) || "X,REC_NOT_GAP".equals(wait.requestMode()))
                || !("X".equals(wait.blockerMode()) || "X,REC_NOT_GAP".equals(wait.blockerMode()))) return false;
        if ("PRIMARY".equals(wait.indexName())) return Long.toString(sourceId).equals(wait.lockData());
        return "uk_import_source_tenant_id".equals(wait.indexName())
                && isSourceTenantIdLockData(wait.lockData());
    }

    private boolean isSourceTenantIdLockData(String lockData) {
        if (lockData == null) return false;
        String[] keyParts = lockData.split(",", -1);
        return keyParts.length == 2 && tenantId.equals(unquoteLockValue(keyParts[0].trim()))
                && Long.toString(sourceId).equals(unquoteLockValue(keyParts[1].trim()));
    }

    private String unquoteLockValue(String value) {
        if (value.length() >= 2 && ((value.startsWith("'") && value.endsWith("'"))
                || (value.startsWith("\"") && value.endsWith("\"")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record SourceLockWait(long requesterConnectionId, long blockerConnectionId, String indexName,
                                  String lockData, String requestType, String requestMode,
                                  String requestStatus, String blockerType, String blockerMode,
                                  String blockerStatus) { }

    private record SourcePrimaryRecordLock(String engine, String lockId, String transactionId,
                                           long connectionId, String schema, String table, String index,
                                           String type, String mode, String status, String lockData,
                                           long sourceId) { }

    private record SourcePrimaryWaitEdge(String engine, long requesterConnectionId, String requesterLockId,
                                         String requesterTransactionId, String requesterSchema,
                                         String requesterTable, String requesterIndex, String requesterType,
                                         String requesterMode, String requesterStatus, String requesterData,
                                         long holderConnectionId, String holderLockId, String holderTransactionId,
                                         String holderSchema, String holderTable, String holderIndex,
                                         String holderType, String holderMode, String holderStatus,
                                         String holderData) { }

    private void updateAndCommit(String sql, Object[] params, AtomicReference<Long> connectionId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connectionId.set(connectionId(connection));
            executeUpdate(connection, sql, params);
            connection.commit();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private void executeQueryForUpdate(Connection connection, String sql, Object[] params) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
        }
    }

    private void executeUpdate(Connection connection, String sql, Object[] params) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void bind(java.sql.PreparedStatement statement, Object[] params) throws Exception {
        for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
    }

    private long connectionId(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private int jobVersion() {
        return jdbc.queryForObject("SELECT version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                Integer.class, tenantId, jobId);
    }

    private String rowSetShaForStoredRows(long runId) {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
        digest.update("SES-IMPORT-ROW-SET-V1".getBytes(StandardCharsets.US_ASCII));
        digest.update(new byte[] {0, 0, 0, 1});
        jdbc.query("SELECT source_row_number,row_commit_sha256 FROM t_import_validation_row "
                        + "WHERE tenant_id=? AND job_id=? AND validation_run_id=? ORDER BY source_row_number",
                rs -> {
                    digest.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(rs.getLong(1)).array());
                    byte[] commitment = rs.getBytes(2);
                    digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(commitment.length).array());
                    digest.update(commitment);
                }, tenantId, jobId, runId);
        return HexFormat.of().formatHex(digest.digest());
    }

    private void assertLeaseExpiryHasRemainingMargin(long minimumMicros) {
        Long remainingMicros = jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,CURRENT_TIMESTAMP(6),"
                + "lease_expires_at) FROM t_import_job WHERE tenant_id=? AND id=?", Long.class, tenantId, jobId);
        assertTrue(remainingMicros != null, "lease expiry intervalが取得できませんでした");
        assertTrue(remainingMicros >= minimumMicros,
                "gate waitを確認した時点ですでに期限切れ、または余裕不足: remainingMicros=" + remainingMicros);
    }

    private void awaitLeaseExpiryByDatabaseClock() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            Integer expired = jdbc.queryForObject("SELECT lease_expires_at<=CURRENT_TIMESTAMP(6) FROM t_import_job "
                    + "WHERE tenant_id=? AND id=?", Integer.class, tenantId, jobId);
            if (Integer.valueOf(1).equals(expired)) return;
            Thread.sleep(25);
        }
        throw new AssertionError("gateを保持したままDB時刻によるlease期限到達を確認できませんでした");
    }

    private BlockedLease blockLeaseAtAuthorizationGate(
            java.util.concurrent.Callable<?> operation) throws Exception {
        Connection blocker = null;
        ExecutorService pool = null;
        Future<?> service = null;
        boolean transferred = false;
        Throwable primaryFailure = null;
        try {
            blocker = dataSource.getConnection();
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement(
                    "SELECT id FROM m_permission_group WHERE tenant_id=? AND id=? FOR UPDATE")) {
                statement.setString(1, tenantId);
                statement.setLong(2, groupId);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            long blockerId = connectionId(blocker);
            pool = Executors.newSingleThreadExecutor();
            service = pool.submit(() -> withActorContext(operation));
            String lockData = permissionLockData(blockerId, "m_permission_group", "PRIMARY");
            awaitExactLockWait(blockerId, "m_permission_group", "PRIMARY", lockData, service,
                    "validation lease authorization gate");
            BlockedLease result = new BlockedLease(blocker, pool, service);
            transferred = true;
            return result;
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!transferred) {
                List<Throwable> cleanup = new java.util.ArrayList<>();
                if (blocker != null) {
                    try { blocker.rollback(); } catch (Exception | Error failure) { cleanup.add(failure); }
                    try { blocker.close(); } catch (Exception | Error failure) { cleanup.add(failure); }
                }
                if (pool != null) {
                    try {
                        pool.shutdownNow();
                        if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                            cleanup.add(new AssertionError("lease gate worker cleanup timeout"));
                        }
                    } catch (Exception | Error interrupted) {
                        if (interrupted instanceof InterruptedException) Thread.currentThread().interrupt();
                        cleanup.add(interrupted);
                    }
                }
                if (service != null) {
                    try { service.get(0, TimeUnit.NANOSECONDS); }
                    catch (java.util.concurrent.ExecutionException failure) {
                        cleanup.add(failure.getCause() == null ? failure : failure.getCause());
                    } catch (java.util.concurrent.TimeoutException timeout) { cleanup.add(timeout); }
                    catch (java.util.concurrent.CancellationException cancelled) { cleanup.add(cancelled); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        cleanup.add(interrupted);
                    }
                }
                if (primaryFailure != null) {
                    Throwable original = primaryFailure;
                    cleanup.stream().filter(failure -> failure != original).forEach(original::addSuppressed);
                }
                else reportCleanupFailures("lease gate setup fixture", cleanup, null);
            }
        }
    }

    private void closeBlockedLease(BlockedLease blocked, int expectedCode) throws Exception {
        try { blocked.blocker().rollback(); } finally {
            try { blocked.blocker().close(); } finally {
                blocked.pool().shutdownNow();
                assertTrue(blocked.pool().awaitTermination(5, TimeUnit.SECONDS), "lease gate workerが終了しませんでした");
                try {
                    blocked.service().get(0, TimeUnit.NANOSECONDS);
                    assertEquals(0, expectedCode);
                } catch (java.util.concurrent.ExecutionException failure) {
                    assertTrue(failure.getCause() instanceof BusinessException);
                    assertEquals(expectedCode, ((BusinessException) failure.getCause()).getCode());
                }
            }
        }
    }

    private record BlockedLease(Connection blocker, ExecutorService pool, Future<?> service) { }

    private GatePause pauseRealWriteGate() {
        return pauseRealWriteGateAt(1);
    }

    private GatePause pauseRealWriteGateAt(int invocationToPause) {
        GatePause pause = new GatePause();
        AtomicInteger invocationCount = new AtomicInteger();
        doAnswer(invocation -> {
            ImportAuthorizedContext authorized = (ImportAuthorizedContext) invocation.callRealMethod();
            if (invocationCount.incrementAndGet() == invocationToPause) {
                pause.connectionId.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                pause.transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                pause.isolation.set(jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
                pause.entered.countDown();
                if (!pause.release.await(10, TimeUnit.SECONDS)) throw new AssertionError("実service gate barrier timeout");
            }
            return authorized;
        }).when(metadataAuthorization).requireCurrentAuthorizationForWrite(any());
        return pause;
    }

    private void assertGateRunsInReadCommittedTransaction(GatePause pause) {
        assertTrue(pause.transactionActive.get(), "write gateは実service transaction内で呼び出します");
        assertEquals("READ-COMMITTED", pause.isolation.get());
        assertTrue(pause.connectionId.get() != null);
    }

    private void insertDenyActionAndWaitForCommit(AtomicReference<Long> writerId, CountDownLatch commit) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rows.next(); writerId.set(rows.getLong(1));
            }
            try (var statement = connection.prepareStatement("INSERT INTO t_permission_group_action "
                    + "(tenant_id,group_id,action_key,deny_flag,deleted_flag) VALUES(?,?,'import.*',1,0)")) {
                statement.setString(1, tenantId); statement.setLong(2, groupId); statement.executeUpdate();
            }
            if (!commit.await(10, TimeUnit.SECONDS)) throw new AssertionError("deny insert commit barrier timeout");
            connection.commit();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private void insertMembershipAndWaitForCommit(long targetGroup, AtomicReference<Long> writerId, CountDownLatch commit) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rows.next(); writerId.set(rows.getLong(1));
            }
            try (var statement = connection.prepareStatement("INSERT INTO t_user_permission_group "
                    + "(tenant_id,user_id,group_id,assigned_by,deleted_flag) VALUES(?,?,?, ?,0)")) {
                statement.setString(1, tenantId); statement.setLong(2, actorUserId);
                statement.setLong(3, targetGroup); statement.setLong(4, actorUserId); statement.executeUpdate();
            }
            if (!commit.await(10, TimeUnit.SECONDS)) throw new AssertionError("membership insert commit barrier timeout");
            connection.commit();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private void awaitPermissionActionWait(AtomicReference<Long> requester, AtomicReference<Long> blocker,
                                           Future<?> service, Future<?> mutation) throws Exception {
        awaitTargetedWait(requester, blocker, service, mutation, "t_permission_group_action", "uk_permission_group_action",
                "X,GAP,INSERT_INTENTION", null);
    }

    private void awaitMembershipWait(AtomicReference<Long> requester, AtomicReference<Long> blocker,
                                     Future<?> service, Future<?> mutation) throws Exception {
        // 旧metadata service用のRR gap/insert-intention lock検査は既存fixture向けに維持する。
        awaitTargetedWait(requester, blocker, service, mutation, "t_user_permission_group", "uk_user_permission_group",
                "X,INSERT_INTENTION", "supremum pseudo-record");
    }

    private void awaitForeignKeyParentWait(AtomicReference<Long> requester, AtomicReference<Long> blocker,
                                           Future<?> service, Future<?> mutation,
                                           String parentTable, long parentId) throws Exception {
        String expectedKey = Long.toString(parentId);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFuturePending(service, "実metadata service");
            assertFuturePending(mutation, "permission FK child insert");
            Long requesterId = requester.get(), blockerId = blocker.get();
            if (requesterId != null && blockerId != null) {
                Integer found = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req
                          ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk
                          ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME=? AND req.INDEX_NAME='PRIMARY'
                          AND req.LOCK_TYPE='RECORD' AND req.LOCK_MODE='S,REC_NOT_GAP'
                          AND req.LOCK_STATUS='WAITING' AND req.LOCK_DATA=?
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME=? AND blk.INDEX_NAME='PRIMARY'
                          AND blk.LOCK_TYPE='RECORD' AND blk.LOCK_MODE='X,REC_NOT_GAP'
                          AND blk.LOCK_STATUS='GRANTED' AND blk.LOCK_DATA=?
                        """, Integer.class, requesterId, blockerId, parentTable, expectedKey,
                        parentTable, expectedKey);
                if (found != null && found > 0) return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("READ COMMITTED FK parent lock waitを確認できませんでした: parent="
                + parentTable + " PRIMARY " + expectedKey + "; "
                + safeWaitDetails(requester.get(), blocker.get()));
    }

    private void awaitTargetedWait(AtomicReference<Long> requester, AtomicReference<Long> blocker,
                                   Future<?> service, Future<?> mutation, String table, String index,
                                   String requestMode, String lockData) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFuturePending(service, "実metadata service");
            assertFuturePending(mutation, "permission mutation");
            Long requestId = requester.get(), blockerId = blocker.get();
            if (requestId != null && blockerId != null) {
                Integer found = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME=? AND req.INDEX_NAME=?
                          AND req.LOCK_TYPE='RECORD' AND req.LOCK_MODE=? AND req.LOCK_STATUS='WAITING'
                          AND (? IS NULL OR req.LOCK_DATA=?)
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME=? AND blk.INDEX_NAME=?
                          AND blk.LOCK_TYPE='RECORD' AND blk.LOCK_STATUS='GRANTED'
                          AND blk.LOCK_MODE IN ('X','X,REC_NOT_GAP')
                        """, Integer.class, requestId, blockerId, table, index, requestMode, lockData, lockData,
                        table, index);
                if (found != null && found > 0) return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("実service gateに対する指定permission lock waitを確認できませんでした: "
                + safeWaitDetails(requester.get(), blocker.get()));
    }

    private void awaitValidationRowWait(AtomicReference<Long> requester, AtomicReference<Long> blocker,
                                       Future<?> append, long rowNumber, long runId, long rowId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFuturePending(append, "REQUIRES_NEW append row lock待機");
            Long requesterId = requester.get(), blockerId = blocker.get();
            if (requesterId != null && blockerId != null) {
                List<String> keys = jdbc.query("""
                        SELECT req.LOCK_DATA
                        FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req
                          ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk
                          ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME='t_import_validation_row'
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME='t_import_validation_row'
                          AND req.INDEX_NAME='uk_import_row_run_number' AND blk.INDEX_NAME=req.INDEX_NAME
                          AND req.LOCK_DATA=blk.LOCK_DATA
                          AND req.LOCK_TYPE='RECORD' AND req.LOCK_STATUS='WAITING'
                          AND req.LOCK_MODE IN ('X','X,REC_NOT_GAP')
                          AND blk.LOCK_TYPE='RECORD' AND blk.LOCK_STATUS='GRANTED'
                          AND blk.LOCK_MODE IN ('X','X,REC_NOT_GAP')
                        """, (rs, row) -> rs.getString(1), requesterId, blockerId);
                for (String key : keys) {
                    if (isValidationRowLockData(key, runId, rowNumber, rowId)) {
                        System.out.printf("NF06 row lock wait確認: requester=%d blocker=%d schema=DATABASE() table=t_import_validation_row index=uk_import_row_run_number keyShape=(tenantId,runId,rowNumber[,primaryId]) mode=record-X WAITING/GRANTED%n",
                                requesterId, blockerId);
                        return;
                    }
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("実REQUIRES_NEW transactionの指定validation row lock waitを確認できませんでした: "
                + safeWaitDetails(requester.get(), blocker.get()));
    }

    private boolean isValidationRowLockData(String lockData, long runId, long rowNumber, long rowId) {
        if (lockData == null) return false;
        String[] parts = lockData.split(",", -1);
        if (parts.length != 3 && parts.length != 4) return false;
        if (!tenantId.equals(unquoteLockValue(parts[0].trim()))
                || !Long.toString(runId).equals(unquoteLockValue(parts[1].trim()))
                || !Long.toString(rowNumber).equals(unquoteLockValue(parts[2].trim()))) return false;
        return parts.length == 3 || Long.toString(rowId).equals(unquoteLockValue(parts[3].trim()));
    }

    private List<Future<?>> futures(Future<?>... values) {
        List<Future<?>> result = new java.util.ArrayList<>();
        for (Future<?> value : values) if (value != null) result.add(value);
        return result;
    }

    private static final class GatePause {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<Long> connectionId = new AtomicReference<>();
        private final AtomicReference<String> isolation = new AtomicReference<>();
        private final AtomicBoolean transactionActive = new AtomicBoolean();
    }

    private void assertRevokedWhileRegistryLockHeld(long validationRunId, byte[] lookupDigest) throws Exception {
        Connection blocker = null;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> operationFuture = null;
        AtomicReference<Long> blockerId = new AtomicReference<>();
        Throwable primaryFailure = null;
        boolean expectedDenialObserved = false;
        try {
            blocker = dataSource.getConnection();
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM t_import_identity_registry WHERE tenant_id=? "
                    + "AND source_system_id=? AND entity_type='CUSTOMER' AND lookup_digest=? FOR UPDATE")) {
                statement.setString(1, tenantId);
                statement.setLong(2, sourceId);
                statement.setBytes(3, lookupDigest);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            try (var statement = blocker.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rows.next();
                blockerId.set(rows.getLong(1));
            }
            String registryLockData = permissionLockData(blockerId.get(), "t_import_identity_registry",
                    "uk_import_identity_namespace");
            Future<?> submitted = pool.submit(() -> withActorContext(() ->
                    candidateService.registerCandidate(jobId, validationRunId, 1)));
            operationFuture = submitted;
            long requester = awaitExactLockWait(blockerId.get(), "t_import_identity_registry",
                    "uk_import_identity_namespace", registryLockData, submitted, "candidate registry");
            assertEquals(1, jdbc.update("UPDATE t_permission_group_action SET deleted_flag=1 WHERE tenant_id=? "
                            + "AND group_id=? AND action_key=? AND deny_flag=0",
                    tenantId, groupId, ImportMetadataAuthorizationService.ACTION));
            blocker.commit();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> submitted.get(12, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException,
                    "SQL lock error/timeoutを403として扱わない: " + failure.getCause());
            assertEquals(403, ((BusinessException) failure.getCause()).getCode());
            expectedDenialObserved = true;
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (blocker != null) {
                try { blocker.rollback(); } catch (Exception failure) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(failure); else throw failure;
                }
                try { blocker.close(); } catch (Exception failure) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(failure); else throw failure;
                }
            }
            reportCleanupFailures("candidate registry撤権競合fixture",
                    awaitFutures(operationFuture == null ? List.of() : List.of(operationFuture), pool, expectedDenialObserved),
                    primaryFailure);
        }
    }

    private void assertRevokedWhileJobLockHeld(Supplier<?> operation) throws Exception {
        Connection blocker = null;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> operationFuture = null;
        AtomicReference<Long> blockerId = new AtomicReference<>();
        Throwable primaryFailure = null;
        boolean expectedDenialObserved = false;
        try {
            blocker = dataSource.getConnection();
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE")) {
                statement.setString(1, tenantId);
                statement.setLong(2, jobId);
                try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            try (var statement = blocker.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rows.next();
                blockerId.set(rows.getLong(1));
            }
            String jobLockData = permissionLockData(blockerId.get(), "t_import_job", "PRIMARY");
            Future<?> submitted = pool.submit(() -> withActorContext(operation::get));
            operationFuture = submitted;
            awaitExactLockWait(blockerId.get(), "t_import_job", "PRIMARY", jobLockData, submitted, "validation lease job");
            assertEquals(1, jdbc.update("UPDATE t_permission_group_action SET deleted_flag=1 WHERE tenant_id=? "
                            + "AND group_id=? AND action_key=? AND deny_flag=0",
                    tenantId, groupId, ImportMetadataAuthorizationService.ACTION));
            blocker.commit();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> submitted.get(12, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException,
                    "SQL lock error/timeoutを403として扱わない: " + failure.getCause());
            assertEquals(403, ((BusinessException) failure.getCause()).getCode());
            expectedDenialObserved = true;
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (blocker != null) {
                try { blocker.rollback(); } catch (Exception failure) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(failure); else throw failure;
                }
                try { blocker.close(); } catch (Exception failure) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(failure); else throw failure;
                }
            }
            reportCleanupFailures("lease job撤権競合fixture",
                    awaitFutures(operationFuture == null ? List.of() : List.of(operationFuture), pool, expectedDenialObserved),
                    primaryFailure);
        }
    }

    private String permissionLockData(long connectionId, String table, String index) {
        return jdbc.queryForObject("SELECT dl.LOCK_DATA FROM performance_schema.data_locks dl "
                        + "JOIN performance_schema.threads t ON t.THREAD_ID=dl.THREAD_ID "
                        + "WHERE t.PROCESSLIST_ID=? AND dl.OBJECT_SCHEMA=DATABASE() AND dl.OBJECT_NAME=? "
                        + "AND dl.INDEX_NAME=? AND dl.LOCK_TYPE='RECORD' AND dl.LOCK_STATUS='GRANTED' "
                        + "AND dl.LOCK_DATA IS NOT NULL ORDER BY dl.LOCK_MODE LIMIT 1",
                String.class, connectionId, table, index);
    }

    private long awaitExactLockWait(long blockerId, String table, String index, String lockData,
                                   Future<?> operation, String label) throws Exception {
        return awaitExactLockWait(null, blockerId, table, index, lockData, operation, label);
    }

    private long awaitExactLockWait(AtomicReference<Long> expectedRequesterId, long blockerId, String table,
                                   String index, String lockData, Future<?> operation, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            assertFuturePending(operation, label);
            Long capturedRequesterId = expectedRequesterId == null ? null : expectedRequesterId.get();
            long requesterFilter = capturedRequesterId == null ? 0L : capturedRequesterId;
            List<Long> requesters = capturedRequesterId == null && expectedRequesterId != null ? List.of() : jdbc.query("""
                    SELECT wt.PROCESSLIST_ID
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                    JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                    JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                    WHERE (?=0 OR wt.PROCESSLIST_ID=?) AND wt.PROCESSLIST_ID<>? AND bt.PROCESSLIST_ID=?
                      AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME=? AND req.INDEX_NAME=?
                      AND req.LOCK_TYPE='RECORD' AND req.LOCK_MODE='X,REC_NOT_GAP' AND req.LOCK_STATUS='WAITING'
                      AND req.LOCK_DATA=?
                      AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME=? AND blk.INDEX_NAME=?
                      AND blk.LOCK_TYPE='RECORD' AND blk.LOCK_MODE='X,REC_NOT_GAP' AND blk.LOCK_STATUS='GRANTED'
                      AND blk.LOCK_DATA=?
                    """, (rs, row) -> rs.getLong(1), requesterFilter, requesterFilter,
                    blockerId, blockerId, table, index, lockData,
                    table, index, lockData);
            if (!requesters.isEmpty()) return requesters.get(0);
            Thread.sleep(25);
        }
        assertFuturePending(operation, label);
        throw new AssertionError(label + "の対象record X lock waitを確認できませんでした: "
                + safeWaitDetails(null, blockerId));
    }

    private <T> T withActorContext(java.util.concurrent.Callable<T> operation) {
        var authority = new SimpleGrantedAuthority("ROLE_管理者");
        installPrincipal(tenantId, authority);
        AccountingTenantContextHolder.setTenantId(tenantId);
        try { return operation.call(); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        finally {
            SecurityContextHolder.clearContext();
            AccountingTenantContextHolder.clear();
        }
    }

    private void assertRevokedWhileSourceLockHeld(Supplier<?> operation) throws Exception {
        Connection blocker = null;
        ExecutorService pool = null;
        Future<?> operationFuture = null;
        AtomicReference<Long> requesterId = new AtomicReference<>();
        Throwable primaryFailure = null;
        boolean expectedDenialObserved = false;
        try {
            blocker = dataSource.getConnection();
            blocker.setAutoCommit(false);
            SourcePrimaryRecordLock holderLock = lockExactSourcePrimaryRow(blocker, sourceId);

            pool = Executors.newSingleThreadExecutor();
            Future<?> submittedOperation = pool.submit(() -> {
                var authority = new SimpleGrantedAuthority("ROLE_管理者");
                installPrincipal(tenantId, authority);
                AccountingTenantContextHolder.setTenantId(tenantId);
                try {
                    return runSourceWaitWorker(requesterId, operation);
                } finally {
                    SecurityContextHolder.clearContext();
                    AccountingTenantContextHolder.clear();
                }
            });
            operationFuture = submittedOperation;
            awaitSourcePrimaryWait(requesterId, holderLock, sourceId, submittedOperation);
            assertEquals(1, jdbc.update("UPDATE t_permission_group_action SET deleted_flag=1 "
                            + "WHERE tenant_id=? AND group_id=? AND action_key=? AND deny_flag=0",
                    tenantId, groupId, ImportMetadataAuthorizationService.ACTION));
            blocker.commit();
            var failure = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> submittedOperation.get(SOURCE_WAIT_FUTURE_SECONDS, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BusinessException);
            assertEquals(403, ((BusinessException) failure.getCause()).getCode());
            expectedDenialObserved = true;
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            List<Throwable> cleanupFailures = new java.util.ArrayList<>();
            if (!expectedDenialObserved && blocker != null) {
                try {
                    jdbc.update("UPDATE t_permission_group_action SET deleted_flag=1 "
                                    + "WHERE tenant_id=? AND group_id=? AND action_key=? AND deny_flag=0",
                            tenantId, groupId, ImportMetadataAuthorizationService.ACTION);
                } catch (Exception failure) { cleanupFailures.add(failure); }
            }
            if (blocker != null) {
                try { blocker.rollback(); } catch (Exception failure) { cleanupFailures.add(failure); }
                try { blocker.close(); } catch (Exception failure) { cleanupFailures.add(failure); }
            }
            if (pool != null) {
                cleanupFailures.addAll(awaitSourceWorkers(operationFuture == null ? List.of() : List.of(operationFuture),
                        pool, requesterId, true));
            }
            reportCleanupFailures("metadata service source lock競合fixture", cleanupFailures, primaryFailure);
        }
    }

    private ImportMappingRequest customerMappingRequest() {
        return new ImportMappingRequest(sourceId, ImportEntity.CUSTOMER, "ses-import-v1",
                Map.of("externalKey", "externalKey", "companyName", "companyName"));
    }

    private ImportJobRegistrationRequest customerJobRequest() {
        return new ImportJobRegistrationRequest(sourceId, ImportEntity.CUSTOMER, "ses-import-v1",
                ImportEncoding.UTF8, SOURCE_HASH, documentId, documentVersionId, 1);
    }

    private void prepareUnboundCustomerJob() {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        mappingId = metadataService.createMapping(customerMappingRequest());
        jobId = metadataService.registerJob(customerJobRequest());
    }

    private void prepareBoundCustomerJob() {
        prepareUnboundCustomerJob();
        assertEquals(2L, metadataService.bindMapping(jobId, 1, mappingId));
    }

    private long prepareValidatedCustomerJob() {
        prepareBoundCustomerJob();
        byte[] digest = HexFormat.of().parseHex(SOURCE_HASH);
        jdbc.update("""
                INSERT INTO t_import_validation_run(tenant_id,job_id,run_no,state,created_lease_generation,
                  last_resumed_lease_generation,active_slot,actor_user_id,source_system_id,source_version,
                  source_code_snapshot,entity_type,schema_version,source_encoding,source_sha256,document_id,
                  document_version_id,document_version_no,document_sha256,mapping_id,mapping_version,
                  mapping_sha256,validation_fingerprint)
                VALUES(?,?,1,'BUILDING',0,0,1,?,?,1,?,'CUSTOMER','ses-import-v1','UTF8',?,?,?,?,?,?,1,?,?)
                """, tenantId, jobId, actorUserId, sourceId,
                jdbc.queryForObject("SELECT source_code FROM t_import_source_system WHERE tenant_id=? AND id=?",
                        String.class, tenantId, sourceId), digest,
                documentId, documentVersionId, 1, digest, mappingId,
                jdbc.queryForObject("SELECT mapping_sha256 FROM t_import_mapping WHERE tenant_id=? AND id=?",
                        byte[].class, tenantId, mappingId), digest);
        long runId = jdbc.queryForObject("SELECT id FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND run_no=1",
                Long.class, tenantId, jobId);
        StringBuilder complete = new StringBuilder("UPDATE t_import_validation_run SET state='COMPLETED',active_slot=NULL,"
                + "published_slot=1,source_row_count=1,physical_row_count=1,empty_row_count=0,accepted_row_count=1,"
                + "rejected_row_count=0,finding_summary_bytes=?,finding_summary_sha256=?,amount_excluded_reason_counts=?,"
                + "amount_excluded_reason_sha256=?,row_set_sha256=?,summary_sha256=?");
        for (String field : List.of("expected_unit_price", "unit_price_min", "unit_price_max", "selling_price", "cost_price")) {
            complete.append(',').append(field).append("_accepted_known_count=0,").append(field)
                    .append("_accepted_sum_yen=NULL,").append(field).append("_rejected_known_count=0,")
                    .append(field).append("_rejected_sum_yen=NULL,").append(field).append("_excluded_count=0");
        }
        complete.append(" WHERE tenant_id=? AND job_id=? AND id=?");
        jdbc.update(complete.toString(), new byte[]{0}, new byte[32], new byte[]{0}, new byte[32],
                new byte[32], new byte[32], tenantId, jobId, runId);
        String rowHash = candidateRowHash(List.of("customer-1", "Example"));
        var sourceRow = new SourceRow(1, rowHash,
                Map.of("externalKey", "customer-1", "companyName", "Example"));
        byte[] fingerprint = CanonicalImportRowFingerprint.sha256(
                CanonicalRowFactory.create(ImportEntity.CUSTOMER, sourceRow));
        jdbc.update("""
                INSERT INTO t_import_validation_row(tenant_id,job_id,validation_run_id,source_row_number,
                  source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256)
                VALUES(?,?,?,1,?,?,'ACCEPTED',?,?)
                """, tenantId, jobId, runId, HexFormat.of().parseHex(rowHash), fingerprint,
                new byte[]{0}, new byte[32]);
        jdbc.update("UPDATE t_import_job SET state='VALIDATED',current_validation_run_id=?,version_no=version_no+1 "
                + "WHERE tenant_id=? AND id=? AND state='MAPPED'", runId, tenantId, jobId);
        return runId;
    }

    private String candidateRowHash(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Map<String, Object> leaseSnapshot() {
        return jdbc.queryForMap("SELECT state,version_no,lease_owner,lease_generation,lease_expires_at,"
                + "active_validation_run_id,current_validation_run_id FROM t_import_job WHERE tenant_id=? AND id=?",
                tenantId, jobId);
    }

    private void assertCandidateRegistrySnapshotEquals(Map<String, Object> expected, Map<String, Object> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String column : expected.keySet()) {
            Object expectedValue = expected.get(column);
            Object actualValue = actual.get(column);
            if (expectedValue instanceof byte[] expectedBytes) {
                assertArrayEquals(expectedBytes, (byte[]) actualValue, column);
            } else {
                assertEquals(expectedValue, actualValue, column);
            }
        }
    }

    private int mappingCountForSource() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_import_mapping WHERE tenant_id=? AND source_system_id=?",
                Integer.class, tenantId, sourceId);
    }

    private void assertJobBinding(String expectedState, int expectedVersion, Long expectedMappingId) {
        var actual = jdbc.queryForMap("SELECT state, version_no, mapping_id FROM t_import_job WHERE tenant_id=? AND id=?",
                tenantId, jobId);
        assertEquals(expectedState, actual.get("state"));
        assertEquals((long) expectedVersion, ((Number) actual.get("version_no")).longValue());
        Object actualMappingId = actual.get("mapping_id");
        if (expectedMappingId == null) assertTrue(actualMappingId == null);
        else assertEquals(expectedMappingId.longValue(), ((Number) actualMappingId).longValue());
    }

    private void assertSourceRemainsActiveAtVersionOne() {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_source_system "
                + "WHERE tenant_id=? AND id=? AND status='ACTIVE' AND version_no=1",
                Integer.class, tenantId, sourceId));
    }

    @Test
    void gate取得後の新規deny行insertはcommitまで待ち次回metadata操作を拒否する() throws Exception {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        String foreignTenant = tenantId + "-foreign";
        jdbc.update("INSERT INTO m_permission_group(tenant_id,group_key,group_name,enabled,deleted_flag) VALUES(?,?,?,1,0)",
                foreignTenant, "nf06-cross-tenant", "tenant境界テスト用");
        foreignGroupId = jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id=? AND group_key=?",
                Long.class, foreignTenant, "nf06-cross-tenant");
        ImportAuthorizedContext context = metadataAuthorization.requireCurrentAuthorization();
        var gateHeld = new java.util.concurrent.CountDownLatch(1);
        var releaseGate = new java.util.concurrent.CountDownLatch(1);
        var gateConnectionId = new AtomicReference<Long>();
        var pool = Executors.newFixedThreadPool(3);
        Future<?> gate = pool.submit(() -> {
            var authority = new SimpleGrantedAuthority("ROLE_管理者");
            installPrincipal(tenantId, authority);
            AccountingTenantContextHolder.setTenantId(tenantId);
            try {
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)).execute(status -> {
                    gateConnectionId.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    metadataAuthorization.requireCurrentAuthorizationForWrite(context);
                    gateHeld.countDown();
                    try {
                        if (!releaseGate.await(30, TimeUnit.SECONDS)) throw new AssertionError("gate解放待ちがtimeoutしました");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return null;
                });
            } finally {
                SecurityContextHolder.clearContext();
                AccountingTenantContextHolder.clear();
            }
        });
        var writerId = new AtomicReference<Long>();
        Future<?> denyInsert = null;
        Future<?> membershipInsert = null;
        Throwable primaryFailure = null;
        try {
            assertTrue(gateHeld.await(5, TimeUnit.SECONDS), "認可gateを取得できませんでした");
            denyInsert = pool.submit(() -> {
            try (Connection connection = dataSource.getConnection()) {
                try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    rows.next(); writerId.set(rows.getLong(1));
                }
                try (var statement = connection.prepareStatement("""
                        INSERT INTO t_permission_group_action(tenant_id,group_id,action_key,deny_flag,deleted_flag)
                        VALUES(?,?,'import.*',1,0)
                        """)) {
                    statement.setString(1, tenantId);
                    statement.setLong(2, groupId);
                    statement.executeUpdate();
                }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        var membershipWriterId = new AtomicReference<Long>();
        membershipInsert = pool.submit(() -> {
            try (Connection connection = dataSource.getConnection()) {
                try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    rows.next(); membershipWriterId.set(rows.getLong(1));
                }
                try (var statement = connection.prepareStatement("""
                        INSERT INTO t_user_permission_group(tenant_id,user_id,group_id,assigned_by,deleted_flag)
                        VALUES(?,?,?,?,0)
                        """)) {
                    statement.setString(1, tenantId);
                    statement.setLong(2, actorUserId);
                    statement.setLong(3, foreignGroupId);
                    statement.setLong(4, actorUserId);
                    statement.executeUpdate();
                }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            awaitPermissionActionUniqueIndexWait(writerId, gateConnectionId, gate, denyInsert, membershipInsert);
            awaitMembershipUniqueIndexWait(membershipWriterId, gateConnectionId, gate, denyInsert, membershipInsert);
            assertTrue(!denyInsert.isDone(), "permission groupを参照する新規deny行がgate commit前に挿入されました");
            assertTrue(!membershipInsert.isDone(), "sys_userを参照する新規membershipがgate commit前に挿入されました");
            releaseGate.countDown();
            gate.get(10, TimeUnit.SECONDS);
            denyInsert.get(10, TimeUnit.SECONDS);
            membershipInsert.get(10, TimeUnit.SECONDS);
            BusinessException denied = assertThrows(BusinessException.class,
                    () -> metadataService.disableSource(sourceId, 1, false));
            assertEquals(403, denied.getCode());
            assertEquals(1, jdbc.queryForObject("SELECT version_no FROM t_import_source_system WHERE tenant_id=? AND id=?",
                    Integer.class, tenantId, sourceId));
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            releaseGate.countDown();
            List<Future<?>> pending = new java.util.ArrayList<>();
            pending.add(gate);
            if (denyInsert != null) pending.add(denyInsert);
            if (membershipInsert != null) pending.add(membershipInsert);
            reportCleanupFailures("認可gate lock競合fixture", awaitFutures(pending, pool, false), primaryFailure);
        }
    }

    private void awaitMembershipUniqueIndexWait(AtomicReference<Long> requesterId,
                                                AtomicReference<Long> blockerId, Future<?> gate,
                                                Future<?> denyInsert, Future<?> membershipInsert) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertContentionTasksPending(gate, denyInsert, membershipInsert);
            Long requester = requesterId.get();
            Long blocker = blockerId.get();
            if (requester != null && blocker != null) {
                Integer waits = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME='t_user_permission_group'
                          AND req.INDEX_NAME='uk_user_permission_group' AND req.LOCK_TYPE='RECORD'
                          AND req.LOCK_MODE='X,INSERT_INTENTION' AND req.LOCK_STATUS='WAITING'
                          AND req.LOCK_DATA='supremum pseudo-record'
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME='t_user_permission_group'
                          AND blk.INDEX_NAME='uk_user_permission_group' AND blk.LOCK_TYPE='RECORD'
                          AND blk.LOCK_MODE='X' AND blk.LOCK_STATUS='GRANTED'
                          AND blk.LOCK_DATA='supremum pseudo-record'
                        """, Integer.class, requester, blocker);
                if (waits != null && waits > 0) return;
            }
            Thread.sleep(25);
        }
        assertContentionTasksPending(gate, denyInsert, membershipInsert);
        throw new AssertionError("新規membershipのunique index supremum lock waitを確認できませんでした。" +
                safeWaitDetails(requesterId.get(), blockerId.get()));
    }

    private void awaitPermissionActionUniqueIndexWait(AtomicReference<Long> requesterId,
                                                      AtomicReference<Long> blockerId, Future<?> gate,
                                                      Future<?> denyInsert, Future<?> membershipInsert) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertContentionTasksPending(gate, denyInsert, membershipInsert);
            Long requester = requesterId.get();
            Long blocker = blockerId.get();
            if (requester != null && blocker != null) {
                Integer waits = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME='t_permission_group_action'
                          AND req.INDEX_NAME='uk_permission_group_action' AND req.LOCK_TYPE='RECORD'
                          AND req.LOCK_MODE='X,GAP,INSERT_INTENTION' AND req.LOCK_STATUS='WAITING'
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME='t_permission_group_action'
                          AND blk.INDEX_NAME='uk_permission_group_action' AND blk.LOCK_TYPE='RECORD'
                          AND blk.LOCK_MODE='X' AND blk.LOCK_STATUS='GRANTED'
                        """, Integer.class, requester, blocker);
                if (waits != null && waits > 0) return;
            }
            Thread.sleep(25);
        }
        assertContentionTasksPending(gate, denyInsert, membershipInsert);
        throw new AssertionError("新規deny actionのunique index lock waitを確認できませんでした。" +
                safeWaitDetails(requesterId.get(), blockerId.get()));
    }

    private SourcePrimaryRecordLock lockExactSourcePrimaryRow(Connection holder, long expectedSourceId) throws Exception {
        long holderConnectionId;
        try (var statement = holder.createStatement(); ResultSet rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertTrue(rows.next());
            holderConnectionId = rows.getLong(1);
        }
        try (var statement = holder.prepareStatement("SELECT id FROM t_import_source_system WHERE tenant_id=? AND id=? FOR UPDATE")) {
            statement.setString(1, tenantId);
            statement.setLong(2, expectedSourceId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "holderがfixture sourceのPRIMARY行を取得します");
                assertEquals(expectedSourceId, rows.getLong(1));
                assertTrue(!rows.next(), "tenantとPK指定のholder lock queryは一行だけ返します");
            }
        }
        List<SourcePrimaryRecordLock> records = sourcePrimaryRecordLocks(holderConnectionId);
        if (records.size() != 1) {
            throw new AssertionError("holderの対象schema/table/PRIMARYにあるRECORD lockは一件である必要があります: " + records);
        }
        SourcePrimaryRecordLock lock = records.get(0);
        if (lock.engine() == null || lock.lockId() == null || lock.transactionId() == null
                || lock.connectionId() != holderConnectionId || lock.sourceId() != expectedSourceId
                || !isSourcePrimaryRecordLock(lock, expectedSourceId, "GRANTED")) {
            throw new AssertionError("holderが対象source PRIMARYの非gap X lockを保持していません: " + lock);
        }
        return lock;
    }

    private List<SourcePrimaryRecordLock> sourcePrimaryRecordLocks(long connectionId) {
        return withSourceWaitObserverBudget(() -> jdbc.query("""
                SELECT dl.ENGINE,dl.ENGINE_LOCK_ID,dl.ENGINE_TRANSACTION_ID,th.PROCESSLIST_ID,
                       dl.OBJECT_SCHEMA,dl.OBJECT_NAME,dl.INDEX_NAME,dl.LOCK_TYPE,dl.LOCK_MODE,
                       dl.LOCK_STATUS,dl.LOCK_DATA
                FROM performance_schema.data_locks dl
                JOIN performance_schema.threads th ON th.THREAD_ID=dl.THREAD_ID
                WHERE th.PROCESSLIST_ID=? AND dl.OBJECT_SCHEMA=DATABASE()
                  AND dl.OBJECT_NAME='t_import_source_system' AND dl.INDEX_NAME='PRIMARY'
                  AND dl.LOCK_TYPE='RECORD'
                """, (rs, row) -> new SourcePrimaryRecordLock(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), sourceId), connectionId));
    }

    private boolean isSourcePrimaryRecordLock(SourcePrimaryRecordLock lock, long expectedSourceId, String status) {
        return lock.schema() != null && lock.schema().equals(currentDatabaseWithinObserverBudget())
                && "t_import_source_system".equals(lock.table()) && "PRIMARY".equals(lock.index())
                && "RECORD".equals(lock.type()) && isExclusiveRecordMode(lock.mode())
                && status.equals(lock.status())
                && (lock.lockData() == null || lock.lockData().equals(Long.toString(expectedSourceId)));
    }

    private boolean isExclusiveRecordMode(String mode) {
        if (mode == null) return false;
        Set<String> parts = Set.of(mode.split(","));
        return parts.contains("X") && parts.contains("REC_NOT_GAP")
                && !parts.contains("GAP") && !parts.contains("INSERT_INTENTION") && !parts.contains("SUPREMUM");
    }

    private List<SourcePrimaryWaitEdge> sourcePrimaryWaitEdges(long requesterId) {
        return withSourceWaitObserverBudget(() -> jdbc.query("""
                SELECT w.ENGINE,wt.PROCESSLIST_ID AS req_connection_id,req.ENGINE_LOCK_ID AS req_lock_id,
                       req.ENGINE_TRANSACTION_ID AS req_trx_id,req.OBJECT_SCHEMA AS req_schema,
                       req.OBJECT_NAME AS req_table,req.INDEX_NAME AS req_index,req.LOCK_TYPE AS req_type,
                       req.LOCK_MODE AS req_mode,req.LOCK_STATUS AS req_status,req.LOCK_DATA AS req_data,
                       bt.PROCESSLIST_ID AS holder_connection_id,blk.ENGINE_LOCK_ID AS holder_lock_id,
                       blk.ENGINE_TRANSACTION_ID AS holder_trx_id,blk.OBJECT_SCHEMA AS holder_schema,
                       blk.OBJECT_NAME AS holder_table,blk.INDEX_NAME AS holder_index,blk.LOCK_TYPE AS holder_type,
                       blk.LOCK_MODE AS holder_mode,blk.LOCK_STATUS AS holder_status,blk.LOCK_DATA AS holder_data
                FROM performance_schema.data_lock_waits w
                JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                JOIN performance_schema.data_locks req
                  ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.data_locks blk
                  ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                WHERE wt.PROCESSLIST_ID=?
                """, (rs, row) -> new SourcePrimaryWaitEdge(rs.getString("ENGINE"),
                rs.getLong("req_connection_id"), rs.getString("req_lock_id"), rs.getString("req_trx_id"),
                rs.getString("req_schema"), rs.getString("req_table"), rs.getString("req_index"),
                rs.getString("req_type"), rs.getString("req_mode"), rs.getString("req_status"),
                rs.getString("req_data"), rs.getLong("holder_connection_id"), rs.getString("holder_lock_id"),
                rs.getString("holder_trx_id"), rs.getString("holder_schema"), rs.getString("holder_table"),
                rs.getString("holder_index"), rs.getString("holder_type"), rs.getString("holder_mode"),
                rs.getString("holder_status"), rs.getString("holder_data")), requesterId));
    }

    private boolean isExactSourcePrimaryWait(List<SourcePrimaryRecordLock> holderRecords,
                                             List<SourcePrimaryWaitEdge> waits,
                                             long requesterId, long holderId, long expectedSourceId) {
        if (holderRecords == null || holderRecords.size() != 1 || waits == null || waits.size() != 1) return false;
        SourcePrimaryRecordLock holder = holderRecords.get(0);
        SourcePrimaryWaitEdge edge = waits.get(0);
        if (holder.engine() == null || holder.lockId() == null || holder.transactionId() == null
                || holder.connectionId() != holderId || holder.sourceId() != expectedSourceId
                || !isSourcePrimaryRecordLock(holder, expectedSourceId, "GRANTED")) return false;
        return edge.engine() != null && edge.requesterLockId() != null && edge.requesterTransactionId() != null
                && edge.holderLockId() != null && edge.holderTransactionId() != null
                && edge.engine().equals(holder.engine())
                && edge.requesterConnectionId() == requesterId && edge.holderConnectionId() == holderId
                && !edge.requesterTransactionId().equals(holder.transactionId())
                && edge.holderTransactionId().equals(holder.transactionId())
                && edge.holderLockId().equals(holder.lockId())
                && isSourcePrimaryEdgeSide(edge.requesterSchema(), edge.requesterTable(), edge.requesterIndex(),
                        edge.requesterType(), edge.requesterMode(), edge.requesterStatus(), edge.requesterData(),
                        expectedSourceId, "WAITING")
                && isSourcePrimaryEdgeSide(edge.holderSchema(), edge.holderTable(), edge.holderIndex(),
                        edge.holderType(), edge.holderMode(), edge.holderStatus(), edge.holderData(),
                        expectedSourceId, "GRANTED");
    }

    private boolean isSourcePrimaryEdgeSide(String schema, String table, String index, String type,
                                            String mode, String status, String lockData,
                                            long expectedSourceId, String expectedStatus) {
        return schema != null && schema.equals(currentDatabaseWithinObserverBudget())
                && "t_import_source_system".equals(table) && "PRIMARY".equals(index)
                && "RECORD".equals(type) && isExclusiveRecordMode(mode) && expectedStatus.equals(status)
                && (lockData == null || lockData.equals(Long.toString(expectedSourceId)));
    }

    private SourcePrimaryWaitEdge awaitSourcePrimaryWait(AtomicReference<Long> requesterId,
                                                         SourcePrimaryRecordLock holderLock,
                                                         long expectedSourceId, Future<?> sourceOperation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertFuturePending(sourceOperation, "metadata service");
            Long requester = requesterId.get();
            if (requester != null) {
                if (requester == holderLock.connectionId()) {
                    throw new AssertionError("requesterとholderのconnection IDが同一です");
                }
                List<SourcePrimaryWaitEdge> waits = sourcePrimaryWaitEdges(requester);
                if (!waits.isEmpty()) {
                    List<SourcePrimaryRecordLock> holderRecords = sourcePrimaryRecordLocks(holderLock.connectionId());
                    if (!isExactSourcePrimaryWait(holderRecords, waits, requester,
                            holderLock.connectionId(), expectedSourceId)
                            || !sameSourcePrimaryLockIdentity(holderRecords.get(0), holderLock)) {
                        throw new AssertionError("source PRIMARY wait identityが曖昧または期待外です: waits="
                                + waits + "; holderRecords=" + holderRecords);
                    }
                    assertFuturePending(sourceOperation, "metadata service");
                    return waits.get(0);
                }
            }
            Thread.sleep(25);
        }
        assertFuturePending(sourceOperation, "metadata service");
        throw new AssertionError("対象source PRIMARY行のMySQL lock waitを確認できませんでした。"
                + safeWaitDetails(requesterId.get(), holderLock.connectionId()) + "; holder=" + holderLock);
    }

    private boolean sameSourcePrimaryLockIdentity(SourcePrimaryRecordLock observed, SourcePrimaryRecordLock captured) {
        return java.util.Objects.equals(observed.engine(), captured.engine())
                && java.util.Objects.equals(observed.lockId(), captured.lockId())
                && java.util.Objects.equals(observed.transactionId(), captured.transactionId())
                && observed.connectionId() == captured.connectionId()
                && java.util.Objects.equals(observed.schema(), captured.schema())
                && java.util.Objects.equals(observed.table(), captured.table())
                && java.util.Objects.equals(observed.index(), captured.index())
                && java.util.Objects.equals(observed.type(), captured.type())
                && java.util.Objects.equals(observed.mode(), captured.mode())
                && java.util.Objects.equals(observed.status(), captured.status());
    }

    private void assertContentionTasksPending(Future<?> gate, Future<?> denyInsert,
                                              Future<?> membershipInsert) throws Exception {
        assertFuturePending(gate, "認可gate");
        assertFuturePending(denyInsert, "deny-action insert");
        assertFuturePending(membershipInsert, "membership insert");
    }

    private void assertFuturePending(Future<?> future, String label) throws Exception {
        if (future == null || !future.isDone()) return;
        try {
            future.get(0, TimeUnit.NANOSECONDS);
            throw new AssertionError(label + "がgate解放前に完了しました");
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new AssertionError(label + "が非同期実行中に失敗しました", failure.getCause());
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw new AssertionError(label + "が予期せずcancelされました", cancelled);
        }
    }

    private <T> T runSourceWaitWorker(AtomicReference<Long> requesterId, Supplier<T> operation) {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        transaction.setTimeout(SOURCE_WAIT_LOCK_SECONDS + 5);
        return transaction.execute(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            int originalNetworkTimeout;
            int originalLockWaitSeconds;
            try {
                originalNetworkTimeout = connection.getNetworkTimeout();
                connection.setNetworkTimeout(Runnable::run, SOURCE_WAIT_NETWORK_MILLIS);
                try {
                    originalLockWaitSeconds = jdbc.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
                } catch (RuntimeException failure) {
                    try { connection.setNetworkTimeout(Runnable::run, originalNetworkTimeout); }
                    catch (java.sql.SQLException resetFailure) { failure.addSuppressed(resetFailure); }
                    throw failure;
                }
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("source wait workerのconnection budgetを設定できません", failure);
            }
            try {
                jdbc.execute("SET SESSION innodb_lock_wait_timeout=" + SOURCE_WAIT_LOCK_SECONDS);
                requesterId.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                return operation.get();
            } finally {
                try { jdbc.execute("SET SESSION innodb_lock_wait_timeout=" + originalLockWaitSeconds); }
                finally {
                    try { connection.setNetworkTimeout(Runnable::run, originalNetworkTimeout); }
                    catch (java.sql.SQLException failure) {
                        throw new IllegalStateException("source wait workerのnetwork timeoutを復元できません", failure);
                    }
                }
            }
        });
    }

    private <T> T withSourceWaitObserverBudget(Supplier<T> observation) {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        transaction.setTimeout(5);
        return transaction.execute(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            int originalNetworkTimeout;
            try {
                originalNetworkTimeout = connection.getNetworkTimeout();
                connection.setNetworkTimeout(Runnable::run, 5_000);
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("source wait observerのnetwork timeoutを設定できません", failure);
            }
            try { return observation.get(); }
            finally {
                try { connection.setNetworkTimeout(Runnable::run, originalNetworkTimeout); }
                catch (java.sql.SQLException failure) {
                    throw new IllegalStateException("source wait observerのnetwork timeoutを復元できません", failure);
                }
            }
        });
    }

    private String currentDatabaseWithinObserverBudget() {
        return withSourceWaitObserverBudget(() -> jdbc.queryForObject("SELECT DATABASE()", String.class));
    }

    private List<Throwable> awaitSourceWorkers(List<? extends Future<?>> futures, ExecutorService pool,
                                               AtomicReference<Long> requesterId,
                                               boolean expectedAuthorizationDenial) {
        List<Throwable> failures = new java.util.ArrayList<>();
        boolean timedOut = false;
        for (Future<?> future : futures) {
            if (future == null) continue;
            try {
                future.get(SOURCE_WAIT_FUTURE_SECONDS, TimeUnit.SECONDS);
                if (expectedAuthorizationDenial) {
                    failures.add(new AssertionError("source lock解放後にworkerが成功しました。認可拒否が必要です"));
                }
            } catch (java.util.concurrent.ExecutionException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                if (!(expectedAuthorizationDenial && cause instanceof BusinessException denied
                        && denied.getCode() == 403)) failures.add(cause);
            } catch (java.util.concurrent.TimeoutException timeout) {
                timedOut = true;
                failures.add(new AssertionError("source lock workerが有限時間内に完了しませんでした", timeout));
            } catch (java.util.concurrent.CancellationException cancelled) {
                timedOut = true;
                failures.add(cancelled);
            } catch (InterruptedException interruption) {
                timedOut = true;
                failures.add(interruption);
                Thread.currentThread().interrupt();
            }
        }
        if (pool != null) {
            if (timedOut) {
                Long connectionId = requesterId == null ? null : requesterId.get();
                boolean workerStillRunning = futures.stream().anyMatch(future -> future != null && !future.isDone());
                if (workerStillRunning) {
                    try { killCapturedSourceWaitConnection(connectionId); }
                    catch (Exception failure) { failures.add(failure); }
                }
                for (Future<?> future : futures) if (future != null) future.cancel(true);
                pool.shutdownNow();
            } else {
                pool.shutdown();
            }
            try {
                if (!pool.awaitTermination(SOURCE_WAIT_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                    SOURCE_WAIT_FIXTURE_UNSAFE.set(true);
                    failures.add(new AssertionError("source lock workerのexecutor終了を確認できずfixtureをunsafeにしました"));
                } else if (timedOut) {
                    Long connectionId = requesterId == null ? null : requesterId.get();
                    if (!awaitCapturedSourceConnectionEnd(connectionId)) {
                        SOURCE_WAIT_FIXTURE_UNSAFE.set(true);
                        failures.add(new AssertionError("timeout workerのcaptured MySQL connection終了を確認できずfixtureをunsafeにしました"));
                    }
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                SOURCE_WAIT_FIXTURE_UNSAFE.set(true);
                failures.add(new AssertionError("source lock workerのexecutor終了確認が中断されfixtureをunsafeにしました", interruption));
            }
        }
        return failures;
    }

    private void killCapturedSourceWaitConnection(Long connectionId) throws Exception {
        if (connectionId == null || connectionId <= 0) {
            throw new AssertionError("workerの実connection ID未取得のため、限定KILLを実行できません");
        }
        try (Connection killer = dataSource.getConnection()) {
            int originalNetworkTimeout = killer.getNetworkTimeout();
            killer.setNetworkTimeout(Runnable::run, 5_000);
            try (var statement = killer.createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute("KILL CONNECTION " + connectionId);
            } finally {
                try { killer.setNetworkTimeout(Runnable::run, originalNetworkTimeout); }
                catch (java.sql.SQLException ignored) { /* close the dedicated killer connection */ }
            }
        }
    }

    private boolean awaitCapturedSourceConnectionEnd(Long connectionId) throws InterruptedException {
        if (connectionId == null || connectionId <= 0) return false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SOURCE_WAIT_TERMINATION_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer active = withSourceWaitObserverBudget(() -> jdbc.queryForObject(
                    "SELECT COUNT(*) FROM performance_schema.threads WHERE PROCESSLIST_ID=?", Integer.class, connectionId));
            if (active != null && active == 0) return true;
            Thread.sleep(100);
        }
        return false;
    }

    private List<String> safeWaitDetails(Long requesterId, Long blockerId) {
        if (requesterId == null || blockerId == null) return List.of("waiter/blocker connection id未取得");
        return withSourceWaitObserverBudget(() -> jdbc.query("""
                SELECT req.OBJECT_NAME AS req_table, req.INDEX_NAME AS req_index,
                       req.LOCK_TYPE AS req_type, req.LOCK_MODE AS req_mode,
                       req.LOCK_STATUS AS req_status, req.LOCK_DATA AS req_key,
                       blk.OBJECT_NAME AS blk_table, blk.INDEX_NAME AS blk_index,
                       blk.LOCK_TYPE AS blk_type, blk.LOCK_MODE AS blk_mode,
                       blk.LOCK_STATUS AS blk_status, blk.LOCK_DATA AS blk_key
                FROM performance_schema.data_lock_waits w
                JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                JOIN performance_schema.data_locks req
                  ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.data_locks blk
                  ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                WHERE wt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=?
                """, (rs, row) -> "request=" + rs.getString("req_table") + "/" + rs.getString("req_index")
                + "," + rs.getString("req_type") + "," + rs.getString("req_mode") + "," + rs.getString("req_status")
                + ",key=" + rs.getString("req_key") + "; blocker=" + rs.getString("blk_table") + "/"
                + rs.getString("blk_index") + "," + rs.getString("blk_type") + "," + rs.getString("blk_mode")
                + "," + rs.getString("blk_status") + ",key=" + rs.getString("blk_key"), requesterId, blockerId));
    }

    private List<Throwable> awaitFutures(List<? extends Future<?>> futures, ExecutorService pool,
                                         boolean expectedRegistrationDenial) {
        return awaitFutures(futures, pool, expectedRegistrationDenial, Set.of());
    }

    private List<Throwable> awaitFutures(List<? extends Future<?>> futures, ExecutorService pool,
                                         boolean expectedRegistrationDenial,
                                         Set<? extends Future<?>> verifiedExpectedFailures) {
        List<Throwable> failures = new java.util.ArrayList<>();
        java.util.Set<Future<?>> unobserved = new java.util.HashSet<>(futures);
        for (Future<?> future : futures) {
            try {
                future.get(3, TimeUnit.SECONDS);
                unobserved.remove(future);
            } catch (java.util.concurrent.ExecutionException failure) {
                unobserved.remove(future);
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                if (!verifiedExpectedFailures.contains(future)
                        && !(expectedRegistrationDenial && cause instanceof BusinessException denied
                        && denied.getCode() == 403)) failures.add(cause);
            } catch (Throwable failure) {
                if (!(failure instanceof java.util.concurrent.TimeoutException)) unobserved.remove(future);
                failures.add(failure);
            }
        }
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) failures.add(new AssertionError("競合test workerが5秒以内に終了しませんでした"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failures.add(interrupted);
        }
        for (Future<?> future : unobserved) {
            if (!future.isDone()) {
                failures.add(new AssertionError("競合test workerが終了後もFutureを完了できませんでした"));
                continue;
            }
            try {
                future.get(0, TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.ExecutionException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                if (!verifiedExpectedFailures.contains(future)
                        && !(expectedRegistrationDenial && cause instanceof BusinessException denied
                        && denied.getCode() == 403)) failures.add(cause);
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
        return failures;
    }

    private void reportCleanupFailures(String label, List<Throwable> failures, Throwable primaryFailure) {
        if (failures.isEmpty()) return;
        if (primaryFailure != null) {
            failures.forEach(primaryFailure::addSuppressed);
            return;
        }
        AssertionError cleanupFailure = new AssertionError(label + "のcleanupで非同期failureが発生しました");
        failures.forEach(cleanupFailure::addSuppressed);
        throw cleanupFailure;
    }

    @Test
    void wildcardGrantだけでは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE t_permission_group_action SET action_key='*' WHERE tenant_id=? AND group_id=? AND action_key=? AND deny_flag=0",
                tenantId, groupId, ImportMetadataAuthorizationService.ACTION);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void importWildcardGrantだけでは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE t_permission_group_action SET action_key='import.*' WHERE tenant_id=? AND group_id=? AND action_key=? AND deny_flag=0",
                tenantId, groupId, ImportMetadataAuthorizationService.ACTION);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void exactAllowがあってもwildcardDenyが優先される() {
        prepareDownloadableJob();
        jdbc.update("INSERT INTO t_permission_group_action(tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES(?, ?, '*', 1, 0)",
                tenantId, groupId);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void exactAllowがあってもimportWildcardDenyが優先される() {
        prepareDownloadableJob();
        jdbc.update("INSERT INTO t_permission_group_action(tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES(?, ?, 'import.*', 1, 0)",
                tenantId, groupId);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void admin以外のprincipalでは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE sys_user SET role='営業' WHERE id=? AND tenant_id=?", actorUserId, tenantId);
        actorUser.setRole("営業");
        var authority = new SimpleGrantedAuthority("ROLE_営業");
        installPrincipal(tenantId, authority);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void inactiveUserは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=? AND tenant_id=?", actorUserId, tenantId);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void deletedUserは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE sys_user SET deleted_flag=1 WHERE id=? AND tenant_id=?", actorUserId, tenantId);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void 無効化されたpermissionGroupでは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE m_permission_group SET enabled=0 WHERE tenant_id=? AND id=?", tenantId, groupId);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void 削除済みpermissionGroupでは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE m_permission_group SET deleted_flag=1 WHERE tenant_id=? AND id=?", tenantId, groupId);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void 削除済みexplicitAllowでは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE t_permission_group_action SET deleted_flag=1 WHERE tenant_id=? AND group_id=? AND action_key=? AND deny_flag=0",
                tenantId, groupId, ImportMetadataAuthorizationService.ACTION);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void 無効化されたsourceでは実sourceを開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE t_import_source_system SET status='DISABLED', version_no=version_no+1 WHERE tenant_id=? AND id=?",
                tenantId, sourceId);

        assertDeniedWithoutStorageOrAudit(409);
    }

    @Test
    void 別tenantへ束縛されたprincipalでは実sourceを開けない() {
        prepareDownloadableJob();
        var authority = new SimpleGrantedAuthority("ROLE_管理者");
        installPrincipal("nf06-foreign-tenant", authority);

        assertDeniedWithoutStorageOrAudit(403);
    }

    @Test
    void 別legalEntityのdocumentは実FileScope経路で開けない() {
        prepareDownloadableJob();
        jdbc.update("UPDATE t_document SET legal_entity_id=? WHERE tenant_id=? AND id=?",
                Long.toString(LEGAL_ENTITY_ID + 1), tenantId, documentId);

        BusinessException denied = assertThrows(BusinessException.class, () -> metadataService.openSource(jobId));
        assertTrue(Set.of(403, 404).contains(denied.getCode()));
        assertNoDownloadEffects();
    }

    private void prepareDownloadableJob() {
        sourceId = metadataService.createSource(new ImportSourceRequest("SyntheticSource", "合成source"));
        jobId = metadataService.registerJob(new ImportJobRegistrationRequest(sourceId, ImportEntity.CUSTOMER,
                "ses-import-v1", ImportEncoding.UTF8, SOURCE_HASH, documentId, documentVersionId, 1));
        storage.resetOpenCount();
        assertEquals(0, downloadAuditCount());
    }

    private void assertDeniedWithoutStorageOrAudit(int expectedCode) {
        BusinessException denied = assertThrows(BusinessException.class, () -> metadataService.openSource(jobId));
        assertEquals(expectedCode, denied.getCode());
        assertNoDownloadEffects();
    }

    private void assertNoDownloadEffects() {
        assertEquals(0, storage.openCount());
        assertEquals(0, downloadAuditCount());
    }

    private int downloadAuditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_document_access_log WHERE document_id=? AND version_id=? AND user_id=? AND action='DOWNLOAD'",
                Integer.class, documentId, documentVersionId, actorUserId);
    }

    private void captureActualAuthorizationConnection() {
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            AtomicReference<Long> captured = actualAuthorizationConnection.get();
            if (captured != null && TransactionSynchronizationManager.isActualTransactionActive()) {
                captured.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                AtomicReference<String> isolation = actualAuthorizationIsolation.get();
                if (isolation != null) isolation.set(jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
            }
            return result;
        }).when(metadataAuthorization).requireCurrentAuthorization();
    }

    private <T> T withCapturedAuthorizationConnection(AtomicReference<Long> captured,
                                                       java.util.concurrent.Callable<T> operation) {
        return withCapturedAuthorizationConnection(captured, null, operation);
    }

    private <T> T withCapturedAuthorizationConnection(AtomicReference<Long> captured,
                                                       AtomicReference<String> isolation,
                                                       java.util.concurrent.Callable<T> operation) {
        actualAuthorizationConnection.set(captured);
        actualAuthorizationIsolation.set(isolation);
        try { return withActorContext(operation); }
        finally {
            actualAuthorizationConnection.remove();
            actualAuthorizationIsolation.remove();
        }
    }

    private void installPrincipal(String principalTenant, SimpleGrantedAuthority authority) {
        var principal = new LoginUser(actorUser, List.of(authority), principalTenant);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "test-only", List.of(authority)));
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean @Primary
        TestDocumentStorage testDocumentStorage() { return new TestDocumentStorage(); }

        @Bean @Primary
        ImportIdentityKeyProvider importIdentityKeyProvider() {
            return new ImportIdentityKeyProvider() {
                @Override public String currentLookupKeyVersion() { return LOOKUP_KEY_VERSION; }
                @Override public byte[] lookupKey(String keyVersion) { return LOOKUP_KEY.clone(); }
                @Override public String currentEncryptionKeyVersion() { return LOOKUP_KEY_VERSION; }
                @Override public byte[] encryptionKey(String keyVersion) { return LOOKUP_KEY.clone(); }
            };
        }
    }

    static class TestDocumentStorage implements DocumentStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger failAtOpen = new AtomicInteger(-1);
        private final AtomicInteger failAfterBytes = new AtomicInteger(-1);
        private final AtomicInteger changeAtOpen = new AtomicInteger(-1);
        private final AtomicInteger changeOffset = new AtomicInteger(-1);
        private final AtomicInteger changeValue = new AtomicInteger(-1);

        @Override public void put(String key, InputStream content, boolean quarantine) {
            try { objects.put(key, content.readAllBytes()); }
            catch (IOException failure) { throw new IllegalStateException("synthetic storage write failed", failure); }
        }
        @Override public void put(String key, byte[] content, boolean quarantine) { objects.put(key, content.clone()); }
        @Override public void promote(String key) { }
        @Override public InputStream open(String key) {
            byte[] content = objects.get(key);
            if (content == null) throw new IllegalStateException("synthetic storage object is missing");
            int openNumber = opens.incrementAndGet();
            byte[] openedContent = content.clone();
            if (openNumber == changeAtOpen.get()) {
                int offset = changeOffset.get();
                if (offset < 0 || offset >= openedContent.length) throw new IllegalStateException("synthetic change offset is invalid");
                openedContent[offset] = (byte) changeValue.get();
                changeAtOpen.set(-1);
            }
            ByteArrayInputStream source = new ByteArrayInputStream(openedContent);
            if (openNumber != failAtOpen.get()) return source;
            int limit = failAfterBytes.get();
            failAtOpen.set(-1);
            failAfterBytes.set(-1);
            return new InputStream() {
                private int read;
                private void failIfAtLimit() throws IOException {
                    if (read >= limit) throw new IOException("synthetic source read failure");
                }
                @Override public int read() throws IOException {
                    failIfAtLimit();
                    int value = source.read();
                    if (value >= 0) read++;
                    return value;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    failIfAtLimit();
                    int count = source.read(bytes, offset, Math.min(length, limit - read));
                    if (count > 0) read += count;
                    return count;
                }
                @Override public void close() throws IOException { source.close(); }
            };
        }
        @Override public byte[] readAll(String key) throws IOException {
            byte[] content = objects.get(key);
            if (content == null) throw new IOException("synthetic storage object is missing");
            return content.clone();
        }
        @Override public void delete(String key) { objects.remove(key); }
        int openCount() { return opens.get(); }
        void resetOpenCount() { opens.set(0); failAtOpen.set(-1); failAfterBytes.set(-1); changeAtOpen.set(-1); }
        void failOpenAt(int openNumber, int afterBytes) {
            failAtOpen.set(openNumber);
            failAfterBytes.set(afterBytes);
        }
        void changeAtOpen(int openNumber, int offset, byte value) {
            changeOffset.set(offset); changeValue.set(value); changeAtOpen.set(openNumber);
        }
    }
}
