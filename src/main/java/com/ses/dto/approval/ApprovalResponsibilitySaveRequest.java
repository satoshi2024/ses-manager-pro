package com.ses.dto.approval;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 組織責任者・財務責任者assignmentの登録リクエスト。 */
public record ApprovalResponsibilitySaveRequest(
        @NotBlank String responsibilityType,
        Long organizationId,
        @NotNull Long userId,
        @NotNull LocalDate validFrom,
        LocalDate validTo) {
}
