package com.ses.service.impl;

import com.ses.entity.ProjectSkill;
import com.ses.service.ProjectSkillService;
import com.ses.service.effective.EffectiveIntervalSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProjectSkillServiceImplTest {

    @Autowired
    private ProjectSkillService projectSkillService;

    @Autowired
    private com.ses.mapper.ProjectSkillEventMapper projectSkillEventMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("INSERT INTO m_skill_tag (id, skill_name) VALUES (30, 'Go')");
        String name = "proj-skill-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", name);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, name);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')",
                name, customerId);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, name);
    }

    @Test
    void replaceSkills_closesOpenIntervalOnChangeDate() {
        ProjectSkill skill = new ProjectSkill();
        skill.setSkillId(30L);
        skill.setRequiredLevel("上級");
        projectSkillService.replaceSkills(projectId, List.of(skill));

        projectSkillService.replaceSkills(projectId, List.of());

        List<com.ses.entity.ProjectSkillEvent> events = projectSkillEventMapper.selectByProjectId(projectId);
        assertEquals(1, events.size());
        LocalDate today = LocalDate.now();
        assertEquals(today.minusDays(1), events.get(0).getEffectiveTo());
        assertTrue(noActiveOpenEvent(events, 30L, today));
    }

    private static boolean noActiveOpenEvent(List<com.ses.entity.ProjectSkillEvent> events, Long skillId,
                                             LocalDate asOf) {
        return events.stream()
                .noneMatch(event -> "OPEN".equals(event.getEventType())
                        && skillId.equals(event.getSkillId())
                        && EffectiveIntervalSupport.isActiveAtAsOf(
                                event.getEffectiveFrom(), event.getEffectiveTo(), asOf));
    }
}
