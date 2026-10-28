package com.ses.privacy.foundation;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外部依存・可変状態・副作用を持たない PurePolicyVerificationKernel の単体テスト。
 */
class PurePolicyVerificationKernelTest {
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final String TENANT = "tenant-test-1";
    private static final String SCOPE = "data-element:contact";
    private static final String ACTION = "RESTRICT";
    private static final String PROVIDER = "test-store";

    @Test
    void verifySchemaVersion_succeedsForSupportedVersionAndRejectsOthers() {
        ApprovalEnvelopeMetadata valid = metadataWithSchema("1");
        assertDoesNotThrow(() -> PurePolicyVerificationKernel.verifySchemaVersion(valid));

        ApprovalEnvelopeMetadata invalid = metadataWithSchema("99");
        PolicySecurityException pse = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifySchemaVersion(invalid));
        assertEquals(PolicyFailureReason.UNSUPPORTED_SCHEMA_VERSION, pse.reason());
        assertEquals("未対応の承認envelope schema versionです", pse.getMessage());
    }

    @Test
    void verifyAnchorIdentity_succeedsWhenMatchingAndRejectsMismatch() throws Exception {
        KeyPair kp = generateKeyPair();
        TrustedAuthority anchor = createAnchor(kp, "authority-1", "key-1");
        ApprovalEnvelopeMetadata matching = metadata("authority-1", "key-1");

        assertDoesNotThrow(() -> PurePolicyVerificationKernel.verifyAnchorIdentity(matching, anchor));

        ApprovalEnvelopeMetadata mismatchedAuthority = metadata("authority-2", "key-1");
        PolicySecurityException pse1 = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyAnchorIdentity(mismatchedAuthority, anchor));
        assertEquals(PolicyFailureReason.UNKNOWN_TRUST_ANCHOR, pse1.reason());
        assertEquals("authorityまたはkeyが一致しません", pse1.getMessage());

        ApprovalEnvelopeMetadata mismatchedKey = metadata("authority-1", "key-2");
        PolicySecurityException pse2 = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyAnchorIdentity(mismatchedKey, anchor));
        assertEquals(PolicyFailureReason.UNKNOWN_TRUST_ANCHOR, pse2.reason());
    }

    @Test
    void verifyAuthorityScope_checksAllAttributesAndRejectsScopeMismatch() throws Exception {
        KeyPair kp = generateKeyPair();
        TrustedAuthority anchor = createAnchor(kp, "authority-1", "key-1");
        ApprovalEnvelopeMetadata meta = metadata("authority-1", "key-1");

        // 正常系（POLICY & CASE）
        assertDoesNotThrow(() -> PurePolicyVerificationKernel.verifyAuthorityScope(
                "POLICY", "contact", meta, ACTION, PROVIDER, anchor));
        assertDoesNotThrow(() -> PurePolicyVerificationKernel.verifyAuthorityScope(
                "CASE", null, meta, ACTION, PROVIDER, anchor));

        // テナント不一致
        ApprovalEnvelopeMetadata wrongTenant = new ApprovalEnvelopeMetadata("1", "authority-1", "key-1",
                "other-tenant", SCOPE, SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE, "r1", "req-1", "app-2",
                "rev-1", NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(600));
        PolicySecurityException pseTenant = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyAuthorityScope("POLICY", "contact", wrongTenant, ACTION, PROVIDER, anchor));
        assertEquals(PolicyFailureReason.AUTHORITY_SCOPE_MISMATCH, pseTenant.reason());

        // アクション不一致
        PolicySecurityException pseAction = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyAuthorityScope("POLICY", "contact", meta, "DELETE", PROVIDER, anchor));
        assertEquals(PolicyFailureReason.AUTHORITY_SCOPE_MISMATCH, pseAction.reason());

        // データ要素不一致
        PolicySecurityException pseData = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyAuthorityScope("POLICY", "out-of-scope-elem", meta, ACTION, PROVIDER, anchor));
        assertEquals(PolicyFailureReason.AUTHORITY_SCOPE_MISMATCH, pseData.reason());
    }

    @Test
    void verifyTimeWindow_enforcesHalfOpenIntervalsAndDeadlines() throws Exception {
        KeyPair kp = generateKeyPair();
        Instant validFrom = NOW.minusSeconds(3600);
        Instant validUntil = NOW.plusSeconds(3600);
        TrustedAuthority anchor = new TrustedAuthority("authority-1", "key-1", kp.getPublic(),
                Set.of(TENANT), Set.of(SCOPE), Set.of("contact"), Set.of("POLICY", "CASE"),
                Set.of(SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE), Set.of(ACTION), Set.of(PROVIDER),
                validFrom, validUntil);

        ApprovalEnvelopeMetadata normal = new ApprovalEnvelopeMetadata("1", "authority-1", "key-1",
                TENANT, SCOPE, SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE, "r1", "req-1", "app-2", "rev-1",
                NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(300));

        assertDoesNotThrow(() -> PurePolicyVerificationKernel.verifyTimeWindow(NOW, anchor, normal));

        // 境界値テスト: now == validFrom (有効)
        assertDoesNotThrow(() -> PurePolicyVerificationKernel.verifyTimeWindow(validFrom, anchor, normal));

        // 境界値テスト: now == validUntil (失効: AUTHORITY_VALIDITY_EXPIRED)
        PolicySecurityException pseUntil = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyTimeWindow(validUntil, anchor, normal));
        assertEquals(PolicyFailureReason.AUTHORITY_VALIDITY_EXPIRED, pseUntil.reason());

        // 未来の発行日時
        ApprovalEnvelopeMetadata futureIssued = new ApprovalEnvelopeMetadata("1", "authority-1", "key-1",
                TENANT, SCOPE, SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE, "r1", "req-1", "app-2", "rev-1",
                NOW.plusSeconds(10), NOW.minusSeconds(60), NOW.plusSeconds(300));
        PolicySecurityException pseIssued = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyTimeWindow(NOW, anchor, futureIssued));
        assertEquals(PolicyFailureReason.NOT_YET_ISSUED, pseIssued.reason());

        // 未発効
        ApprovalEnvelopeMetadata futureEffective = new ApprovalEnvelopeMetadata("1", "authority-1", "key-1",
                TENANT, SCOPE, SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE, "r1", "req-1", "app-2", "rev-1",
                NOW.minusSeconds(60), NOW.plusSeconds(10), NOW.plusSeconds(300));
        PolicySecurityException pseEffective = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyTimeWindow(NOW, anchor, futureEffective));
        assertEquals(PolicyFailureReason.NOT_YET_EFFECTIVE, pseEffective.reason());

        // 期限切れ (now == expiresAt: EXPIRED_AT_OR_AFTER_DEADLINE)
        Instant expires = NOW.plusSeconds(300);
        ApprovalEnvelopeMetadata expired = new ApprovalEnvelopeMetadata("1", "authority-1", "key-1",
                TENANT, SCOPE, SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE, "r1", "req-1", "app-2", "rev-1",
                NOW.minusSeconds(60), NOW.minusSeconds(60), expires);
        PolicySecurityException pseExpired = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyTimeWindow(expires, anchor, expired));
        assertEquals(PolicyFailureReason.EXPIRED_AT_OR_AFTER_DEADLINE, pseExpired.reason());
    }

    @Test
    void verifyRevocationStatus_enforcesActiveWhitelistAndRevocationPriorities() {
        // ACTIVE, ACTIVE は成功
        assertDoesNotThrow(() -> PurePolicyVerificationKernel.verifyRevocationStatus(
                RevocationStatus.ACTIVE, RevocationStatus.ACTIVE));

        // 鍵失効優先判定
        PolicySecurityException pseKey = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyRevocationStatus(RevocationStatus.REVOKED, RevocationStatus.ACTIVE));
        assertEquals(PolicyFailureReason.KEY_REVOKED, pseKey.reason());

        // エンベロープ失効判定
        PolicySecurityException pseEnv = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyRevocationStatus(RevocationStatus.ACTIVE, RevocationStatus.REVOKED));
        assertEquals(PolicyFailureReason.ENVELOPE_REVOKED, pseEnv.reason());

        // 鍵・エンベロープ双方が失効時は鍵失効が優先
        PolicySecurityException pseBoth = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyRevocationStatus(RevocationStatus.REVOKED, RevocationStatus.REVOKED));
        assertEquals(PolicyFailureReason.KEY_REVOKED, pseBoth.reason());

        // null または UNKNOWN 等の非 ACTIVE はすべて REVOCATION_STATUS_UNKNOWN
        PolicySecurityException pseNullKey = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyRevocationStatus(null, RevocationStatus.ACTIVE));
        assertEquals(PolicyFailureReason.REVOCATION_STATUS_UNKNOWN, pseNullKey.reason());

        PolicySecurityException pseNullEnv = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyRevocationStatus(RevocationStatus.ACTIVE, null));
        assertEquals(PolicyFailureReason.REVOCATION_STATUS_UNKNOWN, pseNullEnv.reason());
    }

    @Test
    void verifySignature_succeedsForValidSignatureAndRejectsTamperedPayload() throws Exception {
        KeyPair kp = generateKeyPair();
        TrustedAuthority anchor = createAnchor(kp, "authority-1", "key-1");
        byte[] payload = "test-payload".getBytes();

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(kp.getPrivate());
        signer.update(payload);
        byte[] sig = signer.sign();

        // 正常署名検証
        assertDoesNotThrow(() -> PurePolicyVerificationKernel.verifySignature(anchor, payload, sig));

        // 改竄ペイロード
        byte[] tamperedPayload = "tampered-payload".getBytes();
        PolicySecurityException psePayload = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifySignature(anchor, tamperedPayload, sig));
        assertEquals(PolicyFailureReason.SIGNATURE_INVALID, psePayload.reason());

        // 改竄署名
        byte[] tamperedSig = sig.clone();
        tamperedSig[0] ^= 0x5a;
        PolicySecurityException pseSig = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifySignature(anchor, payload, tamperedSig));
        assertEquals(PolicyFailureReason.SIGNATURE_INVALID, pseSig.reason());
    }

    @Test
    void verifyPolicySoD_and_verifyPolicyBindings_test() {
        ApprovalEnvelopeMetadata sameActor = new ApprovalEnvelopeMetadata("1", "authority-1", "key-1",
                TENANT, SCOPE, SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE, "r1", "same-user", "same-user",
                "rev-1", NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(300));

        PolicySecurityException pseSoD = assertThrows(PolicySecurityException.class,
                () -> PurePolicyVerificationKernel.verifyPolicySoD(sameActor));
        assertEquals(PolicyFailureReason.SOD_VIOLATION, pseSoD.reason());
        assertEquals("職務分離違反: 申請者と承認者は異なる必要があります", pseSoD.getMessage());

        ApprovalEnvelopeMetadata validMeta = metadata("authority-1", "key-1");
        assertDoesNotThrow(() -> PurePolicyVerificationKernel.verifyPolicySoD(validMeta));

        PolicyApprovalEnvelope policy = new PolicyApprovalEnvelope(validMeta, "policy-1", "1",
                "contact", ACTION, PROVIDER, "purpose", "trigger", "rule", "disposition", new byte[0]);

        assertDoesNotThrow(() -> PurePolicyVerificationKernel.verifyPolicyBindings(
                policy, TENANT, SCOPE, "contact", ACTION, PROVIDER));

        // 各種ドリフト
        assertThrows(PolicySecurityException.class, () -> PurePolicyVerificationKernel.verifyPolicyBindings(
                policy, "other-tenant", SCOPE, "contact", ACTION, PROVIDER));
        assertThrows(PolicySecurityException.class, () -> PurePolicyVerificationKernel.verifyPolicyBindings(
                policy, TENANT, "other-scope", "contact", ACTION, PROVIDER));
        assertThrows(PolicySecurityException.class, () -> PurePolicyVerificationKernel.verifyPolicyBindings(
                policy, TENANT, SCOPE, "other-data", ACTION, PROVIDER));
        assertThrows(PolicySecurityException.class, () -> PurePolicyVerificationKernel.verifyPolicyBindings(
                policy, TENANT, SCOPE, "contact", "OTHER_ACTION", PROVIDER));
        assertThrows(PolicySecurityException.class, () -> PurePolicyVerificationKernel.verifyPolicyBindings(
                policy, TENANT, SCOPE, "contact", ACTION, "other-provider"));
    }

    @Test
    void calculateEffectiveExpiry_returnsMinInstant() {
        Instant t1 = NOW.plusSeconds(100);
        Instant t2 = NOW.plusSeconds(200);
        assertEquals(t1, PurePolicyVerificationKernel.calculateEffectiveExpiry(t1, t2));
        assertEquals(t1, PurePolicyVerificationKernel.calculateEffectiveExpiry(t2, t1));
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        return generator.generateKeyPair();
    }

    private static TrustedAuthority createAnchor(KeyPair kp, String authorityId, String keyId) {
        return new TrustedAuthority(authorityId, keyId, kp.getPublic(),
                Set.of(TENANT), Set.of(SCOPE), Set.of("contact"), Set.of("POLICY", "CASE"),
                Set.of(SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE), Set.of(ACTION), Set.of(PROVIDER),
                NOW.minusSeconds(3600), NOW.plusSeconds(3600));
    }

    private static ApprovalEnvelopeMetadata metadata(String authorityId, String keyId) {
        return new ApprovalEnvelopeMetadata("1", authorityId, keyId, TENANT, SCOPE,
                SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE, "r1", "req-1", "app-2",
                "rev-1", NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(300));
    }

    private static ApprovalEnvelopeMetadata metadataWithSchema(String schemaVersion) {
        return new ApprovalEnvelopeMetadata(schemaVersion, "authority-1", "key-1", TENANT, SCOPE,
                SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE, "r1", "req-1", "app-2",
                "rev-1", NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(300));
    }
}
