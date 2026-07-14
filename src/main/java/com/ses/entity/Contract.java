package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 契約エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_contract")
public class Contract extends BaseEntity {

    /** 契約番号 */
    private String contractNo;

    /** 提案ID */
    private Long proposalId;

    /** エンジニアID */
    @NotNull(message = "要員は必須です")
    private Long engineerId;

    /** 案件ID */
    @NotNull(message = "案件は必須です")
    private Long projectId;

    /** 顧客ID */
    @NotNull(message = "顧客は必須です")
    private Long customerId;

    /**
     * 契約形態
     * '準委任','請負','派遣'
     */
    private String contractType;

    /** 契約開始日 */
    @NotNull(message = "契約開始日は必須です")
    private LocalDate startDate;

    /** 契約終了日 */
    private LocalDate endDate;

    /** 売上単価 */
    @NotNull(message = "売上単価は必須です")
    private BigDecimal sellingPrice;

    /** 原価 */
    @NotNull(message = "原価は必須です")
    private BigDecimal costPrice;

    /** 精算基準時間（下限） */
    private BigDecimal settlementHoursMin;

    /** 精算基準時間（上限） */
    private BigDecimal settlementHoursMax;

    /** 端数処理ルール */
    private String fractionRule;

    /** 自動更新 (1:する, 0:しない) */
    private Integer autoRenew;

    /**
     * ステータス
     * '準備中','稼動中','終了','解約'
     */
    private String status;

    /** 備考 */
    private String remarks;

    /**
     * 成約担当営業ID（成約時の主担当を既定値とし変更可）
     * updateStrategy=ALWAYS: グローバルの not_null 戦略を上書きし、
     * NULL(未設定に戻す操作)も確実に更新反映させる。契約更新は全項目を送る単一経路のため安全。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long salesUserId;

    /**
     * インセンティブ基準の個別上書き（粗利/売上、NULL=既定規則適用）
     * updateStrategy=ALWAYS: 「既定に戻す」= NULL 更新を反映させるため。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String commissionBaseType;

    /**
     * インセンティブ率の個別上書き（%、NULL=既定規則適用）
     * updateStrategy=ALWAYS: 「既定に戻す」= NULL 更新を反映させるため。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal commissionRate;

    /** 自動更新ドラフトの生成元契約ID（このIDから自動生成された更新ドラフトの場合のみ設定） */
    private Long renewedFromContractId;

    /** 作成者ID */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
