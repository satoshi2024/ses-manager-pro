package com.ses.privacy.foundation;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Set;

/**
 * hold 解除 authority の trust anchor（NF07-REL。D-03 の署名鍵と D-05 の権限者名簿から登録する）。
 *
 * <p>{@code principalRef} は authority の主体で、要求者・発行者・承認者の分離（SoD）の比較に使う（例:
 * {@code sys_user:<id>}。要求者は同じ名前空間の値で表す）。{@code tenants}・{@code roles}・{@code targetTypes}
 * （{@code SUBJECT} / {@code DATA_ELEMENT}）は解除権限の範囲で、空欄・不足は拒否として扱う。
 */
public record HoldReleaseTrustAnchor(String authorityId, String keyId, PublicKey publicKey, String principalRef,
                                     Set<String> tenants, Set<HoldReleaseAuthorityClaim.Role> roles,
                                     Set<String> targetTypes, Instant validFrom, Instant validUntil) {

    public HoldReleaseTrustAnchor {
        if (authorityId == null || authorityId.isBlank() || keyId == null || keyId.isBlank() || publicKey == null
                || principalRef == null || principalRef.isBlank()) {
            throw new IllegalArgumentException("解除 authority の識別子・鍵・主体は必須です");
        }
        if (tenants == null || roles == null || targetTypes == null) {
            throw new IllegalArgumentException("解除 authority の範囲は必須です");
        }
        tenants = Set.copyOf(tenants);
        roles = Set.copyOf(roles);
        targetTypes = Set.copyOf(targetTypes);
        if (validFrom == null || validUntil == null || !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("解除 authority の有効期間が不正です");
        }
    }

    /** registry の key（{@code authorityId + ":" + keyId}）。 */
    public String anchorId() {
        return authorityId + ":" + keyId;
    }
}
