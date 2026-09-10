package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiRecommendationItem;
import com.ses.entity.AiRecommendationRun;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationItemMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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
    private AiArtifactVersionMapper artifactVersionMapper;
    @Autowired
    private AiRecommendationRunMapper recommendationRunMapper;
    @Autowired
    private AiRecommendationItemMapper recommendationItemMapper;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser(username = "10", roles = "営業")
    void 旧推薦feedbackはscopeContract未完成のためdisabled() throws Exception {
        Long itemId = itemForActor(10L);
        mockMvc.perform(post("/api/ai/feedback").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(itemId, "HOLD")))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value(503));
    }

    @Test
    @WithMockUser(username = "11", roles = "営業")
    void 旧推薦feedbackは他営業からも到達できない() throws Exception {
        Long itemId = itemForActor(10L);
        mockMvc.perform(post("/api/ai/feedback").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(itemId, "REJECT")))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value(503));
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
        AiArtifactVersion artifact = artifactVersionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiArtifactVersion>()
                        .eq(AiArtifactVersion::getUseCase, "MATCHING")
                        .eq(AiArtifactVersion::getStatus, "ACTIVE")
                        .last("LIMIT 1"));
        AiRecommendationRun run = new AiRecommendationRun();
        run.setTraceId(UUID.randomUUID().toString());
        run.setUseCase("MATCHING");
        run.setArtifactVersionId(artifact.getId());
        run.setActorUserId(actorUserId);
        run.setInputHash("1".repeat(64));
        run.setStatus("SUCCEEDED");
        run.setStatusVersion(0);
        recommendationRunMapper.insert(run);

        AiRecommendationItem item = new AiRecommendationItem();
        item.setRunId(run.getId());
        item.setRankNo(1);
        item.setTargetType("PROJECT");
        item.setTargetId(101L);
        item.setSelectedFlag(0);
        recommendationItemMapper.insert(item);
        return item.getId();
    }

    private String body(Long itemId, String decision) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "itemId", itemId,
                "decision", decision));
    }
}
