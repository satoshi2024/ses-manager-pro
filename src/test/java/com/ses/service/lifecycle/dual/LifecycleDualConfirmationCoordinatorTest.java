package com.ses.service.lifecycle.dual;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import com.ses.dto.lifecycle.dual.DualConfirmationDecisionRequest;
import com.ses.dto.lifecycle.dual.DualConfirmationDocumentSelectionRequest;
import com.ses.dto.lifecycle.dual.DualConfirmationResultDto;
import com.ses.dto.lifecycle.dual.DualConfirmationSubmitRequest;
import com.ses.dto.lifecycle.dual.DualConfirmationViewDto;
import com.ses.entity.Engineer;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleDualConfirmation;
import com.ses.entity.LifecycleDualProjection;
import com.ses.entity.LifecycleDualSubmission;
import com.ses.entity.LifecycleDualSubmissionDocument;
import com.ses.entity.LifecycleTask;
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
import com.ses.service.document.DocumentEvidenceEligibility.Verdict;
import com.ses.service.document.DocumentEvidenceStorageVerifier;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationAttemptService.ConfirmAttempt;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationAttemptService.SubmitAttempt;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDenyRecorder.DenyRecord;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDependencyReader.ConfirmDependencies;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationDependencyReader.TaskContext;
import com.ses.service.lifecycle.evidence.LifecycleCanonicalization;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan;
import com.ses.service.security.ExplicitActorAuthorizationEvaluator;
import com.ses.service.security.ExplicitActorAuthorizationEvaluator.ExplicitActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * U11（NF01細粒度epoch計画 §6.2、W3-21a）: 二者確認coordinatorの単体試験（DB不要、協力部品はmock）。
 *
 * <ul>
 *   <li>ambient transactionの拒否（計画の {@code NEVER} の意図。注釈を付けない理由はcoordinatorのJavadoc）。</li>
 *   <li>再試行の予算: {@code DependencyChangedException}・1213・1205・{@code QueryTimeoutException}・
 *       {@code TransactionTimedOutException}・{@code DuplicateKeyException}（rollbackに隠れた例外を含む）を3回まで数え、
 *       使い切ると409 {@code error.lifecycle.concurrentUpdateRetryExhausted}。試行ごとに新しいdiscoveryから始める。</li>
 *   <li>決定的な拒否（業務例外）は再試行しない。拒否eventは試行のrollback後（試行の呼出しの後）に記録し、失敗しても応答を変えない。</li>
 *   <li>エラー対応表（§5.9）と拒否eventのreason（§5.8）。スイッチOFF・bodyの形式違反は記録しない。</li>
 * </ul>
 */
class LifecycleDualConfirmationCoordinatorTest {

    private static final String TENANT = "tenant-a";
    private static final long SUBMITTER = 11L;
    private static final long CONFIRMER = 22L;
    private static final long TASK = 101L;
    private static final long CASE = 201L;
    private static final long ENGINEER = 301L;
    private static final long DOCUMENT = 401L;
    private static final long VERSION = 501L;
    private static final long SUBMISSION = 601L;
    private static final long GENERATION = 4L;
    private static final String KEY = "op-20261116.abc_~-1";
    private static final String EVIDENCE_SHA = "b".repeat(64);
    private static final EligibleVersion ELIGIBLE = new EligibleVersion(TENANT, DOCUMENT, VERSION, 1,
            "skill/401/1.xlsx", "a".repeat(64), 10L);

    private FeatureSwitchProperties properties;
    private SysUserMapper sysUserMapper;
    private ExplicitActorAuthorizationEvaluator evaluator;
    private LifecycleDualConfirmationDependencyReader reader;
    private DocumentEvidenceEligibility eligibility;
    private DocumentEvidenceStorageVerifier verifier;
    private LifecycleDualConfirmationAttemptService attempts;
    private LifecycleDualConfirmationDenyRecorder recorder;
    private AuthorizationEpochMapper epochMapper;
    private LifecycleDualProjectionMapper projectionMapper;
    private LifecycleDualSubmissionDocumentMapper submissionDocumentMapper;
    private LifecycleDualConfirmationMapper confirmationMapper;
    private AtomicInteger pauses;
    private LifecycleDualConfirmationCoordinator coordinator;
    private TaskContext context;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new FeatureSwitchProperties();
        properties.getNf01().setEnabled(true);
        properties.getNf01DualConfirmation().setEnabled(true);
        ObjectProvider<FeatureSwitches> switches = mock(ObjectProvider.class);
        when(switches.getIfAvailable()).thenAnswer(invocation -> new FeatureSwitches(properties));
        sysUserMapper = mock(SysUserMapper.class);
        evaluator = mock(ExplicitActorAuthorizationEvaluator.class);
        reader = mock(LifecycleDualConfirmationDependencyReader.class);
        eligibility = mock(DocumentEvidenceEligibility.class);
        verifier = mock(DocumentEvidenceStorageVerifier.class);
        attempts = mock(LifecycleDualConfirmationAttemptService.class);
        recorder = mock(LifecycleDualConfirmationDenyRecorder.class);
        epochMapper = mock(AuthorizationEpochMapper.class);
        projectionMapper = mock(LifecycleDualProjectionMapper.class);
        submissionDocumentMapper = mock(LifecycleDualSubmissionDocumentMapper.class);
        confirmationMapper = mock(LifecycleDualConfirmationMapper.class);
        pauses = new AtomicInteger();
        coordinator = new LifecycleDualConfirmationCoordinator(switches, sysUserMapper, evaluator, reader, eligibility,
                verifier, attempts, recorder, epochMapper, projectionMapper, submissionDocumentMapper,
                confirmationMapper, mock(DocumentLinkMapper.class), mock(DocumentVersionMapper.class),
                mock(DocumentMapper.class), pauses::incrementAndGet);

