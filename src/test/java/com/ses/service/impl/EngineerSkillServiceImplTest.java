package com.ses.service.impl;

import com.ses.entity.EngineerSkill;
import com.ses.service.EngineerSkillService;
import com.ses.service.effective.EffectiveIntervalSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.ses.common.exception.BusinessException;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@org.springframework.test.context.jdbc.Sql("/sql/engineer-schema-h2.sql")
// replaceSkills は要員・スキルタグの存在を検証するため、対象データをseedする。
@org.springframework.test.context.jdbc.Sql(statements = {
        "INSERT INTO t_engineer (id, full_name) VALUES (1, 'テスト要員')",
        "INSERT INTO m_skill_tag (id, skill_name) VALUES (10, 'Java'), (20, 'Python')"
})
public class EngineerSkillServiceImplTest {

    @Autowired
    private EngineerSkillService engineerSkillService;

    @Autowired
    private com.ses.mapper.EngineerSkillEventMapper engineerSkillEventMapper;

    @Test
    public void testReplaceSkills() {
        Long engineerId = 1L;

        // Create skills with duplicates and different engineer IDs to test distinct and enforcement logic
        EngineerSkill s1 = new EngineerSkill();
        s1.setSkillId(10L);
        s1.setProficiency("上級");
        s1.setExperienceYears(5);
        s1.setEngineerId(999L); // Should be overwritten

        EngineerSkill s2 = new EngineerSkill();
        s2.setSkillId(20L);
        s2.setProficiency("中級");
        s2.setExperienceYears(3);

        EngineerSkill s3 = new EngineerSkill();
        s3.setSkillId(10L); // Duplicate
        s3.setProficiency("初級");
        s3.setExperienceYears(1);

        List<EngineerSkill> skills = Arrays.asList(s1, s2, s3);

        // Replace skills
        engineerSkillService.replaceSkills(engineerId, skills);

        // Verify
        List<EngineerSkill> afterReplace = engineerSkillService.list(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EngineerSkill>()
                        .eq(EngineerSkill::getEngineerId, engineerId)
        );

        // Should have 2 skills (10L and 20L), 10L duplicate is ignored
        assertEquals(2, afterReplace.size());
        
        // Ensure all have the correct engineer ID
        for (EngineerSkill es : afterReplace) {
            assertEquals(engineerId, es.getEngineerId());
        }

        // Test replace with empty list
        engineerSkillService.replaceSkills(engineerId, Arrays.asList());
        List<EngineerSkill> afterEmpty = engineerSkillService.list(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EngineerSkill>()
                        .eq(EngineerSkill::getEngineerId, engineerId)
        );
        assertEquals(0, afterEmpty.size());
    }

    @Test
    public void replaceSkills_closesOpenIntervalAndSetsSupersedes() {
        Long engineerId = 1L;
        EngineerSkill skill = new EngineerSkill();
        skill.setSkillId(10L);
        skill.setProficiency("上級");
        engineerSkillService.replaceSkills(engineerId, List.of(skill));

        List<com.ses.entity.EngineerSkillEvent> afterInsert = engineerSkillEventMapper.selectByEngineerId(engineerId);
        assertEquals(1, afterInsert.size());
        com.ses.entity.EngineerSkillEvent open = afterInsert.get(0);
        assertEquals("OPEN", open.getEventType());
        assertNull(open.getEffectiveTo());

        engineerSkillService.replaceSkills(engineerId, List.of());
        List<com.ses.entity.EngineerSkillEvent> afterClear = engineerSkillEventMapper.selectByEngineerId(engineerId);
        assertEquals(1, afterClear.size());
        LocalDate today = LocalDate.now();
        assertEquals(today.minusDays(1), afterClear.get(0).getEffectiveTo());
        assertTrue(noActiveOpenEvent(afterClear, 10L, today));
        assertTrue(noActiveOpenEvent(afterClear, 10L, today.plusDays(1)));

        EngineerSkill reopened = new EngineerSkill();
        reopened.setSkillId(10L);
        reopened.setProficiency("中級");
        engineerSkillService.replaceSkills(engineerId, List.of(reopened));

        List<com.ses.entity.EngineerSkillEvent> afterReopen = engineerSkillEventMapper.selectByEngineerId(engineerId);
        assertEquals(2, afterReopen.size());
        com.ses.entity.EngineerSkillEvent closed = afterReopen.get(0);
        com.ses.entity.EngineerSkillEvent reopenedOpen = afterReopen.get(1);
        assertEquals("OPEN", reopenedOpen.getEventType());
        assertEquals(closed.getId(), reopenedOpen.getSupersedesEventId());
        assertNull(reopenedOpen.getEffectiveTo());
    }

    @Test
    public void testReplaceSkills_nullSkillIdThrowsException() {
        Long engineerId = 1L;

        EngineerSkill s1 = new EngineerSkill();
        s1.setSkillId(10L);
        s1.setProficiency("上級");

        EngineerSkill s2 = new EngineerSkill();
        s2.setSkillId(null);
        s2.setProficiency("中級");

        List<EngineerSkill> skills = Arrays.asList(s1, s2);

        BusinessException ex = assertThrows(BusinessException.class, () -> engineerSkillService.replaceSkills(engineerId, skills));
        assertTrue(ex.getMessage().contains("error.skill.notFound"));
    }

    private static boolean noActiveOpenEvent(List<com.ses.entity.EngineerSkillEvent> events, Long skillId,
                                             LocalDate asOf) {
        return events.stream()
                .noneMatch(event -> "OPEN".equals(event.getEventType())
                        && skillId.equals(event.getSkillId())
                        && EffectiveIntervalSupport.isActiveAtAsOf(
                                event.getEffectiveFrom(), event.getEffectiveTo(), asOf));
    }
}
