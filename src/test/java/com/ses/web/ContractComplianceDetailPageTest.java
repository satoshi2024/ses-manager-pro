package com.ses.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T063 A1: 契約詳細ページ（compliance profile画面）のレンダリング回帰。
 * ページはJS駆動のため、サーバー側はviewの枠組み（loading/content/findingsカード・契約形態別section・
 * page-js読み込み）が存在することを検証する。field maskはAPI側（ContractComplianceProfileApiTest）と
 * JS側（contract-compliance.js）が担い、画面はデータに依存しない。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@WithMockUser(username = "admin", roles = "管理者")
class ContractComplianceDetailPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 契約詳細ページが表示でき共通レイアウトを持つ() throws Exception {
        String html = mockMvc.perform(get("/contract/detail/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("contract-detail-container");
        assertThat(html).contains("contract-detail-loading");
        assertThat(html).contains("id=\"cd-save-btn\"");
        assertThat(html).contains("data-section=\"dispatch\"");
        assertThat(html).contains("data-section=\"quasi\"");
        assertThat(html).contains("cpp-findings-card");
        assertThat(html).contains("contract-compliance.js");
        assertThat(html).contains("name=\"viewport\"");
        assertThat(html).contains("id=\"sidebar-toggle-btn\"");
    }

    @Test
    void 契約詳細ページは全契約形態の入力項目を持つ() throws Exception {
        String html = mockMvc.perform(get("/contract/detail/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 派遣固有: 保険・待遇・苦情・抵触日
        assertThat(html).contains("id=\"cpp-healthInsuranceStatus\"");
        assertThat(html).contains("id=\"cpp-dispatchFeeAmount\"");
        assertThat(html).contains("id=\"cpp-workplaceLimitationDate\"");
        assertThat(html).contains("id=\"cpp-sourceComplaintContactName\"");
        // 準委任/請負固有: 指示経路・再委託・検収
        assertThat(html).contains("id=\"cpp-instructionRoute\"");
        assertThat(html).contains("id=\"cpp-subcontractAllowed\"");
        assertThat(html).contains("id=\"cpp-acceptanceMethod\"");
        // 共通: 就業先・業務内容・責任者
        assertThat(html).contains("id=\"cpp-workplaceId\"");
        assertThat(html).contains("id=\"cpp-commandPersonName\"");
    }
}
