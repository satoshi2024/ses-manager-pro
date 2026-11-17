package com.ses.service.lifecycle.dual;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.audit.ActorType;
import com.ses.common.audit.ConfirmationSource;
import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.dual.DualConfirmationResultDto;
import com.ses.entity.LifecycleDualConfirmation;
import com.ses.entity.LifecycleDualEvent;
import com.ses.entity.LifecycleDualIdempotency;
import com.ses.entity.LifecycleDualProjection;
import com.ses.entity.LifecycleDualSubmission;
import com.ses.entity.LifecycleDualSubmissionDocument;
import com.ses.entity.LifecycleDualSubmissionOrgFence;
import com.ses.entity.LifecycleEvent;
import com.ses.entity.LifecycleEvidenceLink;
import com.ses.entity.LifecycleTask;
import com.ses.mapper.LifecycleDualConfirmationMapper;
import com.ses.mapper.LifecycleDualEventMapper;
import com.ses.mapper.LifecycleDualIdempotencyMapper;
import com.ses.mapper.LifecycleDualProjectionMapper;
import com.ses.mapper.LifecycleDualSubmissionDocumentMapper;
import com.ses.mapper.LifecycleDualSubmissionMapper;
import com.ses.mapper.LifecycleDualSubmissionOrgFenceMapper;
import com.ses.mapper.LifecycleEventMapper;
import com.ses.mapper.LifecycleEvidenceLinkMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.service.document.DocumentEvidenceEligibility;
import com.ses.service.document.DocumentEvidenceEligibility.EligibleVersion;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDependencyReader.ConfirmDependencies;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDependencyReader.SubmitDependencies;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDependencyReader.TaskContext;
import com.ses.service.lifecycle.evidence.LifecycleAuthorizationFence;
import com.ses.service.lifecycle.evidence.LifecycleCanonicalization;
import com.ses.service.lifecycle.evidence.LifecycleCanonicalization.ConfirmationBinding;
import com.ses.service.lifecycle.evidence.LifecycleCanonicalization.Decision;
import com.ses.service.lifecycle.evidence.LifecycleLockExecutor;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan;
import com.ses.service.security.ExplicitActorAuthorizationEvaluator;
import com.ses.service.security.ExplicitActorAuthorizationEvaluator.ExplicitActor;
import com.ses.service.security.epoch.AuthorizationEpochReader;
import com.ses.service.security.epoch.TransactionBoundState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;

