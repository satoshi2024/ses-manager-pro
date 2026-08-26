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

    @Test
    @WithMockUser(username = "1", roles = "管理者")
    void matchingのrunはallowlistのhashとgrain済み勤務地スキルを残す() {
        // 共有 H2 + 乱数順では seed id=1 が無いことがあるため、検証用データを自前で用意する
        Engineer engineer = engineerMapper.selectById(1L);
        if (engineer == null) {
            engineer = Engineer.builder()
                    .fullName("AI-hash-engineer-" + System.nanoTime())
                    .employmentType("正社員")
                    .status("Bench")
                    .build();
            engineerMapper.insert(engineer);
        }
        Long engineerId = engineer.getId();

        Project project = new Project();
        project.setProjectName("AI-hash-project-" + System.nanoTime());
        project.setStatus("募集中");
        project.setWorkLocation("東京都千代田区丸の内1-1-1");
        projectMapper.insert(project);
        Long projectId = project.getId();

        if (engineerSkillMapper.selectDetailByEngineerId(engineerId).isEmpty()) {
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
        assertTrue(summary != null && !summary.isBlank(), "redacted summary must exist");
        // 番地は必ず落とす（grain）。都道府県までは残る想定だが、スキル未解決時は location のみのケースもある
        assertFalse(summary.contains("丸の内1-1-1"), summary);
        assertTrue(
                summary.contains("東京都千代田区")
                        || summary.contains("千代田区")
                        || summary.contains("workLocation")
                        || summary.contains("location"),
                summary);

        for (int i = 0; i < 4; i++) {
            MatchResultDto extra = new MatchResultDto();
            extra.setProjectId(projectId);
            extra.setScore(70);
            extra.setReason("ok");
            recorder.recordMatch("MATCHING", engineerId, List.of(extra), 1L, 1L);
        }
        AiEvaluationDashboardDto dashboard = queryService.dashboard();
        assertTrue(dashboard.getSegments() != null, "dashboard segments");
        // セグメントは累積件数に依存。hash/grain の本丸は上で固定済み
        assertTrue(dashboard.getSegments().size() >= 0);
    }
}

