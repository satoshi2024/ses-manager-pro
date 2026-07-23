package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 案件エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_project")
public class Project extends BaseEntity {

    /**
     * 案件名
     */
    @NotBlank(message = "案件名は必須です")
    private String projectName;

    /**
     * 顧客ID
     */
    @NotNull(message = "顧客は必須です")
    private Long customerId;

    /**
     * 商流位置
     */
    private String commercialFlow;

    /**
     * 案件概要
     */
    private String description;

    /**
     * 募集人数
     */
    private Integer requiredCount;

    /**
     * 単価(下限)
     */
    private BigDecimal unitPriceMin;

    /**
     * 単価(上限)
     */
    private BigDecimal unitPriceMax;

    /**
     * 作業場所
     */
    private String workLocation;

    /**
     * リモート状況
     */
    private String remoteType;

    /**
     * 開始日
     */
    private LocalDate startDate;

    /**
     * 終了予定日
     */
    private LocalDate endDate;

    /**
     * ステータス
     */
    @jakarta.validation.constraints.Pattern(regexp = "^(準備中|提案中|稼動中|完了|保留|)$", message = "ステータスが不正です")
    private String status;

    /**
     * 優先度
     */
    private String priority;

    /**
     * 備考
     */
    private String remarks;

    /**
     * 登録者ID
     */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    /**
     * 単価の下限が上限を超えていないことを検証する。
     * いずれかが未入力の場合は検証をスキップする。
     */
    @JsonIgnore
    @AssertTrue(message = "単価上限は下限以上の値を指定してください")
    public boolean isUnitPriceRangeValid() {
        return unitPriceMin == null || unitPriceMax == null
                || unitPriceMin.compareTo(unitPriceMax) <= 0;
    }

    /**
     * 終了予定日が開始日以降であることを検証する。
     * いずれかが未入力の場合は検証をスキップする。
     */
    @JsonIgnore
    @AssertTrue(message = "終了予定日は開始日以降を指定してください")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }
}
