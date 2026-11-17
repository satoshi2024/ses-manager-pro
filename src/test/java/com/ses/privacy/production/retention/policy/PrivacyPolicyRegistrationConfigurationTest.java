package com.ses.privacy.production.retention.policy;

import com.ses.config.OidcSecurityProperties;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.HoldReleaseTrustRegistry;
import com.ses.privacy.foundation.PolicyFailureReason;
import com.ses.privacy.foundation.PolicyRegistrationRejectedException;
import com.ses.privacy.foundation.PolicyRegistrationVerifier;
import com.ses.privacy.foundation.ProductionPolicyTrustRegistry;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.foundation.RevocationStatus;
import com.ses.privacy.foundation.RevocationStatusProvider;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.PrivacyDecisionWindowMonitor;
import com.ses.privacy.production.PrivacyFeatureProperties;
import com.ses.privacy.production.PrivacyProductionConfiguration;
import com.ses.privacy.production.retention.hold.ConfiguredHoldReleaseTrustRegistry;
import com.ses.privacy.production.retention.trust.PrivacyTrustSource;
import com.ses.service.AuditLogService;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.LegalEntityContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * NF07-REG・説明照会の Spring 登録が、trust（{@link PrivacyTrustSource}）を構築時に1回ではなく検証と開放の判定のたびに読むこと
 * （RB-NF07-REG review P2-1）。可変の fake の trust から anchor を消すと、再起動せずに次の判定で gate が閉じ、次の検証で
 * {@code UNKNOWN_TRUST_ANCHOR} になる。鍵は test-only（{@link PolicyEnvelopeTestSigner}）。DB には触れない。
 */
class PrivacyPolicyRegistrationConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final String TENANT = "nf07-policy-config-test";

    private Clock clock;
    private MockEnvironment prod;
    private PolicyEnvelopeTestSigner signer;
    private MutableTrust trust;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneId.of("Asia/Tokyo"));
        prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        signer = new PolicyEnvelopeTestSigner(TENANT, NOW);
        trust = new MutableTrust(signer);
    }

    @Test
    void 登録のgateとverifierはtrustを判定のたびに読みanchorを消せば再起動せずに閉じる() {
        PrivacyPolicyRegistrationService service = new PrivacyPolicyRegistrationConfiguration()
                .privacyPolicyRegistrationService(mock(PrivacyAccessGuard.class), provider(OidcSecurityProperties.class,
                                null), approval(), mock(RetentionPolicyMapper.class), mock(PiiDataElementMapper.class),
                        mock(AuditLogService.class), mock(PlatformTransactionManager.class), provider(Clock.class, clock),
                        prod, trust);
        assertThat(trust.calls.get()).as("Bean の構築時には trust を読まない").isZero();

        assertThat(service.isOpen()).isTrue();
        assertThat(trust.calls.get()).isPositive();

        signer.remove(PolicyEnvelopeTestSigner.AUTHORITY);
        assertThat(service.isOpen()).as("anchor を消した次の判定で閉じる").isFalse();
        assertThat(service.readiness().missing()).containsExactly("trust");

        signer.register(PolicyEnvelopeTestSigner.AUTHORITY);
        assertThat(service.isOpen()).isTrue();
        trust.status.set(RevocationStatus.UNKNOWN);
        assertThat(service.isOpen()).as("失効 provider の状態も判定のたびに読む").isFalse();
    }

    @Test
    void 登録の検証はanchorを消した次の検証でUNKNOWN_TRUST_ANCHORになる() {
        PolicyRegistrationVerifier verifier = ProductionPrivacyComponents.policyRegistrationVerifier(prod,
                PrivacyTrustSource.policyAnchorsOf(trust), PrivacyTrustSource.revocationsOf(trust), clock);
        PolicyEnvelopeTestSigner.Signed signed = signer.sign("nf08-ai-prompt-log", "r1");

        assertThat(verifier.verify(TENANT, signed.canonical(), signed.signature()).digest()).isEqualTo(signed.digest());

        signer.remove(PolicyEnvelopeTestSigner.AUTHORITY);
        assertThatThrownBy(() -> verifier.verify(TENANT, signed.canonical(), signed.signature()))
                .isInstanceOfSatisfying(PolicyRegistrationRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(PolicyFailureReason.UNKNOWN_TRUST_ANCHOR));
        assertThat(verifier.trustReady()).isFalse();

        // trust source が例外を投げても閉じる（fail-closed）
        trust.failing = true;
        assertThat(verifier.trustReady()).isFalse();
        assertThatThrownBy(() -> verifier.verify(TENANT, signed.canonical(), signed.signature()))
                .isInstanceOf(PolicyRegistrationRejectedException.class);
    }

    @Test
    void 説明照会のBeanも構築時にはtrustを読まない() {
        PrivacyFeatureProperties features = new PrivacyFeatureProperties();
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId(TENANT);

        new PrivacyProductionConfiguration().productionPolicyReadonlyQueryPort(prod, features,
                mock(LegalEntityContextService.class), mock(SysUserMapper.class),
                provider(AuthorizationService.class, null), oidc, mock(PiiDataElementMapper.class),
                mock(RetentionPolicyMapper.class), provider(PrivacyDecisionWindowMonitor.class, null), trust, clock);

        assertThat(trust.calls.get()).isZero();
    }

    private PrivacyApprovalProperties approval() {
        PrivacyApprovalProperties approval = new PrivacyApprovalProperties();
        decision(approval.getD01(), "D01-NF07-TEST-001");
        decision(approval.getD02(), "D02-NF07-TEST-001");
        decision(approval.getD03(), "D03-NF07-TEST-001");
        return approval;
    }

    private static void decision(PrivacyApprovalProperties.DecisionReference reference, String decisionId) {
        reference.setDecisionId(decisionId);
        reference.setSignedOn("2026-11-01");
        reference.setExpiresOn("2027-06-01");
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(Class<T> type, T value) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (value != null) {
            factory.registerSingleton(type.getSimpleName(), value);
        }
        return factory.getBeanProvider(type);
    }

    /** 呼出しのたびに signer の現在の anchor を返す可変の trust（D-03 の方式の adapter の代わり）。 */
    private static final class MutableTrust implements PrivacyTrustSource {
        private final PolicyEnvelopeTestSigner signer;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<RevocationStatus> status = new AtomicReference<>(RevocationStatus.ACTIVE);
        private volatile boolean failing;

        private MutableTrust(PolicyEnvelopeTestSigner signer) {
            this.signer = signer;
        }

        @Override
        public ProductionPolicyTrustRegistry policyAnchors() {
            calls.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("test: trust source の障害");
            }
            return new ConfiguredPolicyTrustRegistry(signer.anchors());
        }

        @Override
        public HoldReleaseTrustRegistry holdReleaseAnchors() {
            calls.incrementAndGet();
            return ConfiguredHoldReleaseTrustRegistry.empty();
        }

        @Override
        public RevocationStatusProvider revocations() {
            calls.incrementAndGet();
            return reference -> status.get();
        }
    }
}
