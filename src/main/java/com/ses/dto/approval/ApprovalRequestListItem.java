package com.ses.dto.approval;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 承認inbox/申請一覧の公開用行。 */
public record ApprovalRequestListItem(Long id, String requestNo, String requestType,
                                      String targetType, Long targetId, Long applicantId,
                                      BigDecimal amountSnapshot, String status, Integer currentStep,
                                      LocalDateTime requestedAt, LocalDateTime finalizedAt,
                                      String targetUrl) {
}
