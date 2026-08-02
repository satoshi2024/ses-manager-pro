package com.ses.dto.approval;

import java.time.LocalDate;
import java.util.List;

/** 代理設定の監査表示DTO。 */
public record ApprovalDelegationView(
        Long id,
        Long fromUserId,
        String fromUserName,
        Long toUserId,
        String toUserName,
        LocalDate validFrom,
        LocalDate validTo,
        List<String> requestTypes,
        String reason,
        Long approvedBy,
        Long createdBy) {
}
