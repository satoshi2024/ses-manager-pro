package com.ses.privacy.production.retention.trust;

import com.ses.privacy.foundation.HoldReleaseAuthorityClaim;
import com.ses.privacy.foundation.HoldReleaseTrustAnchor;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.foundation.RevocationStatus;
import com.ses.privacy.foundation.TrustedAuthority;
import com.ses.privacy.production.retention.hold.ConfiguredHoldReleaseTrustRegistry;
import com.ses.privacy.production.retention.policy.ConfiguredPolicyTrustRegistry;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NF07 の trust の接続点（{@link PrivacyTrustSource}）。第1期の構成では policy の登録（NF07-REG）と hold 解除（NF07-REL）の
 * どちらの入口も開かないこと、D-03 の方式の adapter が使う registry が登録の時点で key と鍵の算法を検査すること
 * （REL review P3-8）を確かめる。鍵は test-only（実行のたびに memory 上で生成）。
 */
class PrivacyTrustSourceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");

    @Test
    void 第1期のtrustはanchorが0件で失効状態は常にUNKNOWN() {
        PrivacyTrustSource source = new PrivacyTrustConfiguration().privacyTrustSource();

        assertThat(source).isInstanceOf(UnconfiguredPrivacyTrustSource.class);
        assertThat(source.policyAnchors().anchors()).isEmpty();
        assertThat(source.holdReleaseAnchors().anchors()).isEmpty();
        assertThat(source.revocations().statusOf("policy-issuer:k1")).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(source.revocations().statusOf("envelope-revocation")).isEqualTo(RevocationStatus.UNKNOWN);
    }

    @Test
    void 第1期のtrustではpolicyの登録とhold解除のverifierがどちらも開かない() {
        PrivacyTrustSource source = new UnconfiguredPrivacyTrustSource();
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThat(ProductionPrivacyComponents.policyRegistrationVerifier(prod, source.policyAnchors(),
                source.revocations(), clock).trustReady()).isFalse();
        assertThat(ProductionPrivacyComponents.holdReleaseVerifier(prod, source.holdReleaseAnchors(),
                source.revocations(), clock).trustReady()).isFalse();
    }

    @Test
    void policyのregistryはkeyの食い違いとEd25519以外の鍵を登録の時点で拒否する() throws Exception {
        PublicKey ed25519 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        PublicKey rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic();

        ConfiguredPolicyTrustRegistry valid = new ConfiguredPolicyTrustRegistry(
                Map.of("policy-issuer:k1", authority("policy-issuer", ed25519)));
        assertThat(valid.anchors()).containsOnlyKeys("policy-issuer:k1");

        assertThatThrownBy(() -> new ConfiguredPolicyTrustRegistry(
                Map.of("policy-issuer:k1", authority("policy-issuer", rsa))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ed25519");
        assertThatThrownBy(() -> new ConfiguredPolicyTrustRegistry(
                Map.of("other:k1", authority("policy-issuer", ed25519))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("authorityId:keyId");
    }

    @Test
    void hold解除のregistryはkeyの食い違いを登録の時点で拒否する() throws Exception {
        PublicKey ed25519 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        HoldReleaseTrustAnchor anchor = new HoldReleaseTrustAnchor("issuer-a", "k1", ed25519, "sys_user:20",
                Set.of("default"), Set.of(HoldReleaseAuthorityClaim.Role.ISSUER), Set.of("SUBJECT"),
                NOW.minusSeconds(60), NOW.plusSeconds(3600));

        assertThat(new ConfiguredHoldReleaseTrustRegistry(Map.of("issuer-a:k1", anchor)).anchors())
                .containsOnlyKeys("issuer-a:k1");
        assertThatThrownBy(() -> new ConfiguredHoldReleaseTrustRegistry(Map.of("issuer-b:k1", anchor)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("authorityId:keyId");
    }

    private static TrustedAuthority authority(String authorityId, PublicKey key) {
        return new TrustedAuthority(authorityId, "k1", key, Set.of("default"), Set.of("NF08_AI_SUMMARY"),
                Set.of("AI_PROMPT_LOG"), Set.of("POLICY"), Set.of("PRODUCTION"), Set.of("EXTERNAL_DISPATCH"),
                Set.of("gemini-adapter"), NOW.minusSeconds(60), NOW.plusSeconds(3600));
    }
}
