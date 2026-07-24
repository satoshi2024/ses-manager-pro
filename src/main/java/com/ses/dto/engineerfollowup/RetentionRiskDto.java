package com.ses.dto.engineerfollowup;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 要員の定着リスクスコア（RetentionRiskService.score の結果）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetentionRiskDto {

    /** 要員ID */
    private Long engineerId;

    /** 合成スコア（0-100、高いほど高リスク） */
    private int score;

    /** score が閾値（retention.risk.threshold）以上か */
    private boolean highRisk;

    /** Bench継続日数（Bench中でない場合は null） */
    private Long benchDays;

    /** 直近フォローの満足度（記録が無ければ null） */
    private Integer lastSatisfaction;

    /** 最終フォロー（無ければ要員登録日）からの経過日数 */
    private Long daysSinceLastFollowup;
}
