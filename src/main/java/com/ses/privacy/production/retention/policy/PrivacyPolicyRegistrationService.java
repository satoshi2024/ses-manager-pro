package com.ses.privacy.production.retention.policy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.privacy.PiiDataElement;
import com.ses.entity.privacy.RetentionPolicy;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.mapper.privacy.RetentionPolicyMapper;
import com.ses.privacy.foundation.PolicyRegistrationRejectedException;
import com.ses.privacy.foundation.PolicyRegistrationVerifier;
import com.ses.privacy.foundation.VerifiedPolicyRegistration;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.PrivacyApprovalSource;
import com.ses.privacy.production.PrivacyDecisionBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * NF07 policy の登録と有効化（NF07-REG、W-2。production-bridge-phased-plan.md §4）。実装は署名前に入れるが、開放は D-01・D-02・
 * D-03 の署名と trust anchor の登録の後。
 *
 * <p>開放の gate（{@link #isOpen()}。要求ごとに評価する）: 次の全てが揃うまで、登録・有効化の入口は存在しない URL と同じ404を返す。
 * <ul>
 *   <li>D-01（正式 Privacy Owner）・D-02（保持期間・起算 trigger・法的根拠）・D-03（署名鍵）の決定ID が、署名日・失効・
 *       placeholder・重複を含めて有効（{@link PrivacyDecisionBinding#evaluateStandalone}）。</li>
 *   <li>verifier がある（{@code ProductionPrivacyComponents.policyRegistrationVerifier} は prod profile でしか生成できない）。</li>
 *   <li>trust anchor が1件以上登録され、そのうち1件以上が、verifier と同じ clock の時刻に有効期間内で、授権 tenant
 *       （{@code OIDC_TENANT_ID}、未設定なら {@code default}）を範囲に含み、失効 provider がその失効状態を ACTIVE と答える
 *       （{@link PolicyRegistrationVerifier#trustReady(String)}。trust 文書（D-03 = AWS KMS の adapter）を設定するまでは
 *       registry が空・失効 provider が常に UNKNOWN のため開かない）。</li>
 *   <li>trust 文書の構造に不足が無い（{@code PrivacyTrustStructureState}。KMS の trust を設定した prod の起動時に flag と関係なく
 *       検査した結果。不足は起動を止めず、この入口だけを閉じる。feature-enablement-console design §8.5 の 4。FE-C04）。</li>
 * </ul>
 * 決定は承認の値の源（{@link PrivacyApprovalSource}）から呼出しごとに読む（署名の差替えは次の呼出しから効く）。
 *
 * <p>状態の遷移（{@code m_retention_policy.status}）:
 * <ol>
 *   <li>登録（{@code POST /api/privacy/policies}）: 署名済み envelope を検証してから（verify-then-store）、検証した canonical
 *       bytes・署名・digest と、envelope から取った key で {@code CANDIDATE} の行を作る。同じ policy・revision の同じ内容の再送は
 *       元の行を返す（{@code replayed}）。内容が違えば409。同じ key に別の policy ID の行（REVOKED 以外）があれば409（catalog が
 *       policy を一意に解決できなくなるため）。</li>
 *   <li>有効化（{@code POST /api/privacy/policies/{id}/activate}）: 登録者と別の privacy-admin だけが行える（職務分離。DB の
 *       {@code chk_retention_policy_activation} も同じ規則）。保存した envelope を同じ規則で検証し直し、key の行を lock して
 *       要求の {@code expectedCurrentId} と実際の current を比べ（CAS）、旧 current を {@code SUPERSEDED}、対象を {@code CURRENT}
 *       にする（同じ transaction。行ごとに status・row_version の CAS）。current の一意性は生成列 {@code current_key} の
 *       UNIQUE が DB で保証する。current があるとき、対象の envelope の発行日時（署名の範囲内）が current の発行日時より後で
 *       なければ409（{@code policyRevisionRollback}。古い revision へ戻す有効化を拒否し、戻すには内容を新しい revision として
 *       署名し直して登録する。revision の文字列は比較しない）。</li>
 *   <li>取下げ（{@code POST /api/privacy/policies/{id}/withdraw}）: {@code CANDIDATE} だけを {@code REVOKED} にする（有効化された
 *       ことの無い行。{@code activated_by} は NULL のまま）。登録者本人と別の privacy-admin のどちらも行える（状態を減らす操作で、
 *       何も有効にしないため）。{@code CURRENT}・{@code SUPERSEDED} は取り下げられない（current を止めるのは失効 provider の
 *       {@code REVOKED}）。誤って登録した CANDIDATE が同じ key の別 policy ID の登録を409で止め続けないようにする。取り下げた
 *       revision は再利用できない（新しい revision で登録し直す）。</li>
 * </ol>
 * 有効化された行の {@code REVOKED} への遷移は本 batch に無い（失効は失効 provider が判定し、照会は {@code POLICY_REVOKED} を返す）。
 * policy が使えるかどうかは説明照会だけで決まり、本 service は実行認可を付与しない。{@code APPROVED_ACTIVE} は処分の承認ではない
 * （D-04 は B1 の処分の入口が確かめる）。
 */
public class PrivacyPolicyRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyPolicyRegistrationService.class);

    static final String MANAGE_ACTION = "privacy.policy.manage";
    static final String STATUS_CANDIDATE = "CANDIDATE";
    static final String STATUS_CURRENT = "CURRENT";
    static final String STATUS_SUPERSEDED = "SUPERSEDED";
    static final String STATUS_REVOKED = "REVOKED";
    static final String LOG_CODE_REJECTED = "NF07_PRIVACY_POLICY_REJECTED";
    /** 開放の前提の固定 code。 */
    static final String REASON_VERIFIER = "verifier";
    static final String REASON_TRUST = "trust";
    static final String REASON_TRUST_STRUCTURE = "trustStructure";
    /** canonical bytes の上限（64KB）の base64 の長さ。 */
    static final int MAX_ENVELOPE_BASE64 = 87_384;
    /** 署名の上限（1024 byte）の base64 の長さ。 */
    static final int MAX_SIGNATURE_BASE64 = 1_368;
    /** policy ID・revision・authority・key の文字種（監査の本文で符号化しても伸びない文字だけ）。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]+");
    /** 照会 key の文字種（説明照会の {@code PrivacyPolicyExplanationService} と同じ）。 */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:-]+");

    private final PrivacyAccessGuard accessGuard;
    private final OidcSecurityProperties oidcProperties;
    private final PrivacyApprovalSource approvalSource;
    private final BooleanSupplier trustStructureSound;
    private final PolicyRegistrationVerifier verifier;
    private final RetentionPolicyMapper policyMapper;
    private final PiiDataElementMapper dataElementMapper;
    private final PrivacyPolicyAudit audit;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Supplier<Actor> actorSource;

    /** property の源で包み、trust 文書の構造は不足なしとして扱う（既存の試験のため）。 */
    PrivacyPolicyRegistrationService(PrivacyAccessGuard accessGuard, OidcSecurityProperties oidcProperties,
                                     PrivacyApprovalProperties approvalProperties, PolicyRegistrationVerifier verifier,
                                     RetentionPolicyMapper policyMapper, PiiDataElementMapper dataElementMapper,
                                     PrivacyPolicyAudit audit, TransactionTemplate transaction, Clock clock,
                                     Supplier<Actor> actorSource) {
        this(accessGuard, oidcProperties, PrivacyApprovalSource.properties(approvalProperties, null), verifier,
                policyMapper, dataElementMapper, audit, transaction, clock, actorSource, () -> true);
    }

    /**
     * @param trustStructureSound trust 文書の構造に不足が無いか（判定できなければ false を返すこと）
     */
    PrivacyPolicyRegistrationService(PrivacyAccessGuard accessGuard, OidcSecurityProperties oidcProperties,
                                     PrivacyApprovalSource approvalSource, PolicyRegistrationVerifier verifier,
                                     RetentionPolicyMapper policyMapper, PiiDataElementMapper dataElementMapper,
                                     PrivacyPolicyAudit audit, TransactionTemplate transaction, Clock clock,
                                     Supplier<Actor> actorSource, BooleanSupplier trustStructureSound) {
        this.accessGuard = Objects.requireNonNull(accessGuard, "accessGuard");
        this.oidcProperties = oidcProperties;
        this.approvalSource = approvalSource;
        this.trustStructureSound = trustStructureSound == null ? () -> false : trustStructureSound;
        this.verifier = verifier;
        this.policyMapper = Objects.requireNonNull(policyMapper, "policyMapper");
        this.dataElementMapper = Objects.requireNonNull(dataElementMapper, "dataElementMapper");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.actorSource = actorSource == null ? Actor::fromSecurityContext : actorSource;
    }

    /** 登録・有効化の入口が開いているか（D-01・D-02・D-03・verifier・trust）。判定できなければ false。 */
    public boolean isOpen() {
        try {
            return readiness().ready();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 開放の前提の評価（欠けている前提の固定 code。決定ID・設定値は含めない）。 */
    public Readiness readiness() {
        List<String> missing = new ArrayList<>();
        LocalDate today = PrivacyDecisionBinding.businessDate(clock);
        PrivacyApprovalSource values = approvalSource == null ? null : approvalSource.resolve();
        for (PrivacyDecisionBinding.Decision decision : List.of(PrivacyDecisionBinding.Decision.D01,
                PrivacyDecisionBinding.Decision.D02, PrivacyDecisionBinding.Decision.D03)) {
            if (!PrivacyDecisionBinding.evaluateStandalone(values, decision, today).valid()) {
                missing.add(decision.key());
            }
        }
        if (!structureSound()) {
            missing.add(REASON_TRUST_STRUCTURE);
        }
        if (verifier == null) {
            missing.add(REASON_VERIFIER);
        } else if (missing.isEmpty() && !trustReady()) {
            // trust の判定は失効 provider に照会し得るため、決定が揃ってからだけ行う
            missing.add(REASON_TRUST);
        }
        return new Readiness(missing.isEmpty(), List.copyOf(missing));
    }

    private boolean structureSound() {
        try {
            return trustStructureSound.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 署名済み envelope を検証して CANDIDATE として登録する。 */
    public PrivacyPolicyRegistrationResult register(PrivacyPolicyRegistrationRequest request) {
        accessGuard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, MANAGE_ACTION);
        Actor actor = requireActor();
        requireOpen();
        byte[] canonical = decode(request == null ? null : request.envelope(), MAX_ENVELOPE_BASE64);
        byte[] signature = decode(request == null ? null : request.signature(), MAX_SIGNATURE_BASE64);
        VerifiedPolicyRegistration verified;
        try {
            verified = verifier.verify(actor.tenantId(), canonical, signature);
        } catch (PolicyRegistrationRejectedException e) {
            rejected(actor, "REGISTER", null, e.reason().name(), 403);
            throw BusinessException.of(403, "error.privacy.policyEnvelopeRejected");
        } catch (SecurityException e) {
            // verifier が prod 以外で動いた（生成は prod に限るため通常は起きない）。入口が無いのと同じ扱い
            throw new PrivacyAccessGuard.PrivacyEntryNotFoundException();
        } catch (RuntimeException e) {
            log.warn("[privacy-policy] envelopeを検証できません: exceptionClass={}", e.getClass().getSimpleName());
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }
        if (!storable(verified)) {
            rejected(actor, "REGISTER", null, "FIELD_FORMAT", 400);
            throw invalid();
        }
        try {
            return transaction.execute(status -> registerVerified(actor, verified));
        } catch (BusinessException e) {
            throw e;
        } catch (DataIntegrityViolationException e) {
            // 同じ policy・revision の並行の登録（UNIQUE）。後の要求は再送として読み直せば元の行を返す
            throw BusinessException.of(409, "error.privacy.policyRevisionConflict");
        } catch (ConcurrencyFailureException e) {
            throw BusinessException.of(409, "error.privacy.policyConcurrentUpdate");
        } catch (RuntimeException e) {
            log.warn("[privacy-policy] policyを登録できません: exceptionClass={}", e.getClass().getSimpleName());
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }
    }

    /** CANDIDATE の policy を current にする（登録者と別の privacy-admin だけ）。 */
    public PrivacyPolicyRegistrationResult activate(long id, PrivacyPolicyActivationRequest request) {
        accessGuard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, MANAGE_ACTION);
        Actor actor = requireActor();
        requireOpen();
        Long expectedCurrentId = request == null ? null : request.expectedCurrentId();
        if (id <= 0 || (expectedCurrentId != null && expectedCurrentId <= 0)) {
            throw invalid();
        }
        try {
            return transaction.execute(status -> activateLocked(actor, id, expectedCurrentId));
        } catch (Rejected e) {
            // 拒否の監査は有効化の transaction を戻した後に書く（変更と一緒に消さない）
            rejected(actor, "ACTIVATE", id, e.reason, e.mapped.getCode());
            throw e.mapped;
        } catch (BusinessException e) {
            throw e;
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            // current_key の UNIQUE・lock の待機の打切り・deadlock（並行の有効化）。読み直してやり直す
            throw BusinessException.of(409, "error.privacy.policyConcurrentUpdate");
        } catch (RuntimeException e) {
            log.warn("[privacy-policy] policyを有効化できません: exceptionClass={}", e.getClass().getSimpleName());
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }
    }

    /**
     * 有効化されたことの無い CANDIDATE を取り下げる（{@code REVOKED}。登録者本人・別の privacy-admin のどちらも可）。同じ行の
     * 再送（既に取り下げた行）は状態を変えずに元の結果を返す。
     */
    public PrivacyPolicyRegistrationResult withdraw(long id) {
        accessGuard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, MANAGE_ACTION);
        Actor actor = requireActor();
        requireOpen();
        if (id <= 0) {
            throw invalid();
        }
        try {
            return transaction.execute(status -> withdrawLocked(actor, id));
        } catch (BusinessException e) {
            throw e;
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            throw BusinessException.of(409, "error.privacy.policyConcurrentUpdate");
        } catch (RuntimeException e) {
            log.warn("[privacy-policy] policyを取り下げられません: exceptionClass={}", e.getClass().getSimpleName());
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }
    }

    private PrivacyPolicyRegistrationResult withdrawLocked(Actor actor, long id) {
        String tenantId = actor.tenantId();
        // 有効化と同じ順で key の行を lock する（取下げと有効化が並行しても、後の操作は先の結果を見て409になる）
        RetentionPolicy probe = policyMapper.selectOne(keyColumns()
                .eq(RetentionPolicy::getTenantId, tenantId)
                .eq(RetentionPolicy::getId, id));
        if (probe == null || probe.getLegalEntityId() != null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        List<RetentionPolicy> keyRows = lockKeyRows(tenantId, probe.getModuleCode(), probe.getDataElementId(),
                probe.getTargetAction(), probe.getTargetProvider());
        RetentionPolicy target = keyRows.stream().filter(row -> Objects.equals(row.getId(), id)).findFirst()
                .orElseThrow(() -> BusinessException.of(409, "error.privacy.policyConcurrentUpdate"));
        if (STATUS_REVOKED.equals(target.getStatus()) && target.getActivatedBy() == null) {
            return result(target, null, true);
        }
        if (!STATUS_CANDIDATE.equals(target.getStatus())) {
            throw BusinessException.of(409, "error.privacy.policyNotCandidate");
        }
        int withdrawn = policyMapper.update(null, new LambdaUpdateWrapper<RetentionPolicy>()
                .set(RetentionPolicy::getStatus, STATUS_REVOKED)
                .setSql("row_version = row_version + 1")
                .eq(RetentionPolicy::getTenantId, tenantId)
                .eq(RetentionPolicy::getId, id)
                .eq(RetentionPolicy::getStatus, STATUS_CANDIDATE)
                .isNull(RetentionPolicy::getActivatedBy)
                .eq(RetentionPolicy::getRowVersion, target.getRowVersion()));
        if (withdrawn != 1) {
            throw BusinessException.of(409, "error.privacy.policyConcurrentUpdate");
        }
        audit.record(actor.username(), PrivacyPolicyAudit.WITHDRAWN, PrivacyPolicyAudit.fields(
                "id", Long.toString(id), "policy", target.getPolicyId(), "revision", target.getPolicyRevision(),
                "digest", target.getEnvelopeDigest() == null ? null : target.getEnvelopeDigest().substring(0, 16),
                "by", actor.userId().equals(target.getRegisteredBy()) ? "REGISTRANT" : "OTHER_PRIVACY_ADMIN"),
                200, true);
        target.setStatus(STATUS_REVOKED);
        return result(target, null, false);
    }

    private PrivacyPolicyRegistrationResult registerVerified(Actor actor, VerifiedPolicyRegistration verified) {
        String tenantId = actor.tenantId();
        requireDataElement(tenantId, verified.dataElementId());
        RetentionPolicy existing = policyMapper.selectOne(new LambdaQueryWrapper<RetentionPolicy>()
                .eq(RetentionPolicy::getTenantId, tenantId)
                .eq(RetentionPolicy::getPolicyId, verified.policyId())
                .eq(RetentionPolicy::getPolicyRevision, verified.revision())
                .last("FOR UPDATE"));
        if (existing != null) {
            if (sameEnvelope(existing, verified)) {
                return result(existing, null, true);
            }
            throw BusinessException.of(409, "error.privacy.policyRevisionConflict");
        }
        List<RetentionPolicy> keyRows = lockKeyRows(tenantId, verified.scope(), verified.dataElementId(),
                verified.action(), verified.providerId());
        requireSinglePolicyId(keyRows, verified.policyId());

        RetentionPolicy row = new RetentionPolicy();
        row.setTenantId(tenantId);
        row.setPolicyId(verified.policyId());
        row.setPolicyRevision(verified.revision());
        row.setModuleCode(verified.scope());
        row.setLegalEntityId(null);
        row.setDataElementId(verified.dataElementId());
        row.setTargetAction(verified.action());
        row.setTargetProvider(verified.providerId());
        row.setStatus(STATUS_CANDIDATE);
        row.setEnvelopeSchemaVersion(verified.schemaVersion());
        row.setEnvelopeCanonical(verified.canonical());
        row.setEnvelopeSignature(verified.signature());
        row.setEnvelopeDigest(verified.digest());
        row.setAuthorityId(verified.authorityId());
        row.setKeyId(verified.keyId());
        row.setRegisteredBy(actor.userId());
        row.setRegisteredAt(LocalDateTime.now(clock));
        row.setRowVersion(0L);
        if (policyMapper.insert(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("policyの行を作成できません");
        }
        audit.record(actor.username(), PrivacyPolicyAudit.REGISTERED, PrivacyPolicyAudit.fields(
                "id", Long.toString(row.getId()), "policy", verified.policyId(), "revision", verified.revision(),
                "digest", verified.digest().substring(0, 16), "authority", verified.authorityId(),
                "key", verified.keyId()), 200, true);
        return result(row, null, false);
    }

    private PrivacyPolicyRegistrationResult activateLocked(Actor actor, long id, Long expectedCurrentId) {
        String tenantId = actor.tenantId();
        // key を知るための非 lock の読取り。lock は key の行全体へ key の index の走査順に1回で取る（対象の行を先に lock すると、
        // 同じ key の2件を並行に有効化したときに互いの行を待って deadlock する）
        RetentionPolicy probe = policyMapper.selectOne(keyColumns()
                .eq(RetentionPolicy::getTenantId, tenantId)
                .eq(RetentionPolicy::getId, id));
        if (probe == null || probe.getLegalEntityId() != null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        List<RetentionPolicy> keyRows = lockKeyRows(tenantId, probe.getModuleCode(), probe.getDataElementId(),
                probe.getTargetAction(), probe.getTargetProvider());
        RetentionPolicy target = keyRows.stream().filter(row -> Objects.equals(row.getId(), id)).findFirst()
                .orElseThrow(() -> BusinessException.of(409, "error.privacy.policyConcurrentUpdate"));
        if (STATUS_CURRENT.equals(target.getStatus()) && actor.userId().equals(target.getActivatedBy())) {
            // 同じ有効化者の再送（ACK 不明の後等）。状態を変えずに元の結果を返す
            return result(target, null, true);
        }
        if (!STATUS_CANDIDATE.equals(target.getStatus())) {
            throw BusinessException.of(409, "error.privacy.policyNotCandidate");
        }
        if (actor.userId().equals(target.getRegisteredBy())) {
            throw new Rejected("SEPARATION_OF_DUTIES",
                    BusinessException.of(403, "error.privacy.policyActivationSeparation"));
        }
        RetentionPolicy stored = policyMapper.selectOne(new LambdaQueryWrapper<RetentionPolicy>()
                .eq(RetentionPolicy::getTenantId, tenantId)
                .eq(RetentionPolicy::getId, id));
        if (stored == null) {
            throw BusinessException.of(409, "error.privacy.policyConcurrentUpdate");
        }
        VerifiedPolicyRegistration verified;
        try {
            verified = verifier.verify(tenantId, stored.getEnvelopeCanonical(), stored.getEnvelopeSignature());
        } catch (PolicyRegistrationRejectedException e) {
            throw new Rejected(e.reason().name(), BusinessException.of(403, "error.privacy.policyEnvelopeRejected"));
        }
        if (!matchesStored(stored, verified)) {
            // 保存後に行の key・digest・authority が書き換えられた（envelope と行が食い違う行は current にしない）
            throw new Rejected("STORED_ROW_MISMATCH", BusinessException.of(403, "error.privacy.policyEnvelopeRejected"));
        }
        requireDataElement(tenantId, target.getDataElementId());
        requireSinglePolicyId(keyRows, target.getPolicyId());

        List<RetentionPolicy> currents = keyRows.stream().filter(row -> STATUS_CURRENT.equals(row.getStatus())).toList();
        if (currents.size() > 1) {
            throw BusinessException.of(409, "error.privacy.policyConcurrentUpdate");
        }
        RetentionPolicy current = currents.isEmpty() ? null : currents.get(0);
        Long actualCurrentId = current == null ? null : current.getId();
        if (!Objects.equals(actualCurrentId, expectedCurrentId)) {
            throw BusinessException.of(409, "error.privacy.policyCurrentChanged");
        }
        if (current != null) {
            requireSignedAfterCurrent(tenantId, current, verified);
        }
        if (current != null) {
            int superseded = policyMapper.update(null, new LambdaUpdateWrapper<RetentionPolicy>()
                    .set(RetentionPolicy::getStatus, STATUS_SUPERSEDED)
                    .setSql("row_version = row_version + 1")
                    .eq(RetentionPolicy::getTenantId, tenantId)
                    .eq(RetentionPolicy::getId, current.getId())
                    .eq(RetentionPolicy::getStatus, STATUS_CURRENT)
                    .eq(RetentionPolicy::getRowVersion, current.getRowVersion()));
            if (superseded != 1) {
                throw BusinessException.of(409, "error.privacy.policyConcurrentUpdate");
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int activated = policyMapper.update(null, new LambdaUpdateWrapper<RetentionPolicy>()
                .set(RetentionPolicy::getStatus, STATUS_CURRENT)
                .set(RetentionPolicy::getActivatedBy, actor.userId())
                .set(RetentionPolicy::getActivatedAt, now)
                .setSql("row_version = row_version + 1")
                .eq(RetentionPolicy::getTenantId, tenantId)
                .eq(RetentionPolicy::getId, id)
                .eq(RetentionPolicy::getStatus, STATUS_CANDIDATE)
                .eq(RetentionPolicy::getRowVersion, target.getRowVersion()));
        if (activated != 1) {
            throw BusinessException.of(409, "error.privacy.policyConcurrentUpdate");
        }
        audit.record(actor.username(), PrivacyPolicyAudit.ACTIVATED, PrivacyPolicyAudit.fields(
                "id", Long.toString(id), "policy", target.getPolicyId(), "revision", target.getPolicyRevision(),
                "digest", verified.digest().substring(0, 16),
                "superseded", current == null ? "none" : Long.toString(current.getId())), 200, true);
        if (current != null) {
            audit.record(actor.username(), PrivacyPolicyAudit.SUPERSEDED, PrivacyPolicyAudit.fields(
                    "id", Long.toString(current.getId()), "policy", current.getPolicyId(),
                    "revision", current.getPolicyRevision(), "by", Long.toString(id)), 200, true);
        }
        target.setStatus(STATUS_CURRENT);
        return result(target, actualCurrentId, false);
    }

    /**
     * 対象の envelope が current より後に署名されたこと（発行日時の比較。同じ時刻も拒否する）。revision の文字列は順序を持たない
     * ため比較しない。current の envelope は復号だけを行い、trust（鍵の失効・rotation）は見ない（置き換えを止めないため）。
     * 古い revision へ戻す有効化は409（RB-NF07-REG review P3-6。戻すなら内容を新しい revision として署名し直して登録する。
     * D-02 owner が別の運用を望む場合は再検討する）。current を復号できなければ行の食い違いとして拒否する。
     */
    private void requireSignedAfterCurrent(String tenantId, RetentionPolicy current, VerifiedPolicyRegistration verified) {
        RetentionPolicy currentRow = policyMapper.selectOne(new LambdaQueryWrapper<RetentionPolicy>()
                .select(RetentionPolicy::getId, RetentionPolicy::getEnvelopeCanonical,
                        RetentionPolicy::getEnvelopeSignature)
                .eq(RetentionPolicy::getTenantId, tenantId)
                .eq(RetentionPolicy::getId, current.getId()));
        java.time.Instant currentIssuedAt;
        try {
            currentIssuedAt = currentRow == null ? null
                    : verifier.signedIssuedAt(currentRow.getEnvelopeCanonical(), currentRow.getEnvelopeSignature());
        } catch (PolicyRegistrationRejectedException e) {
            currentIssuedAt = null;
        }
        if (currentIssuedAt == null) {
            throw new Rejected("CURRENT_UNREADABLE", BusinessException.of(403, "error.privacy.policyEnvelopeRejected"));
        }
        if (!verified.issuedAt().isAfter(currentIssuedAt)) {
            throw new Rejected("REVISION_ROLLBACK", BusinessException.of(409, "error.privacy.policyRevisionRollback"));
        }
    }

    /**
     * key（tenant・module・tenant-wide・data element・action・provider）の全ての行を lock する。lock は key の index の走査順に
     * 1回で取る（結果は id 順に並べる。同じ形の照会だけを使うため、操作どうしの lock の順序は一致する）。
     */
    private List<RetentionPolicy> lockKeyRows(String tenantId, String moduleCode, String dataElementId,
                                              String targetAction, String targetProvider) {
        return policyMapper.selectList(keyColumns()
                .eq(RetentionPolicy::getTenantId, tenantId)
                .eq(RetentionPolicy::getModuleCode, moduleCode)
                .isNull(RetentionPolicy::getLegalEntityId)
                .eq(RetentionPolicy::getDataElementId, dataElementId)
                .eq(RetentionPolicy::getTargetAction, targetAction)
                .eq(RetentionPolicy::getTargetProvider, targetProvider)
                .orderByAsc(RetentionPolicy::getId)
                .last("FOR UPDATE"));
    }

    /** 同じ key に REVOKED 以外で別の policy ID の行があれば409（catalog の「policy ID が1種類」を保つ）。 */
    private static void requireSinglePolicyId(List<RetentionPolicy> keyRows, String policyId) {
        boolean other = keyRows.stream().anyMatch(row -> !STATUS_REVOKED.equals(row.getStatus())
                && !Objects.equals(row.getPolicyId(), policyId));
        if (other) {
            throw BusinessException.of(409, "error.privacy.policyKeyConflict");
        }
    }

    /** data element が tenant の catalog に1件登録されていること（照会の catalog と同じ条件）。 */
    private void requireDataElement(String tenantId, String dataElementId) {
        Long registered = dataElementMapper.selectCount(new LambdaQueryWrapper<PiiDataElement>()
                .eq(PiiDataElement::getTenantId, tenantId)
                .eq(PiiDataElement::getDataElementId, dataElementId));
        if (registered == null || registered != 1L) {
            throw BusinessException.of(409, "error.privacy.policyDataElementUnknown");
        }
    }

    private static LambdaQueryWrapper<RetentionPolicy> keyColumns() {
        return new LambdaQueryWrapper<RetentionPolicy>()
                .select(RetentionPolicy::getId, RetentionPolicy::getTenantId, RetentionPolicy::getPolicyId,
                        RetentionPolicy::getPolicyRevision, RetentionPolicy::getModuleCode,
                        RetentionPolicy::getLegalEntityId, RetentionPolicy::getDataElementId,
                        RetentionPolicy::getTargetAction, RetentionPolicy::getTargetProvider,
                        RetentionPolicy::getStatus, RetentionPolicy::getEnvelopeDigest,
                        RetentionPolicy::getRegisteredBy, RetentionPolicy::getActivatedBy,
                        RetentionPolicy::getRowVersion);
    }

    /** 同じ policy・revision の再送か（canonical bytes の digest・署名・key が全て同じ）。 */
    private static boolean sameEnvelope(RetentionPolicy existing, VerifiedPolicyRegistration verified) {
        return verified.digest().equals(existing.getEnvelopeDigest())
                && Arrays.equals(verified.signature(), existing.getEnvelopeSignature())
                && matchesKey(existing, verified);
    }

    /** 保存した行の列が、保存した envelope を検証した値と一致するか。 */
    private static boolean matchesStored(RetentionPolicy stored, VerifiedPolicyRegistration verified) {
        return verified.digest().equals(stored.getEnvelopeDigest())
                && Objects.equals(verified.policyId(), stored.getPolicyId())
                && Objects.equals(verified.revision(), stored.getPolicyRevision())
                && Objects.equals(verified.authorityId(), stored.getAuthorityId())
                && Objects.equals(verified.keyId(), stored.getKeyId())
                && Objects.equals(verified.schemaVersion(), stored.getEnvelopeSchemaVersion())
                && matchesKey(stored, verified);
    }

    private static boolean matchesKey(RetentionPolicy row, VerifiedPolicyRegistration verified) {
        return row.getLegalEntityId() == null
                && Objects.equals(verified.tenantId(), row.getTenantId())
                && Objects.equals(verified.scope(), row.getModuleCode())
                && Objects.equals(verified.dataElementId(), row.getDataElementId())
                && Objects.equals(verified.action(), row.getTargetAction())
                && Objects.equals(verified.providerId(), row.getTargetProvider());
    }

    /** 列の長さと、照会・監査で扱える文字種に収まるか（署名済みでも収まらない値は行にしない）。 */
    private static boolean storable(VerifiedPolicyRegistration verified) {
        return matches(IDENTIFIER, verified.policyId(), 128) && matches(IDENTIFIER, verified.revision(), 64)
                && matches(CODE, verified.scope(), 50) && matches(CODE, verified.dataElementId(), 128)
                && matches(CODE, verified.action(), 50) && matches(CODE, verified.providerId(), 100)
                && matches(IDENTIFIER, verified.authorityId(), 64) && matches(IDENTIFIER, verified.keyId(), 64);
    }

    private static boolean matches(Pattern pattern, String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength && pattern.matcher(value).matches();
    }

    private void requireOpen() {
        if (!isOpen()) {
            // 開放の前（D-01・D-02・D-03 の署名前・placeholder・失効、trust anchor 未登録・失効状態を確認できない、prod 以外）は
            // 入口が無いのと同じ404
            throw new PrivacyAccessGuard.PrivacyEntryNotFoundException();
        }
    }

    private boolean trustReady() {
        try {
            // gate は授権 tenant（呼出し元の tenant はこれと一致しなければ403）を範囲に含む anchor だけを数える
            return verifier != null && verifier.trustReady(authorizationTenant());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 権限評価の設定 tenant（{@code OIDC_TENANT_ID}、未設定なら {@code default}）。 */
    private String authorizationTenant() {
        return oidcProperties != null && StringUtils.hasText(oidcProperties.getTenantId())
                ? oidcProperties.getTenantId() : "default";
    }

    /** 照会者（principal に束縛された tenant と user）。tenant が権限評価の設定 tenant と違えば403。 */
    private Actor requireActor() {
        Actor actor = actorSource.get();
        String callerTenant = actor == null ? null : actor.tenantId();
        String authorizationTenant = authorizationTenant();
        if (!StringUtils.hasText(callerTenant) || !authorizationTenant.equals(callerTenant)
                || actor.userId() == null || actor.userId() <= 0) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        return actor;
    }

    private static byte[] decode(String base64, int maxLength) {
        if (!StringUtils.hasText(base64) || base64.length() > maxLength) {
            throw invalid();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64);
            if (decoded.length == 0) {
                throw invalid();
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private void rejected(Actor actor, String operation, Long id, String reason, int status) {
        log.warn("[privacy-policy] policyの{}を拒否しました: code={} reason={}", operation, LOG_CODE_REJECTED, reason);
        try {
            transaction.executeWithoutResult(tx -> audit.record(actor.username(), PrivacyPolicyAudit.REJECTED,
                    PrivacyPolicyAudit.fields("operation", operation, "id", id == null ? null : Long.toString(id),
                            "reason", reason), status, false));
        } catch (RuntimeException e) {
            log.warn("[privacy-policy] 拒否の監査を記録できません: exceptionClass={}", e.getClass().getSimpleName());
        }
    }

    private static PrivacyPolicyRegistrationResult result(RetentionPolicy row, Long supersededId, boolean replayed) {
        return new PrivacyPolicyRegistrationResult(row.getId(), row.getPolicyId(), row.getPolicyRevision(),
                row.getStatus(), row.getEnvelopeDigest(), supersededId, replayed);
    }

    private static BusinessException invalid() {
        return BusinessException.of(400, "error.privacy.policyInvalidRequest");
    }

    /**
     * 開放の前提の評価結果。
     *
     * @param ready   全て揃っているか
     * @param missing 欠けている前提の固定 code（{@code d01} / {@code d02} / {@code d03} / {@code verifier} / {@code trust}）
     */
    public record Readiness(boolean ready, List<String> missing) {
        public Readiness {
            missing = List.copyOf(missing);
        }
    }

    /** 要求者（principal に束縛された tenant・user・username）。 */
    record Actor(String tenantId, Long userId, String username) {
        static Actor fromSecurityContext() {
            return new Actor(SecurityUtils.currentTenantId(), SecurityUtils.currentUserId(),
                    SecurityUtils.currentUsername());
        }
    }

    /** 有効化の transaction を戻してから監査に残す拒否。 */
    private static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String reason;
        private final BusinessException mapped;

        Rejected(String reason, BusinessException mapped) {
            super(reason, null, false, false);
            this.reason = reason;
            this.mapped = mapped;
        }
    }
}
