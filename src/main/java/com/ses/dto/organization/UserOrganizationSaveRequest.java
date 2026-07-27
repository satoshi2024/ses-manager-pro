package com.ses.dto.organization;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** ユーザー所属履歴の登録リクエスト。 */
public record UserOrganizationSaveRequest(
        @NotNull(message = "組織IDは必須です") Long organizationId,
        String positionName,
        Long managerUserId,
        Integer primaryFlag,
        @NotNull(message = "有効開始日は必須です") LocalDate validFrom,
        LocalDate validTo,
        /** 異動・更新時に画面が読んだ所属の版番号。新規登録では未指定でよい。 */
        Integer version) {
}
