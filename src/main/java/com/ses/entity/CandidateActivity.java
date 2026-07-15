package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 候補者ステージ変更履歴エンティティ。
 * このテーブルへの挿入は必ず{@link com.ses.service.CandidateService#changeStage}経由で行うこと
 * (t_candidate.currentStageの非正規化キャッシュを同期更新する必要があるため)。
 */
@Data
@TableName("t_candidate_activity")
public class CandidateActivity {

    /** ID */
    private Long id;

    /** 候補者ID */
    private Long candidateId;

    /** 変更後ステージ */
    private String stage;

    /** 理由(不採用/内定辞退時は必須) */
    private String reason;

    /** 変更者ID */
    private Long changedBy;

    /** 変更日時 */
    private LocalDateTime changedAt;

    /** 備考 */
    private String remarks;
}
