package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.candidate.CandidateEngineerInitialDto;
import com.ses.entity.Candidate;
import com.ses.entity.CandidateActivity;
import com.ses.mapper.CandidateActivityMapper;
import com.ses.mapper.CandidateMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CandidateServiceImplの結合テスト(タスクA テスト要件: ステージ同期・不採用理由必須・権限は
 * CandidateApiControllerTest側で確認)。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class CandidateServiceImplTest {

    @Autowired
    private CandidateServiceImpl candidateService;

    @Autowired
    private CandidateMapper candidateMapper;

    @Autowired
    private CandidateActivityMapper candidateActivityMapper;

    private MockedStatic<com.ses.common.util.SecurityUtils> mockedSecurityUtils;

    @BeforeEach
    void setUp() {
        mockedSecurityUtils = Mockito.mockStatic(com.ses.common.util.SecurityUtils.class);
        mockedSecurityUtils.when(com.ses.common.util.SecurityUtils::currentUserId).thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        if (mockedSecurityUtils != null && !mockedSecurityUtils.isClosed()) {
            mockedSecurityUtils.close();
        }
    }

    private Candidate seedCandidate(String stage) {
        Candidate candidate = new Candidate();
        candidate.setName("テスト候補者");
        candidate.setSkillSummary("Java経験3年");
        candidate.setCurrentStage(stage);
        candidateMapper.insert(candidate);
        return candidate;
    }

    @Test
    void save_ステージ未指定なら応募受付を設定する() {
        Candidate candidate = new Candidate();
        candidate.setName("新規候補者");

        candidateService.save(candidate);

        Candidate saved = candidateMapper.selectById(candidate.getId());
        assertEquals("応募受付", saved.getCurrentStage());
    }

    @Test
    void save_未知ステージなら保存しない() {
        Candidate candidate = new Candidate();
        candidate.setName("不正候補者");
        candidate.setCurrentStage("任意文字列");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> candidateService.save(candidate));

        assertEquals("error.candidate.stageInvalid", ex.getMessage());
        assertNull(candidate.getId());
    }

    @Test
    void changeStage_currentStageが同期更新され履歴が記録される() {
        Candidate candidate = seedCandidate("応募受付");

        candidateService.changeStage(candidate.getId(), "書類選考", null, "一次通過");

        Candidate updated = candidateMapper.selectById(candidate.getId());
        assertEquals("書類選考", updated.getCurrentStage());

        List<CandidateActivity> activities = candidateActivityMapper.selectList(
                new LambdaQueryWrapper<CandidateActivity>().eq(CandidateActivity::getCandidateId, candidate.getId()));
        assertEquals(1, activities.size());
        assertEquals("書類選考", activities.get(0).getStage());
        assertEquals("一次通過", activities.get(0).getRemarks());
        assertEquals(1L, activities.get(0).getChangedBy());
    }

    @Test
    void changeStage_不採用は理由必須でreasonなしなら例外() {
        Candidate candidate = seedCandidate("一次面談");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> candidateService.changeStage(candidate.getId(), "不採用", null, null));
        assertTrue(ex.getMessage().contains("error.candidate.reasonRequired"));

        // ステージは変更されていないこと
        Candidate unchanged = candidateMapper.selectById(candidate.getId());
        assertEquals("一次面談", unchanged.getCurrentStage());
    }

    @Test
    void changeStage_内定辞退は理由必須でreasonありなら成功() {
        Candidate candidate = seedCandidate("内定");

        candidateService.changeStage(candidate.getId(), "内定辞退", "他社に決めた", null);

        Candidate updated = candidateMapper.selectById(candidate.getId());
        assertEquals("内定辞退", updated.getCurrentStage());
    }

    @Test
    void changeStage_候補者が存在しない場合は例外() {
        assertThrows(BusinessException.class,
                () -> candidateService.changeStage(9999L, "書類選考", null, null));
    }

    @Test
    void changeStage_未知ステージなら状態も履歴も変更しない() {
        Candidate candidate = seedCandidate("応募受付");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> candidateService.changeStage(candidate.getId(), "任意文字列", null, null));

        assertEquals("error.candidate.stageInvalid", ex.getMessage());
        assertEquals("応募受付", candidateMapper.selectById(candidate.getId()).getCurrentStage());
        long activityCount = candidateActivityMapper.selectCount(
                new LambdaQueryWrapper<CandidateActivity>()
                        .eq(CandidateActivity::getCandidateId, candidate.getId()));
        assertEquals(0, activityCount);
    }

    @Test
    void getEngineerInitialDto_入社ステージ以外は例外() {
        Candidate candidate = seedCandidate("内定");

        assertThrows(BusinessException.class,
                () -> candidateService.getEngineerInitialDto(candidate.getId()));
    }

    @Test
    void getEngineerInitialDto_入社ステージなら初期値DTOを返す() {
        Candidate candidate = seedCandidate("入社");

        CandidateEngineerInitialDto dto = candidateService.getEngineerInitialDto(candidate.getId());
        assertEquals(candidate.getId(), dto.getCandidateId());
        assertEquals("テスト候補者", dto.getFullName());
        assertEquals("Java経験3年", dto.getResumeSummary());
    }

    @Test
    void linkConvertedEngineer_convertedEngineerIdが設定される() {
        Candidate candidate = seedCandidate("入社");

        candidateService.linkConvertedEngineer(candidate.getId(), 42L);

        Candidate updated = candidateMapper.selectById(candidate.getId());
        assertEquals(42L, updated.getConvertedEngineerId());
    }
}
