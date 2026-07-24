package com.ses.controller.api;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.dto.compliance.ContractComplianceDto;
import com.ses.service.compliance.LaborComplianceService;
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

@WebMvcTest(ComplianceApiController.class)
class ComplianceApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LaborComplianceService laborComplianceService;

    @Test
    @WithMockUser
    void findings_該当契約一覧を返す() throws Exception {
        ContractComplianceDto dto = new ContractComplianceDto();
        dto.setContractId(1L);
        dto.setContractNo("C-202607-0001");
        dto.setFindings(List.of(new ComplianceFinding("TIER_EXCEEDED", "warning", "段数超過", 1L)));
        when(laborComplianceService.findCurrentRisks()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/compliance/findings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].contractNo").value("C-202607-0001"))
                .andExpect(jsonPath("$.data[0].findings[0].code").value("TIER_EXCEEDED"));
    }
}
