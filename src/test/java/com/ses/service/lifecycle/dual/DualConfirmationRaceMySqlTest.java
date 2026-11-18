package com.ses.service.lifecycle.dual;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.dto.lifecycle.dual.DualConfirmationResultDto;
import com.ses.entity.SysUser;
import com.ses.mapper.AuthorizationEpochMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleTaskDepMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.document.DocumentEvidenceStorageVerifier;
import com.ses.service.security.LegalEntityContextService;
import com.ses.service.lifecycle.LifecycleCaseService;
import com.ses.service.lifecycle.LifecycleNotificationService;
import com.ses.service.lifecycle.LifecycleTaskService;
import com.ses.service.lifecycle.dual.DualConfirmationMySqlFixtures.Fixture;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.ses.service.lifecycle.dual.DualConfirmationMySqlFixtures.capture;
import static com.ses.service.lifecycle.dual.DualConfirmationMySqlFixtures.decisionBody;
import static com.ses.service.lifecycle.dual.DualConfirmationMySqlFixtures.principal;
import static com.ses.service.lifecycle.dual.DualConfirmationMySqlFixtures.submitBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * M8（NF01細粒度epoch計画 §6.1・§7.8 Demo (2)〜(5)(7)、W3-21a）: 実MySQL 8で二者確認の競合・冪等性・失効を確かめる。
 *
 * <ul>
 *   <li>担当営業の提出をHRが確認するとタスクがCOMPLETEDになり後続が昇格する（Demo (2)）。</li>
 *   <li>2人の確認者の同時確認は1件だけ確定し、他方は409 {@code submissionStale}。</li>
 *   <li>確認成功後の同key・同内容の再送は保存済みの結果、内容を変えた同keyは409 {@code idempotencyConflict}、提出者が
 *       認可を失った後の再送は409 {@code authorizationChanged}（Demo (3)）。</li>
 *   <li>提出後に担当営業を解除すると確認は409、別の要員の担当変更では確認できる（細粒度。Demo (5)）。</li>
 *   <li>保留→再開・担当変更の後の旧提出は409 {@code submissionStale}（Demo (7)）。</li>
 *   <li>提出×失効・確認×失効を、global・actor・engineer・組織・文書の各anchorについて両順序で確かめる。writerは
 *       anchor行を {@code FOR UPDATE} してepochを進める最小の形で模す（業務writerがanchorをXで取って増分することは
 *       M2〜M7が固定済み）。待ちの判定は {@link InnoDbRecordLockWaits} のrequester/blocker対で行い、{@code LOCK_DATA}を
 *       連結条件にしない。</li>
 *   <li>実際の業務writer（{@code EngineerSalesService.release}・{@code DocumentService.placeLegalHold}）も確認と両順序で
 *       競合させ、他のlockと混ざっても循環待ちにならず、writer先行は409・reader先行は確認成功になる（W3-21a review P3-11）。</li>
 * </ul>
 *
 * <p>B0の不変表はtriggerが削除を拒否するため後始末で行を消さない。専用のMySQL containerを使い、試験ごとに新しい
 * 利用者・要員・案件・タスク・文書で分離し、B0の行と拒否eventはタスクIDで絞って数える。tenantは配備のtenant（{@code default}）に
 * 固定する（明示actorのaction判定が配備のtenant以外を拒否するため。{@link DualConfirmationMySqlFixtures}）。
 * lock取得の前後のhookは {@link HookedLifecycleLockExecutor}（Mockitoのspyを使わない）。storageの内容検証は本試験の対象外の
 * ためmockで一致とする。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
