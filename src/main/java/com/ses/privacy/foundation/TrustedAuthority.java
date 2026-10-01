package com.ses.privacy.foundation;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Set;

/** 明示登録されたtrust anchor。許可listの空欄・不足は拒否として扱う。 */
public record TrustedAuthority(
        String authorityId,
        String keyId,
        PublicKey publicKey,
        Set<String> tenants,
        Set<String> scopes,
        Set<String> dataElements,
        Set<String> envelopeKinds,
        Set<String> audiences,
        Set<String> actions,
        Set<String> providers,
        Instant validFrom,
        Instant validUntil) {

    public TrustedAuthority {
        authorityId = required(authorityId, "authorityId");
        keyId = required(keyId, "keyId");
        if (publicKey == null) throw new IllegalArgumentException("publicKeyは必須です");
        tenants = Set.copyOf(tenants);
        scopes = Set.copyOf(scopes);
        dataElements = Set.copyOf(dataElements);
        envelopeKinds = Set.copyOf(envelopeKinds);
        audiences = Set.copyOf(audiences);
        actions = Set.copyOf(actions);
        providers = Set.copyOf(providers);
        if (validFrom == null || validUntil == null || !validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("authorityの有効期間が不正です");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "は必須です");
        return value;
    }
}
