package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 二者確認の提出時の組織集合（{@code t_lifecycle_dual_submission_org_fence}、V195）。
 *
 * <p>主キーは (submission_id, organization_id) の複合keyのため {@code @TableId} を置かない。組織はtenantに依存しない
 * （計画 §2.6）ため、組織への参照はIDだけである。追記専用
 * （{@link com.ses.mapper.LifecycleDualSubmissionOrgFenceMapper} とDB triggerが更新・削除を拒否する）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_lifecycle_dual_submission_org_fence")
public class LifecycleDualSubmissionOrgFence {

    /** テナントID（提出と同じtenant。複合FKで固定される） */
    private String tenantId;

    /** 提出ID (t_lifecycle_dual_submission.id) */
    private Long submissionId;

    /** 組織ID (m_organization_unit.id) */
    private Long organizationId;

    /** 提出時の組織部分木scope epoch */
    private Long orgScopeEpoch;
}
