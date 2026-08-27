package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.ai.MatchResultDto;
import com.ses.service.ai.AiRecommendationRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AiFeedbackApiControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AiRecommendationRecorder recorder;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser(username = "10", roles = "営業")
    void 本人営業はfeedbackできる() throws Exception {
        Long itemId = itemForActor(10L);
        mockMvc.perform(post("/api/ai/feedback").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(itemId, "HOLD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser(username = "11", roles = "営業")
    void 他営業は403() throws Exception {
        Long itemId = itemForActor(10L);
        mockMvc.perform(post("/api/ai/feedback").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(itemId, "REJECT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @WithMockUser(username = "hr", roles = "HR")
    void HRは403() throws Exception {
        Long itemId = itemForActor(10L);
        mockMvc.perform(post("/api/ai/feedback").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(itemId, "ACCEPT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    private Long itemForActor(Long actorUserId) {
        MatchResultDto dto = new MatchResultDto();
        dto.setProjectId(101L);
        dto.setScore(80);
        dto.setReason("ok");
        recorder.recordMatch("MATCHING", actorUserId, List.of(dto));
        return dto.getItemId();
    }

    private String body(Long itemId, String decision) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "itemId", itemId,
                "decision", decision));
    }
}
