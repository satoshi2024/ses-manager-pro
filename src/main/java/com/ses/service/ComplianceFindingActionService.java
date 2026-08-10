package com.ses.service;

import java.time.LocalDateTime;

/**
 * compliance findingの対応状態遷移（T065 B2、R3.4）。
 *  - ack: OPEN/IN_PROGRESS → ACKNOWLEDGED（acknowledged_by/at記録）
 *  - inProgress: OPEN/ACKNOWLEDGED → IN_PROGRESS
 *  - resolve: 任意状態 → RESOLVED（根拠note必須、evidence任意）
 *  - exception: OPEN/ACKNOWLEDGED/IN_PROGRESS → EXCEPTION_APPROVED（根拠note＋有効期限必須）
 * 権限: 管理者/HR/マネージャー（compliance権限＋write）。営業は403。
 */
public interface ComplianceFindingActionService {

    void ack(Long contractId, Long findingId);

    void inProgress(Long contractId, Long findingId);

    void resolve(Long contractId, Long findingId, String note, Long evidenceDocumentId);

    void exception(Long contractId, Long findingId, String note, LocalDateTime expiresAt);
}
