package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.ai.AiEvaluationDashboardDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.test.context.support.WithMockUser;

import java.nio.charset.StandardCharsets;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
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
        assertTrue(dto.getVersions().stream().allMatch(v -> v.getPrecisionAt5() >= 0 && v.getPrecisionAt10() >= 0));
        String html = new ClassPathResource("templates/ai/evaluation.html")
                .getContentAsString(StandardCharsets.UTF_8);
        String js = new ClassPathResource("static/js/modules/ai-evaluation.js")
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(html.contains("ai.evaluation.col.precision5"));
        assertTrue(html.contains("ai.evaluation.col.precision10"));
        assertTrue(html.contains("ai.evaluation.segments"));
        assertTrue(html.contains("aiEvalSegments"));
        assertTrue(js.contains("precisionAt5"));
        assertTrue(js.contains("precisionAt10"));
        assertTrue(js.contains("data.segments"));
        assertTrue(js.contains("aiEvalSegments"));
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

    @Test
    @WithMockUser(username = "sales", roles = "営業")
    void 営業はlistとrunが403() throws Exception {
        mockMvc.perform(get("/api/ai/evaluations"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/ai/evaluations/run")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"candidateVersionId\":1,\"baselineVersionId\":2}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "sales", roles = "営業")
    void 営業はdashboardに到達できる() throws Exception {
        mockMvc.perform(get("/api/ai/evaluations/dashboard"))
                .andExpect(status().isOk());
    }

    private AiEvaluationDashboardDto data(MvcResult result) throws Exception {
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        return objectMapper.treeToValue(tree.get("data"), AiEvaluationDashboardDto.class);
    }
}
