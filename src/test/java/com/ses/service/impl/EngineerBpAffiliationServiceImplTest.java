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

    @Test
    @DisplayName("未来の乗換予約は現在の所属を切らず未来行を追加する")
    void testFutureReservationKeepsCurrentAffiliation() {
        Long engineerId = 998L;
        Long companyA = 100L;
        Long companyB = 200L;

        affiliationService.assignBpAffiliation(engineerId, companyA, LocalDate.now().minusMonths(6), null);
        LocalDate futureStart = LocalDate.now().plusMonths(1);
        EngineerBpAffiliation future = affiliationService.assignBpAffiliation(
                engineerId, companyB, futureStart, null);

        assertEquals(futureStart, future.getValidFrom());
        List<EngineerBpAffiliation> history = affiliationService.getAffiliationHistory(engineerId);
        EngineerBpAffiliation current = history.stream()
                .filter(a -> a.getBpCompanyId().equals(companyA))
                .findFirst().orElseThrow();
        assertNull(current.getValidTo(), "未来予約では現在の所属を閉じない");
        assertEquals(companyA, affiliationService.getActiveAffiliationAsOf(engineerId, LocalDate.now()).getBpCompanyId());
        assertEquals(companyB, affiliationService.getActiveAffiliationAsOf(engineerId, futureStart).getBpCompanyId());
    }

    @Test
    @DisplayName("遡及登録は既存区間を分割し、重複区間を作らない")
    void testRetroactiveRegistrationSplitsExistingInterval() {
        Long engineerId = 997L;
        Long companyA = 100L;
        Long companyB = 200L;

        EngineerBpAffiliation existing = affiliationService.assignBpAffiliation(
                engineerId, companyA, LocalDate.of(2026, 1, 1), null);
        LocalDate retroStart = LocalDate.of(2025, 6, 1);
        affiliationService.assignBpAffiliation(engineerId, companyB, retroStart, null);

        List<EngineerBpAffiliation> history = affiliationService.getAffiliationHistory(engineerId);
        EngineerBpAffiliation retro = history.stream()
                .filter(a -> a.getBpCompanyId().equals(companyB))
                .findFirst().orElseThrow();
        assertEquals(LocalDate.of(2025, 12, 31), retro.getValidTo(),
                "遡及区間は後続の既存所属開始前日までで打ち切る");
        EngineerBpAffiliation kept = history.stream()
                .filter(a -> a.getId().equals(existing.getId()))
                .findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 1, 1), kept.getValidFrom());
        assertNull(kept.getValidTo());

        // 重複区間が無いこと（境界日はちょうど1件ずつ）
        assertEquals(companyB, affiliationService.getActiveAffiliationAsOf(engineerId, LocalDate.of(2025, 12, 31)).getBpCompanyId());
        assertEquals(companyA, affiliationService.getActiveAffiliationAsOf(engineerId, LocalDate.of(2026, 1, 1)).getBpCompanyId());
    }

    @Test
    @DisplayName("部分重複は未被覆区間だけを移し、隣接は別区間として保持する")
    void testPartialOverlapAndAdjacency() {
        Long engineerId = 996L;
        Long companyA = 100L;
        Long companyB = 200L;

        EngineerBpAffiliation a = affiliationService.assignBpAffiliation(
                engineerId, companyA, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        affiliationService.assignBpAffiliation(engineerId, companyB, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

        List<EngineerBpAffiliation> history = affiliationService.getAffiliationHistory(engineerId);
        assertEquals(3, history.size(), "旧区間の閉鎖・B区間・Aの尻尾の3件");
        EngineerBpAffiliation aHead = history.stream()
                .filter(h -> h.getId().equals(a.getId()))
                .findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 1, 31), aHead.getValidTo());
        EngineerBpAffiliation aTail = history.stream()
                .filter(h -> h.getBpCompanyId().equals(companyA) && !h.getId().equals(a.getId()))
                .findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 3, 1), aTail.getValidFrom());
        assertEquals(LocalDate.of(2026, 3, 31), aTail.getValidTo());

        // 隣接: Aの尻尾の終了翌日からCを追加しても既存区間を変更しない
        EngineerBpAffiliation c = affiliationService.assignBpAffiliation(
                engineerId, companyB, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));
        assertEquals(LocalDate.of(2026, 4, 1), c.getValidFrom());
        assertEquals(4, affiliationService.getAffiliationHistory(engineerId).size());
    }

    @Test
    @DisplayName("所属なし期間は直前の所属へfallbackせず、空区間は拒否する")
    void testGapIsAllowedAndEmptyIntervalRejected() {
        Long engineerId = 995L;
        Long companyA = 100L;
        Long companyB = 200L;

        affiliationService.assignBpAffiliation(engineerId, companyA, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        affiliationService.assignBpAffiliation(engineerId, companyB, LocalDate.of(2026, 3, 1), null);

        assertNull(affiliationService.getActiveAffiliationAsOf(engineerId, LocalDate.of(2026, 2, 15)),
                "所属なし期間はNULLのまま");
        assertNotNull(affiliationService.getActiveAffiliationAsOf(engineerId, LocalDate.of(2026, 3, 15)));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.ses.common.exception.BusinessException.class,
                () -> affiliationService.assignBpAffiliation(engineerId, companyB, LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 9)));
    }

    @Test
    @DisplayName("未来予約行が存在する状態での遡及登録（複合ケースでvalid_from重複が発生しないこと）")
    void testFutureReservationAndRetroactiveCombined() {
        Long engineerId = 995L;
        Long companyA = 100L;
        Long companyB = 200L;
        Long companyC = 300L;

        // Step 1: 2026-01-01 から A社 (無期限)
        affiliationService.assignBpAffiliation(engineerId, companyA, LocalDate.of(2026, 1, 1), null);

        // Step 2: 2026-09-01 から B社 (未来予約)
        affiliationService.assignBpAffiliation(engineerId, companyB, LocalDate.of(2026, 9, 1), null);

        // Step 3: 2026-05-01 から C社 (遡及登録)
        affiliationService.assignBpAffiliation(engineerId, companyC, LocalDate.of(2026, 5, 1), null);

        List<EngineerBpAffiliation> history = affiliationService.getAffiliationHistory(engineerId);

        // validFrom の重複がないこと
        long uniqueValidFromCount = history.stream().map(EngineerBpAffiliation::getValidFrom).distinct().count();
        assertEquals(history.size(), uniqueValidFromCount, "validFrom に重複が存在しないこと");

        // A社は 2026-01-01 ～ 2026-04-30
        EngineerBpAffiliation affA = history.stream().filter(a -> a.getBpCompanyId().equals(companyA)).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 1, 1), affA.getValidFrom());
        assertEquals(LocalDate.of(2026, 4, 30), affA.getValidTo());

        // C社は 2026-05-01 ～ 2026-08-31
        EngineerBpAffiliation affC = history.stream().filter(a -> a.getBpCompanyId().equals(companyC)).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 5, 1), affC.getValidFrom());
        assertEquals(LocalDate.of(2026, 8, 31), affC.getValidTo());

        // B社は 2026-09-01 ～ 無期限
        EngineerBpAffiliation affB = history.stream().filter(a -> a.getBpCompanyId().equals(companyB)).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 1), affB.getValidFrom());
        assertNull(affB.getValidTo());
    }
}
