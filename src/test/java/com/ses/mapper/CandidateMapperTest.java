package com.ses.mapper;

import com.ses.BaseIntegrationTest;
import com.ses.entity.Candidate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CandidateMapperの基本CRUD検証(F1タスクのテスト要件)。
 */
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class CandidateMapperTest extends BaseIntegrationTest {

    @Autowired
    private CandidateMapper candidateMapper;

    @Test
    void testInsertAndSelectById() {
        Candidate candidate = new Candidate();
        candidate.setName("山田花子");
        candidate.setContactEmail("hanako@example.com");
        candidate.setSkillSummary("Java/Spring Boot 3年");
        candidate.setCurrentStage("応募受付");

        int rows = candidateMapper.insert(candidate);
        assertEquals(1, rows);
        assertNotNull(candidate.getId());

        Candidate found = candidateMapper.selectById(candidate.getId());
        assertNotNull(found);
        assertEquals("山田花子", found.getName());
        assertEquals("応募受付", found.getCurrentStage());
        assertEquals(0, found.getDeletedFlag());
    }

    @Test
    void testUpdateById() {
        Candidate candidate = new Candidate();
        candidate.setName("鈴木一郎");
        candidate.setCurrentStage("応募受付");
        candidateMapper.insert(candidate);

        Candidate update = new Candidate();
        update.setId(candidate.getId());
        update.setCurrentStage("書類選考");
        candidateMapper.updateById(update);

        Candidate found = candidateMapper.selectById(candidate.getId());
        assertEquals("書類選考", found.getCurrentStage());
        // 更新対象外のフィールドは保持される(mybatis-plusのnot_null戦略)
        assertEquals("鈴木一郎", found.getName());
    }

    @Test
    void testLogicalDelete() {
        Candidate candidate = new Candidate();
        candidate.setName("削除対象");
        candidate.setCurrentStage("応募受付");
        candidateMapper.insert(candidate);

        int rows = candidateMapper.deleteById(candidate.getId());
        assertEquals(1, rows);

        // 論理削除のため、通常のselectByIdでは取得できなくなる
        Candidate found = candidateMapper.selectById(candidate.getId());
        assertNull(found);
    }
}
