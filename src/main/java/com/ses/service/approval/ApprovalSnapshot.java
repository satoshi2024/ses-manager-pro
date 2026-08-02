package com.ses.service.approval;

import java.math.BigDecimal;
import java.util.Map;

/**
 * F2の対象adapterが対象entityの現在値から作る申請素材。design §2の{@code ApprovalSnapshot}。
 */
public record ApprovalSnapshot(
        Long targetVersion,
        BigDecimal amountSnapshot,
        Long organizationId,
        Map<String, Object> payload,
        Map<String, Object> diff
) {
}
