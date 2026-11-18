package com.ses.service.ai.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.entity.AuditLog;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.privacy.foundation.ApprovalEnvelopeCanonicalizer;
import com.ses.privacy.foundation.ApprovalEnvelopeMetadata;
import com.ses.privacy.foundation.CurrentPolicySnapshot;
import com.ses.privacy.foundation.PolicyApprovalEnvelope;
import com.ses.privacy.foundation.PolicyEnforcementPort;
import com.ses.privacy.foundation.PolicyLookupKey;
import com.ses.privacy.foundation.ProductionCatalogBinding;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.foundation.RevocationStatus;
import com.ses.privacy.foundation.TrustedAuthority;
import com.ses.service.AuditLogService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.AiExternalSendApprovalFixtures;
import com.ses.service.ai.AiExternalSendAuditor;
import com.ses.service.ai.AiExternalSendPrivacyPolicyCheck;
import com.ses.service.ai.AiGatewayRequest;
import com.ses.service.ai.AiGatewayResult;
import com.ses.service.ai.AiOutboundProbe;
import com.ses.service.ai.AiProductionApprovalGate;
import com.ses.service.ai.CopilotBoundedAiTextService;
import com.ses.service.ai.CopilotProviderResponse;
import com.ses.service.ai.CopilotProviderUsage;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshotFactory;
import com.ses.service.ai.copilot.summary.CopilotSummaryPromptTemplate;
import com.ses.service.ai.impl.AiExecutionGatewayImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * NF07-NF08-LINK: 承認済み外部送信は、NF07 の policy が現行で有効な場合だけ送信する（計画 §8・external-send-approval-gate §1.1）。
 *
 * <p>NF07 側は本番の内部判定 port（{@link ProductionPrivacyComponents#productionPolicyEnforcement}）を test-only の鍵で
 * 署名した policy（合成値）と in-memory の catalog・source・trust・失効状態で動かし、provider は送信を記録するだけの fake
 * （ネットワーク I/O・実 provider・実 secret・DB なし）。NF07 の flag OFF・UNKNOWN・REVOKED・EXPIRED・DRIFT・policy ID の不一致は
 * 全て送信前に拒否し、NF08 の承認が current policy の再発行より前に署名されたもの（W3-03 P3-2 の再利用）も拒否し、
 * 全て揃ったときだけ1回送信する。
 */
class CopilotExternalSendPrivacyPolicyLinkTest {

    private static final String PROMPT_VERSION = CopilotSummaryPromptTemplate.VERSION + "-gemini-json-v1";
    private static final String CONFIG_HASH = "synthetic-approved-config-hash-v1";
    private static final String CLAIM_KEY = "forecast.utilization.2026-09";
    private static final Instant NOW = Instant.parse("2026-11-13T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final String TENANT = "tenant-a";
    private static final String POLICY_ID = AiExternalSendApprovalFixtures.NF07_POLICY_ID;
    private static final String ANCHOR_KEY = "policy-issuer-test:k1";
    private static final String REVOCATION_REF = "envelope-revocation-test";

    /** NF07 側の状態（test-only）。 */
    enum Scenario {
        ALL_SATISFIED, RETENTION_OFF, REVOCATION_UNKNOWN, ENVELOPE_REVOKED, POLICY_EXPIRED, SIGNATURE_TAMPERED,
        POLICY_ID_MISMATCH,
        /** NF08 の承認（署名日 2026-11-12）が current policy の再発行（2026-11-13）より前（W3-03 P3-2 の再利用）。 */
        APPROVAL_PREDATES_POLICY
    }

    private AuditLogService auditLogService;
    private AiOutboundProbe probe;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        auditLogService = mock(AuditLogService.class);
        probe = mock(AiOutboundProbe.class);
        meters = new SimpleMeterRegistry();
    }

    @Test
    void NF07のpolicyが全て揃ったときだけ一回送信する() throws Exception {
        FakeRemoteAdapter adapter = new FakeRemoteAdapter();
        AiExecutionGatewayImpl gateway = gateway(adapter, nf07(Scenario.ALL_SATISFIED));

        AiGatewayResult result = AccountingTenantContextHolder.runWithTenant(TENANT,
                () -> gateway.execute(request()));

        assertEquals(1, adapter.prompts.size());
        assertTrue(result.getText().contains("dashboard.summary"));
        List<AuditLog> audits = capturedAudits(2);
        assertEquals(AiExternalSendAuditor.APPLICATION_ATTEMPT, audits.get(0).getApplicationCode());
        assertEquals(AiExternalSendAuditor.APPLICATION_RESULT, audits.get(1).getApplicationCode());
        verify(probe, times(1)).record(any());
        assertTrue(meters.getMeters().isEmpty(), "許可ではNF07拒否のmetricを出さない");
    }

    @ParameterizedTest
    @EnumSource(value = Scenario.class, names = "ALL_SATISFIED", mode = EnumSource.Mode.EXCLUDE)
    void NF07のflagOFF_UNKNOWN_REVOKED_EXPIRED_DRIFT_policyId不一致は送信前に拒否する(Scenario scenario)
            throws Exception {
        FakeRemoteAdapter adapter = new FakeRemoteAdapter();
        AiExecutionGatewayImpl gateway = gateway(adapter, nf07(scenario));

        BusinessException denied = AccountingTenantContextHolder.runWithTenant(TENANT,
                () -> assertThrows(BusinessException.class, () -> gateway.execute(request())));

        assertEquals(503, denied.getCode());
        assertEquals(AiProductionApprovalGate.REASON_PRIVACY_POLICY_NOT_ACTIVE, denied.getMessage());
        assertTrue(adapter.prompts.isEmpty(), "providerへ送信しない");
        verify(probe, never()).record(any());
        AuditLog audit = capturedAudits(1).get(0);
        assertEquals(AiExternalSendAuditor.APPLICATION_DENIED, audit.getApplicationCode());
        assertEquals(AiProductionApprovalGate.REASON_PRIVACY_POLICY_NOT_ACTIVE, audit.getErrorCode());
        assertEquals(503, audit.getStatus());
        assertEquals(1.0, meters.counter(AiExternalSendPrivacyPolicyCheck.DENIED_METRIC, "stage", "GATEWAY",
                "reason", expectedReason(scenario)).count(), scenario.name());
    }

    @ParameterizedTest
    @EnumSource(value = Scenario.class, names = "ALL_SATISFIED", mode = EnumSource.Mode.EXCLUDE)
    void 読取専用preflightもNF07の拒否で止め監査もproviderも呼ばない(Scenario scenario) throws Exception {
        FakeRemoteAdapter adapter = new FakeRemoteAdapter();
        AiExecutionGatewayImpl gateway = gateway(adapter, nf07(scenario));

        BusinessException denied = AccountingTenantContextHolder.runWithTenant(TENANT,
                () -> assertThrows(BusinessException.class, () -> gateway.preflightCopilot(request())));

        assertEquals(AiProductionApprovalGate.REASON_PRIVACY_POLICY_NOT_ACTIVE, denied.getMessage());
        assertTrue(adapter.prompts.isEmpty());
        verifyNoInteractions(auditLogService);
        verify(probe, never()).record(any());
    }

    @Test
    void 本番の判定portを持たないgateは承認設定が完全でも送信しない() {
        FakeRemoteAdapter adapter = new FakeRemoteAdapter();
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AiProductionApprovalGate gate = new AiProductionApprovalGate(config, clock);
        AiExecutionGatewayImpl gateway = new AiExecutionGatewayImpl(adapter, config, probe, new ObjectMapper(),
                mock(AiArtifactVersionMapper.class), mock(AiRecommendationRunMapper.class),
                mock(PlatformTransactionManager.class), clock, gate, new CopilotFeatureGate(config, gate),
                new AiExternalSendAuditor(auditLogService));

        BusinessException denied = AccountingTenantContextHolder.runWithTenant(TENANT,
                () -> assertThrows(BusinessException.class, () -> gateway.execute(request())));

        assertEquals(AiProductionApprovalGate.REASON_PRIVACY_POLICY_NOT_ACTIVE, denied.getMessage());
        assertTrue(adapter.prompts.isEmpty());
    }

    // ---------------------------------------------------------------- helper

    private static String expectedReason(Scenario scenario) {
        return switch (scenario) {
            case RETENTION_OFF -> "RETENTION_DISABLED";
            case REVOCATION_UNKNOWN -> "POLICY_UNKNOWN";
            case ENVELOPE_REVOKED -> "POLICY_REVOKED";
            case POLICY_EXPIRED -> "POLICY_EXPIRED";
            case SIGNATURE_TAMPERED -> "POLICY_DRIFT";
            case POLICY_ID_MISMATCH -> "POLICY_ID_MISMATCH";
            case APPROVAL_PREDATES_POLICY -> "APPROVAL_PREDATES_POLICY";
            case ALL_SATISFIED -> "PERMITTED";
        };
    }

    /** 本番の内部判定 port を、test-only の鍵で署名した合成 policy と in-memory の部品で構成する。 */
    private static PolicyEnforcementPort nf07(Scenario scenario) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        // 既定は NF08 の承認の署名日（fixture: 2026-11-12 JST）より前に発行した envelope。再利用の場面だけ署名日の後に再発行する。
        Instant issuedAt = scenario == Scenario.APPROVAL_PREDATES_POLICY
                ? NOW.minus(Duration.ofHours(1)) : NOW.minus(Duration.ofDays(2));
        ApprovalEnvelopeMetadata metadata = new ApprovalEnvelopeMetadata("1", "policy-issuer-test", "k1", TENANT,
                AiExternalSendPrivacyPolicyCheck.MODULE_CODE, "PRODUCTION", "r1", "requester-test", "approver-test",
                REVOCATION_REF, issuedAt, issuedAt, NOW.plus(Duration.ofDays(30)));
        PolicyApprovalEnvelope unsigned = new PolicyApprovalEnvelope(metadata, POLICY_ID, "v1",
                AiExternalSendPrivacyPolicyCheck.DATA_ELEMENT_ID, AiExternalSendPrivacyPolicyCheck.ACTION,
                "gemini-adapter", "TEST_PURPOSE", "TEST_TRIGGER", "TEST_ONLY_RULE", "TEST_ONLY_DISPOSITION",
                new byte[]{0});
        byte[] canonical = ApprovalEnvelopeCanonicalizer.canonicalBytes(unsigned);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        // 本番は digest 声明への署名だけを受け付ける（NF07-TRUST）
        signer.update(com.ses.privacy.foundation.PolicyEnvelopeSigningStatement.of(canonical));
        byte[] signature = signer.sign();
        if (scenario == Scenario.SIGNATURE_TAMPERED) {
            signature[0] ^= 1;
        }
        TrustedAuthority anchor = new TrustedAuthority("policy-issuer-test", "k1", keyPair.getPublic(),
                Set.of(TENANT), Set.of(AiExternalSendPrivacyPolicyCheck.MODULE_CODE),
                Set.of(AiExternalSendPrivacyPolicyCheck.DATA_ELEMENT_ID), Set.of("POLICY"), Set.of("PRODUCTION"),
                Set.of(AiExternalSendPrivacyPolicyCheck.ACTION), Set.of("gemini-adapter"),
                NOW.minus(Duration.ofDays(7)), NOW.plus(Duration.ofDays(365)));
        String currentPolicyId = scenario == Scenario.POLICY_ID_MISMATCH ? "NF07-POL-2026-0099" : POLICY_ID;
        ProductionCatalogBinding binding = new ProductionCatalogBinding(currentPolicyId,
                AiExternalSendPrivacyPolicyCheck.MODULE_CODE, ProductionCatalogBinding.TENANT_WIDE_ACTOR_BINDING,
                AiExternalSendPrivacyPolicyCheck.DATA_ELEMENT_ID, AiExternalSendPrivacyPolicyCheck.ACTION,
                "gemini-adapter", PolicyLookupKey.TENANT_WIDE);
        CurrentPolicySnapshot current = CurrentPolicySnapshot.current(currentPolicyId, "r1",
                ApprovalEnvelopeCanonicalizer.digest(canonical), canonical, signature);
        Map<String, RevocationStatus> revocations = switch (scenario) {
            case REVOCATION_UNKNOWN -> Map.of();
            case ENVELOPE_REVOKED -> Map.of(ANCHOR_KEY, RevocationStatus.ACTIVE, REVOCATION_REF, RevocationStatus.REVOKED);
            default -> Map.of(ANCHOR_KEY, RevocationStatus.ACTIVE, REVOCATION_REF, RevocationStatus.ACTIVE);
        };
        Instant nf07Now = scenario == Scenario.POLICY_EXPIRED ? NOW.plus(Duration.ofDays(31)) : NOW;
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        return ProductionPrivacyComponents.productionPolicyEnforcement(prod,
                () -> scenario != Scenario.RETENTION_OFF,
                key -> TENANT.equals(key.tenantId()) ? Optional.of(binding) : Optional.empty(),
                (key, resolved) -> current,
                () -> Map.of(ANCHOR_KEY, anchor),
                reference -> revocations.getOrDefault(reference, RevocationStatus.UNKNOWN),
                Clock.fixed(nf07Now, ZoneOffset.UTC));
    }

    private AiExecutionGatewayImpl gateway(CopilotBoundedAiTextService adapter, PolicyEnforcementPort nf07) {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AiProductionApprovalGate gate = AiExternalSendApprovalFixtures.gate(config, clock,
                AiExternalSendApprovalFixtures.privacyPolicyCheck(nf07, meters));
        return new AiExecutionGatewayImpl(adapter, config, probe, new ObjectMapper(),
                mock(AiArtifactVersionMapper.class), mock(AiRecommendationRunMapper.class),
                mock(PlatformTransactionManager.class), clock, gate, new CopilotFeatureGate(config, gate),
                new AiExternalSendAuditor(auditLogService));
    }

    private List<AuditLog> capturedAudits(int expected) {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, times(expected)).recordDomainEventRequired(captor.capture());
        return captor.getAllValues();
    }

    private static AiGatewayRequest request() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("catalog.queryId", "dashboard.summary");
        fields.put("summary.claimKeys", CLAIM_KEY);
        CopilotExecutionContext context = copilotContext();
        return AiGatewayRequest.builder()
                .useCase(AiGatewayRequest.USE_COPILOT)
                .taskMarker(CopilotSummaryPromptTemplate.TASK_MARKER)
                .trustedInstruction(CopilotSummaryPromptTemplate.TRUSTED_INSTRUCTION)
                .allowlistedFields(fields)
                .persistRun(false)
                .requireJson(true)
                .executionContext(context)
                .scopeContext(context.scope())
                .scopeHash(context.scopeHash())
                .deadlineNanos(System.nanoTime() + 10_000_000_000L)
                .costBudgetYen(50)
                .expectedArtifactVersionId(7L)
                .expectedProvider("gemini")
                .expectedModel(AiExternalSendApprovalFixtures.MODEL)
                .expectedPromptVersion(PROMPT_VERSION)
                .expectedConfigHash(CONFIG_HASH)
                .build();
    }

    private static CopilotExecutionContext copilotContext() {
        CopilotExecutionContext context = new CopilotExecutionContext(TENANT, 1L,
                Instant.parse("2026-09-08T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        EffectiveScopeSnapshot snapshot = new EffectiveScopeSnapshot(TENANT, 1L,
                LocalDate.of(2026, 9, 8), "COMPANY_WIDE", true, false, false,
                null, null, null, null, null, null, null, null, null,
                EffectiveScopeSnapshotFactory.POLICY_VERSION, false, "ALL",
                scopeHash(TENANT, 1L, LocalDate.of(2026, 9, 8)));
        context.bindSnapshot(snapshot);
        context.bind("dashboard.summary", CopilotQueryParameters.ofQuery("dashboard.summary"), snapshot.scope());
        return context;
    }

    private static String scopeHash(String tenantId, long legalEntityId, LocalDate asOf) {
        String canonical = "tenant=" + tenantId + "|legalEntity=" + legalEntityId
                + "|asOf=" + asOf + "|scopeType=COMPANY_WIDE|policy="
                + EffectiveScopeSnapshotFactory.POLICY_VERSION + "|members=ALL";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("effective scope hash calculation failed", ex);
        }
    }

    /** 外部providerを模したfake。ネットワークI/Oを行わず、受け取ったpromptを記録するだけ。 */
    private static final class FakeRemoteAdapter implements CopilotBoundedAiTextService {
        private final List<String> prompts = new ArrayList<>();

        @Override public String providerId() { return "gemini"; }
        @Override public String copilotModelId() { return AiExternalSendApprovalFixtures.MODEL; }
        @Override public String copilotPromptVersion() { return PROMPT_VERSION; }
        @Override public String copilotConfigHash() { return CONFIG_HASH; }
        @Override public String copilotCanonicalEndpoint() { return "https://generativelanguage.googleapis.com"; }

        @Override
        public String generate(String prompt) {
            throw new IllegalStateException("legacy generateは呼ばれてはならない");
        }

        @Override
        public String generateCopilotSummary(String prompt, long deadlineNanos) {
            throw new IllegalStateException("deadline overloadは呼ばれてはならない");
        }

        @Override
        public CopilotProviderResponse generateCopilotResponse(String prompt, CopilotInvocation invocation) {
            prompts.add(prompt);
            return new CopilotProviderResponse("{\"queryId\":\"dashboard.summary\",\"summaryText\":\"ok\","
                    + "\"claimKeys\":[\"" + CLAIM_KEY + "\"]}", new CopilotProviderUsage(100, 20, 120, 0, 0));
        }
    }
}
