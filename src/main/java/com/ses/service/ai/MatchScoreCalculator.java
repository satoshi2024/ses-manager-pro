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
     * @param priceMin         案件単価下限(円, null=下限なし)
     * @param priceMax         案件単価上限(円, null=上限なし)
     * @param expectedPrice    要員希望単価(円)
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
        // 単価は全て「円」で受け取る（t_project.unit_price_min/max・t_engineer.expected_unit_price の
        // 実際の格納単位。画面も円で入出力する）。減点は「1万円の乖離につき2点」で、乖離10万円で0点。
        int priceScore;
        if (expectedPrice == null || (priceMin == null && priceMax == null)) {
            priceScore = 10;
        } else if (priceMin != null && expectedPrice.compareTo(priceMin) < 0) {
            priceScore = scoreFromGap(priceMin.subtract(expectedPrice));
        } else if (priceMax != null && expectedPrice.compareTo(priceMax) > 0) {
            priceScore = scoreFromGap(expectedPrice.subtract(priceMax));
        } else {
            // 範囲内。上限/下限の片側しか設定されていない場合、未設定側は制約なしとして扱う。
            priceScore = 20;
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

    /** 希望単価が案件レンジから外れた額(円)を20点満点の単価スコアへ変換する（1万円につき2点減点）。 */
    private static int scoreFromGap(BigDecimal gapYen) {
        int penalty = gapYen.divide(TEN_THOUSAND, 0, java.math.RoundingMode.DOWN)
                .multiply(BigDecimal.valueOf(2))
                .min(BigDecimal.valueOf(20))
                .intValue();
        return Math.max(0, 20 - penalty);
    }

    /** 単価スコアの減点単位（1万円）。 */
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
}
