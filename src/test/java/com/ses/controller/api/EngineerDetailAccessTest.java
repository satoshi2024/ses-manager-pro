package com.ses.controller.api;

import com.ses.entity.Engineer;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerSalesService;
import com.ses.service.EngineerService;
import com.ses.service.ProposalService;
import com.ses.service.RetentionRiskService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 要員detail APIのscope内・scope外・不存在契約を固定する。 */
@WebMvcTest(EngineerApiController.class)
class EngineerDetailAccessTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private EngineerService engineerService;
    @MockBean
    private EngineerSalesService engineerSalesService;
    @MockBean
    private DataScopeService dataScopeService;
    @MockBean
    private OrganizationScopeService organizationScopeService;
    @MockBean
    private ProposalService proposalService;
    @MockBean
    private RetentionRiskService retentionRiskService;
    @MockBean
    private EngineerAccountLinkService engineerAccountLinkService;

    @BeforeEach
    void setUp() {
        when(dataScopeService.isScoped()).thenReturn(false);
        when(organizationScopeService.hasFullAccess()).thenReturn(false);
        when(organizationScopeService.allowedEngineerIds(any(LocalDate.class))).thenReturn(Set.of(1L, 3L));
        when(organizationScopeService.intersectWithDataScope(any(), isNull())).thenReturn(Set.of(1L, 3L));
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void scope内detailは実dataを200で返す() throws Exception {
        Engineer engineer = Engineer.builder().fullName("範囲内 要員").status("Bench").build();
        engineer.setId(1L);
        when(engineerService.getById(1L)).thenReturn(engineer);

        mockMvc.perform(get("/api/engineers/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fullName").value("範囲内 要員"));
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void scope外detailはservice取得前に非漏えい404() throws Exception {
        mockMvc.perform(get("/api/engineers/2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        verify(engineerService, never()).getById(2L);
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void scope内IDでも不存在なら同じ404() throws Exception {
        when(engineerService.getById(3L)).thenReturn(null);

        mockMvc.perform(get("/api/engineers/3"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
