package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * 要員配置計画。
 *
 * <p>{@code position_id IS NULL} は「社内/待機」という業務値（未割当ではない。design §5.1）。
 * {@code source_contract_id IS NOT NULL} の行はactual（契約由来）。需給集計SQLのWHERE句でplanと排他する。
 * 状態機械: 下書き → 確定 / 破棄、確定 → 破棄 / 変更（新区間）（design §5.4）。
 * 過配賦例外は {@code exception_reason} + {@code approval_request_id} が必須（R2.2）。
 */
@Data
@EqualsAndHashCode(callSuper = true, exclude = "presentAlwaysFields")
@ToString(callSuper = true, exclude = "presentAlwaysFields")
@TableName("t_allocation_plan")
public class AllocationPlan extends BaseEntity {

    public static final String STATUS_DRAFT = "下書き";
    public static final String STATUS_CONFIRMED = "確定";
    public static final String STATUS_DISCARDED = "破棄";

    public static final String TYPE_PROJECT = "案件";
    public static final String TYPE_INTERNAL = "社内";
    public static final String TYPE_BENCH = "待機";

    /** 要員ID */
    @NotNull(message = "要員は必須です")
    private Long engineerId;

    /** ポジションID（NULL=社内/待機） */
    @Setter(AccessLevel.NONE)
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private Long positionId;

    /** 配置種別: 案件/社内/待機 */
    @NotBlank(message = "配置種別は必須です")
    private String allocationType;

    /** 開始日（inclusive） */
    @NotNull(message = "開始日は必須です")
    private LocalDate startDate;

    /** 終了日（inclusive・NULL=open end: 計画window末まで） */
    @Setter(AccessLevel.NONE)
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private LocalDate endDate;

    /** 配賦率(%) */
    @NotNull(message = "配賦率は必須です")
    @DecimalMin(value = "0.01", message = "配賦率は0より大きく入力してください")
    @DecimalMax(value = "100", message = "配賦率は100以下で入力してください")
    private BigDecimal allocationPercent;

    /** 状態: 下書き/確定/破棄 */
    @NotBlank(message = "状態は必須です")
    private String status;

    /** 実契約ID（NOT NULL=actual。planと排他） */
    @Setter(AccessLevel.NONE)
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private Long sourceContractId;

    /** 過配賦例外の理由（例外時必須） */
    @Setter(AccessLevel.NONE)
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String exceptionReason;

    /** 過配賦例外の承認申請ID（例外時必須） */
    @Setter(AccessLevel.NONE)
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private Long approvalRequestId;

    /**
     * JSON に出現した ALWAYS フィールド名（CON-01）。
     * setter 経由でキー出現を記録し、未出現はサービス側で既存値へ回填する。
     */
    @JsonIgnore
    @TableField(exist = false)
    private final Set<String> presentAlwaysFields = new HashSet<>();

    /** 楽観ロック */
    @Version
    private Integer version;

    /** 作成者ID */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    public void setPositionId(Long positionId) {
        this.positionId = positionId;
        presentAlwaysFields.add("positionId");
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
        presentAlwaysFields.add("endDate");
    }

    public void setSourceContractId(Long sourceContractId) {
        this.sourceContractId = sourceContractId;
        presentAlwaysFields.add("sourceContractId");
    }

    public void setExceptionReason(String exceptionReason) {
        this.exceptionReason = exceptionReason;
        presentAlwaysFields.add("exceptionReason");
    }

    public void setApprovalRequestId(Long approvalRequestId) {
        this.approvalRequestId = approvalRequestId;
        presentAlwaysFields.add("approvalRequestId");
    }

    @AssertTrue(message = "終了日は開始日以降を指定してください")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }
}
