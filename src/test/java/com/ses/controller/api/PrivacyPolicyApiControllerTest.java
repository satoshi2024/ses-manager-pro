package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.config.feature.FeatureSwitchFilter;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.retention.policy.PrivacyPolicyExplanation;
import com.ses.privacy.production.retention.policy.PrivacyPolicyExplanationService;
import com.ses.privacy.production.retention.policy.PrivacyPolicySummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** NF07 policy 説明API（W3-14）: OFF は404、service 層の再認可で403、ApiResult 形式。 */
@WebMvcTest(PrivacyPolicyApiController.class)
class PrivacyPolicyApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PrivacyPolicyExplanationService explanationService;

    @Test
    @WithMockUser(roles = "管理者")
    void 公開スイッチOFFならFeatureSwitchFilterと同じ本文の404を返す() throws Exception {
        when(explanationService.list(anyLong(), anyLong()))
                .thenThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException());
        when(explanationService.explain(any(), any(), any(), any()))
                .thenThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException());

        for (String uri : List.of("/api/privacy/policies",
                "/api/privacy/policies/evaluate?moduleCode=NF08_AI_SUMMARY&dataElementId=AI_PROMPT_LOG"
                        + "&targetAction=EXTERNAL_DISPATCH&targetProvider=gemini-adapter")) {
            mockMvc.perform(get(uri))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value(FeatureSwitchFilter.NOT_FOUND_MESSAGE))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Test
    @WithMockUser(roles = "管理者")
    void service層の再認可で拒否されれば403のApiResultを返す() throws Exception {
        when(explanationService.detail(anyLong())).thenThrow(BusinessException.of(403, "error.accessDenied"));

        mockMvc.perform(get("/api/privacy/policies/3"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 一覧と説明照会はApiResultで返し実行認可を含めない() throws Exception {
        Page<PrivacyPolicySummary> page = new Page<>(1, 10);
        page.setRecords(List.of(new PrivacyPolicySummary(1L, "policy-1", "r1", "NF08_AI_SUMMARY", "AI_PROMPT_LOG",
                "EXTERNAL_DISPATCH", "gemini-adapter", "CANDIDATE", "a".repeat(64), null, null)));
        when(explanationService.list(1L, 10L)).thenReturn(page);
        when(explanationService.explain(eq("NF08_AI_SUMMARY"), eq("AI_PROMPT_LOG"), eq("EXTERNAL_DISPATCH"),
                eq("gemini-adapter"))).thenReturn(new PrivacyPolicyExplanation("POLICY_UNKNOWN",
                "STORE_UNAVAILABLE", null, null, "AI_PROMPT_LOG", "EXTERNAL_DISPATCH", "gemini-adapter", null,
                null, null, "この環境では production policy source を構成していません", false));

        mockMvc.perform(get("/api/privacy/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].policyId").value("policy-1"))
                .andExpect(jsonPath("$.data.records[0].registrationStatus").value("CANDIDATE"));
        mockMvc.perform(get("/api/privacy/policies/evaluate")
                        .param("moduleCode", "NF08_AI_SUMMARY").param("dataElementId", "AI_PROMPT_LOG")
                        .param("targetAction", "EXTERNAL_DISPATCH").param("targetProvider", "gemini-adapter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.decisionStatus").value("POLICY_UNKNOWN"))
                .andExpect(jsonPath("$.data.executionAuthorized").value(false));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 照会条件の不正はserviceの400をそのまま返す() throws Exception {
        when(explanationService.explain(any(), any(), any(), any()))
                .thenThrow(BusinessException.of(400, "error.privacy.invalidQuery"));

        mockMvc.perform(get("/api/privacy/policies/evaluate").param("moduleCode", "NF08 AI"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
