package com.ses.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.Engineer;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleDualSubmission;
import com.ses.entity.LifecycleTask;
import com.ses.entity.LifecycleTemplate;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleDualEventMapper;
import com.ses.mapper.LifecycleDualSubmissionMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.nf01.preflight.Nf01PreflightTestAccess;
import com.ses.service.lifecycle.evidence.LifecycleLockExecutor;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.LockKey;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.Mode;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.Rank;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import com.ses.service.security.epoch.AuthorizationEpochReader;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.InnoDbRecordLockWaits.HeldLock;
import com.ses.test.InnoDbRecordLockWaits.WaitEdge;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M1（NF01細粒度epoch計画 §6.1・§7.1 W3-10 Demo）: 実MySQL 8の空DBへV1からLATESTまで適用し、V189の
 * 全体epoch表・5 anchor列・CHECK・一意keyと、{@link AuthorizationEpochFence}・{@link AuthorizationEpochReader}・
 * {@link LifecycleLockExecutor} の実DBでの振る舞いを確かめる。
 *
 * <ul>
 *   <li>全体epoch行は (singleton_id=1, epoch=0) の1行だけ。全体epochを変える操作はすべてrollbackするtransactionで行い、
 *       試験の実行順（surefireはrandom）に関係なく初期値を観測できるようにする。</li>
 *   <li>増分は updated_at と version を変えない。上限・行欠落は409で値を変えない。</li>
 *   <li>増分後にentity全体を {@code updateById} してもepochは巻き戻らない（entityへ写像しないため。E-7）。</li>
 *   <li>readerの {@code FOR SHARE} 保持中はwriterの {@code FOR UPDATE} が待つ。待機の判定は
 *       {@code data_locks.LOCK_DATA} に依存せず、readerが主キー一点で取った共有record lockをwriterの開始前に1件だけに
 *       固定し（fixture行はcommit済みで暗黙lockは無い）、待機辺のblocking lock IDがその共有lockであることを要求する
 *       （writer側は主キー一点の {@code FOR UPDATE} なので、同時に待つlockは1件だけである）。</li>
 *   <li>W3-18（計画 §7.7 B0、V195）: 二者確認coordinatorの7表が空で作られ、一意key・複合主キー・14件のFK・CHECK・
 *       tenant列の照合順序が定義どおりであること。不変6表へのUPDATE・DELETEをtrigger（MySQL error 1644）と
 *       mapper（SQL発行前の例外）が拒否し、projectionだけがversionのCASで更新できること。他tenantの案件・利用者・文書版、
 *       別案件のtask、提出の束縛（revision・証跡hash・世代）違いを複合FK（error 1452）が拒否し、各CHECK（error 3819。
 *       冪等性keyの空文字・末尾空白・改行・許可外の文字を含む）が許可外の値を拒否すること（messageの制約名で特定する）。B0の行はすべて
 *       rollbackするtransactionで入れ、表を空のまま保つ（triggerが削除を拒否するため後始末はできない）。lock待ちは扱わない。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class AuthorizationEpochSchemaMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_nf01_authorization_epoch")
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
    }

    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final AtomicInteger TEMPLATE_VERSION = new AtomicInteger(810_000);
    private static final Timestamp FIXED_UPDATED_AT = Timestamp.valueOf("2020-01-02 03:04:05");
    private static final long MISSING_ID = 8_000_000_000_000_000L;
    private static final long WAIT_SECONDS = 30;
    private static final Set<String> SHARED_RECORD_MODES = Set.of("S", "S,REC_NOT_GAP");
    private static final Set<String> EXCLUSIVE_RECORD_MODES = Set.of("X", "X,REC_NOT_GAP");
    /** V195のB0 7表（projectionだけが可変）。 */
    private static final List<String> B0_TABLES = List.of("t_lifecycle_dual_submission",
            "t_lifecycle_dual_submission_document", "t_lifecycle_dual_submission_org_fence", "t_lifecycle_dual_confirmation",
            "t_lifecycle_dual_projection", "t_lifecycle_dual_idempotency", "t_lifecycle_dual_event");
    private static final List<String> B0_IMMUTABLE_TABLES = B0_TABLES.stream()
            .filter(table -> !"t_lifecycle_dual_projection".equals(table)).toList();
    private static final String EVIDENCE_SHA = "b".repeat(64);
    private static final String REQUEST_SHA = "c".repeat(64);
    private static final String SUBMISSION_INSERT = "INSERT INTO t_lifecycle_dual_submission (tenant_id, case_id, task_id, "
            + "engineer_id, task_generation, revision, submitter_user_id, submitter_role_snapshot, global_epoch, "
            + "submitter_actor_epoch, engineer_scope_epoch, task_version_snapshot, evidence_canonical_json, evidence_sha256, "
            + "request_sha256) VALUES (?, ?, ?, ?, 0, ?, ?, 'HR', 0, 0, 0, 0, '{}', ?, ?)";
    private static final String SUBMISSION_DOCUMENT_INSERT = "INSERT INTO t_lifecycle_dual_submission_document (tenant_id, "
            + "submission_id, document_id, document_version_id, version_no, sha256, evidence_eligibility_epoch) "
            + "VALUES (?, ?, ?, ?, 1, ?, 0)";
    private static final String ORG_FENCE_INSERT = "INSERT INTO t_lifecycle_dual_submission_org_fence (tenant_id, "
            + "submission_id, organization_id, org_scope_epoch) VALUES (?, ?, ?, 0)";
    private static final String CONFIRMATION_INSERT = "INSERT INTO t_lifecycle_dual_confirmation (tenant_id, case_id, task_id, "
            + "task_generation, submission_id, submission_revision, evidence_sha256, confirmer_user_id, "
            + "confirmer_role_snapshot, global_epoch, confirmer_actor_epoch, decision) "
            + "VALUES (?, ?, ?, 0, ?, ?, ?, ?, 'HR', 0, 0, 'CONFIRMED')";
    private static final String PROJECTION_INSERT = "INSERT INTO t_lifecycle_dual_projection (tenant_id, task_id, case_id, "
            + "task_generation, revision, submission_id, evidence_sha256, state) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')";
    private static final String IDEMPOTENCY_INSERT = "INSERT INTO t_lifecycle_dual_idempotency (tenant_id, case_id, task_id, "
            + "task_generation, operation, actor_user_id, idempotency_key, request_sha256, submission_id) "
            + "VALUES (?, ?, ?, 0, 'SUBMIT', ?, ?, ?, ?)";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AuthorizationEpochFence fence;
    @Autowired private AuthorizationEpochReader reader;
    @Autowired private LifecycleLockExecutor lockExecutor;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private OrganizationUnitMapper organizationUnitMapper;
    @Autowired private DocumentMapper documentMapper;
    @Autowired private LifecycleTemplateMapper templateMapper;
    @Autowired private LifecycleCaseMapper caseMapper;
    @Autowired private LifecycleTaskMapper taskMapper;
    @Autowired private LifecycleDualSubmissionMapper dualSubmissionMapper;
    @Autowired private LifecycleDualEventMapper dualEventMapper;

    // ===== schema =====

    @Test
    void 空DBからLATESTまで適用すると全体epochは1と0の1行だけで5列はNOT_NULL_DEFAULT_0と有効なCHECKを持つ() {
        assertEquals(LatestMigrationVersion.LATEST, jdbc.queryForObject("SELECT version FROM flyway_schema_history "
                + "WHERE version IS NOT NULL AND success = 1 ORDER BY installed_rank DESC LIMIT 1", String.class));
        assertEquals(1L, count("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '189' AND success = 1"));

        List<Map<String, Object>> global = jdbc.queryForList("SELECT singleton_id, epoch FROM t_authorization_global_epoch");
        assertEquals(1, global.size(), "全体epoch行は1行だけ: " + global);
        assertEquals(1L, ((Number) global.get(0).get("singleton_id")).longValue());
        assertEquals(0L, ((Number) global.get(0).get("epoch")).longValue());
        assertCheck("t_authorization_global_epoch", "ck_authz_global_singleton", "singleton_id");
        assertCheck("t_authorization_global_epoch", "ck_authz_global_epoch_nonneg", "epoch");

        for (String[] column : List.of(
                new String[]{"sys_user", "actor_authz_epoch", "ck_sys_user_actor_authz_epoch"},
                new String[]{"t_engineer", "engineer_scope_epoch", "ck_engineer_scope_epoch"},
                new String[]{"m_organization_unit", "org_scope_epoch", "ck_org_scope_epoch"},
                new String[]{"t_document", "evidence_eligibility_epoch", "ck_document_evidence_eligibility_epoch"},
                new String[]{"t_lifecycle_task", "task_generation", "ck_lifecycle_task_generation"})) {
            Map<String, Object> definition = jdbc.queryForMap("SELECT DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT "
                    + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    column[0], column[1]);
            assertEquals("bigint", String.valueOf(definition.get("DATA_TYPE")).toLowerCase(), column[1]);
            assertEquals("NO", definition.get("IS_NULLABLE"), column[1]);
            assertEquals("0", String.valueOf(definition.get("COLUMN_DEFAULT")), column[1]);
            assertCheck(column[0], column[2], column[1]);
            assertEquals(0L, count("SELECT COUNT(*) FROM " + column[0] + " WHERE " + column[1] + " < 0"));
        }
        // 既存行（V2のadmin、V60の移行組織）はDEFAULT 0でbackfillされる。本classの試験はこれらの行を進めない
        assertEquals(0L, count("SELECT actor_authz_epoch FROM sys_user WHERE username = 'admin'"));
        assertEquals(0L, count("SELECT org_scope_epoch FROM m_organization_unit WHERE code = 'LEGACY'"));

        assertUniqueKey("t_lifecycle_case", "uk_lifecycle_case_tenant_id", "tenant_id,id");
        assertUniqueKey("t_lifecycle_task", "uk_lifecycle_task_case_id", "case_id,id");
        assertUniqueKey("t_document_version", "uk_document_version_tenant_doc_id", "tenant_id,document_id,id");
        assertUniqueKey("sys_user", "uk_sys_user_tenant_id", "tenant_id,id");
        assertEquals(0L, count("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE() "
                + "AND EVENT_OBJECT_TABLE IN ('t_authorization_global_epoch', 'sys_user', 't_engineer', "
                + "'m_organization_unit', 't_document', 't_lifecycle_task')"), "V189はtriggerを追加しない");
    }

    @Test
    void CHECK制約は負値を拒否し全体epoch表は2行目と別のsingletonを拒否する() {
        Fixture f = fixture();
        for (AnchorRow row : anchors(f)) {
            DataAccessException rejected = assertThrows(DataAccessException.class,
                    () -> jdbc.update("UPDATE " + row.table() + " SET " + row.column() + " = -1 WHERE id = ?", row.id()));
            assertSqlErrorCode(rejected, 3819, row.column());
            assertEquals(0L, epoch(row), row.column() + " は変わらないこと");
        }
        assertSqlErrorCode(assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO t_authorization_global_epoch (singleton_id, epoch) VALUES (2, 0)")), 3819, "singleton");
        assertSqlErrorCode(assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE t_authorization_global_epoch SET epoch = -1 WHERE singleton_id = 1")), 3819, "global epoch");
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "INSERT INTO t_authorization_global_epoch (singleton_id, epoch) VALUES (1, 0)"));
        assertEquals(List.of(Map.of("singleton_id", 1, "epoch", 0L)), jdbc.query(
                "SELECT singleton_id, epoch FROM t_authorization_global_epoch",
                (rs, i) -> Map.<String, Object>of("singleton_id", rs.getInt(1), "epoch", rs.getLong(2))));
    }

    // ===== fence =====

    @Test
    void 増分は各anchorを1だけ進めupdated_atとversionを変えず同じtransactionの重複は1回になる() {
        Fixture f = fixture();
        List<AnchorRow> rows = anchors(f);
        Map<String, Long> versionsBefore = new TreeMap<>();
        for (AnchorRow row : rows) {
            jdbc.update("UPDATE " + row.table() + " SET updated_at = ? WHERE id = ?", FIXED_UPDATED_AT, row.id());
            if (row.versioned()) {
                versionsBefore.put(row.table(), version(row));
            }
        }

        inTransaction(() -> {
            lockCase(f);
            for (AnchorRow row : rows) {
                assertEquals(0L, fence.lockForUpdate(row.anchor()), row.column());
                assertTrue(fence.advance(row.anchor()), row.column());
                assertFalse(fence.advance(row.anchor()), "同じtransactionの2回目は増やさない: " + row.column());
            }
            return null;
        });

        for (AnchorRow row : rows) {
            assertEquals(1L, epoch(row), row.column());
            assertEquals(FIXED_UPDATED_AT, jdbc.queryForObject("SELECT updated_at FROM " + row.table() + " WHERE id = ?",
                    Timestamp.class, row.id()), "updated_atを変えないこと: " + row.table());
            if (row.versioned()) {
                assertEquals(versionsBefore.get(row.table()), version(row), "versionを変えないこと: " + row.table());
            }
        }

        // 全体epochはrollbackするtransactionの中だけで進める（初期値 (1,0) を他の試験から観測できるようにする）
        rollbackOnly(() -> {
            assertEquals(0L, fence.lockForUpdate(AuthorizationEpochAnchor.global()));
            assertTrue(fence.advance(AuthorizationEpochAnchor.global()));
            assertFalse(fence.advance(AuthorizationEpochAnchor.global()));
            assertEquals(1L, count("SELECT epoch FROM t_authorization_global_epoch WHERE singleton_id = 1"));
        });
        assertEquals(0L, count("SELECT epoch FROM t_authorization_global_epoch WHERE singleton_id = 1"));
    }

    @Test
    void 上限のanchorは409で業務更新ごと止まり値を変えず行欠落も409になる() {
        Fixture f = fixture();
        for (AnchorRow row : anchors(f)) {
            jdbc.update("UPDATE " + row.table() + " SET " + row.column() + " = ? WHERE id = ?", Long.MAX_VALUE, row.id());
            BusinessException failure = assertThrows(BusinessException.class, () -> inTransaction(() -> {
                lockCase(f);
                jdbc.update("UPDATE " + row.table() + " SET updated_at = ? WHERE id = ?", FIXED_UPDATED_AT, row.id());
                return fence.advance(row.anchor());
            }));
            assertEpochUnavailable(failure);
            assertEquals(Long.MAX_VALUE, epoch(row), "上限では値を変えないこと: " + row.column());
            assertFalse(FIXED_UPDATED_AT.equals(jdbc.queryForObject("SELECT updated_at FROM " + row.table()
                    + " WHERE id = ?", Timestamp.class, row.id())), "同じtransactionの業務更新もrollbackされること: " + row.table());
        }

        assertEpochUnavailable(assertThrows(BusinessException.class, () -> inTransaction(
                () -> fence.advance(AuthorizationEpochAnchor.engineer(f.tenant() + "-other", f.engineerId())))));
        assertEpochUnavailable(assertThrows(BusinessException.class, () -> inTransaction(
                () -> fence.advance(AuthorizationEpochAnchor.organization(MISSING_ID)))));
        assertEpochUnavailable(assertThrows(BusinessException.class, () -> inTransaction(
                () -> fence.advance(AuthorizationEpochAnchor.taskGeneration(f.tenant(), f.caseId() + 1_000_000, f.taskA())))));
        assertEpochUnavailable(assertThrows(BusinessException.class, () -> inTransaction(
                () -> fence.lockForUpdate(AuthorizationEpochAnchor.document(f.tenant() + "-other", f.documentId())))));

        rollbackOnly(() -> {
            jdbc.update("UPDATE t_authorization_global_epoch SET epoch = ? WHERE singleton_id = 1", Long.MAX_VALUE);
            assertEpochUnavailable(assertThrows(BusinessException.class,
                    () -> fence.advance(AuthorizationEpochAnchor.global())));
            assertEquals(Long.MAX_VALUE, count("SELECT epoch FROM t_authorization_global_epoch WHERE singleton_id = 1"));
        });
        assertEquals(0L, count("SELECT epoch FROM t_authorization_global_epoch WHERE singleton_id = 1"));
    }

    @Test
    void 増分後にentity全体をupdateByIdしてもepochは巻き戻らず増分はversionを変えない() {
        Fixture f = fixture();
        // 増分より前に読んだentity（epoch列は写像されないため値を持たない）
        SysUser staleUser = sysUserMapper.selectById(f.userId());
        Engineer staleEngineer = engineerMapper.selectById(f.engineerId());
        OrganizationUnit staleOrganization = organizationUnitMapper.selectById(f.organizationId());
        Document staleDocument = documentMapper.selectById(f.documentId());
        LifecycleTask staleTask = taskMapper.selectById(f.taskA());

        inTransaction(() -> {
            lockCase(f);
            for (AnchorRow row : anchors(f)) {
                fence.lockForUpdate(row.anchor());
                fence.advance(row.anchor());
            }
            return null;
        });

        // 古いentity全体の書戻し。増分はversionを変えないため楽観ロックのCASも通り、全列UPDATEになる
        staleUser.setRealName("書戻し利用者");
        assertEquals(1, sysUserMapper.updateById(staleUser));
        staleEngineer.setRemarks("書戻し要員");
        assertEquals(1, engineerMapper.updateById(staleEngineer));
        staleOrganization.setName("書戻し組織");
        assertEquals(1, organizationUnitMapper.updateById(staleOrganization));
        staleDocument.setTitle("書戻し文書");
        assertEquals(1, documentMapper.updateById(staleDocument));
        staleTask.setTaskName("書戻しタスク");
        assertEquals(1, taskMapper.updateById(staleTask));

        // EngineerStatusServiceImpl と同じ形: 同一transactionで FOR UPDATE して読んだentity全体を書き戻す
        inTransaction(() -> {
            Engineer locked = engineerMapper.selectByIdForUpdateForTenant(f.engineerId(), f.tenant());
            locked.setStatus("提案中");
            assertEquals(1, engineerMapper.updateById(locked));
            return null;
        });

        for (AnchorRow row : anchors(f)) {
            assertEquals(1L, epoch(row), "entityの全体更新でepochが巻き戻らないこと: " + row.column());
        }
        assertEquals("提案中", engineerMapper.selectById(f.engineerId()).getStatus());
        assertEquals("書戻し文書", documentMapper.selectById(f.documentId()).getTitle());
    }

    // ===== reader × writer =====

    @Test
    void readerがFOR_SHAREを保持する間writerのFOR_UPDATEは待つ() throws Exception {
        Fixture f = fixture();
        assertWriterWaitsForSharedReader("全体", "t_authorization_global_epoch",
                () -> reader.readGlobal(), () -> fence.lockForUpdate(AuthorizationEpochAnchor.global()));
        assertWriterWaitsForSharedReader("利用者", "sys_user",
                () -> reader.readActor(f.tenant(), f.userId()),
                () -> fence.lockForUpdate(AuthorizationEpochAnchor.actor(f.tenant(), f.userId())));
        assertWriterWaitsForSharedReader("組織", "m_organization_unit",
                () -> reader.readOrganizations(List.of(f.organizationId())).get(f.organizationId()),
                () -> fence.lockForUpdate(AuthorizationEpochAnchor.organization(f.organizationId())));
        assertWriterWaitsForSharedReader("要員", "t_engineer",
                () -> reader.readEngineer(f.tenant(), f.engineerId()),
                () -> fence.lockForUpdate(AuthorizationEpochAnchor.engineer(f.tenant(), f.engineerId())));
        assertWriterWaitsForSharedReader("文書", "t_document",
                () -> reader.readDocument(f.tenant(), f.documentId()),
                () -> fence.lockForUpdate(AuthorizationEpochAnchor.document(f.tenant(), f.documentId())));
    }

    @Test
    void readerは論理削除済みとtenant不一致のanchorを409にする() {
        Fixture f = fixture();
        jdbc.update("UPDATE t_engineer SET deleted_flag = 1 WHERE id = ?", f.engineerId());
        assertEpochUnavailable(assertThrows(BusinessException.class,
                () -> inTransaction(() -> reader.readEngineer(f.tenant(), f.engineerId()))));
        assertEpochUnavailable(assertThrows(BusinessException.class,
                () -> inTransaction(() -> reader.readActor(f.tenant() + "-other", f.userId()))));
        assertEquals(0L, inTransaction(() -> reader.readActor(f.tenant(), f.userId())));
    }

    // ===== lock executor =====

    @Test
    void lock計画どおりの行を指定modeで1回ずつlockしcase保持下でtaskを取る() {
        Fixture f = fixture();
        LifecycleLockPlan plan = LifecycleLockPlan.from(List.of(
                LockKey.shared(Rank.DOCUMENT_VERSION, f.tenant(), f.documentVersionId()),
                LockKey.shared(Rank.DOCUMENT, f.tenant(), f.documentId()),
                LockKey.shared(Rank.ENGINEER, f.tenant(), f.engineerId()),
                LockKey.exclusive(Rank.ENGINEER, f.tenant(), f.engineerId()),
                LockKey.shared(Rank.ORGANIZATION, "", f.organizationId()),
                LockKey.exclusive(Rank.LIFECYCLE_TASK, f.tenant(), f.taskB()),
                LockKey.exclusive(Rank.LIFECYCLE_TASK, f.tenant(), f.taskA()),
                LockKey.exclusive(Rank.LIFECYCLE_CASE, f.tenant(), f.caseId()),
                LockKey.shared(Rank.SYS_USER, f.tenant(), f.userId()),
                LockKey.global(Mode.SHARED)));

        Map<String, List<String>> held = readCommitted().execute(status -> {
            lockExecutor.acquire(plan);
            Map<String, List<String>> locks = primaryRecordLocks(connectionId());
            // 取得済みkey以前のkeyを同じtransactionで後から取らない（§3.5 規則1）
            assertThrows(IllegalStateException.class, () -> lockExecutor.acquire(LifecycleLockPlan.from(List.of(
                    LockKey.shared(Rank.SYS_USER, f.tenant(), f.userId())))));
            status.setRollbackOnly();
            return locks;
        });

        Map<String, List<String>> expected = new TreeMap<>();
        expected.put("m_organization_unit", List.of("S,REC_NOT_GAP"));
        expected.put("sys_user", List.of("S,REC_NOT_GAP"));
        expected.put("t_authorization_global_epoch", List.of("S,REC_NOT_GAP"));
        expected.put("t_document", List.of("S,REC_NOT_GAP"));
        expected.put("t_document_version", List.of("S,REC_NOT_GAP"));
        expected.put("t_engineer", List.of("X,REC_NOT_GAP"));
        expected.put("t_lifecycle_case", List.of("X,REC_NOT_GAP"));
        expected.put("t_lifecycle_task", List.of("X,REC_NOT_GAP", "X,REC_NOT_GAP"));
        assertEquals(expected, held, "planの各行を主キーで1回ずつ、指定のmode（同じ行のS+XはX）でlockすること");

        // 他tenantの案件の下ではtaskを取らない（案件の一覧にtaskが属さないため行欠落として扱う）
        Fixture other = fixture();
        assertThrows(LifecycleLockPlan.DependencyChangedException.class, () -> rollbackOnly(() -> lockExecutor.acquire(
                LifecycleLockPlan.from(List.of(LockKey.exclusive(Rank.LIFECYCLE_CASE, f.tenant(), f.caseId()),
                        LockKey.exclusive(Rank.LIFECYCLE_TASK, f.tenant(), other.taskA()))))));
    }

    /**
     * W3-10 review P3-1: 要員（rank 7）の SHARED の SQL（{@code SELECT id … WHERE id=? AND tenant_id=? FOR SHARE}）は、
     * {@code (tenant_id, id)} を覆う二次索引（V169 の {@code idx_engineer_tenant_population}。二次索引は主キーを含む）だけで
     * 答えられる形である。二次索引の記録にだけS lockが付くと、主キーで更新するwriterは待たない。上の試験では同じ行のS+XがXに
     * 合わさるため、要員だけをSHAREDで取るplanで、PRIMARYの {@code S,REC_NOT_GAP} がちょうど1件で二次索引のlockが無いこと、
     * 主キーでXを取るwriterがそのS lockを待つことを確かめる（{@code LOCK_DATA} に依存しない）。
     */
    @Test
    void 要員だけのSHARED_lockはPRIMARYの共有record_lockになり主キーのwriterを待たせる() throws Exception {
        Fixture f = fixture();
        LifecycleLockPlan plan = LifecycleLockPlan.from(List.of(LockKey.shared(Rank.ENGINEER, f.tenant(), f.engineerId())));

        Map<String, List<String>> held = readCommitted().execute(status -> {
            lockExecutor.acquire(plan);
            long connection = connectionId();
            Map<String, List<String>> locks = primaryRecordLocks(connection);
            assertEquals(0L, secondaryRecordLockCount(connection, "t_engineer"),
                    "要員のSHAREDは二次索引だけのlockにならない");
            status.setRollbackOnly();
            return locks;
        });
        assertEquals(Map.of("t_engineer", List.of("S,REC_NOT_GAP")), held,
                "lock planの要員SHAREDはPRIMARYの共有record lock1件だけ");

        // readerはlock planのSHAREDだけを取り、epochは非lockの読取で返す（epoch mapperのFOR SHAREを足さない）
        assertWriterWaitsForSharedReader("要員（lock planのSHARED）", "t_engineer", () -> {
            lockExecutor.acquire(plan);
            return jdbc.queryForObject("SELECT engineer_scope_epoch FROM t_engineer WHERE id = ?", Long.class,
                    f.engineerId());
        }, () -> fence.lockForUpdate(AuthorizationEpochAnchor.engineer(f.tenant(), f.engineerId())));
    }

    @Test
    void lock計画の各rankのSQLは実MySQLで実行でき対象行が無ければ依存変更になる() {
        Fixture f = fixture();
        List<String> checked = new ArrayList<>();
        for (Rank rank : Rank.values()) {
            if (rank == Rank.GLOBAL_AUTHORIZATION_EPOCH || rank == Rank.LIFECYCLE_TASK) {
                continue;
            }
            String partition = rank.partitioning() == LifecycleLockPlan.Partitioning.TENANT ? f.tenant() : "";
            for (Mode mode : rank.sharedAllowed() ? List.of(Mode.SHARED, Mode.EXCLUSIVE) : List.of(Mode.EXCLUSIVE)) {
                LifecycleLockPlan plan = LifecycleLockPlan.from(List.of(new LockKey(rank, partition, MISSING_ID, mode)));
                assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                        () -> rollbackOnly(() -> lockExecutor.acquire(plan)), rank + "/" + mode);
                checked.add(rank + "/" + mode);
            }
        }
        assertThrows(LifecycleLockPlan.DependencyChangedException.class, () -> rollbackOnly(() -> lockExecutor.acquire(
                LifecycleLockPlan.from(List.of(LockKey.exclusive(Rank.LIFECYCLE_CASE, f.tenant(), f.caseId()),
                        LockKey.exclusive(Rank.LIFECYCLE_TASK, f.tenant(), MISSING_ID))))));
        for (Mode mode : List.of(Mode.SHARED, Mode.EXCLUSIVE)) {
            rollbackOnly(() -> lockExecutor.acquire(LifecycleLockPlan.from(List.of(LockKey.global(mode)))));
        }
        assertEquals(Rank.values().length - 2 + 5, checked.size(), "全rankのSQLを実行したこと: " + checked);
    }

    // ===== preflight =====

    @Test
    void V189適用後のpreflightはepoch列と全体epoch表を適用済みとしてPASSにする() throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword())) {
            JsonNode findings = new ObjectMapper().readTree(Nf01PreflightTestAccess.authorizationEpochChecksJson(
                    connection, MYSQL.getDatabaseName())).path("findings");
            int columns = 0;
            for (JsonNode finding : findings) {
                assertEquals("PASS", finding.path("status").asText(), finding.toString());
                assertTrue(finding.path("reason").asText().contains("state=APPLIED"), finding.toString());
                if ("AUTHORIZATION_EPOCH_COLUMN".equals(finding.path("checkId").asText())) {
                    columns++;
                }
            }
            assertEquals(5, columns);
            assertEquals(7, findings.size(), "列5件・全体表1件・適用状態1件");
        }
    }

    // ===== B0（V195、W3-18。計画 §5.12・§7.7 Demo (1)〜(3)） =====

    @Test
    void V195はB0の7表と一意keyとFKを空で作り照合順序は参照先と一致しtriggerは不変6表にだけ付く() {
        assertEquals(1L, count("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '195' AND success = 1"));
        for (String table : B0_TABLES) {
            assertEquals("utf8mb4_unicode_ci", jdbc.queryForObject("SELECT TABLE_COLLATION FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", String.class, table), table);
            assertEquals(0L, count("SELECT COUNT(*) FROM " + table), table + " は行を作らない");
        }

        // 複合FKが参照する4つの一意key（V189の3件とV165の1件。V195は追加しない）
        assertUniqueKey("t_lifecycle_case", "uk_lifecycle_case_tenant_id", "tenant_id,id");
        assertUniqueKey("t_lifecycle_task", "uk_lifecycle_task_case_id", "case_id,id");
        assertUniqueKey("t_document_version", "uk_document_version_tenant_doc_id", "tenant_id,document_id,id");
        assertUniqueKey("sys_user", "uk_sys_user_tenant_id", "tenant_id,id");
        // B0の一意key・複合主キー
        assertUniqueKey("t_lifecycle_dual_submission", "uk_dual_submission_revision",
                "tenant_id,task_id,task_generation,revision");
        assertUniqueKey("t_lifecycle_dual_submission", "uk_dual_submission_binding",
                "tenant_id,task_id,task_generation,revision,id,evidence_sha256");
        assertUniqueKey("t_lifecycle_dual_submission", "uk_dual_submission_tenant_id", "tenant_id,id");
        assertUniqueKey("t_lifecycle_dual_confirmation", "uk_dual_confirmation_submission", "tenant_id,submission_id");
        assertUniqueKey("t_lifecycle_dual_idempotency", "uk_dual_idempotency",
                "tenant_id,task_id,task_generation,operation,actor_user_id,idempotency_key");
        assertUniqueKey("t_lifecycle_dual_submission_document", "PRIMARY", "submission_id,document_id");
        assertUniqueKey("t_lifecycle_dual_submission_org_fence", "PRIMARY", "submission_id,organization_id");
        assertUniqueKey("t_lifecycle_dual_projection", "PRIMARY", "tenant_id,task_id");

        Map<String, String> expectedForeignKeys = new TreeMap<>(Map.ofEntries(
                Map.entry("fk_dual_submission_case", "t_lifecycle_dual_submission(tenant_id,case_id)->t_lifecycle_case(tenant_id,id)"),
                Map.entry("fk_dual_submission_task", "t_lifecycle_dual_submission(case_id,task_id)->t_lifecycle_task(case_id,id)"),
                Map.entry("fk_dual_submission_submitter",
                        "t_lifecycle_dual_submission(tenant_id,submitter_user_id)->sys_user(tenant_id,id)"),
                Map.entry("fk_dual_doc_submission",
                        "t_lifecycle_dual_submission_document(tenant_id,submission_id)->t_lifecycle_dual_submission(tenant_id,id)"),
                Map.entry("fk_dual_doc_version", "t_lifecycle_dual_submission_document(tenant_id,document_id,document_version_id)"
                        + "->t_document_version(tenant_id,document_id,id)"),
                Map.entry("fk_dual_org_submission",
                        "t_lifecycle_dual_submission_org_fence(tenant_id,submission_id)->t_lifecycle_dual_submission(tenant_id,id)"),
                Map.entry("fk_dual_org_unit", "t_lifecycle_dual_submission_org_fence(organization_id)->m_organization_unit(id)"),
                Map.entry("fk_dual_confirmation_submission", "t_lifecycle_dual_confirmation(tenant_id,task_id,task_generation,"
                        + "submission_revision,submission_id,evidence_sha256)->t_lifecycle_dual_submission(tenant_id,task_id,"
                        + "task_generation,revision,id,evidence_sha256)"),
                Map.entry("fk_dual_confirmation_confirmer",
                        "t_lifecycle_dual_confirmation(tenant_id,confirmer_user_id)->sys_user(tenant_id,id)"),
                Map.entry("fk_dual_projection_case", "t_lifecycle_dual_projection(tenant_id,case_id)->t_lifecycle_case(tenant_id,id)"),
                Map.entry("fk_dual_projection_task", "t_lifecycle_dual_projection(case_id,task_id)->t_lifecycle_task(case_id,id)"),
                Map.entry("fk_dual_projection_submission", "t_lifecycle_dual_projection(tenant_id,task_id,task_generation,"
                        + "revision,submission_id,evidence_sha256)->t_lifecycle_dual_submission(tenant_id,task_id,"
                        + "task_generation,revision,id,evidence_sha256)"),
                Map.entry("fk_dual_idem_case", "t_lifecycle_dual_idempotency(tenant_id,case_id)->t_lifecycle_case(tenant_id,id)"),
                Map.entry("fk_dual_idem_actor", "t_lifecycle_dual_idempotency(tenant_id,actor_user_id)->sys_user(tenant_id,id)")));
        Map<String, String> foreignKeys = new TreeMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT CONSTRAINT_NAME AS fk, TABLE_NAME AS src, "
                + "GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION) AS src_cols, MIN(REFERENCED_TABLE_NAME) AS dst, "
                + "GROUP_CONCAT(REFERENCED_COLUMN_NAME ORDER BY ORDINAL_POSITION) AS dst_cols "
                + "FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME LIKE 't\\_lifecycle\\_dual\\_%' AND REFERENCED_TABLE_NAME IS NOT NULL "
                + "GROUP BY CONSTRAINT_NAME, TABLE_NAME")) {
            foreignKeys.put(String.valueOf(row.get("fk")), row.get("src") + "(" + row.get("src_cols") + ")->"
                    + row.get("dst") + "(" + row.get("dst_cols") + ")");
        }
        assertEquals(expectedForeignKeys, foreignKeys);
        assertEquals(0L, count("SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME LIKE 't\\_lifecycle\\_dual\\_%' "
                + "AND (DELETE_RULE IN ('CASCADE', 'SET NULL') OR UPDATE_RULE IN ('CASCADE', 'SET NULL'))"),
                "B0のFKは参照先の削除・更新を連鎖させない");

        // tenant列は参照先と同じ型・照合順序（計画 §5.12。不一致ならFKを作れない）。idempotency_key は ascii_bin
        String tenantCollation = columnCollation("sys_user", "tenant_id");
        assertEquals(tenantCollation, columnCollation("t_lifecycle_case", "tenant_id"));
        assertEquals(tenantCollation, columnCollation("t_document_version", "tenant_id"));
        for (String table : B0_TABLES) {
            assertEquals(tenantCollation, columnCollation(table, "tenant_id"), table);
            assertEquals("varchar(100)", jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'tenant_id'", String.class, table), table);
        }
        assertEquals("ascii_bin", columnCollation("t_lifecycle_dual_idempotency", "idempotency_key"));

        assertCheck("t_lifecycle_dual_submission", "ck_dual_submission_nonneg", "revision");
        assertCheck("t_lifecycle_dual_confirmation", "ck_dual_confirmation_decision", "decision");
        assertCheck("t_lifecycle_dual_projection", "ck_dual_projection_state", "state");
        assertCheck("t_lifecycle_dual_idempotency", "ck_dual_idem_operation", "operation");
        assertCheck("t_lifecycle_dual_event", "ck_dual_event_type", "event_type");
        assertCheck("t_lifecycle_dual_idempotency", "ck_dual_idem_key", "idempotency_key");

        Map<String, Set<String>> triggers = new TreeMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT EVENT_OBJECT_TABLE AS tbl, EVENT_MANIPULATION AS ev, "
                + "ACTION_TIMING AS timing FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE() "
                + "AND EVENT_OBJECT_TABLE LIKE 't\\_lifecycle\\_dual\\_%'")) {
            assertEquals("BEFORE", row.get("timing"), String.valueOf(row));
            triggers.computeIfAbsent(String.valueOf(row.get("tbl")), table -> new TreeSet<>()).add(String.valueOf(row.get("ev")));
        }
        Map<String, Set<String>> expectedTriggers = new TreeMap<>();
        for (String table : B0_IMMUTABLE_TABLES) {
            expectedTriggers.put(table, new TreeSet<>(Set.of("DELETE", "UPDATE")));
        }
        assertEquals(expectedTriggers, triggers, "triggerは不変6表のUPDATE・DELETEにだけ付く（projectionは可変）");
    }

    @Test
    void 冪等性keyのCHECKは空文字と末尾空白と改行と許可外の文字を拒否し許可する文字だけのkeyを通す() {
        Fixture f = fixture();
        rollbackOnly(() -> {
            long submissionId = insertSubmission(f.tenant(), f.caseId(), f.taskA(), f.engineerId(), f.userId(), 1);
            // ascii_bin は PAD SPACE のため、末尾空白のkeyは一意keyで 'Key-A' と同一視される。CHECKはその前に拒否する
            for (String invalid : List.of("", "Key-A ", " Key-A", "key a", "key/a", "key\n", "key\t", "key+a", "key%a")) {
                assertCheckRejected("key=[" + invalid + "]", "ck_dual_idem_key",
                        () -> insertIdempotency(f, f.userId(), invalid, submissionId));
            }
            for (String valid : List.of("Key-A", "a.b_c~d-1", "Z".repeat(190))) {
                assertTrue(insertIdempotency(f, f.userId(), valid, submissionId) > 0, "key=[" + valid + "]");
            }
        });
    }

    @Test
    void B0の不変6表はUPDATEとDELETEをtriggerとmapperで拒否しprojectionだけCASで更新できる() {
        Fixture f = fixture();
        rollbackOnly(() -> {
            B0Rows rows = insertB0Rows(f);
            for (String table : B0_IMMUTABLE_TABLES) {
                String where = rows.where(table);
                DataAccessException update = assertThrows(DataAccessException.class,
                        () -> jdbc.update("UPDATE " + table + " SET tenant_id = tenant_id WHERE " + where), table);
                assertSqlErrorCode(update, 1644, table + " のUPDATEはtriggerが拒否する");
                DataAccessException delete = assertThrows(DataAccessException.class,
                        () -> jdbc.update("DELETE FROM " + table + " WHERE " + where), table);
                assertSqlErrorCode(delete, 1644, table + " のDELETEはtriggerが拒否する");
                assertEquals(1L, count("SELECT COUNT(*) FROM " + table + " WHERE " + where), table + " の行は残る");
            }
            assertEquals(1, jdbc.update("UPDATE t_lifecycle_dual_projection SET state = 'CONFIRMED', "
                    + "projection_version = projection_version + 1 WHERE tenant_id = ? AND task_id = ? AND projection_version = 0",
                    f.tenant(), f.taskA()), "projectionはversionのCASで更新できる");
            assertEquals(0, jdbc.update("UPDATE t_lifecycle_dual_projection SET state = 'REJECTED' "
                    + "WHERE tenant_id = ? AND task_id = ? AND projection_version = 0", f.tenant(), f.taskA()),
                    "古いversionのCASは0件");

            // mapper層（実MyBatis-Plus proxy）はSQLを発行せずに拒否する
            LifecycleDualSubmission submission = dualSubmissionMapper.selectById(rows.submissionId());
            assertEquals(f.tenant(), submission.getTenantId());
            assertThrows(UnsupportedOperationException.class, () -> dualSubmissionMapper.updateById(submission));
            assertThrows(UnsupportedOperationException.class, () -> dualSubmissionMapper.deleteById(rows.submissionId()));
            assertThrows(UnsupportedOperationException.class, () -> dualEventMapper.delete(null));
        });
    }

    @Test
    void B0の複合FKは他tenantの案件と利用者と文書版と別案件のtaskと提出の束縛違いを拒否する() {
        Fixture f = fixture();
        Fixture other = fixture();
        rollbackOnly(() -> {
            // 各ケースはFKを1つだけ破り、他のFKは満たす（error 1452 とmessage中の制約名で、破った制約を特定する）
            assertForeignKeyRejected("他tenantの案件を指す提出", "fk_dual_submission_case",
                    () -> insertSubmission(f.tenant(), other.caseId(), other.taskA(), f.engineerId(), f.userId(), 1));
            assertForeignKeyRejected("別案件のtaskを指す提出", "fk_dual_submission_task",
                    () -> insertSubmission(f.tenant(), f.caseId(), other.taskA(), f.engineerId(), f.userId(), 1));
            assertForeignKeyRejected("他tenantの提出者", "fk_dual_submission_submitter",
                    () -> insertSubmission(f.tenant(), f.caseId(), f.taskA(), f.engineerId(), other.userId(), 1));

            long submissionId = insertSubmission(f.tenant(), f.caseId(), f.taskA(), f.engineerId(), f.userId(), 1);
            assertForeignKeyRejected("他tenantの文書版", "fk_dual_doc_version",
                    () -> insertSubmissionDocument(f.tenant(), submissionId, other.documentId(), other.documentVersionId()));
            assertForeignKeyRejected("別文書の版", "fk_dual_doc_version",
                    () -> insertSubmissionDocument(f.tenant(), submissionId, f.documentId(), other.documentVersionId()));
            assertForeignKeyRejected("他tenantから提出を指す文書行", "fk_dual_doc_submission",
                    () -> insertSubmissionDocument(other.tenant(), submissionId, other.documentId(), other.documentVersionId()));
            assertForeignKeyRejected("存在しない組織", "fk_dual_org_unit",
                    () -> jdbc.update(ORG_FENCE_INSERT, f.tenant(), submissionId, MISSING_ID));
            assertForeignKeyRejected("他tenantから提出を指す組織fence", "fk_dual_org_submission",
                    () -> jdbc.update(ORG_FENCE_INSERT, other.tenant(), submissionId, f.organizationId()));
            assertForeignKeyRejected("証跡hashの違う確認", "fk_dual_confirmation_submission",
                    () -> insertConfirmation(f, submissionId, 1, "d".repeat(64), f.userId()));
            assertForeignKeyRejected("revisionの違う確認", "fk_dual_confirmation_submission",
                    () -> insertConfirmation(f, submissionId, 2, EVIDENCE_SHA, f.userId()));
            assertForeignKeyRejected("他tenantの確認者", "fk_dual_confirmation_confirmer",
                    () -> insertConfirmation(f, submissionId, 1, EVIDENCE_SHA, other.userId()));
            assertForeignKeyRejected("世代の違う提出を指すprojection", "fk_dual_projection_submission",
                    () -> jdbc.update(PROJECTION_INSERT, f.tenant(), f.taskA(), f.caseId(), 1L, 1, submissionId, EVIDENCE_SHA));
            assertForeignKeyRejected("他tenantの冪等性要求者", "fk_dual_idem_actor",
                    () -> insertIdempotency(f, other.userId(), "Key-A", submissionId));
            assertForeignKeyRejected("他tenantの案件を指す冪等性", "fk_dual_idem_case",
                    () -> jdbc.update(IDEMPOTENCY_INSERT, f.tenant(), other.caseId(), f.taskA(), f.userId(), "Key-B",
                            REQUEST_SHA, submissionId));

            // CHECK（error 3819）: FKを満たす行で、値だけが許可外。一意keyより先に判定される
            assertSqlErrorCode(assertThrows(DataAccessException.class,
                    () -> insertSubmission(f.tenant(), f.caseId(), f.taskA(), f.engineerId(), f.userId(), 0)), 3819, "revision 0");
            assertCheckRejected("決定がCONFIRMED/REJECTED以外", "ck_dual_confirmation_decision",
                    () -> jdbc.update(CONFIRMATION_INSERT.replace("'CONFIRMED')", "'APPROVED')"), f.tenant(), f.caseId(),
                            f.taskA(), submissionId, 1, EVIDENCE_SHA, f.userId()));
            assertCheckRejected("projectionの状態がPENDING/CONFIRMED/REJECTED以外", "ck_dual_projection_state",
                    () -> jdbc.update(PROJECTION_INSERT.replace("'PENDING')", "'DONE')"), f.tenant(), f.taskA(), f.caseId(),
                            0L, 1, submissionId, EVIDENCE_SHA));
            assertCheckRejected("冪等性の操作がSUBMIT/CONFIRM以外", "ck_dual_idem_operation",
                    () -> jdbc.update(IDEMPOTENCY_INSERT.replace("'SUBMIT'", "'REJECT'"), f.tenant(), f.caseId(), f.taskA(),
                            f.userId(), "Key-C", REQUEST_SHA, submissionId));
            assertCheckRejected("監査eventの種別が既知以外", "ck_dual_event_type",
                    () -> jdbc.update("INSERT INTO t_lifecycle_dual_event (tenant_id, actor_user_id, operation, event_type) "
                            + "VALUES (?, ?, 'SUBMIT', 'UPDATED')", f.tenant(), f.userId()));

            // 一意key: 同じtask・世代のrevision、提出ごとの確認、冪等性key（ascii_binで大文字小文字を区別する）
            assertThrows(DuplicateKeyException.class,
                    () -> insertSubmission(f.tenant(), f.caseId(), f.taskA(), f.engineerId(), f.userId(), 1));
            assertTrue(insertSubmission(f.tenant(), f.caseId(), f.taskA(), f.engineerId(), f.userId(), 2) > submissionId);
            insertConfirmation(f, submissionId, 1, EVIDENCE_SHA, f.userId());
            assertThrows(DuplicateKeyException.class, () -> insertConfirmation(f, submissionId, 1, EVIDENCE_SHA, f.userId()));
            insertIdempotency(f, f.userId(), "Key-A", submissionId);
            insertIdempotency(f, f.userId(), "key-A", submissionId);
            assertThrows(DuplicateKeyException.class, () -> insertIdempotency(f, f.userId(), "Key-A", submissionId));

            // 監査はFKを持たず、他tenantのIDや存在しないIDを要求値として記録できる（計画 §5.8）
            assertEquals(1, jdbc.update("INSERT INTO t_lifecycle_dual_event (tenant_id, requested_task_id, "
                    + "requested_submission_id, actor_user_id, operation, event_type, reason_code, http_status, attempt_count) "
                    + "VALUES (?, ?, ?, ?, 'CONFIRM', 'DENIED', 'NOT_FOUND_OR_FOREIGN', 404, 1)",
                    f.tenant(), other.taskA(), MISSING_ID, f.userId()));
        });
    }

    // ===== helpers: reader × writer =====

    private void assertWriterWaitsForSharedReader(String label, String table, Supplier<Long> readCall,
                                                  Supplier<Long> lockCall) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch readerLocked = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        AtomicLong readerConnection = new AtomicLong();
        AtomicLong writerConnection = new AtomicLong();
        try {
            Future<Long> readerResult = pool.submit(() -> readCommitted().execute(status -> {
                try {
                    readerConnection.set(connectionId());
                    return readCall.get();
                } finally {
                    readerLocked.countDown();
                    awaitQuietly(releaseReader, WAIT_SECONDS * 3);
                    status.setRollbackOnly();
                }
            }));
            if (!readerLocked.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                fail(label + ": readerがFOR SHAREの読取を終えなかった");
            }
            if (readerResult.isDone()) {
                fail(label + ": readerがlockを保持する前に終了した: " + outcome(readerResult));
            }
            String sharedLockId = requireSingleSharedLock(readerConnection.get(), table, label);

            Future<Long> writerResult = pool.submit(() -> transactionTemplate().execute(status -> {
                writerConnection.set(connectionId());
                Long locked = lockCall.get();
                status.setRollbackOnly();
                return locked;
            }));
            WaitEdge edge = awaitWriterWaitsOnSharedLock(writerResult, writerConnection, readerConnection.get(), table,
                    sharedLockId, label);
            assertFalse(writerResult.isDone(), label + ": writerはreaderの解放まで待つこと。edge=" + edge.describe());

            releaseReader.countDown();
            long read = readerResult.get(WAIT_SECONDS, TimeUnit.SECONDS);
            long locked = writerResult.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(read, locked, label + ": writerはreaderの解放後に同じ値でlockを得ること");
        } finally {
            releaseReader.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * readerが当該表の主キーで共有record lockをちょうど1件保持していることを確かめ、そのlock IDを返す
     * （writerを始める前の前提。LOCK_DATAには依存しない）。
     */
    private String requireSingleSharedLock(long readerConnection, String table, String label) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<HeldLock> held = List.of();
        while (System.nanoTime() < deadline) {
            held = InnoDbRecordLockWaits.grantedRecordLocks(jdbc, readerConnection, table, "PRIMARY", SHARED_RECORD_MODES);
            if (held.size() == 1) {
                return held.get(0).lockId();
            }
            if (held.size() > 1) {
                break;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(label + ": readerが" + table + "の主キーで共有record lockをちょうど1件保持していることを"
                + "確認できなかった: count=" + held.size() + "; " + InnoDbRecordLockWaits.describeWaitsForBlocker(jdbc, readerConnection));
    }

    /**
     * writerがreaderの共有lockを待っていることを確かめる。毎poll、readerの共有lockが固定したlock IDのまま1件であることを要求し、
     * 待機辺のblocking lock IDがそれと一致し、writer側が同じ表・主キーの排他record lockでWAITINGであることを確かめる。
     */
    private WaitEdge awaitWriterWaitsOnSharedLock(Future<Long> writer, AtomicLong writerConnection, long readerConnection,
                                                  String table, String sharedLockId, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (writer.isDone()) {
                fail(label + ": writerが待たずに終了した: " + outcome(writer));
            }
            List<HeldLock> held = InnoDbRecordLockWaits.grantedRecordLocks(jdbc, readerConnection, table, "PRIMARY",
                    SHARED_RECORD_MODES);
            if (held.size() != 1 || !held.get(0).lockId().equals(sharedLockId)) {
                fail(label + ": readerの共有lockが固定したものと変わった: anchored=" + sharedLockId + ", held=" + held);
            }
            long requester = writerConnection.get();
            if (requester > 0) {
                for (WaitEdge edge : InnoDbRecordLockWaits.waitEdges(jdbc, requester, readerConnection, table, "PRIMARY")) {
                    if (sharedLockId.equals(edge.blockerLockId())
                            && "RECORD".equals(edge.requesterType()) && "WAITING".equals(edge.requesterStatus())
                            && EXCLUSIVE_RECORD_MODES.contains(edge.requesterMode())
                            && "RECORD".equals(edge.blockerType()) && "GRANTED".equals(edge.blockerStatus())
                            && SHARED_RECORD_MODES.contains(edge.blockerMode())) {
                        return edge;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError(label + ": writerが" + table + "でreaderの共有lockを待つことを確認できなかった: writer="
                + writerConnection.get() + "; " + InnoDbRecordLockWaits.describeWaitsForBlocker(jdbc, readerConnection));
    }

    private Map<String, List<String>> primaryRecordLocks(long connectionId) {
        Map<String, List<String>> locks = new TreeMap<>();
        jdbc.query("SELECT dl.OBJECT_NAME, dl.LOCK_MODE FROM performance_schema.data_locks dl "
                        + "JOIN performance_schema.threads th ON th.THREAD_ID = dl.THREAD_ID "
                        + "WHERE th.PROCESSLIST_ID = ? AND dl.OBJECT_SCHEMA = DATABASE() AND dl.LOCK_TYPE = 'RECORD' "
                        + "AND dl.INDEX_NAME = 'PRIMARY' AND dl.LOCK_STATUS = 'GRANTED' ORDER BY dl.OBJECT_NAME, dl.LOCK_MODE",
                rs -> {
                    locks.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>()).add(rs.getString(2));
                }, connectionId);
        return locks;
    }

    /** 接続が表の二次索引（PRIMARY以外）に持つrecord lockの件数。 */
    private long secondaryRecordLockCount(long connectionId, String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_locks dl "
                + "JOIN performance_schema.threads th ON th.THREAD_ID = dl.THREAD_ID "
                + "WHERE th.PROCESSLIST_ID = ? AND dl.OBJECT_SCHEMA = DATABASE() AND dl.OBJECT_NAME = ? "
                + "AND dl.LOCK_TYPE = 'RECORD' AND dl.INDEX_NAME <> 'PRIMARY'", Long.class, connectionId, table);
        return count == null ? 0L : count;
    }

    private static void awaitQuietly(CountDownLatch latch, long seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String outcome(Future<?> future) {
        try {
            return String.valueOf(future.get(1, TimeUnit.SECONDS));
        } catch (Exception failure) {
            return failure.toString();
        }
    }

    // ===== helpers: transaction =====

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    /** CONFIRM attemptと同じ READ_COMMITTED のtransaction。 */
    private TransactionTemplate readCommitted() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private <T> T inTransaction(Callable<T> action) {
        return transactionTemplate().execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private void rollbackOnly(Runnable action) {
        transactionTemplate().executeWithoutResult(status -> {
            try {
                action.run();
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    private long connectionId() {
        Long id = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private void lockCase(Fixture f) {
        assertTrue(caseMapper.selectByIdForUpdateAndTenant(f.caseId(), f.tenant()) != null, "案件を先にX lockする");
    }

    // ===== helpers: assertions =====

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private long epoch(AnchorRow row) {
        return count("SELECT " + row.column() + " FROM " + row.table() + " WHERE id = " + row.id());
    }

    private long version(AnchorRow row) {
        return count("SELECT `version` FROM " + row.table() + " WHERE id = " + row.id());
    }

    private void assertCheck(String table, String constraint, String column) {
        Map<String, Object> placement = jdbc.queryForMap("SELECT TABLE_NAME, ENFORCED FROM information_schema.TABLE_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = ? AND CONSTRAINT_TYPE = 'CHECK'", constraint);
        assertEquals(table, placement.get("TABLE_NAME"), constraint);
        assertEquals("YES", placement.get("ENFORCED"), constraint);
        String clause = jdbc.queryForObject("SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = ?", String.class, constraint);
        assertTrue(clause != null && clause.contains(column), constraint + ": " + clause);
    }

    private void assertUniqueKey(String table, String index, String columns) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols, "
                + "MIN(NON_UNIQUE) AS non_unique FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = ? AND INDEX_NAME = ? GROUP BY INDEX_NAME", table, index);
        assertEquals(1, rows.size(), table + "." + index + " が存在すること");
        assertEquals(columns, rows.get(0).get("cols"), index);
        assertEquals(0L, ((Number) rows.get(0).get("non_unique")).longValue(), index + " は一意であること");
    }

    private static void assertSqlErrorCode(DataAccessException failure, int expected, String label) {
        Throwable cause = failure.getMostSpecificCause();
        assertTrue(cause instanceof SQLException && ((SQLException) cause).getErrorCode() == expected,
                label + ": MySQL error " + expected + " であること: " + cause);
    }

    private static void assertEpochUnavailable(BusinessException failure) {
        assertEquals(409, failure.getCode());
        assertEquals("error.authorization.epochUnavailable", failure.getMessageKey());
    }

    // ===== fixture =====

    private Fixture fixture() {
        long seq = SEQUENCE.incrementAndGet();
        String tenant = "nf01-epoch-" + seq;
        SysUser user = SysUser.builder().tenantId(tenant).username("nf01_epoch_" + seq).password("pass")
                .realName("epoch利用者").role("HR").status(1).build();
        sysUserMapper.insert(user);
        Engineer engineer = Engineer.builder().tenantId(tenant).fullName("epoch要員" + seq)
                .status("Bench").employmentType("正社員").build();
        engineerMapper.insert(engineer);
        OrganizationUnit organization = OrganizationUnit.builder().code("NF01-EPOCH-" + seq).name("epoch組織")
                .type("部").validFrom(LocalDate.of(2026, 1, 1)).status("有効").build();
        organizationUnitMapper.insert(organization);
        long documentId = insert("INSERT INTO t_document (tenant_id, document_type, direction, status, title) "
                + "VALUES (?, 'SKILL_SHEET', 'INTERNAL', 'DRAFT', ?)", tenant, "epoch文書" + seq);
        long documentVersionId = insert("INSERT INTO t_document_version (tenant_id, document_id, version_no, storage_key, "
                        + "original_name, sha256, source_type, business_key, version_discriminator, scan_status) "
                        + "VALUES (?, ?, 1, ?, 'epoch.pdf', ?, 'TEST', ?, 'v1', 'CLEAN')",
                tenant, documentId, "nf01/epoch/" + seq, "a".repeat(64), "nf01-epoch-" + seq);
        LifecycleTemplate template = LifecycleTemplate.builder().templateType("JOIN").name("epoch検証" + seq)
                .versionNo(TEMPLATE_VERSION.incrementAndGet()).status("ACTIVE").validFrom(LocalDate.now().minusDays(1))
                .createdBy(user.getId()).updatedBy(user.getId()).build();
        templateMapper.insert(template);
        LifecycleCase lcCase = LifecycleCase.builder().tenantId(tenant).caseNo("LC-EPOCH-" + seq)
                .lifecycleType("JOIN").engineerId(engineer.getId()).templateId(template.getId())
                .templateVersion(template.getVersionNo()).anchorDate(LocalDate.now()).status("ACTIVE")
                .title("epoch検証案件").applicantUserId(user.getId()).engineerSnapshotJson("{}")
                .createdBy(user.getId()).updatedBy(user.getId()).build();
        caseMapper.insert(lcCase);
        Long taskA = task(lcCase, user, "EPOCH_TASK_A");
        Long taskB = task(lcCase, user, "EPOCH_TASK_B");
        return new Fixture(tenant, user.getId(), engineer.getId(), organization.getId(), documentId, documentVersionId,
                lcCase.getId(), taskA, taskB);
    }

    private Long task(LifecycleCase lcCase, SysUser user, String code) {
        LifecycleTask task = LifecycleTask.builder().caseId(lcCase.getId()).taskCode(code).taskName(code)
                .dueDate(LocalDate.now().plusDays(7)).assigneeRole("HR").isMandatory(1).isBlocking(1)
                .evidenceType("NONE").isEngineerVisible(1).status("IN_PROGRESS")
                .createdBy(user.getId()).updatedBy(user.getId()).build();
        taskMapper.insert(task);
        return task.getId();
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private List<AnchorRow> anchors(Fixture f) {
        return List.of(
                new AnchorRow("sys_user", "actor_authz_epoch", f.userId(),
                        AuthorizationEpochAnchor.actor(f.tenant(), f.userId()), false),
                new AnchorRow("t_engineer", "engineer_scope_epoch", f.engineerId(),
                        AuthorizationEpochAnchor.engineer(f.tenant(), f.engineerId()), true),
                new AnchorRow("m_organization_unit", "org_scope_epoch", f.organizationId(),
                        AuthorizationEpochAnchor.organization(f.organizationId()), true),
                new AnchorRow("t_document", "evidence_eligibility_epoch", f.documentId(),
                        AuthorizationEpochAnchor.document(f.tenant(), f.documentId()), true),
                new AnchorRow("t_lifecycle_task", "task_generation", f.taskA(),
                        AuthorizationEpochAnchor.taskGeneration(f.tenant(), f.caseId(), f.taskA()), true));
    }

    // ===== helpers: B0（V195） =====

    private String columnCollation(String table, String column) {
        return jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = ? AND COLUMN_NAME = ?", String.class, table, column);
    }

    private static void assertForeignKeyRejected(String label, String constraint, Executable insert) {
        DataAccessException failure = assertThrows(DataAccessException.class, insert, label);
        assertSqlErrorCode(failure, 1452, label + " は複合FKが拒否する");
        assertConstraintNamed(failure, constraint, label);
    }

    private static void assertCheckRejected(String label, String constraint, Executable insert) {
        DataAccessException failure = assertThrows(DataAccessException.class, insert, label);
        assertSqlErrorCode(failure, 3819, label + " はCHECKが拒否する");
        assertConstraintNamed(failure, constraint, label);
    }

    /** MySQLのmessage（1452: CONSTRAINT `名前` FOREIGN KEY …、3819: Check constraint '名前' is violated.）に制約名がある。 */
    private static void assertConstraintNamed(DataAccessException failure, String constraint, String label) {
        String message = String.valueOf(failure.getMostSpecificCause().getMessage());
        assertTrue(message.contains(constraint), label + ": " + constraint + " で拒否されること: " + message);
    }

    private long insertSubmission(String tenant, long caseId, long taskId, long engineerId, long submitterUserId,
                                  int revision) {
        return insert(SUBMISSION_INSERT, tenant, caseId, taskId, engineerId, revision, submitterUserId, EVIDENCE_SHA,
                REQUEST_SHA);
    }

    private int insertSubmissionDocument(String tenant, long submissionId, long documentId, long documentVersionId) {
        return jdbc.update(SUBMISSION_DOCUMENT_INSERT, tenant, submissionId, documentId, documentVersionId, "a".repeat(64));
    }

    private long insertConfirmation(Fixture f, long submissionId, int revision, String evidenceSha256, long confirmerUserId) {
        return insert(CONFIRMATION_INSERT, f.tenant(), f.caseId(), f.taskA(), submissionId, revision, evidenceSha256,
                confirmerUserId);
    }

    private long insertIdempotency(Fixture f, long actorUserId, String key, long submissionId) {
        return insert(IDEMPOTENCY_INSERT, f.tenant(), f.caseId(), f.taskA(), actorUserId, key, REQUEST_SHA, submissionId);
    }

    /** 7表へ整合した1組の行を入れる（呼出し側のtransactionでrollbackする）。 */
    private B0Rows insertB0Rows(Fixture f) {
        long submissionId = insertSubmission(f.tenant(), f.caseId(), f.taskA(), f.engineerId(), f.userId(), 1);
        assertEquals(1, insertSubmissionDocument(f.tenant(), submissionId, f.documentId(), f.documentVersionId()));
        assertEquals(1, jdbc.update(ORG_FENCE_INSERT, f.tenant(), submissionId, f.organizationId()));
        long confirmationId = insertConfirmation(f, submissionId, 1, EVIDENCE_SHA, f.userId());
        assertEquals(1, jdbc.update(PROJECTION_INSERT, f.tenant(), f.taskA(), f.caseId(), 0L, 1, submissionId, EVIDENCE_SHA));
        long idempotencyId = insertIdempotency(f, f.userId(), "nf01-b0-key", submissionId);
        long eventId = insert("INSERT INTO t_lifecycle_dual_event (tenant_id, case_id, task_id, task_generation, "
                + "submission_id, actor_user_id, operation, event_type) VALUES (?, ?, ?, 0, ?, ?, 'SUBMIT', 'SUBMITTED')",
                f.tenant(), f.caseId(), f.taskA(), submissionId, f.userId());
        return new B0Rows(submissionId, confirmationId, idempotencyId, eventId);
    }

    private record B0Rows(long submissionId, long confirmationId, long idempotencyId, long eventId) {
        String where(String table) {
            return switch (table) {
                case "t_lifecycle_dual_submission" -> "id = " + submissionId;
                case "t_lifecycle_dual_submission_document", "t_lifecycle_dual_submission_org_fence" ->
                        "submission_id = " + submissionId;
                case "t_lifecycle_dual_confirmation" -> "id = " + confirmationId;
                case "t_lifecycle_dual_idempotency" -> "id = " + idempotencyId;
                case "t_lifecycle_dual_event" -> "id = " + eventId;
                default -> throw new IllegalArgumentException(table);
            };
        }
    }

    private record AnchorRow(String table, String column, long id, AuthorizationEpochAnchor anchor, boolean versioned) { }

    private record Fixture(String tenant, long userId, long engineerId, long organizationId, long documentId,
                           long documentVersionId, long caseId, long taskA, long taskB) { }
}
