package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 提案エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_proposal")
public class Proposal extends BaseEntity {

    /**
     * t_proposal には created_at 列が存在しない（作成日時は proposed_at 列で管理）ため、
     * BaseEntity 由来の共通「作成日時」カラムはマッピング対象外にする。
     * これを無効化しないと INSERT 時に「Unknown column 'created_at'」で保存に失敗する。
     */
    @TableField(exist = false)
    private LocalDateTime createdAt;

    /** エンジニアID */
    @NotNull(message = "要員は必須です")
    private Long engineerId;

    /** 案件ID */
    @NotNull(message = "案件は必須です")
    private Long projectId;

    /** 提案単価 */
    @PositiveOrZero(message = "提案単価は0以上で入力してください")
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
