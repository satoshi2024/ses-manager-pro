package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.Objects;

/**
 * 合成環境・セキュリティ境界で認証済みの不可変な呼び出し元コンテキスト。
 * DTO による自報ロールは受け付けず、信頼されたプロバイダーからのみ提供される。
 */
public record TrustedCallerContext(
    String tenantId,
    String actorId,
    TrustedActorRole actorRole,
    Instant authenticatedAt
) {
    public TrustedCallerContext {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(actorRole, "actorRole must not be null");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt must not be null");
        if (tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        if (actorId.isBlank()) throw new IllegalArgumentException("actorId must not be blank");
    }
}
