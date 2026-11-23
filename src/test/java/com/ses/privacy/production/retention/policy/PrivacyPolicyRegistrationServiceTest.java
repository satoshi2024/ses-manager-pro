package com.ses.privacy.production.retention.policy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.SysUser;
import com.ses.entity.privacy.PiiDataElement;
import com.ses.entity.privacy.RetentionPolicy;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.PolicyDecisionStatus;
import com.ses.privacy.foundation.PolicyEvaluationQuery;
import com.ses.privacy.foundation.PolicyExplanationResult;
import com.ses.privacy.foundation.PolicyReadonlyQueryPort;
import com.ses.privacy.foundation.PolicyRegistrationVerifier;
import com.ses.privacy.foundation.ProductionPolicyTrustRegistry;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.foundation.RevocationStatus;
import com.ses.privacy.foundation.RevocationStatusProvider;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.service.AuditLogService;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.LegalEntityContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NF07-REG: 署名済み envelope の登録（verify-then-store）と、別の privacy-admin による current の切替を H2 上で確かめる
 * （production-bridge-phased-plan.md §4 の対象限定テスト: 改竄・未知 anchor・失効 UNKNOWN・SoD・二重 current の CAS）。
 *
 * <p>鍵は test-only（{@link PolicyEnvelopeTestSigner}。実行のたびに memory 上で生成）。verifier は prod の {@link MockEnvironment}
 * で {@link ProductionPrivacyComponents} から生成する。current の並行切替と lock の待機は {@code PrivacyPolicyRegistrationMySqlTest}
 * が実 MySQL で確かめる。各 test は専用の tenant の行だけを読み、test の transaction の rollback で消える。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PrivacyPolicyRegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final long REGISTRANT = 101L;
    private static final long ACTIVATOR = 102L;
    private static final String POLICY_ID = "nf08-ai-prompt-log";

    @Autowired
    private RetentionPolicyMapper policyMapper;
    @Autowired
    private PiiDataElementMapper dataElementMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private String tenant;
    private Clock clock;
    private PolicyEnvelopeTestSigner signer;
    private PrivacyApprovalProperties approval;
    private OidcSecurityProperties oidc;
    private PrivacyAccessGuard guard;
    private final List<String> auditUris = new ArrayList<>();
    private final AtomicReference<Function<String, RevocationStatus>> revocation =
            new AtomicReference<>(ref -> RevocationStatus.ACTIVE);
    private final AtomicReference<PrivacyPolicyRegistrationService.Actor> actor = new AtomicReference<>();
    private PolicyRegistrationVerifier verifier;
    private PrivacyPolicyRegistrationService service;

    @BeforeEach
    void setUp() {
        tenant = "nf07-policy-reg-" + UUID.randomUUID();
        clock = Clock.fixed(NOW, ZoneId.of("Asia/Tokyo"));
        signer = new PolicyEnvelopeTestSigner(tenant, NOW);
        approval = new PrivacyApprovalProperties();
        decision(approval.getD01(), "D01-NF07-TEST-001");
        decision(approval.getD02(), "D02-NF07-TEST-001");
        decision(approval.getD03(), "D03-NF07-TEST-001");
        oidc = new OidcSecurityProperties();
        oidc.setTenantId(tenant);
        guard = mock(PrivacyAccessGuard.class);
        registerElement(PolicyEnvelopeTestSigner.ELEMENT);
        verifier = verifierWith(signer.registry());
        service = serviceWith(verifier);
        as(REGISTRANT);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- 登録・有効化・説明照会（Demo） ---

    @Test
    void 署名済みenvelopeを検証して保存し別のprivacy_adminが有効化すると説明照会がAPPROVED_ACTIVEになる() {
        PolicyEnvelopeTestSigner.Signed signed = signer.sign(POLICY_ID, "r1");

        PrivacyPolicyRegistrationResult registered = service.register(signed.request());

        assertThat(registered.registrationStatus()).isEqualTo("CANDIDATE");
        assertThat(registered.replayed()).isFalse();
        RetentionPolicy row = policyMapper.selectById(registered.id());
        assertThat(row.getTenantId()).isEqualTo(tenant);
        assertThat(row.getPolicyId()).isEqualTo(POLICY_ID);
        assertThat(row.getPolicyRevision()).isEqualTo("r1");
        assertThat(row.getModuleCode()).isEqualTo(PolicyEnvelopeTestSigner.MODULE);
        assertThat(row.getLegalEntityId()).isNull();
        assertThat(row.getDataElementId()).isEqualTo(PolicyEnvelopeTestSigner.ELEMENT);
        assertThat(row.getTargetAction()).isEqualTo(PolicyEnvelopeTestSigner.ACTION);
        assertThat(row.getTargetProvider()).isEqualTo(PolicyEnvelopeTestSigner.PROVIDER);
        assertThat(row.getEnvelopeCanonical()).as("検証した bytes をそのまま保存する").isEqualTo(signed.canonical());
        assertThat(row.getEnvelopeSignature()).isEqualTo(signed.signature());
        assertThat(row.getEnvelopeDigest()).isEqualTo(signed.digest());
        assertThat(row.getAuthorityId()).isEqualTo(PolicyEnvelopeTestSigner.AUTHORITY);
        assertThat(row.getKeyId()).isEqualTo(PolicyEnvelopeTestSigner.KEY_ID);
        assertThat(row.getEnvelopeSchemaVersion()).isEqualTo(1);
        assertThat(row.getRegisteredBy()).isEqualTo(REGISTRANT);
        assertThat(row.getActivatedBy()).isNull();
        assertThat(auditUris).anyMatch(uri -> uri.startsWith("/api/privacy/policies#REGISTERED?id=" + registered.id()));

        as(ACTIVATOR);
        PrivacyPolicyRegistrationResult activated = service.activate(registered.id(),
                new PrivacyPolicyActivationRequest(null));

        assertThat(activated.registrationStatus()).isEqualTo("CURRENT");
        assertThat(activated.supersededId()).isNull();
        RetentionPolicy current = policyMapper.selectById(registered.id());
        assertThat(current.getStatus()).isEqualTo("CURRENT");
        assertThat(current.getActivatedBy()).isEqualTo(ACTIVATOR);
        assertThat(current.getActivatedAt()).isNotNull();
        assertThat(current.getRowVersion()).isEqualTo(1L);
        assertThat(auditUris).anyMatch(uri -> uri.contains("#ACTIVATED?id=" + registered.id()) && uri.contains("superseded=none"));

        PolicyExplanationResult explained = explain(ACTIVATOR);
        assertThat(explained.status()).as(explained.explanationReason()).isEqualTo(PolicyDecisionStatus.APPROVED_ACTIVE);
        assertThat(explained.policyDigest()).isEqualTo(signed.digest());
    }

    // --- 改竄・未知 anchor・失効 ---

    @Test
    void 改竄したenvelopeは検証で拒否し行を作らない() {
        PolicyEnvelopeTestSigner.Signed signed = signer.sign(POLICY_ID, "r1");
        byte[] altered = signer.sign(POLICY_ID, "r1", PolicyEnvelopeTestSigner.AUTHORITY, "requester-test",
                "approver-test", "TEST_ONLY_RULE_ALTERED").canonical();

        assertBusiness(() -> service.register(signed.withCanonical(altered)), 403, "error.privacy.policyEnvelopeRejected");
        assertThat(auditUris).anyMatch(uri -> uri.contains("#REJECTED?operation=REGISTER") && uri.contains("reason=SIGNATURE_INVALID"));

        byte[] trailing = Arrays.copyOf(signed.canonical(), signed.canonical().length + 1);
        assertBusiness(() -> service.register(signed.withCanonical(trailing)), 403, "error.privacy.policyEnvelopeRejected");

        byte[] forged = signed.signature();
        forged[0] ^= 0x01;
        assertBusiness(() -> service.register(new PrivacyPolicyRegistrationRequest(
                        Base64.getEncoder().encodeToString(signed.canonical()), Base64.getEncoder().encodeToString(forged))),
                403, "error.privacy.policyEnvelopeRejected");
        assertThat(rows()).isZero();
    }

    @Test
    void 保存した行のkeyやbytesを書き換えれば有効化で拒否しcurrentにしない() {
        PrivacyPolicyRegistrationResult registered = service.register(signer.sign(POLICY_ID, "r1").request());
        jdbc.update("UPDATE m_retention_policy SET policy_revision = 'r9' WHERE id = ?", registered.id());

        as(ACTIVATOR);
        assertBusiness(() -> service.activate(registered.id(), new PrivacyPolicyActivationRequest(null)), 403,
                "error.privacy.policyEnvelopeRejected");
        assertThat(auditUris).anyMatch(uri -> uri.contains("#REJECTED?operation=ACTIVATE") && uri.contains("reason=STORED_ROW_MISMATCH"));

        jdbc.update("UPDATE m_retention_policy SET policy_revision = 'r1', envelope_canonical = ? WHERE id = ?",
                signer.sign(POLICY_ID, "r1", PolicyEnvelopeTestSigner.AUTHORITY, "requester-test", "approver-test",
                        "TEST_ONLY_RULE_ALTERED").canonical(), registered.id());
        assertBusiness(() -> service.activate(registered.id(), new PrivacyPolicyActivationRequest(null)), 403,
                "error.privacy.policyEnvelopeRejected");
        assertThat(policyMapper.selectById(registered.id()).getStatus()).isEqualTo("CANDIDATE");
    }

    @Test
    void 未知のtrust_anchorの鍵で署名したenvelopeは拒否する() {
        PolicyEnvelopeTestSigner.Signed unknownAuthority = signer.sign(POLICY_ID, "r1", "unregistered-issuer",
                "requester-test", "approver-test", "TEST_ONLY_RULE");

        assertBusiness(() -> service.register(unknownAuthority.request()), 403, "error.privacy.policyEnvelopeRejected");
        assertThat(auditUris).anyMatch(uri -> uri.contains("reason=UNKNOWN_TRUST_ANCHOR"));

        // 登録済みの authority を名乗る別の鍵（同じ authority・key ID の未登録の鍵）
        PolicyEnvelopeTestSigner.Signed genuine = signer.sign(POLICY_ID, "r1");
        PolicyEnvelopeTestSigner.Signed foreignKey = new PolicyEnvelopeTestSigner.Signed(genuine.canonical(),
                PolicyEnvelopeTestSigner.sign(PolicyEnvelopeTestSigner.generate().getPrivate(),
                        com.ses.privacy.foundation.PolicyEnvelopeSigningStatement.of(genuine.canonical())));
        assertBusiness(() -> service.register(foreignKey.request()), 403, "error.privacy.policyEnvelopeRejected");
        assertThat(rows()).isZero();
    }

    @Test
    void 失効状態がUNKNOWNなら入口は開かずenvelopeの失効状態がUNKNOWNや失効なら登録を拒否する() {
        PolicyEnvelopeTestSigner.Signed signed = signer.sign(POLICY_ID, "r1");

        revocation.set(ref -> RevocationStatus.UNKNOWN);
        assertThat(service.isOpen()).isFalse();
        assertThat(service.readiness().missing()).containsExactly("trust");
        assertNotFound(() -> service.register(signed.request()));

        revocation.set(ref -> {
            throw new IllegalStateException("test: 失効 provider の障害");
        });
        assertNotFound(() -> service.register(signed.request()));

        // anchor は ACTIVE（入口は開く）が envelope の失効状態が UNKNOWN・REVOKED
        revocation.set(ref -> PolicyEnvelopeTestSigner.REVOCATION_REF.equals(ref) ? RevocationStatus.UNKNOWN
                : RevocationStatus.ACTIVE);
        assertThat(service.isOpen()).isTrue();
        assertBusiness(() -> service.register(signed.request()), 403, "error.privacy.policyEnvelopeRejected");
        assertThat(auditUris).anyMatch(uri -> uri.contains("reason=REVOCATION_STATUS_UNKNOWN"));
        revocation.set(ref -> PolicyEnvelopeTestSigner.REVOCATION_REF.equals(ref) ? RevocationStatus.REVOKED
                : RevocationStatus.ACTIVE);
        assertBusiness(() -> service.register(signed.request()), 403, "error.privacy.policyEnvelopeRejected");
        assertThat(auditUris).anyMatch(uri -> uri.contains("reason=ENVELOPE_REVOKED"));
        assertThat(rows()).isZero();

        // 登録の後に失効状態を確認できなくなれば、有効化も同じ規則で拒否する
        revocation.set(ref -> RevocationStatus.ACTIVE);
        PrivacyPolicyRegistrationResult registered = service.register(signed.request());
        revocation.set(ref -> PolicyEnvelopeTestSigner.REVOCATION_REF.equals(ref) ? RevocationStatus.UNKNOWN
                : RevocationStatus.ACTIVE);
        as(ACTIVATOR);
        assertBusiness(() -> service.activate(registered.id(), new PrivacyPolicyActivationRequest(null)), 403,
                "error.privacy.policyEnvelopeRejected");
        assertThat(policyMapper.selectById(registered.id()).getStatus()).isEqualTo("CANDIDATE");
    }

    // --- 職務分離 ---

    @Test
    void 登録者は有効化できずenvelopeの申請者と承認者が同じなら登録を拒否する() {
        PrivacyPolicyRegistrationResult registered = service.register(signer.sign(POLICY_ID, "r1").request());

        assertBusiness(() -> service.activate(registered.id(), new PrivacyPolicyActivationRequest(null)), 403,
                "error.privacy.policyActivationSeparation");
        assertThat(auditUris).anyMatch(uri -> uri.contains("#REJECTED?operation=ACTIVATE") && uri.contains("reason=SEPARATION_OF_DUTIES"));
        assertThat(policyMapper.selectById(registered.id()).getStatus()).isEqualTo("CANDIDATE");

        // DB の CHECK も登録者と同じ有効化者を拒否する
        assertThatThrownBy(() -> jdbc.update("UPDATE m_retention_policy SET status = 'CURRENT', activated_by = ?, "
                + "activated_at = CURRENT_TIMESTAMP WHERE id = ?", REGISTRANT, registered.id()))
                .isInstanceOf(DataIntegrityViolationException.class);

        PolicyEnvelopeTestSigner.Signed selfApproved = signer.sign(POLICY_ID, "r2", PolicyEnvelopeTestSigner.AUTHORITY,
                "same-person", "same-person", "TEST_ONLY_RULE");
        assertBusiness(() -> service.register(selfApproved.request()), 403, "error.privacy.policyEnvelopeRejected");
        assertThat(auditUris).anyMatch(uri -> uri.contains("reason=SOD_VIOLATION"));
    }

    // --- 二重 current の CAS ---

    @Test
    void current切替はexpectedCurrentIdのCASで旧currentを同じtransactionでSUPERSEDEDにする() {
        Long r1 = service.register(signer.sign(POLICY_ID, "r1").request()).id();
        Long r2 = service.register(signer.sign(POLICY_ID, "r2").request()).id();
        as(ACTIVATOR);
        service.activate(r1, new PrivacyPolicyActivationRequest(null));

        // 確認した current（無し）と実際の current（r1）が違えば409で、どちらの行も変えない
        assertBusiness(() -> service.activate(r2, new PrivacyPolicyActivationRequest(null)), 409,
                "error.privacy.policyCurrentChanged");
        assertBusiness(() -> service.activate(r2, new PrivacyPolicyActivationRequest(r2)), 409,
                "error.privacy.policyCurrentChanged");
        assertThat(status(r1)).isEqualTo("CURRENT");
        assertThat(status(r2)).isEqualTo("CANDIDATE");

        PrivacyPolicyRegistrationResult switched = service.activate(r2, new PrivacyPolicyActivationRequest(r1));

        assertThat(switched.registrationStatus()).isEqualTo("CURRENT");
        assertThat(switched.supersededId()).isEqualTo(r1);
        assertThat(status(r1)).isEqualTo("SUPERSEDED");
        assertThat(status(r2)).isEqualTo("CURRENT");
        assertThat(policyMapper.selectById(r1).getRowVersion()).isEqualTo(2L);
        assertThat(currentRows()).isEqualTo(1L);
        assertThat(auditUris).anyMatch(uri -> uri.contains("#SUPERSEDED?id=" + r1) && uri.contains("by=" + r2));

        // 同じ有効化者の再送は状態を変えずに元の結果、SUPERSEDED の行は有効化できない
        assertThat(service.activate(r2, new PrivacyPolicyActivationRequest(r1)).replayed()).isTrue();
        assertBusiness(() -> service.activate(r1, new PrivacyPolicyActivationRequest(r2)), 409,
                "error.privacy.policyNotCandidate");

        // DB の UNIQUE（current_key）は同じ key の2件目の CURRENT を拒否する
        assertThatThrownBy(() -> jdbc.update("UPDATE m_retention_policy SET status = 'CURRENT' WHERE id = ?", r1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(currentRows()).isEqualTo(1L);
    }

    @Test
    void currentより前に署名されたrevisionの有効化は409で戻すには新しいrevisionとして署名し直して登録する() {
        Long r1 = service.register(signer.signIssuedAt(POLICY_ID, "r1", NOW.minusSeconds(3000)).request()).id();
        Long r2 = service.register(signer.signIssuedAt(POLICY_ID, "r2", NOW.minusSeconds(2400)).request()).id();
        Long sameTime = service.register(signer.signIssuedAt(POLICY_ID, "r2b", NOW.minusSeconds(2400)).request()).id();
        Long r3 = service.register(signer.signIssuedAt(POLICY_ID, "r3", NOW.minusSeconds(1800)).request()).id();
        as(ACTIVATOR);
        service.activate(r2, new PrivacyPolicyActivationRequest(null));

        assertBusiness(() -> service.activate(r1, new PrivacyPolicyActivationRequest(r2)), 409,
                "error.privacy.policyRevisionRollback");
        assertBusiness(() -> service.activate(sameTime, new PrivacyPolicyActivationRequest(r2)), 409,
                "error.privacy.policyRevisionRollback");
        assertThat(auditUris).anyMatch(uri -> uri.contains("#REJECTED?operation=ACTIVATE&id=" + r1)
                && uri.contains("reason=REVISION_ROLLBACK"));
        assertThat(status(r1)).isEqualTo("CANDIDATE");
        assertThat(status(r2)).isEqualTo("CURRENT");

        PrivacyPolicyRegistrationResult switched = service.activate(r3, new PrivacyPolicyActivationRequest(r2));
        assertThat(switched.supersededId()).isEqualTo(r2);
        assertThat(status(r3)).isEqualTo("CURRENT");
        assertThat(currentRows()).isEqualTo(1L);
    }

    // --- 取下げ ---

    @Test
    void CANDIDATEは登録者も別のprivacy_adminも取り下げられ同じkeyの別policyの登録を止めなくなる() {
        PolicyEnvelopeTestSigner.Signed wrong = signer.sign("wrong-policy", "r1");
        Long wrongId = service.register(wrong.request()).id();
        assertBusiness(() -> service.register(signer.sign(POLICY_ID, "r1").request()), 409,
                "error.privacy.policyKeyConflict");

        PrivacyPolicyRegistrationResult withdrawn = service.withdraw(wrongId);

        assertThat(withdrawn.registrationStatus()).isEqualTo("REVOKED");
        assertThat(withdrawn.replayed()).isFalse();
        RetentionPolicy row = policyMapper.selectById(wrongId);
        assertThat(row.getStatus()).isEqualTo("REVOKED");
        assertThat(row.getActivatedBy()).as("有効化されたことは無い").isNull();
        assertThat(row.getRowVersion()).isEqualTo(1L);
        assertThat(auditUris).anyMatch(uri -> uri.startsWith("/api/privacy/policies#WITHDRAWN?id=" + wrongId)
                && uri.contains("by=REGISTRANT"));
        assertThat(service.withdraw(wrongId).replayed()).as("取り下げた行の再送").isTrue();
        // 取り下げた revision は再利用できない（同じ内容の再送は取り下げた行、別の内容は409）
        assertThat(service.register(wrong.request()).registrationStatus()).isEqualTo("REVOKED");
        assertBusiness(() -> service.register(signer.sign("wrong-policy", "r1", PolicyEnvelopeTestSigner.AUTHORITY,
                "requester-test", "approver-test", "TEST_ONLY_RULE_OTHER").request()), 409,
                "error.privacy.policyRevisionConflict");

        Long r1 = service.register(signer.sign(POLICY_ID, "r1").request()).id();
        Long r2 = service.register(signer.sign(POLICY_ID, "r2").request()).id();
        as(ACTIVATOR);
        assertThat(service.withdraw(r2).registrationStatus()).isEqualTo("REVOKED");
        assertThat(auditUris).anyMatch(uri -> uri.contains("#WITHDRAWN?id=" + r2) && uri.contains("by=OTHER_PRIVACY_ADMIN"));
        assertBusiness(() -> service.activate(r2, new PrivacyPolicyActivationRequest(null)), 409,
                "error.privacy.policyNotCandidate");

        // 有効化した行（CURRENT・SUPERSEDED）は取り下げられない
        service.activate(r1, new PrivacyPolicyActivationRequest(null));
        assertBusiness(() -> service.withdraw(r1), 409, "error.privacy.policyNotCandidate");
        assertThat(status(r1)).isEqualTo("CURRENT");
    }

    // --- 再送・key の競合・catalog ---

    @Test
    void 同じ内容の再送は元の行を返し同じrevisionの別内容や同じkeyの別policyは409() {
        PolicyEnvelopeTestSigner.Signed signed = signer.sign(POLICY_ID, "r1");
        PrivacyPolicyRegistrationResult first = service.register(signed.request());

        PrivacyPolicyRegistrationResult resent = service.register(signed.request());
        assertThat(resent.replayed()).isTrue();
        assertThat(resent.id()).isEqualTo(first.id());

        PolicyEnvelopeTestSigner.Signed different = signer.sign(POLICY_ID, "r1", PolicyEnvelopeTestSigner.AUTHORITY,
                "requester-test", "approver-test", "TEST_ONLY_RULE_OTHER");
        assertBusiness(() -> service.register(different.request()), 409, "error.privacy.policyRevisionConflict");

        // 同じ key に別の policy ID を登録すると catalog が policy を一意に解決できなくなるため拒否する
        assertBusiness(() -> service.register(signer.sign("other-policy", "r1").request()), 409,
                "error.privacy.policyKeyConflict");
        assertThat(rows()).isEqualTo(1L);
    }

    @Test
    void data_elementがcatalogに無ければ登録と有効化を409で拒否する() {
        PrivacyPolicyRegistrationResult registered = service.register(signer.sign(POLICY_ID, "r1").request());
        jdbc.update("DELETE FROM m_pii_data_element WHERE tenant_id = ?", tenant);

        assertBusiness(() -> service.register(signer.sign(POLICY_ID, "r2").request()), 409,
                "error.privacy.policyDataElementUnknown");
        as(ACTIVATOR);
        assertBusiness(() -> service.activate(registered.id(), new PrivacyPolicyActivationRequest(null)), 409,
                "error.privacy.policyDataElementUnknown");
        assertThat(status(registered.id())).isEqualTo("CANDIDATE");
    }

    // --- 開放の gate・入力・権限 ---

    @Test
    void D01とD02とD03とverifierとtrust_anchorが揃うまで登録と有効化は404で何も書かない() {
        PolicyEnvelopeTestSigner.Signed signed = signer.sign(POLICY_ID, "r1");
        assertThat(service.isOpen()).isTrue();

        approval.getD01().setDecisionId(null);
        assertNotFound(() -> service.register(signed.request()));
        assertThat(service.readiness().missing()).containsExactly("d01");
        decision(approval.getD01(), "D01-NF07-TEST-001");
        approval.getD02().setDecisionId("D02-TBD-0001");
        assertNotFound(() -> service.register(signed.request()));
        decision(approval.getD02(), "D02-NF07-TEST-001");
        approval.getD03().setExpiresOn("2026-11-17");
        assertNotFound(() -> service.register(signed.request()));
        assertNotFound(() -> service.activate(1L, new PrivacyPolicyActivationRequest(null)));
        assertNotFound(() -> service.withdraw(1L));
        decision(approval.getD03(), "D03-NF07-TEST-001");
        approval.getD02().setDecisionId("D03-NF07-TEST-001");
        assertNotFound(() -> service.register(signed.request()));
        decision(approval.getD02(), "D02-NF07-TEST-001");

        assertNotFound(() -> serviceWith(null).register(signed.request()));
        assertThat(serviceWith(null).readiness().missing()).containsExactly("verifier");
        assertNotFound(() -> serviceWith(verifierWith(() -> Map.of())).register(signed.request()));
        assertNotFound(() -> serviceWith(verifierWith(() -> {
            throw new IllegalStateException("test: registry の障害");
        })).register(signed.request()));
        assertThat(rows()).isZero();

        assertThat(service.register(signed.request()).registrationStatus()).isEqualTo("CANDIDATE");
    }

    @Test
    void 本番構成の空のregistryと常にUNKNOWNの失効providerでは開かずprod以外ではverifierを作れない() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        PolicyRegistrationVerifier phaseOne = ProductionPrivacyComponents.policyRegistrationVerifier(prod,
                ConfiguredPolicyTrustRegistry.empty(), new UnknownRevocationStatusProvider(), clock);
        assertThat(phaseOne.trustReady(tenant)).isFalse();
        assertNotFound(() -> serviceWith(phaseOne).register(signer.sign(POLICY_ID, "r1").request()));

        // anchor が登録されても（D-03 の方式の決定前に誤って登録された場合）、失効状態が常に UNKNOWN の間は開かない
        PolicyRegistrationVerifier unknownRevocation = ProductionPrivacyComponents.policyRegistrationVerifier(prod,
                signer.registry(), new UnknownRevocationStatusProvider(), clock);
        assertThat(unknownRevocation.trustReady(tenant)).isFalse();

        for (String[] profiles : List.of(new String[]{"test"}, new String[]{}, new String[]{"dev"})) {
            MockEnvironment other = new MockEnvironment();
            other.setActiveProfiles(profiles);
            assertThatThrownBy(() -> ProductionPrivacyComponents.policyRegistrationVerifier(other, signer.registry(),
                    ref -> RevocationStatus.ACTIVE, clock)).isInstanceOf(SecurityException.class);
        }
        assertThat(rows()).isZero();
    }

    @Test
    void 形式の不正な要求は400で拒否する() {
        PolicyEnvelopeTestSigner.Signed signed = signer.sign(POLICY_ID, "r1");
        String envelope = Base64.getEncoder().encodeToString(signed.canonical());
        String signature = Base64.getEncoder().encodeToString(signed.signature());

        assertInvalid(() -> service.register(null));
        assertInvalid(() -> service.register(new PrivacyPolicyRegistrationRequest(null, signature)));
        assertInvalid(() -> service.register(new PrivacyPolicyRegistrationRequest(envelope, " ")));
        assertInvalid(() -> service.register(new PrivacyPolicyRegistrationRequest("%%%", signature)));
        assertInvalid(() -> service.register(new PrivacyPolicyRegistrationRequest(
                "A".repeat(PrivacyPolicyRegistrationService.MAX_ENVELOPE_BASE64 + 4), signature)));
        assertInvalid(() -> service.register(new PrivacyPolicyRegistrationRequest(envelope,
                "A".repeat(PrivacyPolicyRegistrationService.MAX_SIGNATURE_BASE64 + 4))));
        // 署名済みでも照会・監査で扱えない文字の policy ID は行にしない
        assertInvalid(() -> service.register(signer.sign("policy id", "r1").request()));
        assertThat(auditUris).anyMatch(uri -> uri.contains("reason=FIELD_FORMAT"));

        as(ACTIVATOR);
        assertInvalid(() -> service.activate(1L, new PrivacyPolicyActivationRequest(0L)));
        assertInvalid(() -> service.activate(0L, null));
        assertBusiness(() -> service.activate(Long.MAX_VALUE, null), 404, "error.scope.notFound");
        assertInvalid(() -> service.withdraw(0L));
        assertBusiness(() -> service.withdraw(Long.MAX_VALUE), 404, "error.scope.notFound");
        assertThat(rows()).isZero();
    }

    @Test
    void privacy_policy_manageの権限が無ければ403で開放の判定もしない() {
        doThrow(BusinessException.of(403, "error.accessDenied")).when(guard)
                .requireAllowed(any(), eq(PrivacyPolicyRegistrationService.MANAGE_ACTION));
        revocation.set(ref -> {
            throw new AssertionError("権限の無い要求で失効 provider に照会しない");
        });

        assertBusiness(() -> service.register(signer.sign(POLICY_ID, "r1").request()), 403, "error.accessDenied");
        assertBusiness(() -> service.activate(1L, null), 403, "error.accessDenied");
        assertBusiness(() -> service.withdraw(1L), 403, "error.accessDenied");
        assertThat(rows()).isZero();
    }

    @Test
    void 公開スイッチOFFは404で呼出し元のtenantが権限評価のtenantと違えば403() {
        doThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException()).when(guard)
                .requireAllowed(any(), eq(PrivacyPolicyRegistrationService.MANAGE_ACTION));
        assertNotFound(() -> service.register(signer.sign(POLICY_ID, "r1").request()));

        guard = mock(PrivacyAccessGuard.class);
        service = serviceWith(verifier);
        actor.set(new PrivacyPolicyRegistrationService.Actor("other-tenant", REGISTRANT, "user-" + REGISTRANT));
        assertBusiness(() -> service.register(signer.sign(POLICY_ID, "r1").request()), 403, "error.accessDenied");
        actor.set(new PrivacyPolicyRegistrationService.Actor(tenant, null, "anonymous"));
        assertBusiness(() -> service.register(signer.sign(POLICY_ID, "r1").request()), 403, "error.accessDenied");
        assertThat(rows()).isZero();
    }

    /** FE-C04（design §8.5 の 4）: trust 文書の構造に不足があれば、決定・verifier・anchor が揃っていても入口は 404 のまま。 */
    @Test
    void trust文書の構造に不足があれば登録の入口は404のまま() {
        assertThat(service.isOpen()).isTrue();
        java.util.concurrent.atomic.AtomicBoolean sound = new java.util.concurrent.atomic.AtomicBoolean(false);
        PrivacyPolicyRegistrationService structured = new PrivacyPolicyRegistrationService(guard, oidc,
                com.ses.privacy.production.PrivacyApprovalSource.properties(approval, null), verifier, policyMapper,
                dataElementMapper, new PrivacyPolicyAudit(mock(AuditLogService.class)),
                new TransactionTemplate(transactionManager), clock, actor::get, sound::get);

        assertThat(structured.isOpen()).isFalse();
        assertThat(structured.readiness().missing()).containsExactly(PrivacyPolicyRegistrationService.REASON_TRUST_STRUCTURE);
        sound.set(true);
        assertThat(structured.isOpen()).as("構造が直れば再開する").isTrue();
    }
    // --- helpers ---

    private PolicyRegistrationVerifier verifierWith(ProductionPolicyTrustRegistry registry) {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        RevocationStatusProvider provider = ref -> revocation.get().apply(ref);
        return ProductionPrivacyComponents.policyRegistrationVerifier(prod, registry, provider, clock);
    }

    private PrivacyPolicyRegistrationService serviceWith(PolicyRegistrationVerifier verifierToUse) {
        AuditLogService auditLogService = mock(AuditLogService.class);
        doAnswer(invocation -> {
            auditUris.add(invocation.getArgument(2));
            return null;
        }).when(auditLogService).recordRequired(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyBoolean());
        return new PrivacyPolicyRegistrationService(guard, oidc, approval, verifierToUse, policyMapper,
                dataElementMapper, new PrivacyPolicyAudit(auditLogService), new TransactionTemplate(transactionManager),
                clock, actor::get);
    }

    private void as(long userId) {
        actor.set(new PrivacyPolicyRegistrationService.Actor(tenant, userId, "privacy-admin-" + userId));
    }

    private PolicyExplanationResult explain(long userId) {
        SysUser admin = new SysUser();
        admin.setId(userId);
        admin.setTenantId(tenant);
        admin.setRole("管理者");
        admin.setStatus(1);
        admin.setDeletedFlag(0);
        SysUserMapper sysUserMapper = mock(SysUserMapper.class);
        when(sysUserMapper.selectByIdAndTenant(userId, tenant)).thenReturn(admin);
        LegalEntityContextService legalEntityContext = mock(LegalEntityContextService.class);
        when(legalEntityContext.requireCurrentLegalEntityId()).thenReturn(42L);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(authorizationService.isAllowed(any(), eq("privacy.policy.view"))).thenReturn(true);
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_管理者"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(admin, authorities, tenant), "test-credential", authorities));
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        PolicyReadonlyQueryPort port = ProductionPrivacyComponents.productionPolicyReadonlyQuery(prod, () -> true,
                legalEntityContext, sysUserMapper, () -> authorizationService, tenant,
                new DbProductionPolicyCatalog(dataElementMapper, policyMapper), new DbCurrentPolicySource(policyMapper),
                signer.registry(), ref -> revocation.get().apply(ref), clock);
        return port.evaluate(new PolicyEvaluationQuery(tenant, Long.toString(userId), PolicyEnvelopeTestSigner.MODULE,
                PolicyEnvelopeTestSigner.ELEMENT, PolicyEnvelopeTestSigner.ACTION, PolicyEnvelopeTestSigner.PROVIDER));
    }

    private void registerElement(String elementId) {
        PiiDataElement element = new PiiDataElement();
        element.setTenantId(tenant);
        element.setDataElementId(elementId);
        element.setStorageKind("AI_PAYLOAD");
        element.setStorageRef("t_ai_recommendation_run.prompt");
        assertThat(dataElementMapper.insert(element)).isEqualTo(1);
    }

    private static void decision(PrivacyApprovalProperties.DecisionReference reference, String decisionId) {
        reference.setDecisionId(decisionId);
        reference.setSignedOn("2026-11-01");
        reference.setExpiresOn("2027-06-01");
    }

    private long rows() {
        return policyMapper.selectCount(new LambdaQueryWrapper<RetentionPolicy>()
                .eq(RetentionPolicy::getTenantId, tenant));
    }

    private long currentRows() {
        return policyMapper.selectCount(new LambdaQueryWrapper<RetentionPolicy>()
                .eq(RetentionPolicy::getTenantId, tenant)
                .eq(RetentionPolicy::getStatus, "CURRENT"));
    }

    private String status(Long id) {
        return policyMapper.selectById(id).getStatus();
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(PrivacyAccessGuard.PrivacyEntryNotFoundException.class);
    }

    private static void assertInvalid(Runnable call) {
        assertBusiness(call, 400, "error.privacy.policyInvalidRequest");
    }

    private static void assertBusiness(Runnable call, int code, String key) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(code);
            assertThat(e.getMessageKey()).isEqualTo(key);
        });
    }
}
