package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.Objects;

/** 署名対象となる共通承認情報。全項目を正規化署名payloadへ含める。 */
public record ApprovalEnvelopeMetadata(
        String schemaVersion,
        String authorityId,
        String keyId,
        String tenantId,
        String scope,
        String audience,
        String revision,
        String requesterId,
        String approverId,
        String revocationReference,
        Instant issuedAt,
        Instant effectiveAt,
        Instant expiresAt) {

    public ApprovalEnvelopeMetadata {
        schemaVersion = required(schemaVersion, "schemaVersion");
        authorityId = required(authorityId, "authorityId");
        keyId = required(keyId, "keyId");
        tenantId = required(tenantId, "tenantId");
        scope = required(scope, "scope");
        audience = required(audience, "audience");
        revision = required(revision, "revision");
        requesterId = required(requesterId, "requesterId");
        approverId = required(approverId, "approverId");
        revocationReference = required(revocationReference, "revocationReference");
        Objects.requireNonNull(issuedAt, "issuedAtが必要です");
        Objects.requireNonNull(effectiveAt, "effectiveAtが必要です");
        Objects.requireNonNull(expiresAt, "expiresAtが必要です");
        if (!expiresAt.isAfter(effectiveAt)) {
            throw new IllegalArgumentException("expiresAtはeffectiveAtより後でなければなりません");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "は必須です");
        }
        return value;
    }
}
