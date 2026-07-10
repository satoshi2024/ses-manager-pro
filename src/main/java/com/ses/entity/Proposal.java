package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 提案エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_proposal")
public class Proposal extends BaseEntity {

    /** エンジニアID */
    private Long engineerId;

    /** 案件ID */
    private Long projectId;

    /** 提案単価 */
    private BigDecimal proposedUnitPrice;

    /**
     * ステータス
     * '書類選考中', '一次面接', '二次面接', '結果待ち', '成約', '見送り'
     */
    private String status;

    /** スキルシートパス */
    private String skillSheetPath;

    /** 提案メールテキスト */
    private String proposalEmailText;

    /** AIマッチングスコア */
    private BigDecimal aiMatchScore;

    /** マッチング理由 */
    private String matchReason;

    /** 備考 */
    private String remarks;

    /** 提案者ID */
    private Long proposedBy;

    /** 提案日時 */
    private LocalDateTime proposedAt;

    /** クローズ日時 */
    private LocalDateTime closedAt;
}
