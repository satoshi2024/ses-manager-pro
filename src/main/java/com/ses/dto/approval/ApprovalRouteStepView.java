package com.ses.dto.approval;

import java.util.List;

/** route一覧・approver preview共通のstep表示。 */
public record ApprovalRouteStepView(
        Integer stepNo,
        Integer parallelGroup,
        String approverType,
        String approverValue,
        Integer slaHours,
        List<Long> resolvedApproverUserIds) {
}
