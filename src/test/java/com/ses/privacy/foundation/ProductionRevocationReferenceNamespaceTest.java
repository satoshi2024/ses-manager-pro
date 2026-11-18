package com.ses.privacy.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本番の verifier が鍵と envelope の失効状態の名前空間を文法で分けること（RB-NF07-TRUST-KMS review P2-1、
 * {@link RevocationReferences}）。envelope の {@code revocationReference} が {@code :} を含む（鍵の ID {@code authorityId:keyId} と
 * 区別できない）なら、失効 provider に照会せず失効状態を UNKNOWN とし、{@code REVOCATION_STATUS_UNKNOWN} で拒否する。どの参照にも
 * ACTIVE と答える provider でも通らない。鍵は test-only（実行のたびに memory 上で生成）、envelope の値は合成値。
 */
class ProductionRevocationReferenceNamespaceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");
    private static final String TENANT = "default";

    @Test
    void コロンを含むrevocationReferenceは照会せずREVOCATION_STATUS_UNKNOWNで拒否する() throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        List<String> asked = new ArrayList<>();
        PolicyRegistrationVerifier verifier = verifier(key, reference -> {
            asked.add(reference);
            return RevocationStatus.ACTIVE;
        });

        for (String reference : List.of("policy-issuer:kms-2026a", "issuer-a:k1", "urn:nf07:policy-1", ":")) {
            asked.clear();
            byte[] canonical = canonical(reference);
            assertThatThrownBy(() -> verifier.verify(TENANT, canonical, sign(key, canonical))).as(reference)
                    .isInstanceOfSatisfying(PolicyRegistrationRejectedException.class,
                            e -> assertThat(e.reason()).isEqualTo(PolicyFailureReason.REVOCATION_STATUS_UNKNOWN));
            assertThat(asked).as("鍵の ID だけを照会し、':' を含む envelope の参照は照会しない")
                    .containsExactly("policy-issuer:kms-2026a");
        }

        asked.clear();
        byte[] canonical = canonical("envelope-revocation-1");
        assertThat(verifier.verify(TENANT, canonical, sign(key, canonical))).isNotNull();
        assertThat(asked).containsExactly("policy-issuer:kms-2026a", "envelope-revocation-1");
    }

    @Test
    void 名前空間の規則はコロンの有無だけで決まる() {
        assertThat(RevocationReferences.isEnvelopeReference("envelope-revocation-1")).isTrue();
        assertThat(RevocationReferences.isEnvelopeReference("policy-issuer:kms-2026a")).isFalse();
        assertThat(RevocationReferences.isEnvelopeReference("")).isFalse();
        assertThat(RevocationReferences.isEnvelopeReference(" ")).isFalse();
        assertThat(RevocationReferences.isEnvelopeReference(null)).isFalse();
        assertThat(RevocationReferences.isKeyReference("policy-issuer:kms-2026a")).isTrue();
        assertThat(RevocationReferences.isKeyReference("envelope-revocation-1")).isFalse();
        assertThat(RevocationReferences.isKeyReference(null)).isFalse();
    }

    private static PolicyRegistrationVerifier verifier(KeyPair key, RevocationStatusProvider revocations) {
        TrustedAuthority anchor = new TrustedAuthority("policy-issuer", "kms-2026a", key.getPublic(), Set.of(TENANT),
                Set.of("NF08_AI_SUMMARY"), Set.of("AI_PROMPT_LOG"), Set.of("POLICY"), Set.of("PRODUCTION"),
                Set.of("EXTERNAL_DISPATCH"), Set.of("gemini-adapter"), NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(365)));
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        return ProductionPrivacyComponents.policyRegistrationVerifier(prod,
                () -> Map.of("policy-issuer:kms-2026a", anchor), revocations, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static byte[] canonical(String revocationReference) {
        ApprovalEnvelopeMetadata metadata = new ApprovalEnvelopeMetadata("1", "policy-issuer", "kms-2026a", TENANT,
                "NF08_AI_SUMMARY", ProductionEnvironmentGuard.PRODUCTION_AUDIENCE, "r1", "requester-test",
                "approver-test", revocationReference, NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofDays(30)));
        return ApprovalEnvelopeCanonicalizer.canonicalBytes(new PolicyApprovalEnvelope(metadata, "policy-test-1", "v1",
                "AI_PROMPT_LOG", "EXTERNAL_DISPATCH", "gemini-adapter", "TEST_PURPOSE", "TEST_TRIGGER",
                "TEST_ONLY_RULE", "TEST_ONLY_DISPOSITION", new byte[]{0}));
    }

    private static byte[] sign(KeyPair key, byte[] canonical) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(PolicyEnvelopeSigningStatement.of(canonical));
        return signer.sign();
    }
}
