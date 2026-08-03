package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.crm.OpportunitySaveRequest;
import com.ses.service.CrmKpiService;
import com.ses.service.OpportunityService;
import com.ses.service.security.CrmScopeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 顧客参照エラーがAPIの404 JSONとして返ることを固定する。 */
@WebMvcTest(OpportunityApiController.class)
class OpportunityApiControllerReferenceValidationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private OpportunityService opportunityService;
    @MockBean
    private CrmScopeService crmScopeService;
    @MockBean
    private CrmKpiService crmKpiService;

    @Test
    @WithMockUser(roles = "管理者")
    void create_不存在顧客は404ApiResultでSQL情報を含めない() throws Exception {
        when(opportunityService.createBasic(any()))
                .thenThrow(BusinessException.of(404, "error.crm.customerNotFound"));

        mockMvc.perform(post("/api/crm/opportunities").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(9999L, null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("顧客が存在しないか、アクセス権限がありません"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("fk_opportunity_customer"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("SQLException"))));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void update_不存在顧客は404ApiResultで500へ変換しない() throws Exception {
        when(opportunityService.updateBasic(eq(10L), any()))
                .thenThrow(BusinessException.of(404, "error.crm.customerNotFound"));

        mockMvc.perform(put("/api/crm/opportunities/10").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(9999L, 2))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("顧客が存在しないか、アクセス権限がありません"));
    }

    private OpportunitySaveRequest request(Long customerId, Integer version) {
        OpportunitySaveRequest request = new OpportunitySaveRequest();
        request.setCustomerId(customerId);
        request.setTitle("参照検証商機");
        request.setOwnerUserId(10L);
        request.setProbability(20);
        request.setVersion(version);
        return request;
    }
}
