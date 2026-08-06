package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 月次検収エンティティ（t_acceptance）。
 * 契約×月の検収を管理し、対象work record・提出日・顧客確認者・確認日・結果・差戻し理由・原本を持つ。
 * UNIQUE(contract_id, work_month) で契約×月1件を保証する。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_acceptance")
public class Acceptance extends BaseEntity {

    /** 契約ID */
    @NotNull(message = "契約は必須です")
    private Long contractId;

    /** 対象work record ID */
    private Long workRecordId;

    /** 対象月(YYYY-MM) */
    @NotNull(message = "対象月は必須です")
    private String workMonth;

    /** 状態: 未提出 / 提出済 / 検収済 / 差戻し */
    private String status;

    /** 提出日時 */
    private LocalDateTime submittedAt;

    /** 顧客確認者ID */
    private Long customerContactId;

    /** 顧客確認者名snapshot（検収実行時点で固定。改名後も過去の検収証跡は不変） */
    private String customerContactNameSnapshot;

    /** 検収日時 */
    private LocalDateTime acceptedAt;

    /** 差戻し理由 */
    private String rejectComment;

    /** 検収書document ID */
    private Long documentId;

    /** 提出時点の工数snapshot（以後の工数変更で検収額を変えない） */
    private BigDecimal hoursSnapshot;

    /** 提出時点の請求金額snapshot */
    private BigDecimal amountSnapshot;

    /** 提出時点のwork record更新日時（version代用） */
    private LocalDateTime workRecordUpdatedAt;

    /** 楽観ロックバージョン */
    @Version
    private Integer version;

    /** 作成者ID */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
