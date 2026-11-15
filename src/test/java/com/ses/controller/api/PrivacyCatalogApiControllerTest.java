package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.FeatureSwitchFilter;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyCoverageReport;
import com.ses.privacy.production.retention.catalog.PrivacyCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** NF07 catalog API（F2）: OFF は404、service 層の再認可で403、ApiResult 形式、書き込みの入口なし。 */
@WebMvcTest(PrivacyCatalogApiController.class)
class PrivacyCatalogApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PrivacyCatalogService catalogService;

    @Test
    @WithMockUser(roles = "管理者")
    void 公開スイッチOFFならFeatureSwitchFilterと同じ本文の404を返す() throws Exception {
        when(catalogService.catalog()).thenThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException());

        mockMvc.perform(get("/api/privacy/catalog"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(FeatureSwitchFilter.NOT_FOUND_MESSAGE))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void service層の再認可で拒否されれば403のApiResultを返す() throws Exception {
        when(catalogService.catalog()).thenThrow(BusinessException.of(403, "error.accessDenied"));

        mockMvc.perform(get("/api/privacy/catalog"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 登録済みproviderと未登録の要素を分けて理由と本人帰属の分類を返す() throws Exception {
        PrivacyCoverageReport report = new PrivacyCoverageReport("default",
                List.of(new PrivacyCoverageReport.ProviderEntry("engineer-profile",
                        List.of(new PrivacyCoverageReport.ProviderElementEntry("ENGINEER_PROFILE", false, "ATTRIBUTABLE",
                                List.of(new PrivacyCoverageReport.ProviderSubjectRule("ENGINEER", "t_engineer.id")))),
                        Map.of("t_engineer", List.of("id", "full_name")))),
                List.of(new PrivacyCoverageReport.ElementEntry("ENGINEER_PROFILE", "DB_COLUMN", "t_engineer.(allow-list)",
                        "engineer-profile", "ATTRIBUTABLE", true, "UNKNOWN", "UNKNOWN", "pii-inventory.md#DB-004", null)),
                List.of(new PrivacyCoverageReport.ElementEntry("AUDIT_LOG", "DB_COLUMN", "t_audit_log", null, "UNKNOWN",
                        false, "UNKNOWN", "UNKNOWN", "pii-inventory.md#DB-019", "PROVIDER_UNREGISTERED")),
                List.of(), new PrivacyCoverageReport.Summary(1, 1, 1, 0));
        when(catalogService.catalog()).thenReturn(report);

        mockMvc.perform(get("/api/privacy/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.providers[0].providerId").value("engineer-profile"))
                .andExpect(jsonPath("$.data.providers[0].columnAllowList.t_engineer[1]").value("full_name"))
                .andExpect(jsonPath("$.data.registeredElements[0].subjectAttribution").value("ATTRIBUTABLE"))
                .andExpect(jsonPath("$.data.unregisteredElements[0].dataElementId").value("AUDIT_LOG"))
                .andExpect(jsonPath("$.data.unregisteredElements[0].reason").value("PROVIDER_UNREGISTERED"))
                .andExpect(jsonPath("$.data.unregisteredElements[0].subjectAttribution").value("UNKNOWN"));
    }

    @Test
    void 入口はGETだけで書き込みのmappingを持たない() {
        for (java.lang.reflect.Method method : PrivacyCatalogApiController.class.getDeclaredMethods()) {
            boolean mapped = method.isAnnotationPresent(org.springframework.web.bind.annotation.RequestMapping.class)
                    || method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)
                    || method.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class)
                    || method.isAnnotationPresent(org.springframework.web.bind.annotation.PatchMapping.class)
                    || method.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class);
            org.assertj.core.api.Assertions.assertThat(mapped).as(method.getName()).isFalse();
        }
        org.assertj.core.api.Assertions.assertThat(java.util.Arrays.stream(
                        PrivacyCatalogApiController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)))
                .hasSize(1);
    }
}
