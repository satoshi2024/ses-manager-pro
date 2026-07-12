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

    @Test
    void testPricePenalty() {
        // Price outside range, 10k off -> -2 points
        BigDecimal min = new BigDecimal("50");
        BigDecimal max = new BigDecimal("70");
        
        MatchScore scoreOver = MatchScoreCalculator.calculate(
                Set.of(), Set.of(), Set.of(), min, max, new BigDecimal("75"), null, null);
        assertEquals(10, scoreOver.getPriceScore()); // penalty = 5 * 2 = 10, score = 20 - 10 = 10
        
        MatchScore scoreUnder = MatchScoreCalculator.calculate(
                Set.of(), Set.of(), Set.of(), min, max, new BigDecimal("48"), null, null);
        assertEquals(16, scoreUnder.getPriceScore()); // penalty = 2 * 2 = 4, score = 20 - 4 = 16
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
