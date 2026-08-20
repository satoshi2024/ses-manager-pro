package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.ai.AiEvaluationDashboardDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiEvaluationApiControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void 管理者はcostが見える() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/ai/evaluations/dashboard").with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        AiEvaluationDashboardDto dto = data(result);
        assertTrue(dto.isCostVisible());
        assertTrue(dto.getMinSegmentCount() >= 5);
    }

    @Test
    @WithMockUser(username = "manager", roles = "マネージャー")
    void マネージャーはcostが不可視() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/ai/evaluations/dashboard"))
                .andExpect(status().isOk())
                .andReturn();
        AiEvaluationDashboardDto dto = data(result);
        assertFalse(dto.isCostVisible());
        assertTrue(dto.getVersions().stream().allMatch(v -> v.getCostJpy() == null));
    }

    @Test
    @WithMockUser(username = "hr", roles = "HR")
    void HRは評価APIに到達できない() throws Exception {
        mockMvc.perform(get("/api/ai/evaluations/dashboard"))
                .andExpect(status().isForbidden());
    }

    private AiEvaluationDashboardDto data(MvcResult result) throws Exception {
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        return objectMapper.treeToValue(tree.get("data"), AiEvaluationDashboardDto.class);
    }
}
