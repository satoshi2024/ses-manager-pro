package com.ses.dto.organization;

import jakarta.validation.constraints.NotNull;

/** 組織統合リクエスト。旧組織の楽観ロック版を必須とする。 */
public record OrganizationMergeRequest(
        @NotNull Long targetOrganizationId,
        @NotNull Integer version) {
}
