package com.ses.service.lifecycle.dual;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.dual.DualConfirmationResultDto;
import com.ses.entity.Engineer;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleDualConfirmation;
import com.ses.entity.LifecycleDualIdempotency;
import com.ses.entity.LifecycleDualProjection;
import com.ses.entity.LifecycleDualSubmission;
import com.ses.entity.LifecycleDualSubmissionDocument;
import com.ses.entity.LifecycleDualSubmissionOrgFence;
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
import com.ses.service.document.DocumentEvidenceEligibility.Verdict;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationAttemptService.ConfirmAttempt;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationAttemptService.SubmitAttempt;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDependencyReader.ConfirmDependencies;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDependencyReader.SubmitDependencies;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDependencyReader.TaskContext;
import com.ses.service.lifecycle.evidence.LifecycleCanonicalization;
import com.ses.service.lifecycle.evidence.LifecycleCanonicalization.ConfirmationBinding;
import com.ses.service.lifecycle.evidence.LifecycleCanonicalization.Decision;
import com.ses.service.lifecycle.evidence.LifecycleLockExecutor;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan;
import com.ses.service.security.ExplicitActorAuthorizationEvaluator;
import com.ses.service.security.ExplicitActorAuthorizationEvaluator.ExplicitActor;
import com.ses.service.security.epoch.AuthorizationEpochReader;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3-21a review P3-9: 二者確認の1回の試行（{@link LifecycleDualConfirmationAttemptService}）の判定を、DB無し・協力部品mockで
 * 固定する（M8/M9 の実MySQLとは別に、fastで順序と分岐を確かめる）。
 *
 * <ul>
 *   <li>計画 §5.5 の順序: lock → 依存先の再読込と照合 → 認可 → epoch → 文書（提出者のACL・可視性 → 適格性）→ 冪等性 →
 *       状態 → 書込み（{@link InOrder} で固定）。</li>
 *   <li>replay の2分岐（提出・確認）: 同key・同hashは保存済みの結果、同key・異hashは409で何も書かない。提出の再送でも
 *       epochが変わっていれば409。</li>
 *   <li>状態: 400 の不成立は {@code INVALID_STATE}、409 の世代不一致・決定済みは {@code SUBMISSION_STALE}（P3-8）。</li>
 *   <li>Demo (4): 確認者のrole・本人・route・閲覧可否を試行の中でも判定する。確認時に提出者の述語が成り立たずfenceが
 *       変わらなければ {@code SUBMITTER_PREDICATE_LOST}（P3-8）。</li>
 *   <li>範囲外の文書は適格性の詳細を返さず400 {@code error.document.notFound}（P3-5）。</li>
 *   <li>後続の昇格は書く列だけを指名し、entity全体の {@code updateById} を使わない（P3-13）。</li>
 * </ul>
 */
class LifecycleDualConfirmationAttemptServiceTest {

