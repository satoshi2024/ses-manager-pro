package com.ses.privacy.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 開放の gate（{@link PrivacyTrustReadiness}）が、verifier と同じ clock で有効期間外の anchor を除き、授権 tenant で絞ること
 * （RB-NF07-REG delta review P3-1）。唯一の anchor・組が期限切れ・未発効・tenant 外のとき、REG（{@link PolicyRegistrationVerifier}）・
 * REL（{@link HoldReleaseVerifier}）のどちらの gate も開かない。区間は verifier と同じ半開区間（{@code validFrom <= now < validUntil}）。
 * 鍵は test-only（実行のたびに memory 上で生成）。
 */
class PrivacyTrustReadinessWindowTest {

    private static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");
    private static final String TENANT = "default";

    @Test
    void policyの唯一のanchorが期限切れ_未発効_tenant外ならREGのgateは開かない() throws Exception {
        PublicKey key = ed25519();
        assertThat(policyReady(policy(key, NOW.minusSeconds(3600), NOW.plusSeconds(3600), TENANT), NOW)).isTrue();
        assertThat(policyReady(policy(key, NOW.minusSeconds(3600), NOW, TENANT), NOW)).as("validUntil の当時刻")
                .isFalse();
        assertThat(policyReady(policy(key, NOW, NOW.plusSeconds(3600), TENANT), NOW)).as("validFrom の当時刻")
                .isTrue();
        assertThat(policyReady(policy(key, NOW.plusSeconds(1), NOW.plusSeconds(3600), TENANT), NOW)).as("未発効")
                .isFalse();
        assertThat(policyReady(policy(key, NOW.minusSeconds(3600), NOW.plusSeconds(3600), "other-tenant"), NOW))
                .as("tenant 外").isFalse();
        // 期限切れの anchor と有効な anchor があれば、有効な方で開く（rotation の重複期間の後）
        TrustedAuthority expired = policy(key, NOW.minusSeconds(7200), NOW.minusSeconds(1), TENANT);
        TrustedAuthority current = new TrustedAuthority("policy-issuer", "k2", ed25519(), Set.of(TENANT),
                Set.of("NF08_AI_SUMMARY"), Set.of("AI_PROMPT_LOG"), Set.of("POLICY"), Set.of("PRODUCTION"),
                Set.of("EXTERNAL_DISPATCH"), Set.of("gemini-adapter"), NOW.minusSeconds(60), NOW.plusSeconds(3600));
        assertThat(ProductionPrivacyComponents.policyRegistrationVerifier(prod(),
                () -> Map.of("policy-issuer:k1", expired, "policy-issuer:k2", current), ref -> RevocationStatus.ACTIVE,
                Clock.fixed(NOW, ZoneOffset.UTC)).trustReady(TENANT)).isTrue();
        // 授権 tenant が無い（null・空）なら開かない
        assertThat(ProductionPrivacyComponents.policyRegistrationVerifier(prod(),
                () -> Map.of("policy-issuer:k2", current), ref -> RevocationStatus.ACTIVE,
                Clock.fixed(NOW, ZoneOffset.UTC)).trustReady(" ")).isFalse();
    }

    @Test
    void 解除の唯一の組の片方が期限切れ_未発効_tenant外ならRELのgateは開かない() throws Exception {
        HoldReleaseTrustAnchor issuer = hold("issuer-a", "sys_user:20", NOW.minusSeconds(3600), NOW.plusSeconds(3600),
                TENANT);
        HoldReleaseTrustAnchor approver = hold("approver-b", "sys_user:30", NOW.minusSeconds(3600),
                NOW.plusSeconds(3600), TENANT);
        assertThat(releaseReady(issuer, approver, NOW)).isTrue();
        assertThat(releaseReady(issuer, hold("approver-b", "sys_user:30", NOW.minusSeconds(3600), NOW, TENANT), NOW))
                .as("承認者が validUntil の当時刻").isFalse();
        assertThat(releaseReady(hold("issuer-a", "sys_user:20", NOW.plusSeconds(1), NOW.plusSeconds(3600), TENANT),
                approver, NOW)).as("発行者が未発効").isFalse();
        assertThat(releaseReady(issuer, hold("approver-b", "sys_user:30", NOW.minusSeconds(3600),
                NOW.plusSeconds(3600), "other-tenant"), NOW)).as("承認者が tenant 外").isFalse();
        // gate の時刻は verifier の clock（期限の後の clock では閉じる）
        assertThat(releaseReady(issuer, approver, NOW.plusSeconds(3600))).isFalse();
    }

    private static boolean policyReady(TrustedAuthority anchor, Instant now) {
        return ProductionPrivacyComponents.policyRegistrationVerifier(prod(),
                () -> Map.of(anchor.authorityId() + ":" + anchor.keyId(), anchor), ref -> RevocationStatus.ACTIVE,
                Clock.fixed(now, ZoneOffset.UTC)).trustReady(TENANT);
    }

    private static boolean releaseReady(HoldReleaseTrustAnchor issuer, HoldReleaseTrustAnchor approver, Instant now) {
        return ProductionPrivacyComponents.holdReleaseVerifier(prod(),
                () -> Map.of(issuer.anchorId(), issuer, approver.anchorId(), approver), ref -> RevocationStatus.ACTIVE,
                Clock.fixed(now, ZoneOffset.UTC)).trustReady(TENANT);
    }

    private static TrustedAuthority policy(PublicKey key, Instant validFrom, Instant validUntil, String tenant) {
        return new TrustedAuthority("policy-issuer", "k1", key, Set.of(tenant), Set.of("NF08_AI_SUMMARY"),
                Set.of("AI_PROMPT_LOG"), Set.of("POLICY"), Set.of("PRODUCTION"), Set.of("EXTERNAL_DISPATCH"),
                Set.of("gemini-adapter"), validFrom, validUntil);
    }

    private static HoldReleaseTrustAnchor hold(String authorityId, String principal, Instant validFrom,
                                               Instant validUntil, String tenant) throws Exception {
        return new HoldReleaseTrustAnchor(authorityId, "k1", ed25519(), principal, Set.of(tenant),
                Set.of(HoldReleaseAuthorityClaim.Role.ISSUER, HoldReleaseAuthorityClaim.Role.APPROVER),
                Set.of("SUBJECT", "DATA_ELEMENT"), validFrom, validUntil);
    }

    private static PublicKey ed25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
    }

    private static MockEnvironment prod() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }
}
