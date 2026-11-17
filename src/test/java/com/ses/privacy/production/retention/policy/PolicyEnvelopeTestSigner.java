package com.ses.privacy.production.retention.policy;

import com.ses.privacy.foundation.ApprovalEnvelopeCanonicalizer;
import com.ses.privacy.foundation.ApprovalEnvelopeMetadata;
import com.ses.privacy.foundation.PolicyApprovalEnvelope;
import com.ses.privacy.foundation.ProductionPolicyTrustRegistry;
import com.ses.privacy.foundation.TrustedAuthority;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * test 専用の policy envelope の署名者（NF07-REG）。Ed25519 の鍵対を実行のたびに memory 上で生成し、file・設定・main の artifact へ
 * 書かない（試験は test-only 鍵だけを使い、実鍵を使わない・作らない）。envelope の値は合成値で、法的な値ではない。
 */
final class PolicyEnvelopeTestSigner {

    static final String MODULE = "NF08_AI_SUMMARY";
    static final String ELEMENT = "AI_PROMPT_LOG";
    static final String ACTION = "EXTERNAL_DISPATCH";
    static final String PROVIDER = "gemini-adapter";
    static final String AUTHORITY = "policy-issuer-test";
    static final String KEY_ID = "k1";
    static final String REVOCATION_REF = "envelope-revocation-test";

    private final String tenantId;
    private final Instant now;
    private final Map<String, TrustedAuthority> anchors = new LinkedHashMap<>();
    private final Map<String, PrivateKey> keys = new LinkedHashMap<>();
    private int signatures;

    PolicyEnvelopeTestSigner(String tenantId, Instant now) {
        this.tenantId = tenantId;
        this.now = now;
        register(AUTHORITY);
    }

    /** 範囲が本 test の key だけの anchor を登録する（同じ authority を登録し直すと鍵も作り直す）。 */
    TrustedAuthority register(String authorityId) {
        KeyPair pair = generate();
        TrustedAuthority anchor = new TrustedAuthority(authorityId, KEY_ID, pair.getPublic(), Set.of(tenantId),
                Set.of(MODULE), Set.of(ELEMENT), Set.of("POLICY"), Set.of("PRODUCTION"), Set.of(ACTION),
                Set.of(PROVIDER), now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(365)));
        anchors.put(authorityId + ":" + KEY_ID, anchor);
        keys.put(authorityId + ":" + KEY_ID, pair.getPrivate());
        return anchor;
    }

    ProductionPolicyTrustRegistry registry() {
        return () -> Map.copyOf(anchors);
    }

    /** 登録済みの anchor の一覧（その時点の値）。 */
    Map<String, TrustedAuthority> anchors() {
        return Map.copyOf(anchors);
    }

    /** anchor を registry から外す（鍵の削除の再現。署名の秘密鍵は残す）。 */
    void remove(String authorityId) {
        anchors.remove(authorityId + ":" + KEY_ID);
    }

    /** 署名済み envelope（既定の key・登録済みの authority）。 */
    Signed sign(String policyId, String revision) {
        return sign(policyId, revision, AUTHORITY, "requester-test", "approver-test", "TEST_ONLY_RULE");
    }

    /**
     * 値を指定して署名する（{@code authorityId} が未登録なら、登録されていない鍵で署名する）。発行日時は署名のたびに1秒ずつ進める
     * （後に署名した revision ほど新しい。有効化の順序の規則に合わせる）。
     */
    Signed sign(String policyId, String revision, String authorityId, String requesterId, String approverId,
                String retentionRule) {
        return sign(policyId, revision, authorityId, requesterId, approverId, retentionRule,
                now.minus(Duration.ofHours(1)).plusSeconds(signatures++));
    }

    /** 発行日時を指定して署名する。 */
    Signed signIssuedAt(String policyId, String revision, Instant issuedAt) {
        return sign(policyId, revision, AUTHORITY, "requester-test", "approver-test", "TEST_ONLY_RULE", issuedAt);
    }

    private Signed sign(String policyId, String revision, String authorityId, String requesterId, String approverId,
                        String retentionRule, Instant issuedAt) {
        ApprovalEnvelopeMetadata metadata = new ApprovalEnvelopeMetadata("1", authorityId, KEY_ID, tenantId, MODULE,
                "PRODUCTION", revision, requesterId, approverId, REVOCATION_REF, issuedAt,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(30)));
        PolicyApprovalEnvelope unsigned = new PolicyApprovalEnvelope(metadata, policyId, "v1", ELEMENT, ACTION,
                PROVIDER, "TEST_PURPOSE", "TEST_TRIGGER", retentionRule, "TEST_ONLY_DISPOSITION", new byte[]{0});
        byte[] canonical = ApprovalEnvelopeCanonicalizer.canonicalBytes(unsigned);
        PrivateKey key = keys.getOrDefault(authorityId + ":" + KEY_ID, generate().getPrivate());
        return new Signed(canonical, sign(key, canonical));
    }

    static byte[] sign(PrivateKey key, byte[] payload) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(payload);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** canonical bytes と署名。 */
    record Signed(byte[] canonical, byte[] signature) {
        Signed {
            canonical = canonical.clone();
            signature = signature.clone();
        }

        @Override
        public byte[] canonical() {
            return canonical.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }

        String digest() {
            return ApprovalEnvelopeCanonicalizer.digest(canonical);
        }

        PrivacyPolicyRegistrationRequest request() {
            return new PrivacyPolicyRegistrationRequest(Base64.getEncoder().encodeToString(canonical),
                    Base64.getEncoder().encodeToString(signature));
        }

        /** 別の canonical bytes に同じ署名を付けた要求（改竄の再現）。 */
        PrivacyPolicyRegistrationRequest withCanonical(byte[] other) {
            return new PrivacyPolicyRegistrationRequest(Base64.getEncoder().encodeToString(other),
                    Base64.getEncoder().encodeToString(signature));
        }
    }
}
