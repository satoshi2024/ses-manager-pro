package com.ses.dto.integrationhub;

import java.time.LocalDateTime;

/** inbound event管理画面のallow-list DTO。raw hash/snapshot/secretは表示しない。 */
public record InboundEventAdminDto(
        String reference,
        String clientId,
        String providerName,
        String providerEventId,
        Boolean signatureValid,
        String status,
        String resultCode,
        LocalDateTime receivedAt,
        LocalDateTime processedAt,
        LocalDateTime retentionExpiresAt) {
}
