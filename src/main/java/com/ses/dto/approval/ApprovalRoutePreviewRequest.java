package com.ses.dto.approval;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 指定asOfでrouteと承認者を事前確認するリクエスト。 */
public record ApprovalRoutePreviewRequest(
        @NotBlank String requestType,
        Long organizationId,
        BigDecimal amountSnapshot,
        @NotNull Long applicantId,
        LocalDate asOf) {
}
