package com.ses.importcenter.metadata;

import com.ses.config.LoginUser;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.SysUser;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    @Autowired private DocumentService documentService;
    @Autowired private DocumentMapper documentMapper;
    @Autowired private DocumentVersionMapper documentVersionMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private TestDocumentStorage storage;

    private String tenantId;
    private long actorUserId;
    private long groupId;
    private long sourceId;
    private long mappingId;
    private long jobId;
    private long documentId;
    private long documentVersionId;
    private String storageKey;
    private SysUser actorUser;

    @BeforeEach
    void prepareAuthorizedFixture() {
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
        if (tenantId == null) return;
        if (documentId > 0) {
            jdbc.update("DELETE FROM t_document_access_log WHERE document_id=?", documentId);
            jdbc.update("DELETE FROM t_document_link WHERE tenant_id=? AND document_id=?", tenantId, documentId);
            jdbc.update("DELETE FROM t_import_job WHERE tenant_id=? AND id=?", tenantId, jobId);
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

        @Override public void put(String key, InputStream content, boolean quarantine) {
            try { objects.put(key, content.readAllBytes()); }
            catch (IOException failure) { throw new IllegalStateException("synthetic storage write failed", failure); }
        }
        @Override public void put(String key, byte[] content, boolean quarantine) { objects.put(key, content.clone()); }
        @Override public void promote(String key) { }
        @Override public InputStream open(String key) {
            byte[] content = objects.get(key);
            if (content == null) throw new IllegalStateException("synthetic storage object is missing");
            opens.incrementAndGet();
            return new ByteArrayInputStream(content.clone());
        }
        @Override public byte[] readAll(String key) throws IOException {
            byte[] content = objects.get(key);
            if (content == null) throw new IOException("synthetic storage object is missing");
            return content.clone();
        }
        @Override public void delete(String key) { objects.remove(key); }
        int openCount() { return opens.get(); }
        void resetOpenCount() { opens.set(0); }
    }
}
