package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.PositiveOrZero;
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

    /**
     * 契約終了日
     * updateStrategy=ALWAYS: グローバルの not_null 戦略を上書きし、編集で「無期限に戻す」(NULL更新)を
     * 反映させる。契約更新は全項目を送る単一経路(updateWithBusinessRules)のため安全。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate endDate;

    /** 売上単価 */
    @NotNull(message = "売上単価は必須です")
    @PositiveOrZero(message = "売上単価は0以上で入力してください")
    private BigDecimal sellingPrice;

    /** 原価 */
    @NotNull(message = "原価は必須です")
    @PositiveOrZero(message = "原価は0以上で入力してください")
    private BigDecimal costPrice;

    /**
     * 精算基準時間（下限）
     * updateStrategy=ALWAYS: 編集で精算幅の下限をクリア(NULL更新=固定額精算に戻す)できるようにする。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal settlementHoursMin;

    /**
     * 精算基準時間（上限）
     * updateStrategy=ALWAYS: 編集で精算幅の上限をクリア(NULL更新)できるようにする。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal settlementHoursMax;

    /**
     * 端数処理ルール（自由記述メモ。精算計算には適用されない）
     * updateStrategy=ALWAYS: 編集でメモをクリア(NULL更新)できるようにする。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
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

    /** 指揮命令の実態フラグ（偽装請負判定用。準委任/請負契約なのに指揮命令の実態がある場合はtrue） */
    private Boolean directCommandFlag;

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
    @PositiveOrZero(message = "インセンティブ率は0以上で入力してください")
    private BigDecimal commissionRate;

    /** 自動更新ドラフトの生成元契約ID（このIDから自動生成された更新ドラフトの場合のみ設定） */
    private Long renewedFromContractId;

    /** 生成元見積ID（見積受注からのドラフトのみ設定） */
    private Long quotationId;

    /**
     * 更新判断（'CONTINUE':継続確定/'END':更新不要、NULL:未定）。
     * 契約更新カレンダーのエスカレーション停止条件として参照される。
     * updateStrategy=ALWAYS: 「未定に戻す」= NULL 更新を反映させるため。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String renewalDecision;

    /** 作成者ID */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @AssertTrue(message = "契約終了日は開始日以降を指定してください")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }

    @AssertTrue(message = "精算基準時間の上限は下限以上を指定してください")
    public boolean isSettlementHoursRangeValid() {
        return settlementHoursMin == null || settlementHoursMax == null
                || settlementHoursMin.compareTo(settlementHoursMax) <= 0;
    }
}
