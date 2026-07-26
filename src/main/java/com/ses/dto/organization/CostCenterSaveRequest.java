package com.ses.dto.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 原価部門の登録・更新リクエスト。 */
public record CostCenterSaveRequest(
        Long legalEntityId,
        @NotBlank(message = "原価部門コードは必須です") String code,
        @NotBlank(message = "原価部門名は必須です") String name,
        Long organizationId,
        @NotNull(message = "有効開始日は必須です") LocalDate validFrom,
        LocalDate validTo,
        String status,
        Integer version) {
}
