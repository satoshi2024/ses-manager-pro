package com.ses.service.impl;

import com.ses.dto.engineerfollowup.RetentionRiskDto;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerFollowup;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerFollowupMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.RetentionRiskService;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定着リスクスコア（長期Bench・低満足度・フォロー間隔超過の合成）の検証
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
class RetentionRiskServiceImplTest {

    @Autowired
    private RetentionRiskService retentionRiskService;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private ContractMapper contractMapper;

    @Autowired
    private EngineerFollowupMapper engineerFollowupMapper;

    @Autowired
    private SystemConfigService systemConfigService;

    private Long insertEngineer(String status, LocalDate createdAt) {
        Engineer e = Engineer.builder().fullName("要員").status(status).build();
        engineerMapper.insert(e);
        if (createdAt != null) {
            e.setCreatedAt(createdAt.atStartOfDay());
            engineerMapper.updateById(e);
        }
        return e.getId();
    }

    private void insertContract(Long engineerId, LocalDate endDate) {
        Contract c = new Contract();
        c.setEngineerId(engineerId);
        c.setEndDate(endDate);
        c.setStatus("終了");
        contractMapper.insert(c);
    }

    private void insertFollowup(Long engineerId, LocalDate followupDate, Integer satisfaction) {
        EngineerFollowup f = EngineerFollowup.builder()
                .engineerId(engineerId)
                .followupType("1on1")
                .followupDate(followupDate)
                .satisfaction(satisfaction)
                .build();
        engineerFollowupMapper.insert(f);
    }

    @Test
    void 稼働中でフォローも十分ならリスクは低い() {
        Long engineerId = insertEngineer("稼動中", LocalDate.now());
        insertFollowup(engineerId, LocalDate.now(), 5);

        RetentionRiskDto dto = retentionRiskService.score(engineerId);

        assertFalse(dto.isHighRisk());
        assertEquals(0, dto.getScore());
    }

    @Test
    void 長期Benchと低満足度が重なると高リスクになる() {
        int benchWarnDays = systemConfigService.getInt("retention.risk.bench-warn-days", 30);
        Long engineerId = insertEngineer("Bench", LocalDate.now().minusDays(200));
        // 直近契約終了日を基準日数の3倍前とし、長期Bench判定(閾値の2倍以上)を確実にする
        insertContract(engineerId, LocalDate.now().minusDays((long) benchWarnDays * 3));
        insertFollowup(engineerId, LocalDate.now().minusDays(5), 1);

        RetentionRiskDto dto = retentionRiskService.score(engineerId);

        assertTrue(dto.getScore() >= 60, "score=" + dto.getScore());
        assertTrue(dto.isHighRisk());
        assertEquals(1, dto.getLastSatisfaction());
        assertTrue(dto.getBenchDays() >= benchWarnDays * 2L);
    }

    @Test
    void フォロー間隔超過のみでは閾値未満の加点にとどまる() {
        int intervalDays = systemConfigService.getInt("retention.risk.followup-interval-days", 30);
        Long engineerId = insertEngineer("稼動中", LocalDate.now().minusDays((long) intervalDays * 3));

        RetentionRiskDto dto = retentionRiskService.score(engineerId);

        // フォロー記録が無いため要員登録日を最終フォロー日として扱う→間隔超過分(30点)のみ加点
        assertEquals(30, dto.getScore());
        assertFalse(dto.isHighRisk());
    }

    @Test
    void 存在しない要員IDは例外になる() {
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                com.ses.common.exception.BusinessException.class,
                () -> retentionRiskService.score(999999L)).getMessage() != null);
    }
}
