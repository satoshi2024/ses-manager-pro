package com.ses.dto.security;

import java.time.LocalDateTime;

/** session一覧の公開用DTO。session IDの原文/hashは返さない。 */
public record SessionSummary(
        Long id,
        LocalDateTime issuedAt,
        LocalDateTime lastSeenAt,
        LocalDateTime idleExpiresAt,
        LocalDateTime expiresAt,
        String userAgent,
        boolean current) {
}