        context = new TaskContext(task("DUAL_CONFIRMATION"), lifecycleCase(), engineer());
        principalIs(SUBMITTER, "営業");
        principalIs(CONFIRMER, "HR");
        lenient().when(reader.readTaskContext(TENANT, TASK)).thenReturn(Optional.of(context));
        lenient().when(reader.submitterOrganizations(any())).thenReturn(new TreeSet<>());
        lenient().when(reader.readConfirm(eq(TENANT), eq(TASK), eq(SUBMISSION), anyLong(), any()))
                .thenReturn(Optional.of(confirmDependencies(SUBMITTER)));
        lenient().when(eligibility.evaluate(any())).thenReturn(new DocumentEvidenceEligibility.Result(
                Verdict.ELIGIBLE, ELIGIBLE));
        // 提出のdiscoveryは適格性の前に文書ACLと案件の要員へのlinkを確かめる（W3-21a review P3-5）
        lenient().when(eligibility.visibleEvidenceCandidate(eq(TENANT), anyLong(), anyLong())).thenReturn(true);
        lenient().when(evaluator.isEngineerAllowedByDataScope(any(), any())).thenReturn(true);
        lenient().when(verifier.verify(any())).thenReturn(DocumentEvidenceStorageVerifier.Outcome.MATCH);
        lenient().when(epochMapper.selectTaskGeneration(TASK, CASE, TENANT))
                .thenReturn(new AuthorizationEpochMapper.EpochRow(GENERATION, 0));
    }

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    // ===================================================================== transaction境界

    @Test
    void ambient_transactionからの呼出しは全入口で拒否し試行も拒否eventも行わない() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(IllegalStateException.class, () -> submit(DualConfirmationChannel.INTERNAL, submitBody()));
        assertThrows(IllegalStateException.class, () -> decide(decisionBody("CONFIRM")));
        assertThrows(IllegalStateException.class,
                () -> coordinator.view(TASK, principal(SUBMITTER), DualConfirmationChannel.INTERNAL));
        assertThrows(IllegalStateException.class,
                () -> coordinator.candidates(TASK, principal(SUBMITTER), DualConfirmationChannel.INTERNAL));

        verifyNoInteractions(attempts, recorder, reader);
    }

    @Test
    void 実transactionを伴わないsynchronizationの中からの呼出しも拒否する() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(IllegalStateException.class, () -> submit(DualConfirmationChannel.INTERNAL, submitBody()));
            assertThrows(IllegalStateException.class, () -> decide(decisionBody("REJECT")));
            assertThrows(IllegalStateException.class,
                    () -> coordinator.view(TASK, principal(SUBMITTER), DualConfirmationChannel.SELF));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verifyNoInteractions(attempts, recorder, reader);
    }

    @Test
    void 試行とdeny_recorderの境界はREQUIRES_NEWでcoordinatorはtransactionを宣言しない() throws Exception {
        for (String name : List.of("submit", "confirm")) {
            Method method = Arrays.stream(LifecycleDualConfirmationAttemptService.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name) && Modifier.isPublic(m.getModifiers()))
                    .findFirst().orElseThrow();
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional).as(name).isNotNull();
            assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
            assertThat(transactional.isolation()).isEqualTo(Isolation.READ_COMMITTED);
            assertThat(transactional.timeout()).isEqualTo(LifecycleDualConfirmationAttemptService.ATTEMPT_TIMEOUT_SECONDS)
                    .isEqualTo(10);
            assertThat(transactional.rollbackFor()).contains(Exception.class);
        }
        Transactional deny = LifecycleDualConfirmationDenyRecorder.class.getMethod("record", DenyRecord.class)
                .getAnnotation(Transactional.class);
        assertThat(deny.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(deny.rollbackFor()).contains(Exception.class);
        assertThat(LifecycleDualConfirmationCoordinator.class.getAnnotation(Transactional.class)).isNull();
        for (Method method : LifecycleDualConfirmationCoordinator.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Transactional.class)).as(method.getName()).isNull();
        }
    }

    // ===================================================================== 子スイッチ・入力の検査

    @Test
    void 子スイッチOFFでは全入口が400で拒否eventを記録しない() {
        properties.getNf01DualConfirmation().setEnabled(false);
        assertAllEntriesDisabled();

        properties.getNf01DualConfirmation().setEnabled(true);
        properties.getNf01().setEnabled(false);
        assertAllEntriesDisabled();
        verifyNoInteractions(attempts, recorder, reader, sysUserMapper);
    }

    static Stream<String> invalidKeys() {
        return Stream.of("", " ", "key-1 ", " key-1", "key-1\n", "key-1\r\n", "key-1\t", "key 1", "key:1",
                "key/1", "キー1", "a".repeat(191));
    }

    @ParameterizedTest
    @MethodSource("invalidKeys")
    void Idempotency_Keyの末尾空白や改行や許可外文字は400(String key) {
        BusinessException denied = assertThrows(BusinessException.class, () -> coordinator.submit(TASK,
                principal(SUBMITTER), DualConfirmationChannel.INTERNAL, submitBody(), key));
        assertBusiness(denied, 400, "error.lifecycle.idempotencyKeyInvalid");
        BusinessException deniedDecision = assertThrows(BusinessException.class, () -> coordinator.decide(TASK,
                SUBMISSION, principal(CONFIRMER), decisionBody("CONFIRM"), key));
        assertBusiness(deniedDecision, 400, "error.lifecycle.idempotencyKeyInvalid");
        verifyNoInteractions(attempts, recorder);
    }

    @Test
    void Idempotency_Keyの欠落は400で190文字までは受け付ける() {
        assertBusiness(assertThrows(BusinessException.class, () -> coordinator.submit(TASK, principal(SUBMITTER),
                DualConfirmationChannel.INTERNAL, submitBody(), null)), 400, "error.lifecycle.idempotencyKeyInvalid");
        String longest = "A".repeat(100) + "z9._~-".repeat(15);
        assertEquals(190, longest.length());
        assertEquals(longest, LifecycleDualConfirmationCoordinator.requireIdempotencyKey(longest));
    }

    @Test
    void 未知propertyとbodyの形式違反は400で拒否eventを記録しない() {
        DualConfirmationSubmitRequest unknownTop = submitBody();
        unknownTop.putUnknown("tenantId", "tenant-b");
        DualConfirmationSubmitRequest unknownNested = submitBody();
        unknownNested.getDocumentSelections().get(0).putUnknown("sha256", "0".repeat(64));
        DualConfirmationSubmitRequest noGeneration = submitBody();
        noGeneration.setTaskGeneration(null);
        DualConfirmationSubmitRequest tooLong = submitBody();
        tooLong.setComment("あ".repeat(1001));
        DualConfirmationSubmitRequest loneSurrogate = submitBody();
        loneSurrogate.setComment("確認\uD800");
        for (DualConfirmationSubmitRequest body : List.of(unknownTop, unknownNested, noGeneration, tooLong,
                loneSurrogate)) {
            assertBusiness(assertThrows(BusinessException.class, () -> submit(DualConfirmationChannel.INTERNAL, body)),
                    400, "error.validationError");
        }
        DualConfirmationDecisionRequest unknownDecision = decisionBody("CONFIRM");
        unknownDecision.putUnknown("actorUserId", 1);
        for (DualConfirmationDecisionRequest body : List.of(unknownDecision, decisionBody("APPROVE"),
                decisionBody("confirm"), withSha(decisionBody("REJECT"), "xyz"), withRevision(decisionBody("CONFIRM"), 0L))) {
            assertBusiness(assertThrows(BusinessException.class, () -> decide(body)), 400, "error.validationError");
        }
        DualConfirmationSubmitRequest exactLimit = submitBody();
        exactLimit.setComment("😀".repeat(1000));
        assertThat(LifecycleDualConfirmationCoordinator.wellFormedComment(exactLimit.getComment())).isTrue();
        verifyNoInteractions(attempts, recorder);
    }

    // ===================================================================== 再試行

    static Stream<Arguments> retryableFailures() {
        TransactionSystemException hidden = new TransactionSystemException("rollbackに失敗");
        hidden.initApplicationException(new CannotAcquireLockException("Lock wait timeout exceeded"));
        return Stream.of(
                Arguments.of("依存の変化", new LifecycleLockPlan.DependencyChangedException()),
                Arguments.of("1205", new CannotAcquireLockException("Lock wait timeout exceeded")),
                Arguments.of("1213", new PessimisticLockingFailureException("Deadlock found")),
                Arguments.of("文の実行中のtimeout", new QueryTimeoutException("statement cancelled")),
                Arguments.of("期限切れ後のtimeout", new TransactionTimedOutException("deadline exceeded")),
                Arguments.of("冪等行の同時insert", new DuplicateKeyException("uk_dual_idempotency")),
                Arguments.of("rollbackに隠れた1205", hidden),
                Arguments.of("未変換の1213", new RuntimeException(new SQLException("Deadlock", "40001", 1213))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retryableFailures")
    void 予算に数える例外は新しいdiscoveryから3回まで試し使い切ると専用keyの409(String label, RuntimeException failure) {
        when(attempts.submit(any())).thenThrow(failure);

        BusinessException denied = assertThrows(BusinessException.class,
                () -> submit(DualConfirmationChannel.INTERNAL, submitBody()));

        assertBusiness(denied, 409, "error.lifecycle.concurrentUpdateRetryExhausted");
        verify(attempts, times(3)).submit(any());
        verify(reader, times(3)).readTaskContext(TENANT, TASK);
        verify(verifier, times(3)).verify(ELIGIBLE);
        assertEquals(2, pauses.get(), "試行間にだけjitterを入れる");
        DenyRecord record = recorded();
        assertEquals(DualConfirmationDenyReason.RETRY_EXHAUSTED, record.reason());
        assertEquals(409, record.httpStatus());
        assertEquals(3, record.attemptCount());
        assertEquals("SUBMIT", record.operation());
        assertEquals(TENANT, record.tenantId());
        assertEquals(SUBMITTER, record.actorUserId());
        assertEquals(TASK, record.requestedTaskId());
        assertEquals(CASE, record.caseId());
        assertEquals(GENERATION, record.taskGeneration());
    }

    @Test
    void 確認でも期限切れのtimeoutを予算に数え使い切ると409() {
        when(attempts.confirm(any())).thenThrow(new TransactionTimedOutException("deadline exceeded"));

        assertBusiness(assertThrows(BusinessException.class, () -> decide(decisionBody("REJECT"))), 409,
                "error.lifecycle.concurrentUpdateRetryExhausted");

        verify(attempts, times(3)).confirm(any());
        verify(reader, times(3)).readConfirm(eq(TENANT), eq(TASK), eq(SUBMISSION), eq(CONFIRMER), any());
        DenyRecord record = recorded();
        assertEquals(DualConfirmationDenyReason.RETRY_EXHAUSTED, record.reason());
        assertEquals("REJECT", record.operation());
        assertEquals(SUBMISSION, record.requestedSubmissionId());
        assertEquals(SUBMISSION, record.submissionId());
    }

    @Test
    void 一時的な失敗の後に成功すれば結果を返し拒否eventを記録しない() {
        DualConfirmationResultDto accepted = DualConfirmationResultDto.builder().taskId(TASK).submissionId(SUBMISSION)
                .state("PENDING").build();
        when(attempts.submit(any())).thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"))
                .thenReturn(accepted);

        assertThat(submit(DualConfirmationChannel.INTERNAL, submitBody())).isSameAs(accepted);

        verify(attempts, times(2)).submit(any());
        assertEquals(1, pauses.get());
        verifyNoInteractions(recorder);
    }

    @Test
    void 試行の入力はbodyの世代で作ったrequest_hashとdiscoveryのplanと検証済みの版を持つ() {
        when(attempts.submit(any())).thenReturn(DualConfirmationResultDto.builder().build());

        submit(DualConfirmationChannel.SELF, submitBodyAs(SUBMITTER, "要員"));

        ArgumentCaptor<SubmitAttempt> captor = ArgumentCaptor.forClass(SubmitAttempt.class);
        verify(attempts).submit(captor.capture());
        SubmitAttempt attempt = captor.getValue();
        String expected = new LifecycleCanonicalization.Request(TENANT, TASK, GENERATION,
                LifecycleCanonicalization.Operation.SUBMIT, SUBMITTER, KEY, "確認をお願いします",
                List.of(new LifecycleCanonicalization.DocumentSelection(DOCUMENT, VERSION))).sha256();
        assertEquals(expected, attempt.requestSha256());
        assertThat(attempt.requestSha256()).matches("[0-9a-f]{64}");
        assertEquals(DualConfirmationChannel.SELF, attempt.channel());
        assertEquals(ELIGIBLE, attempt.verifiedVersion());
        assertThat(attempt.plan().locks()).extracting(LifecycleLockPlan.LockKey::rank).containsExactly(
                LifecycleLockPlan.Rank.GLOBAL_AUTHORIZATION_EPOCH, LifecycleLockPlan.Rank.SYS_USER,
                LifecycleLockPlan.Rank.LIFECYCLE_CASE, LifecycleLockPlan.Rank.LIFECYCLE_TASK,
                LifecycleLockPlan.Rank.ENGINEER, LifecycleLockPlan.Rank.DOCUMENT,
                LifecycleLockPlan.Rank.DOCUMENT_VERSION);
    }

    // ===================================================================== 決定的な拒否と拒否event

    @Test
    void 決定的な拒否は再試行せず試行のrollback後に拒否eventを記録する() {
        when(attempts.submit(any())).thenThrow(DualConfirmationRejection.of(409,
                "error.lifecycle.authorizationChanged", DualConfirmationDenyReason.AUTHORIZATION_CHANGED_ENGINEER));

        assertBusiness(assertThrows(BusinessException.class, () -> submit(DualConfirmationChannel.INTERNAL,
                submitBody())), 409, "error.lifecycle.authorizationChanged");

        verify(attempts, times(1)).submit(any());
        assertEquals(0, pauses.get());
        InOrder order = inOrder(attempts, recorder);
        order.verify(attempts).submit(any());
        order.verify(recorder).record(any());
        DenyRecord record = recorded();
        assertEquals(DualConfirmationDenyReason.AUTHORIZATION_CHANGED_ENGINEER, record.reason());
        assertEquals(1, record.attemptCount());
    }

    @Test
    void 試行の業務例外はmessage_keyからreasonを決め再試行しない() {
        when(attempts.confirm(any())).thenThrow(BusinessException.of(409, "error.authorization.epochUnavailable"));

        assertBusiness(assertThrows(BusinessException.class, () -> decide(decisionBody("CONFIRM"))), 409,
                "error.authorization.epochUnavailable");

        verify(attempts, times(1)).confirm(any());
        assertEquals(DualConfirmationDenyReason.EPOCH_UNAVAILABLE, recorded().reason());
    }

    @Test
    void 拒否eventの記録に失敗しても応答は変えない() {
        when(attempts.submit(any())).thenThrow(DualConfirmationRejection.of(409, "error.lifecycle.submissionStale",
                DualConfirmationDenyReason.SUBMISSION_STALE));
        doThrow(new IllegalStateException("insert失敗")).when(recorder).record(any());

        assertBusiness(assertThrows(BusinessException.class, () -> submit(DualConfirmationChannel.INTERNAL,
                submitBody())), 409, "error.lifecycle.submissionStale");
    }

    @Test
    void 未知の例外は再試行せず拒否eventにもしない() {
        when(attempts.submit(any())).thenThrow(new IllegalStateException("lock順に違反します"));

        assertThrows(IllegalStateException.class, () -> submit(DualConfirmationChannel.INTERNAL, submitBody()));

        verify(attempts, times(1)).submit(any());
        verifyNoInteractions(recorder);
    }

    // ===================================================================== エラー対応表（§5.9）

    @Test
    void 他tenantや不可視のタスクは404で記録しcaseを持たない() {
        when(reader.readTaskContext(TENANT, TASK)).thenReturn(Optional.empty());

        assertDenied(() -> submit(DualConfirmationChannel.INTERNAL, submitBody()), 404,
                "error.lifecycle.taskNotFound", DualConfirmationDenyReason.NOT_FOUND_OR_FOREIGN);
        assertThat(recorded().caseId()).isNull();
    }

    @Test
    void DUAL以外のタスクへの提出は400() {
        TaskContext other = new TaskContext(task("DOCUMENT_LINK"), lifecycleCase(), engineer());
        when(reader.readTaskContext(TENANT, TASK)).thenReturn(Optional.of(other));

        // 400の状態の不成立は409の世代不一致（SUBMISSION_STALE）と分けて記録する（W3-21a review P3-8）
        assertDenied(() -> submit(DualConfirmationChannel.INTERNAL, submitBody()), 400,
                "error.lifecycle.invalidStateTransition", DualConfirmationDenyReason.INVALID_STATE);
    }

    @Test
    void 提出のdiscoveryは案件の要員に紐付かない文書を存在しない文書と同じ400にし適格性を評価しない() {
        // 範囲外の文書がhold中（適格性ならEVIDENCE_UNAVAILABLE・409）でも、応答は存在しない文書と区別できない
        when(eligibility.evaluate(any())).thenReturn(new DocumentEvidenceEligibility.Result(
                Verdict.DOCUMENT_UNAVAILABLE, null));
        when(eligibility.visibleEvidenceCandidate(TENANT, DOCUMENT, ENGINEER)).thenReturn(false);

        assertDenied(() -> submit(DualConfirmationChannel.INTERNAL, submitBody()), 400, "error.document.notFound",
                DualConfirmationDenyReason.NOT_FOUND_OR_FOREIGN);

        verify(eligibility, never()).evaluate(any());
        verify(verifier, never()).verify(any());
        verify(attempts, never()).submit(any());
    }

    @Test
    void 提出のdiscoveryは提出者の文書ACL外の文書を存在しない文書と同じ400にしlinkも適格性も見ない() {
        when(evaluator.isEngineerAllowedByDataScope(any(), any())).thenReturn(false);

        assertDenied(() -> submit(DualConfirmationChannel.INTERNAL, submitBody()), 400, "error.document.notFound",
                DualConfirmationDenyReason.SCOPE_DENIED);

        verify(eligibility, never()).visibleEvidenceCandidate(any(), anyLong(), anyLong());
        verify(eligibility, never()).evaluate(any());
        verify(attempts, never()).submit(any());
    }

    @Test
    void 提出のdiscoveryは文書の可視性を適格性より先に判定し可視の文書だけ適格性の詳細を返す() {
        when(eligibility.evaluate(any())).thenReturn(new DocumentEvidenceEligibility.Result(
                Verdict.DOCUMENT_UNAVAILABLE, null));

        assertDenied(() -> submit(DualConfirmationChannel.INTERNAL, submitBody()), 409,
                "error.lifecycle.evidenceDocumentUnavailable", DualConfirmationDenyReason.EVIDENCE_UNAVAILABLE);

        InOrder order = inOrder(evaluator, eligibility);
        order.verify(evaluator).isEngineerAllowedByDataScope(any(), eq(ENGINEER));
        order.verify(eligibility).visibleEvidenceCandidate(TENANT, DOCUMENT, ENGINEER);
        order.verify(eligibility).evaluate(any());
    }

    @Test
    void 同tenantのscope外の提出は403() {
        doThrow(BusinessException.of(403, "error.lifecycle.taskEditDenied")).when(evaluator)
                .assertCanEditTask(any(), any(), any(), any());

        assertDenied(() -> submit(DualConfirmationChannel.INTERNAL, submitBody()), 403,
                "error.lifecycle.taskEditDenied", DualConfirmationDenyReason.SCOPE_DENIED);
    }

    @Test
    void 本人の入口を要員以外が使うと403() {
        assertDenied(() -> submit(DualConfirmationChannel.SELF, submitBody()), 403,
                "error.lifecycle.accessDenied", DualConfirmationDenyReason.SCOPE_DENIED);
    }

    @Test
    void 文書指定が1件でない場合と版IDなしは400() {
        DualConfirmationSubmitRequest two = submitBody();
        two.setDocumentSelections(List.of(selection(DOCUMENT, VERSION), selection(DOCUMENT + 1, VERSION + 1)));
        assertDenied(() -> submit(DualConfirmationChannel.INTERNAL, two), 400,
                "error.lifecycle.evidenceSelectionInvalid", DualConfirmationDenyReason.EVIDENCE_SELECTION_INVALID);

        DualConfirmationSubmitRequest none = submitBody();
        none.setDocumentSelections(List.of());
        assertDenied(() -> submit(DualConfirmationChannel.INTERNAL, none), 400,
                "error.lifecycle.evidenceSelectionInvalid", DualConfirmationDenyReason.EVIDENCE_SELECTION_INVALID);

        DualConfirmationSubmitRequest versionless = submitBody();
        versionless.setDocumentSelections(List.of(selection(DOCUMENT, null)));
        assertDenied(() -> submit(DualConfirmationChannel.INTERNAL, versionless), 400,
                "error.lifecycle.evidenceVersionRequired", DualConfirmationDenyReason.EVIDENCE_VERSION_REQUIRED);
    }

    @Test
    void 文書適格性の各判定は対応表のkeyとreasonになる() {
        Map<Verdict, DualConfirmationDenyReason> expected = Map.of(
                Verdict.DOCUMENT_NOT_FOUND, DualConfirmationDenyReason.NOT_FOUND_OR_FOREIGN,
                Verdict.DOCUMENT_TYPE_UNSUPPORTED, DualConfirmationDenyReason.EVIDENCE_TYPE_UNSUPPORTED,
                Verdict.TARGET_UNSUPPORTED, DualConfirmationDenyReason.EVIDENCE_TARGET_UNSUPPORTED,
                Verdict.DOCUMENT_UNAVAILABLE, DualConfirmationDenyReason.EVIDENCE_UNAVAILABLE,
                Verdict.VERSION_NOT_FOUND, DualConfirmationDenyReason.NOT_FOUND_OR_FOREIGN,
                Verdict.VERSION_NOT_CLEAN, DualConfirmationDenyReason.EVIDENCE_VERSION_NOT_CLEAN);
        expected.forEach((verdict, reason) -> {
            org.mockito.Mockito.reset(recorder);
            when(eligibility.evaluate(any())).thenReturn(new DocumentEvidenceEligibility.Result(verdict, null));
            assertDenied(() -> submit(DualConfirmationChannel.INTERNAL, submitBody()), verdict.httpStatus(),
                    verdict.messageKey(), reason);
        });
        verify(attempts, never()).submit(any());
    }

    @Test
    void storageの内容が版と一致しなければ409で試行しない() {
        when(verifier.verify(any())).thenReturn(DocumentEvidenceStorageVerifier.Outcome.MISMATCH);

        assertDenied(() -> submit(DualConfirmationChannel.INTERNAL, submitBody()), 409,
                "error.lifecycle.evidenceDocumentUnavailable", DualConfirmationDenyReason.STORAGE_INTEGRITY);
        verify(attempts, never()).submit(any());
    }

    @Test
    void 確認者のroleが管理者HR以外と提出者本人の確認は403() {
        principalIs(CONFIRMER, "マネージャー");
        assertDenied(() -> decide(decisionBody("CONFIRM")), 403, "error.lifecycle.confirmerRoleDenied",
                DualConfirmationDenyReason.CONFIRMER_ROLE_DENIED);

        principalIs(CONFIRMER, "HR");
        when(reader.readConfirm(eq(TENANT), eq(TASK), eq(SUBMISSION), anyLong(), any()))
                .thenReturn(Optional.of(confirmDependencies(CONFIRMER)));
        org.mockito.Mockito.reset(recorder);
        assertDenied(() -> decide(decisionBody("CONFIRM")), 403, "error.lifecycle.selfConfirmationDenied",
                DualConfirmationDenyReason.SELF_CONFIRMATION);
        verify(attempts, never()).confirm(any());
    }

    @Test
    void 確認のdiscoveryで不適格な文書はstorageを読まずattemptへ判定を任せる() {
        when(eligibility.evaluate(any())).thenReturn(new DocumentEvidenceEligibility.Result(
                Verdict.DOCUMENT_UNAVAILABLE, null));
        when(attempts.confirm(any())).thenThrow(DualConfirmationRejection.of(409,
                "error.lifecycle.authorizationChanged", DualConfirmationDenyReason.AUTHORIZATION_CHANGED_DOCUMENT));

        assertDenied(() -> decide(decisionBody("CONFIRM")), 409, "error.lifecycle.authorizationChanged",
                DualConfirmationDenyReason.AUTHORIZATION_CHANGED_DOCUMENT);

        ArgumentCaptor<ConfirmAttempt> captor = ArgumentCaptor.forClass(ConfirmAttempt.class);
        verify(attempts).confirm(captor.capture());
        assertThat(captor.getValue().verifiedVersion()).isNull();
        assertEquals(EVIDENCE_SHA, captor.getValue().binding().evidenceSha256());
        verify(verifier, never()).verify(any());
    }

    @Test
    void 照会は現在の世代を返し確認可否は社内の入口のHRで提出者以外の時だけtrue() {
        LifecycleDualProjection projection = LifecycleDualProjection.builder().tenantId(TENANT).taskId(TASK)
                .caseId(CASE).taskGeneration(GENERATION).revision(1).submissionId(SUBMISSION)
                .evidenceSha256(EVIDENCE_SHA.toUpperCase()).state("PENDING").projectionVersion(0L).build();
        when(projectionMapper.selectOne(any())).thenReturn(projection);
        when(reader.findSubmission(TENANT, TASK, SUBMISSION)).thenReturn(submission(SUBMITTER));
        when(evaluator.canViewCase(any(), any(), any())).thenReturn(true);
        when(evaluator.isTaskVisible(any(), any())).thenReturn(true);

        DualConfirmationViewDto hrView = coordinator.view(TASK, principal(CONFIRMER), DualConfirmationChannel.INTERNAL);
        assertEquals(GENERATION, hrView.getTaskGeneration());
        assertThat(hrView.isStale()).isFalse();
        assertThat(hrView.isCanDecide()).isTrue();
        assertEquals(EVIDENCE_SHA, hrView.getEvidenceSha256(), "hashは小文字hexで返す");

        DualConfirmationViewDto ownView = coordinator.view(TASK, principal(SUBMITTER), DualConfirmationChannel.INTERNAL);
        assertThat(ownView.isCanDecide()).isFalse();

        projection.setTaskGeneration(GENERATION - 1);
        assertThat(coordinator.view(TASK, principal(CONFIRMER), DualConfirmationChannel.INTERNAL).isStale()).isTrue();
        verifyNoInteractions(attempts, recorder);
    }

    /**
     * RB-POSTLAUNCH D-2: 提出者・確認者は「ユーザーID N」ではなく名前で返す。社内の入口では照会者のtenantの利用者の氏名
     * （無ければusername）を返し、本人の入口では照会者自身の名前だけを返す（社内の確認者の名前は本人画面へ広げない）。
     */
    @Test
    void 照会は社内の入口で提出者と確認者の名前を返し本人の入口では本人の名前だけを返す() {
        long engineerUser = 33L;
        principalIs(engineerUser, "要員");
        LifecycleDualProjection projection = LifecycleDualProjection.builder().tenantId(TENANT).taskId(TASK)
                .caseId(CASE).taskGeneration(GENERATION).revision(1).submissionId(SUBMISSION)
                .evidenceSha256(EVIDENCE_SHA).state("CONFIRMED").projectionVersion(0L).build();
        when(projectionMapper.selectOne(any())).thenReturn(projection);
        when(reader.findSubmission(TENANT, TASK, SUBMISSION)).thenReturn(submission(engineerUser));
        when(confirmationMapper.selectOne(any())).thenReturn(LifecycleDualConfirmation.builder().tenantId(TENANT)
                .submissionId(SUBMISSION).taskId(TASK).decision("CONFIRMED").confirmerUserId(CONFIRMER).build());
        when(evaluator.canViewCase(any(), any(), any())).thenReturn(true);
        when(evaluator.isTaskVisible(any(), any())).thenReturn(true);
        when(sysUserMapper.selectByIdAndTenant(engineerUser, TENANT)).thenReturn(named(engineerUser, "検証 二号", "eng1"));
        when(sysUserMapper.selectByIdAndTenant(CONFIRMER, TENANT)).thenReturn(named(CONFIRMER, " ", "hr1"));

        DualConfirmationViewDto internal = coordinator.view(TASK, principal(CONFIRMER), DualConfirmationChannel.INTERNAL);
        assertEquals("検証 二号", internal.getSubmitterName());
        assertEquals("hr1", internal.getConfirmerName(), "氏名が空ならusername");
        assertEquals(CONFIRMER, internal.getConfirmerUserId());

        DualConfirmationViewDto self = coordinator.view(TASK, principal(engineerUser), DualConfirmationChannel.SELF);
        assertEquals("検証 二号", self.getSubmitterName());
        assertNull(self.getConfirmerName(), "本人画面には社内の確認者の名前を返さない");

        // tenant内に見つからない利用者（削除済み・別tenant）は名前を返さない
        when(sysUserMapper.selectByIdAndTenant(CONFIRMER, TENANT)).thenReturn(null);
        assertNull(coordinator.view(TASK, principal(CONFIRMER), DualConfirmationChannel.INTERNAL).getConfirmerName());
        verify(sysUserMapper, never()).selectByIdAndTenant(anyLong(), eq("tenant-b"));
    }

    private static SysUser named(long id, String realName, String username) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(TENANT);
        user.setRealName(realName);
        user.setUsername(username);
        user.setStatus(1);
        return user;
    }

    @Test
    void 再試行の分類は決定的な結果と未知の例外を数えない() {
        assertThat(LifecycleDualConfirmationCoordinator.RetryCause.classify(
                BusinessException.of(409, "error.lifecycle.submissionStale"))).isNull();
        assertThat(LifecycleDualConfirmationCoordinator.RetryCause.classify(DualConfirmationRejection.of(409,
                "error.lifecycle.idempotencyConflict", DualConfirmationDenyReason.IDEMPOTENCY_CONFLICT))).isNull();
        assertThat(LifecycleDualConfirmationCoordinator.RetryCause.classify(new IllegalStateException())).isNull();
        assertThat(LifecycleDualConfirmationCoordinator.RetryCause.classify(
                new RuntimeException(new SQLException("Lock wait timeout", "HY000", 1205))))
                .isEqualTo(LifecycleDualConfirmationCoordinator.RetryCause.LOCK_WAIT_TIMEOUT);
        assertThat(LifecycleDualConfirmationCoordinator.RetryCause.classify(
                new RuntimeException(BusinessException.of(409, "error.lifecycle.submissionStale")))).isNull();
    }

    // ===================================================================== helpers

    private void assertAllEntriesDisabled() {
        List<Executable> entries = List.of(
                () -> coordinator.view(TASK, principal(SUBMITTER), DualConfirmationChannel.INTERNAL),
                () -> coordinator.view(TASK, principal(SUBMITTER), DualConfirmationChannel.SELF),
                () -> coordinator.candidates(TASK, principal(SUBMITTER), DualConfirmationChannel.INTERNAL),
                () -> coordinator.candidates(TASK, principal(SUBMITTER), DualConfirmationChannel.SELF),
                () -> submit(DualConfirmationChannel.INTERNAL, submitBody()),
                () -> submit(DualConfirmationChannel.SELF, submitBody()),
                () -> decide(decisionBody("CONFIRM")));
        for (Executable entry : entries) {
            assertBusiness(assertThrows(BusinessException.class, entry), 400,
                    "error.lifecycle.dualConfirmationDisabled");
        }
    }

    private void assertDenied(Executable call, int status, String key, DualConfirmationDenyReason reason) {
        assertBusiness(assertThrows(BusinessException.class, call), status, key);
        DenyRecord record = recorded();
        assertEquals(reason, record.reason(), key);
        assertEquals(status, record.httpStatus());
    }

    private DenyRecord recorded() {
        ArgumentCaptor<DenyRecord> captor = ArgumentCaptor.forClass(DenyRecord.class);
        verify(recorder, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        return captor.getValue();
    }

    private static void assertBusiness(BusinessException exception, int status, String key) {
        assertEquals(status, exception.getCode(), key);
        assertEquals(key, exception.getMessageKey());
    }

    private DualConfirmationResultDto submit(DualConfirmationChannel channel, DualConfirmationSubmitRequest body) {
        return coordinator.submit(TASK, principal(SUBMITTER), channel, body, KEY);
    }

    private DualConfirmationResultDto decide(DualConfirmationDecisionRequest body) {
        return coordinator.decide(TASK, SUBMISSION, principal(CONFIRMER), body, KEY);
    }

    private static LifecycleDualConfirmationCoordinator.Principal principal(long userId) {
        return new LifecycleDualConfirmationCoordinator.Principal(userId, TENANT);
    }

    private void principalIs(long userId, String role) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setTenantId(TENANT);
        user.setRole(role);
        user.setStatus(1);
        lenient().when(sysUserMapper.selectById(userId)).thenReturn(user);
        lenient().when(evaluator.resolveActor(eq(TENANT), eq(userId), any(LocalDate.class)))
                .thenReturn(Optional.of(new ExplicitActor(TENANT, userId, role, LocalDate.now())));
    }

    private DualConfirmationSubmitRequest submitBodyAs(long userId, String role) {
        principalIs(userId, role);
        return submitBody();
    }

    private static DualConfirmationSubmitRequest submitBody() {
        DualConfirmationSubmitRequest body = new DualConfirmationSubmitRequest();
        body.setTaskGeneration(GENERATION);
        body.setDocumentSelections(List.of(selection(DOCUMENT, VERSION)));
        body.setComment("確認をお願いします");
        return body;
    }

    private static DualConfirmationDocumentSelectionRequest selection(Long documentId, Long versionId) {
        DualConfirmationDocumentSelectionRequest selection = new DualConfirmationDocumentSelectionRequest();
        selection.setDocumentId(documentId);
        selection.setDocumentVersionId(versionId);
        return selection;
    }

    private static DualConfirmationDecisionRequest decisionBody(String decision) {
        DualConfirmationDecisionRequest body = new DualConfirmationDecisionRequest();
        body.setSubmissionRevision(1L);
        body.setEvidenceSha256(EVIDENCE_SHA.toUpperCase());
        body.setDecision(decision);
        body.setComment("内容を確認しました");
        return body;
    }

    private static DualConfirmationDecisionRequest withSha(DualConfirmationDecisionRequest body, String sha) {
        body.setEvidenceSha256(sha);
        return body;
    }

    private static DualConfirmationDecisionRequest withRevision(DualConfirmationDecisionRequest body, Long revision) {
        body.setSubmissionRevision(revision);
        return body;
    }

    private ConfirmDependencies confirmDependencies(long submitterUserId) {
        LifecycleDualSubmissionDocument document = LifecycleDualSubmissionDocument.builder().tenantId(TENANT)
                .submissionId(SUBMISSION).documentId(DOCUMENT).documentVersionId(VERSION).versionNo(1)
                .sha256("a".repeat(64)).evidenceEligibilityEpoch(0L).build();
        return new ConfirmDependencies(TENANT, context, submission(submitterUserId), document, CONFIRMER,
                new ExplicitActor(TENANT, submitterUserId, "営業", LocalDate.now()), new TreeSet<>(), new TreeSet<>(),
                Map.of());
    }

    private static LifecycleDualSubmission submission(long submitterUserId) {
        return LifecycleDualSubmission.builder().id(SUBMISSION).tenantId(TENANT).caseId(CASE).taskId(TASK)
                .engineerId(ENGINEER).taskGeneration(GENERATION).revision(1).submitterUserId(submitterUserId)
                .submitterRoleSnapshot("営業").globalEpoch(0L).submitterActorEpoch(0L).engineerScopeEpoch(0L)
                .taskVersionSnapshot(0).evidenceSha256(EVIDENCE_SHA).requestSha256("c".repeat(64)).build();
    }

    private static LifecycleTask task(String evidenceType) {
        LifecycleTask task = LifecycleTask.builder().caseId(CASE).taskCode("RESIGN_DUAL").taskName("二者確認")
                .evidenceType(evidenceType).status("IN_PROGRESS").assigneeRole("営業").isEngineerVisible(1).build();
        task.setId(TASK);
        return task;
    }

    private static LifecycleCase lifecycleCase() {
        LifecycleCase lcCase = LifecycleCase.builder().tenantId(TENANT).caseNo("LC-1").engineerId(ENGINEER)
                .status("ACTIVE").build();
        lcCase.setId(CASE);
        return lcCase;
    }

    private static Engineer engineer() {
        Engineer engineer = Engineer.builder().tenantId(TENANT).build();
        engineer.setId(ENGINEER);
        return engineer;
    }
}
