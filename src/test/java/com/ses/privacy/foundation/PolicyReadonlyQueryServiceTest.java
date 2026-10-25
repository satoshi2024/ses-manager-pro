package com.ses.privacy.foundation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PolicyReadonlyQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final String TENANT = "tenant-synth-1";
    private static final String ACTOR_ID = "actor-audit-1";
    private static final String MODULE_NF08 = "NF08_AI_SUMMARY";
    private static final String MODULE_NF03 = "NF03_CERTIFICATION";
    private static final String ELEMENT_AI_LOG = "AI_PROMPT_LOG";
    private static final String ELEMENT_CERT = "CERTIFICATION_PII";
    private static final String ACTION_PURGE = "DISPOSITION_PURGE";
    private static final String ACTION_DISPATCH = "EXTERNAL_DISPATCH";
    private static final String PROVIDER_DB = "local-db";
    private static final String PROVIDER_GEMINI = "gemini-adapter";
    private static final String POLICY_ID_PURGE = "policy-nf08-log-purge-v1";
    private static final String POLICY_ID_DISPATCH = "policy-nf08-log-dispatch-v1";
    private static final String SCOPE_AI = "AI_FEEDBACK";
    private static final String SCOPE_CERT = "CERTIFICATION";

    private KeyPair keyPair;
    private TrustedAuthority anchor;
    private Map<String, TrustedAuthority> trustAnchors;
    private Map<String, RevocationStatus> revocationMap;
    private RevocationStatusProvider revocations;
    private SyntheticEnvironmentGuard environmentGuard;
    private Clock clock;
    private ApprovalEnvelopeVerifier verifier;
    private SyntheticPolicyCatalog catalog;
    private Map<String, PolicyLookupResult> sourceStorage;
    private PolicyEnvelopeSource policySource;

    private TrustedCallerContext defaultCaller;
    private SyntheticCallerContextProvider callerProvider;
    private PolicyEvaluationQuery defaultQuery;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        keyPair = generator.generateKeyPair();
        anchor = new TrustedAuthority("synthetic-authority", "key-ephemeral", keyPair.getPublic(),
                Set.of(TENANT), Set.of(SCOPE_AI, SCOPE_CERT), Set.of(ELEMENT_AI_LOG, ELEMENT_CERT),
                Set.of("POLICY", "CASE"),
                Set.of(SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE),
                Set.of(ACTION_PURGE, ACTION_DISPATCH),
                Set.of(PROVIDER_DB, PROVIDER_GEMINI),
                NOW.minusSeconds(3600), NOW.plusSeconds(3600));

        trustAnchors = Map.of("synthetic-authority:key-ephemeral", anchor);
        revocationMap = new HashMap<>();
        revocationMap.put("synthetic-authority:key-ephemeral", RevocationStatus.ACTIVE);
        revocations = ref -> revocationMap.getOrDefault(ref, RevocationStatus.ACTIVE);

        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        environmentGuard = new SyntheticEnvironmentGuard(new MockEnvironment());
        verifier = new ApprovalEnvelopeVerifier(trustAnchors, revocations, clock, environmentGuard);
        catalog = SyntheticPolicyCatalog.createDefault();

        sourceStorage = new HashMap<>();
        policySource = (tenantId, policyId, dataElementId, action, providerId) -> {
            String key = tenantId + ":" + policyId + ":" + dataElementId + ":" + action + ":" + providerId;
            return sourceStorage.getOrDefault(key, new PolicyLookupResult(PolicyLookupResult.LookupStatus.NOT_FOUND, null, null, null));
        };

        defaultCaller = new TrustedCallerContext(TENANT, ACTOR_ID, TrustedActorRole.ROLE_PRIVACY_AUDITOR, NOW.minusSeconds(60));
        callerProvider = () -> Optional.of(defaultCaller);
        defaultQuery = new PolicyEvaluationQuery(TENANT, ACTOR_ID, MODULE_NF08, ELEMENT_AI_LOG, ACTION_PURGE, PROVIDER_DB);

        // デフォルトの有効なポリシーを作成・登録
        PolicyApprovalEnvelope envelope = createSignedPolicy(POLICY_ID_PURGE, "rev-1", ELEMENT_AI_LOG, ACTION_PURGE, PROVIDER_DB, SCOPE_AI,
                NOW.minusSeconds(600), NOW.minusSeconds(300), NOW.plusSeconds(1800), "req-1", "app-2");
        String digest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(envelope));
        revocationMap.put(envelope.metadata().revocationReference(), RevocationStatus.ACTIVE);
        sourceStorage.put(TENANT + ":" + POLICY_ID_PURGE + ":" + ELEMENT_AI_LOG + ":" + ACTION_PURGE + ":" + PROVIDER_DB,
                new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE, envelope, "rev-1", digest));
    }

    private PolicyReadonlyQueryService createService() {
        return new PolicyReadonlyQueryService(callerProvider, catalog, policySource, verifier, environmentGuard, clock);
    }

    private PolicyApprovalEnvelope createSignedPolicy(String policyId, String revision, String dataElementId,
                                                      String action, String providerId, String scope,
                                                      Instant issuedAt, Instant effectiveAt, Instant expiresAt,
                                                      String requesterId, String approverId) throws Exception {
        ApprovalEnvelopeMetadata metadata = new ApprovalEnvelopeMetadata(
                "1", "synthetic-authority", "key-ephemeral", TENANT, scope,
                SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE, revision,
                requesterId, approverId, "revoc-" + policyId + "-" + revision,
                issuedAt, effectiveAt, expiresAt
        );
        PolicyApprovalEnvelope unsigned = new PolicyApprovalEnvelope(
                metadata, policyId, "1.0", dataElementId, action, providerId,
                "synthetic-purpose", "event-trigger", "retention-rule-30d", "purge-data", new byte[0]
        );
        byte[] canonicalBytes = ApprovalEnvelopeCanonicalizer.canonicalBytes(unsigned);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(canonicalBytes);
        byte[] sig = signer.sign();

        return new PolicyApprovalEnvelope(
                metadata, unsigned.policyId(), unsigned.policyVersion(), unsigned.dataElementId(),
                action, providerId, unsigned.purpose(), unsigned.trigger(), unsigned.retentionRule(),
                unsigned.disposition(), sig
        );
    }

    // =========================================================================
    // 9.1 呼び出し元コンテキスト検証テスト
    // =========================================================================

    @Test
    void testCallerContext_UnprivilegedUser_ReturnsAccessDenied() {
        callerProvider = () -> Optional.of(new TrustedCallerContext(TENANT, ACTOR_ID, TrustedActorRole.ROLE_UNPRIVILEGED_USER, NOW));
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.ACCESS_DENIED, result.status());
        assertEquals(PolicyFailureReason.UNAUTHORIZED_ACTOR_ROLE, result.failureReason());
    }

    @Test
    void testCallerContext_CrossTenantMismatch_ReturnsAccessDenied() {
        callerProvider = () -> Optional.of(new TrustedCallerContext("tenant-other", ACTOR_ID, TrustedActorRole.ROLE_ADMIN, NOW));
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.ACCESS_DENIED, result.status());
        assertEquals(PolicyFailureReason.CALLER_TENANT_MISMATCH, result.failureReason());
    }

    @Test
    void testCallerContext_ActorMismatch_ReturnsAccessDenied() {
        callerProvider = () -> Optional.of(new TrustedCallerContext(TENANT, "actor-other", TrustedActorRole.ROLE_ADMIN, NOW));
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.ACCESS_DENIED, result.status());
        assertEquals(PolicyFailureReason.CALLER_ACTOR_MISMATCH, result.failureReason());
    }

    @Test
    void testCallerContext_MissingIdentity_ReturnsAccessDenied() {
        callerProvider = Optional::empty;
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.ACCESS_DENIED, result.status());
        assertEquals(PolicyFailureReason.MISSING_CALLER_IDENTITY, result.failureReason());
    }

    // =========================================================================
    // 9.2 カタログ・ソース・現在版照合・非干渉テスト
    // =========================================================================

    @Test
    void testCatalog_UnregisteredEntry_ReturnsPolicyUnknown() {
        PolicyReadonlyQueryService service = createService();
        PolicyEvaluationQuery unknownQuery = new PolicyEvaluationQuery(TENANT, ACTOR_ID, MODULE_NF08, "UNREGISTERED_DATA", ACTION_PURGE, PROVIDER_DB);

        PolicyExplanationResult result = service.evaluate(unknownQuery);
        assertEquals(PolicyDecisionStatus.POLICY_UNKNOWN, result.status());
        assertEquals(PolicyFailureReason.POLICY_NOT_FOUND, result.failureReason());
    }

    @Test
    void testSource_UnapprovedPolicy_ReturnsPolicyUnknown() {
        sourceStorage.put(TENANT + ":" + POLICY_ID_PURGE + ":" + ELEMENT_AI_LOG + ":" + ACTION_PURGE + ":" + PROVIDER_DB,
                new PolicyLookupResult(PolicyLookupResult.LookupStatus.UNAPPROVED, null, null, null));
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_UNKNOWN, result.status());
        assertEquals(PolicyFailureReason.POLICY_UNAPPROVED, result.failureReason());
    }

    @Test
    void testSource_StoreUnavailable_ReturnsPolicyUnknown() {
        sourceStorage.put(TENANT + ":" + POLICY_ID_PURGE + ":" + ELEMENT_AI_LOG + ":" + ACTION_PURGE + ":" + PROVIDER_DB,
                new PolicyLookupResult(PolicyLookupResult.LookupStatus.STORE_UNAVAILABLE, null, null, null));
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_UNKNOWN, result.status());
        assertEquals(PolicyFailureReason.STORE_UNAVAILABLE, result.failureReason());
    }

    @Test
    void testSource_PolicyIdMismatch_ReturnsPolicyDrift() throws Exception {
        PolicyApprovalEnvelope envelope = createSignedPolicy("wrong-policy-id", "rev-1", ELEMENT_AI_LOG, ACTION_PURGE, PROVIDER_DB, SCOPE_AI,
                NOW.minusSeconds(600), NOW.minusSeconds(300), NOW.plusSeconds(1800), "req-1", "app-2");
        String digest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(envelope));
        sourceStorage.put(TENANT + ":" + POLICY_ID_PURGE + ":" + ELEMENT_AI_LOG + ":" + ACTION_PURGE + ":" + PROVIDER_DB,
                new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE, envelope, "rev-1", digest));
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_DRIFT, result.status());
        assertEquals(PolicyFailureReason.POLICY_ID_MISMATCH, result.failureReason());
    }

    @Test
    void testSource_RevisionMismatch_ReturnsPolicyDrift() throws Exception {
        PolicyApprovalEnvelope envelope = createSignedPolicy(POLICY_ID_PURGE, "rev-actual", ELEMENT_AI_LOG, ACTION_PURGE, PROVIDER_DB, SCOPE_AI,
                NOW.minusSeconds(600), NOW.minusSeconds(300), NOW.plusSeconds(1800), "req-1", "app-2");
        String digest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(envelope));
        sourceStorage.put(TENANT + ":" + POLICY_ID_PURGE + ":" + ELEMENT_AI_LOG + ":" + ACTION_PURGE + ":" + PROVIDER_DB,
                new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE, envelope, "rev-declared", digest));
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_DRIFT, result.status());
        assertEquals(PolicyFailureReason.REVISION_MISMATCH, result.failureReason());
    }

    @Test
    void testSource_DigestMismatch_ReturnsPolicyDrift() throws Exception {
        PolicyApprovalEnvelope envelope = createSignedPolicy(POLICY_ID_PURGE, "rev-1", ELEMENT_AI_LOG, ACTION_PURGE, PROVIDER_DB, SCOPE_AI,
                NOW.minusSeconds(600), NOW.minusSeconds(300), NOW.plusSeconds(1800), "req-1", "app-2");
        sourceStorage.put(TENANT + ":" + POLICY_ID_PURGE + ":" + ELEMENT_AI_LOG + ":" + ACTION_PURGE + ":" + PROVIDER_DB,
                new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE, envelope, "rev-1", "sha256:corrupted-digest"));
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_DRIFT, result.status());
        assertEquals(PolicyFailureReason.DIGEST_MISMATCH, result.failureReason());
    }

    @Test
    void testSameDataElement_DifferentActionAndProvider_IndependentEvaluation() throws Exception {
        // 同一要素 AI_PROMPT_LOG に対し、EXTERNAL_DISPATCH:gemini-adapter 用の独立ポリシーを登録
        PolicyApprovalEnvelope dispatchEnvelope = createSignedPolicy(POLICY_ID_DISPATCH, "rev-dispatch-1", ELEMENT_AI_LOG, ACTION_DISPATCH, PROVIDER_GEMINI, SCOPE_AI,
                NOW.minusSeconds(500), NOW.minusSeconds(200), NOW.plusSeconds(2000), "req-1", "app-2");
        String dispatchDigest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(dispatchEnvelope));
        revocationMap.put(dispatchEnvelope.metadata().revocationReference(), RevocationStatus.ACTIVE);
        sourceStorage.put(TENANT + ":" + POLICY_ID_DISPATCH + ":" + ELEMENT_AI_LOG + ":" + ACTION_DISPATCH + ":" + PROVIDER_GEMINI,
                new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE, dispatchEnvelope, "rev-dispatch-1", dispatchDigest));

        PolicyReadonlyQueryService service = createService();

        // 1. DISPOSITION_PURGE の照会
        PolicyExplanationResult purgeResult = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.APPROVED_ACTIVE, purgeResult.status());
        assertEquals(ACTION_PURGE, purgeResult.targetAction());
        assertEquals(PROVIDER_DB, purgeResult.targetProvider());
        assertEquals("rev-1", sourceStorage.get(TENANT + ":" + POLICY_ID_PURGE + ":" + ELEMENT_AI_LOG + ":" + ACTION_PURGE + ":" + PROVIDER_DB).currentRevision());

        // 2. EXTERNAL_DISPATCH の照会（同一要素・別操作・別プロバイダー）
        PolicyEvaluationQuery dispatchQuery = new PolicyEvaluationQuery(TENANT, ACTOR_ID, MODULE_NF08, ELEMENT_AI_LOG, ACTION_DISPATCH, PROVIDER_GEMINI);
        PolicyExplanationResult dispatchResult = service.evaluate(dispatchQuery);
        assertEquals(PolicyDecisionStatus.APPROVED_ACTIVE, dispatchResult.status());
        assertEquals(ACTION_DISPATCH, dispatchResult.targetAction());
        assertEquals(PROVIDER_GEMINI, dispatchResult.targetProvider());
        assertEquals(dispatchDigest, dispatchResult.policyDigest());
        assertNotEquals(purgeResult.policyDigest(), dispatchResult.policyDigest());
    }

    // =========================================================================
    // 9.3 ポリシー型付け検証テスト
    // =========================================================================

    @Test
    void testPolicy_NotYetIssued_ReturnsPolicyExpired() throws Exception {
        PolicyApprovalEnvelope envelope = createSignedPolicy(POLICY_ID_PURGE, "rev-1", ELEMENT_AI_LOG, ACTION_PURGE, PROVIDER_DB, SCOPE_AI,
                NOW.plusSeconds(60), NOW.minusSeconds(300), NOW.plusSeconds(1800), "req-1", "app-2");
        String digest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(envelope));
        sourceStorage.put(TENANT + ":" + POLICY_ID_PURGE + ":" + ELEMENT_AI_LOG + ":" + ACTION_PURGE + ":" + PROVIDER_DB,
                new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE, envelope, "rev-1", digest));
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_EXPIRED, result.status());
        assertEquals(PolicyFailureReason.NOT_YET_ISSUED, result.failureReason());
    }

    @Test
    void testPolicy_NotYetEffective_ReturnsPolicyExpired() throws Exception {
        PolicyApprovalEnvelope envelope = createSignedPolicy(POLICY_ID_PURGE, "rev-1", ELEMENT_AI_LOG, ACTION_PURGE, PROVIDER_DB, SCOPE_AI,
                NOW.minusSeconds(600), NOW.plusSeconds(60), NOW.plusSeconds(1800), "req-1", "app-2");
        String digest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(envelope));
        sourceStorage.put(TENANT + ":" + POLICY_ID_PURGE + ":" + ELEMENT_AI_LOG + ":" + ACTION_PURGE + ":" + PROVIDER_DB,
                new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE, envelope, "rev-1", digest));
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_EXPIRED, result.status());
        assertEquals(PolicyFailureReason.NOT_YET_EFFECTIVE, result.failureReason());
    }

    @Test
    void testPolicy_ExpiredAtDeadline_ReturnsPolicyExpired() throws Exception {
        PolicyApprovalEnvelope envelope = createSignedPolicy(POLICY_ID_PURGE, "rev-1", ELEMENT_AI_LOG, ACTION_PURGE, PROVIDER_DB, SCOPE_AI,
                NOW.minusSeconds(600), NOW.minusSeconds(300), NOW, "req-1", "app-2"); // expiresAt == NOW
        String digest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(envelope));
        sourceStorage.put(TENANT + ":" + POLICY_ID_PURGE + ":" + ELEMENT_AI_LOG + ":" + ACTION_PURGE + ":" + PROVIDER_DB,
                new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE, envelope, "rev-1", digest));
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_EXPIRED, result.status());
        assertEquals(PolicyFailureReason.EXPIRED_AT_OR_AFTER_DEADLINE, result.failureReason());
    }

    @Test
    void testPolicy_AuthorityExpired_ReturnsPolicyExpired() {
        TrustedAuthority expiredAnchor = new TrustedAuthority("synthetic-authority", "key-ephemeral", keyPair.getPublic(),
                anchor.tenants(), anchor.scopes(), anchor.dataElements(), anchor.envelopeKinds(), anchor.audiences(),
                anchor.actions(), anchor.providers(), NOW.minusSeconds(3600), NOW); // validUntil == NOW
        ApprovalEnvelopeVerifier localVerifier = new ApprovalEnvelopeVerifier(
                Map.of("synthetic-authority:key-ephemeral", expiredAnchor), revocations, clock, environmentGuard);
        PolicyReadonlyQueryService service = new PolicyReadonlyQueryService(callerProvider, catalog, policySource, localVerifier, environmentGuard, clock);

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_EXPIRED, result.status());
        assertEquals(PolicyFailureReason.AUTHORITY_VALIDITY_EXPIRED, result.failureReason());
    }

    @Test
    void testPolicy_AuthorityScopeMismatch_ReturnsPolicyDrift() {
        TrustedAuthority narrowAnchor = new TrustedAuthority("synthetic-authority", "key-ephemeral", keyPair.getPublic(),
                anchor.tenants(), Set.of("OTHER_SCOPE"), anchor.dataElements(), anchor.envelopeKinds(), anchor.audiences(),
                anchor.actions(), anchor.providers(), NOW.minusSeconds(3600), NOW.plusSeconds(3600));
        ApprovalEnvelopeVerifier localVerifier = new ApprovalEnvelopeVerifier(
                Map.of("synthetic-authority:key-ephemeral", narrowAnchor), revocations, clock, environmentGuard);
        PolicyReadonlyQueryService service = new PolicyReadonlyQueryService(callerProvider, catalog, policySource, localVerifier, environmentGuard, clock);

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_DRIFT, result.status());
        assertEquals(PolicyFailureReason.AUTHORITY_SCOPE_MISMATCH, result.failureReason());
    }

    @Test
    void testPolicy_KeyRevoked_ReturnsPolicyRevoked() {
        revocationMap.put("synthetic-authority:key-ephemeral", RevocationStatus.REVOKED);
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_REVOKED, result.status());
        assertEquals(PolicyFailureReason.KEY_REVOKED, result.failureReason());
    }

    @Test
    void testPolicy_EnvelopeRevoked_ReturnsPolicyRevoked() {
        revocationMap.put("revoc-" + POLICY_ID_PURGE + "-rev-1", RevocationStatus.REVOKED);
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_REVOKED, result.status());
        assertEquals(PolicyFailureReason.ENVELOPE_REVOKED, result.failureReason());
    }

    @Test
    void testPolicy_RevocationStatusUnknown_ReturnsPolicyUnknown() {
        revocationMap.put("synthetic-authority:key-ephemeral", RevocationStatus.UNKNOWN);
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_UNKNOWN, result.status());
        assertEquals(PolicyFailureReason.REVOCATION_STATUS_UNKNOWN, result.failureReason());
    }

    @Test
    void testPolicy_RevocationKeyNull_ReturnsPolicyUnknown() {
        revocations = ref -> ref.startsWith("synthetic-authority") ? null : RevocationStatus.ACTIVE;
        verifier = new ApprovalEnvelopeVerifier(trustAnchors, revocations, clock, environmentGuard);
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_UNKNOWN, result.status());
        assertEquals(PolicyFailureReason.REVOCATION_STATUS_UNKNOWN, result.failureReason());
    }

    @Test
    void testPolicy_RevocationEnvelopeNull_ReturnsPolicyUnknown() {
        revocations = ref -> ref.startsWith("revoc-") ? null : RevocationStatus.ACTIVE;
        verifier = new ApprovalEnvelopeVerifier(trustAnchors, revocations, clock, environmentGuard);
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_UNKNOWN, result.status());
        assertEquals(PolicyFailureReason.REVOCATION_STATUS_UNKNOWN, result.failureReason());
    }

    @Test
    void testPolicy_RevocationProviderError_ReturnsPolicyUnknown() {
        revocations = ref -> { throw new RuntimeException("revocation service unavailable"); };
        verifier = new ApprovalEnvelopeVerifier(trustAnchors, revocations, clock, environmentGuard);
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_UNKNOWN, result.status());
        assertEquals(PolicyFailureReason.REVOCATION_PROVIDER_ERROR, result.failureReason());
    }

    @Test
    void testPolicy_TamperedSignature_ReturnsPolicyDrift() throws Exception {
        PolicyApprovalEnvelope validEnvelope = sourceStorage.get(TENANT + ":" + POLICY_ID_PURGE + ":" + ELEMENT_AI_LOG + ":" + ACTION_PURGE + ":" + PROVIDER_DB).currentEnvelope();
        byte[] tamperedSig = validEnvelope.signature();
        tamperedSig[0] ^= (byte) 0xFF; // 1バイト改竄
        PolicyApprovalEnvelope tamperedEnvelope = new PolicyApprovalEnvelope(
                validEnvelope.metadata(), validEnvelope.policyId(), validEnvelope.policyVersion(),
                validEnvelope.dataElementId(), validEnvelope.action(), validEnvelope.providerId(),
                validEnvelope.purpose(), validEnvelope.trigger(), validEnvelope.retentionRule(),
                validEnvelope.disposition(), tamperedSig
        );
        String digest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(tamperedEnvelope));
        sourceStorage.put(TENANT + ":" + POLICY_ID_PURGE + ":" + ELEMENT_AI_LOG + ":" + ACTION_PURGE + ":" + PROVIDER_DB,
                new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE, tamperedEnvelope, "rev-1", digest));
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_DRIFT, result.status());
        assertEquals(PolicyFailureReason.SIGNATURE_INVALID, result.failureReason());
    }

    @Test
    void testPolicy_SodViolation_ReturnsPolicyDrift() throws Exception {
        PolicyApprovalEnvelope envelope = createSignedPolicy(POLICY_ID_PURGE, "rev-1", ELEMENT_AI_LOG, ACTION_PURGE, PROVIDER_DB, SCOPE_AI,
                NOW.minusSeconds(600), NOW.minusSeconds(300), NOW.plusSeconds(1800), "same-user", "same-user");
        String digest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(envelope));
        sourceStorage.put(TENANT + ":" + POLICY_ID_PURGE + ":" + ELEMENT_AI_LOG + ":" + ACTION_PURGE + ":" + PROVIDER_DB,
                new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE, envelope, "rev-1", digest));
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_DRIFT, result.status());
        assertEquals(PolicyFailureReason.SOD_VIOLATION, result.failureReason());
    }

    @Test
    void testPolicy_MismatchedBinding_ReturnsPolicyDrift() throws Exception {
        // カタログでは local-db を期待しているが、エンベロープ側が gemini-adapter に署名されている
        PolicyApprovalEnvelope envelope = createSignedPolicy(POLICY_ID_PURGE, "rev-1", ELEMENT_AI_LOG, ACTION_PURGE, PROVIDER_GEMINI, SCOPE_AI,
                NOW.minusSeconds(600), NOW.minusSeconds(300), NOW.plusSeconds(1800), "req-1", "app-2");
        String digest = ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBytes(envelope));
        sourceStorage.put(TENANT + ":" + POLICY_ID_PURGE + ":" + ELEMENT_AI_LOG + ":" + ACTION_PURGE + ":" + PROVIDER_DB,
                new PolicyLookupResult(PolicyLookupResult.LookupStatus.CURRENT_CANDIDATE, envelope, "rev-1", digest));
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.POLICY_DRIFT, result.status());
        assertEquals(PolicyFailureReason.PROVIDER_MISMATCH, result.failureReason());
    }

    @Test
    void testEnvironment_ProdProfile_ThrowsSecurityException() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        SyntheticEnvironmentGuard prodGuard = new SyntheticEnvironmentGuard(prodEnv);
        PolicyReadonlyQueryService service = new PolicyReadonlyQueryService(callerProvider, catalog, policySource, verifier, prodGuard, clock);

        assertThrows(SecurityException.class, () -> service.evaluate(defaultQuery));
    }

    // =========================================================================
    // 9.4 正向テスト（成功しても実行権限を与えないことの証明）
    // =========================================================================

    @Test
    void testValidSyntheticPolicy_ReturnsApprovedActiveWithoutExecutionCapability() {
        PolicyReadonlyQueryService service = createService();

        PolicyExplanationResult result = service.evaluate(defaultQuery);
        assertEquals(PolicyDecisionStatus.APPROVED_ACTIVE, result.status());
        assertEquals(PolicyFailureReason.NONE, result.failureReason());
        assertEquals("1.0", result.policyVersion());
        assertNotNull(result.policyDigest());
        assertEquals(ELEMENT_AI_LOG, result.dataElementId());
        assertEquals(ACTION_PURGE, result.targetAction());
        assertEquals(PROVIDER_DB, result.targetProvider());
        assertEquals(SCOPE_AI, result.scope());
        assertTrue(result.effectiveFrom().isPresent());
        assertTrue(result.expiresAt().isPresent());
        assertNotNull(result.explanationReason());

        // 実行 Capability や権限フラグが存在しないことを確認
        assertFalse(result.getClass().toGenericString().contains("Permitted"));
    }
}
