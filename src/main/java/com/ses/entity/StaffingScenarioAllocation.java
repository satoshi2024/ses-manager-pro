package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * シナリオ内の仮配置（日単位）。
 *
 * <p>{@code dates} は対象日のISO日付JSON配列（昇順・重複なし）。
 * scenario操作は本テーブルのみを更新し、実データ（t_allocation_plan/契約/提案）を一切変更しない。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_staffing_scenario_allocation")
public class StaffingScenarioAllocation extends BaseEntity {

    /** scenarioID */
    @NotNull(message = "シナリオは必須です")
    private Long scenarioId;

    /** 要員ID */
    @NotNull(message = "要員は必須です")
    private Long engineerId;

    /** ポジションID（NULL=社内/待機）。案件→社内/待機へ変更できるようALWAYS（S12-R1-P2-03）。 */
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private Long positionId;

    /** 対象日のJSON配列（ISO日付・昇順・重複なし） */
    @NotNull(message = "対象日は必須です")
    private String dates;

    /** 配賦率(%) */
    @NotNull(message = "配賦率は必須です")
    @DecimalMin(value = "0.01", message = "配賦率は0より大きく入力してください")
    @DecimalMax(value = "100", message = "配賦率は100以下で入力してください")
    private BigDecimal percent;
}
