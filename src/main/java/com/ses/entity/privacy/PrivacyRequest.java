package com.ses.entity.privacy;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * NF07 DSAR case（{@code t_privacy_request}、V190）。production-bridge-phased-plan.md §3.5。
 *
 * <p>依頼者の連絡先・本人確認書類の本体は保存せず、外部 ticket・証跡の参照だけを持つ。状態遷移は
 * {@code row_version} と現状態を条件にした CAS で行い（{@code PrivacyRequestService}）、{@code updateById} は使わない。
 * 承認・交付の列（{@code preparedBy} 以降）は A2b が書く。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_privacy_request")
public class PrivacyRequest extends BaseEntity {
    private String tenantId;
    private String requestNo;
    private String requestType;
    /** {@code PrivacyCaseState} の部分集合。 */
    private String status;
    private String channel;
    private LocalDateTime receivedAt;
    /** D-06 の期限規則で計算した期限日。規則未設定なら NULL。 */
    private LocalDate dueDate;
    private String intakeTicketRef;
    private String identityState;
    private String identityEvidenceRef;
    /** 適用した D-06 本人確認基準の決定ID。 */
    private String identityRuleRef;
    private Long identityVerifiedBy;
    private LocalDateTime identityVerifiedAt;
    private Long caseOwnerId;
    private String scopeSnapshotHash;
    private Long preparedBy;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private Long approvedPackageId;
    private String approvedContentSha256;
    private Long deliveredBy;
    private LocalDateTime deliveredAt;
    private String deliveryMethod;
    private String deliveryEvidenceRef;
    private Long reopenOfRequestId;
    private Long rowVersion;
    private Long createdBy;
}
