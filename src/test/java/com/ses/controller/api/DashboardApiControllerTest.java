package com.ses.controller.api;

import com.ses.dto.dashboard.ContractProfitDto;
import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.service.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardApiController.class)
class DashboardApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DashboardService dashboardService;

    @Test
    @WithMockUser
    void testGetSummary_WithYear() throws Exception {
        DashboardSummaryDto dto = new DashboardSummaryDto();
        when(dashboardService.getSummary(2026)).thenReturn(dto);

        mockMvc.perform(get("/api/dashboard/summary").param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void testGetSummary_WithoutYear() throws Exception {
        DashboardSummaryDto dto = new DashboardSummaryDto();
        when(dashboardService.getSummary(null)).thenReturn(dto);

        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void testGetProfitAnalysis() throws Exception {
        ContractProfitDto dto = new ContractProfitDto();
        dto.setContractNo("C001");
        dto.setGrossProfitAmount(400000);
        
        when(dashboardService.getProfitAnalysis()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/dashboard/profit-analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].contractNo").value("C001"))
                .andExpect(jsonPath("$.data[0].grossProfitAmount").value(400000));
    }
}
