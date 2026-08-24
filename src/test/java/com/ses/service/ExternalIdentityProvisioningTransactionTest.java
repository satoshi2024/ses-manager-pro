package com.ses.service;

import com.ses.config.LoginUser;
import com.ses.dto.security.ExternalIdentityProvisionRequest;
import com.ses.entity.SysUser;
import com.ses.entity.UserExternalIdentity;
import com.ses.mapper.UserExternalIdentityMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * REV-P1-011 / REV-P1-012 / REV-P2-011: 実H2トランザクションで監査失敗ロールバックと並行再承認の冪等を検証する。
 * クラス名は Surefire 既定 (*Test) に含め、verify-like-ci の fast gate へ載せる。
 */
@SpringBootTest
@ActiveProfiles("test")
class ExternalIdentityProvisioningTransactionTest {

    @Autowired
    private ExternalIdentityProvisioningService provisioningService;
    @Autowired
    private UserExternalIdentityMapper identityMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @SpyBean
    private AuditLogService auditLogService;

    private final String suffix = "oidc-tx-" + System.nanoTime();
    private final Set<String> trackedSubjects = ConcurrentHashMap.newKeySet();
    private long adminId;
    private long providerId;

    @BeforeEach
    void setUp() {
        Mockito.reset(auditLogService);
        trackedSubjects.clear();
        adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'admin' AND deleted_flag = 0", Long.class);
        jdbcTemplate.update("INSERT INTO m_identity_provider "
                        + "(tenant_id, provider_type, issuer_uri, client_id, enabled, deleted_flag) "
                        + "VALUES ('default', 'OIDC', ?, ?, 1, 0)",
                "https://idp.example.test/" + suffix, suffix + "-client");
        providerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_identity_provider WHERE client_id = ?", Long.class, suffix + "-client");
        authenticate(adminId, "admin");
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(auditLogService);
        SecurityContextHolder.clearContext();
        for (String subject : trackedSubjects) {
            jdbcTemplate.update(
                    "DELETE FROM t_audit_log WHERE application_code = 'OIDC_BINDING_APPROVED' AND uri LIKE ?",
                    "%subjectSha256=" + sha256Hex(subject) + "%");
        }
        jdbcTemplate.update("DELETE FROM t_user_external_identity WHERE subject LIKE ?", suffix + "%");
        jdbcTemplate.update("DELETE FROM m_identity_provider WHERE client_id = ?", suffix + "-client");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE ?", suffix + "-admin%");
    }

    @Test
    void 新規承認の監査失敗はbindingも監査行も残さない() {
        forceAuditFailureAfterInsert(new IllegalStateException("forced rollback after required audit insert"));
        String subject = track(suffix + "-new");

        assertThrows(IllegalStateException.class,
                () -> provisioningService.provision(providerId, request(adminId, subject)));

        UserExternalIdentity leftover = readInNewTransaction(subject);
        assertNull(leftover, "監査失敗後は新規bindingが残ってはいけない");
        assertEquals(0, countApprovedAudits(subject));
    }

    @Test
    void 監査のDuplicateKeyExceptionでもAPPROVED欠監査でcommitしない() {
        forceAuditFailureAfterInsert(new DuplicateKeyException("forced audit duplicate"));
        String subject = track(suffix + "-audit-dup");

        assertThrows(DuplicateKeyException.class,
                () -> provisioningService.provision(providerId, request(adminId, subject)));

        UserExternalIdentity leftover = readInNewTransaction(subject);
        assertNull(leftover, "監査 DuplicateKey を binding 競合と誤認して commit してはいけない");
        assertEquals(0, countApprovedAudits(subject));
    }

    @Test
    void QUARANTINED更新の監査失敗は元状態のまま追加監査も残さない() {
        forceAuditFailureAfterInsert(new IllegalStateException("forced rollback after required audit insert"));
        String subject = track(suffix + "-quarantined");
        insertQuarantined(subject);

        assertThrows(IllegalStateException.class,
                () -> provisioningService.provision(providerId, request(adminId, subject)));

        UserExternalIdentity leftover = readInNewTransaction(subject);
        assertNotNull(leftover);
        assertEquals("QUARANTINED", leftover.getReviewStatus());
        assertNull(leftover.getReviewedBy());
        assertEquals(0, countApprovedAudits(subject));
    }

