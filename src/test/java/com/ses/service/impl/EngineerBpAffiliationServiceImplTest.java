package com.ses.service.impl;

import com.ses.entity.EngineerBpAffiliation;
import com.ses.service.EngineerBpAffiliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EngineerBpAffiliationServiceImplTest {

    @Autowired
    private EngineerBpAffiliationService affiliationService;

    @Test
    @DisplayName("BP乗換時の同日重複防止と期間代数ルールの検証")
    void testBpAffiliationPeriodAlgebra() {
        Long engineerId = 999L;
        Long companyA = 100L;
        Long companyB = 200L;

        // 1. 最初は A社 に 2026-01-01 から無期限で所属
        EngineerBpAffiliation aff1 = affiliationService.assignBpAffiliation(
                engineerId, companyA, LocalDate.of(2026, 1, 1), null);

        assertNotNull(aff1.getId());
        assertNull(aff1.getValidTo());

        // 2. 2026-07-01 に B社 へ同日乗換
        EngineerBpAffiliation aff2 = affiliationService.assignBpAffiliation(
                engineerId, companyB, LocalDate.of(2026, 7, 1), null);

        // A社の所属終了日が 2026-06-30 (乗換前日) に切り詰められていること
        EngineerBpAffiliation updatedAff1 = affiliationService.getAffiliationHistory(engineerId).stream()
                .filter(a -> a.getId().equals(aff1.getId()))
                .findFirst().orElseThrow();

        assertEquals(LocalDate.of(2026, 6, 30), updatedAff1.getValidTo());
        assertEquals(LocalDate.of(2026, 7, 1), aff2.getValidFrom());

        // 2026-06-30 時点では A社所属
        EngineerBpAffiliation activeJune = affiliationService.getActiveAffiliationAsOf(engineerId, LocalDate.of(2026, 6, 30));
        assertEquals(companyA, activeJune.getBpCompanyId());

        // 2026-07-01 時点では B社所属 (同日重複なし)
        EngineerBpAffiliation activeJuly = affiliationService.getActiveAffiliationAsOf(engineerId, LocalDate.of(2026, 7, 1));
        assertEquals(companyB, activeJuly.getBpCompanyId());

        // 履歴総数が 2 件であること
        List<EngineerBpAffiliation> history = affiliationService.getAffiliationHistory(engineerId);
        assertEquals(2, history.size());
    }
}
