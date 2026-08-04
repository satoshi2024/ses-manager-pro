package com.ses.dto.approval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** routeの新規登録・version改版リクエスト。既存行を更新せず新versionを追加する。 */
public record ApprovalRouteSaveRequest(
        Long routeId,
        @NotBlank String requestType,
        Long organizationId,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        String applicantRoleCondition,
        @NotEmpty List<@Valid ApprovalRouteStepRequest> steps) {

    /** 既存のroute登録呼出しとのsource互換を保つ旧形式constructor。 */
    public ApprovalRouteSaveRequest(Long routeId, String requestType, Long organizationId,
                                    BigDecimal minAmount, BigDecimal maxAmount,
                                    LocalDate validFrom, LocalDate validTo,
                                    List<ApprovalRouteStepRequest> steps) {
        this(routeId, requestType, organizationId, minAmount, maxAmount,
                validFrom, validTo, null, steps);
    }
}