/**
 * 二者確認の1回の試行（NF01細粒度epoch計画 §5.4・§5.5・§3.4）。coordinatorとは別beanで、試行ごとに新しい
 * {@code REQUIRES_NEW} + {@code READ_COMMITTED} のtransaction（timeout {@value #ATTEMPT_TIMEOUT_SECONDS} 秒）で動く。
 *
 * <p>判定の順序はSUBMIT・CONFIRMで共通に「lock → 依存先の再読込と照合 → 認可 → epoch → 文書適格性 → 冪等性の検索
 * （一致すれば保存済みの結果）→ 世代・projection・タスクの状態 → 書込み」とする。</p>
 *
 * <ul>
 *   <li>lockはdiscoveryで作ったplanを {@link LifecycleLockExecutor} で1回だけ取る。lock後は同じ部品
 *       （{@link LifecycleDualConfirmationDependencyReader}）で依存先を読み直し、planが違えば
 *       {@link LifecycleLockPlan.DependencyChangedException} で試行ごとrollbackする（途中でlockを足さない。§3.5 規則1）。</li>
 *   <li>lock後の再読込が現在の行を読むことはREAD_COMMITTEDで保証する（文ごとに新しいsnapshot。REPEATABLE_READでは最初の
 *       通常読取でread viewが固定され、lock待ちの間にcommitされた値を見落とす）。MyBatisのsession cacheは
 *       {@code local-cache-scope: statement} で文単位に消える。epoch値は {@link AuthorizationEpochReader} がlocking readで読む。</li>
 *   <li>決定的な拒否は {@link DualConfirmationRejection} で投げ、試行をrollbackさせる。拒否eventはcoordinatorがrollback後に
 *       別transactionで追記する（§5.8）。</li>
 *   <li>不変6表は {@code AppendOnlyMapper} の {@code insert} と読取だけを使い、projectionは
 *       {@link LifecycleDualProjectionMapper#compareAndSet} だけで更新する。hashは常に小文字hexで書き、比較する。</li>
 *   <li>B0の列名（{@code task_generation} 等）は注釈SQLや文字列に書かず、entityのmetadata（Lambda wrapper）で扱う。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LifecycleDualConfirmationAttemptService {

    /** attemptのtransaction timeout（秒。計画 §5.4 の推奨値）。 */
    public static final int ATTEMPT_TIMEOUT_SECONDS = 10;

    static final String OPERATION_SUBMIT = "SUBMIT";
    static final String OPERATION_CONFIRM = "CONFIRM";
    static final String OPERATION_REJECT = "REJECT";
    static final String STATE_PENDING = "PENDING";
    static final String STATE_CONFIRMED = "CONFIRMED";
    static final String STATE_REJECTED = "REJECTED";
    static final String EVIDENCE_TYPE_DUAL = "DUAL_CONFIRMATION";
    static final String ROLE_ADMIN = "管理者";
    static final String ROLE_HR = "HR";
    private static final Set<String> CONFIRMER_ROLES = Set.of(ROLE_ADMIN, ROLE_HR);
    private static final String TASK_IN_PROGRESS = "IN_PROGRESS";
    private static final String TASK_COMPLETED = "COMPLETED";
    private static final String CASE_ACTIVE = "ACTIVE";

    private final LifecycleLockExecutor lockExecutor;
    private final AuthorizationEpochReader epochReader;
    private final ExplicitActorAuthorizationEvaluator evaluator;
    private final DocumentEvidenceEligibility eligibility;
    private final LifecycleDualConfirmationDependencyReader dependencyReader;
    private final LifecycleDualSubmissionMapper submissionMapper;
    private final LifecycleDualSubmissionDocumentMapper submissionDocumentMapper;
    private final LifecycleDualSubmissionOrgFenceMapper orgFenceMapper;
    private final LifecycleDualConfirmationMapper confirmationMapper;
    private final LifecycleDualProjectionMapper projectionMapper;
    private final LifecycleDualIdempotencyMapper idempotencyMapper;
    private final LifecycleDualEventMapper dualEventMapper;
    private final LifecycleTaskMapper taskMapper;
    private final LifecycleEventMapper lifecycleEventMapper;
    private final LifecycleEvidenceLinkMapper evidenceLinkMapper;
    private final LifecycleDualConfirmationNotifier notifier;
    private final ObjectMapper objectMapper;

    /**
     * 提出の試行の入力（coordinatorのdiscovery結果とstorage検証済みの版）。
     *
     * @param requestSha256 bodyの世代で作ったrequest hash（小文字hex）
     */
    public record SubmitAttempt(String tenantId, long taskId, long submitterUserId, DualConfirmationChannel channel,
                                long requestedTaskGeneration, long documentId, long documentVersionId,
                                String comment, String idempotencyKey, String requestSha256,
                                LifecycleLockPlan plan, EligibleVersion verifiedVersion, LocalDate asOf) {
        public SubmitAttempt {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(requestSha256, "requestSha256");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(verifiedVersion, "verifiedVersion");
            Objects.requireNonNull(asOf, "asOf");
        }
    }

    /**
     * 確認・差戻しの試行の入力。{@code binding} は確認者が画面で見た提出（bodyの値）。
     *
     * @param verifiedVersion discoveryでstorage検証した版。discovery時点で文書が不適格だった場合はnull（attemptが §5.5 の順に
     *                        epoch → 文書適格性で拒否理由を決める。適格に戻っていれば依存の変化として新しいdiscoveryからやり直す）
     */
    public record ConfirmAttempt(String tenantId, long taskId, long submissionId, long confirmerUserId,
                                 ConfirmationBinding binding, Decision decision, String comment,
                                 String idempotencyKey, LifecycleLockPlan plan, EligibleVersion verifiedVersion,
                                 LocalDate asOf) {
        public ConfirmAttempt {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(asOf, "asOf");
        }
    }

    // ===================================================================== SUBMIT

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class, timeout = ATTEMPT_TIMEOUT_SECONDS)
    public DualConfirmationResultDto submit(SubmitAttempt attempt) {
        TransactionBoundState.requireActualTransaction();
        String tenant = attempt.tenantId();
        // 1. lock → 依存先の再読込と照合
        lockExecutor.acquire(attempt.plan());
        ExplicitActor submitter = evaluator.resolveActor(tenant, attempt.submitterUserId(), attempt.asOf())
                .orElseThrow(() -> DualConfirmationRejection.of(403, "error.lifecycle.accessDenied",
                        DualConfirmationDenyReason.SCOPE_DENIED));
        SubmitDependencies current = dependencyReader.readSubmit(tenant, attempt.taskId(), submitter,
                attempt.documentId(), attempt.documentVersionId())
                .orElseThrow(LifecycleLockPlan.DependencyChangedException::new);
        attempt.plan().requireSameDependencies(current.plan());
        TaskContext context = current.context();

        // 2. 認可（提出者の述語）
        authorizeSubmitter(submitter, attempt.channel(), context, false);

        // 3. epoch（新規提出ではsnapshotとして保存する値）
        long generation = epochReader.readTaskGeneration(tenant, context.caseId(), context.taskId());
        LifecycleAuthorizationFence fence = currentFence(tenant, submitter.userId(), context.engineerId(),
                current.organizations());
        long documentEpoch = epochReader.readDocument(tenant, attempt.documentId());

        // 4. 提出者の文書ACLと文書の可視性 → 文書適格性。範囲外の文書は適格性の詳細を返さず、存在しない文書と同じ
        //    400 error.document.notFound にする（coordinatorのdiscoveryと同じ順。W3-21a review P3-5、計画 §5.9）
        if (!documentAclAllows(submitter, context.engineerId())) {
            throw DualConfirmationRejection.of(400, "error.document.notFound", DualConfirmationDenyReason.SCOPE_DENIED);
        }
        if (!eligibility.linkedToEngineer(tenant, attempt.documentId(), context.engineerId())) {
            throw DualConfirmationRejection.of(400, "error.document.notFound",
                    DualConfirmationDenyReason.NOT_FOUND_OR_FOREIGN);
        }
        EligibleVersion version = requireEligible(tenant, attempt.documentId(), attempt.documentVersionId(),
                context.engineerId(), attempt.verifiedVersion());

        // 5. 冪等性（現在のタスク世代で検索する）
        LifecycleDualIdempotency stored = findIdempotency(tenant, context.taskId(), generation, OPERATION_SUBMIT,
                submitter.userId(), attempt.idempotencyKey());
        if (stored != null) {
            return replaySubmit(stored, attempt, context, generation, fence, documentEpoch);
        }

        // 6. 状態
        requireDualTask(context.task(), 400, "error.lifecycle.invalidStateTransition");
        // 400 の状態の不成立は INVALID_STATE、409 の世代不一致は SUBMISSION_STALE（W3-21a review P3-8）
        if (!CASE_ACTIVE.equals(context.lcCase().getStatus())) {
            throw DualConfirmationRejection.of(400, "error.lifecycle.caseNotActive",
                    DualConfirmationDenyReason.INVALID_STATE);
        }
        if (!TASK_IN_PROGRESS.equals(context.task().getStatus())) {
            throw DualConfirmationRejection.of(400, "error.lifecycle.invalidStateTransition",
                    DualConfirmationDenyReason.INVALID_STATE);
        }
        if (attempt.requestedTaskGeneration() != generation) {
            throw DualConfirmationRejection.of(409, "error.lifecycle.submissionStale",
                    DualConfirmationDenyReason.SUBMISSION_STALE);
        }

        // 7. 書込み（タスク行は変更しない）
        return writeSubmission(attempt, submitter, context, generation, fence, documentEpoch, version);
    }

    private DualConfirmationResultDto replaySubmit(LifecycleDualIdempotency stored, SubmitAttempt attempt,
                                                   TaskContext context, long generation,
                                                   LifecycleAuthorizationFence currentFence, long documentEpoch) {
        if (!attempt.requestSha256().equals(lowerHex(stored.getRequestSha256()))) {
            throw DualConfirmationRejection.of(409, "error.lifecycle.idempotencyConflict",
                    DualConfirmationDenyReason.IDEMPOTENCY_CONFLICT);
        }
        // 冪等行のsubmission_idはDBで束縛されないため、tenant・タスク・世代・提出者を条件に読み直す（W3-18 review P3-8）。
        LifecycleDualSubmission submission = submissionMapper.selectOne(new LambdaQueryWrapper<LifecycleDualSubmission>()
                .eq(LifecycleDualSubmission::getTenantId, attempt.tenantId())
                .eq(LifecycleDualSubmission::getId, stored.getSubmissionId())
                .eq(LifecycleDualSubmission::getTaskId, context.taskId())
                .eq(LifecycleDualSubmission::getTaskGeneration, generation)
                .eq(LifecycleDualSubmission::getSubmitterUserId, attempt.submitterUserId()));
        if (submission == null) {
            log.warn("[二者確認] 冪等行が指す提出を読めません: taskId={} idempotencyId={}", context.taskId(), stored.getId());
            throw DualConfirmationRejection.of(409, "error.lifecycle.idempotencyConflict",
                    DualConfirmationDenyReason.IDEMPOTENCY_CONFLICT);
        }
        requireSnapshotStillCurrent(submission, currentFence, documentEpoch);
        LifecycleDualProjection projection = findProjection(attempt.tenantId(), context.taskId());
        return result(context.taskId(), submission, null, projectionStateFor(projection, submission),
                context.task().getStatus(), true);
    }

    private DualConfirmationResultDto writeSubmission(SubmitAttempt attempt, ExplicitActor submitter,
                                                      TaskContext context, long generation,
                                                      LifecycleAuthorizationFence fence, long documentEpoch,
                                                      EligibleVersion version) {
        String tenant = attempt.tenantId();
        LocalDateTime now = LocalDateTime.now();
        LifecycleDualProjection previous = findProjection(tenant, context.taskId());
        int revision = nextRevision(tenant, context.taskId(), generation);
        LifecycleCanonicalization.EvidenceSnapshot evidence = new LifecycleCanonicalization.EvidenceSnapshot(tenant,
                List.of(new LifecycleCanonicalization.DocumentVersionSnapshot(version.documentId(),
                        version.documentVersionId(), version.versionNo(), version.sha256(), documentEpoch)));
        String evidenceSha256 = evidence.sha256();

        LifecycleDualSubmission submission = LifecycleDualSubmission.builder()
                .tenantId(tenant)
                .caseId(context.caseId())
                .taskId(context.taskId())
                .engineerId(context.engineerId())
                .taskGeneration(generation)
                .revision(revision)
                .submitterUserId(submitter.userId())
                .submitterRoleSnapshot(submitter.role())
                .globalEpoch(fence.global())
                .submitterActorEpoch(fence.submitterActor())
                .engineerScopeEpoch(fence.engineer())
                .taskVersionSnapshot(context.task().getVersion() == null ? 0 : context.task().getVersion())
                .evidenceCanonicalJson(evidence.canonicalJson())
                .evidenceSha256(evidenceSha256)
                .requestSha256(attempt.requestSha256())
                .businessComment(blankToNull(attempt.comment()))
                .build();
        submissionMapper.insert(submission);
        long submissionId = submission.getId();
        submissionDocumentMapper.insert(LifecycleDualSubmissionDocument.builder()
                .tenantId(tenant)
                .submissionId(submissionId)
                .documentId(version.documentId())
                .documentVersionId(version.documentVersionId())
                .versionNo(version.versionNo())
                .sha256(lowerHex(version.sha256()))
                .evidenceEligibilityEpoch(documentEpoch)
                .build());
        fence.organizations().forEach((organizationId, epoch) -> orgFenceMapper.insert(
                LifecycleDualSubmissionOrgFence.builder()
                        .tenantId(tenant)
                        .submissionId(submissionId)
                        .organizationId(organizationId)
                        .orgScopeEpoch(epoch)
                        .build()));

        LifecycleDualProjection next = LifecycleDualProjection.builder()
                .tenantId(tenant)
                .taskId(context.taskId())
                .caseId(context.caseId())
                .taskGeneration(generation)
                .revision(revision)
                .submissionId(submissionId)
                .evidenceSha256(evidenceSha256)
                .state(STATE_PENDING)
                .updatedAt(now)
                .build();
        if (previous == null) {
            next.setProjectionVersion(0L);
            projectionMapper.insert(next);
        } else {
            if (projectionMapper.compareAndSet(next, previous.getProjectionVersion()) != 1) {
                // タスク行のX lock下ではprojectionを他者が変えられない。変わっていれば依存の変化として試行をやり直す。
                throw new LifecycleLockPlan.DependencyChangedException();
            }
            if (STATE_PENDING.equals(previous.getState())) {
                dualEventMapper.insert(event(tenant, context, previous.getTaskGeneration(), previous.getSubmissionId(),
                        submitter.userId(), OPERATION_SUBMIT, "SUPERSEDED"));
            }
        }
        dualEventMapper.insert(event(tenant, context, generation, submissionId, submitter.userId(),
                OPERATION_SUBMIT, "SUBMITTED"));
        idempotencyMapper.insert(LifecycleDualIdempotency.builder()
                .tenantId(tenant)
                .caseId(context.caseId())
                .taskId(context.taskId())
                .taskGeneration(generation)
                .operation(OPERATION_SUBMIT)
                .actorUserId(submitter.userId())
                .idempotencyKey(attempt.idempotencyKey())
                .requestSha256(attempt.requestSha256())
                .submissionId(submissionId)
                .build());
        notifier.submittedAfterCommit(context.task(), context.lcCase(), submissionId);
        return result(context.taskId(), submission, null, STATE_PENDING, context.task().getStatus(), false);
    }

    // ===================================================================== CONFIRM / REJECT

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class, timeout = ATTEMPT_TIMEOUT_SECONDS)
    public DualConfirmationResultDto confirm(ConfirmAttempt attempt) {
        TransactionBoundState.requireActualTransaction();
        String tenant = attempt.tenantId();
        // 1. lock → 依存先の再読込と照合
        lockExecutor.acquire(attempt.plan());
        ConfirmDependencies current = dependencyReader.readConfirm(tenant, attempt.taskId(), attempt.submissionId(),
                attempt.confirmerUserId(), attempt.asOf())
                .orElseThrow(LifecycleLockPlan.DependencyChangedException::new);
        attempt.plan().requireSameDependencies(current.plan());
        TaskContext context = current.context();
        LifecycleDualSubmission submission = current.submission();

        // 2. 認可（確認者）
        ExplicitActor confirmer = authorizeConfirmer(tenant, attempt, context, submission);

        // 3. 認可（提出者）: 提出時と同じtenant・roleで、現在も述語 (a)〜(f) を満たす
        ExplicitActor submitter = current.currentSubmitter();
        if (submitter == null || !tenant.equals(submitter.tenantId())
                || !Objects.equals(submitter.role(), submission.getSubmitterRoleSnapshot())) {
            throw authorizationChanged(DualConfirmationDenyReason.AUTHORIZATION_CHANGED_ACTOR);
        }
        try {
            authorizeSubmitter(submitter, DualConfirmationChannel.forSubmitterRole(submitter.role()), context, true);
        } catch (DualConfirmationRejection | BusinessException lost) {
            throw authorizationChanged(classifySubmitterChange(tenant, submission, current, context));
        }

        // 4. epoch: 提出時のsnapshotと現在値を比べる。確認者のactor epochは比較せず、現在値を確認行に記録する
        long generation = epochReader.readTaskGeneration(tenant, context.caseId(), context.taskId());
        LifecycleAuthorizationFence currentFence = currentFence(tenant, submitter.userId(), context.engineerId(),
                current.currentSubmitterOrganizations());
        long documentEpoch = epochReader.readDocument(tenant, current.document().getDocumentId());
        requireSnapshotStillCurrent(submission, currentFence, documentEpoch);
        long confirmerActorEpoch = epochReader.readActor(tenant, confirmer.userId());

        // 5. 文書適格性の再評価と、提出者・確認者の文書ACL
        LifecycleDualSubmissionDocument document = current.document();
        EligibleVersion version = requireEligible(tenant, document.getDocumentId(), document.getDocumentVersionId(),
                context.engineerId(), attempt.verifiedVersion());
        if (!Objects.equals(version.versionNo(), document.getVersionNo())
                || !lowerHex(version.sha256()).equals(lowerHex(document.getSha256()))) {
            log.error("[二者確認] 提出が固定した版の内容識別子と現在の版が一致しません: submissionId={} documentId={} "
                    + "documentVersionId={}", submission.getId(), document.getDocumentId(), document.getDocumentVersionId());
            throw DualConfirmationRejection.of(409, "error.lifecycle.evidenceDocumentUnavailable",
                    DualConfirmationDenyReason.STORAGE_INTEGRITY);
        }
        if (!documentAclAllows(submitter, context.engineerId())) {
            throw authorizationChanged(DualConfirmationDenyReason.AUTHORIZATION_CHANGED_ENGINEER);
        }
        if (!documentAclAllows(confirmer, context.engineerId())) {
            throw DualConfirmationRejection.of(400, "error.document.notFound", DualConfirmationDenyReason.SCOPE_DENIED);
        }

        // 6. 冪等性（CONFIRMは世代を進めないので、確認済みタスクへの再送もここで当たる）
        String requestSha256 = LifecycleCanonicalization.Request.confirm(tenant, context.taskId(), generation,
                confirmer.userId(), attempt.idempotencyKey(), attempt.comment(), attempt.binding(),
                attempt.decision()).sha256();
        LifecycleDualIdempotency stored = findIdempotency(tenant, context.taskId(), generation, OPERATION_CONFIRM,
                confirmer.userId(), attempt.idempotencyKey());
        if (stored != null) {
            return replayConfirm(stored, requestSha256, attempt, context, submission);
        }

        // 7. 状態
        LifecycleDualProjection projection = findProjection(tenant, context.taskId());
        if (!Objects.equals(submission.getTaskGeneration(), generation)
                || projection == null
                || !Objects.equals(projection.getSubmissionId(), submission.getId())
                || !Objects.equals(projection.getRevision(), submission.getRevision())
                || !Objects.equals(projection.getTaskGeneration(), generation)
                || !STATE_PENDING.equals(projection.getState())
                || attempt.binding().submissionRevision() != submission.getRevision()
                || !attempt.binding().evidenceSha256().equals(lowerHex(submission.getEvidenceSha256()))
                || !attempt.binding().evidenceSha256().equals(lowerHex(projection.getEvidenceSha256()))
                || !EVIDENCE_TYPE_DUAL.equals(context.task().getEvidenceType())
                || !CASE_ACTIVE.equals(context.lcCase().getStatus())
                || !TASK_IN_PROGRESS.equals(context.task().getStatus())) {
            throw DualConfirmationRejection.of(409, "error.lifecycle.submissionStale",
                    DualConfirmationDenyReason.SUBMISSION_STALE);
        }

        // 8. 書込み
        return writeDecision(attempt, confirmer, current, projection, currentFence.global(), confirmerActorEpoch,
                requestSha256, generation);
    }

    private ExplicitActor authorizeConfirmer(String tenant, ConfirmAttempt attempt, TaskContext context,
                                             LifecycleDualSubmission submission) {
        ExplicitActor confirmer = evaluator.resolveActor(tenant, attempt.confirmerUserId(), attempt.asOf())
                .orElseThrow(() -> DualConfirmationRejection.of(403, "error.lifecycle.accessDenied",
                        DualConfirmationDenyReason.SCOPE_DENIED));
        if (!CONFIRMER_ROLES.contains(confirmer.role())) {
            throw DualConfirmationRejection.of(403, "error.lifecycle.confirmerRoleDenied",
                    DualConfirmationDenyReason.CONFIRMER_ROLE_DENIED);
        }
        if (Objects.equals(confirmer.userId(), submission.getSubmitterUserId())) {
            throw DualConfirmationRejection.of(403, "error.lifecycle.selfConfirmationDenied",
                    DualConfirmationDenyReason.SELF_CONFIRMATION);
        }
        if (!evaluator.isRouteAllowed(confirmer, "POST",
                DualConfirmationChannel.decisionRoute(context.taskId(), submission.getId()))) {
            throw DualConfirmationRejection.of(403, "error.lifecycle.accessDenied",
                    DualConfirmationDenyReason.SCOPE_DENIED);
        }
        if (!evaluator.canViewCase(confirmer, context.lcCase(), context.engineer())
                || !evaluator.isTaskVisible(confirmer, context.task())) {
            throw DualConfirmationRejection.of(404, "error.lifecycle.taskNotFound",
                    DualConfirmationDenyReason.NOT_FOUND_OR_FOREIGN);
        }
        return confirmer;
    }

    /**
     * 確認時に提出者の述語が成り立たない場合、どの世代が変わったかで拒否理由を細かくする。fenceに変化が無い（例: 世代に
     * 現れない述語の変化）か変化を判定できなければ {@code SUBMITTER_PREDICATE_LOST}（actor epochの変化と区別する。
     * W3-21a review P3-8）。
     */
    private DualConfirmationDenyReason classifySubmitterChange(String tenant, LifecycleDualSubmission submission,
                                                               ConfirmDependencies current, TaskContext context) {
        try {
            LifecycleAuthorizationFence now = currentFence(tenant, submission.getSubmitterUserId(),
                    context.engineerId(), current.currentSubmitterOrganizations());
            LifecycleAuthorizationFence.Component change = storedFence(submission).firstChange(now);
            return change == null ? DualConfirmationDenyReason.SUBMITTER_PREDICATE_LOST
                    : DualConfirmationDenyReason.authorizationChanged(change);
        } catch (RuntimeException unreadable) {
            return DualConfirmationDenyReason.SUBMITTER_PREDICATE_LOST;
        }
    }

    private DualConfirmationResultDto replayConfirm(LifecycleDualIdempotency stored, String requestSha256,
                                                    ConfirmAttempt attempt, TaskContext context,
                                                    LifecycleDualSubmission submission) {
        if (!requestSha256.equals(lowerHex(stored.getRequestSha256()))) {
            throw DualConfirmationRejection.of(409, "error.lifecycle.idempotencyConflict",
                    DualConfirmationDenyReason.IDEMPOTENCY_CONFLICT);
        }
        // confirmationは (tenant, submission_id) で一意かつ提出へFKで束縛される。冪等行の confirmation_id は照合にだけ使う。
        LifecycleDualConfirmation confirmation = confirmationMapper.selectOne(
                new LambdaQueryWrapper<LifecycleDualConfirmation>()
                        .eq(LifecycleDualConfirmation::getTenantId, attempt.tenantId())
                        .eq(LifecycleDualConfirmation::getSubmissionId, submission.getId())
                        .eq(LifecycleDualConfirmation::getTaskId, context.taskId())
                        .eq(LifecycleDualConfirmation::getConfirmerUserId, attempt.confirmerUserId()));
        if (confirmation == null || !Objects.equals(confirmation.getId(), stored.getConfirmationId())) {
            log.warn("[二者確認] 冪等行が指す確認を読めません: taskId={} idempotencyId={}", context.taskId(), stored.getId());
            throw DualConfirmationRejection.of(409, "error.lifecycle.idempotencyConflict",
                    DualConfirmationDenyReason.IDEMPOTENCY_CONFLICT);
        }
        LifecycleDualProjection projection = findProjection(attempt.tenantId(), context.taskId());
        return result(context.taskId(), submission, confirmation, projectionStateFor(projection, submission),
                context.task().getStatus(), true);
    }

    private DualConfirmationResultDto writeDecision(ConfirmAttempt attempt, ExplicitActor confirmer,
                                                    ConfirmDependencies current, LifecycleDualProjection projection,
                                                    long globalEpoch, long confirmerActorEpoch, String requestSha256,
                                                    long generation) {
        String tenant = attempt.tenantId();
        TaskContext context = current.context();
        LifecycleDualSubmission submission = current.submission();
        boolean confirmed = attempt.decision() == Decision.CONFIRM;
        LocalDateTime now = LocalDateTime.now();

        LifecycleDualConfirmation confirmation = LifecycleDualConfirmation.builder()
                .tenantId(tenant)
                .caseId(context.caseId())
                .taskId(context.taskId())
                .taskGeneration(submission.getTaskGeneration())
                .submissionId(submission.getId())
                .submissionRevision(submission.getRevision())
                .evidenceSha256(submission.getEvidenceSha256())
                .confirmerUserId(confirmer.userId())
                .confirmerRoleSnapshot(confirmer.role())
                .globalEpoch(globalEpoch)
                .confirmerActorEpoch(confirmerActorEpoch)
                .decision(confirmed ? STATE_CONFIRMED : STATE_REJECTED)
                .decisionComment(blankToNull(attempt.comment()))
                .build();
        confirmationMapper.insert(confirmation);

        LifecycleDualProjection next = LifecycleDualProjection.builder()
                .tenantId(tenant)
                .taskId(context.taskId())
                .caseId(context.caseId())
                .taskGeneration(projection.getTaskGeneration())
                .revision(projection.getRevision())
                .submissionId(projection.getSubmissionId())
                .evidenceSha256(projection.getEvidenceSha256())
                .state(confirmed ? STATE_CONFIRMED : STATE_REJECTED)
                .updatedAt(now)
                .build();
        if (projectionMapper.compareAndSet(next, projection.getProjectionVersion()) != 1) {
            throw new LifecycleLockPlan.DependencyChangedException();
        }
        dualEventMapper.insert(event(tenant, context, generation, submission.getId(), confirmer.userId(),
                confirmed ? OPERATION_CONFIRM : OPERATION_REJECT, confirmed ? STATE_CONFIRMED : STATE_REJECTED));

        String taskStatus = context.task().getStatus();
        if (confirmed) {
            completeTask(context, submission, confirmation, confirmer, now);
            promoteEligibleSuccessors(context, current, confirmer.userId(), now);
            taskStatus = TASK_COMPLETED;
        }
        idempotencyMapper.insert(LifecycleDualIdempotency.builder()
                .tenantId(tenant)
                .caseId(context.caseId())
                .taskId(context.taskId())
                .taskGeneration(generation)
                .operation(OPERATION_CONFIRM)
                .actorUserId(confirmer.userId())
                .idempotencyKey(attempt.idempotencyKey())
                .requestSha256(requestSha256)
                .submissionId(submission.getId())
                .confirmationId(confirmation.getId())
                .build());
        notifier.decidedAfterCommit(context.task(), context.lcCase(), submission.getId(),
                submission.getSubmitterUserId(), confirmed,
                DualConfirmationChannel.forSubmitterRole(submission.getSubmitterRoleSnapshot())
                        == DualConfirmationChannel.SELF);
        return result(context.taskId(), submission, confirmation, confirmed ? STATE_CONFIRMED : STATE_REJECTED,
                taskStatus, false);
    }

    /**
     * タスクのCAS（CONFIRMの時だけ）。{@code completed_by} は確認者、{@code evidence_data_json} は提出ID・revision・証跡hash・
     * 確認IDだけを持つ。業務commentはタスクへ写さない（commentの削除・匿名化の経路をB0の2列に閉じるため。§12 O-8）。
     * 既存画面の表示互換として、確定版の文書・版を {@code t_lifecycle_evidence_link} に1行入れる。
     */
    private void completeTask(TaskContext context, LifecycleDualSubmission submission,
                              LifecycleDualConfirmation confirmation, ExplicitActor confirmer, LocalDateTime now) {
        LifecycleTask task = context.task();
        int version = task.getVersion() == null ? 0 : task.getVersion();
        LambdaUpdateWrapper<LifecycleTask> update = new LambdaUpdateWrapper<LifecycleTask>()
                .eq(LifecycleTask::getId, context.taskId())
                .eq(LifecycleTask::getCaseId, context.caseId())
                .eq(LifecycleTask::getStatus, TASK_IN_PROGRESS)
                .eq(LifecycleTask::getVersion, version)
                .set(LifecycleTask::getStatus, TASK_COMPLETED)
                .set(LifecycleTask::getCompletedAt, now)
                .set(LifecycleTask::getCompletedBy, confirmer.userId())
                .set(LifecycleTask::getEvidenceDataJson, evidenceDataJson(submission, confirmation))
                .set(LifecycleTask::getUpdatedBy, confirmer.userId())
                .set(LifecycleTask::getUpdatedAt, now)
                .set(LifecycleTask::getVersion, version + 1);
        if (taskMapper.update(null, update) != 1) {
            throw new LifecycleLockPlan.DependencyChangedException();
        }
        LifecycleDualSubmissionDocument document = submissionDocumentMapper.selectOne(
                new LambdaQueryWrapper<LifecycleDualSubmissionDocument>()
                        .eq(LifecycleDualSubmissionDocument::getTenantId, submission.getTenantId())
                        .eq(LifecycleDualSubmissionDocument::getSubmissionId, submission.getId()));
        evidenceLinkMapper.insert(LifecycleEvidenceLink.builder()
                .taskId(context.taskId())
                .documentId(document.getDocumentId())
                .documentVersionId(document.getDocumentVersionId())
                .verifiedAt(now)
                .verifiedBy(confirmer.userId())
                .build());
        lifecycleEventMapper.insert(LifecycleEvent.builder()
                .caseId(context.caseId())
                .taskId(context.taskId())
                .eventType("TASK_COMPLETED")
                .actorUserId(confirmer.userId())
                .actorRoleSnapshot(confirmer.role())
                .actorType(ActorType.HUMAN.name())
                .confirmationSource(ConfirmationSource.MANUAL_API.name())
                .beforeState(TASK_IN_PROGRESS)
                .afterState(TASK_COMPLETED)
                .detailsJson(toJson(Map.of("dualConfirmation", true, "submissionId", submission.getId(),
                        "confirmationId", confirmation.getId())))
                .occurredAt(now)
                .build());
    }

    /**
     * 後続タスクの自動昇格（{@code LifecycleTaskServiceImpl#promoteEligibleSuccessors} と同じ規則、batch 0 L3）。
     * 後続と後続の先行はplanの順5で既にX lock済みのため、ここでは通常読取（READ_COMMITTEDで現在値）だけを行い、lockを足さない。
     */
    private void promoteEligibleSuccessors(TaskContext context, ConfirmDependencies current, long actorUserId,
                                           LocalDateTime now) {
        for (Map.Entry<Long, List<Long>> entry : current.predecessorsBySuccessor().entrySet()) {
            LifecycleTask successor = lockedTask(entry.getKey(), context.caseId());
            if (successor == null || !"PENDING".equals(successor.getStatus())) {
                continue;
            }
            boolean allDone = entry.getValue().stream().allMatch(predecessorId ->
                    predecessorId == context.taskId() || isDone(lockedTask(predecessorId, context.caseId())));
            if (!allDone) {
                continue;
            }
            // 書く列（status・updated_by・updated_at・version）だけを指名する（W3-21a review P3-13）。entity全体の updateById は
            // lock後に読んだ非key列をそのまま書き戻すため、列が増えたときに意図しない上書きの経路になる。
            int version = successor.getVersion() == null ? 0 : successor.getVersion();
            LambdaUpdateWrapper<LifecycleTask> promote = new LambdaUpdateWrapper<LifecycleTask>()
                    .eq(LifecycleTask::getId, successor.getId())
                    .eq(LifecycleTask::getCaseId, context.caseId())
                    .eq(LifecycleTask::getStatus, "PENDING")
                    .eq(LifecycleTask::getVersion, version)
                    .set(LifecycleTask::getStatus, TASK_IN_PROGRESS)
                    .set(LifecycleTask::getUpdatedBy, actorUserId)
                    .set(LifecycleTask::getUpdatedAt, now)
                    .set(LifecycleTask::getVersion, version + 1);
            if (taskMapper.update(null, promote) != 1) {
                throw new LifecycleLockPlan.DependencyChangedException();
            }
            lifecycleEventMapper.insert(LifecycleEvent.builder()
                    .caseId(context.caseId())
                    .taskId(successor.getId())
                    .eventType("TASK_STARTED")
                    .actorUserId(null)
                    .actorRoleSnapshot("SYSTEM")
                    .actorType(ActorType.SYSTEM.name())
                    .confirmationSource(ConfirmationSource.SCHEDULER_POLL.name())
                    .beforeState("PENDING")
                    .afterState(TASK_IN_PROGRESS)
                    .detailsJson("{\"autoPromoted\":true}")
                    .occurredAt(now)
                    .build());
        }
    }

    // ===================================================================== 共通部品

    /**
     * 提出者の述語（{@code ExplicitActorAuthorizationEvaluator}、task種別・可視性を含む）。本人の入口は要員だけが使える。
     *
     * @param atConfirmation 確認時の再評価か（trueなら拒否はcallerが409 authorizationChangedへ変換する）
     */
    private void authorizeSubmitter(ExplicitActor submitter, DualConfirmationChannel channel, TaskContext context,
                                    boolean atConfirmation) {
        if (channel == DualConfirmationChannel.SELF && !"要員".equals(submitter.role())) {
            throw DualConfirmationRejection.of(403, "error.lifecycle.accessDenied",
                    DualConfirmationDenyReason.SCOPE_DENIED);
        }
        if (!evaluator.isRouteAllowed(submitter, "POST", channel.submitRoute(context.taskId()))) {
            throw DualConfirmationRejection.of(403, "error.lifecycle.accessDenied",
                    DualConfirmationDenyReason.SCOPE_DENIED);
        }
        try {
            evaluator.assertCanEditTask(submitter, context.task(), context.lcCase(), context.engineer());
        } catch (BusinessException denied) {
            if (atConfirmation) {
                throw denied;
            }
            throw DualConfirmationRejection.of(denied.getCode(), denied.getMessageKey() == null
                            ? "error.lifecycle.taskEditDenied" : denied.getMessageKey(),
                    DualConfirmationDenyReason.fromMessageKey(denied.getMessageKey()));
        }
    }

    /** 文書ACL（SKILL_SHEET×単一ENGINEER linkに限った {@code DocumentServiceImpl#assertDocumentAccessAllowed} の明示actor版）。 */
    private boolean documentAclAllows(ExplicitActor actor, long engineerId) {
        if (ROLE_ADMIN.equals(actor.role())) {
            return true;
        }
        return evaluator.isEngineerAllowedByDataScope(actor, engineerId);
    }

    private LifecycleAuthorizationFence currentFence(String tenant, long submitterUserId, long engineerId,
                                                     SortedSet<Long> organizations) {
        long global = epochReader.readGlobal();
        long actor = epochReader.readActor(tenant, submitterUserId);
        long engineer = epochReader.readEngineer(tenant, engineerId);
        SortedMap<Long, Long> organizationEpochs = epochReader.readOrganizations(organizations);
        return new LifecycleAuthorizationFence(global, actor, engineer, organizationEpochs);
    }

    private LifecycleAuthorizationFence storedFence(LifecycleDualSubmission submission) {
        SortedMap<Long, Long> organizations = new TreeMap<>();
        for (LifecycleDualSubmissionOrgFence row : orgFenceMapper.selectList(
                new LambdaQueryWrapper<LifecycleDualSubmissionOrgFence>()
                        .eq(LifecycleDualSubmissionOrgFence::getTenantId, submission.getTenantId())
                        .eq(LifecycleDualSubmissionOrgFence::getSubmissionId, submission.getId()))) {
            organizations.put(row.getOrganizationId(), row.getOrgScopeEpoch());
        }
        return new LifecycleAuthorizationFence(submission.getGlobalEpoch(), submission.getSubmitterActorEpoch(),
                submission.getEngineerScopeEpoch(), organizations);
    }

    /** 提出時のsnapshot（global・提出者actor・engineer・組織集合・文書epoch）が現在値と一致することを要求する。 */
    private void requireSnapshotStillCurrent(LifecycleDualSubmission submission, LifecycleAuthorizationFence current,
                                             long documentEpoch) {
        LifecycleAuthorizationFence.Component change = storedFence(submission).firstChange(current);
        if (change != null) {
            throw authorizationChanged(DualConfirmationDenyReason.authorizationChanged(change));
        }
        LifecycleDualSubmissionDocument document = submissionDocumentMapper.selectOne(
                new LambdaQueryWrapper<LifecycleDualSubmissionDocument>()
                        .eq(LifecycleDualSubmissionDocument::getTenantId, submission.getTenantId())
                        .eq(LifecycleDualSubmissionDocument::getSubmissionId, submission.getId()));
        if (document == null || !Objects.equals(document.getEvidenceEligibilityEpoch(), documentEpoch)) {
            throw authorizationChanged(DualConfirmationDenyReason.AUTHORIZATION_CHANGED_DOCUMENT);
        }
    }

    /** §5.1 条件3〜9をS lock下で評価し、discoveryでstorage検証した版の組が変わっていないことを確かめる。 */
    private EligibleVersion requireEligible(String tenant, long documentId, long documentVersionId, long engineerId,
                                            EligibleVersion verified) {
        DocumentEvidenceEligibility.Result result = eligibility.evaluate(
                new DocumentEvidenceEligibility.Candidate(tenant, documentId, documentVersionId, engineerId));
        if (!result.eligible()) {
            DocumentEvidenceEligibility.Verdict verdict = result.verdict();
            throw DualConfirmationRejection.of(verdict.httpStatus(), verdict.messageKey(),
                    DualConfirmationDenyReason.valueOf(verdict.reasonCode()));
        }
        if (!result.version().equals(verified)) {
            // 版の内容識別子はappend-only。discovery後に変わっていれば新しいdiscoveryでstorageを検証し直す。
            throw new LifecycleLockPlan.DependencyChangedException();
        }
        return result.version();
    }

    /** DUAL以外のタスクは初回要求の状態の不成立（400 {@code INVALID_STATE}。W3-21a review P3-8）。 */
    private static void requireDualTask(LifecycleTask task, int status, String messageKey) {
        if (!EVIDENCE_TYPE_DUAL.equals(task.getEvidenceType())) {
            throw DualConfirmationRejection.of(status, messageKey, DualConfirmationDenyReason.INVALID_STATE);
        }
    }

    private static DualConfirmationRejection authorizationChanged(DualConfirmationDenyReason reason) {
        return DualConfirmationRejection.of(409, "error.lifecycle.authorizationChanged", reason);
    }

    private LifecycleDualIdempotency findIdempotency(String tenant, long taskId, long generation, String operation,
                                                     long actorUserId, String key) {
        return idempotencyMapper.selectOne(new LambdaQueryWrapper<LifecycleDualIdempotency>()
                .eq(LifecycleDualIdempotency::getTenantId, tenant)
                .eq(LifecycleDualIdempotency::getTaskId, taskId)
                .eq(LifecycleDualIdempotency::getTaskGeneration, generation)
                .eq(LifecycleDualIdempotency::getOperation, operation)
                .eq(LifecycleDualIdempotency::getActorUserId, actorUserId)
                .eq(LifecycleDualIdempotency::getIdempotencyKey, key));
    }

    private LifecycleDualProjection findProjection(String tenant, long taskId) {
        return projectionMapper.selectOne(new LambdaQueryWrapper<LifecycleDualProjection>()
                .eq(LifecycleDualProjection::getTenantId, tenant)
                .eq(LifecycleDualProjection::getTaskId, taskId));
    }

    /** 同じ世代の最大revision + 1（タスク行のX lock下なので並行に採番されない）。 */
    private int nextRevision(String tenant, long taskId, long generation) {
        return submissionMapper.selectList(new LambdaQueryWrapper<LifecycleDualSubmission>()
                        .select(LifecycleDualSubmission::getRevision)
                        .eq(LifecycleDualSubmission::getTenantId, tenant)
                        .eq(LifecycleDualSubmission::getTaskId, taskId)
                        .eq(LifecycleDualSubmission::getTaskGeneration, generation))
                .stream()
                .map(LifecycleDualSubmission::getRevision)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0) + 1;
    }

    private LifecycleTask lockedTask(long taskId, long caseId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<LifecycleTask>()
                .eq(LifecycleTask::getId, taskId)
                .eq(LifecycleTask::getCaseId, caseId));
    }

    private static boolean isDone(LifecycleTask task) {
        return task != null && (TASK_COMPLETED.equals(task.getStatus()) || "WAIVED".equals(task.getStatus()));
    }

    private static String projectionStateFor(LifecycleDualProjection projection, LifecycleDualSubmission submission) {
        return projection != null && Objects.equals(projection.getSubmissionId(), submission.getId())
                ? projection.getState() : null;
    }

    private static LifecycleDualEvent event(String tenant, TaskContext context, Long generation, Long submissionId,
                                            long actorUserId, String operation, String eventType) {
        return LifecycleDualEvent.builder()
                .tenantId(tenant)
                .caseId(context.caseId())
                .taskId(context.taskId())
                .taskGeneration(generation)
                .submissionId(submissionId)
                .requestedTaskId(context.taskId())
                .requestedSubmissionId(submissionId)
                .actorUserId(actorUserId)
                .operation(operation)
                .eventType(eventType)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    private static DualConfirmationResultDto result(long taskId, LifecycleDualSubmission submission,
                                                    LifecycleDualConfirmation confirmation, String state,
                                                    String taskStatus, boolean replayed) {
        return DualConfirmationResultDto.builder()
                .taskId(taskId)
                .submissionId(submission.getId())
                .submissionRevision(submission.getRevision())
                .evidenceSha256(lowerHex(submission.getEvidenceSha256()))
                .state(state)
                .confirmationId(confirmation == null ? null : confirmation.getId())
                .decision(confirmation == null ? null : confirmation.getDecision())
                .taskStatus(taskStatus)
                .replayed(replayed)
                .build();
    }

    private String evidenceDataJson(LifecycleDualSubmission submission, LifecycleDualConfirmation confirmation) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("submissionId", submission.getId());
        data.put("submissionRevision", submission.getRevision());
        data.put("evidenceSha256", lowerHex(submission.getEvidenceSha256()));
        data.put("confirmationId", confirmation.getId());
        return toJson(data);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(value));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("二者確認の記録をJSONにできません", impossible);
        }
    }

    static String lowerHex(String hash) {
        return hash == null ? null : hash.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
