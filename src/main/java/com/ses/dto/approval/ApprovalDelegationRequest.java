package com.ses.dto.approval;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/** 期間・対象・委任者/代理者・理由を含む代理登録。 */
public record ApprovalDelegationRequest(
        @NotNull Long fromUserId,
        @NotNull Long toUserId,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        List<String> requestTypes,
        @NotBlank String reason) {
}
