package com.ses.dto.approval;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** version付きrouteの監査可能な表示DTO。 */
public record ApprovalRouteView(
        Long id,
        String requestType,
        Long organizationId,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Integer versionNo,
        LocalDate validFrom,
        LocalDate validTo,
        Integer activeFlag,
        List<ApprovalRouteStepView> steps) {
}
