package com.ses.privacy.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.EdDSAParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * policy envelope の digest 声明（{@link PolicyEnvelopeSigningStatement}）と、AWS KMS の {@code MessageType=RAW} の上限
 * （4096 bytes）に対する被署名 bytes の長さ（NF07-TRUST）。
 *
 * <ul>
 *   <li>policy envelope の canonical bytes は 4096 bytes を超え得る（登録の上限は 64KB）。KMS では固定長（100 bytes）の digest
 *       声明に純粋な Ed25519 で署名し、本番の verifier はこの形<b>だけ</b>を受け付ける（canonical bytes そのものへの署名は、
 *       4096 bytes 以下でも拒否する。RB-NF07-TRUST-KMS review P3-2）。</li>
 *   <li>Ed25519ph（prehash。KMS の {@code ED25519_PH_SHA_512}）の署名と、digest だけへの署名は受け付けない。</li>
 *   <li>canonical bytes への署名を検証する合成の {@link ApprovalEnvelopeVerifier}（本番ではない）の形と声明は、先頭の bytes で
 *       区別できる（先頭を固定する）。</li>
 *   <li>hold 解除の claim の被署名 bytes は、authority・key の ID が64文字・日時が {@link Instant} の範囲の端でも 4096 bytes を
 *       大きく下回る（digest 声明は要らない）。</li>
 * </ul>
 * test vector の値は Node.js で独立に計算した。鍵は test-only（実行のたびに memory 上で生成）。
 */
class PolicyEnvelopeSigningStatementTest {

    private static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");
    private static final String TENANT = "default";

    @Test
    void 声明は長さ付きfieldの固定長100bytesでNodejsで計算したtest_vectorと一致する() throws Exception {
        byte[] statement = PolicyEnvelopeSigningStatement.of("abc".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(statement, StandardCharsets.US_ASCII)).isEqualTo("30:NF07-POLICY-ENVELOPE-DIGEST-V164:"
                + "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(statement).hasSize(PolicyEnvelopeSigningStatement.LENGTH);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(statement)))
                .isEqualTo("9d79de0b996eff91f78260cbc8c886e2be239e01a1487ac6ad03a1683bfa7e66");
        assertThatThrownBy(() -> PolicyEnvelopeSigningStatement.of(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 上限の4096bytesを超えるenvelopeは声明への純粋なEd25519署名で本番の検証に通る() throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PolicyApprovalEnvelope unsigned = envelope("R".repeat(6000));
        byte[] canonical = ApprovalEnvelopeCanonicalizer.canonicalBytes(unsigned);
        assertThat(canonical.length).as("KMS の RAW では署名できない長さ")
                .isGreaterThan(PolicyEnvelopeSigningStatement.KMS_RAW_MESSAGE_LIMIT);
        byte[] statement = PolicyEnvelopeSigningStatement.of(canonical);
        assertThat(statement.length).isLessThanOrEqualTo(PolicyEnvelopeSigningStatement.KMS_RAW_MESSAGE_LIMIT);
        PolicyRegistrationVerifier verifier = verifier(key);

        assertThat(verifier.verify(TENANT, canonical, sign(key, statement, false)).digest())
                .isEqualTo(ApprovalEnvelopeCanonicalizer.digest(canonical));
    }

    @Test
    void 本番はcanonical_bytesそのものへの署名を4096bytes以下でも受け付けない() throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] canonical = ApprovalEnvelopeCanonicalizer.canonicalBytes(envelope("TEST_ONLY_RULE"));
        assertThat(canonical.length).isLessThanOrEqualTo(PolicyEnvelopeSigningStatement.KMS_RAW_MESSAGE_LIMIT);
        PolicyRegistrationVerifier verifier = verifier(key);

        assertThatThrownBy(() -> verifier.verify(TENANT, canonical, sign(key, canonical, false)))
                .isInstanceOfSatisfying(PolicyRegistrationRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(PolicyFailureReason.SIGNATURE_INVALID));
        assertThat(verifier.verify(TENANT, canonical, sign(key, PolicyEnvelopeSigningStatement.of(canonical), false)))
                .isNotNull();
    }

    @Test
    void 合成層のcanonical_bytesの形と本番の声明は先頭のbytesで区別できる() {
        byte[] canonical = ApprovalEnvelopeCanonicalizer.canonicalBytes(envelope("TEST_ONLY_RULE"));
        byte[] statement = PolicyEnvelopeSigningStatement.of(canonical);
        byte[] canonicalHead = new byte[24];
        canonicalHead[3] = 20;
        System.arraycopy("SES-PRIVACY-APPROVAL".getBytes(StandardCharsets.US_ASCII), 0, canonicalHead, 4, 20);

        // canonical bytes は4 byte の長さ 20（00 00 00 14）と SES-PRIVACY-APPROVAL で始まる
        assertThat(java.util.Arrays.copyOf(canonical, 24)).isEqualTo(canonicalHead);
        // 声明は ASCII の 30:NF07-POLICY-ENVELOPE-DIGEST-V1 で始まり、1 byte 目から canonical bytes と違う
        assertThat(new String(statement, 0, 33, StandardCharsets.US_ASCII))
                .isEqualTo("30:" + PolicyEnvelopeSigningStatement.DOMAIN);
        assertThat(statement[0]).isNotEqualTo(canonical[0]);
    }

    @Test
    void Ed25519phの署名と別envelopeの声明とdigestだけへの署名は受け付けない() throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] canonical = ApprovalEnvelopeCanonicalizer.canonicalBytes(envelope("TEST_ONLY_RULE"));
        byte[] other = ApprovalEnvelopeCanonicalizer.canonicalBytes(envelope("TEST_ONLY_RULE_2"));
        byte[] statement = PolicyEnvelopeSigningStatement.of(canonical);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
        PolicyRegistrationVerifier verifier = verifier(key);

