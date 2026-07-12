package com.ses.service.ai;

import com.ses.dto.ai.MatchScore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Set;

/**
 * ルールベース採点エンジン
 */
public final class MatchScoreCalculator {

    private MatchScoreCalculator() {}

    /**
     * 要員と案件のマッチスコアを計算する
     *
     * @param mustSkillIds     案件の必須スキルID
     * @param niceSkillIds     案件の尚可スキルID
     * @param engineerSkillIds 要員の保有スキルID
     * @param priceMin         案件単価下限(万円)
     * @param priceMax         案件単価上限(万円)
     * @param expectedPrice    要員希望単価(万円)
     * @param projectStart     案件開始予定日
     * @param availableDate    要員稼動可能日
     * @return 採点結果
     */
    public static MatchScore calculate(Set<Long> mustSkillIds, Set<Long> niceSkillIds,
                                       Set<Long> engineerSkillIds, BigDecimal priceMin, BigDecimal priceMax,
                                       BigDecimal expectedPrice, LocalDate projectStart, LocalDate availableDate) {

        MatchScore result = new MatchScore();
        if (mustSkillIds == null) mustSkillIds = Collections.emptySet();
        if (niceSkillIds == null) niceSkillIds = Collections.emptySet();
        if (engineerSkillIds == null) engineerSkillIds = Collections.emptySet();

        // 1. 必須スキル (50点)
        int mustScore;
        if (mustSkillIds.isEmpty()) {
            mustScore = 50;
            result.setMustCoverage(1.0);
        } else {
            for (Long id : mustSkillIds) {
                if (engineerSkillIds.contains(id)) {
                    result.getMatchedMustIds().add(id);
                } else {
                    result.getMissingMustIds().add(id);
                }
            }
            result.setMustCoverage((double) result.getMatchedMustIds().size() / mustSkillIds.size());
            mustScore = (int) Math.round(result.getMustCoverage() * 50);
        }

        if (result.getMustCoverage() < 0.5) {
            result.setExcluded(true);
        }

        // 2. 尚可スキル (20点)
        int niceScore;
        if (niceSkillIds.isEmpty()) {
            niceScore = 20;
        } else {
            for (Long id : niceSkillIds) {
                if (engineerSkillIds.contains(id)) {
                    result.getMatchedNiceIds().add(id);
                } else {
                    result.getMissingNiceIds().add(id);
                }
            }
            double niceCoverage = (double) result.getMatchedNiceIds().size() / niceSkillIds.size();
            niceScore = (int) Math.round(niceCoverage * 20);
        }

        // 3. 単価 (20点)
        int priceScore = 0;
        if (expectedPrice == null || (priceMin == null && priceMax == null)) {
            priceScore = 10;
        } else {
            BigDecimal min = priceMin != null ? priceMin : BigDecimal.ZERO;
            BigDecimal max = priceMax != null ? priceMax : new BigDecimal("99999");

            if (expectedPrice.compareTo(min) >= 0 && expectedPrice.compareTo(max) <= 0) {
                priceScore = 20;
            } else if (expectedPrice.compareTo(min) < 0) {
                BigDecimal diff = min.subtract(expectedPrice);
                int penalty = diff.intValue() * 2;
                priceScore = Math.max(0, 20 - penalty);
            } else {
                BigDecimal diff = expectedPrice.subtract(max);
                int penalty = diff.intValue() * 2;
                priceScore = Math.max(0, 20 - penalty);
            }
        }
        result.setPriceScore(priceScore);

        // 4. 稼働可能日 (10点)
        int dateScore = 0;
        if (projectStart == null || availableDate == null) {
            dateScore = 5;
        } else {
            if (!availableDate.isAfter(projectStart)) {
                dateScore = 10;
            } else {
                long daysLate = ChronoUnit.DAYS.between(projectStart, availableDate);
                if (daysLate <= 30) {
                    dateScore = 5;
                } else {
                    dateScore = 0;
                }
            }
        }
        result.setDateScore(dateScore);

        result.setTotalScore(mustScore + niceScore + priceScore + dateScore);

        return result;
    }
}
