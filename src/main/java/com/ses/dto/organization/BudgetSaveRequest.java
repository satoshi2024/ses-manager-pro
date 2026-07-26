package com.ses.dto.organization;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 組織別月次予算の登録・更新リクエスト。 */
public record BudgetSaveRequest(
        @NotNull Long organizationId,
        Long costCenterId,
        @NotNull LocalDate budgetMonth,
        @NotNull @PositiveOrZero BigDecimal revenue,
        @NotNull @PositiveOrZero BigDecimal grossProfit,
        @NotNull @PositiveOrZero Integer utilizationCount,
        @NotNull @PositiveOrZero Integer hireCount,
        Integer version) {
}
