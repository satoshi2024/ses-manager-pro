package com.ses.privacy.foundation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF07-NF08-LINK の内部判定 port（{@link ProductionPolicyEnforcementService}）。
 *
 * <p>flag OFF・UNKNOWN・REVOKED・EXPIRED・DRIFT・policy ID の不一致を全て拒否し、全ての前提を満たしたときだけ許可する。
 * 鍵は test-only（実行のたびに memory 上で生成）で、envelope の値は合成値（法的な値ではない）。呼出し元の認証・privacy 権限を
 * 一切設定しない（内部判定は tenant だけを束縛する）。
 */
class ProductionPolicyEnforcementServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T03:00:00Z");
    private static final String TENANT = "tenant-a";
    private static final String MODULE = "NF08_AI_SUMMARY";
    private static final String ELEMENT = "AI_PROMPT_LOG";
    private static final String ACTION = "EXTERNAL_DISPATCH";
    private static final String PROVIDER = "gemini-adapter";
    private static final String POLICY_ID = "policy-test-1";
    private static final String REVISION = "r1";
    private static final String ANCHOR_KEY = "authority-test:key-test";

    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicInteger catalogReads = new AtomicInteger();
    private final AtomicInteger sourceReads = new AtomicInteger();
    private final AtomicInteger trustReads = new AtomicInteger();
    private final AtomicInteger revocationReads = new AtomicInteger();

    private Optional<ProductionCatalogBinding> catalogResult;
    private CurrentPolicySnapshot sourceResult;
    private Map<String, TrustedAuthority> anchors;
    private Map<String, RevocationStatus> revocationStatuses;
    private RevocationStatus defaultRevocation;
    private Clock clock;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        catalogResult = Optional.of(binding(POLICY_ID));
        sourceResult = CurrentPolicySnapshot.of(CurrentPolicySnapshot.CurrentStatus.NOT_FOUND);
        anchors = Map.of();
        revocationStatuses = Map.of();
        defaultRevocation = RevocationStatus.UNKNOWN;
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 生成

    @Test
    void 構築時はflag_catalog_source_trust_失効状態のいずれも読まずprod以外では生成できない() {
        AtomicInteger flagReads = new AtomicInteger();
        ProductionPrivacyComponents.productionPolicyEnforcement(prod(), () -> {
            flagReads.incrementAndGet();
            return true;
        }, key -> fail("catalog"), (key, binding) -> fail("source"), () -> fail("trust"),
                reference -> fail("revocation"), clock);
        assertEquals(0, flagReads.get());

        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        for (MockEnvironment environment : java.util.List.of(dev, new MockEnvironment())) {
            assertThrows(SecurityException.class, () -> ProductionPrivacyComponents.productionPolicyEnforcement(
                    environment, () -> true, key -> Optional.empty(), (key, binding) -> null, Map::of,
                    reference -> RevocationStatus.ACTIVE, clock));
        }
    }

    // ---------------------------------------------------------------- flag OFF

    @Test
    void flag_OFFでは何も読まずRETENTION_DISABLEDで拒否する() throws Exception {
        allSatisfied();
        enabled.set(false);

        assertDenied(port().check(request(POLICY_ID)), PolicyEnforcementReason.RETENTION_DISABLED,
                PolicyFailureReason.NONE);
        assertEquals(0, catalogReads.get() + sourceReads.get() + trustReads.get() + revocationReads.get());
    }

    @Test
    void flagの判定部品が例外を投げてもOFFとして拒否する() throws Exception {
        allSatisfied();
        PolicyEnforcementPort port = ProductionPrivacyComponents.productionPolicyEnforcement(prod(), () -> {
            throw new IllegalStateException("flag unavailable");
        }, this::catalog, this::source, this::trust, this::revocation, clock);

        assertDenied(port.check(request(POLICY_ID)), PolicyEnforcementReason.RETENTION_DISABLED,
                PolicyFailureReason.NONE);
        assertEquals(0, catalogReads.get());
    }

    // ---------------------------------------------------------------- 要求の検査

    @Test
    void 不完全な要求はcatalogを読まずREQUEST_INVALIDで拒否する() throws Exception {
        allSatisfied();
        PolicyEnforcementPort port = port();

        assertDenied(port.check(null), PolicyEnforcementReason.REQUEST_INVALID, PolicyFailureReason.NONE);
        for (PolicyEnforcementRequest invalid : java.util.List.of(
                new PolicyEnforcementRequest(TENANT, MODULE, ELEMENT, ACTION, PROVIDER, null),
                new PolicyEnforcementRequest(TENANT, MODULE, ELEMENT, ACTION, PROVIDER, " "),
                new PolicyEnforcementRequest(null, MODULE, ELEMENT, ACTION, PROVIDER, POLICY_ID),
                new PolicyEnforcementRequest(" ", MODULE, ELEMENT, ACTION, PROVIDER, POLICY_ID),
                new PolicyEnforcementRequest(TENANT, null, ELEMENT, ACTION, PROVIDER, POLICY_ID),
                new PolicyEnforcementRequest(TENANT, MODULE, "", ACTION, PROVIDER, POLICY_ID),
                new PolicyEnforcementRequest(TENANT, MODULE, ELEMENT, null, PROVIDER, POLICY_ID),
                new PolicyEnforcementRequest(TENANT, MODULE, ELEMENT, ACTION, "", POLICY_ID))) {
            assertDenied(port.check(invalid), PolicyEnforcementReason.REQUEST_INVALID, PolicyFailureReason.NONE);
        }
        assertEquals(0, catalogReads.get());
    }

    // ---------------------------------------------------------------- UNKNOWN

    @Test
    void catalog未登録と照会失敗はsourceを読まずPOLICY_UNKNOWNで拒否する() throws Exception {
        allSatisfied();
        catalogResult = Optional.empty();
        assertDenied(port().check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_UNKNOWN,
                PolicyFailureReason.POLICY_NOT_FOUND);

        PolicyEnforcementPort failing = ProductionPrivacyComponents.productionPolicyEnforcement(prod(), () -> true,
                key -> {
                    throw new IllegalStateException("db down");
                }, this::source, this::trust, this::revocation, clock);
        assertDenied(failing.check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_UNKNOWN,
                PolicyFailureReason.STORE_UNAVAILABLE);
        assertEquals(0, sourceReads.get());
    }

    @Test
    void sourceの0件_未承認_競合_不達はPOLICY_UNKNOWNで拒否する() {
        for (CurrentPolicySnapshot.CurrentStatus status : java.util.List.of(
                CurrentPolicySnapshot.CurrentStatus.NOT_FOUND, CurrentPolicySnapshot.CurrentStatus.UNAPPROVED,
                CurrentPolicySnapshot.CurrentStatus.CONFLICT, CurrentPolicySnapshot.CurrentStatus.UNAVAILABLE)) {
            sourceResult = CurrentPolicySnapshot.of(status);
            PolicyEnforcementDecision decision = port().check(request(POLICY_ID));
            assertEquals(PolicyEnforcementReason.POLICY_UNKNOWN, decision.reason(), status.name());
            assertFalse(decision.permitted());
        }
        sourceResult = null;
        assertDenied(port().check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_UNKNOWN,
                PolicyFailureReason.POLICY_NOT_FOUND);
        PolicyEnforcementPort failing = ProductionPrivacyComponents.productionPolicyEnforcement(prod(), () -> true,
                this::catalog, (key, binding) -> {
                    throw new IllegalStateException("db down");
                }, this::trust, this::revocation, clock);
        assertDenied(failing.check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_UNKNOWN,
                PolicyFailureReason.STORE_UNAVAILABLE);
    }

    /** 第1期の状態: anchor が登録されても失効 provider が UNKNOWN なら許可しない。 */
    @Test
    void 失効状態がUNKNOWNならPOLICY_UNKNOWNで拒否する() throws Exception {
        allSatisfied();
        defaultRevocation = RevocationStatus.UNKNOWN;
        revocationStatuses = Map.of();

        assertDenied(port().check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_UNKNOWN,
                PolicyFailureReason.REVOCATION_STATUS_UNKNOWN);
        assertTrue(revocationReads.get() > 0);
    }

    @Test
    void 失効providerの障害はPOLICY_UNKNOWNで拒否する() throws Exception {
        allSatisfied();
        PolicyEnforcementPort port = ProductionPrivacyComponents.productionPolicyEnforcement(prod(), () -> true,
                this::catalog, this::source, this::trust, reference -> {
                    throw new IllegalStateException("ocsp down");
                }, clock);

        assertDenied(port.check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_UNKNOWN,
                PolicyFailureReason.REVOCATION_PROVIDER_ERROR);
    }

    // ---------------------------------------------------------------- REVOKED

    @Test
    void envelopeまたは署名鍵の失効はPOLICY_REVOKEDで拒否する() throws Exception {
        Signed signed = allSatisfied();
        revocationStatuses = Map.of(ANCHOR_KEY, RevocationStatus.ACTIVE,
                Signed.REVOCATION_REFERENCE, RevocationStatus.REVOKED);
        assertDenied(port().check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_REVOKED,
                PolicyFailureReason.ENVELOPE_REVOKED);

        revocationStatuses = Map.of(ANCHOR_KEY, RevocationStatus.REVOKED,
                Signed.REVOCATION_REFERENCE, RevocationStatus.ACTIVE);
        assertDenied(port().check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_REVOKED,
                PolicyFailureReason.KEY_REVOKED);
        assertEquals(POLICY_ID, signed.policyId());
    }

    // ---------------------------------------------------------------- EXPIRED

    @Test
    void policyの期限切れと未発効はPOLICY_EXPIREDで拒否する() throws Exception {
        allSatisfied();
        clock = Clock.fixed(NOW.plus(Duration.ofDays(31)), ZoneOffset.UTC);
        assertDenied(port().check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_EXPIRED,
                PolicyFailureReason.EXPIRED_AT_OR_AFTER_DEADLINE);

        clock = Clock.fixed(NOW.minus(Duration.ofHours(2)), ZoneOffset.UTC);
        PolicyEnforcementDecision early = port().check(request(POLICY_ID));
        assertEquals(PolicyEnforcementReason.POLICY_EXPIRED, early.reason());
        assertEquals(PolicyFailureReason.NOT_YET_ISSUED, early.detail());
    }

    // ---------------------------------------------------------------- DRIFT

    @Test
    void trust_registryが空なら正しい署名でもPOLICY_DRIFTで拒否する() throws Exception {
        allSatisfied();
        anchors = Map.of();

        assertDenied(port().check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_DRIFT,
                PolicyFailureReason.UNKNOWN_TRUST_ANCHOR);
        assertEquals(0, revocationReads.get());
    }

    @Test
    void 署名の改竄_digestの不一致_currentとcatalogのpolicyIdの不一致はPOLICY_DRIFTで拒否する() throws Exception {
        Signed signed = allSatisfied();

        byte[] tampered = signed.signature();
        tampered[0] ^= 1;
        sourceResult = CurrentPolicySnapshot.current(POLICY_ID, REVISION, signed.digest(), signed.canonical(), tampered);
        assertDenied(port().check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_DRIFT,
                PolicyFailureReason.SIGNATURE_INVALID);

        sourceResult = CurrentPolicySnapshot.current(POLICY_ID, REVISION, "0".repeat(64), signed.canonical(),
                signed.signature());
        assertDenied(port().check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_DRIFT,
                PolicyFailureReason.DIGEST_MISMATCH);

        sourceResult = CurrentPolicySnapshot.current("policy-other", REVISION, signed.digest(), signed.canonical(),
                signed.signature());
        assertDenied(port().check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_DRIFT,
                PolicyFailureReason.POLICY_ID_MISMATCH);
    }

    @Test
    void catalogのbindingがkeyと違えばsourceを読まずPOLICY_DRIFTで拒否する() throws Exception {
        allSatisfied();
        catalogResult = Optional.of(new ProductionCatalogBinding(POLICY_ID, MODULE,
                ProductionCatalogBinding.TENANT_WIDE_ACTOR_BINDING, "OTHER_ELEMENT", ACTION, PROVIDER,
                PolicyLookupKey.TENANT_WIDE));
        assertDenied(port().check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_DRIFT,
                PolicyFailureReason.DATA_ELEMENT_MISMATCH);

        catalogResult = Optional.of(new ProductionCatalogBinding(POLICY_ID, MODULE, "ACTOR:17", ELEMENT, ACTION,
                PROVIDER, PolicyLookupKey.TENANT_WIDE));
        assertDenied(port().check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_DRIFT,
                PolicyFailureReason.SCOPE_MISMATCH);
        assertEquals(0, sourceReads.get());
    }

    @Test
    void 別tenantのenvelopeはPOLICY_DRIFTで拒否する() throws Exception {
        allSatisfied();
        Signed other = signed("tenant-b");
        sourceResult = CurrentPolicySnapshot.current(POLICY_ID, REVISION, other.digest(), other.canonical(),
                other.signature());
        anchors = Map.of(ANCHOR_KEY, other.anchor());

        PolicyEnforcementDecision decision = port().check(request(POLICY_ID));

        assertFalse(decision.permitted());
        assertEquals(PolicyEnforcementReason.POLICY_DRIFT, decision.reason());
    }

    // ---------------------------------------------------------------- policy ID の不一致

    @Test
    void 承認に記載したpolicyIdとcurrentが違えばsourceを読まずPOLICY_ID_MISMATCHで拒否する() throws Exception {
        allSatisfied();

        assertDenied(port().check(request("policy-test-2")), PolicyEnforcementReason.POLICY_ID_MISMATCH,
                PolicyFailureReason.NONE);
        assertDenied(port().check(request("POLICY-TEST-1")), PolicyEnforcementReason.POLICY_ID_MISMATCH,
                PolicyFailureReason.NONE);
        assertEquals(0, sourceReads.get(), "policy IDの不一致はsourceを読む前に拒否する");
    }

    // ---------------------------------------------------------------- 許可

    /** 対照: 全ての前提が揃った fake でだけ許可する（検査が空振りしていないことの確認。本番の証明ではない）。 */
    @Test
    void 全ての前提が揃ったときだけ許可し呼出し元の認証やprivacy権限を要求しない() throws Exception {
        allSatisfied();
        assertTrue(SecurityContextHolder.getContext().getAuthentication() == null);

        PolicyEnforcementDecision decision = port().check(request(POLICY_ID));

        assertTrue(decision.permitted());
        assertEquals(PolicyEnforcementReason.PERMITTED, decision.reason());
        assertEquals(PolicyFailureReason.NONE, decision.detail());
        assertEquals(1, catalogReads.get());
        assertEquals(1, sourceReads.get());

        // 判定は cache しない: 次の判定で失効させれば即時に拒否する
        revocationStatuses = Map.of(ANCHOR_KEY, RevocationStatus.ACTIVE,
                Signed.REVOCATION_REFERENCE, RevocationStatus.REVOKED);
        assertDenied(port().check(request(POLICY_ID)), PolicyEnforcementReason.POLICY_REVOKED,
                PolicyFailureReason.ENVELOPE_REVOKED);
    }

    @Test
    void 失敗理由はどれも許可に分類されない() {
        for (PolicyFailureReason reason : PolicyFailureReason.values()) {
            PolicyEnforcementReason classified = ProductionPolicyEnforcementService.classify(
                    ProductionPolicyReadonlyQueryService.mapStatus(reason));
            assertNotEquals(PolicyEnforcementReason.PERMITTED, classified, reason.name());
        }
        for (PolicyDecisionStatus status : PolicyDecisionStatus.values()) {
            assertNotEquals(PolicyEnforcementReason.PERMITTED, ProductionPolicyEnforcementService.classify(status),
                    status.name());
        }
    }

    @Test
    void 判定結果は可否と理由コードの組を矛盾させない() {
        assertThrows(IllegalArgumentException.class, () -> new PolicyEnforcementDecision(true,
                PolicyEnforcementReason.POLICY_UNKNOWN, PolicyFailureReason.NONE));
        assertThrows(IllegalArgumentException.class, () -> new PolicyEnforcementDecision(false,
                PolicyEnforcementReason.PERMITTED, PolicyFailureReason.NONE));
        assertThrows(IllegalArgumentException.class, () -> new PolicyEnforcementDecision(true,
                PolicyEnforcementReason.PERMITTED, PolicyFailureReason.SIGNATURE_INVALID));
        assertEquals(PolicyFailureReason.NONE,
                PolicyEnforcementDecision.deny(PolicyEnforcementReason.POLICY_DRIFT, null).detail());
    }

    // ---------------------------------------------------------------- helper

    /** 全ての前提を揃える（flag ON・catalog・current・anchor・失効状態 ACTIVE）。 */
    private Signed allSatisfied() throws Exception {
        Signed signed = signed(TENANT);
        enabled.set(true);
        catalogResult = Optional.of(binding(POLICY_ID));
        sourceResult = CurrentPolicySnapshot.current(POLICY_ID, REVISION, signed.digest(), signed.canonical(),
                signed.signature());
        anchors = Map.of(ANCHOR_KEY, signed.anchor());
        revocationStatuses = Map.of();
        defaultRevocation = RevocationStatus.ACTIVE;
        return signed;
    }

    private PolicyEnforcementPort port() {
        return ProductionPrivacyComponents.productionPolicyEnforcement(prod(), enabled::get, this::catalog,
                this::source, this::trust, this::revocation, clock);
    }

    private Optional<ProductionCatalogBinding> catalog(PolicyLookupKey key) {
        catalogReads.incrementAndGet();
        return catalogResult;
    }

    private CurrentPolicySnapshot source(PolicyLookupKey key, ProductionCatalogBinding binding) {
        sourceReads.incrementAndGet();
        return sourceResult;
    }

    private Map<String, TrustedAuthority> trust() {
        trustReads.incrementAndGet();
        return anchors;
    }

    private RevocationStatus revocation(String reference) {
        revocationReads.incrementAndGet();
        return revocationStatuses.getOrDefault(reference, defaultRevocation);
    }

    private static MockEnvironment prod() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    private static PolicyEnforcementRequest request(String expectedPolicyId) {
        return new PolicyEnforcementRequest(TENANT, MODULE, ELEMENT, ACTION, PROVIDER, expectedPolicyId);
    }

    private static ProductionCatalogBinding binding(String policyId) {
        return new ProductionCatalogBinding(policyId, MODULE, ProductionCatalogBinding.TENANT_WIDE_ACTOR_BINDING,
                ELEMENT, ACTION, PROVIDER, PolicyLookupKey.TENANT_WIDE);
    }

    private static <T> T fail(String message) {
        throw new AssertionError(message + " は構築時に読まない");
    }

    private static void assertDenied(PolicyEnforcementDecision decision, PolicyEnforcementReason reason,
                                     PolicyFailureReason detail) {
        assertFalse(decision.permitted());
        assertEquals(reason, decision.reason());
        assertEquals(detail, decision.detail());
    }

    /** test 専用の鍵で署名した policy envelope（値は合成。法的な値ではない）。 */
    private record Signed(byte[] canonical, byte[] signature, String digest, TrustedAuthority anchor,
                          String policyId) {
        static final String REVOCATION_REFERENCE = "revocation-test";

        @Override
        public byte[] canonical() {
            return canonical.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }

    private static Signed signed(String tenantId) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ApprovalEnvelopeMetadata metadata = new ApprovalEnvelopeMetadata("1", "authority-test", "key-test", tenantId,
                MODULE, ProductionEnvironmentGuard.PRODUCTION_AUDIENCE, REVISION, "requester-test", "approver-test",
                Signed.REVOCATION_REFERENCE, NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofDays(30)));
        PolicyApprovalEnvelope unsigned = new PolicyApprovalEnvelope(metadata, POLICY_ID, "v1", ELEMENT, ACTION,
                PROVIDER, "TEST_PURPOSE", "TEST_TRIGGER", "TEST_ONLY_RULE", "TEST_ONLY_DISPOSITION", new byte[]{0});
        byte[] canonical = ApprovalEnvelopeCanonicalizer.canonicalBytes(unsigned);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(canonical);
        byte[] signature = signer.sign();
        TrustedAuthority anchor = new TrustedAuthority("authority-test", "key-test", keyPair.getPublic(),
                Set.of(tenantId), Set.of(MODULE), Set.of(ELEMENT), Set.of("POLICY"),
                Set.of(ProductionEnvironmentGuard.PRODUCTION_AUDIENCE), Set.of(ACTION), Set.of(PROVIDER),
                NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(365)));
        return new Signed(canonical, signature, ApprovalEnvelopeCanonicalizer.digest(canonical), anchor, POLICY_ID);
    }
}