@Import(HookedLifecycleLockExecutor.Configuration.class)
class DualConfirmationRaceMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf01_dual_race")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
        // 組織fence（マネージャーの有効主所属）を試すため組織scopeを有効にする
        registry.add("organization.scope.enabled", () -> "true");
        registry.add("app.features.nf01.enabled", () -> "true");
        registry.add("app.features.nf01-dual-confirmation.enabled", () -> "true");
    }

    private static final long WAIT_SECONDS = 20;
    private static final Set<String> SHARED = Set.of("S", "S,REC_NOT_GAP");

    /** 失効を起こすanchor（writerはanchor行をXで取ってepochを1進める）。 */
    enum Anchor {
        GLOBAL("t_authorization_global_epoch", DualConfirmationDenyReason.AUTHORIZATION_CHANGED_GLOBAL),
        ACTOR("sys_user", DualConfirmationDenyReason.AUTHORIZATION_CHANGED_ACTOR),
        ENGINEER("t_engineer", DualConfirmationDenyReason.AUTHORIZATION_CHANGED_ENGINEER),
        ORGANIZATION("m_organization_unit", DualConfirmationDenyReason.AUTHORIZATION_CHANGED_ORGANIZATION),
        DOCUMENT("t_document", DualConfirmationDenyReason.AUTHORIZATION_CHANGED_DOCUMENT);

        private final String table;
        private final DualConfirmationDenyReason reason;

        Anchor(String table, DualConfirmationDenyReason reason) {
            this.table = table;
            this.reason = reason;
        }

        /** 提出者はマネージャー（組織fenceを持つ）。 */
        long rowId(Fixture f) {
            return switch (this) {
                case GLOBAL -> 1L;
                case ACTOR -> f.manager();
                case ENGINEER -> f.engineer();
                case ORGANIZATION -> f.organization();
                case DOCUMENT -> f.documentId();
            };
        }

        void lockAndIncrement(AuthorizationEpochMapper mapper, Fixture f) {
            int updated = switch (this) {
                case GLOBAL -> {
                    mapper.selectGlobalForUpdate();
                    yield mapper.incrementGlobal();
                }
                case ACTOR -> {
                    mapper.selectActorForUpdate(f.manager(), f.tenant());
                    yield mapper.incrementActor(f.manager(), f.tenant());
                }
                case ENGINEER -> {
                    mapper.selectEngineerForUpdate(f.engineer(), f.tenant());
                    yield mapper.incrementEngineer(f.engineer(), f.tenant());
                }
                case ORGANIZATION -> {
                    mapper.selectOrganizationForUpdate(f.organization());
                    yield mapper.incrementOrganization(f.organization());
                }
                case DOCUMENT -> {
                    mapper.selectDocumentForUpdate(f.documentId(), f.tenant());
                    yield mapper.incrementDocument(f.documentId(), f.tenant());
                }
            };
            assertEquals(1, updated, name() + "のepochを進められない");
        }

        String currentEpochSql() {
            return switch (this) {
                case GLOBAL -> "SELECT epoch FROM t_authorization_global_epoch WHERE singleton_id = 1";
                case ACTOR -> "SELECT actor_authz_epoch FROM sys_user WHERE id = ?";
                case ENGINEER -> "SELECT engineer_scope_epoch FROM t_engineer WHERE id = ?";
                case ORGANIZATION -> "SELECT org_scope_epoch FROM m_organization_unit WHERE id = ?";
                case DOCUMENT -> "SELECT evidence_eligibility_epoch FROM t_document WHERE id = ?";
            };
        }

        String snapshotSql() {
            return switch (this) {
                case GLOBAL -> "SELECT global_epoch FROM t_lifecycle_dual_submission WHERE id = ?";
                case ACTOR -> "SELECT submitter_actor_epoch FROM t_lifecycle_dual_submission WHERE id = ?";
                case ENGINEER -> "SELECT engineer_scope_epoch FROM t_lifecycle_dual_submission WHERE id = ?";
                case ORGANIZATION -> "SELECT org_scope_epoch FROM t_lifecycle_dual_submission_org_fence "
                        + "WHERE submission_id = ?";
                case DOCUMENT -> "SELECT evidence_eligibility_epoch FROM t_lifecycle_dual_submission_document "
                        + "WHERE submission_id = ?";
            };
        }
    }

    /** 試行のtimeout（10秒）より短い一時停止の上限（P3-10）。超えたら試行を進め、試験を失敗にする。 */
    private static final long PAUSE_LIMIT_SECONDS = LifecycleDualConfirmationAttemptService.ATTEMPT_TIMEOUT_SECONDS - 2;
    /** 待ちの検出の上限。試行が待っている間に試行のtimeoutへ達しないよう、一時停止の上限より短くする。 */
    private static final long DETECT_MILLIS = TimeUnit.SECONDS.toMillis(6);

    /**
     * 試行のlock取得の前後に差し込むhook（{@link HookedLifecycleLockExecutor}）。最初の試行の接続IDを記録し、reader先行では
     * lock取得後に一時停止する。
     */
    private final class Pause implements HookedLifecycleLockExecutor.Hook {
        private final boolean pauseAfterAcquire;
        private final AtomicLong connection = new AtomicLong();
        private final CountDownLatch acquired = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean pausedTooLong = new AtomicBoolean();

        private Pause(boolean pauseAfterAcquire) {
            this.pauseAfterAcquire = pauseAfterAcquire;
        }

        @Override
        public void before(int attempt) {
            // 試行のtransactionに束縛された接続で読む（attempt自身の接続ID）
            connection.compareAndSet(0, connectionId());
        }

        @Override
        public void after(int attempt) throws InterruptedException {
            if (pauseAfterAcquire && acquired.getCount() > 0) {
                acquired.countDown();
                if (!release.await(PAUSE_LIMIT_SECONDS, TimeUnit.SECONDS)) {
                    pausedTooLong.set(true);
                }
            }
        }

        Long connectionOrNull() {
            long id = connection.get();
            return id == 0 ? null : id;
        }
    }

    private static final AtomicReference<String> CURRENT_TENANT =
            new AtomicReference<>(DualConfirmationMySqlFixtures.TENANT);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private LifecycleDualConfirmationCoordinator coordinator;
    @Autowired private AuthorizationEpochMapper epochMapper;
    @Autowired private LifecycleCaseService caseService;
    @Autowired private LifecycleTaskService taskService;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private EngineerSalesMapper engineerSalesMapper;
    @Autowired private UserOrganizationMapper userOrganizationMapper;
    @Autowired private LifecycleTemplateMapper templateMapper;
    @Autowired private LifecycleCaseMapper caseMapper;
    @Autowired private LifecycleTaskMapper taskMapper;
    @Autowired private LifecycleTaskDepMapper taskDepMapper;
    @Autowired private HookedLifecycleLockExecutor lockExecutor;
    @Autowired private com.ses.service.EngineerSalesService engineerSalesService;
    @Autowired private com.ses.service.DocumentService documentService;
    @MockBean private DocumentEvidenceStorageVerifier storageVerifier;
    @MockBean private LifecycleNotificationService notificationService;
    @MockBean private LegalEntityContextService legalEntityContextService;

    private DualConfirmationMySqlFixtures fixtures;
    private Pause pause;

    @BeforeEach
    void setUp() {
        fixtures = new DualConfirmationMySqlFixtures(jdbc, sysUserMapper, engineerMapper, engineerSalesMapper,
                userOrganizationMapper, templateMapper, caseMapper, taskMapper, taskDepMapper);
        reset(storageVerifier, legalEntityContextService);
        when(storageVerifier.verify(any())).thenReturn(DocumentEvidenceStorageVerifier.Outcome.MATCH);
        lenient().when(legalEntityContextService.requireCurrentDate()).thenReturn(LocalDate.now());
        lenient().when(legalEntityContextService.requireCurrentLegalEntityId())
                .thenReturn(DualConfirmationMySqlFixtures.LEGAL_ENTITY_ID);
        lenient().when(legalEntityContextService.requireTenantId()).thenAnswer(invocation -> CURRENT_TENANT.get());
        pause = null;
        lockExecutor.reset(null);
    }

    @AfterEach
    void tearDown() {
        if (pause != null) {
            pause.release.countDown();
        }
        lockExecutor.reset(null);
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    // ===================================================================== 業務の流れ

    @Test
    void 担当営業の提出をHRが確認するとタスクが完了し後続が昇格する() {
        Fixture f = fixtures.create("w321a-m8-flow");
        DualConfirmationResultDto submitted = submit(f, f.sales(), "key-submit-1");
        assertEquals("PENDING", submitted.getState());
        assertFalse(submitted.isReplayed());

        DualConfirmationResultDto confirmed = decide(f, f.hr1(), submitted, "CONFIRM", "key-confirm-1", "確認しました");

        assertEquals("CONFIRMED", confirmed.getState());
        assertEquals("COMPLETED", confirmed.getTaskStatus());
        assertEquals("COMPLETED", fixtures.taskStatus(f.taskId()));
        assertEquals("IN_PROGRESS", fixtures.taskStatus(f.successorId()), "後続を同じtransactionで昇格する");
        assertEquals(f.hr1(), fixtures.value("SELECT completed_by FROM t_lifecycle_task WHERE id = ?", f.taskId()));
        assertEquals(1, fixtures.count("SELECT COUNT(*) FROM t_lifecycle_evidence_link WHERE task_id = ? "
                + "AND document_id = ? AND document_version_id = ?", f.taskId(), f.documentId(), f.versionId()));
        String evidence = jdbc.queryForObject("SELECT evidence_data_json FROM t_lifecycle_task WHERE id = ?",
                String.class, f.taskId());
        assertThat(evidence).contains("\"submissionId\":" + submitted.getSubmissionId())
                .contains("\"confirmationId\":" + confirmed.getConfirmationId())
                .doesNotContain("確認しました");
        assertEquals(List.of(), fixtures.deniedReasons(f));
    }

    @Test
    void 確認後の同key同内容の再送は保存済みの結果で内容を変えると409で提出者の失効後は409() {
        Fixture f = fixtures.create("w321a-m8-replay");
        DualConfirmationResultDto submitted = submit(f, f.sales(), "key-submit-1");
        DualConfirmationResultDto confirmed = decide(f, f.hr1(), submitted, "CONFIRM", "key-confirm-1", "確認しました");

        DualConfirmationResultDto replayed = decide(f, f.hr1(), submitted, "CONFIRM", "key-confirm-1", "確認しました");
        assertTrue(replayed.isReplayed());
        assertEquals(confirmed.getConfirmationId(), replayed.getConfirmationId());
        assertEquals("COMPLETED", replayed.getTaskStatus());
        DualConfirmationResultDto replayedSubmit = submitAgain(f, f.sales(), "key-submit-1", 0L);
        assertTrue(replayedSubmit.isReplayed());
        assertEquals(submitted.getSubmissionId(), replayedSubmit.getSubmissionId());

        assertBusiness(capture(() -> decide(f, f.hr1(), submitted, "CONFIRM", "key-confirm-1", "内容を変えた")), 409,
                "error.lifecycle.idempotencyConflict");
        assertBusiness(capture(() -> decide(f, f.hr1(), submitted, "REJECT", "key-confirm-1", "確認しました")), 409,
                "error.lifecycle.idempotencyConflict");

        releaseSales(f, f.engineer(), f.salesAssignment());
        assertBusiness(capture(() -> decide(f, f.hr1(), submitted, "CONFIRM", "key-confirm-1", "確認しました")), 409,
                "error.lifecycle.authorizationChanged");
        assertEquals(1, fixtures.confirmations(f));
        assertThat(fixtures.deniedReasons(f)).contains("IDEMPOTENCY_CONFLICT")
                .anyMatch(reason -> reason.startsWith("AUTHORIZATION_CHANGED_"));
    }

    @Test
    void 二人の確認者が同時に確認すると1件だけ確定し他方は409() throws Exception {
        Fixture f = fixtures.create("w321a-m8-two");
        DualConfirmationResultDto submitted = submit(f, f.sales(), "key-submit-1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<Throwable> first = pool.submit(() -> {
                start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                return capture(() -> decide(f, f.hr1(), submitted, "CONFIRM", "key-hr1", "確認"));
            });
            Future<Throwable> second = pool.submit(() -> {
                start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                return capture(() -> decide(f, f.hr2(), submitted, "CONFIRM", "key-hr2", "確認"));
            });
            // 成功側はnullのため、nullを許すlistで受ける
            List<Throwable> outcomes = java.util.Arrays.asList(first.get(60, TimeUnit.SECONDS),
                    second.get(60, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(outcome -> outcome == null).count(), outcomes.toString());
            Throwable loser = outcomes.stream().filter(outcome -> outcome != null).findFirst().orElseThrow();
            assertBusiness(loser, 409, "error.lifecycle.submissionStale");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, fixtures.confirmations(f));
        assertEquals("COMPLETED", fixtures.taskStatus(f.taskId()));
        assertEquals(List.of("SUBMISSION_STALE"), fixtures.deniedReasons(f));
    }

    @Test
    void 提出後に担当営業を解除すると確認は409で別の要員の担当変更では確認できる() {
        Fixture other = fixtures.create("w321a-m8-scope");
        DualConfirmationResultDto otherSubmitted = submit(other, other.sales(), "key-submit-1");
        long[] unrelated = fixtures.otherEngineerWithSales(other);
        releaseSales(other, unrelated[0], unrelated[1]);
        DualConfirmationResultDto confirmed = decide(other, other.hr1(), otherSubmitted, "CONFIRM", "key-confirm-1",
                "確認");
        assertEquals("CONFIRMED", confirmed.getState(), "別の要員の担当変更は当該要員のepochを変えない");

        Fixture f = fixtures.create("w321a-m8-release");
        DualConfirmationResultDto submitted = submit(f, f.sales(), "key-submit-1");
        releaseSales(f, f.engineer(), f.salesAssignment());

        assertBusiness(capture(() -> decide(f, f.hr1(), submitted, "CONFIRM", "key-confirm-1", "確認")), 409,
                "error.lifecycle.authorizationChanged");
        assertEquals("IN_PROGRESS", fixtures.taskStatus(f.taskId()));
        List<String> reasons = fixtures.deniedReasons(f);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).startsWith("AUTHORIZATION_CHANGED_");
    }

    @Test
    void 保留と再開の後の旧提出は409で新しい提出が必要になる() {
        Fixture f = fixtures.create("w321a-m8-hold");
        DualConfirmationResultDto submitted = submit(f, f.sales(), "key-submit-1");
        long before = fixtures.taskGeneration(f);
        bindOperator(f);
        caseService.holdCase(f.caseId(), f.admin(), "W3-21a検証");
        caseService.resumeCase(f.caseId(), f.admin());
        SecurityContextHolder.clearContext();
        assertThat(fixtures.taskGeneration(f)).isGreaterThan(before);

        assertBusiness(capture(() -> decide(f, f.hr1(), submitted, "CONFIRM", "key-confirm-1", "確認")), 409,
                "error.lifecycle.submissionStale");
        assertEquals(List.of("SUBMISSION_STALE"), fixtures.deniedReasons(f));
    }

    @Test
    void 担当変更の後の旧提出は409() {
        Fixture f = fixtures.create("w321a-m8-reassign");
        DualConfirmationResultDto submitted = submit(f, f.sales(), "key-submit-1");
        long before = fixtures.taskGeneration(f);
        bindOperator(f);
        // 担当roleは「営業」のまま担当者を未設定→提出者本人にする。担当が変わるので世代は進み、提出者はタスクを扱えるまま
        // （担当roleが変わると提出者の再認可が先に409 authorizationChangedになり、世代の判定を確かめられない）。
        taskService.reassignTask(f.taskId(), f.sales(), f.admin(), "W3-21a検証");
        SecurityContextHolder.clearContext();
        assertEquals(before + 1, fixtures.taskGeneration(f));

        assertBusiness(capture(() -> decide(f, f.hr1(), submitted, "CONFIRM", "key-confirm-1", "確認")), 409,
                "error.lifecycle.submissionStale");
        assertEquals(List.of("SUBMISSION_STALE"), fixtures.deniedReasons(f));
        assertThat(fixtures.taskStatus(f.taskId())).isNotEqualTo("COMPLETED");
    }

    // ===================================================================== 確認×失効

    @ParameterizedTest
    @EnumSource(Anchor.class)
    void 確認と失効のwriter先行では確認がanchorのcommitを待ち409で拒否される(Anchor anchor) throws Exception {
        Fixture f = fixtures.create("w321a-m8-cw-" + anchor.name().toLowerCase());
        DualConfirmationResultDto submitted = submit(f, f.manager(), "key-submit-1");

        Throwable outcome = writerFirst(anchor, f,
                () -> capture(() -> decide(f, f.hr1(), submitted, "CONFIRM", "key-confirm-1", "確認")));

        assertBusiness(outcome, 409, "error.lifecycle.authorizationChanged");
        assertEquals(List.of(anchor.reason.name()), fixtures.deniedReasons(f));
        assertEquals("IN_PROGRESS", fixtures.taskStatus(f.taskId()));
    }

    @ParameterizedTest
    @EnumSource(Anchor.class)
    void 確認と失効のreader先行ではwriterが確認のcommitを待ち確認は成功する(Anchor anchor) throws Exception {
        Fixture f = fixtures.create("w321a-m8-cr-" + anchor.name().toLowerCase());
        DualConfirmationResultDto submitted = submit(f, f.manager(), "key-submit-1");
        long before = currentEpoch(anchor, f);

        Throwable outcome = readerFirst(anchor, f,
                () -> capture(() -> decide(f, f.hr1(), submitted, "CONFIRM", "key-confirm-1", "確認")));

        assertNull(outcome, String.valueOf(outcome));
        assertEquals("COMPLETED", fixtures.taskStatus(f.taskId()));
        assertEquals(before + 1, currentEpoch(anchor, f), "writerは確認のcommit後に1だけ進める");
    }

    // ===================================================================== 提出×失効

    @ParameterizedTest
    @EnumSource(Anchor.class)
    void 提出と失効のwriter先行では提出がcommitを待ち新しいepochを保存する(Anchor anchor) throws Exception {
        Fixture f = fixtures.create("w321a-m8-sw-" + anchor.name().toLowerCase());
        long before = currentEpoch(anchor, f);
        AtomicReference<DualConfirmationResultDto> result = new AtomicReference<>();

        Throwable outcome = writerFirst(anchor, f, () -> capture(() -> {
            result.set(submit(f, f.manager(), "key-submit-1"));
            return null;
        }));

        assertNull(outcome, String.valueOf(outcome));
        assertEquals(before + 1, snapshotEpoch(anchor, result.get().getSubmissionId()));
    }

    @ParameterizedTest
    @EnumSource(Anchor.class)
    void 提出と失効のreader先行ではwriterが提出のcommitを待ち提出は旧epochを保存する(Anchor anchor) throws Exception {
        Fixture f = fixtures.create("w321a-m8-sr-" + anchor.name().toLowerCase());
        long before = currentEpoch(anchor, f);
        AtomicReference<DualConfirmationResultDto> result = new AtomicReference<>();

        Throwable outcome = readerFirst(anchor, f, () -> capture(() -> {
            result.set(submit(f, f.manager(), "key-submit-1"));
            return null;
        }));

        assertNull(outcome, String.valueOf(outcome));
        assertEquals(before, snapshotEpoch(anchor, result.get().getSubmissionId()));
        assertEquals(before + 1, currentEpoch(anchor, f));
    }

    // ===================================================================== 確認×実際の業務writer（W3-21a review P3-11）

    /**
     * 最小形のwriter（anchor行のFOR UPDATE＋epoch増分）ではなく、実際の {@code EngineerSalesService.release}（7 X{要員} →
     * 10 の割当行 → engineer epoch）を確認と競合させる。writer先行では確認が要員行のcommitを待ち、提出者（担当営業）の
     * 述語とengineer epochの変化で409になる。
     */
    @Test
    void 確認と実際の担当営業解除のwriter先行では確認が要員行のcommitを待ち409で拒否される() throws Exception {
        Fixture f = fixtures.create("w321a-m8-rw-sales");
        DualConfirmationResultDto submitted = submit(f, f.sales(), "key-submit-1");

        Throwable outcome = writerFirst("writer先行 担当営業解除（実writer）", "t_engineer", f.engineer(), () -> {
            bindOperator(f);
            engineerSalesService.release(f.engineer(), f.salesAssignment());
        }, () -> capture(() -> decide(f, f.hr1(), submitted, "CONFIRM", "key-confirm-1", "確認")));

        assertBusiness(outcome, 409, "error.lifecycle.authorizationChanged");
        assertEquals(List.of(DualConfirmationDenyReason.AUTHORIZATION_CHANGED_ENGINEER.name()),
                fixtures.deniedReasons(f));
        assertEquals("IN_PROGRESS", fixtures.taskStatus(f.taskId()));
    }

    /** 実際の担当営業解除のreader先行: 解除は確認の共有lockを待ち、確認のcommit後に解除してengineer epochを1進める。 */
    @Test
    void 確認と実際の担当営業解除のreader先行では解除が確認のcommitを待ち確認は成功する() throws Exception {
        Fixture f = fixtures.create("w321a-m8-rr-sales");
        DualConfirmationResultDto submitted = submit(f, f.sales(), "key-submit-1");
        long before = currentEpoch(Anchor.ENGINEER, f);

        Throwable outcome = readerFirst("reader先行 担当営業解除（実writer）", "t_engineer", f.engineer(), () -> {
            bindOperator(f);
            engineerSalesService.release(f.engineer(), f.salesAssignment());
        }, () -> capture(() -> decide(f, f.hr1(), submitted, "CONFIRM", "key-confirm-1", "確認")));

        assertNull(outcome, String.valueOf(outcome));
        assertEquals("COMPLETED", fixtures.taskStatus(f.taskId()));
        assertEquals(before + 1, currentEpoch(Anchor.ENGINEER, f), "解除は確認のcommit後に1だけ進める");
    }

    /**
     * 実際の {@code DocumentService.placeLegalHold}（15 X{文書} → 法的holdのCAS → 文書epoch）を確認と競合させる。
     * writer先行では確認が文書行のcommitを待ち、文書epochの変化で409になる（holdした証跡で確定しない）。
     */
    @Test
    void 確認と実際の法的hold設定のwriter先行では確認が文書行のcommitを待ち409で拒否される() throws Exception {
        Fixture f = fixtures.create("w321a-m8-rw-hold");
        DualConfirmationResultDto submitted = submit(f, f.manager(), "key-submit-1");

        Throwable outcome = writerFirst("writer先行 法的hold（実writer）", "t_document", f.documentId(), () -> {
            bindOperator(f);
            documentService.placeLegalHold(f.documentId(), true, "W3-21a検証");
        }, () -> capture(() -> decide(f, f.hr1(), submitted, "CONFIRM", "key-confirm-1", "確認")));

        assertBusiness(outcome, 409, "error.lifecycle.authorizationChanged");
        assertEquals(List.of(DualConfirmationDenyReason.AUTHORIZATION_CHANGED_DOCUMENT.name()),
                fixtures.deniedReasons(f));
        assertEquals("IN_PROGRESS", fixtures.taskStatus(f.taskId()));
    }

    /** 実際の法的hold設定のreader先行: holdは確認の共有lockを待ち、確認のcommit後に設定して文書epochを1進める。 */
    @Test
    void 確認と実際の法的hold設定のreader先行ではholdが確認のcommitを待ち確認は成功する() throws Exception {
        Fixture f = fixtures.create("w321a-m8-rr-hold");
        DualConfirmationResultDto submitted = submit(f, f.manager(), "key-submit-1");
        long before = currentEpoch(Anchor.DOCUMENT, f);

        Throwable outcome = readerFirst("reader先行 法的hold（実writer）", "t_document", f.documentId(), () -> {
            bindOperator(f);
            documentService.placeLegalHold(f.documentId(), true, "W3-21a検証");
        }, () -> capture(() -> decide(f, f.hr1(), submitted, "CONFIRM", "key-confirm-1", "確認")));

        assertNull(outcome, String.valueOf(outcome));
        assertEquals("COMPLETED", fixtures.taskStatus(f.taskId()));
        assertEquals(before + 1, currentEpoch(Anchor.DOCUMENT, f), "holdは確認のcommit後に1だけ進める");
    }

    // ===================================================================== 両順序の部品

    /** writerがanchorをXで取りepochを進めてcommit前に止まっている間に、試行がそのanchorで待つことを確かめる。 */
    private Throwable writerFirst(Anchor anchor, Fixture f, java.util.concurrent.Callable<Throwable> operation)
            throws Exception {
        return writerFirst("writer先行 " + anchor, anchor.table, anchor.rowId(f),
                () -> anchor.lockAndIncrement(epochMapper, f), operation);
    }

    /**
     * writer（最小形または実際の業務writer）がanchor行をXで取りcommit前に止まっている間に、試行がその行で待つことを確かめる。
     * writerはanchorの表・主キーにちょうど1件のrecord lockを持つこと（{@code requireSinglePointLock}）を前提とする。
     */
    private Throwable writerFirst(String label, String table, long rowId, Runnable writerCall,
                                  java.util.concurrent.Callable<Throwable> operation) throws Exception {
        Pause hook = new Pause(false);
        pause = hook;
        lockExecutor.reset(hook);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicLong writerConnection = new AtomicLong();
        try {
            Future<Object> writer = pool.submit(() -> required().execute(status -> {
                writerConnection.set(connectionId());
                writerCall.run();
                locked.countDown();
                awaitQuietly(releaseWriter);
                return null;
            }));
            if (!locked.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                fail(label + ": writerがanchorをlockできなかった");
            }
            InnoDbRecordLockWaits.PointLock point = InnoDbRecordLockWaits.requireSinglePointLock(jdbc,
                    writerConnection.get(), table, "PRIMARY", InnoDbRecordLockWaits.primaryKey(rowId), label);
            Future<Throwable> attempt = pool.submit(operation);
            InnoDbRecordLockWaits.WaitEdge edge = InnoDbRecordLockWaits.awaitBlockerAnchoredWait(jdbc, point,
                    hook::connectionOrNull, SHARED, InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES,
                    DETECT_MILLIS, () -> {
                        if (attempt.isDone()) {
                            fail(label + ": 試行がwriterのcommitを待たずに終了した: " + attempt.get());
                        }
                    }, label);
            assertFalse(attempt.isDone(), label + ": " + edge.describe());
            releaseWriter.countDown();
            writer.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return attempt.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseWriter.countDown();
            pool.shutdownNow();
        }
    }

    /** 試行がlock列を取り終えて止まっている間に、writerがanchorの共有lockで待つことを確かめる。 */
    private Throwable readerFirst(Anchor anchor, Fixture f, java.util.concurrent.Callable<Throwable> operation)
            throws Exception {
        return readerFirst("reader先行 " + anchor, anchor.table, anchor.rowId(f),
                () -> anchor.lockAndIncrement(epochMapper, f), operation);
    }

    /** 試行がlock列を取り終えて止まっている間に、writer（最小形または実際の業務writer）がその行の共有lockで待つことを確かめる。 */
    private Throwable readerFirst(String label, String table, long rowId, Runnable writerCall,
                                  java.util.concurrent.Callable<Throwable> operation) throws Exception {
        Pause hook = new Pause(true);
        pause = hook;
        lockExecutor.reset(hook);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicLong writerConnection = new AtomicLong();
        try {
            Future<Throwable> attempt = pool.submit(operation);
            if (!hook.acquired.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                fail(label + ": 試行がlock列を取得できなかった: " + (attempt.isDone() ? attempt.get() : "running"));
            }
            Future<Object> writer = pool.submit(() -> required().execute(status -> {
                writerConnection.set(connectionId());
                writerCall.run();
                return null;
            }));
            InnoDbRecordLockWaits.WaitEdge edge = InnoDbRecordLockWaits.awaitRequesterAnchoredWait(jdbc,
                    () -> writerConnection.get() == 0 ? null : writerConnection.get(), hook::connectionOrNull,
                    table, "PRIMARY", InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES, SHARED,
                    InnoDbRecordLockWaits.primaryKey(rowId), DETECT_MILLIS, () -> {
                        if (writer.isDone()) {
                            fail(label + ": writerが試行の共有lockを待たずに終了した: " + outcome(writer));
                        }
                    }, label);
            assertFalse(writer.isDone(), label + ": " + edge.describe());
            hook.release.countDown();
            Throwable result = attempt.get(WAIT_SECONDS, TimeUnit.SECONDS);
            writer.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertFalse(hook.pausedTooLong.get(), label + ": 一時停止が" + PAUSE_LIMIT_SECONDS
                    + "秒を超えた（試行のtimeoutに近づくため、待ちの検出が遅すぎる）");
            assertEquals(1, lockExecutor.calls(), label + ": 一時停止した試行のまま成功すること（再試行なし）");
            return result;
        } finally {
            hook.release.countDown();
            pool.shutdownNow();
        }
    }

    // ===================================================================== helpers

    private DualConfirmationResultDto submit(Fixture f, long submitter, String key) {
        return submitAgain(f, submitter, key, fixtures.taskGeneration(f));
    }

    private DualConfirmationResultDto submitAgain(Fixture f, long submitter, String key, long generation) {
        return coordinator.submit(f.taskId(), principal(f, submitter), DualConfirmationChannel.INTERNAL,
                submitBody(generation, f, "提出します"), key);
    }

    private DualConfirmationResultDto decide(Fixture f, long confirmer, DualConfirmationResultDto submitted,
                                             String decision, String key, String comment) {
        return coordinator.decide(f.taskId(), submitted.getSubmissionId(), principal(f, confirmer),
                decisionBody(submitted, decision, comment), key);
    }

    /** 担当営業の解除をanchor（要員行）のX lockとepoch増分つきで行う（F2 writerの最小形）。 */
    private void releaseSales(Fixture f, long engineer, long assignment) {
        required().executeWithoutResult(status -> {
            epochMapper.selectEngineerForUpdate(engineer, f.tenant());
            jdbc.update("UPDATE t_engineer_sales SET released_at = ? WHERE id = ?", LocalDate.now().minusDays(1),
                    assignment);
            assertEquals(1, epochMapper.incrementEngineer(engineer, f.tenant()));
        });
    }

    private long currentEpoch(Anchor anchor, Fixture f) {
        return anchor == Anchor.GLOBAL ? fixtures.value(anchor.currentEpochSql())
                : fixtures.value(anchor.currentEpochSql(), anchor.rowId(f));
    }

    private long snapshotEpoch(Anchor anchor, long submissionId) {
        return fixtures.value(anchor.snapshotSql(), submissionId);
    }

    private void bindOperator(Fixture f) {
        CURRENT_TENANT.set(f.tenant());
        AccountingTenantContextHolder.setTenantId(f.tenant());
        SysUser admin = sysUserMapper.selectById(f.admin());
        LoginUser principal = new LoginUser(admin, List.of(new SimpleGrantedAuthority("ROLE_管理者")), f.tenant());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    private TransactionTemplate required() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private long connectionId() {
        Long id = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private static void assertBusiness(Throwable outcome, int status, String key) {
        assertThat(outcome).as("期待した拒否 %s", key).isInstanceOf(BusinessException.class);
        BusinessException business = (BusinessException) outcome;
        assertEquals(status, business.getCode(), key);
        assertEquals(key, business.getMessageKey());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String outcome(Future<?> future) {
        if (!future.isDone()) {
            return "running";
        }
        try {
            future.get();
            return "completed";
        } catch (Exception failure) {
            return failure.toString();
        }
    }

    @SuppressWarnings("unused")
    private static List<String> describe(List<InnoDbRecordLockWaits.WaitEdge> edges) {
        List<String> lines = new ArrayList<>();
        edges.forEach(edge -> lines.add(edge.describe()));
        return lines;
    }
}
