package com.ses.privacy.production.retention.policy;

import com.ses.common.exception.BusinessException;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.privacy.PiiDataElement;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.ApprovalEnvelopeCanonicalizer;
import com.ses.privacy.foundation.ApprovalEnvelopeMetadata;
import com.ses.privacy.foundation.PolicyApprovalEnvelope;
import com.ses.privacy.foundation.PolicyEnforcementPort;
import com.ses.privacy.foundation.PolicyEnforcementReason;
import com.ses.privacy.foundation.ProductionPolicyTrustRegistry;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.foundation.RevocationStatus;
import com.ses.privacy.foundation.RevocationStatusProvider;
import com.ses.privacy.foundation.TrustedAuthority;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.service.AuditLogService;
import com.ses.service.ai.AiExternalSendApproval;
import com.ses.service.ai.AiExternalSendApprovalFixtures;
import com.ses.service.ai.AiExternalSendPrivacyPolicyCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * NF07-NF08-LINK の key（RB-NF08-LINK review P3-2、統括の決定）: NF08 の外部送信 policy の data element を本番 catalog（V196）にある
 * {@code AI_EXTERNAL_PROVIDER_COPY} にしたことで、NF07-REG がその key の policy を登録・有効化でき、LINK の送信判定が
 * {@code PERMITTED} になることを H2 上で確かめる（catalog に無い {@code AI_PROMPT_LOG} は REG が登録を拒否する）。
 *
 * <p>鍵は test-only（実行のたびに memory 上で生成）。envelope の値は合成値で、法的な値ではない。verifier・内部判定は prod の
 * {@link MockEnvironment} で {@link ProductionPrivacyComponents} から生成する。各 test は専用の tenant の行だけを読み、test の
 * transaction の rollback で消える。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PrivacyPolicyNf08DispatchKeyTest {

    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    private static final String AUTHORITY = "policy-issuer-test";
    private static final String KEY_ID = "k1";
    private static final String REVOCATION_REF = "envelope-revocation-test";
    private static final String LEGACY_ELEMENT = "AI_PROMPT_LOG";

    @Autowired
    private RetentionPolicyMapper policyMapper;
    @Autowired
    private PiiDataElementMapper dataElementMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private String tenant;
    private Clock clock;
    private KeyPair keyPair;
    private ProductionPolicyTrustRegistry registry;
    private final RevocationStatusProvider revocations = reference -> RevocationStatus.ACTIVE;
    private final AtomicReference<PrivacyPolicyRegistrationService.Actor> actor = new AtomicReference<>();
    private PrivacyPolicyRegistrationService service;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "nf08-link-key-" + UUID.randomUUID();
        clock = Clock.fixed(NOW, ZoneId.of("Asia/Tokyo"));
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        TrustedAuthority anchor = new TrustedAuthority(AUTHORITY, KEY_ID, keyPair.getPublic(), Set.of(tenant),
                Set.of(AiExternalSendPrivacyPolicyCheck.MODULE_CODE),
                Set.of(AiExternalSendPrivacyPolicyCheck.DATA_ELEMENT_ID, LEGACY_ELEMENT), Set.of("POLICY"),
                Set.of("PRODUCTION"), Set.of(AiExternalSendPrivacyPolicyCheck.ACTION), Set.of("gemini-adapter"),
                NOW.minus(Duration.ofDays(7)), NOW.plus(Duration.ofDays(365)));
        registry = () -> Map.of(AUTHORITY + ":" + KEY_ID, anchor);
        PrivacyApprovalProperties approval = new PrivacyApprovalProperties();
        decision(approval.getD01(), "D01-NF07-TEST-001");
        decision(approval.getD02(), "D02-NF07-TEST-001");
        decision(approval.getD03(), "D03-NF07-TEST-001");
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId(tenant);
        service = new PrivacyPolicyRegistrationService(mock(PrivacyAccessGuard.class), oidc, approval,
                ProductionPrivacyComponents.policyRegistrationVerifier(prod(), registry, revocations, clock),
                policyMapper, dataElementMapper, new PrivacyPolicyAudit(mock(AuditLogService.class)),
                new TransactionTemplate(transactionManager), clock, actor::get);
        // V196 の seed と同じ行（後から作られた tenant には seed が入らないため、同じ値で入れる）
        PiiDataElement element = new PiiDataElement();
        element.setTenantId(tenant);
        element.setDataElementId(AiExternalSendPrivacyPolicyCheck.DATA_ELEMENT_ID);
        element.setStorageKind("AI_PAYLOAD");
        element.setStorageRef("gemini-adapter.request");
        element.setSubjectAttribution("UNKNOWN");
        element.setInventoryRef("pii-inventory.md#3");
        assertThat(dataElementMapper.insert(element)).isEqualTo(1);
    }

    @Test
    void NF08の外部送信keyのdata_elementは本番catalogのV196にある() throws Exception {
        String seed = new String(new ClassPathResource(
                "db/migration/V196__nf07_privacy_data_element_catalog_seed.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(seed).contains("'" + AiExternalSendPrivacyPolicyCheck.DATA_ELEMENT_ID
                + "', 'AI_PAYLOAD', 'gemini-adapter.request'");
        assertThat(seed).as("旧 key の AI_PROMPT_LOG は本番 catalog に無い").doesNotContain("'" + LEGACY_ELEMENT + "'");
    }

    @Test
    void NF08の外部送信keyのpolicyはREGで登録と有効化ができLINKの送信判定がPERMITTEDになる() throws Exception {
        AiExternalSendPrivacyPolicyCheck check = AiExternalSendApprovalFixtures.privacyPolicyCheck(enforcement(), null);
        AiExternalSendApproval.Snapshot approval = AiExternalSendApproval.evaluate(
                AiExternalSendApprovalFixtures.approvedConfig(TODAY), TODAY).snapshot();

        actor.set(new PrivacyPolicyRegistrationService.Actor(tenant, 101L, "privacy-admin-101"));
        PrivacyPolicyRegistrationResult registered = service.register(
                sign(AiExternalSendPrivacyPolicyCheck.DATA_ELEMENT_ID));
        assertThat(registered.registrationStatus()).isEqualTo("CANDIDATE");
        // 登録だけ（current が無い）では送信しない
        assertThat(check.evaluate(tenant, approval, AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY))
                .isEqualTo(PolicyEnforcementReason.POLICY_UNKNOWN);

        actor.set(new PrivacyPolicyRegistrationService.Actor(tenant, 102L, "privacy-admin-102"));
        PrivacyPolicyRegistrationResult activated = service.activate(registered.id(),
                new PrivacyPolicyActivationRequest(null));
        assertThat(activated.registrationStatus()).isEqualTo("CURRENT");

        assertThat(check.evaluate(tenant, approval, AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY))
                .isEqualTo(PolicyEnforcementReason.PERMITTED);
        // 別 tenant の実行からは、この tenant の policy で送信を許可しない
        assertThat(check.evaluate("other-" + tenant, approval, AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY))
                .isEqualTo(PolicyEnforcementReason.POLICY_UNKNOWN);
    }

    @Test
    void catalogに無い旧keyのAI_PROMPT_LOGのpolicyはREGが登録を拒否する() throws Exception {
        actor.set(new PrivacyPolicyRegistrationService.Actor(tenant, 101L, "privacy-admin-101"));

        assertThatThrownBy(() -> service.register(sign(LEGACY_ELEMENT)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo(409);
                    assertThat(business.getMessageKey()).isEqualTo("error.privacy.policyDataElementUnknown");
                });
    }

    private PolicyEnforcementPort enforcement() {
        return ProductionPrivacyComponents.productionPolicyEnforcement(prod(), () -> true,
                new DbProductionPolicyCatalog(dataElementMapper, policyMapper), new DbCurrentPolicySource(policyMapper),
                registry, revocations, clock);
    }

    /** test-only の鍵で署名した NF08 の外部送信 policy（NF08 の承認の署名日 2026-11-16 JST より前に発行）。 */
    private PrivacyPolicyRegistrationRequest sign(String dataElementId) throws Exception {
        Instant issuedAt = NOW.minus(Duration.ofDays(2));
        ApprovalEnvelopeMetadata metadata = new ApprovalEnvelopeMetadata("1", AUTHORITY, KEY_ID, tenant,
                AiExternalSendPrivacyPolicyCheck.MODULE_CODE, "PRODUCTION", "r1", "requester-test", "approver-test",
                REVOCATION_REF, issuedAt, issuedAt, NOW.plus(Duration.ofDays(30)));
        PolicyApprovalEnvelope unsigned = new PolicyApprovalEnvelope(metadata,
                AiExternalSendApprovalFixtures.NF07_POLICY_ID, "v1", dataElementId,
                AiExternalSendPrivacyPolicyCheck.ACTION, "gemini-adapter", "TEST_PURPOSE", "TEST_TRIGGER",
                "TEST_ONLY_RULE", "TEST_ONLY_DISPOSITION", new byte[]{0});
        byte[] canonical = ApprovalEnvelopeCanonicalizer.canonicalBytes(unsigned);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(canonical);
        return new PrivacyPolicyRegistrationRequest(Base64.getEncoder().encodeToString(canonical),
                Base64.getEncoder().encodeToString(signer.sign()));
    }

    private static MockEnvironment prod() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    private static void decision(PrivacyApprovalProperties.DecisionReference reference, String decisionId) {
        reference.setDecisionId(decisionId);
        reference.setSignedOn("2026-11-01");
        reference.setExpiresOn("2027-06-01");
    }
}
