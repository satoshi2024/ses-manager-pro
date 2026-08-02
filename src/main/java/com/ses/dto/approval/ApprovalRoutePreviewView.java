package com.ses.dto.approval;

import java.util.List;

/** route解決結果のapprover preview。 */
public record ApprovalRoutePreviewView(
        Long routeId,
        Integer versionNo,
        Long organizationId,
        List<ApprovalRouteStepView> steps) {
}
