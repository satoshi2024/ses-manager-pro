package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.service.accounting.AccountingTenantContextHolder;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** main default-DENY portが実MySQLでsource解決後もraw bodyを消費しないことを確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
@Import(ImportCsvIngestionRealMySqlTest.TestBeans.class)
class ImportCsvIngestionRealMySqlTest {
    private static final String DATABASE = "nf06_import_ingress_real";
    private static final String TENANT = "nf06-import-ingress-test";
    private static final long LEGAL_ENTITY = 7_601_235L;

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName(DATABASE).withUsername("root").withPassword("ses");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("app.security.oidc.tenant-id", () -> TENANT);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ImportCsvIngestionService ingestion;
    @Autowired private ImportMetadataService metadata;
    @Autowired private com.ses.mapper.SysUserMapper users;
    private long actorId;
    private long groupId;
    private long sourceId;
    private String sourceCode;

    @BeforeEach
    void prepareAuthorizedSource() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        sourceCode = "nf06_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        SysUser actor = SysUser.builder().username("nf06-" + sourceCode).password("test-only")
                .realName("取込テスト管理者").role("管理者").status(1).tenantId(TENANT).build();
        users.insert(actor);
        actorId = actor.getId();
        jdbc.update("""
                INSERT INTO m_organization_unit(legal_entity_id, code, name, type, valid_from, status, deleted_flag)
                VALUES(?, ?, ?, '法人', CURRENT_DATE, '有効', 0)
                """, LEGAL_ENTITY, "NF06-" + TENANT, "取込テスト法人");
        jdbc.update("""
                INSERT INTO m_permission_group(tenant_id, group_key, group_name, enabled, deleted_flag)
                VALUES(?, ?, ?, 1, 0)
                """, TENANT, "nf06-import-" + sourceCode, "取込アップロードテスト");
        groupId = jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id=? AND group_key=?",
                Long.class, TENANT, "nf06-import-" + sourceCode);
        jdbc.update("""
                INSERT INTO t_user_permission_group(tenant_id, user_id, group_id, assigned_by, deleted_flag)
                VALUES(?, ?, ?, ?, 0)
                """, TENANT, actorId, groupId, actorId);
        jdbc.update("""
                INSERT INTO t_permission_group_action(tenant_id, group_id, action_key, deny_flag, deleted_flag)
                VALUES(?, ?, ?, 0, 0)
                """, TENANT, groupId, ImportMetadataAuthorizationService.ACTION);
        var authority = new SimpleGrantedAuthority("ROLE_管理者");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(actor, List.of(authority), TENANT), "test-only", List.of(authority)));
        sourceId = metadata.createSource(new ImportSourceRequest(sourceCode, "合成source"));
    }

    @AfterEach
    void cleanupFixture() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
        if (sourceId > 0) jdbc.update("DELETE FROM t_import_source_system WHERE tenant_id=? AND id=?", TENANT, sourceId);
        if (groupId > 0) {
            jdbc.update("DELETE FROM t_permission_group_action WHERE tenant_id=? AND group_id=?", TENANT, groupId);
            jdbc.update("DELETE FROM t_user_permission_group WHERE tenant_id=? AND group_id=?", TENANT, groupId);
            jdbc.update("DELETE FROM m_permission_group WHERE tenant_id=? AND id=?", TENANT, groupId);
        }
        if (actorId > 0) jdbc.update("DELETE FROM sys_user WHERE id=?", actorId);
        jdbc.update("DELETE FROM m_organization_unit WHERE legal_entity_id=? AND code=?",
                LEGAL_ENTITY, "NF06-" + TENANT);
    }

    @Test
    void realSourceAndExactGrantStillRequireProductionAdmissionBeforeBodyRead() {
        AtomicBoolean bodyOpened = new AtomicBoolean();

        BusinessException denied = assertThrows(BusinessException.class, () -> ingestion.upload(
                new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8), () -> {
                    bodyOpened.set(true);
                    return new ByteArrayInputStream("externalKey,companyName\n".getBytes(StandardCharsets.UTF_8));
                }));

        assertEquals(403, denied.getCode());
        assertFalse(bodyOpened.get());
        assertEquals(sourceCode, jdbc.queryForObject(
                "SELECT source_code FROM t_import_source_system WHERE tenant_id=? AND id=?",
                String.class, TENANT, sourceId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? AND source_system_id=?",
                Integer.class, TENANT, sourceId));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean @Primary
        ImportIdentityKeyProvider importIdentityKeyProvider() {
            return new ImportIdentityKeyProvider() {
                @Override public String currentLookupKeyVersion() { return "nf06-test-v1"; }
                @Override public byte[] lookupKey(String keyVersion) { return new byte[32]; }
                @Override public String currentEncryptionKeyVersion() { return "nf06-test-v1"; }
                @Override public byte[] encryptionKey(String keyVersion) { return new byte[32]; }
            };
        }
    }
}
