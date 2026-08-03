package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 承認申請エンティティ。route_snapshot_json/payload_json/diff_jsonは申請時点で確定し以後不変。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_approval_request")
public class ApprovalRequest extends BaseEntity {

    private String requestNo;
    private String requestType;
    /** QUOTATION / CONTRACT / INVOICE / BP_PAYMENT / MONTHLY_CLOSING */
    private String targetType;
    private Long targetId;
    /** 申請時点の対象entity版snapshot。実値の意味はF2(対象adapter)が定義する。 */
    private Long targetVersion;
    private Long applicantId;
    private Long organizationId;
    private BigDecimal amountSnapshot;
    private String payloadJson;
    private String diffJson;
    private String routeSnapshotJson;
    /** draft/requested/in_review/returned/approved/rejected/withdrawn/conflict */
    private String status;
    private Integer currentStep;
    /** 差戻し後の再申請ラウンド。初回は1。 */
    private Integer roundNo;
    /** 現在stepの開始時刻。SLA判定はrequestedAtではなくこの値を使う。 */
    private LocalDateTime currentStepStartedAt;
    private LocalDateTime requestedAt;
    private LocalDateTime finalizedAt;
    private String idempotencyKey;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
