package com.ses.dto.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 組織マスタの登録・更新リクエスト。監査対象外の内部列を受け付けない。 */
public record OrganizationSaveRequest(
        Long legalEntityId,
        @NotBlank(message = "組織コードは必須です") String code,
        @NotBlank(message = "組織名は必須です") String name,
        @NotBlank(message = "組織種別は必須です") String type,
        Long parentId,
        @NotNull(message = "有効開始日は必須です") LocalDate validFrom,
        LocalDate validTo,
        String status,
        Integer version) {
}
