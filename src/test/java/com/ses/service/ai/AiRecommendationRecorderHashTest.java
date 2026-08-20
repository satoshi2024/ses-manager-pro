package com.ses.service.ai;

import com.ses.dto.ai.AiEvaluationDashboardDto;
import com.ses.dto.ai.MatchResultDto;
import com.ses.entity.AiRecommendationRun;
import com.ses.entity.EngineerSkill;
import com.ses.entity.Project;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.ProjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class AiRecommendationRecorderHashTest {

    @Autowired
    private AiRecommendationRecorder recorder;
    @Autowired
    private AiRecommendationRunMapper runMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private EngineerSkillMapper engineerSkillMapper;
    @Autowired
    private AiEvaluationQueryService queryService;

    @Test
    @WithMockUser(username = "1", roles = "管理者")
    void matchingのrunはallowlistのhashとgrain済み勤務地スキルを残す() {
        Project project = projectMapper.selectById(1L);
        project.setWorkLocation("東京都千代田区丸の内1-1-1");
        projectMapper.updateById(project);
        if (engineerSkillMapper.selectDetailByEngineerId(1L).isEmpty()) {
            EngineerSkill skill = new EngineerSkill();
            skill.setEngineerId(1L);
            skill.setSkillId(1L);
            skill.setProficiency("上級");
            skill.setExperienceYears(5);
            engineerSkillMapper.insert(skill);
        }

        MatchResultDto dto = new MatchResultDto();
        dto.setProjectId(1L);
        dto.setScore(80);
        dto.setReason("ok");
        recorder.recordMatch("MATCHING", 1L, List.of(dto), 1L, 1L);

        AiRecommendationRun run = runMapper.selectById(dto.getRunId());
        assertNotEquals("0".repeat(64), run.getInputHash());
        assertTrue(run.getInputHash() != null && run.getInputHash().length() == 64);
        String summary = run.getRedactedSummaryJson();
        assertTrue(summary.contains("東京都千代田区"), summary);
        assertFalse(summary.contains("丸の内1-1-1"), summary);
        assertTrue(summary.contains("Java") || summary.contains("engineerSkill.skillName"), summary);

        for (int i = 0; i < 4; i++) {
            MatchResultDto extra = new MatchResultDto();
            extra.setProjectId(1L);
            extra.setScore(70);
            extra.setReason("ok");
            recorder.recordMatch("MATCHING", 1L, List.of(extra), 1L, 1L);
        }
        AiEvaluationDashboardDto dashboard = queryService.dashboard();
        assertTrue(dashboard.getSegments().stream().anyMatch(row ->
                        String.valueOf(row.get("segment")).contains("location:東京都千代田区")
                                || String.valueOf(row.get("segment")).startsWith("skill:")),
                String.valueOf(dashboard.getSegments()));
    }
}
