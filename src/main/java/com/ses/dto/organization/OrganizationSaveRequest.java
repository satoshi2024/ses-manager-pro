package com.ses.dto.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 組織マスタの登録・更新リクエスト。監査対象外の内部列を受け付けない。 */
public record OrganizationSaveRequest(
        Long legalEntityId,
        @NotBlank(message = "{validation.organization.code}") String code,
        @NotBlank(message = "{validation.organization.name}") String name,
        @NotBlank(message = "{validation.organization.type}") String type,
        Long parentId,
        @NotNull(message = "{validation.organization.validFrom}") LocalDate validFrom,
        LocalDate validTo,
        String status,
        Integer version) {
}
