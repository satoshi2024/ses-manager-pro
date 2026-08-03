package com.ses.service.approval;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 承認申請の生成コマンド。F2の各対象adapterが{@code snapshot()}/{@code validateBeforeRequest()}で
 * 組み立てて{@link ApprovalEngineService#request(ApprovalRequestCommand)}へ渡す想定。
 * F1では汎用API（{@code POST /api/approval/requests}）が直接このコマンドを受け取る。
 */
public record ApprovalRequestCommand(
        String requestType,
        String targetType,
        Long targetId,
        Long targetVersion,
        Long applicantId,
        Long organizationId,
        BigDecimal amountSnapshot,
        Map<String, Object> payload,
        Map<String, Object> diff,
        String idempotencyKey
) {
}
