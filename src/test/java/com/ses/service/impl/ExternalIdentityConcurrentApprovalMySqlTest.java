package com.ses.service.impl;

import com.ses.config.LoginUser;
import com.ses.dto.security.ExternalIdentityProvisionRequest;
import com.ses.entity.SysUser;
import com.ses.entity.UserExternalIdentity;
import com.ses.mapper.UserExternalIdentityMapper;
import com.ses.service.ExternalIdentityProvisioningService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REV-P2-011: 実MySQLで並行再承認しても reviewer は上書きされず、承認監査は1件に収まること。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ExternalIdentityConcurrentApprovalMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    private ExternalIdentityProvisioningService provisioningService;
    @Autowired
    private UserExternalIdentityMapper identityMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String suffix = "oidc-mysql-" + System.nanoTime();
    private long adminId;
    private long providerId;
    private long secondAdminId;

    @BeforeEach
    void setUp() {
        adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'admin' AND deleted_flag = 0", Long.class);
        jdbcTemplate.update("INSERT INTO m_identity_provider "
                        + "(tenant_id, provider_type, issuer_uri, client_id, enabled, deleted_flag) "
                        + "VALUES ('default', 'OIDC', ?, ?, 1, 0)",
                "https://idp.example.test/" + suffix, suffix + "-client");
        providerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_identity_provider WHERE client_id = ?", Long.class, suffix + "-client");
        jdbcTemplate.update("INSERT INTO sys_user (username, password, real_name, role, email, status, deleted_flag) "
                        + "VALUES (?, 'admin123', '第二管理者', '管理者', ?, 1, 0)",
                suffix + "-admin2", suffix + "-admin2@ses.local");
        secondAdminId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, suffix + "-admin2");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update(
                "DELETE FROM t_audit_log WHERE application_code = 'OIDC_BINDING_APPROVED' AND (uri LIKE ? OR uri LIKE ?)",
                "%subjectSha256=" + sha256Hex(suffix + "-concurrent") + "%",
                "%subjectSha256=" + sha256Hex(suffix + "-insert-race") + "%");
        jdbcTemplate.update("DELETE FROM t_user_external_identity WHERE subject LIKE ?", suffix + "%");
        jdbcTemplate.update("DELETE FROM m_identity_provider WHERE client_id = ?", suffix + "-client");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", suffix + "-admin2");
    }

    @Test
    void 並行再承認はCASで1人のreviewerだけが残る() throws Exception {
        String subject = suffix + "-concurrent";
        jdbcTemplate.update("INSERT INTO t_user_external_identity "
                        + "(tenant_id, user_id, provider_id, subject, linked_at, review_status, deleted_flag) "
                        + "VALUES ('default', ?, ?, ?, NOW(), 'QUARANTINED', 0)",
                adminId, providerId, subject);

        runConcurrentProvision(subject);

        UserExternalIdentity approved = identityMapper.selectByTenantProviderAndSubject("default", providerId, subject);
        assertNotNull(approved);
        assertEquals("APPROVED", approved.getReviewStatus());
        assertTrue(approved.getReviewedBy().equals(adminId) || approved.getReviewedBy().equals(secondAdminId));
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_audit_log WHERE application_code = 'OIDC_BINDING_APPROVED' AND uri LIKE ?",
                Integer.class, "%subjectSha256=" + sha256Hex(subject) + "%");
        assertEquals(1, auditCount);
        String uri = jdbcTemplate.queryForObject(
                "SELECT uri FROM t_audit_log WHERE application_code = 'OIDC_BINDING_APPROVED' AND uri LIKE ?",
                String.class, "%subjectSha256=" + sha256Hex(subject) + "%");
        assertTrue(uri.contains("reviewerId=" + approved.getReviewedBy()));
    }

    @Test
    void 同一subjectの並行新規承認は1件のAPPROVEDと必須監査に収束する() throws Exception {
        String subject = suffix + "-insert-race";

        runConcurrentProvision(subject);

        UserExternalIdentity approved = identityMapper.selectByTenantProviderAndSubject("default", providerId, subject);
        assertNotNull(approved);
        assertEquals("APPROVED", approved.getReviewStatus());
        assertNotNull(approved.getReviewedBy());
        Integer bindingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_user_external_identity WHERE provider_id = ? AND subject = ? AND deleted_flag = 0",
                Integer.class, providerId, subject);
        assertEquals(1, bindingCount);
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_audit_log WHERE application_code = 'OIDC_BINDING_APPROVED' AND uri LIKE ?",
                Integer.class, "%subjectSha256=" + sha256Hex(subject) + "%");
        assertEquals(1, auditCount);
        String uri = jdbcTemplate.queryForObject(
                "SELECT uri FROM t_audit_log WHERE application_code = 'OIDC_BINDING_APPROVED' AND uri LIKE ?",
                String.class, "%subjectSha256=" + sha256Hex(subject) + "%");
        assertTrue(uri.contains("from=NEW") || uri.contains("from=QUARANTINED") || uri.contains("from=APPROVED"));
        assertTrue(uri.contains("reviewerId=" + approved.getReviewedBy()));
    }

    private void runConcurrentProvision(String subject) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        executor.submit(() -> runProvision(adminId, "admin", subject, ready, start, done, success, failure));
        executor.submit(() -> runProvision(secondAdminId, suffix + "-admin2", subject, ready, start, done, success, failure));

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        executor.shutdownNow();

        assertEquals(2, success.get());
        assertEquals(0, failure.get());
    }

    private void runProvision(long reviewerId, String username, String subject,
                              CountDownLatch ready, CountDownLatch start, CountDownLatch done,
                              AtomicInteger success, AtomicInteger failure) {
        try {
            SysUser admin = new SysUser();
            admin.setId(reviewerId);
            admin.setUsername(username);
            admin.setRole("管理者");
            LoginUser principal = new LoginUser(admin, List.of(new SimpleGrantedAuthority("ROLE_管理者")));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities()));
            SecurityContextHolder.setContext(context);
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            ExternalIdentityProvisionRequest request = new ExternalIdentityProvisionRequest();
            request.setUserId(adminId);
            request.setSubject(subject);
            provisioningService.provision(providerId, request);
            success.incrementAndGet();
        } catch (Exception e) {
            failure.incrementAndGet();
        } finally {
            SecurityContextHolder.clearContext();
            done.countDown();
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
