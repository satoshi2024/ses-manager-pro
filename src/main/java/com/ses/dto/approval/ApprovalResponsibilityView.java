package com.ses.dto.approval;

import java.time.LocalDate;

/** 承認責任者assignmentの監査表示DTO。 */
public record ApprovalResponsibilityView(
        Long id,
        String responsibilityType,
        Long organizationId,
        Long userId,
        String userName,
        LocalDate validFrom,
        LocalDate validTo,
        Integer activeFlag) {
}
