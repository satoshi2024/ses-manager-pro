package com.ses.dto.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 原価部門の登録・更新リクエスト。 */
public record CostCenterSaveRequest(
        Long legalEntityId,
        @NotBlank(message = "{validation.costCenter.code.required}") String code,
        @NotBlank(message = "{validation.costCenter.name.required}") String name,
        Long organizationId,
        @NotNull(message = "{validation.organization.validFrom.required}") LocalDate validFrom,
        LocalDate validTo,
        String status,
        Integer version) {
}
