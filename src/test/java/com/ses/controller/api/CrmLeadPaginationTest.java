package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.Lead;
import com.ses.mapper.SysUserMapper;
import com.ses.service.LeadService;
import com.ses.service.security.CrmScopeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LeadApiController.class)
@DisplayName("CRM Lead pagination APIテスト (R3-014)")
class CrmLeadPaginationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LeadService leadService;

    @MockBean
    private SysUserMapper sysUserMapper;

    @MockBean
    private CrmScopeService crmScopeService;

    @Test
    @WithMockUser(roles = "営業")
    @DisplayName("lead/listはPage<Lead>を返し20件単位で取得できる")
    void listReturnsPagedLeads() throws Exception {
        Page<Lead> page = new Page<>(1, 20, 41);
        Lead lead = new Lead();
        lead.setId(1L);
        lead.setCompanyName("テストリード株式会社");
        lead.setStatus("未対応");
        page.setRecords(List.of(lead));

        when(leadService.page(eq(null), eq(null), eq(1L), eq(20L)))
                .thenReturn(page);

        mockMvc.perform(get("/api/crm/leads")
                        .param("current", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(41))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.records[0].companyName").value("テストリード株式会社"));
    }
}
