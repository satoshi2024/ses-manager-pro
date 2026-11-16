package com.ses.service.lifecycle.dual;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.LogRedaction;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.dto.lifecycle.dual.DualConfirmationCandidateDto;
import com.ses.dto.lifecycle.dual.DualConfirmationDecisionRequest;
import com.ses.dto.lifecycle.dual.DualConfirmationDocumentSelectionRequest;
import com.ses.dto.lifecycle.dual.DualConfirmationResultDto;
import com.ses.dto.lifecycle.dual.DualConfirmationSubmitRequest;
import com.ses.dto.lifecycle.dual.DualConfirmationViewDto;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.LifecycleDualConfirmation;
import com.ses.entity.LifecycleDualProjection;
import com.ses.entity.LifecycleDualSubmission;
import com.ses.entity.LifecycleDualSubmissionDocument;
import com.ses.entity.SysUser;
import com.ses.mapper.AuthorizationEpochMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.LifecycleDualConfirmationMapper;
import com.ses.mapper.LifecycleDualProjectionMapper;
import com.ses.mapper.LifecycleDualSubmissionDocumentMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.document.DocumentEvidenceEligibility;
import com.ses.service.document.DocumentEvidenceEligibility.EligibleVersion;
import com.ses.service.document.DocumentEvidenceStorageVerifier;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationAttemptService.ConfirmAttempt;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationAttemptService.SubmitAttempt;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDenyRecorder.DenyRecord;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDependencyReader.ConfirmDependencies;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDependencyReader.SubmitDependencies;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDependencyReader.TaskContext;
import com.ses.service.lifecycle.evidence.LifecycleCanonicalization;
import com.ses.service.lifecycle.evidence.LifecycleCanonicalization.ConfirmationBinding;
import com.ses.service.lifecycle.evidence.LifecycleCanonicalization.Decision;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan;
import com.ses.service.security.ExplicitActorAuthorizationEvaluator;
import com.ses.service.security.ExplicitActorAuthorizationEvaluator.ExplicitActor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 二者確認（DUAL_CONFIRMATION）の提出・確認・差戻し・照会の入口（NF01細粒度epoch計画 §5.2〜§5.9、W3-21a）。
 *
 * <h2>transaction境界（§5.4）</h2>
 * <ul>
 *   <li>本classはtransactionを持たない。呼出し時に実transactionがあれば {@link IllegalStateException} で拒否する
 *       （ambient transactionを拒否する。H2の {@code @Transactional} テストからも呼べない）。計画は {@code Propagation.NEVER} を
 *       挙げるが、Springの既定（{@code SYNCHRONIZATION_ALWAYS}）では {@code NEVER} の空transactionにもsynchronizationが張られ、
 *       最初のDB読取で接続が要求の終わりまでthreadへ束縛される。各試行の {@code REQUIRES_NEW} は2本目の接続を要するため、
 *       同時要求が接続poolを使い切ると互いに待つ（pool枯渇）。そのため注釈は付けず、実transactionの有無を明示検査するだけにした。</li>
 *   <li>discovery（依存先の列挙・状態と認可の前検査・文書適格性）はtransaction外の単文読取（autocommit）で行い、
 *       lock対象の列挙と早期の拒否にだけ使う（受理の根拠にはしない）。</li>
 *   <li>storage検証（open + SHA-256）はtransactionの外で行い、lockを持ったままI/Oをしない（RR-4）。</li>
 *   <li>試行は {@link LifecycleDualConfirmationAttemptService}（{@code REQUIRES_NEW} + {@code READ_COMMITTED}、別bean）、
 *       拒否eventは試行のrollback後に {@link LifecycleDualConfirmationDenyRecorder}（{@code REQUIRES_NEW}、別bean）。</li>
 * </ul>
 *
 * <h2>再試行（§5.6）</h2>
 * <p>予算は1要求あたり {@value #MAX_ATTEMPTS} 回。依存の変化（{@link LifecycleLockPlan.DependencyChangedException}）、
 * MySQL 1213・1205（{@link PessimisticLockingFailureException}）、文の実行中のtimeout（{@link QueryTimeoutException}）、
 * 期限切れ後の次の文の前のtimeout（{@link TransactionTimedOutException}）、冪等行の同時insert（{@link DuplicateKeyException}）を
 * 数える。業務上の決定（{@link DualConfirmationRejection}・{@link BusinessException}）は数えず、即時に拒否eventを記録して応答する。
 * 予算を使い切ると409 {@code error.lifecycle.concurrentUpdateRetryExhausted}（汎用の {@code LOCK_CONFLICT} の文言にしない。
 * W3-16b review の引継ぎ）。</p>
 *
 * <h2>子スイッチ（§5.11）</h2>
 * <p>{@link Feature#NF01_DUAL_CONFIRMATION} がOFFなら全入口を400 {@code error.lifecycle.dualConfirmationDisabled} で拒否する
 * （拒否eventは記録しない）。</p>
 */
@Slf4j
@Service
public class LifecycleDualConfirmationCoordinator {

    /** 1要求あたりの試行回数（初回 + 再試行2回）。 */
    public static final int MAX_ATTEMPTS = 3;
    /** {@code Idempotency-Key} の形式（§5.7）。末尾空白・改行・許可外文字は400。 */
    static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{1,190}");
    /** 業務commentの上限（文字数。DBの {@code VARCHAR(1000)} と同じ数え方）。 */
    static final int COMMENT_MAX_LENGTH = 1000;
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

    /** 認証済み主体（principalのユーザーIDとsessionのtenant）。tenant・roleはDBの値を正とする。 */
    public record Principal(Long userId, String sessionTenantId) {
    }

    /** 試行間の短い待ち（実装裁量のjitter、上限100ms）。 */
    @FunctionalInterface
    interface RetryPause {
        void pause();
    }

    /** 再試行の予算に数える例外の分類（拒否eventのreasonは {@code RETRY_EXHAUSTED}、分類はWARN logに出す）。 */
    enum RetryCause {
        DEPENDENCY_CHANGED,
        DEADLOCK,
        LOCK_WAIT_TIMEOUT,
        QUERY_TIMEOUT,
        TRANSACTION_TIMEOUT,
        DUPLICATE_KEY;

        /** 予算に数える例外なら分類を、決定的な結果・未知の例外ならnullを返す。原因の連鎖と rollback に隠れた例外も見る。 */
        static RetryCause classify(Throwable failure) {
            Throwable current = failure;
            for (int depth = 0; current != null && depth < 16; depth++) {
                if (current instanceof DualConfirmationRejection || current instanceof BusinessException) {
                    return null;
                }
                if (current instanceof LifecycleLockPlan.DependencyChangedException) {
                    return DEPENDENCY_CHANGED;
                }
                if (current instanceof CannotAcquireLockException) {
                    return LOCK_WAIT_TIMEOUT;
                }
                if (current instanceof PessimisticLockingFailureException) {
                    return DEADLOCK;
                }
                if (current instanceof QueryTimeoutException) {
                    return QUERY_TIMEOUT;
                }
                if (current instanceof TransactionTimedOutException) {
                    return TRANSACTION_TIMEOUT;
                }
                if (current instanceof DuplicateKeyException) {
                    return DUPLICATE_KEY;
                }
                if (current instanceof SQLTimeoutException) {
                    return QUERY_TIMEOUT;
                }
                if (current instanceof SQLException sql) {
                    if (sql.getErrorCode() == 1213) {
                        return DEADLOCK;
                    }
                    if (sql.getErrorCode() == 1205) {
                        return LOCK_WAIT_TIMEOUT;
                    }
                    if (sql.getErrorCode() == 1062) {
                        return DUPLICATE_KEY;
                    }
                }
                if (current instanceof TransactionSystemException system && system.getApplicationException() != null) {
                    // rollbackの失敗は元の例外を getApplicationException() に隠す（既知の坑 (d)）
                    current = system.getApplicationException();
                    continue;
                }
                current = current.getCause() == current ? null : current.getCause();
            }
            return null;
        }
    }

    private final ObjectProvider<FeatureSwitches> featureSwitches;
    private final SysUserMapper sysUserMapper;
    private final ExplicitActorAuthorizationEvaluator evaluator;
    private final LifecycleDualConfirmationDependencyReader dependencyReader;
    private final DocumentEvidenceEligibility eligibility;
    private final DocumentEvidenceStorageVerifier storageVerifier;
    private final LifecycleDualConfirmationAttemptService attemptService;
    private final LifecycleDualConfirmationDenyRecorder denyRecorder;
    private final AuthorizationEpochMapper epochMapper;
    private final LifecycleDualProjectionMapper projectionMapper;
    private final LifecycleDualSubmissionDocumentMapper submissionDocumentMapper;
    private final LifecycleDualConfirmationMapper confirmationMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentMapper documentMapper;
    private final RetryPause retryPause;

    @Autowired
    public LifecycleDualConfirmationCoordinator(ObjectProvider<FeatureSwitches> featureSwitches,
                                                SysUserMapper sysUserMapper,
                                                ExplicitActorAuthorizationEvaluator evaluator,
                                                LifecycleDualConfirmationDependencyReader dependencyReader,
                                                DocumentEvidenceEligibility eligibility,
                                                DocumentEvidenceStorageVerifier storageVerifier,
                                                LifecycleDualConfirmationAttemptService attemptService,
                                                LifecycleDualConfirmationDenyRecorder denyRecorder,
                                                AuthorizationEpochMapper epochMapper,
                                                LifecycleDualProjectionMapper projectionMapper,
                                                LifecycleDualSubmissionDocumentMapper submissionDocumentMapper,
                                                LifecycleDualConfirmationMapper confirmationMapper,
                                                DocumentLinkMapper documentLinkMapper,
                                                DocumentVersionMapper documentVersionMapper,
                                                DocumentMapper documentMapper) {
        this(featureSwitches, sysUserMapper, evaluator, dependencyReader, eligibility, storageVerifier, attemptService,
                denyRecorder, epochMapper, projectionMapper, submissionDocumentMapper, confirmationMapper,
                documentLinkMapper, documentVersionMapper, documentMapper,
                LifecycleDualConfirmationCoordinator::jitter);
    }

    LifecycleDualConfirmationCoordinator(ObjectProvider<FeatureSwitches> featureSwitches,
                                         SysUserMapper sysUserMapper,
                                         ExplicitActorAuthorizationEvaluator evaluator,
                                         LifecycleDualConfirmationDependencyReader dependencyReader,
                                         DocumentEvidenceEligibility eligibility,
                                         DocumentEvidenceStorageVerifier storageVerifier,
                                         LifecycleDualConfirmationAttemptService attemptService,
                                         LifecycleDualConfirmationDenyRecorder denyRecorder,
                                         AuthorizationEpochMapper epochMapper,
                                         LifecycleDualProjectionMapper projectionMapper,
                                         LifecycleDualSubmissionDocumentMapper submissionDocumentMapper,
                                         LifecycleDualConfirmationMapper confirmationMapper,
                                         DocumentLinkMapper documentLinkMapper,
                                         DocumentVersionMapper documentVersionMapper,
                                         DocumentMapper documentMapper,
                                         RetryPause retryPause) {
        this.featureSwitches = featureSwitches;
        this.sysUserMapper = sysUserMapper;
        this.evaluator = evaluator;
        this.dependencyReader = dependencyReader;
        this.eligibility = eligibility;
        this.storageVerifier = storageVerifier;
        this.attemptService = attemptService;
        this.denyRecorder = denyRecorder;
        this.epochMapper = epochMapper;
        this.projectionMapper = projectionMapper;
        this.submissionDocumentMapper = submissionDocumentMapper;
        this.confirmationMapper = confirmationMapper;
        this.documentLinkMapper = documentLinkMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.documentMapper = documentMapper;
        this.retryPause = Objects.requireNonNull(retryPause, "retryPause");
    }

    // ===================================================================== 照会（A1・A5、A2・A6）

    /**
     * 現在のprojection・提出の要約・操作可否（A1・A5）。拒否eventは記録しない（照会）。
     * 業務commentの本文は {@link DualConfirmationCommentDisclosure} の表示範囲外の照会者には返さない（有無だけ）。
     */
    public DualConfirmationViewDto view(long taskId, Principal principal, DualConfirmationChannel channel) {
        requireNoAmbientTransaction();
        requireEnabled();
        ExplicitActor actor = resolvePrincipal(principal);
        TaskContext context = readVisibleTask(actor, taskId, channel);
        requireDualTask(context);
        long generation = currentGeneration(actor.tenantId(), context);
        LifecycleDualProjection projection = projectionMapper.selectOne(new LambdaQueryWrapper<LifecycleDualProjection>()
                .eq(LifecycleDualProjection::getTenantId, actor.tenantId())
                .eq(LifecycleDualProjection::getTaskId, context.taskId()));
        boolean active = "ACTIVE".equals(context.lcCase().getStatus())
                && "IN_PROGRESS".equals(context.task().getStatus());
        DualConfirmationViewDto.DualConfirmationViewDtoBuilder view = DualConfirmationViewDto.builder()
                .taskId(context.taskId())
                .caseId(context.caseId())
                .taskStatus(context.task().getStatus())
                .caseStatus(context.lcCase().getStatus())
                .taskGeneration(generation)
                .canSubmit(active && canEdit(actor, context, channel));
        if (projection == null) {
            return view.build();
        }
        boolean stale = !Objects.equals(projection.getTaskGeneration(), generation);
        view.state(projection.getState())
                .stale(stale)
                .submissionId(projection.getSubmissionId())
                .submissionRevision(projection.getRevision())
                .evidenceSha256(LifecycleDualConfirmationAttemptService.lowerHex(projection.getEvidenceSha256()));
        LifecycleDualSubmission submission = dependencyReader.findSubmission(actor.tenantId(), context.taskId(),
                projection.getSubmissionId());
        if (submission != null) {
            // 業務commentの本文は確認者のrole・その提出の提出者にだけ返す（W3-21b、review P3-3の最小公開）
            boolean commentsVisible = DualConfirmationCommentDisclosure.canReadBody(actor.role(), actor.userId(),
                    submission.getSubmitterUserId());
            view.submitterUserId(submission.getSubmitterUserId())
                    .submittedAt(submission.getCreatedAt())
                    .commentsVisible(commentsVisible)
                    .submissionCommentPresent(DualConfirmationCommentDisclosure.present(submission.getBusinessComment()))
                    .submissionComment(commentsVisible ? submission.getBusinessComment() : null);
            LifecycleDualSubmissionDocument document = submissionDocumentMapper.selectOne(
                    new LambdaQueryWrapper<LifecycleDualSubmissionDocument>()
                            .eq(LifecycleDualSubmissionDocument::getTenantId, actor.tenantId())
                            .eq(LifecycleDualSubmissionDocument::getSubmissionId, submission.getId()));
            if (document != null) {
                view.documentId(document.getDocumentId())
                        .documentVersionId(document.getDocumentVersionId())
                        .documentVersionNo(document.getVersionNo());
            }
            LifecycleDualConfirmation confirmation = confirmationMapper.selectOne(
                    new LambdaQueryWrapper<LifecycleDualConfirmation>()
                            .eq(LifecycleDualConfirmation::getTenantId, actor.tenantId())
                            .eq(LifecycleDualConfirmation::getSubmissionId, submission.getId())
                            .eq(LifecycleDualConfirmation::getTaskId, context.taskId()));
            if (confirmation != null) {
                view.decision(confirmation.getDecision())
                        .confirmerUserId(confirmation.getConfirmerUserId())
                        .decidedAt(confirmation.getDecidedAt())
                        .decisionCommentPresent(DualConfirmationCommentDisclosure.present(
                                confirmation.getDecisionComment()))
                        .decisionComment(commentsVisible ? confirmation.getDecisionComment() : null);
            }
            view.canDecide(channel == DualConfirmationChannel.INTERNAL && active && !stale
                    && LifecycleDualConfirmationAttemptService.STATE_PENDING.equals(projection.getState())
                    && isConfirmerRole(actor)
                    && !Objects.equals(submission.getSubmitterUserId(), actor.userId()));
        }
        return view.build();
    }

    /**
     * 証跡候補（A2・A6）: 案件の要員だけにlinkされた適格なSKILL_SHEETのCLEAN版。照会者が提出者として当該タスクを扱えない場合は
     * 404/403、照会者の文書ACLに入らない場合は空。
     */
    public List<DualConfirmationCandidateDto> candidates(long taskId, Principal principal,
                                                         DualConfirmationChannel channel) {
        requireNoAmbientTransaction();
        requireEnabled();
        ExplicitActor actor = resolvePrincipal(principal);
        TaskContext context = readVisibleTask(actor, taskId, channel);
        requireDualTask(context);
        evaluator.assertCanEditTask(actor, context.task(), context.lcCase(), context.engineer());
        if (!documentAclAllows(actor, context.engineerId())) {
            return List.of();
        }
        String tenant = actor.tenantId();
        List<DualConfirmationCandidateDto> candidates = new ArrayList<>();
        List<Long> documentIds = documentLinkMapper.findDocumentIdsByTargetForTenant(tenant,
                DocumentEvidenceEligibility.ACCEPTED_TARGET_TYPE, context.engineerId());
        for (Long documentId : new TreeSet<>(documentIds == null ? List.<Long>of() : documentIds)) {
            List<DocumentVersion> clean = new ArrayList<>();
            List<DocumentVersion> versions = documentVersionMapper.findByTenantAndDocumentId(tenant, documentId);
            if (versions != null) {
                versions.stream()
                        .filter(v -> v != null && v.getId() != null && "CLEAN".equals(v.getScanStatus())
                                && Objects.equals(v.getDocumentId(), documentId))
                        .sorted(Comparator.comparing(DocumentVersion::getVersionNo,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .forEach(clean::add);
            }
            if (clean.isEmpty()) {
                continue;
            }
            // 条件3〜8は文書単位、条件9（同tenant・同文書・CLEAN）は上の絞込みで満たすため、1版の評価で文書の適格性が決まる。
            DocumentEvidenceEligibility.Result result = eligibility.evaluate(new DocumentEvidenceEligibility.Candidate(
                    tenant, documentId, clean.get(0).getId(), context.engineerId()));
            if (!result.eligible()) {
                continue;
            }
            Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                    .eq(Document::getId, documentId).eq(Document::getTenantId, tenant));
            for (DocumentVersion version : clean) {
                candidates.add(DualConfirmationCandidateDto.builder()
                        .documentId(documentId)
                        .documentVersionId(version.getId())
                        .versionNo(version.getVersionNo())
                        .createdAt(version.getCreatedAt())
                        .title(document == null ? null : document.getTitle())
                        .build());
            }
        }
        return candidates;
    }

    // ===================================================================== 提出（A3・A7）

    public DualConfirmationResultDto submit(long taskId, Principal principal, DualConfirmationChannel channel,
                                            DualConfirmationSubmitRequest body, String idempotencyKey) {
        requireNoAmbientTransaction();
        requireEnabled();
        String key = requireIdempotencyKey(idempotencyKey);
        requireWellFormedSubmit(body);
        if (taskId <= 0) {
            throw BusinessException.of(400, "error.validationError");
        }
        ExplicitActor actor = resolvePrincipal(principal);
        DenyContext deny = new DenyContext(actor, LifecycleDualConfirmationAttemptService.OPERATION_SUBMIT, taskId, null);
        try {
            DualConfirmationDocumentSelectionRequest selection = requireSingleSelection(body);
            long documentId = selection.getDocumentId();
            long documentVersionId = selection.getDocumentVersionId();
            String requestSha256 = new LifecycleCanonicalization.Request(actor.tenantId(), taskId,
                    body.getTaskGeneration(), LifecycleCanonicalization.Operation.SUBMIT, actor.userId(), key,
                    body.getComment(), List.of(new LifecycleCanonicalization.DocumentSelection(documentId,
                    documentVersionId))).sha256();
            RetryCause lastCause = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                deny.attempts = attempt;
                TaskContext context = dependencyReader.readTaskContext(actor.tenantId(), taskId)
                        .orElseThrow(() -> DualConfirmationRejection.of(404, "error.lifecycle.taskNotFound",
                                DualConfirmationDenyReason.NOT_FOUND_OR_FOREIGN));
                deny.resolved(context, discoveredGeneration(actor.tenantId(), context), null);
                preAuthorizeSubmitter(actor, channel, context);
                requireDualTask(context);
                EligibleVersion version = requireEligibleAtDiscovery(actor.tenantId(), documentId, documentVersionId,
                        context.engineerId());
                requireStorageMatches(version);
                SubmitDependencies dependencies = new SubmitDependencies(actor.tenantId(), context, actor.userId(),
                        dependencyReader.submitterOrganizations(actor), documentId, documentVersionId);
                try {
                    return attemptService.submit(new SubmitAttempt(actor.tenantId(), taskId, actor.userId(), channel,
                            body.getTaskGeneration(), documentId, documentVersionId, body.getComment(), key,
                            requestSha256, dependencies.plan(), version, actor.asOf()));
                } catch (RuntimeException failure) {
                    lastCause = retryCauseOrRethrow(failure, attempt, taskId);
                }
            }
            throw retryExhausted(lastCause, taskId);
        } catch (DualConfirmationRejection rejection) {
            recordIfNeeded(deny, rejection);
            throw rejection.toBusinessException();
        } catch (BusinessException business) {
            record(deny, DualConfirmationDenyReason.fromMessageKey(business.getMessageKey()), business.getCode());
            throw business;
        }
    }

    // ===================================================================== 確認・差戻し（A4）

    public DualConfirmationResultDto decide(long taskId, long submissionId, Principal principal,
                                            DualConfirmationDecisionRequest body, String idempotencyKey) {
        requireNoAmbientTransaction();
        requireEnabled();
        String key = requireIdempotencyKey(idempotencyKey);
        Decision decision = requireWellFormedDecision(body);
        if (taskId <= 0 || submissionId <= 0) {
            throw BusinessException.of(400, "error.validationError");
        }
        ExplicitActor actor = resolvePrincipal(principal);
        DenyContext deny = new DenyContext(actor, decision == Decision.CONFIRM
                ? LifecycleDualConfirmationAttemptService.OPERATION_CONFIRM
                : LifecycleDualConfirmationAttemptService.OPERATION_REJECT, taskId, submissionId);
        try {
            ConfirmationBinding binding = new ConfirmationBinding(submissionId, body.getSubmissionRevision(),
                    body.getEvidenceSha256().toLowerCase(Locale.ROOT));
            RetryCause lastCause = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                deny.attempts = attempt;
                ConfirmDependencies dependencies = dependencyReader.readConfirm(actor.tenantId(), taskId, submissionId,
                        actor.userId(), actor.asOf())
                        .orElseThrow(() -> DualConfirmationRejection.of(404, "error.lifecycle.taskNotFound",
                                DualConfirmationDenyReason.NOT_FOUND_OR_FOREIGN));
                TaskContext context = dependencies.context();
                deny.resolved(context, discoveredGeneration(actor.tenantId(), context), submissionId);
                preAuthorizeConfirmer(actor, dependencies);
                EligibleVersion version = eligibleOrNull(actor.tenantId(), dependencies);
                if (version != null) {
                    requireStorageMatches(version);
                }
                try {
                    return attemptService.confirm(new ConfirmAttempt(actor.tenantId(), taskId, submissionId,
                            actor.userId(), binding, decision, body.getComment(), key, dependencies.plan(), version,
                            actor.asOf()));
                } catch (RuntimeException failure) {
                    lastCause = retryCauseOrRethrow(failure, attempt, taskId);
                }
            }
            throw retryExhausted(lastCause, taskId);
        } catch (DualConfirmationRejection rejection) {
            recordIfNeeded(deny, rejection);
            throw rejection.toBusinessException();
        } catch (BusinessException business) {
            record(deny, DualConfirmationDenyReason.fromMessageKey(business.getMessageKey()), business.getCode());
            throw business;
        }
    }

    // ===================================================================== discovery・前検査

    /** 照会・候補の対象タスク（照会者のtenant内で、照会者が案件とタスクを閲覧できること）。 */
    private TaskContext readVisibleTask(ExplicitActor actor, long taskId, DualConfirmationChannel channel) {
        TaskContext context = dependencyReader.readTaskContext(actor.tenantId(), taskId)
                .orElseThrow(() -> BusinessException.of(404, "error.lifecycle.taskNotFound"));
        if ((channel == DualConfirmationChannel.SELF && !"要員".equals(actor.role()))
                || !evaluator.canViewCase(actor, context.lcCase(), context.engineer())
                || !evaluator.isTaskVisible(actor, context.task())) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound");
        }
        return context;
    }

    /** 提出者の述語の前検査（lockなし。受理の可否はattemptがlock下で判定し直す）。 */
    private void preAuthorizeSubmitter(ExplicitActor actor, DualConfirmationChannel channel, TaskContext context) {
        if (channel == DualConfirmationChannel.SELF && !"要員".equals(actor.role())) {
            throw DualConfirmationRejection.of(403, "error.lifecycle.accessDenied",
                    DualConfirmationDenyReason.SCOPE_DENIED);
        }
        try {
            evaluator.assertCanEditTask(actor, context.task(), context.lcCase(), context.engineer());
        } catch (BusinessException denied) {
            throw DualConfirmationRejection.of(denied.getCode(), denied.getMessageKey() == null
                            ? "error.lifecycle.taskEditDenied" : denied.getMessageKey(),
                    DualConfirmationDenyReason.fromMessageKey(denied.getMessageKey()));
        }
    }

    /** 確認者の前検査（roleと自己確認。残りの述語はattemptがlock下で判定する）。 */
    private void preAuthorizeConfirmer(ExplicitActor actor, ConfirmDependencies dependencies) {
        if (!isConfirmerRole(actor)) {
            throw DualConfirmationRejection.of(403, "error.lifecycle.confirmerRoleDenied",
                    DualConfirmationDenyReason.CONFIRMER_ROLE_DENIED);
        }
        if (Objects.equals(actor.userId(), dependencies.submitterUserId())) {
            throw DualConfirmationRejection.of(403, "error.lifecycle.selfConfirmationDenied",
                    DualConfirmationDenyReason.SELF_CONFIRMATION);
        }
    }

    private EligibleVersion requireEligibleAtDiscovery(String tenant, long documentId, long documentVersionId,
                                                       long engineerId) {
        DocumentEvidenceEligibility.Result result = eligibility.evaluate(
                new DocumentEvidenceEligibility.Candidate(tenant, documentId, documentVersionId, engineerId));
        if (!result.eligible()) {
            DocumentEvidenceEligibility.Verdict verdict = result.verdict();
            throw DualConfirmationRejection.of(verdict.httpStatus(), verdict.messageKey(),
                    DualConfirmationDenyReason.valueOf(verdict.reasonCode()));
        }
        return result.version();
    }

    /** 確認のdiscoveryでは、不適格な文書をここで拒否せずattemptへ渡す（拒否理由は §5.5 の順でattemptが決める）。 */
    private EligibleVersion eligibleOrNull(String tenant, ConfirmDependencies dependencies) {
        DocumentEvidenceEligibility.Result result = eligibility.evaluate(new DocumentEvidenceEligibility.Candidate(
                tenant, dependencies.document().getDocumentId(), dependencies.document().getDocumentVersionId(),
                dependencies.context().engineerId()));
        return result.eligible() ? result.version() : null;
    }

    /** storageを開き内容のSHA-256を版と照合する（transactionの外。lockを持ったままI/Oをしない）。 */
    private void requireStorageMatches(EligibleVersion version) {
        DocumentEvidenceStorageVerifier.Outcome outcome = storageVerifier.verify(version);
        if (outcome != DocumentEvidenceStorageVerifier.Outcome.MATCH) {
            // 不一致・読取不能の詳細は検証部品がERRORで記録する（storage key・hashは出さない）
            throw DualConfirmationRejection.of(409, "error.lifecycle.evidenceDocumentUnavailable",
                    DualConfirmationDenyReason.STORAGE_INTEGRITY);
        }
    }

    /** DUAL以外のタスクは400（照会でも使うため業務例外で投げる。提出では拒否eventの reason が SUBMISSION_STALE になる）。 */
    private void requireDualTask(TaskContext context) {
        if (!LifecycleDualConfirmationAttemptService.EVIDENCE_TYPE_DUAL.equals(context.task().getEvidenceType())) {
            throw BusinessException.of(400, "error.lifecycle.invalidStateTransition");
        }
    }

    private boolean canEdit(ExplicitActor actor, TaskContext context, DualConfirmationChannel channel) {
        if (channel == DualConfirmationChannel.SELF && !"要員".equals(actor.role())) {
            return false;
        }
        try {
            evaluator.assertCanEditTask(actor, context.task(), context.lcCase(), context.engineer());
            return true;
        } catch (BusinessException denied) {
            return false;
        }
    }

    private boolean documentAclAllows(ExplicitActor actor, long engineerId) {
        return LifecycleDualConfirmationAttemptService.ROLE_ADMIN.equals(actor.role())
                || evaluator.isEngineerAllowedByDataScope(actor, engineerId);
    }

    private static boolean isConfirmerRole(ExplicitActor actor) {
        return LifecycleDualConfirmationAttemptService.ROLE_ADMIN.equals(actor.role())
                || LifecycleDualConfirmationAttemptService.ROLE_HR.equals(actor.role());
    }

    /** 照会用の現在のタスク世代（lockなし）。読めなければ409。 */
    private long currentGeneration(String tenant, TaskContext context) {
        AuthorizationEpochMapper.EpochRow row = epochMapper.selectTaskGeneration(context.taskId(), context.caseId(),
                tenant);
        if (row == null || !row.usable()) {
            throw BusinessException.of(409, AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY);
        }
        return row.getEpoch();
    }

    /** 拒否eventに載せる世代（読めなければnull）。 */
    private Long discoveredGeneration(String tenant, TaskContext context) {
        AuthorizationEpochMapper.EpochRow row = epochMapper.selectTaskGeneration(context.taskId(), context.caseId(),
                tenant);
        return row == null || !row.usable() ? null : row.getEpoch();
    }

    // ===================================================================== 入力の検査（拒否eventに記録しない）

    /**
     * 実transactionに加えて、実transactionを伴わないsynchronization（{@code SUPPORTS}・{@code NOT_SUPPORTED}・{@code NEVER} の
     * 空transaction）の中からの呼出しも拒否する。synchronizationが有効だとdiscoveryの最初の読取で接続がthreadへ束縛され、
     * 試行の {@code REQUIRES_NEW} と合わせて1要求が接続を2本持つため（W3-21a review P3-1）。
     */
    private void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("二者確認のcoordinatorはtransactionの外から呼び出してください"
                    + "（試行ごとに新しいtransactionを使うため、ambient transactionとsynchronizationを受け付けません）");
        }
    }

    private void requireEnabled() {
        FeatureSwitches switches = featureSwitches == null ? null : featureSwitches.getIfAvailable();
        if (switches == null || !switches.isEnabled(Feature.NF01_DUAL_CONFIRMATION)) {
            throw BusinessException.of(400, "error.lifecycle.dualConfirmationDisabled");
        }
    }

    /** {@code Idempotency-Key} の形式（1〜190文字の {@code [A-Za-z0-9._~-]}。末尾空白・改行・空は400）。 */
    static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw BusinessException.of(400, "error.lifecycle.idempotencyKeyInvalid");
        }
        return idempotencyKey;
    }

    private static void requireWellFormedSubmit(DualConfirmationSubmitRequest body) {
        if (body == null || !body.getUnknownFields().isEmpty() || body.getTaskGeneration() == null
                || body.getTaskGeneration() < 0 || !wellFormedComment(body.getComment())) {
            throw BusinessException.of(400, "error.validationError");
        }
    }

    private static Decision requireWellFormedDecision(DualConfirmationDecisionRequest body) {
        if (body == null || !body.getUnknownFields().isEmpty() || body.getSubmissionRevision() == null
                || body.getSubmissionRevision() <= 0 || body.getEvidenceSha256() == null
                || !SHA256_HEX.matcher(body.getEvidenceSha256()).matches() || !wellFormedComment(body.getComment())) {
            throw BusinessException.of(400, "error.validationError");
        }
        if ("CONFIRM".equals(body.getDecision())) {
            return Decision.CONFIRM;
        }
        if ("REJECT".equals(body.getDecision())) {
            return Decision.REJECT;
        }
        throw BusinessException.of(400, "error.validationError");
    }

    /** 文書選択の検査（1件・版IDあり）。こちらは拒否eventに記録する（§5.8 の reason に含まれる）。 */
    private static DualConfirmationDocumentSelectionRequest requireSingleSelection(DualConfirmationSubmitRequest body) {
        List<DualConfirmationDocumentSelectionRequest> selections = body.getDocumentSelections();
        if (selections == null || selections.size() != 1 || selections.get(0) == null) {
            throw DualConfirmationRejection.of(400, "error.lifecycle.evidenceSelectionInvalid",
                    DualConfirmationDenyReason.EVIDENCE_SELECTION_INVALID);
        }
        DualConfirmationDocumentSelectionRequest selection = selections.get(0);
        if (selection.getDocumentId() == null || selection.getDocumentId() <= 0) {
            throw DualConfirmationRejection.of(400, "error.document.notFound",
                    DualConfirmationDenyReason.NOT_FOUND_OR_FOREIGN);
        }
        if (selection.getDocumentVersionId() == null || selection.getDocumentVersionId() <= 0) {
            throw DualConfirmationRejection.of(400, "error.lifecycle.evidenceVersionRequired",
                    DualConfirmationDenyReason.EVIDENCE_VERSION_REQUIRED);
        }
        return selection;
    }

    /** 1000文字以内で、孤立surrogateを含まない（canonical hashの前提）。 */
    static boolean wellFormedComment(String comment) {
        if (comment == null) {
            return true;
        }
        if (comment.codePointCount(0, comment.length()) > COMMENT_MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < comment.length(); i++) {
            char current = comment.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= comment.length() || !Character.isLowSurrogate(comment.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    /** principalの利用者をDBで解決する（tenant・role・statusはDBの値）。解決できない主体は拒否eventに記録しない。 */
    private ExplicitActor resolvePrincipal(Principal principal) {
        if (principal == null || principal.userId() == null) {
            throw BusinessException.of(401, "error.unauthorized");
        }
        SysUser user = sysUserMapper.selectById(principal.userId());
        String tenant = user == null || user.getTenantId() == null || user.getTenantId().isBlank()
                ? null : user.getTenantId().trim();
        String sessionTenant = principal.sessionTenantId();
        if (tenant == null || (sessionTenant != null && !sessionTenant.isBlank() && !tenant.equals(sessionTenant.trim()))) {
            throw BusinessException.of(403, "error.lifecycle.accessDenied");
        }
        return evaluator.resolveActor(tenant, user.getId(), LocalDate.now())
                .orElseThrow(() -> BusinessException.of(403, "error.lifecycle.accessDenied"));
    }

    // ===================================================================== 再試行・拒否event

    private RetryCause retryCauseOrRethrow(RuntimeException failure, int attempt, long taskId) {
        RetryCause cause = RetryCause.classify(failure);
        if (cause == null) {
            throw failure;
        }
        log.warn("[二者確認] 試行を中断しました（再試行の予算に計上）: taskId={} attempt={}/{} cause={} exceptionType={}",
                taskId, attempt, MAX_ATTEMPTS, cause, LogRedaction.exceptionType(failure));
        if (attempt < MAX_ATTEMPTS) {
            retryPause.pause();
        }
        return cause;
    }

    private static DualConfirmationRejection retryExhausted(RetryCause lastCause, long taskId) {
        log.warn("[二者確認] 再試行の予算を使い切りました: taskId={} attempts={} lastCause={}", taskId, MAX_ATTEMPTS,
                lastCause);
        return DualConfirmationRejection.of(409, "error.lifecycle.concurrentUpdateRetryExhausted",
                DualConfirmationDenyReason.RETRY_EXHAUSTED);
    }

    private void recordIfNeeded(DenyContext deny, DualConfirmationRejection rejection) {
        if (rejection.recordable()) {
            record(deny, rejection.reason(), rejection.status());
        }
    }

    /** 拒否eventの追記（attemptのrollback後、別transaction）。失敗はWARNだけで応答を変えない。 */
    private void record(DenyContext deny, DualConfirmationDenyReason reason, int httpStatus) {
        try {
            denyRecorder.record(new DenyRecord(deny.tenantId, deny.actorUserId, deny.operation, deny.requestedTaskId,
                    deny.requestedSubmissionId, deny.caseId, deny.taskId, deny.taskGeneration, deny.submissionId,
                    reason, httpStatus, deny.attempts));
        } catch (RuntimeException failure) {
            log.warn("[二者確認] 拒否eventを記録できませんでした（応答は変えません）: reason={} exceptionType={}",
                    reason, LogRedaction.exceptionType(failure));
        }
    }

    private static void jitter() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(10, 101));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 拒否eventの文脈（主体のtenant内で解決できた案件・タスク・世代だけを持つ）。 */
    private static final class DenyContext {
        private final String tenantId;
        private final long actorUserId;
        private final String operation;
        private final Long requestedTaskId;
        private final Long requestedSubmissionId;
        private Long caseId;
        private Long taskId;
        private Long taskGeneration;
        private Long submissionId;
        private int attempts;

        private DenyContext(ExplicitActor actor, String operation, long requestedTaskId, Long requestedSubmissionId) {
            this.tenantId = actor.tenantId();
            this.actorUserId = actor.userId();
            this.operation = operation;
            this.requestedTaskId = requestedTaskId;
            this.requestedSubmissionId = requestedSubmissionId;
        }

        private void resolved(TaskContext context, Long generation, Long submission) {
            this.caseId = context.caseId();
            this.taskId = context.taskId();
            this.taskGeneration = generation;
            this.submissionId = submission;
        }
    }
}
