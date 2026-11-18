package com.ses.privacy.foundation;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * test 専用の hold 解除 authority（NF07-REL）。Ed25519 の鍵対を実行のたびに memory 上で生成し、file・設定・main の artifact へ
 * 書かない（試験は test-only 鍵だけを使い、実鍵を使わない・作らない）。registry と claim の署名を test に提供する。
 */
public final class HoldReleaseTestAuthorities {

    public static final Instant VALID_FROM = Instant.parse("2026-01-01T00:00:00Z");
    public static final Instant VALID_UNTIL = Instant.parse("2027-12-31T00:00:00Z");
    public static final String KEY_ID = "k1";

    private final Map<String, PrivateKey> privateKeys = new HashMap<>();
    private final Map<String, HoldReleaseTrustAnchor> anchors = new LinkedHashMap<>();

    /** 発行者・承認者の両方を許す anchor を登録する（tenant・対象種別は SUBJECT と DATA_ELEMENT）。 */
    public HoldReleaseTrustAnchor register(String authorityId, String principalRef, String tenant) {
        return register(authorityId, principalRef, Set.of(tenant),
                Set.of(HoldReleaseAuthorityClaim.Role.ISSUER, HoldReleaseAuthorityClaim.Role.APPROVER),
                Set.of("SUBJECT", "DATA_ELEMENT"), VALID_FROM, VALID_UNTIL);
    }

    /** 範囲と有効期間を指定して anchor を登録する（同じ authority を登録し直すと鍵も作り直す）。 */
    public HoldReleaseTrustAnchor register(String authorityId, String principalRef, Set<String> tenants,
                                           Set<HoldReleaseAuthorityClaim.Role> roles, Set<String> targetTypes,
                                           Instant validFrom, Instant validUntil) {
        KeyPair pair = generate();
        HoldReleaseTrustAnchor anchor = new HoldReleaseTrustAnchor(authorityId, KEY_ID, pair.getPublic(), principalRef,
                tenants, roles, targetTypes, validFrom, validUntil);
        privateKeys.put(anchor.anchorId(), pair.getPrivate());
        anchors.put(anchor.anchorId(), anchor);
        return anchor;
    }

    /** 登録済みの anchor の主体だけを差し替える（同じ鍵。SoD の試験用）。 */
    public void rebindPrincipal(String authorityId, String principalRef) {
        HoldReleaseTrustAnchor old = anchors.get(authorityId + ":" + KEY_ID);
        anchors.put(old.anchorId(), new HoldReleaseTrustAnchor(old.authorityId(), old.keyId(), old.publicKey(),
                principalRef, old.tenants(), old.roles(), old.targetTypes(), old.validFrom(), old.validUntil()));
    }

    public HoldReleaseTrustRegistry registry() {
        return () -> Map.copyOf(anchors);
    }

    /** 登録した鍵で claim に署名する。 */
    public HoldReleaseAuthorityClaim sign(String authorityId, HoldReleaseAuthorityClaim.Role role,
                                          HoldReleaseBinding binding, Instant issuedAt, Instant expiresAt) {
        String anchorId = authorityId + ":" + KEY_ID;
        return new HoldReleaseAuthorityClaim(role, authorityId, KEY_ID, issuedAt, expiresAt,
                signBytes(privateKeys.get(anchorId), HoldReleaseAuthorityClaim.signedBytes(binding, role, authorityId,
                        KEY_ID, issuedAt, expiresAt)));
    }

    /** 未登録の鍵（同じ authority を名乗る別の鍵）で署名する。 */
    public static HoldReleaseAuthorityClaim signWithForeignKey(String authorityId, HoldReleaseAuthorityClaim.Role role,
                                                               HoldReleaseBinding binding, Instant issuedAt,
                                                               Instant expiresAt) {
        return new HoldReleaseAuthorityClaim(role, authorityId, KEY_ID, issuedAt, expiresAt,
                signBytes(generate().getPrivate(), HoldReleaseAuthorityClaim.signedBytes(binding, role, authorityId,
                        KEY_ID, issuedAt, expiresAt)));
    }

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] signBytes(PrivateKey key, byte[] payload) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(payload);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
