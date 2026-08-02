package com.ses.dto.approval;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 汎用承認申請APIの入力（F1）。F2の各対象adapterは自前のsnapshot()/validateBeforeRequest()で
 * このcommand相当を組み立ててengineへ委譲する想定で、本DTOはengine coreの直接テスト・Demo用。
 * applicantIdはクライアント指定を許さず、常にログインユーザーで上書きする。
 */
public record ApprovalRequestCreateRequest(
        @NotBlank(message = "対象種別は必須です") String requestType,
        @NotBlank(message = "対象type種別は必須です") String targetType,
        Long targetId,
        Long targetVersion,
        Long organizationId,
        BigDecimal amountSnapshot,
        Map<String, Object> payload,
        Map<String, Object> diff,
        String idempotencyKey
) {
}
