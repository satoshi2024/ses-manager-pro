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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
