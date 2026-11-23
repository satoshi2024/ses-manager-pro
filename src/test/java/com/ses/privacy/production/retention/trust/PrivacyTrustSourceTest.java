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

    /**
     * hold 解除の Spring 登録は trust を構築時に1回ではなく判定・検証のたびに読む（RB-NF07-REG review P2-1）。可変の fake から
     * anchor を消すと、再起動せずに次の判定で入口が閉じ、次の検証で {@code TRUST_ANCHOR_UNKNOWN} になる。
     */
    @Test
    void hold解除のgateとverifierはtrustを判定のたびに読みanchorを消せば再起動せずに閉じる() {
        com.ses.privacy.foundation.HoldReleaseTestAuthorities authorities =
                new com.ses.privacy.foundation.HoldReleaseTestAuthorities();
        authorities.register("issuer-a", "sys_user:20", "default");
        authorities.register("approver-b", "sys_user:30", "default");
        Map<String, HoldReleaseTrustAnchor> full = authorities.registry().anchors();
        java.util.concurrent.atomic.AtomicReference<Map<String, HoldReleaseTrustAnchor>> current =
                new java.util.concurrent.atomic.AtomicReference<>(full);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        PrivacyTrustSource trust = new PrivacyTrustSource() {
            @Override
            public com.ses.privacy.foundation.ProductionPolicyTrustRegistry policyAnchors() {
                return ConfiguredPolicyTrustRegistry.empty();
            }

            @Override
            public com.ses.privacy.foundation.HoldReleaseTrustRegistry holdReleaseAnchors() {
                calls.incrementAndGet();
                return new ConfiguredHoldReleaseTrustRegistry(current.get());
            }

            @Override
            public com.ses.privacy.foundation.RevocationStatusProvider revocations() {
                return reference -> RevocationStatus.ACTIVE;
            }
        };
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        Clock clock = Clock.fixed(NOW, java.time.ZoneId.of("Asia/Tokyo"));
        com.ses.privacy.production.PrivacyApprovalProperties approval =
                new com.ses.privacy.production.PrivacyApprovalProperties();
        for (com.ses.privacy.production.PrivacyApprovalProperties.DecisionReference reference
                : java.util.List.of(approval.getD03(), approval.getD05())) {
            reference.setDecisionId(reference == approval.getD03() ? "D03-NF07-TEST-001" : "D05-NF07-TEST-001");
            reference.setSignedOn("2026-11-01");
            reference.setExpiresOn("2027-06-01");
        }
        com.ses.privacy.production.retention.hold.PrivacyHoldDecisions decisions =
                new com.ses.privacy.production.retention.hold.PrivacyHoldDecisions(approval, null, null, clock);
        com.ses.privacy.production.retention.hold.PrivacyHoldConfiguration configuration =
                new com.ses.privacy.production.retention.hold.PrivacyHoldConfiguration();
        com.ses.privacy.foundation.HoldReleaseTrustRegistry registry =
                configuration.privacyHoldReleaseTrustRegistry(trust);
        org.springframework.beans.factory.support.DefaultListableBeanFactory beans =
                new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("clock", clock);
        com.ses.privacy.production.retention.hold.PrivacyHoldReleaseService service =
                configuration.privacyHoldReleaseService(
                        org.mockito.Mockito.mock(com.ses.privacy.production.PrivacyAccessGuard.class),
                        beans.getBeanProvider(com.ses.config.OidcSecurityProperties.class), decisions,
                        org.mockito.Mockito.mock(com.ses.privacy.production.retention.hold.PrivacyHoldJournal.class),
                        org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                        org.mockito.Mockito.mock(com.ses.privacy.production.retention.hold.HoldSubjectResolver.class),
                        org.mockito.Mockito.mock(com.ses.service.AuditLogService.class),
                        org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class),
                        beans.getBeanProvider(Clock.class), registry, prod, trust,
                        beans.getBeanProvider(PrivacyTrustStructureState.class),
                        beans.getBeanProvider(com.ses.privacy.production.PrivacySubgateSignOffs.class));
        assertThat(calls.get()).as("Bean の構築時には trust を読まない").isZero();

        assertThat(service.isOpen()).isTrue();
        current.set(Map.of("issuer-a:k1", full.get("issuer-a:k1")));
        assertThat(service.isOpen()).as("承認者の anchor を消した次の判定で閉じる").isFalse();

        current.set(full);
        assertThat(service.isOpen()).isTrue();
        // FE-C04: trust 文書の構造に不足があれば（起動時に flag と関係なく検査した結果）、REL は閉じる
        PrivacyTrustStructureState unsound = new PrivacyTrustStructureState();
        unsound.record(java.util.List.of("trust.kms.document.notJson"));
        beans.registerSingleton("privacyTrustStructureState", unsound);
        assertThat(service.isOpen()).as("構造の不足で REL だけが閉じる").isFalse();
        unsound.record(java.util.List.of());
        assertThat(service.isOpen()).as("構造が直れば再開する（副入口は自動で開く。D-4 の例外）").isTrue();
        // review rb-fe-c P2-1: REL の署名（D-03・D-05・NF07_TRUST_KMS・NF07_TRUST_STAGING）が取消し・失効なら REL は閉じる
        com.ses.privacy.production.PrivacySubgateSignOffs signOffs =
                org.mockito.Mockito.mock(com.ses.privacy.production.PrivacySubgateSignOffs.class);
        java.util.concurrent.atomic.AtomicBoolean signOffsValid = new java.util.concurrent.atomic.AtomicBoolean(false);
        org.mockito.Mockito.when(signOffs.valid(com.ses.privacy.production.PrivacySubgateSignOffs.RELEASE))
                .thenAnswer(invocation -> signOffsValid.get());
        beans.registerSingleton("privacySubgateSignOffs", signOffs);
        assertThat(service.isOpen()).as("REL の署名が有効でなければ閉じる").isFalse();
        signOffsValid.set(true);
        assertThat(service.isOpen()).as("新しい署名の二者確認の後は自動で再開する（2026-11-23 の利用者の決定）").isTrue();

        com.ses.privacy.foundation.HoldReleaseVerifier verifier = ProductionPrivacyComponents.holdReleaseVerifier(prod,
                registry, PrivacyTrustSource.revocationsOf(trust), clock);
        com.ses.privacy.foundation.HoldReleaseBinding binding = new com.ses.privacy.foundation.HoldReleaseBinding(
                "default", "ENGINEER:7", "ph-" + "a".repeat(32), 1, "d".repeat(64), "hr-" + "b".repeat(32), 1,
                "c".repeat(64), "sys_user:1", "sys_user:2");
        HoldReleaseAuthorityClaim issuer = authorities.sign("issuer-a", HoldReleaseAuthorityClaim.Role.ISSUER, binding,
                NOW.minusSeconds(60), NOW.plusSeconds(3600));
        HoldReleaseAuthorityClaim approver = authorities.sign("approver-b", HoldReleaseAuthorityClaim.Role.APPROVER,
                binding, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        assertThat(verifier.verify(binding, binding, "SUBJECT", decisions.releaseRules(), issuer, approver)).isNotNull();

        current.set(Map.of());
        assertThatThrownBy(() -> verifier.verify(binding, binding, "SUBJECT", decisions.releaseRules(), issuer, approver))
                .isInstanceOfSatisfying(com.ses.privacy.foundation.HoldReleaseRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(
                                com.ses.privacy.foundation.HoldReleaseRejection.TRUST_ANCHOR_UNKNOWN));
    }

    @Test
    void 第1期のtrustはanchorが0件で失効状態は常にUNKNOWN() {
        PrivacyTrustSource source = new PrivacyTrustConfiguration().privacyTrustSource(new KmsTrustProperties(), null);

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
                source.revocations(), clock).trustReady("default")).isFalse();
        assertThat(ProductionPrivacyComponents.holdReleaseVerifier(prod, source.holdReleaseAnchors(),
                source.revocations(), clock).trustReady("default")).isFalse();
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