    private static final String TENANT = "tenant-a";
    private static final long SUBMITTER = 11L;
    private static final long CONFIRMER = 22L;
    private static final long TASK = 101L;
    private static final long SUCCESSOR = 102L;
    private static final long CASE = 201L;
    private static final long ENGINEER = 301L;
    private static final long DOCUMENT = 401L;
    private static final long VERSION = 501L;
    private static final long SUBMISSION = 601L;
    private static final long CONFIRMATION = 701L;
    private static final long GENERATION = 4L;
    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 17);
    private static final String KEY = "op-20261117.attempt_~-1";
    private static final String CONTENT_SHA = "a".repeat(64);
    private static final String EVIDENCE_SHA = "b".repeat(64);
    private static final String REQUEST_SHA = "c".repeat(64);
    private static final EligibleVersion ELIGIBLE = new EligibleVersion(TENANT, DOCUMENT, VERSION, 1,
            "skill/401/1.xlsx", CONTENT_SHA, 10L);

    private LifecycleLockExecutor lockExecutor;
    private AuthorizationEpochReader epochReader;
    private ExplicitActorAuthorizationEvaluator evaluator;
    private DocumentEvidenceEligibility eligibility;
    private LifecycleDualConfirmationDependencyReader dependencyReader;
    private LifecycleDualSubmissionMapper submissionMapper;
    private LifecycleDualSubmissionDocumentMapper submissionDocumentMapper;
    private LifecycleDualSubmissionOrgFenceMapper orgFenceMapper;
    private LifecycleDualConfirmationMapper confirmationMapper;
    private LifecycleDualProjectionMapper projectionMapper;
    private LifecycleDualIdempotencyMapper idempotencyMapper;
    private LifecycleDualEventMapper dualEventMapper;
    private LifecycleTaskMapper taskMapper;
    private LifecycleEventMapper lifecycleEventMapper;
    private LifecycleEvidenceLinkMapper evidenceLinkMapper;
    private LifecycleDualConfirmationNotifier notifier;
    private LifecycleDualConfirmationAttemptService service;

    private TaskContext context;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(LifecycleTask.class, LifecycleDualSubmission.class,
                LifecycleDualSubmissionDocument.class, LifecycleDualSubmissionOrgFence.class,
                LifecycleDualConfirmation.class, LifecycleDualProjection.class, LifecycleDualIdempotency.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        lockExecutor = mock(LifecycleLockExecutor.class);
        epochReader = mock(AuthorizationEpochReader.class);
        evaluator = mock(ExplicitActorAuthorizationEvaluator.class);
        eligibility = mock(DocumentEvidenceEligibility.class);
        dependencyReader = mock(LifecycleDualConfirmationDependencyReader.class);
        submissionMapper = mock(LifecycleDualSubmissionMapper.class);
        submissionDocumentMapper = mock(LifecycleDualSubmissionDocumentMapper.class);
        orgFenceMapper = mock(LifecycleDualSubmissionOrgFenceMapper.class);
        confirmationMapper = mock(LifecycleDualConfirmationMapper.class);
        projectionMapper = mock(LifecycleDualProjectionMapper.class);
        idempotencyMapper = mock(LifecycleDualIdempotencyMapper.class);
        dualEventMapper = mock(LifecycleDualEventMapper.class);
        taskMapper = mock(LifecycleTaskMapper.class);
        lifecycleEventMapper = mock(LifecycleEventMapper.class);
        evidenceLinkMapper = mock(LifecycleEvidenceLinkMapper.class);
        notifier = mock(LifecycleDualConfirmationNotifier.class);
        service = new LifecycleDualConfirmationAttemptService(lockExecutor, epochReader, evaluator, eligibility,
                dependencyReader, submissionMapper, submissionDocumentMapper, orgFenceMapper, confirmationMapper,
                projectionMapper, idempotencyMapper, dualEventMapper, taskMapper, lifecycleEventMapper,
                evidenceLinkMapper, notifier, new ObjectMapper());

        context = new TaskContext(task(TASK, "DUAL_CONFIRMATION", "IN_PROGRESS"), lifecycleCase("ACTIVE"), engineer());
        actorIs(SUBMITTER, "営業");
        actorIs(CONFIRMER, "HR");
        lenient().when(dependencyReader.readSubmit(eq(TENANT), eq(TASK), any(), eq(DOCUMENT), eq(VERSION)))
                .thenAnswer(invocation -> Optional.of(submitDependencies()));
        lenient().when(dependencyReader.readConfirm(eq(TENANT), eq(TASK), eq(SUBMISSION), eq(CONFIRMER), any()))
                .thenAnswer(invocation -> Optional.of(confirmDependencies(SUBMITTER, Map.of())));
        lenient().when(evaluator.isRouteAllowed(any(), eq("POST"), any())).thenReturn(true);
        lenient().when(evaluator.canViewCase(any(), any(), any())).thenReturn(true);
        lenient().when(evaluator.isTaskVisible(any(), any())).thenReturn(true);
        lenient().when(evaluator.isEngineerAllowedByDataScope(any(), any())).thenReturn(true);
        lenient().when(epochReader.readTaskGeneration(TENANT, CASE, TASK)).thenReturn(GENERATION);
        lenient().when(epochReader.readOrganizations(any())).thenAnswer(invocation -> new TreeMap<Long, Long>());
        lenient().when(eligibility.visibleEvidenceCandidate(TENANT, DOCUMENT, ENGINEER)).thenReturn(true);
        lenient().when(eligibility.evaluate(any())).thenReturn(new DocumentEvidenceEligibility.Result(Verdict.ELIGIBLE,
                ELIGIBLE));
        lenient().when(submissionMapper.selectList(any())).thenReturn(List.of());
        lenient().when(submissionMapper.insert(any(LifecycleDualSubmission.class))).thenAnswer(invocation -> {
            invocation.<LifecycleDualSubmission>getArgument(0).setId(SUBMISSION);
            return 1;
        });
        lenient().when(confirmationMapper.insert(any(LifecycleDualConfirmation.class))).thenAnswer(invocation -> {
            invocation.<LifecycleDualConfirmation>getArgument(0).setId(CONFIRMATION);
            return 1;
        });
        lenient().when(submissionDocumentMapper.selectOne(any())).thenReturn(submissionDocument());
        lenient().when(orgFenceMapper.selectList(any())).thenReturn(List.of());
        lenient().when(projectionMapper.compareAndSet(any(), anyLong())).thenReturn(1);
        lenient().when(taskMapper.update(isNull(), any())).thenReturn(1);

        // 試行は実transactionの中でだけ動く（TransactionBoundState.requireActualTransaction）
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    // ===================================================================== SUBMIT

    @Test
    void 提出は計画5_5の順にlock_再読込_認可_epoch_文書_冪等_状態_書込みを行う() {
        DualConfirmationResultDto result = service.submit(submitAttempt(GENERATION));

        assertEquals("PENDING", result.getState());
        assertEquals(SUBMISSION, result.getSubmissionId());
        assertEquals(1, result.getSubmissionRevision());
        assertThat(result.isReplayed()).isFalse();
        InOrder order = inOrder(lockExecutor, dependencyReader, evaluator, epochReader, eligibility, idempotencyMapper,
                submissionMapper);
        order.verify(lockExecutor).acquire(any(LifecycleLockPlan.class));
        order.verify(dependencyReader).readSubmit(eq(TENANT), eq(TASK), any(), eq(DOCUMENT), eq(VERSION));
        order.verify(evaluator).isRouteAllowed(argThat(actor -> actor != null && actor.userId() == SUBMITTER),
                eq("POST"), eq(DualConfirmationChannel.INTERNAL.submitRoute(TASK)));
        order.verify(evaluator).assertCanEditTask(any(), any(), any(), any());
        order.verify(epochReader).readTaskGeneration(TENANT, CASE, TASK);
        order.verify(epochReader).readDocument(TENANT, DOCUMENT);
        order.verify(evaluator).isEngineerAllowedByDataScope(any(), eq(ENGINEER));
        order.verify(eligibility).visibleEvidenceCandidate(TENANT, DOCUMENT, ENGINEER);
        order.verify(eligibility).evaluate(any());
        order.verify(idempotencyMapper).selectOne(any());
        order.verify(submissionMapper).insert(any(LifecycleDualSubmission.class));

        ArgumentCaptor<LifecycleDualSubmission> submission = ArgumentCaptor.forClass(LifecycleDualSubmission.class);
        verify(submissionMapper).insert(submission.capture());
        assertEquals("提出します", submission.getValue().getBusinessComment());
        assertEquals("営業", submission.getValue().getSubmitterRoleSnapshot());
        assertEquals(GENERATION, submission.getValue().getTaskGeneration());
        assertThat(submission.getValue().getEvidenceCanonicalJson()).doesNotContain("提出します");
        verify(projectionMapper).insert(any(LifecycleDualProjection.class));
        verify(idempotencyMapper).insert(any(LifecycleDualIdempotency.class));
        verify(notifier).submittedAfterCommit(context.task(), context.lcCase(), SUBMISSION, SUBMITTER);
        verify(taskMapper, never()).updateById(any(LifecycleTask.class));
    }

    @Test
    void 提出はlock後の依存先がplanと違えば依存の変化として何も書かない() {
        when(dependencyReader.readSubmit(eq(TENANT), eq(TASK), any(), eq(DOCUMENT), eq(VERSION)))
                .thenReturn(Optional.of(new SubmitDependencies(TENANT, context, SUBMITTER, new TreeSet<>(List.of(9L)),
                        DOCUMENT, VERSION)));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class, () -> service.submit(submitAttempt(GENERATION)));

        when(dependencyReader.readSubmit(eq(TENANT), eq(TASK), any(), eq(DOCUMENT), eq(VERSION)))
                .thenReturn(Optional.empty());
        assertThrows(LifecycleLockPlan.DependencyChangedException.class, () -> service.submit(submitAttempt(GENERATION)));

        // discoveryでstorage検証した版と、lock下で評価した版が違えば新しいdiscoveryからやり直す
        when(dependencyReader.readSubmit(eq(TENANT), eq(TASK), any(), eq(DOCUMENT), eq(VERSION)))
                .thenAnswer(invocation -> Optional.of(submitDependencies()));
        when(eligibility.evaluate(any())).thenReturn(new DocumentEvidenceEligibility.Result(Verdict.ELIGIBLE,
                new EligibleVersion(TENANT, DOCUMENT, VERSION, 1, "skill/401/1.xlsx", "d".repeat(64), 10L)));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class, () -> service.submit(submitAttempt(GENERATION)));

        verifyNothingWritten();
    }

    @Test
    void 提出の同key同内容の再送は保存済みの結果を返し内容の違う同keyは409で何も書かない() {
        when(idempotencyMapper.selectOne(any())).thenReturn(storedIdempotency(REQUEST_SHA.toUpperCase(), null));
        when(submissionMapper.selectOne(any())).thenReturn(submission(SUBMITTER));
        when(projectionMapper.selectOne(any())).thenReturn(projection("PENDING"));

        DualConfirmationResultDto replayed = service.submit(submitAttempt(GENERATION));

        assertThat(replayed.isReplayed()).isTrue();
        assertEquals(SUBMISSION, replayed.getSubmissionId());
        assertEquals("PENDING", replayed.getState());

        when(idempotencyMapper.selectOne(any())).thenReturn(storedIdempotency("e".repeat(64), null));
        assertRejected(() -> service.submit(submitAttempt(GENERATION)), 409, "error.lifecycle.idempotencyConflict",
                DualConfirmationDenyReason.IDEMPOTENCY_CONFLICT);
        verifyNothingWritten();
    }

    @Test
    void 提出の再送でも提出時のsnapshotから認可epochが変わっていれば409で保存済みの結果を返さない() {
        when(idempotencyMapper.selectOne(any())).thenReturn(storedIdempotency(REQUEST_SHA, null));
        when(submissionMapper.selectOne(any())).thenReturn(submission(SUBMITTER));
        when(epochReader.readEngineer(TENANT, ENGINEER)).thenReturn(1L);

        assertRejected(() -> service.submit(submitAttempt(GENERATION)), 409, "error.lifecycle.authorizationChanged",
                DualConfirmationDenyReason.AUTHORIZATION_CHANGED_ENGINEER);
        verifyNothingWritten();
    }

    @Test
    void 提出の状態の不成立は400のINVALID_STATEで世代の不一致は409のSUBMISSION_STALEにする() {
        context = new TaskContext(task(TASK, "DOCUMENT_LINK", "IN_PROGRESS"), lifecycleCase("ACTIVE"), engineer());
        assertRejected(() -> service.submit(submitAttempt(GENERATION)), 400, "error.lifecycle.invalidStateTransition",
                DualConfirmationDenyReason.INVALID_STATE);

        context = new TaskContext(task(TASK, "DUAL_CONFIRMATION", "IN_PROGRESS"), lifecycleCase("ON_HOLD"), engineer());
        assertRejected(() -> service.submit(submitAttempt(GENERATION)), 400, "error.lifecycle.caseNotActive",
                DualConfirmationDenyReason.INVALID_STATE);

        context = new TaskContext(task(TASK, "DUAL_CONFIRMATION", "PENDING"), lifecycleCase("ACTIVE"), engineer());
        assertRejected(() -> service.submit(submitAttempt(GENERATION)), 400, "error.lifecycle.invalidStateTransition",
                DualConfirmationDenyReason.INVALID_STATE);

        context = new TaskContext(task(TASK, "DUAL_CONFIRMATION", "IN_PROGRESS"), lifecycleCase("ACTIVE"), engineer());
        assertRejected(() -> service.submit(submitAttempt(GENERATION - 1)), 409, "error.lifecycle.submissionStale",
                DualConfirmationDenyReason.SUBMISSION_STALE);
        verifyNothingWritten();
    }

    @Test
    void 提出者の文書ACL外と案件の要員に紐付かない文書は適格性を評価せず存在しない文書と同じ400にする() {
        when(evaluator.isEngineerAllowedByDataScope(any(), any())).thenReturn(false);
        assertRejected(() -> service.submit(submitAttempt(GENERATION)), 400, "error.document.notFound",
                DualConfirmationDenyReason.SCOPE_DENIED);
        verify(eligibility, never()).visibleEvidenceCandidate(any(), anyLong(), anyLong());

        when(evaluator.isEngineerAllowedByDataScope(any(), any())).thenReturn(true);
        when(eligibility.visibleEvidenceCandidate(TENANT, DOCUMENT, ENGINEER)).thenReturn(false);
        assertRejected(() -> service.submit(submitAttempt(GENERATION)), 400, "error.document.notFound",
                DualConfirmationDenyReason.NOT_FOUND_OR_FOREIGN);

        verify(eligibility, never()).evaluate(any());
        verifyNothingWritten();
    }

    @Test
    void 本人の入口を要員以外が使った提出とrouteを持たない提出者は403で書かない() {
        SubmitAttempt self = new SubmitAttempt(TENANT, TASK, SUBMITTER, DualConfirmationChannel.SELF, GENERATION,
                DOCUMENT, VERSION, "提出します", KEY, REQUEST_SHA, submitDependencies().plan(), ELIGIBLE, AS_OF);
        assertRejected(() -> service.submit(self), 403, "error.lifecycle.accessDenied",
                DualConfirmationDenyReason.SCOPE_DENIED);

        when(evaluator.isRouteAllowed(any(), eq("POST"), any())).thenReturn(false);
        assertRejected(() -> service.submit(submitAttempt(GENERATION)), 403, "error.lifecycle.accessDenied",
                DualConfirmationDenyReason.SCOPE_DENIED);
        verify(epochReader, never()).readTaskGeneration(any(), anyLong(), anyLong());
        verifyNothingWritten();
    }

    // ===================================================================== CONFIRM / REJECT

    @Test
    void 確認は計画5_5の順に確認者_提出者_epoch_文書_冪等_状態_書込みを行いタスクを完了する() {
        when(projectionMapper.selectOne(any())).thenReturn(projection("PENDING"));

        DualConfirmationResultDto result = service.confirm(confirmAttempt(Decision.CONFIRM, 1L));

        assertEquals("CONFIRMED", result.getState());
        assertEquals("COMPLETED", result.getTaskStatus());
        assertEquals(CONFIRMATION, result.getConfirmationId());
        assertThat(result.isReplayed()).isFalse();
        InOrder order = inOrder(lockExecutor, dependencyReader, evaluator, epochReader, eligibility, idempotencyMapper,
                confirmationMapper, taskMapper);
        order.verify(lockExecutor).acquire(any(LifecycleLockPlan.class));
        order.verify(dependencyReader).readConfirm(eq(TENANT), eq(TASK), eq(SUBMISSION), eq(CONFIRMER), any());
        order.verify(evaluator).isRouteAllowed(argThat(actor -> actor != null && actor.userId() == CONFIRMER),
                eq("POST"), eq(DualConfirmationChannel.decisionRoute(TASK, SUBMISSION)));
        order.verify(evaluator).canViewCase(argThat(actor -> actor != null && actor.userId() == CONFIRMER), any(), any());
        order.verify(evaluator).isRouteAllowed(argThat(actor -> actor != null && actor.userId() == SUBMITTER),
                eq("POST"), eq(DualConfirmationChannel.INTERNAL.submitRoute(TASK)));
        order.verify(evaluator).assertCanEditTask(argThat(actor -> actor != null && actor.userId() == SUBMITTER),
                any(), any(), any());
        order.verify(epochReader).readTaskGeneration(TENANT, CASE, TASK);
        order.verify(epochReader).readDocument(TENANT, DOCUMENT);
        order.verify(epochReader).readActor(TENANT, CONFIRMER);
        order.verify(eligibility).evaluate(any());
        order.verify(evaluator).isEngineerAllowedByDataScope(argThat(actor -> actor != null
                && actor.userId() == SUBMITTER), eq(ENGINEER));
        order.verify(evaluator).isEngineerAllowedByDataScope(argThat(actor -> actor != null
                && actor.userId() == CONFIRMER), eq(ENGINEER));
        order.verify(idempotencyMapper).selectOne(any());
        order.verify(confirmationMapper).insert(any(LifecycleDualConfirmation.class));
        order.verify(taskMapper).update(isNull(), any());

        verify(evidenceLinkMapper).insert(any(com.ses.entity.LifecycleEvidenceLink.class));
        verify(idempotencyMapper).insert(any(LifecycleDualIdempotency.class));
        verify(notifier).decidedAfterCommit(context.task(), context.lcCase(), SUBMISSION, SUBMITTER, true, false);
    }

    @Test
    void 差戻しはタスクを完了せず確認の行とprojectionだけを書く() {
        when(projectionMapper.selectOne(any())).thenReturn(projection("PENDING"));

        DualConfirmationResultDto result = service.confirm(confirmAttempt(Decision.REJECT, 1L));

        assertEquals("REJECTED", result.getState());
        assertEquals("IN_PROGRESS", result.getTaskStatus());
        ArgumentCaptor<LifecycleDualConfirmation> confirmation = ArgumentCaptor.forClass(LifecycleDualConfirmation.class);
        verify(confirmationMapper).insert(confirmation.capture());
        assertEquals("REJECTED", confirmation.getValue().getDecision());
        assertEquals("差戻します", confirmation.getValue().getDecisionComment());
        verify(taskMapper, never()).update(any(), any());
        verify(evidenceLinkMapper, never()).insert(any(com.ses.entity.LifecycleEvidenceLink.class));
    }

    @Test
    void 確認者はHRと管理者だけで本人_routeなし_案件を見られない者は試行の中でも拒否する() {
        actorIs(CONFIRMER, "マネージャー");
        assertRejected(() -> service.confirm(confirmAttempt(Decision.CONFIRM, 1L)), 403,
                "error.lifecycle.confirmerRoleDenied", DualConfirmationDenyReason.CONFIRMER_ROLE_DENIED);

        actorIs(CONFIRMER, "HR");
        // 提出者が確認者本人の提出（discoveryのplanも同じ依存先で作る。planが違えば依存の変化になるため）
        when(dependencyReader.readConfirm(eq(TENANT), eq(TASK), eq(SUBMISSION), eq(CONFIRMER), any()))
                .thenReturn(Optional.of(confirmDependencies(CONFIRMER, Map.of())));
        ConfirmAttempt own = new ConfirmAttempt(TENANT, TASK, SUBMISSION, CONFIRMER, binding(1L), Decision.CONFIRM,
                "確認しました", KEY, confirmDependencies(CONFIRMER, Map.of()).plan(), ELIGIBLE, AS_OF);
        assertRejected(() -> service.confirm(own), 403,
                "error.lifecycle.selfConfirmationDenied", DualConfirmationDenyReason.SELF_CONFIRMATION);

        when(dependencyReader.readConfirm(eq(TENANT), eq(TASK), eq(SUBMISSION), eq(CONFIRMER), any()))
                .thenAnswer(invocation -> Optional.of(confirmDependencies(SUBMITTER, Map.of())));
        when(evaluator.isRouteAllowed(argThat(actor -> actor != null && actor.userId() == CONFIRMER), eq("POST"),
                any())).thenReturn(false);
        assertRejected(() -> service.confirm(confirmAttempt(Decision.CONFIRM, 1L)), 403,
                "error.lifecycle.accessDenied", DualConfirmationDenyReason.SCOPE_DENIED);

        when(evaluator.isRouteAllowed(any(), eq("POST"), any())).thenReturn(true);
        when(evaluator.canViewCase(argThat(actor -> actor != null && actor.userId() == CONFIRMER), any(), any()))
                .thenReturn(false);
        assertRejected(() -> service.confirm(confirmAttempt(Decision.CONFIRM, 1L)), 404,
                "error.lifecycle.taskNotFound", DualConfirmationDenyReason.NOT_FOUND_OR_FOREIGN);

        when(evaluator.resolveActor(eq(TENANT), eq(CONFIRMER), any())).thenReturn(Optional.empty());
        assertRejected(() -> service.confirm(confirmAttempt(Decision.CONFIRM, 1L)), 403,
                "error.lifecycle.accessDenied", DualConfirmationDenyReason.SCOPE_DENIED);

        verify(confirmationMapper, never()).insert(any(LifecycleDualConfirmation.class));
        verify(taskMapper, never()).update(any(), any());
    }

    @Test
    void 確認時に提出者の述語が成り立たずfenceが変わらなければSUBMITTER_PREDICATE_LOSTで世代の変化はその成分にする() {
        doThrow(BusinessException.of(403, "error.lifecycle.taskEditDenied")).when(evaluator).assertCanEditTask(
                argThat(actor -> actor != null && actor.userId() == SUBMITTER), any(), any(), any());

        assertRejected(() -> service.confirm(confirmAttempt(Decision.CONFIRM, 1L)), 409,
                "error.lifecycle.authorizationChanged", DualConfirmationDenyReason.SUBMITTER_PREDICATE_LOST);

        when(epochReader.readEngineer(TENANT, ENGINEER)).thenReturn(1L);
        assertRejected(() -> service.confirm(confirmAttempt(Decision.CONFIRM, 1L)), 409,
                "error.lifecycle.authorizationChanged", DualConfirmationDenyReason.AUTHORIZATION_CHANGED_ENGINEER);

        verify(confirmationMapper, never()).insert(any(LifecycleDualConfirmation.class));
    }

    @Test
    void 確認の同key同内容の再送は保存済みの結果を返し判断を変えた同keyは409で何も書かない() {
        String confirmHash = LifecycleCanonicalization.Request.confirm(TENANT, TASK, GENERATION, CONFIRMER, KEY,
                "確認しました", binding(1L), Decision.CONFIRM).sha256();
        when(idempotencyMapper.selectOne(any())).thenReturn(storedIdempotency(confirmHash.toUpperCase(), CONFIRMATION));
        when(confirmationMapper.selectOne(any())).thenReturn(confirmation());
        when(projectionMapper.selectOne(any())).thenReturn(projection("CONFIRMED"));

        DualConfirmationResultDto replayed = service.confirm(confirmAttempt(Decision.CONFIRM, 1L));

        assertThat(replayed.isReplayed()).isTrue();
        assertEquals(CONFIRMATION, replayed.getConfirmationId());
        assertEquals("CONFIRMED", replayed.getState());

        assertRejected(() -> service.confirm(confirmAttempt(Decision.REJECT, 1L)), 409,
                "error.lifecycle.idempotencyConflict", DualConfirmationDenyReason.IDEMPOTENCY_CONFLICT);
        verify(confirmationMapper, never()).insert(any(LifecycleDualConfirmation.class));
        verify(taskMapper, never()).update(any(), any());
    }

    @Test
    void 確認の状態判定は決定済みやrevision違いを409のSUBMISSION_STALEにする() {
        when(projectionMapper.selectOne(any())).thenReturn(projection("CONFIRMED"));
        assertRejected(() -> service.confirm(confirmAttempt(Decision.CONFIRM, 1L)), 409,
                "error.lifecycle.submissionStale", DualConfirmationDenyReason.SUBMISSION_STALE);

        when(projectionMapper.selectOne(any())).thenReturn(projection("PENDING"));
        assertRejected(() -> service.confirm(confirmAttempt(Decision.CONFIRM, 2L)), 409,
                "error.lifecycle.submissionStale", DualConfirmationDenyReason.SUBMISSION_STALE);

        when(projectionMapper.selectOne(any())).thenReturn(null);
        assertRejected(() -> service.confirm(confirmAttempt(Decision.CONFIRM, 1L)), 409,
                "error.lifecycle.submissionStale", DualConfirmationDenyReason.SUBMISSION_STALE);
        verify(confirmationMapper, never()).insert(any(LifecycleDualConfirmation.class));
    }

    @Test
    void 確認で後続タスクを昇格するときは書く列だけを指名しentity全体を書き戻さない() {
        when(dependencyReader.readConfirm(eq(TENANT), eq(TASK), eq(SUBMISSION), eq(CONFIRMER), any()))
                .thenAnswer(invocation -> Optional.of(confirmDependencies(SUBMITTER, Map.of(SUCCESSOR, List.of(TASK)))));
        when(projectionMapper.selectOne(any())).thenReturn(projection("PENDING"));
        LifecycleTask successor = task(SUCCESSOR, "NONE", "PENDING");
        successor.setVersion(3);
        successor.setTaskName("後続タスク");
        when(taskMapper.selectOne(any())).thenReturn(successor);

        service.confirm(confirmAttempt(Decision.CONFIRM, 1L, Map.of(SUCCESSOR, List.of(TASK))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<LifecycleTask>> updates = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper, times(2)).update(isNull(), updates.capture());
        LambdaUpdateWrapper<LifecycleTask> promote = (LambdaUpdateWrapper<LifecycleTask>) updates.getAllValues().get(1);
        assertThat(promote.getSqlSet()).contains("status=", "updated_by=", "updated_at=", "version=")
                .doesNotContain("task_name", "completed_by", "evidence_data_json", "assignee_user_id");
        assertThat(promote.getSqlSegment()).contains("case_id", "version", "status");
        assertTrue(promote.getParamNameValuePairs().containsValue(4), "versionは読んだ値+1で書く");
        verify(taskMapper, never()).updateById(any(LifecycleTask.class));
    }

    // ===================================================================== helpers

    private void verifyNothingWritten() {
        verify(submissionMapper, never()).insert(any(LifecycleDualSubmission.class));
        verify(submissionDocumentMapper, never()).insert(any(LifecycleDualSubmissionDocument.class));
        verify(projectionMapper, never()).insert(any(LifecycleDualProjection.class));
        verify(projectionMapper, never()).compareAndSet(any(), anyLong());
        verify(idempotencyMapper, never()).insert(any(LifecycleDualIdempotency.class));
        verify(dualEventMapper, never()).insert(any(com.ses.entity.LifecycleDualEvent.class));
    }

    private static void assertRejected(Executable call, int status, String key, DualConfirmationDenyReason reason) {
        DualConfirmationRejection rejection = assertThrows(DualConfirmationRejection.class, call);
        assertEquals(status, rejection.status(), key);
        assertEquals(key, rejection.messageKey());
        assertEquals(reason, rejection.reason(), key);
    }

    private void actorIs(long userId, String role) {
        lenient().when(evaluator.resolveActor(eq(TENANT), eq(userId), any()))
                .thenReturn(Optional.of(new ExplicitActor(TENANT, userId, role, AS_OF)));
    }

    private SubmitAttempt submitAttempt(long requestedGeneration) {
        return new SubmitAttempt(TENANT, TASK, SUBMITTER, DualConfirmationChannel.INTERNAL, requestedGeneration,
                DOCUMENT, VERSION, "提出します", KEY, REQUEST_SHA, submitDependencies().plan(), ELIGIBLE, AS_OF);
    }

    private SubmitDependencies submitDependencies() {
        return new SubmitDependencies(TENANT, context, SUBMITTER, new TreeSet<>(), DOCUMENT, VERSION);
    }

    private ConfirmAttempt confirmAttempt(Decision decision, long revision) {
        return confirmAttempt(decision, revision, Map.of());
    }

    /** discoveryのplan（後続と後続の先行を順5で含む）。lock後の再読込と同じ依存先なら一致する。 */
    private ConfirmAttempt confirmAttempt(Decision decision, long revision, Map<Long, List<Long>> successors) {
        String comment = decision == Decision.CONFIRM ? "確認しました" : "差戻します";
        return new ConfirmAttempt(TENANT, TASK, SUBMISSION, CONFIRMER, binding(revision), decision, comment, KEY,
                confirmDependencies(SUBMITTER, successors).plan(), ELIGIBLE, AS_OF);
    }

    private static ConfirmationBinding binding(long revision) {
        return new ConfirmationBinding(SUBMISSION, revision, EVIDENCE_SHA);
    }

    private ConfirmDependencies confirmDependencies(long submitterUserId, Map<Long, List<Long>> successors) {
        return new ConfirmDependencies(TENANT, context, submission(submitterUserId), submissionDocument(), CONFIRMER,
                new ExplicitActor(TENANT, submitterUserId, "営業", AS_OF), new TreeSet<>(), new TreeSet<>(), successors);
    }

    private static LifecycleDualSubmission submission(long submitterUserId) {
        return LifecycleDualSubmission.builder().id(SUBMISSION).tenantId(TENANT).caseId(CASE).taskId(TASK)
                .engineerId(ENGINEER).taskGeneration(GENERATION).revision(1).submitterUserId(submitterUserId)
                .submitterRoleSnapshot("営業").globalEpoch(0L).submitterActorEpoch(0L).engineerScopeEpoch(0L)
                .taskVersionSnapshot(0).evidenceSha256(EVIDENCE_SHA.toUpperCase()).requestSha256(REQUEST_SHA).build();
    }

    private static LifecycleDualSubmissionDocument submissionDocument() {
        return LifecycleDualSubmissionDocument.builder().tenantId(TENANT).submissionId(SUBMISSION).documentId(DOCUMENT)
                .documentVersionId(VERSION).versionNo(1).sha256(CONTENT_SHA).evidenceEligibilityEpoch(0L).build();
    }

    private static LifecycleDualProjection projection(String state) {
        return LifecycleDualProjection.builder().tenantId(TENANT).taskId(TASK).caseId(CASE).taskGeneration(GENERATION)
                .revision(1).submissionId(SUBMISSION).evidenceSha256(EVIDENCE_SHA).state(state).projectionVersion(5L)
                .build();
    }

    private static LifecycleDualIdempotency storedIdempotency(String requestSha256, Long confirmationId) {
        LifecycleDualIdempotency stored = LifecycleDualIdempotency.builder().tenantId(TENANT).caseId(CASE).taskId(TASK)
                .taskGeneration(GENERATION).actorUserId(confirmationId == null ? SUBMITTER : CONFIRMER)
                .idempotencyKey(KEY).requestSha256(requestSha256).submissionId(SUBMISSION)
                .confirmationId(confirmationId).build();
        stored.setId(801L);
        return stored;
    }

    private static LifecycleDualConfirmation confirmation() {
        LifecycleDualConfirmation confirmation = LifecycleDualConfirmation.builder().tenantId(TENANT).caseId(CASE)
                .taskId(TASK).taskGeneration(GENERATION).submissionId(SUBMISSION).submissionRevision(1)
                .confirmerUserId(CONFIRMER).confirmerRoleSnapshot("HR").globalEpoch(0L).confirmerActorEpoch(0L)
                .decision("CONFIRMED").build();
        confirmation.setId(CONFIRMATION);
        return confirmation;
    }

    private static LifecycleTask task(long id, String evidenceType, String status) {
        LifecycleTask task = LifecycleTask.builder().caseId(CASE).taskCode("RESIGN_DUAL").taskName("二者確認")
                .evidenceType(evidenceType).status(status).assigneeRole("営業").isEngineerVisible(1).build();
        task.setId(id);
        task.setVersion(0);
        return task;
    }

    private static LifecycleCase lifecycleCase(String status) {
        LifecycleCase lcCase = LifecycleCase.builder().tenantId(TENANT).caseNo("LC-1").engineerId(ENGINEER)
                .status(status).build();
        lcCase.setId(CASE);
        return lcCase;
    }

    private static Engineer engineer() {
        Engineer engineer = Engineer.builder().tenantId(TENANT).build();
        engineer.setId(ENGINEER);
        return engineer;
    }
}