    @Test
    void 並行再承認はreviewerを上書きせず承認監査は1件だけ() throws Exception {
        String subject = track(suffix + "-concurrent");
        insertQuarantined(subject);
        long secondAdminId = insertSecondAdmin(suffix + "-admin2");

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

        assertEquals(2, success.get(), "両方とも冪等成功すること");
        assertEquals(0, failure.get());

        UserExternalIdentity approved = readInNewTransaction(subject);
        assertNotNull(approved);
        assertEquals("APPROVED", approved.getReviewStatus());
        assertNotNull(approved.getReviewedBy());
        assertTrue(approved.getReviewedBy().equals(adminId) || approved.getReviewedBy().equals(secondAdminId));
        assertEquals(1, countApprovedAudits(subject), "QUARANTINED→APPROVED の追加監査は勝者の1件だけ");
        String uri = jdbcTemplate.queryForObject(
                "SELECT uri FROM t_audit_log WHERE application_code = 'OIDC_BINDING_APPROVED' AND uri LIKE ?",
                String.class, "%subjectSha256=" + sha256Hex(subject) + "%");
        assertTrue(uri.contains("reviewerId=" + approved.getReviewedBy()));
        assertTrue(uri.contains("from=QUARANTINED"));
    }

    private String track(String subject) {
        trackedSubjects.add(subject);
        return subject;
    }

    private void runProvision(long reviewerId, String username, String subject,
                              CountDownLatch ready, CountDownLatch start, CountDownLatch done,
                              AtomicInteger success, AtomicInteger failure) {
        try {
            authenticate(reviewerId, username);
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            provisioningService.provision(providerId, request(adminId, subject));
            success.incrementAndGet();
        } catch (Exception e) {
            failure.incrementAndGet();
        } finally {
            SecurityContextHolder.clearContext();
            done.countDown();
        }
    }

    private void forceAuditFailureAfterInsert(RuntimeException failure) {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw failure;
        }).when(auditLogService).recordRequired(any(), any(), any(), anyInt(),
                eq("OIDC_BINDING_APPROVED"), anyBoolean());
    }

    private UserExternalIdentity readInNewTransaction(String subject) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status ->
                identityMapper.selectByTenantProviderAndSubject("default", providerId, subject));
    }

    private int countApprovedAudits(String subject) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_audit_log WHERE application_code = 'OIDC_BINDING_APPROVED' AND uri LIKE ?",
                Integer.class, "%subjectSha256=" + sha256Hex(subject) + "%");
        return count == null ? 0 : count;
    }

    private void insertQuarantined(String subject) {
        jdbcTemplate.update("INSERT INTO t_user_external_identity "
                        + "(tenant_id, user_id, provider_id, subject, linked_at, review_status, deleted_flag) "
                        + "VALUES ('default', ?, ?, ?, CURRENT_TIMESTAMP, 'QUARANTINED', 0)",
                adminId, providerId, subject);
    }

    private long insertSecondAdmin(String username) {
        jdbcTemplate.update("INSERT INTO sys_user (username, password, real_name, role, email, status, deleted_flag) "
                        + "VALUES (?, 'admin123', '第二管理者', '管理者', ?, 1, 0)",
                username, username + "@ses.local");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private void authenticate(long userId, String username) {
        SysUser admin = new SysUser();
        admin.setId(userId);
        admin.setUsername(username);
        admin.setRole("管理者");
        LoginUser principal = new LoginUser(admin, List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    private ExternalIdentityProvisionRequest request(long userId, String subject) {
        ExternalIdentityProvisionRequest request = new ExternalIdentityProvisionRequest();
        request.setUserId(userId);
        request.setSubject(subject);
        return request;
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
