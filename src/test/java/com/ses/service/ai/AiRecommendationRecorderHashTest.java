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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共有 H2 の id=1 シード／論理削除に依存しない。本テスト専用のエンジニア・案件を作成する。
 */
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
        long stamp = System.nanoTime();

        Engineer engineer = new Engineer();
        engineer.setFullName("AI-hash-e-" + stamp);
        engineer.setEmploymentType("正社員");
        engineer.setStatus("Bench");
        engineerMapper.insert(engineer);
        assertNotNull(engineer.getId(), "エンジニア挿入に失敗");
        final Long engineerId = engineer.getId();

        Long customerId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM m_customer", Long.class);
        jdbcTemplate.update(
                "INSERT INTO m_customer (id, company_name, deleted_flag) VALUES (?, ?, 0)",
                customerId, "AI-hash-cust-" + stamp);

        Project project = new Project();
        project.setProjectName("AI-hash-seed-" + stamp);
        project.setCustomerId(customerId);
        project.setStatus("募集中");
        project.setWorkLocation("東京都千代田区丸の内1-1-1");
        projectMapper.insert(project);
        assertNotNull(project.getId(), "案件挿入に失敗");
        final Long projectId = project.getId();

        Integer skillCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_skill_tag WHERE id = 1", Integer.class);
        if (skillCount != null && skillCount > 0
                && engineerSkillMapper.selectDetailByEngineerId(engineerId).isEmpty()) {
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
        // 引数順: useCase, actorUserId, results, sourceEngineerId, sourceProjectId
        recorder.recordMatch("MATCHING", 1L, List.of(dto), engineerId, projectId);

        AiRecommendationRun run = runMapper.selectById(dto.getRunId());
        assertNotNull(run, "recommendation run が未作成");
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
            recorder.recordMatch("MATCHING", 1L, List.of(extra), engineerId, projectId);
        }
        AiEvaluationDashboardDto dashboard = queryService.dashboard();
        assertTrue(dashboard.getSegments().stream().anyMatch(row ->
                        String.valueOf(row.get("segment")).contains("location:東京都千代田区")
                                || String.valueOf(row.get("segment")).startsWith("skill:")),
                String.valueOf(dashboard.getSegments()));
    }
}
