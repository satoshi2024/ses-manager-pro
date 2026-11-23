package com.ses.privacy.production;

import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.PolicyEnforcementDecision;
import com.ses.privacy.foundation.PolicyEnforcementPort;
import com.ses.privacy.foundation.PolicyEnforcementReason;
import com.ses.privacy.foundation.PolicyEnforcementRequest;
import com.ses.privacy.production.retention.policy.UnavailablePolicyEnforcementPort;
import com.ses.privacy.production.retention.trust.PrivacyTrustSource;
import com.ses.privacy.production.retention.trust.UnconfiguredPrivacyTrustSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * NF07-NF08-LINK の内部判定 port の Spring 登録。prod 以外は常に拒否する port、prod は本番の判定（flag OFF では DB を読まない）。
 */
class PrivacyPolicyEnforcementConfigurationTest {

    private static final PolicyEnforcementRequest REQUEST = new PolicyEnforcementRequest("tenant-a",
            "NF08_AI_SUMMARY", "AI_EXTERNAL_PROVIDER_COPY", "EXTERNAL_DISPATCH", "gemini-adapter", "NF07-POL-2026-0007",
            Instant.parse("2026-11-17T15:00:00Z"));

    @Test
    void prod以外のprofileでは常に拒否するportだけを登録する() {
        for (String profile : java.util.List.of("test", "dev")) {
            new ApplicationContextRunner()
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                    .withUserConfiguration(PrivacyPolicyEnforcementConfiguration.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        PolicyEnforcementPort port = context.getBean(PolicyEnforcementPort.class);
                        assertThat(port).isInstanceOf(UnavailablePolicyEnforcementPort.class);
                        PolicyEnforcementDecision decision = port.check(REQUEST);
                        assertThat(decision.permitted()).isFalse();
                        assertThat(decision.reason()).isEqualTo(PolicyEnforcementReason.ENFORCEMENT_UNAVAILABLE);
                    });
        }
    }

    @Test
    void prodでは本番の判定を登録し公開スイッチOFFならDBを読まずに拒否する() {
        PiiDataElementMapper dataElements = mock(PiiDataElementMapper.class);
        RetentionPolicyMapper policies = mock(RetentionPolicyMapper.class);
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withBean(PrivacyFeatureProperties.class, PrivacyFeatureProperties::new)
                .withBean(PiiDataElementMapper.class, () -> dataElements)
                .withBean(RetentionPolicyMapper.class, () -> policies)
                .withBean(PrivacyTrustSource.class, UnconfiguredPrivacyTrustSource::new)
                .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-11-17T01:00:00Z"), ZoneOffset.UTC))
                .withUserConfiguration(PrivacyPolicyEnforcementConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PolicyEnforcementPort port = context.getBean(PolicyEnforcementPort.class);
                    assertThat(port).isNotInstanceOf(UnavailablePolicyEnforcementPort.class);
                    PolicyEnforcementDecision decision = port.check(REQUEST);
                    assertThat(decision.permitted()).isFalse();
                    assertThat(decision.reason()).isEqualTo(PolicyEnforcementReason.RETENTION_DISABLED);
                    verifyNoInteractions(dataElements, policies);
                });
    }

    /**
     * RB-NF07-REG review P2-1 の契約: anchor と失効状態は構築時の値ではなく、送信の判定のたびに trust source から取る。
     * anchor の削除・失効は再起動（Bean の作り直し）をせずに、次の送信判定から拒否になる。鍵は test-only（memory 上で生成）。
     */
    @Test
    void prodの送信判定はtrust_sourceを判定のたびに読みanchorの削除と失効を再起動せずに反映する() throws Exception {
        // catalog・source の LambdaQueryWrapper の列指定は MyBatis-Plus の表情報を要する（Spring の test の実行順に依存させない）
        for (Class<?> entity : java.util.List.of(com.ses.entity.privacy.PiiDataElement.class,
                com.ses.entity.privacy.RetentionPolicy.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), entity);
        }
        Instant now = Instant.parse("2026-11-17T01:00:00Z");
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 17);
        Signed signed = sign(now);
        MutableTrustSource trust = new MutableTrustSource();
        trust.anchors.put(ANCHOR_KEY, signed.anchor());
        trust.statuses.put(ANCHOR_KEY, com.ses.privacy.foundation.RevocationStatus.ACTIVE);
        trust.statuses.put(REVOCATION_REF, com.ses.privacy.foundation.RevocationStatus.ACTIVE);
        PiiDataElementMapper dataElements = mock(PiiDataElementMapper.class);
        RetentionPolicyMapper policies = mock(RetentionPolicyMapper.class);
        org.mockito.Mockito.when(dataElements.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        org.mockito.Mockito.when(policies.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(signed.row()));
        // 保持・保全の実効値（開放状態と決定ID束縛の有効期間を含む。FE-C02）が開
        com.ses.config.feature.FeatureSwitches switches = mock(com.ses.config.feature.FeatureSwitches.class);
        org.mockito.Mockito.when(switches.isEnabled(com.ses.config.feature.Feature.NF07_RETENTION)).thenReturn(true);
        com.ses.service.ai.AiExternalSendApproval.Snapshot approval = com.ses.service.ai.AiExternalSendApproval.evaluate(
                com.ses.service.ai.AiExternalSendApprovalFixtures.approvedConfig(today), today).snapshot();

        org.springframework.mock.env.MockEnvironment prod = new org.springframework.mock.env.MockEnvironment();
        prod.setActiveProfiles("prod");
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.ses.config.feature.FeatureSwitches> switchesProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(switchesProvider.getIfAvailable()).thenReturn(switches);

        // Spring 登録と同じ bean method で1回だけ作り、以後は作り直さない（再起動しない）
        PolicyEnforcementPort port = new PrivacyPolicyEnforcementConfiguration().productionPolicyEnforcementPort(prod,
                switchesProvider, dataElements, policies, trust, Clock.fixed(now, ZoneOffset.UTC));
        assertThat(trust.anchorReads.get() + trust.revocationReads.get()).as("構築時に trust source を読まない").isZero();
        com.ses.service.ai.AiExternalSendPrivacyPolicyCheck check =
                com.ses.service.ai.AiExternalSendApprovalFixtures.privacyPolicyCheck(port, null);
        java.util.function.Supplier<PolicyEnforcementReason> send = () -> check.evaluate("tenant-a",
                approval, com.ses.service.ai.AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY);

        assertThat(send.get()).isEqualTo(PolicyEnforcementReason.PERMITTED);
        assertThat(trust.anchorReads.get()).isEqualTo(1);

        // anchor を消す → 次の送信判定で拒否（port は同じまま）
        trust.anchors.clear();
        assertThat(send.get()).isEqualTo(PolicyEnforcementReason.POLICY_DRIFT);

        // anchor を戻すと許可、envelope の失効は次の判定から拒否、失効状態の不明も拒否
        trust.anchors.put(ANCHOR_KEY, signed.anchor());
        assertThat(send.get()).isEqualTo(PolicyEnforcementReason.PERMITTED);
        trust.statuses.put(REVOCATION_REF, com.ses.privacy.foundation.RevocationStatus.REVOKED);
        assertThat(send.get()).isEqualTo(PolicyEnforcementReason.POLICY_REVOKED);
        trust.statuses.remove(REVOCATION_REF);
        assertThat(send.get()).isEqualTo(PolicyEnforcementReason.POLICY_UNKNOWN);
        assertThat(trust.anchorReads.get()).as("判定のたびに読む").isEqualTo(5);
    }

    private static final String ANCHOR_KEY = "policy-issuer-test:k1";
    private static final String REVOCATION_REF = "envelope-revocation-test";

    /** 判定のたびに現在の anchor・失効状態を返す trust source（D-03 の adapter の代わりの test-only の fake）。 */
    private static final class MutableTrustSource implements PrivacyTrustSource {
        private final java.util.Map<String, com.ses.privacy.foundation.TrustedAuthority> anchors =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<String, com.ses.privacy.foundation.RevocationStatus> statuses =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicInteger anchorReads = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger revocationReads =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public com.ses.privacy.foundation.ProductionPolicyTrustRegistry policyAnchors() {
            anchorReads.incrementAndGet();
            java.util.Map<String, com.ses.privacy.foundation.TrustedAuthority> snapshot = java.util.Map.copyOf(anchors);
            return () -> snapshot;
        }

        @Override
        public com.ses.privacy.foundation.HoldReleaseTrustRegistry holdReleaseAnchors() {
            return java.util.Map::of;
        }

        @Override
        public com.ses.privacy.foundation.RevocationStatusProvider revocations() {
            revocationReads.incrementAndGet();
            java.util.Map<String, com.ses.privacy.foundation.RevocationStatus> snapshot = java.util.Map.copyOf(statuses);
            return reference -> snapshot.getOrDefault(reference, com.ses.privacy.foundation.RevocationStatus.UNKNOWN);
        }
    }

    /** test-only の鍵で署名した NF08 の外部送信 policy（合成値）と、それを保存した行。 */
    private record Signed(com.ses.privacy.foundation.TrustedAuthority anchor, com.ses.entity.privacy.RetentionPolicy row) {
    }

    private static Signed sign(Instant now) throws Exception {
        java.security.KeyPair keyPair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        // NF08 の承認の署名日（fixture: 2026-11-16 JST）より前に発行した envelope
        Instant issuedAt = now.minus(java.time.Duration.ofDays(2));
        com.ses.privacy.foundation.ApprovalEnvelopeMetadata metadata = new com.ses.privacy.foundation.ApprovalEnvelopeMetadata(
                "1", "policy-issuer-test", "k1", "tenant-a", "NF08_AI_SUMMARY", "PRODUCTION", "r1", "requester-test",
                "approver-test", REVOCATION_REF, issuedAt, issuedAt, now.plus(java.time.Duration.ofDays(30)));
        com.ses.privacy.foundation.PolicyApprovalEnvelope unsigned = new com.ses.privacy.foundation.PolicyApprovalEnvelope(
                metadata, "NF07-POL-2026-0007", "v1", "AI_EXTERNAL_PROVIDER_COPY", "EXTERNAL_DISPATCH", "gemini-adapter",
                "TEST_PURPOSE", "TEST_TRIGGER", "TEST_ONLY_RULE", "TEST_ONLY_DISPOSITION", new byte[]{0});
        byte[] canonical = com.ses.privacy.foundation.ApprovalEnvelopeCanonicalizer.canonicalBytes(unsigned);
        java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        // 本番は digest 声明への署名だけを受け付ける（NF07-TRUST）
        signer.update(com.ses.privacy.foundation.PolicyEnvelopeSigningStatement.of(canonical));
        com.ses.privacy.foundation.TrustedAuthority anchor = new com.ses.privacy.foundation.TrustedAuthority(
                "policy-issuer-test", "k1", keyPair.getPublic(), java.util.Set.of("tenant-a"),
                java.util.Set.of("NF08_AI_SUMMARY"), java.util.Set.of("AI_EXTERNAL_PROVIDER_COPY"), java.util.Set.of("POLICY"),
                java.util.Set.of("PRODUCTION"), java.util.Set.of("EXTERNAL_DISPATCH"), java.util.Set.of("gemini-adapter"),
                now.minus(java.time.Duration.ofDays(7)), now.plus(java.time.Duration.ofDays(365)));
        com.ses.entity.privacy.RetentionPolicy row = new com.ses.entity.privacy.RetentionPolicy();
        row.setId(1L);
        row.setPolicyId("NF07-POL-2026-0007");
        row.setPolicyRevision("r1");
        row.setEnvelopeCanonical(canonical);
        row.setEnvelopeSignature(signer.sign());
        row.setEnvelopeDigest(com.ses.privacy.foundation.ApprovalEnvelopeCanonicalizer.digest(canonical));
        return new Signed(anchor, row);
    }
}
