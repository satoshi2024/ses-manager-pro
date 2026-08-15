package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 案件ポジション（募集枠）。
 *
 * <p>状態機械: 募集中 → 候補選定 / 取消、候補選定 → 充足 / 保留 / 取消、
 * 充足 → 募集中（欠員発生）、保留 / 取消 → 募集中（design §5.4）。
 * {@code end_date IS NULL} はopen end（計画window末まで）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_project_position")
public class ProjectPosition extends BaseEntity {

    public static final String STATUS_RECRUITING = "募集中";
    public static final String STATUS_CANDIDATE = "候補選定";
    public static final String STATUS_FILLED = "充足";
    public static final String STATUS_HOLD = "保留";
    public static final String STATUS_CANCELLED = "取消";

    /** 案件ID */
    @NotNull(message = "案件は必須です")
    private Long projectId;

    /** ポジション番号（案件内一意） */
    @NotBlank(message = "ポジション番号は必須です")
    private String positionNo;

    /** 役割名 */
    @NotBlank(message = "役割名は必須です")
    private String roleName;

    /** 募集人数 */
    @NotNull(message = "募集人数は必須です")
    @Positive(message = "募集人数は1以上で入力してください")
    private Integer requiredCount;

    /** 必須/歓迎skillのJSON配列（例: ["Java","Spring"]） */
    private String skillsJson;

    /** 単価帯下限（円/月） */
    private BigDecimal unitPriceMin;

    /** 単価帯上限（円/月） */
    private BigDecimal unitPriceMax;

    /** 開始日（inclusive） */
    private LocalDate startDate;

    /** 終了日（inclusive・NULL=open end: 計画window末まで） */
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private LocalDate endDate;

    /** 勤務地 */
    private String location;

    /** 想定稼働率(%) */
    @NotNull(message = "稼働率は必須です")
    @DecimalMin(value = "0.01", message = "稼働率は0より大きく入力してください")
    @DecimalMax(value = "100", message = "稼働率は100以下で入力してください")
    private BigDecimal allocationPercent;

    /** 優先度 */
    private String priority;

    /** 状態 */
    @NotBlank(message = "状態は必須です")
    private String status;

    /** 楽観ロック */
    @Version
    private Integer version;

    @AssertTrue(message = "単価上限は下限以上の値を指定してください")
    public boolean isUnitPriceRangeValid() {
        return unitPriceMin == null || unitPriceMax == null
                || unitPriceMin.compareTo(unitPriceMax) <= 0;
    }

    @AssertTrue(message = "終了日は開始日以降を指定してください")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }
}
