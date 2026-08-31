package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.AuditLog;
import com.ses.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 監査ログAPIのテスト（P8フォローアップ・提案11）。
 */
@WebMvcTest(AuditLogApiController.class)
class AuditLogApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditLogService auditLogService;

    @Test
    @WithMockUser(roles = "管理者")
    void page_管理者は200() throws Exception {
        when(auditLogService.page(any(Long.class), any(Long.class), any(), any())).thenReturn(new Page<>());
        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void page_主体チャネルが空の旧行はLEGACY_UNRESOLVEDで返す() throws Exception {
        AuditLog legacy = new AuditLog();
        legacy.setMethod("POST");
        legacy.setUri("/api/legacy");
        legacy.setStatus(200);
        Page<AuditLog> page = new Page<>(1, 10, 1);
        page.setRecords(java.util.List.of(legacy));
        when(auditLogService.page(any(Long.class), any(Long.class), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].actorType").value("LEGACY_UNRESOLVED"))
                .andExpect(jsonPath("$.data.records[0].confirmationSource").value("LEGACY_UNRESOLVED"));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void export_主体チャネルと相関属性をCSVへ出力する() throws Exception {
        AuditLog providerCallback = new AuditLog();
        providerCallback.setMethod("POST");
        providerCallback.setUri("/api/external-accounts/7/confirm-revoke");
        providerCallback.setStatus(200);
        providerCallback.setActorType("PROVIDER");
        providerCallback.setConfirmationSource("PROVIDER_CALLBACK");
        providerCallback.setReferenceType("EXTERNAL_ACCOUNT_REFERENCE");
        providerCallback.setReferenceId(7L);
        providerCallback.setCorrelationId("callback-correlation");
        providerCallback.setIdempotencyKey("provider-event-7");
        Page<AuditLog> page = new Page<>(1, 10000, 1);
        page.setRecords(java.util.List.of(providerCallback));
        when(auditLogService.page(any(Long.class), any(Long.class), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/audit-logs/export.csv"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("PROVIDER_CALLBACK"),
                                org.hamcrest.Matchers.containsString("callback-correlation"),
                                org.hamcrest.Matchers.containsString("provider-event-7"))));
    }
}
