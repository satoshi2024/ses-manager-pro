package com.ses.dto.approval;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** route改版時のstep定義。 */
public record ApprovalRouteStepRequest(
        @NotNull @Min(1) Integer stepNo,
        @NotNull @Min(1) Integer parallelGroup,
        @NotBlank String approverType,
        String approverValue,
        @Min(0) Integer slaHours) {
}