        for (byte[] signature : new byte[][]{sign(key, statement, true), sign(key, canonical, true),
                sign(key, PolicyEnvelopeSigningStatement.of(other), false), sign(key, digest, false),
                sign(key, HexFormat.of().formatHex(digest).getBytes(StandardCharsets.US_ASCII), false)}) {
            assertThatThrownBy(() -> verifier.verify(TENANT, canonical, signature))
                    .isInstanceOfSatisfying(PolicyRegistrationRejectedException.class,
                            e -> assertThat(e.reason()).isEqualTo(PolicyFailureReason.SIGNATURE_INVALID));
        }
        // 別の鍵の声明への署名も受け付けない
        KeyPair stranger = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThatThrownBy(() -> verifier.verify(TENANT, canonical, sign(stranger, statement, false)))
                .isInstanceOfSatisfying(PolicyRegistrationRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(PolicyFailureReason.SIGNATURE_INVALID));
    }

    @Test
    void hold解除のclaimの被署名bytesはIDと日時の上限でもKMSのRAWの上限を大きく下回る() {
        HoldReleaseBinding binding = new HoldReleaseBinding(TENANT, "ENGINEER:7", "ph-" + "a".repeat(32), 1,
                "d".repeat(64), "hr-" + "b".repeat(32), 1, "c".repeat(64), "sys_user:1", "sys_user:2");
        String id = "a".repeat(64);
        byte[] longest = HoldReleaseAuthorityClaim.signedBytes(binding, HoldReleaseAuthorityClaim.Role.APPROVER, id, id,
                Instant.MAX, Instant.MAX);
        // domain 33 + PRODUCTION 13 + HOLD_RELEASE 15 + APPROVER 10 + authority 67 + key 67 + 日時 40×2 + 束縛の digest 67
        // （Instant の最長の表記は +1000000000-12-31T23:59:59.999999999Z の37文字）
        assertThat(longest).hasSize(352);
        assertThat(longest.length).isLessThan(PolicyEnvelopeSigningStatement.KMS_RAW_MESSAGE_LIMIT);
    }

    private static PolicyRegistrationVerifier verifier(KeyPair key) {
        TrustedAuthority anchor = new TrustedAuthority("policy-issuer", "kms-2026a", key.getPublic(), Set.of(TENANT),
                Set.of("NF08_AI_SUMMARY"), Set.of("AI_PROMPT_LOG"), Set.of("POLICY"), Set.of("PRODUCTION"),
                Set.of("EXTERNAL_DISPATCH"), Set.of("gemini-adapter"), NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(365)));
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        return ProductionPrivacyComponents.policyRegistrationVerifier(prod,
                () -> Map.of("policy-issuer:kms-2026a", anchor), ref -> RevocationStatus.ACTIVE,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PolicyApprovalEnvelope envelope(String retentionRule) {
        ApprovalEnvelopeMetadata metadata = new ApprovalEnvelopeMetadata("1", "policy-issuer", "kms-2026a", TENANT,
                "NF08_AI_SUMMARY", ProductionEnvironmentGuard.PRODUCTION_AUDIENCE, "r1", "requester-test",
                "approver-test", "rev-test", NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofDays(30)));
        return new PolicyApprovalEnvelope(metadata, "policy-test-1", "v1", "AI_PROMPT_LOG", "EXTERNAL_DISPATCH",
                "gemini-adapter", "TEST_PURPOSE", "TEST_TRIGGER", retentionRule, "TEST_ONLY_DISPOSITION",
                new byte[]{0});
    }

    private static byte[] sign(KeyPair key, byte[] message, boolean prehash) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        if (prehash) {
            // Ed25519ph（RFC 8032 §5.1 の prehash。KMS の ED25519_PH_SHA_512 と同じ方式）
            signer.setParameter(new EdDSAParameterSpec(true));
        }
        signer.update(message);
        return signer.sign();
    }
}
