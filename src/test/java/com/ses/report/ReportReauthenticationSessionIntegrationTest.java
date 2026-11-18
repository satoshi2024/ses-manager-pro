package com.ses.report;

import com.ses.config.LoginUser;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.entity.SysUser;
import com.ses.service.DocumentService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.ReportReauthenticationGrant;
import com.ses.service.report.ReportSnapshotService;
import com.ses.mapper.ReportDeliveryMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 実controllerから実delivery serviceまでを通し、別HttpSession間でreauth grantが共有されないことを検証する。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportReauthenticationSessionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReportReauthenticationGrant grants;
    @MockBean private ReportRecipientPreviewService recipientPreviewService;
    @MockBean private ReportSnapshotService snapshotService;
    @MockBean private DocumentService documentService;
    @MockBean private PasswordEncoder passwordEncoder;
    @SpyBean private ReportDeliveryMapper deliveryMapper;

    private Long userId;
    private Long runId;
    private Long deliveryId;
    private String username;
    private String runKey;
    private ReportRecipientPreviewResult currentPreview;

    @BeforeEach
    void setUp() {
        String unique = Long.toUnsignedString(System.nanoTime(), 36);
        username = "reauth-it-" + unique;
        jdbc.update("INSERT INTO sys_user (tenant_id, username, password, real_name, role, email, status, deleted_flag) " +
                        "VALUES ('default', ?, 'correct', '再認証テスト', '管理者', ?, 1, 0)",
                username, username + "@test.invalid");
        userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=? AND tenant_id='default'", Long.class, username);
        runKey = "reauth-session-" + unique;
        runId = insertRun();
        deliveryId = insertDelivery();
        ReportRecipientPreview allow = new ReportRecipientPreview(userId, "管理者", "ALLOW", "SCOPE_MATCH", "scope-a");
        currentPreview = new ReportRecipientPreviewResult("preview-a", "APPROVED_SCOPE_CHECKED",
                LocalDateTime.now(), List.of(allow));
        when(recipientPreviewService.previewForRun(any())).thenReturn(currentPreview);
        when(documentService.getVersionStorageKey(anyLong(), anyInt())).thenReturn("stored/report.pdf");
        when(documentService.download(anyLong(), anyInt()))
                .thenReturn(new ByteArrayInputStream("report".getBytes(StandardCharsets.UTF_8)));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        AccountingTenantContextHolder.setTenantId("default");
    }

    @AfterEach
    void tearDown() {
        if (deliveryId != null) jdbc.update("DELETE FROM t_report_delivery WHERE id=?", deliveryId);
        if (runId != null) jdbc.update("DELETE FROM t_report_run WHERE id=?", runId);
        if (userId != null) jdbc.update("DELETE FROM sys_user WHERE id=? AND username=?", userId, username);
        org.mockito.Mockito.reset(deliveryMapper);
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 実controllerとserviceでAのみ再認証済みとなりBは独立に再認証する() throws Exception {
        MockHttpSession sessionA = new MockHttpSession();
        MockHttpSession sessionB = new MockHttpSession();
        Authentication authA = auth("session-a");
        Authentication authB = auth("session-b");

        download(sessionA, authA).andExpect(status().isForbidden());
        reauthenticate(sessionA, authA).andExpect(status().isOk());
        assertThatReauthenticatedAtIsPersisted();
        download(sessionA, authA).andExpect(status().isOk());

        download(sessionB, authB).andExpect(status().isForbidden());
        reauthenticate(sessionB, authB).andExpect(status().isOk());
        download(sessionB, authB).andExpect(status().isOk());
    }

    @Test
    void grant後の現在scope撤回と同sessionのauthentication交換はdownloadを拒否する() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Authentication auth = auth("same-user");
        reauthenticate(session, auth).andExpect(status().isOk());
        ReportRecipientPreview denied = new ReportRecipientPreview(userId, "管理者", "DENY", "ROLE_REVOKED", "scope-b");
        when(recipientPreviewService.previewForRun(any())).thenReturn(new ReportRecipientPreviewResult(
                "preview-a", "APPROVED_SCOPE_CHECKED", LocalDateTime.now(), List.of(denied)));
        download(session, auth).andExpect(status().isForbidden());

        when(recipientPreviewService.previewForRun(any())).thenReturn(currentPreview);
        reauthenticate(session, auth).andExpect(status().isOk());
        download(session, auth("same-user-new-authentication-instance")).andExpect(status().isForbidden());
    }

    @Test
    void delivery更新失敗時はgrantを発行せずdownloadを拒否する() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Authentication auth = auth("update-failure");
        doReturn(0).when(deliveryMapper).update(any(), any());

        reauthenticate(session, auth).andExpect(status().is5xxServerError());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT reauthenticated_at FROM t_report_delivery WHERE id=?", LocalDateTime.class, deliveryId))
                .isNull();

        org.mockito.Mockito.reset(deliveryMapper);
        download(session, auth).andExpect(status().isForbidden());
    }

    @Test
    void DBで停止されたrecipientは再認証も既存grant後downloadも拒否される() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Authentication auth = auth("disabled-user");
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=?", userId);

        reauthenticate(session, auth).andExpect(status().isForbidden());
        jdbc.update("UPDATE sys_user SET status=1 WHERE id=?", userId);
        reauthenticate(session, auth).andExpect(status().isOk());
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=?", userId);
        download(session, auth).andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions reauthenticate(MockHttpSession session,
                                                                               Authentication authentication) throws Exception {
        return mockMvc.perform(post("/api/management-reports/deliveries/{id}/reauthenticate", deliveryId)
                .session(session).with(authentication(authentication)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"correct\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions download(MockHttpSession session,
                                                                         Authentication authentication) throws Exception {
        return mockMvc.perform(get("/api/management-reports/deliveries/{id}/download", deliveryId)
                .session(session).with(authentication(authentication)));
    }

    private Authentication auth(String username) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername(username);
        user.setPassword("correct");
        user.setRole("管理者");
        user.setStatus(1);
        user.setTenantId("default");
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_管理者")), "default");
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private void assertThatReauthenticatedAtIsPersisted() {
        LocalDateTime timestamp = jdbc.queryForObject(
                "SELECT reauthenticated_at FROM t_report_delivery WHERE id=?", LocalDateTime.class, deliveryId);
        org.assertj.core.api.Assertions.assertThat(timestamp).isNotNull();
    }

    private Long insertRun() {
        jdbc.update("""
                INSERT INTO t_report_run (tenant_id, run_key, template_id, template_version_id, snapshot_version,
                    principal_type, principal_user_id, scope_owner_type, scope_owner_id, organization_scope_json,
                    scope_policy_version, scope_hash, period_from, period_to, cutoff_kind, as_of_at, timezone_id,
                    data_as_of_at, status, snapshot_schema_version, source_policy_hash, generated_at)
                VALUES ('default', ?, 1, 1, 1, 'USER', ?, 'COMPANY', 1,
                    '{"companyWide":true,"organizationIds":[],"directUserIds":[]}', 'v1', 'run-scope',
                    '2026-08-01', '2026-08-31', '速報', CURRENT_TIMESTAMP, 'Asia/Tokyo', CURRENT_TIMESTAMP,
                    'SUCCEEDED', 'report-1.0', 'policy', CURRENT_TIMESTAMP)
                """, runKey, userId);
        return jdbc.queryForObject("SELECT id FROM t_report_run WHERE tenant_id='default' AND run_key=?", Long.class, runKey);
    }

    private Long insertDelivery() {
        jdbc.update("""
                INSERT INTO t_report_delivery (tenant_id, run_id, document_id, document_version_no,
                    recipient_user_id, recipient_scope_json, recipient_scope_hash, preview_status, scope_decision,
                    notification_dedupe_key, link_token_hash, link_expires_at, reauth_required)
                VALUES ('default', ?, 990001, 1, ?, '{"organizationIds":[]}', 'scope-a', 'ALLOWED', 'ALLOW',
                    ?, ?, ?, 1)
                """, runId, userId, "reauth-test-" + System.nanoTime(), sha256("valid-token"),
                LocalDateTime.now().plusDays(1));
        return jdbc.queryForObject("SELECT id FROM t_report_delivery WHERE run_id=?", Long.class, runId);
    }

    private String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
