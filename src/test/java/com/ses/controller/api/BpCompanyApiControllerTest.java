package com.ses.controller.api;

import com.ses.entity.BpCompany;
import com.ses.service.BpCompanyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BpCompanyApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BpCompanyService bpCompanyService;

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("取引停止(SUSPENDED)のBP会社がAutocomplete候補APIの検索結果からSQLレベルで除外されることの検証")
    void autocompleteExcludesSuspendedCompaniesTest() throws Exception {
        // 1. 有効なBP会社
        BpCompany activeCompany = BpCompany.builder()
                .legalName("アクティブパートナー株式会社")
                .entityType("CORPORATE")
                .status("ACTIVE")
                .build();
        bpCompanyService.createBpCompany(activeCompany);

        // 2. 取引停止のBP会社
        BpCompany suspendedCompany = BpCompany.builder()
                .legalName("取引停止パートナー株式会社")
                .entityType("CORPORATE")
                .status("SUSPENDED")
                .build();
        bpCompanyService.createBpCompany(suspendedCompany);

        // 3. Autocomplete API 呼出し
        mockMvc.perform(get("/api/bp-companies/autocomplete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[?(@.legalName == 'アクティブパートナー株式会社')]").exists())
                .andExpect(jsonPath("$.data[?(@.legalName == '取引停止パートナー株式会社')]").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("口座登録APIはマスク済みDTOのみを返し、復号値・暗号文をレスポンスに含めない")
    void bankAccountApiReturnsMaskedDtoOnly() throws Exception {
        BpCompany company = BpCompany.builder()
                .legalName("口座APIテストBP")
                .entityType("CORPORATE")
                .status("ACTIVE")
                .build();
        bpCompanyService.createBpCompany(company);

        mockMvc.perform(post("/api/bp-companies/{id}/bank-accounts", company.getId())
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"bankName\":\"三井住友銀行\",\"branchName\":\"本店\",\"accountType\":\"ORDINARY\","
                                + "\"accountNumber\":\"9876543210\",\"accountHolder\":\"テストタロウ\","
                                + "\"validFrom\":\"2026-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.maskedLabel").value("****3210"))
                .andExpect(jsonPath("$.data.approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.encryptedAccountNumber").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("口座承認APIはPENDINGからの状態CAS遷移で承認できる")
    void bankAccountApprovalApi() throws Exception {
        BpCompany company = BpCompany.builder()
                .legalName("口座承認テストBP")
                .entityType("CORPORATE")
                .status("ACTIVE")
                .build();
        bpCompanyService.createBpCompany(company);
        com.ses.dto.bpcompany.BpBankAccountDto account = bpCompanyService.addBankAccount(
                company.getId(), "みずほ銀行", "本店", "ORDINARY", "1234567890", "テストタロウ",
                java.time.LocalDate.of(2026, 1, 1), null);

        mockMvc.perform(put("/api/bp-companies/{id}/bank-accounts/{accountId}/approval",
                        company.getId(), account.getId())
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"approved\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser(roles = "営業")
    @DisplayName("営業ロールによる法適用区分更新は403 Forbiddenで拒否されることの検証 (P1-03)")
    void salesRoleCannotUpdateApplicabilityTest() throws Exception {
        BpCompany company = BpCompany.builder()
                .legalName("権限テストBP")
                .entityType("CORPORATE")
                .status("ACTIVE")
                .build();
        bpCompanyService.createBpCompany(company);

        mockMvc.perform(put("/api/bp-companies/{id}/compliance-applicability", company.getId())
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"applicability\":\"EXEMPT\",\"note\":\"営業からの変更試行\"}"))
                .andExpect(status().isForbidden());
    }
}
