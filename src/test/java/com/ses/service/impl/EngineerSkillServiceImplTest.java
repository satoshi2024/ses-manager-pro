package com.ses.service.impl;

import com.ses.entity.EngineerSkill;
import com.ses.service.EngineerSkillService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
