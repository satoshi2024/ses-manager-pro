package com.ses.service.ai;

import com.ses.dto.ai.AiEvaluationDashboardDto;
import com.ses.dto.ai.MatchResultDto;
import com.ses.entity.AiRecommendationRun;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerSkill;
import com.ses.entity.Project;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AiRecommendationRecorderHashTest {

    @Autowired
    private AiRecommendationRecorder recorder;
    @Autowired
    private AiRecommendationRunMapper runMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private EngineerSkillMapper engineerSkillMapper;
    @Autowired
    private AiEvaluationQueryService queryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @WithMockUser(username = "1", roles = "管理者")
    void matchingのrunはallowlistのhashとgrain済み勤務地スキルを残す() {
        Engineer engineer = engineerMapper.selectById(1L);
        if (engineer == null) {
            jdbcTemplate.update(
                    "INSERT INTO t_engineer (id, full_name, employment_type, status) VALUES (1, 'AI-hash-e1', '正社員', 'Bench')");
            engineer = engineerMapper.selectById(1L);
        }
        final Long engineerId = engineer.getId();

        Project project = projectMapper.selectById(1L);
        if (project == null) {
            jdbcTemplate.update(
                    "INSERT INTO m_customer (id, company_name) VALUES (91001, 'AI-hash-cust')");
            project = new Project();
            project.setProjectName("AI-hash-seed-" + System.nanoTime());
            project.setCustomerId(91001L);
            project.setStatus("募集中");
            project.setWorkLocation("東京都千代田区丸の内1-1-1");
            projectMapper.insert(project);
        } else {
            project.setWorkLocation("東京都千代田区丸の内1-1-1");
            projectMapper.updateById(project);
        }
        final Long projectId = project.getId();

        Integer skillCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM m_skill_tag WHERE id = 1", Integer.class);
        if (skillCount != null && skillCount > 0 && engineerSkillMapper.selectDetailByEngineerId(engineerId).isEmpty()) {
            EngineerSkill skill = new EngineerSkill();
            skill.setEngineerId(engineerId);
            skill.setSkillId(1L);
            skill.setProficiency("上級");
            skill.setExperienceYears(5);
            engineerSkillMapper.insert(skill);
        }

        MatchResultDto dto = new MatchResultDto();
        dto.setProjectId(projectId);
        dto.setScore(80);
        dto.setReason("ok");
        recorder.recordMatch("MATCHING", engineerId, List.of(dto), 1L, 1L);

        AiRecommendationRun run = runMapper.selectById(dto.getRunId());
        assertNotEquals("0".repeat(64), run.getInputHash());
        assertTrue(run.getInputHash() != null && run.getInputHash().length() == 64);
        String summary = run.getRedactedSummaryJson();
        assertTrue(summary != null && summary.contains("東京都千代田区"), summary);
        assertFalse(summary.contains("丸の内1-1-1"), summary);

        for (int i = 0; i < 4; i++) {
            MatchResultDto extra = new MatchResultDto();
            extra.setProjectId(projectId);
            extra.setScore(70);
            extra.setReason("ok");
            recorder.recordMatch("MATCHING", engineerId, List.of(extra), 1L, 1L);
        }
        AiEvaluationDashboardDto dashboard = queryService.dashboard();
        assertTrue(dashboard.getSegments().stream().anyMatch(row ->
                        String.valueOf(row.get("segment")).contains("location:東京都千代田区")
                                || String.valueOf(row.get("segment")).startsWith("skill:")),
                String.valueOf(dashboard.getSegments()));
    }
}
