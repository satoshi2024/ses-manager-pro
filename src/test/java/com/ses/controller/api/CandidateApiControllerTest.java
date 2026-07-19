package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.Candidate;
import com.ses.service.CandidateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 候補者APIの結合テスト(タスクA テスト要件: 権限(HR/営業/管理者のみ許可)の確認を含む)。
 *
 * 権限はDB駆動のMenuPermissionFilterで制御されるため(V16マイグレーションでcandidateメニューを
 * 管理者/営業/HRにのみ許可)、テストでも同じ内容をm_menu/t_role_menuへ直接投入して検証する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class CandidateApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CandidateService candidateService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedMenuPermission() {
        jdbcTemplate.update("DELETE FROM t_role_menu");
        jdbcTemplate.update("DELETE FROM m_menu");
        jdbcTemplate.update(
                "INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) VALUES ('candidate', '候補者管理', '/candidate', '/api/candidates', 67)");
        Long menuId = jdbcTemplate.queryForObject("SELECT id FROM m_menu WHERE menu_key = 'candidate'", Long.class);
        jdbcTemplate.update("INSERT INTO t_role_menu (role, menu_id) VALUES ('管理者', ?)", menuId);
        jdbcTemplate.update("INSERT INTO t_role_menu (role, menu_id) VALUES ('営業', ?)", menuId);
        jdbcTemplate.update("INSERT INTO t_role_menu (role, menu_id) VALUES ('HR', ?)", menuId);
    }

    private Candidate seedCandidate(String stage) {
        Candidate candidate = new Candidate();
        candidate.setName("APIテスト候補者");
        candidate.setSkillSummary("Python 5年");
        candidate.setCurrentStage(stage);
        candidateService.save(candidate);
        return candidate;
    }

    @Test
    @WithMockUser(username = "test", roles = {"営業"})
    void testCreateCandidate() throws Exception {
        Candidate candidate = new Candidate();
        candidate.setName("新規候補者");
        candidate.setSkillSummary("Go 2年");

        mockMvc.perform(post("/api/candidates")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is(true)));

        assertEquals(1, candidateService.count());
    }

    @Test
    @WithMockUser(username = "test", roles = {"HR"})
    void testGetCandidateList() throws Exception {
        seedCandidate("応募受付");

        mockMvc.perform(get("/api/candidates").param("name", "APIテスト"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.records", hasSize(1)))
                .andExpect(jsonPath("$.data.records[0].name", is("APIテスト候補者")));
    }

    @Test
    @WithMockUser(username = "test", roles = {"管理者"})
    void testChangeStage_success() throws Exception {
        Candidate candidate = seedCandidate("応募受付");

        mockMvc.perform(post("/api/candidates/" + candidate.getId() + "/activities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"書類選考\",\"remarks\":\"通過\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is(true)));

        Candidate updated = candidateService.getById(candidate.getId());
        assertEquals("書類選考", updated.getCurrentStage());
    }

    @Test
    @WithMockUser(username = "test", roles = {"管理者"})
    void testChangeStage_rejectedWithoutReason_returnsBusinessError() throws Exception {
        Candidate candidate = seedCandidate("一次面談");

        // BusinessException.of(messageKey) はデフォルトでcode=500を使う(BusinessException.java参照)
        mockMvc.perform(post("/api/candidates/" + candidate.getId() + "/activities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"不採用\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)));

        Candidate unchanged = candidateService.getById(candidate.getId());
        assertEquals("一次面談", unchanged.getCurrentStage());
    }

    @Test
    @WithMockUser(username = "test", roles = {"管理者"})
    void testConvertToEngineer_notHiredStage_returnsBusinessError() throws Exception {
        Candidate candidate = seedCandidate("内定");

        mockMvc.perform(post("/api/candidates/" + candidate.getId() + "/convert-to-engineer")
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(400)));
    }

    @Test
    @WithMockUser(username = "test", roles = {"管理者"})
    void testConvertToEngineer_hiredStage_returnsInitialDto() throws Exception {
        Candidate candidate = seedCandidate("入社");

        mockMvc.perform(post("/api/candidates/" + candidate.getId() + "/convert-to-engineer")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.fullName", is("APIテスト候補者")))
                .andExpect(jsonPath("$.data.resumeSummary", is("Python 5年")));
    }

    @Test
    @WithMockUser(username = "test", roles = {"管理者"})
    void testLinkConvertedEngineer() throws Exception {
        Candidate candidate = seedCandidate("入社");

        mockMvc.perform(put("/api/candidates/" + candidate.getId() + "/converted-engineer")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"engineerId\":123}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is(true)));

        Candidate updated = candidateService.getById(candidate.getId());
        assertEquals(123L, updated.getConvertedEngineerId());
    }

    @Test
    @WithMockUser(username = "test", roles = {"管理者"})
    void testDeleteCandidate() throws Exception {
        Candidate candidate = seedCandidate("応募受付");

        mockMvc.perform(delete("/api/candidates/" + candidate.getId())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is(true)));

        assertNull(candidateService.getById(candidate.getId()));
    }

    // ===== 権限検証: HR/営業/管理者のみ許可、他ロールは403 =====

    @Test
    @WithMockUser(username = "test", roles = {"マネージャー"})
    void testGetCandidateList_managerRoleForbidden() throws Exception {
        mockMvc.perform(get("/api/candidates"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "test", roles = {"HR"})
    void testGetCandidateList_hrRoleAllowed() throws Exception {
        mockMvc.perform(get("/api/candidates"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "test", roles = {"営業"})
    void testGetCandidateList_salesRoleAllowed() throws Exception {
        mockMvc.perform(get("/api/candidates"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "test", roles = {"管理者"})
    void testGetCandidateList_adminRoleAllowed() throws Exception {
        mockMvc.perform(get("/api/candidates"))
                .andExpect(status().isOk());
    }
}
