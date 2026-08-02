package com.ses.dto.approval;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 承認申請の画面/export向け公開DTO。生のpayload/diff JSONを返さない。 */
public record ApprovalRequestView(Long id, String requestNo, String requestType, String targetType,
                                  Long targetId, Long targetVersion, Long applicantId,
                                  Long organizationId, BigDecimal amountSnapshot, String status,
                                  Integer currentStep, LocalDateTime requestedAt,
                                  LocalDateTime finalizedAt, String targetUrl,
                                  List<ApprovalDiffItem> diff, List<ApprovalActionView> actions,
                                  Map<String, Object> payload, boolean canApprove,
                                  boolean canReject, boolean canReturn, boolean canWithdraw,
                                  boolean canResubmit) {
}
