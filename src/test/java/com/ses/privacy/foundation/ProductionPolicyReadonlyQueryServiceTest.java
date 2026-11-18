package com.ses.privacy.foundation;

import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.LegalEntityContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * production の読み取り専用 policy 照会（W3-14、Plan Review 注意事項8、policy-source-catalog-wiring-plan.md §4）。
 *
 * <p>fake port だけで契約を確認する。本 test が通っても本番の currentness（P-SRC）は証明しない。
 */
class ProductionPolicyReadonlyQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T03:00:00Z");
    private static final String TENANT = "tenant-a";
    private static final String MODULE = "NF08_AI_SUMMARY";
    private static final String ELEMENT = "AI_PROMPT_LOG";
    private static final String ACTION = "EXTERNAL_DISPATCH";
    private static final String PROVIDER = "gemini-adapter";
    private static final String POLICY_ID = "policy-test-1";
    private static final String REVISION = "r1";

    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicInteger flagReads = new AtomicInteger();
    private final AtomicInteger catalogReads = new AtomicInteger();
    private final AtomicInteger sourceReads = new AtomicInteger();
    private final AtomicInteger trustReads = new AtomicInteger();
    private final AtomicInteger revocationReads = new AtomicInteger();

    private SysUserMapper sysUserMapper;
    private LegalEntityContextService legalEntityContext;
    private AuthorizationService authorizationService;
    private Optional<ProductionCatalogBinding> catalogResult;
    private CurrentPolicySnapshot sourceResult;
    private Map<String, TrustedAuthority> anchors = Map.of();
    private RevocationStatus revocationStatus = RevocationStatus.UNKNOWN;

    @BeforeEach
    void setUp() {
        sysUserMapper = mock(SysUserMapper.class);
        legalEntityContext = mock(LegalEntityContextService.class);
        authorizationService = mock(AuthorizationService.class);
        when(sysUserMapper.selectByIdAndTenant(17L, TENANT)).thenReturn(admin());
        when(legalEntityContext.requireCurrentLegalEntityId()).thenReturn(42L);
        when(authorizationService.isAllowed(any(), eq("privacy.policy.view"))).thenReturn(true);
        catalogResult = Optional.of(binding());
        sourceResult = CurrentPolicySnapshot.of(CurrentPolicySnapshot.CurrentStatus.NOT_FOUND);
        authenticateAdmin();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 構築と公開スイッチ

    @Test
    void 構築時はflag_catalog_source_trust_caller_DBのいずれも読まない() {
        PolicyReadonlyQueryPort port = port(prod());

        assertTrue(port instanceof ProductionPolicyReadonlyQueryService);
        assertEquals(0, flagReads.get());
        assertEquals(0, catalogReads.get());
        assertEquals(0, sourceReads.get());
        assertEquals(0, trustReads.get());
        verifyNoInteractions(sysUserMapper, legalEntityContext, authorizationService);
    }

    @Test
    void prod以外のprofileではproduction照会を生成できない() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThrows(SecurityException.class, () -> port(dev));
        assertThrows(SecurityException.class, () -> port(new MockEnvironment()));
        assertEquals(0, catalogReads.get());
    }

    @Test
    void flag_OFFではsourceを含め何も読まずPOLICY_UNKNOWNを返す() {
        enabled.set(false);
        PolicyReadonlyQueryPort port = port(prod());

        PolicyExplanationResult result = port.evaluate(query());

        assertEquals(PolicyDecisionStatus.POLICY_UNKNOWN, result.status());
        assertEquals(PolicyFailureReason.STORE_UNAVAILABLE, result.failureReason());
        assertEquals(ProductionPolicyReadonlyQueryService.SOURCE_DISABLED_EXPLANATION, result.explanationReason());
        assertEquals(1, flagReads.get());
        assertEquals(0, catalogReads.get(), "catalogの読取は0回");
        assertEquals(0, sourceReads.get(), "sourceの読取は0回");
        assertEquals(0, trustReads.get());
        verifyNoInteractions(sysUserMapper, legalEntityContext, authorizationService);
    }

    @Test
    void flagの判定部品が例外を投げてもOFFとして扱う() {
        PolicyReadonlyQueryPort port = ProductionPrivacyComponents.productionPolicyReadonlyQuery(prod(),
                () -> {
                    throw new IllegalStateException("broken flag");
                }, legalEntityContext, sysUserMapper, () -> authorizationService, TENANT,
                key -> fail("catalogを読まない"), (key, binding) -> fail("sourceを読まない"),
                () -> fail("trustを読まない"), reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(PolicyDecisionStatus.POLICY_UNKNOWN, port.evaluate(query()).status());
    }

    // ---------------------------------------------------------------- 呼び出し元（source照会より前）

    @Test
    void 認証が無い_privacy権限が無い_tenantやactorが違う照会はsourceを読まずACCESS_DENIEDにする() {
        PolicyReadonlyQueryPort port = port(prod());

        SecurityContextHolder.clearContext();
        assertDenied(port.evaluate(query()), PolicyFailureReason.MISSING_CALLER_IDENTITY);

        authenticateAdmin();
        when(authorizationService.isAllowed(any(), eq("privacy.policy.view"))).thenReturn(false);
        assertDenied(port.evaluate(query()), PolicyFailureReason.MISSING_CALLER_IDENTITY);

        when(authorizationService.isAllowed(any(), eq("privacy.policy.view"))).thenReturn(true);
        assertDenied(port.evaluate(new PolicyEvaluationQuery("tenant-b", "17", MODULE, ELEMENT, ACTION, PROVIDER)),
                PolicyFailureReason.CALLER_TENANT_MISMATCH);
        assertDenied(port.evaluate(new PolicyEvaluationQuery(TENANT, "18", MODULE, ELEMENT, ACTION, PROVIDER)),
                PolicyFailureReason.CALLER_ACTOR_MISMATCH);
        assertEquals(0, catalogReads.get());
        assertEquals(0, sourceReads.get());
    }

    // ---------------------------------------------------------------- catalog・source

    @Test
    void catalog未登録と照会失敗はsourceを読まずPOLICY_UNKNOWNにする() {
        catalogResult = Optional.empty();
        assertUnknown(port(prod()).evaluate(query()), PolicyFailureReason.POLICY_NOT_FOUND);
        assertEquals(0, sourceReads.get());

        PolicyReadonlyQueryPort failing = ProductionPrivacyComponents.productionPolicyReadonlyQuery(prod(),
                () -> true, legalEntityContext, sysUserMapper, () -> authorizationService, TENANT,
                key -> {
                    throw new IllegalStateException("catalog down");
                }, (key, binding) -> fail("sourceを読まない"), () -> fail("trustを読まない"),
                reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC));
        PolicyExplanationResult result = failing.evaluate(query());
        assertUnknown(result, PolicyFailureReason.STORE_UNAVAILABLE);
        assertTrue(!result.explanationReason().contains("catalog down"), "例外の詳細を返さない");
    }

    @Test
    void catalogのbindingがkeyと違えばsourceを読まずPOLICY_DRIFTにする() {
        catalogResult = Optional.of(new ProductionCatalogBinding(POLICY_ID, MODULE,
                ProductionCatalogBinding.TENANT_WIDE_ACTOR_BINDING, "OTHER_ELEMENT", ACTION, PROVIDER, 0L));
        assertDrift(port(prod()).evaluate(query()), PolicyFailureReason.DATA_ELEMENT_MISMATCH);

        catalogResult = Optional.of(new ProductionCatalogBinding(POLICY_ID, MODULE,
                ProductionCatalogBinding.TENANT_WIDE_ACTOR_BINDING, ELEMENT, ACTION, PROVIDER, 3L));
        assertDrift(port(prod()).evaluate(query()), PolicyFailureReason.SCOPE_MISMATCH);

        catalogResult = Optional.of(new ProductionCatalogBinding(POLICY_ID, MODULE, "ACTOR:17",
                ELEMENT, ACTION, PROVIDER, 0L));
        assertDrift(port(prod()).evaluate(query()), PolicyFailureReason.SCOPE_MISMATCH);
        assertEquals(0, sourceReads.get());
    }

    @Test
    void sourceの0件_未承認_競合_不達_例外は全てPOLICY_UNKNOWNに閉じる() {
        PolicyReadonlyQueryPort port = port(prod());
        sourceResult = CurrentPolicySnapshot.of(CurrentPolicySnapshot.CurrentStatus.NOT_FOUND);
        assertUnknown(port.evaluate(query()), PolicyFailureReason.POLICY_NOT_FOUND);
        sourceResult = CurrentPolicySnapshot.of(CurrentPolicySnapshot.CurrentStatus.UNAPPROVED);
        assertUnknown(port.evaluate(query()), PolicyFailureReason.POLICY_UNAPPROVED);
        sourceResult = CurrentPolicySnapshot.of(CurrentPolicySnapshot.CurrentStatus.CONFLICT);
        assertUnknown(port.evaluate(query()), PolicyFailureReason.STORE_UNAVAILABLE);
        sourceResult = CurrentPolicySnapshot.of(CurrentPolicySnapshot.CurrentStatus.UNAVAILABLE);
        assertUnknown(port.evaluate(query()), PolicyFailureReason.STORE_UNAVAILABLE);
        sourceResult = null;
        assertUnknown(port.evaluate(query()), PolicyFailureReason.POLICY_NOT_FOUND);
        assertEquals(5, sourceReads.get());
        assertEquals(0, trustReads.get());

        PolicyReadonlyQueryPort failing = ProductionPrivacyComponents.productionPolicyReadonlyQuery(prod(),
                () -> true, legalEntityContext, sysUserMapper, () -> authorizationService, TENANT,
                key -> Optional.of(binding()), (key, binding) -> {
                    throw new IllegalStateException("source down");
                }, () -> fail("trustを読まない"), reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC));
        assertUnknown(failing.evaluate(query()), PolicyFailureReason.STORE_UNAVAILABLE);
    }

    @Test
    void current行のpolicyIdとdigestが一致しなければ復号前にPOLICY_DRIFTにする() throws Exception {
        Signed signed = signed();
        PolicyReadonlyQueryPort port = port(prod());

        sourceResult = CurrentPolicySnapshot.current("other-policy", REVISION, signed.digest(), signed.canonical(),
                signed.signature());
        assertDrift(port.evaluate(query()), PolicyFailureReason.POLICY_ID_MISMATCH);

        sourceResult = CurrentPolicySnapshot.current(POLICY_ID, REVISION, "0".repeat(64), signed.canonical(),
                signed.signature());
        assertDrift(port.evaluate(query()), PolicyFailureReason.DIGEST_MISMATCH);
        assertEquals(0, trustReads.get());
    }

    @Test
    void 正規形でない保存値は復号で拒否しPOLICY_DRIFTにする() throws Exception {
        Signed signed = signed();
        PolicyReadonlyQueryPort port = port(prod());

        byte[] garbage = "not-a-canonical-envelope".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        sourceResult = CurrentPolicySnapshot.current(POLICY_ID, REVISION, ApprovalEnvelopeCanonicalizer.digest(garbage),
                garbage, signed.signature());
        assertDrift(port.evaluate(query()), PolicyFailureReason.UNSUPPORTED_SCHEMA_VERSION);

        byte[] trailing = java.util.Arrays.copyOf(signed.canonical(), signed.canonical().length + 1);
        sourceResult = CurrentPolicySnapshot.current(POLICY_ID, REVISION, ApprovalEnvelopeCanonicalizer.digest(trailing),
                trailing, signed.signature());
        assertDrift(port.evaluate(query()), PolicyFailureReason.UNSUPPORTED_SCHEMA_VERSION);
        assertEquals(0, trustReads.get());
    }

    // ---------------------------------------------------------------- 第1期の2つの独立した遮断

    @Test
    void trust_registryが空なら正しい署名でもUNKNOWN_TRUST_ANCHORでAPPROVED_ACTIVEにならない() throws Exception {
        Signed signed = signed();
        sourceResult = CurrentPolicySnapshot.current(POLICY_ID, REVISION, signed.digest(), signed.canonical(),
                signed.signature());
        revocationStatus = RevocationStatus.ACTIVE;

        PolicyExplanationResult result = port(prod()).evaluate(query());

        assertDrift(result, PolicyFailureReason.UNKNOWN_TRUST_ANCHOR);
        assertEquals(1, trustReads.get());
        assertEquals(0, revocationReads.get());
    }

    @Test
    void anchorが登録されても失効状態がUNKNOWNならPOLICY_UNKNOWNでAPPROVED_ACTIVEにならない() throws Exception {
        Signed signed = signed();
        sourceResult = CurrentPolicySnapshot.current(POLICY_ID, REVISION, signed.digest(), signed.canonical(),
                signed.signature());
        anchors = Map.of("authority-test:key-test", signed.anchor());
        revocationStatus = RevocationStatus.UNKNOWN;

        PolicyExplanationResult result = port(prod()).evaluate(query());

        assertUnknown(result, PolicyFailureReason.REVOCATION_STATUS_UNKNOWN);
        assertTrue(revocationReads.get() > 0);
    }

    @Test
    void trust_registryの障害はPOLICY_DRIFTに閉じる() throws Exception {
        Signed signed = signed();
        sourceResult = CurrentPolicySnapshot.current(POLICY_ID, REVISION, signed.digest(), signed.canonical(),
                signed.signature());
        PolicyReadonlyQueryPort port = ProductionPrivacyComponents.productionPolicyReadonlyQuery(prod(),
                () -> true, legalEntityContext, sysUserMapper, () -> authorizationService, TENANT,
                key -> Optional.of(binding()), (key, binding) -> sourceResult, () -> {
                    throw new IllegalStateException("kms down");
                }, reference -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC));

        assertDrift(port.evaluate(query()), PolicyFailureReason.UNKNOWN_TRUST_ANCHOR);
    }

    /** 対照: 全ての前提が揃った fake では説明だけを返す（検査が空振りしていないことの確認。本番の証明ではない）。 */
    @Test
    void 全ての前提が揃ったfakeでは説明metadataだけを返し判定が空振りしていない() throws Exception {
        Signed signed = signed();
        sourceResult = CurrentPolicySnapshot.current(POLICY_ID, REVISION, signed.digest(), signed.canonical(),
                signed.signature());
        anchors = Map.of("authority-test:key-test", signed.anchor());
        revocationStatus = RevocationStatus.ACTIVE;

        PolicyExplanationResult result = port(prod()).evaluate(query());

        assertEquals(PolicyDecisionStatus.APPROVED_ACTIVE, result.status());
        assertEquals(signed.digest(), result.policyDigest());

        // 1 byte でも署名が違えば拒否する
        byte[] tampered = signed.signature();
        tampered[0] ^= 1;
        sourceResult = CurrentPolicySnapshot.current(POLICY_ID, REVISION, signed.digest(), signed.canonical(), tampered);
        assertDrift(port(prod()).evaluate(query()), PolicyFailureReason.SIGNATURE_INVALID);
    }

    @Test
    void snapshotは保存bytesを防御的に複製する() throws Exception {
        Signed signed = signed();
        byte[] canonical = signed.canonical();
        CurrentPolicySnapshot snapshot = CurrentPolicySnapshot.current(POLICY_ID, REVISION, signed.digest(),
                canonical, signed.signature());
        canonical[0] ^= 1;
        assertArrayEquals(signed.canonical(), snapshot.envelopeCanonical());
        assertNotEquals(canonical[0], snapshot.envelopeCanonical()[0]);
        assertThrows(IllegalArgumentException.class,
                () -> CurrentPolicySnapshot.of(CurrentPolicySnapshot.CurrentStatus.CURRENT));
    }

    // ---------------------------------------------------------------- helper

    private PolicyReadonlyQueryPort port(MockEnvironment environment) {
        return ProductionPrivacyComponents.productionPolicyReadonlyQuery(environment,
                () -> {
                    flagReads.incrementAndGet();
                    return enabled.get();
                },
                legalEntityContext, sysUserMapper, () -> authorizationService, TENANT,
                key -> {
                    catalogReads.incrementAndGet();
                    return catalogResult;
                },
                (key, binding) -> {
                    sourceReads.incrementAndGet();
                    return sourceResult;
                },
                () -> {
                    trustReads.incrementAndGet();
                    return anchors;
                },
                reference -> {
                    revocationReads.incrementAndGet();
                    return revocationStatus;
                },
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MockEnvironment prod() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    private static PolicyEvaluationQuery query() {
        return new PolicyEvaluationQuery(TENANT, "17", MODULE, ELEMENT, ACTION, PROVIDER);
    }

    private static ProductionCatalogBinding binding() {
        return new ProductionCatalogBinding(POLICY_ID, MODULE, ProductionCatalogBinding.TENANT_WIDE_ACTOR_BINDING,
                ELEMENT, ACTION, PROVIDER, PolicyLookupKey.TENANT_WIDE);
    }

    private static SysUser admin() {
        SysUser user = new SysUser();
        user.setId(17L);
        user.setTenantId(TENANT);
        user.setRole("管理者");
        user.setStatus(1);
        user.setDeletedFlag(0);
        return user;
    }

    private static void authenticateAdmin() {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_管理者"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(admin(), authorities, TENANT), "test-credential", authorities));
    }

    private static <T> T fail(String message) {
        throw new AssertionError(message);
    }

    private static void assertDenied(PolicyExplanationResult result, PolicyFailureReason reason) {
        assertEquals(PolicyDecisionStatus.ACCESS_DENIED, result.status());
        assertEquals(reason, result.failureReason());
    }

    private static void assertUnknown(PolicyExplanationResult result, PolicyFailureReason reason) {
        assertEquals(PolicyDecisionStatus.POLICY_UNKNOWN, result.status(), result.explanationReason());
        assertEquals(reason, result.failureReason(), result.explanationReason());
    }

    private static void assertDrift(PolicyExplanationResult result, PolicyFailureReason reason) {
        assertEquals(PolicyDecisionStatus.POLICY_DRIFT, result.status(), result.explanationReason());
        assertEquals(reason, result.failureReason(), result.explanationReason());
    }

    /** test 専用の鍵で署名した policy envelope（値は合成。法的な値ではない）。 */
    private record Signed(byte[] canonical, byte[] signature, String digest, TrustedAuthority anchor) {
        @Override
        public byte[] canonical() {
            return canonical.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }

    private static Signed signed() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ApprovalEnvelopeMetadata metadata = new ApprovalEnvelopeMetadata("1", "authority-test", "key-test", TENANT,
                MODULE, ProductionEnvironmentGuard.PRODUCTION_AUDIENCE, REVISION, "requester-test", "approver-test",
                "revocation-test", NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofDays(30)));
        PolicyApprovalEnvelope unsigned = new PolicyApprovalEnvelope(metadata, POLICY_ID, "v1", ELEMENT, ACTION,
                PROVIDER, "TEST_PURPOSE", "TEST_TRIGGER", "TEST_ONLY_RULE", "TEST_ONLY_DISPOSITION", new byte[]{0});
        byte[] canonical = ApprovalEnvelopeCanonicalizer.canonicalBytes(unsigned);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(canonical);
        byte[] signature = signer.sign();
        TrustedAuthority anchor = new TrustedAuthority("authority-test", "key-test", keyPair.getPublic(),
                Set.of(TENANT), Set.of(MODULE), Set.of(ELEMENT), Set.of("POLICY"),
                Set.of(ProductionEnvironmentGuard.PRODUCTION_AUDIENCE), Set.of(ACTION), Set.of(PROVIDER),
                NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(365)));
        return new Signed(canonical, signature, ApprovalEnvelopeCanonicalizer.digest(canonical), anchor);
    }

    @Test
    void 失敗理由から判定statusへの対応は合成serviceと同じである() {
        for (PolicyFailureReason reason : PolicyFailureReason.values()) {
            PolicyDecisionStatus status = ProductionPolicyReadonlyQueryService.mapStatus(reason);
            if (reason == PolicyFailureReason.NONE) {
                assertEquals(PolicyDecisionStatus.APPROVED_ACTIVE, status);
            } else {
                assertNotEquals(PolicyDecisionStatus.APPROVED_ACTIVE, status, reason.name());
            }
        }
        verify(authorizationService, never()).isAllowed(any(), any());
    }
}
