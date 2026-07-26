package com.ses.service.ai;

import com.ses.dto.ai.MatchScore;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MatchScoreCalculatorTest {

    @Test
    void testPerfectMatch() {
        Set<Long> mustSkills = Set.of(1L, 2L);
        Set<Long> niceSkills = Set.of(3L);
        Set<Long> engSkills = Set.of(1L, 2L, 3L, 4L);
        BigDecimal min = new BigDecimal("50");
        BigDecimal max = new BigDecimal("70");
        BigDecimal expected = new BigDecimal("60");
        LocalDate projStart = LocalDate.of(2023, 10, 1);
        LocalDate availDate = LocalDate.of(2023, 9, 30);

        MatchScore score = MatchScoreCalculator.calculate(
                mustSkills, niceSkills, engSkills, min, max, expected, projStart, availDate);

        assertEquals(100, score.getTotalScore());
        assertFalse(score.isExcluded());
        assertEquals(1.0, score.getMustCoverage());
    }

    @Test
    void testExcludedMustSkillsBelow50Percent() {
        Set<Long> mustSkills = Set.of(1L, 2L, 3L);
        Set<Long> niceSkills = Set.of(4L);
        Set<Long> engSkills = Set.of(1L); // 1/3 coverage -> 33% < 50%
        
        MatchScore score = MatchScoreCalculator.calculate(
                mustSkills, niceSkills, engSkills, null, null, null, null, null);

        assertTrue(score.isExcluded());
        assertEquals(17, Math.round(score.getMustCoverage() * 50));
    }

    @Test
    void testMustSkillsExactly50Percent() {
        Set<Long> mustSkills = Set.of(1L, 2L);
        Set<Long> niceSkills = Set.of();
        Set<Long> engSkills = Set.of(1L); // 1/2 coverage -> 50%
        
        MatchScore score = MatchScoreCalculator.calculate(
                mustSkills, niceSkills, engSkills, null, null, null, null, null);

        assertFalse(score.isExcluded());
        assertEquals(25, Math.round(score.getMustCoverage() * 50));
    }

    /**
     * 単価は「円」で渡す（t_project.unit_price_min/max・t_engineer.expected_unit_price の実際の格納単位）。
     * 減点は1万円の乖離につき2点。
     */
    @Test
    void testPricePenalty() {
        BigDecimal min = new BigDecimal("500000");
        BigDecimal max = new BigDecimal("700000");

        MatchScore scoreOver = MatchScoreCalculator.calculate(
                Set.of(), Set.of(), Set.of(), min, max, new BigDecimal("750000"), null, null);
        assertEquals(10, scoreOver.getPriceScore()); // 5万円超過 -> penalty 10 -> 20-10=10

        MatchScore scoreUnder = MatchScoreCalculator.calculate(
                Set.of(), Set.of(), Set.of(), min, max, new BigDecimal("480000"), null, null);
        assertEquals(16, scoreUnder.getPriceScore()); // 2万円下回り -> penalty 4 -> 20-4=16

        MatchScore scoreInRange = MatchScoreCalculator.calculate(
                Set.of(), Set.of(), Set.of(), min, max, new BigDecimal("600000"), null, null);
        assertEquals(20, scoreInRange.getPriceScore());

        // 乖離が大きくても0点止まり（負値にならない）
        MatchScore scoreFarOff = MatchScoreCalculator.calculate(
                Set.of(), Set.of(), Set.of(), min, max, new BigDecimal("3000000"), null, null);
        assertEquals(0, scoreFarOff.getPriceScore());
    }

    /**
     * 上限・下限が未設定の側は「制約なし」として減点しない。
     * 以前は上限未設定時に 99999 という万円前提の番兵と円の希望単価を比べており、
     * 上限を設けていない案件の候補が軒並み単価0点になっていた。
     */
    @Test
    void testPriceNoBoundIsNotPenalized() {
        MatchScore noMax = MatchScoreCalculator.calculate(
                Set.of(), Set.of(), Set.of(), new BigDecimal("500000"), null,
                new BigDecimal("900000"), null, null);
        assertEquals(20, noMax.getPriceScore(), "上限未設定なら高単価でも減点しない");

        MatchScore noMin = MatchScoreCalculator.calculate(
                Set.of(), Set.of(), Set.of(), null, new BigDecimal("700000"),
                new BigDecimal("100000"), null, null);
        assertEquals(20, noMin.getPriceScore(), "下限未設定なら低単価でも減点しない");

        // 両方未設定 or 希望単価なし は従来どおり中立の10点
        MatchScore unknown = MatchScoreCalculator.calculate(
                Set.of(), Set.of(), Set.of(), null, null, new BigDecimal("600000"), null, null);
        assertEquals(10, unknown.getPriceScore());
    }

    @Test
    void testDateScoreLate() {
        LocalDate projStart = LocalDate.of(2023, 10, 1);
        LocalDate availDateLate = LocalDate.of(2023, 10, 15); // 14 days late -> score 5
        LocalDate availDateTooLate = LocalDate.of(2023, 11, 2); // >30 days late -> score 0
        
        MatchScore scoreLate = MatchScoreCalculator.calculate(
                Set.of(), Set.of(), Set.of(), null, null, null, projStart, availDateLate);
        assertEquals(5, scoreLate.getDateScore());
        
        MatchScore scoreTooLate = MatchScoreCalculator.calculate(
                Set.of(), Set.of(), Set.of(), null, null, null, projStart, availDateTooLate);
        assertEquals(0, scoreTooLate.getDateScore());
    }

    @Test
    void testNullValues() {
        MatchScore score = MatchScoreCalculator.calculate(
                null, null, null, null, null, null, null, null);
        
        assertFalse(score.isExcluded());
        assertEquals(50, Math.round(score.getMustCoverage() * 50));
        assertEquals(85, score.getTotalScore());
    }
}
