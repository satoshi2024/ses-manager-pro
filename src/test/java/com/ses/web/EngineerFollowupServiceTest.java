package com.ses.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.BaseIntegrationTest;
import com.ses.entity.Engineer;
import com.ses.mapper.EngineerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FR-11 要員フォロー記録API（/api/engineers/{id}/followups）のCRUD・IDOR検証
 */
@Sql("/sql/engineer-schema-h2.sql")
class EngineerFollowupServiceTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private Long engineer1Id;
    private Long engineer2Id;

    @BeforeEach
    void setUp() {
        engineer1Id = insertEngineer("要員 一郎");
        engineer2Id = insertEngineer("要員 二郎");
    }

    private Long insertEngineer(String name) {
        Engineer e = Engineer.builder().fullName(name).status("稼動中").build();
        engineerMapper.insert(e);
        return e.getId();
    }

    private Map<String, Object> followupPayload(String nextDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("followupType", "1on1");
        body.put("followupDate", "2026-07-01");
        body.put("satisfaction", 4);
        body.put("topic", "定期面談");
        body.put("content", "特に問題なし");
        body.put("nextDate", nextDate);
        return body;
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void フォローを登録し一覧と詳細に反映される() throws Exception {
        mockMvc.perform(post("/api/engineers/" + engineer1Id + "/followups")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(followupPayload("2026-08-01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/engineers/" + engineer1Id + "/followups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].topic").value("定期面談"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void フォローを更新削除できる() throws Exception {
        String createRes = mockMvc.perform(post("/api/engineers/" + engineer1Id + "/followups")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(followupPayload("2026-08-01"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(createRes.contains("\"code\":200"));

        Long followupId = fetchFirstFollowupId(engineer1Id);

        Map<String, Object> update = followupPayload("2026-09-01");
        update.put("topic", "更新後トピック");
        mockMvc.perform(put("/api/engineers/" + engineer1Id + "/followups/" + followupId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/engineers/" + engineer1Id + "/followups/" + followupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topic").value("更新後トピック"));

        mockMvc.perform(delete("/api/engineers/" + engineer1Id + "/followups/" + followupId)
                        .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/engineers/" + engineer1Id + "/followups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void 他要員IDでの更新削除はIDORとして404になる() throws Exception {
        mockMvc.perform(post("/api/engineers/" + engineer1Id + "/followups")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(followupPayload("2026-08-01"))))
                .andExpect(status().isOk());

        Long followupId = fetchFirstFollowupId(engineer1Id);

        // URLパスの engineerId (=engineer2) と実際の所有者(=engineer1) が異なるため404
        mockMvc.perform(get("/api/engineers/" + engineer2Id + "/followups/" + followupId))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/engineers/" + engineer2Id + "/followups/" + followupId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(followupPayload("2026-08-01"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/engineers/" + engineer2Id + "/followups/" + followupId)
                        .with(csrf()))
                .andExpect(status().isNotFound());

        // 本来の所有者からは変わらず参照できる
        mockMvc.perform(get("/api/engineers/" + engineer1Id + "/followups/" + followupId))
                .andExpect(status().isOk());
    }

    private Long fetchFirstFollowupId(Long engineerId) throws Exception {
        String listRes = mockMvc.perform(get("/api/engineers/" + engineerId + "/followups"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(listRes);
        return root.get("data").get(0).get("id").asLong();
    }
}
