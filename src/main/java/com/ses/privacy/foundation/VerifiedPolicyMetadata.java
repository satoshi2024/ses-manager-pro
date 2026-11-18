package com.ses.privacy.foundation;

import java.time.Instant;

/**
 * ポリシー検証済み内部不変メタデータ（Capability は含まず、パッケージプライベートに閉じる）。
 */
record VerifiedPolicyMetadata(
    String tenantId,
    String policyId,
    String policyVersion,
    String policyDigest,
    String dataElementId,
    String action,
    String providerId,
    String scope,
    Instant effectiveAt,
    Instant expiresAt,
    String retentionRule,
    String disposition
) {}
