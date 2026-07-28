package com.ses.dto.organization;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** ユーザー所属履歴の登録リクエスト。 */
public record UserOrganizationSaveRequest(
        @NotNull(message = "{validation.organization.id.required}") Long organizationId,
        String positionName,
        Long managerUserId,
        Integer primaryFlag,
        @NotNull(message = "{validation.organization.validFrom.required}") LocalDate validFrom,
        LocalDate validTo,
        /** 異動・更新時に画面が読んだ所属の版番号。新規登録では未指定でよい。 */
        Integer version) {
}
