package com.ses.service.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.dto.lifecycle.LifecycleCaseDto;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.entity.Engineer;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.nf01.preflight.Nf01PreflightTestAccess;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.test.MySQLContainer;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * NF01 N1-1（L7 / V187）: 実MySQL 8で、同一tenant・同一要員の退社案件を2 sessionが同時に起票しても
 * 一意制約 uk_lifecycle_case_active_resignation により1件だけが成立し、他方は 400 duplicateActiveCase になる。
 *
 * <p>後続sessionが「先行sessionの未commit行で一意indexの重複確認を待っている」ことを performance_schema で確認してから
 * 先行sessionをcommitする。待機の確認は {@code data_locks.LOCK_DATA} の値に依存しない（LOCK_DATAはpageを
 * latch待ちなしで参照できたときだけ鍵値を返し、それ以外はNULLになるため。InnoDbRecordLockWaitsと同じ考え方）。
 * 次の組合せで「先行sessionが挿入した1行での待機」を確定する。</p>
 * <ol>
 *   <li>thread対: {@code data_lock_waits} のREQUESTING/BLOCKING threadのPROCESSLIST_IDが後続・先行の接続と一致し、互いに異なる。</li>
 *   <li>構造: 両端が同じschema・{@code t_lifecycle_case}・一意index {@code uk_lifecycle_case_active_resignation} 上の
 *       RECORD lockで、後続はWAITING（共有・排他のrecord lock。gap専用・insert intentionは数えない）、先行はGRANTEDの排他lock。</li>
 *   <li>行の同一性（先行側基準）: 挿入直後の行の暗黙lockはdata_locksに現れず、後続sessionによる暗黙→明示の変換にも
 *       観測を頼れないため、先行sessionは挿入後に自分の案件行を一意indexの一意点検索で {@code FOR UPDATE} し
 *       （生成列が要員IDを持つことも同時に確認）、当該indexの排他record lockをちょうど1件持つことを確かめてから
 *       後続sessionを始める。待機辺のblocking lockはそのlock IDと一致しなければならない。</li>
 *   <li>行の同一性（後続側基準）: InnoDBのtransactionが同時に待つlockは1件だけである。</li>
 * </ol>
 * <p>あわせて、V187適用前の重複データをpreflightが数え、その件数とV187のDDL成否が一致することを確認する。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class LifecycleResignationUniquenessMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf01_resignation_unique")
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

    private static final String UNIQUE_INDEX = "uk_lifecycle_case_active_resignation";
    private static final Set<String> RECORD_REQUEST_MODES = Set.of("S", "S,REC_NOT_GAP", "X", "X,REC_NOT_GAP");
    private static final Set<String> EXCLUSIVE_RECORD_MODES = Set.of("X", "X,REC_NOT_GAP");
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final AtomicInteger TEMPLATE_SUFFIX = new AtomicInteger();
    private static final long WAIT_SECONDS = 30;
    /** 先行transactionの保持上限。待機確認の期限より長くする（失敗時の診断を保持中の状態で取るため）。 */
    private static final long HOLD_SECONDS = WAIT_SECONDS * 3;

    @Autowired private LifecycleCaseService caseService;
    @Autowired private LifecycleTemplateService templateService;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void 同一要員の退社案件を2sessionが同時に起票しても一意制約で1件だけ成立し他方は400になる() throws Exception {
        Fixture f = fixture();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong firstConnection = new AtomicLong();
        AtomicLong secondConnection = new AtomicLong();
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        AtomicReference<Throwable> firstPreconditionFailure = new AtomicReference<>();
        try {
            // 採番行（tenant・月）の行ロックで直列化されないよう、2件の基準日は別の月にする。
            Future<Object> first = worker(pool, f, firstConnection, null, () -> {
                LifecycleCaseDto created = caseService.createCase(f.admin().getId(), command(f, LocalDate.now()));
                try {
                    // 挿入直後の行は暗黙lockでdata_locksに現れないため、先行session自身が一意indexの対象行を
                    // 一意点検索でlocking readし、明示的な排他record lockにしてから後続sessionを始める。
                    // 同時に、案件行が一意indexの対象（退社・未終結・未削除）として生成列に要員IDを持つことを確かめる。
                    requireOwnActiveResignationSlot(f, created.getId());
                } catch (Throwable failure) {
                    firstPreconditionFailure.set(failure);
                    throw failure;
                } finally {
                    firstInserted.countDown();
                }
                // 待機確認の期限（WAIT_SECONDS）より長く保持する。同じ期限だと確認に失敗したときに先行transactionが
                // 先にtimeoutしてrollbackし、失敗時の診断（locks・trx）が解放後の状態になってしまう。
                awaitLatch(releaseFirst, "先行起票transactionの解放", HOLD_SECONDS);
                return created;
            });
            awaitLatch(firstInserted, "先行起票が案件行を挿入して一意indexの対象行をlockする");
            if (firstPreconditionFailure.get() != null) {
                throw new AssertionError("先行起票の前提確認に失敗した", firstPreconditionFailure.get());
            }
            String anchoredLockId = requireSingleExclusiveSlotLock(firstConnection.get());
            Future<Object> second = worker(pool, f, secondConnection, secondThread,
                    () -> caseService.createCase(f.admin().getId(), command(f, LocalDate.now().plusMonths(2))));

            WaitEdge edge = awaitUniqueIndexWait(second, secondConnection::get, firstConnection::get, anchoredLockId,
                    f, secondThread);
            assertEquals(0L, countOpenResignationCases(f),
                    "後続起票は事前確認（非locking read）を通過したうえで一意indexの重複確認で待っており、まだどちらも確定していない。edge="
                            + edge.describe());

            releaseFirst.countDown();
            Object firstOutcome = first.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Object secondOutcome = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNoLockFailure("先行起票", firstOutcome);
            assertNoLockFailure("後続起票", secondOutcome);
            assertInstanceOf(LifecycleCaseDto.class, firstOutcome, "先行起票は成立すること: " + firstOutcome);
            assertBusinessRejection("後続起票", secondOutcome, 400, "error.lifecycle.duplicateActiveCase");
            assertEquals(1L, countOpenResignationCases(f), "未終結の退社案件は1件だけであること");
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 一意制約はアプリを通らない登録も拒否し案件の中止後は同じ要員で再び起票できる() {
        Fixture f = fixture();
        bindTenant(f);
        try {
            LifecycleCaseDto first = caseService.createCase(f.admin().getId(), command(f, LocalDate.now()));

            BusinessException duplicate = assertThrows(BusinessException.class,
                    () -> caseService.createCase(f.admin().getId(), command(f, LocalDate.now().plusMonths(1))));
            assertEquals(400, duplicate.getCode());
            assertEquals("error.lifecycle.duplicateActiveCase", duplicate.getMessageKey());

            // アプリの事前確認を通らない直接登録もDBが拒否する。MySQLのエラーにindex名が含まれ、400へ変換できる。
            DuplicateKeyException direct = assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                    "INSERT INTO t_lifecycle_case (tenant_id, case_no, lifecycle_type, engineer_id, template_id, "
                            + "template_version, anchor_date, status, title, applicant_user_id, engineer_snapshot_json) "
                            + "VALUES (?, ?, 'RESIGNATION', ?, ?, 1, CURRENT_DATE, 'ON_HOLD', 'L7直接登録', ?, '{}')",
                    f.tenant(), "LC-L7-DIRECT-" + SEQUENCE.incrementAndGet(), f.engineer().getId(), f.templateId(),
                    f.admin().getId()));
            assertTrue(String.valueOf(direct.getMostSpecificCause().getMessage()).contains(UNIQUE_INDEX),
                    "MySQLの重複エラーが一意index名を含むこと: " + direct.getMostSpecificCause().getMessage());

            caseService.cancelCase(first.getId(), f.admin().getId(), "L7再起票の検証");
            LifecycleCaseDto second = caseService.createCase(f.admin().getId(), command(f, LocalDate.now().plusMonths(1)));
            assertEquals("ACTIVE", second.getStatus());
            assertEquals(1L, countOpenResignationCases(f));
        } finally {
            clearTenant();
        }
    }

    @Test
    void V187適用前の重複はpreflightが数えその件数とV187のDDL成否が一致する() throws Exception {
        String target = "nf01_l7_premigration";
        String source = MYSQL.getDatabaseName();
        try (Connection root = rootConnection(source); Statement st = root.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + target);
            st.execute("CREATE DATABASE " + target);
            // 実schemaと同じ定義（collationを含む）を写し、V187の列とindexを外して適用前の形にする。
            st.execute("CREATE TABLE " + target + ".t_lifecycle_case LIKE " + source + ".t_lifecycle_case");
            st.execute("ALTER TABLE " + target + ".t_lifecycle_case DROP INDEX " + UNIQUE_INDEX
                    + ", DROP COLUMN active_resignation_engineer_id");
            // case 1・2: tenantの大文字小文字だけが違う未終結退社案件。一意indexはcollationで比較するため重複になる。
            // case 3（中止済み）・4（退社以外）・5（論理削除済み）・6（別要員）は対象外。
            st.executeUpdate("INSERT INTO " + target + ".t_lifecycle_case (id, tenant_id, case_no, lifecycle_type, "
                    + "engineer_id, template_id, template_version, anchor_date, status, title, applicant_user_id, "
                    + "engineer_snapshot_json, deleted_flag) VALUES "
                    + "(1, 'tenant-l7', 'LC-L7-PRE-1', 'RESIGNATION', 10, 1, 1, '2026-11-01', 'ACTIVE', 't', 1, '{}', 0),"
                    + "(2, 'TENANT-L7', 'LC-L7-PRE-2', 'RESIGNATION', 10, 1, 1, '2026-11-02', 'ON_HOLD', 't', 1, '{}', 0),"
                    + "(3, 'tenant-l7', 'LC-L7-PRE-3', 'RESIGNATION', 10, 1, 1, '2026-11-03', 'CANCELLED', 't', 1, '{}', 0),"
                    + "(4, 'tenant-l7', 'LC-L7-PRE-4', 'JOIN', 10, 1, 1, '2026-11-04', 'ACTIVE', 't', 1, '{}', 0),"
                    + "(5, 'tenant-l7', 'LC-L7-PRE-5', 'RESIGNATION', 10, 1, 1, '2026-11-05', 'ACTIVE', 't', 1, '{}', 1),"
                    + "(6, 'tenant-l7', 'LC-L7-PRE-6', 'RESIGNATION', 11, 1, 1, '2026-11-06', 'ACTIVE', 't', 1, '{}', 0)");
        }

        try (Connection targetConnection = rootConnection(target)) {
            JsonNode blocked = duplicateFinding(targetConnection);
            assertEquals("BLOCKED_MAPPING", blocked.path("status").asText());
            assertEquals(2L, blocked.path("rowCount").asLong());
            assertTrue(blocked.path("reason").asText().contains("duplicateGroups=1"), blocked.toString());

            SQLException ddlFailure = assertThrows(SQLException.class, () -> applyV187(targetConnection));
            // ER_DUP_ENTRY(1062)。ALTERの経路によってはkey名付きの同義エラー(1586)になるため両方を許す。
            assertTrue(ddlFailure.getErrorCode() == 1062 || ddlFailure.getErrorCode() == 1586,
                    "重複が残る間はV187が重複エラーで失敗すること: " + ddlFailure.getErrorCode() + " " + ddlFailure);
            assertEquals(0L, indexCount(targetConnection, target), "失敗したALTERは列もindexも残さないこと");

            // 残す案件以外を終結させると、preflightは0件になりV187を適用できる。
            try (Statement st = targetConnection.createStatement()) {
                st.executeUpdate("UPDATE t_lifecycle_case SET status = 'CANCELLED' WHERE id = 2");
            }
            JsonNode passed = duplicateFinding(targetConnection);
            assertEquals("PASS", passed.path("status").asText());
            assertEquals(0L, passed.path("rowCount").asLong());
            applyV187(targetConnection);
            assertTrue(indexCount(targetConnection, target) > 0, "V187の一意indexが作成されること");
        } finally {
            try (Connection root = rootConnection(source); Statement st = root.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + target);
            }
        }
    }

    // ===== fixture =====

    private Fixture fixture() {
        long seq = SEQUENCE.incrementAndGet();
        String tenant = "lc-l7-" + seq;
        SysUser admin = SysUser.builder().tenantId(tenant).username("lc_l7_admin_" + seq).password("pass")
                .realName("L7管理者").role("管理者").status(1).build();
        sysUserMapper.insert(admin);
        Engineer engineer = Engineer.builder().tenantId(tenant).fullName("L7要員" + seq)
                .status("稼動中").employmentType("正社員").build();
        engineerMapper.insert(engineer);
        LifecycleTemplateDto template = templateService.createTemplate(LifecycleTemplateDto.builder()
                .templateType("RESIGNATION")
                .name("L7並行起票検証-" + seq + "-" + TEMPLATE_SUFFIX.incrementAndGet())
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(LifecycleTemplateTaskDto.builder()
                        .taskCode("RESIGN_ASSET_RETURN")
                        .taskName("貸与資産返却")
                        .assigneeRule("APPLICANT")
                        .isMandatory(1)
                        .isBlocking(1)
                        .evidenceType("NONE")
                        .build()))
                .build(), admin.getId());
        return new Fixture(tenant, admin, engineer, template.getId());
    }

    private CreateLifecycleCaseCommand command(Fixture f, LocalDate anchorDate) {
        return CreateLifecycleCaseCommand.builder()
                .engineerId(f.engineer().getId())
                .lifecycleType("RESIGNATION")
                .templateId(f.templateId())
                .anchorDate(anchorDate)
                .title("L7並行起票")
                .build();
    }

    private long countOpenResignationCases(Fixture f) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_lifecycle_case WHERE tenant_id = ? AND engineer_id = ? "
                        + "AND lifecycle_type = 'RESIGNATION' AND status NOT IN ('COMPLETED', 'CANCELLED') AND deleted_flag = 0",
                Long.class, f.tenant(), f.engineer().getId());
        return count == null ? 0L : count;
    }

    private void bindTenant(Fixture f) {
        TenantTestSecurity.bindAs(f.admin().getId(), f.admin().getUsername(), f.tenant(), "管理者");
    }

    private static void clearTenant() {
        SecurityContextHolder.clearContext();
        TestSecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    // ===== concurrency helpers =====

    /** 自分の接続IDを記録してからactionを1 transactionで実行する。戻り値はactionの結果、または失敗した例外。 */
    private Future<Object> worker(ExecutorService pool, Fixture f, AtomicLong connectionId,
                                  AtomicReference<Thread> thread, Supplier<Object> action) {
        return pool.submit(() -> {
            if (thread != null) {
                thread.set(Thread.currentThread());
            }
            bindTenant(f);
            try {
                return new TransactionTemplate(transactionManager).execute(status -> {
                    connectionId.set(currentConnectionId());
                    return action.get();
                });
            } catch (Throwable failure) {
                return failure;
            } finally {
                clearTenant();
            }
        });
    }

    private long currentConnectionId() {
        Long id = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
        return id == null ? 0L : id;
    }

    /**
     * 先行session（自transaction内）で、作成した案件行を一意indexの一意点検索でlocking readする。
     * 一意index列（tenant・生成列）が両方NULLでない一意点検索なので、当該indexの対象行1件にだけ排他record lock
     * （X,REC_NOT_GAP）が付く。取得した行が作成した案件と違う・無い場合は、生成列が要員IDを持っていない
     * （一意indexの対象になっていない）ことを意味するため、行の状態を添えて失敗させる。
     */
    private void requireOwnActiveResignationSlot(Fixture f, Long createdCaseId) {
        List<Long> locked = jdbc.queryForList("SELECT id FROM t_lifecycle_case FORCE INDEX (" + UNIQUE_INDEX + ") "
                        + "WHERE tenant_id = ? AND active_resignation_engineer_id = ? FOR UPDATE",
                Long.class, f.tenant(), f.engineer().getId());
        if (!List.of(createdCaseId).equals(locked)) {
            throw new AssertionError("先行起票の案件行が一意indexの対象として見つからない: created=" + createdCaseId
                    + ", locked=" + locked + ", rows=" + jdbc.queryForList("SELECT id, tenant_id, engineer_id, "
                    + "lifecycle_type, status, deleted_flag, active_resignation_engineer_id FROM t_lifecycle_case "
                    + "WHERE tenant_id = ?", f.tenant()));
        }
    }

    /**
     * 先行sessionが一意indexの排他record lockをちょうど1件保持していることを確認し、そのlock IDを返す（後続sessionを
     * 始める前の前提。InnoDbRecordLockWaits.requireSinglePointLock と同じ考え方で、LOCK_DATAには依存しない）。
     */
    private String requireSingleExclusiveSlotLock(long blockerConnectionId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<String> held = List.of();
        while (System.nanoTime() < deadline) {
            held = grantedExclusiveRecordLockIds(blockerConnectionId);
            if (held.size() == 1) {
                return held.get(0);
            }
            if (held.size() > 1) {
                break;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("先行sessionが一意index " + UNIQUE_INDEX + " の排他record lockをちょうど1件保持していることを"
                + "確認できなかった: count=" + held.size() + "; locks=" + locks() + "; trx=" + transactions());
    }

    /**
     * 後続sessionが先行sessionの案件行で一意indexの重複確認を待っていることを確認する（LOCK_DATAに依存しない）。
     * 毎poll、先行sessionが当該indexで保持する排他record lockが、後続session開始前に確認した1件のまま
     * （lock IDが一致）であることを要求し、待機辺のblocking lockがそのlockであることを確かめる。
     */
    private WaitEdge awaitUniqueIndexWait(Future<Object> waiter, Supplier<Long> requester, Supplier<Long> blocker,
                                          String anchoredLockId, Fixture f, AtomicReference<Thread> waiterThread)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (waiter.isDone()) {
                throw new AssertionError("一意indexの待機を観測する前に後続起票が終了した: outcome=" + waiter.get()
                        + "; locks=" + locks() + "; trx=" + transactions() + "; rows=" + uncommittedCaseRows(f));
            }
            Long requesterId = requester.get();
            Long blockerId = blocker.get();
            if (requesterId != null && blockerId != null && requesterId > 0 && blockerId > 0
                    && !requesterId.equals(blockerId)) {
                List<String> heldLockIds = grantedExclusiveRecordLockIds(blockerId);
                if (heldLockIds.size() > 1 || (heldLockIds.size() == 1 && !heldLockIds.get(0).equals(anchoredLockId))) {
                    throw new AssertionError("先行sessionの一意indexの排他record lockが、後続session開始前に確認したものと"
                            + "変わった: anchored=" + anchoredLockId + ", held=" + heldLockIds + "; locks=" + locks());
                }
                for (WaitEdge edge : waitEdges(requesterId, blockerId)) {
                    if (edge.blockerLockId().equals(anchoredLockId) && edge.structurallyMatches()) {
                        return edge;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("一意index " + UNIQUE_INDEX + " での待機を観測できなかった。requester=" + requester.get()
                + ", blocker=" + blocker.get() + ", anchoredLock=" + anchoredLockId
                + "; edges=" + describeEdges(requester.get()) + "; locks=" + locks() + "; trx=" + transactions()
                + "; rows=" + uncommittedCaseRows(f) + "; waiterStack=" + stackOf(waiterThread.get()));
    }

    /** 失敗時の診断: 実行中transactionの状態と実行中の文（先頭200文字）。 */
    private String transactions() {
        try {
            return jdbc.queryForList("SELECT trx_mysql_thread_id, trx_state, trx_operation_state, trx_rows_locked, "
                    + "trx_rows_modified, LEFT(COALESCE(trx_query, ''), 200) AS query FROM information_schema.innodb_trx "
                    + "ORDER BY trx_mysql_thread_id").toString();
        } catch (RuntimeException failure) {
            return "transaction一覧の取得に失敗: " + failure.getClass().getSimpleName();
        }
    }

    /** 失敗時の診断: 未commitを含むfixture tenantの案件行（READ UNCOMMITTEDの別接続で読む。読取のみ）。 */
    private String uncommittedCaseRows(Fixture f) {
        try (Connection connection = rootConnection(MYSQL.getDatabaseName());
             Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ UNCOMMITTED");
            List<String> rows = new ArrayList<>();
            try (var prepared = connection.prepareStatement("SELECT id, engineer_id, lifecycle_type, status, deleted_flag, "
                    + "active_resignation_engineer_id FROM t_lifecycle_case WHERE tenant_id = ? ORDER BY id")) {
                prepared.setString(1, f.tenant());
                try (var result = prepared.executeQuery()) {
                    while (result.next()) {
                        rows.add(result.getLong(1) + "/" + result.getLong(2) + "/" + result.getString(3) + "/"
                                + result.getString(4) + "/" + result.getInt(5) + "/" + result.getObject(6));
                    }
                }
            }
            return rows.toString();
        } catch (Exception failure) {
            return "案件行の取得に失敗: " + failure.getClass().getSimpleName();
        }
    }

    private static String stackOf(Thread thread) {
        if (thread == null) {
            return "<thread未取得>";
        }
        StringBuilder stack = new StringBuilder(thread.getState().name());
        StackTraceElement[] frames = thread.getStackTrace();
        for (int i = 0; i < Math.min(frames.length, 25); i++) {
            stack.append(" <- ").append(frames[i]);
        }
        return stack.toString();
    }

    /** 先行sessionが一意index上で保持する排他record lock（gap専用・insert intentionは除く）。 */
    private List<String> grantedExclusiveRecordLockIds(long connectionId) {
        return jdbc.query("SELECT dl.ENGINE_LOCK_ID, dl.LOCK_MODE FROM performance_schema.data_locks dl "
                        + "JOIN performance_schema.threads th ON th.THREAD_ID = dl.THREAD_ID "
                        + "WHERE th.PROCESSLIST_ID = ? AND dl.OBJECT_SCHEMA = DATABASE() AND dl.OBJECT_NAME = 't_lifecycle_case' "
                        + "AND dl.INDEX_NAME = ? AND dl.LOCK_TYPE = 'RECORD' AND dl.LOCK_STATUS = 'GRANTED'",
                        (rs, row) -> new String[] {rs.getString(1), rs.getString(2)}, connectionId, UNIQUE_INDEX).stream()
                .filter(lock -> EXCLUSIVE_RECORD_MODES.contains(lock[1]))
                .map(lock -> lock[0])
                .toList();
    }

    /** requesterからblockerへの、t_lifecycle_case上の待機辺（thread対で結ぶ。LOCK_DATAでは結合しない）。 */
    private List<WaitEdge> waitEdges(long requester, long blocker) {
        return jdbc.query("SELECT wt.PROCESSLIST_ID, bt.PROCESSLIST_ID, "
                        + "req.INDEX_NAME, req.LOCK_TYPE, req.LOCK_MODE, req.LOCK_STATUS, req.LOCK_DATA, "
                        + "blk.ENGINE_LOCK_ID, blk.INDEX_NAME, blk.LOCK_TYPE, blk.LOCK_MODE, blk.LOCK_STATUS, blk.LOCK_DATA "
                        + "FROM performance_schema.data_lock_waits w "
                        + "JOIN performance_schema.threads wt ON wt.THREAD_ID = w.REQUESTING_THREAD_ID "
                        + "JOIN performance_schema.threads bt ON bt.THREAD_ID = w.BLOCKING_THREAD_ID "
                        + "JOIN performance_schema.data_locks req "
                        + "ON req.ENGINE = w.ENGINE AND req.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
                        + "JOIN performance_schema.data_locks blk "
                        + "ON blk.ENGINE = w.ENGINE AND blk.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID "
                        + "WHERE wt.PROCESSLIST_ID = ? AND bt.PROCESSLIST_ID = ? "
                        + "AND req.OBJECT_SCHEMA = DATABASE() AND req.OBJECT_NAME = 't_lifecycle_case' "
                        + "AND blk.OBJECT_SCHEMA = DATABASE() AND blk.OBJECT_NAME = 't_lifecycle_case'",
                (rs, row) -> new WaitEdge(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13)),
                requester, blocker);
    }

    private String describeEdges(Long requester) {
        if (requester == null || requester <= 0) {
            return "requester connection id未取得";
        }
        try {
            List<String> described = new ArrayList<>();
            for (WaitEdge edge : jdbc.query("SELECT wt.PROCESSLIST_ID, bt.PROCESSLIST_ID, "
                            + "req.INDEX_NAME, req.LOCK_TYPE, req.LOCK_MODE, req.LOCK_STATUS, req.LOCK_DATA, "
                            + "blk.ENGINE_LOCK_ID, blk.INDEX_NAME, blk.LOCK_TYPE, blk.LOCK_MODE, blk.LOCK_STATUS, blk.LOCK_DATA "
                            + "FROM performance_schema.data_lock_waits w "
                            + "JOIN performance_schema.threads wt ON wt.THREAD_ID = w.REQUESTING_THREAD_ID "
                            + "JOIN performance_schema.threads bt ON bt.THREAD_ID = w.BLOCKING_THREAD_ID "
                            + "JOIN performance_schema.data_locks req "
                            + "ON req.ENGINE = w.ENGINE AND req.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
                            + "JOIN performance_schema.data_locks blk "
                            + "ON blk.ENGINE = w.ENGINE AND blk.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID "
                            + "WHERE wt.PROCESSLIST_ID = ?",
                    (rs, row) -> new WaitEdge(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                            rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13)),
                    requester)) {
                described.add(edge.describe());
            }
            return described.toString();
        } catch (RuntimeException failure) {
            return "待機辺の取得に失敗: " + failure.getClass().getSimpleName();
        }
    }

    private String locks() {
        try {
            return jdbc.query("SELECT CONCAT(th.PROCESSLIST_ID, ':', dl.OBJECT_NAME, ':', COALESCE(dl.INDEX_NAME, 'NULL'), ':', "
                    + "dl.LOCK_TYPE, ':', dl.LOCK_MODE, ':', dl.LOCK_STATUS, ':', COALESCE(dl.LOCK_DATA, 'NULL')) "
                    + "FROM performance_schema.data_locks dl JOIN performance_schema.threads th ON th.THREAD_ID = dl.THREAD_ID "
                    + "WHERE dl.OBJECT_SCHEMA = DATABASE()", rs -> {
                List<String> states = new ArrayList<>();
                while (rs.next()) states.add(rs.getString(1));
                return states.toString();
            });
        } catch (RuntimeException failure) {
            return "lock一覧の取得に失敗: " + failure.getClass().getSimpleName();
        }
    }

    /** {@code data_lock_waits} の1辺。LOCK_DATAは診断表示にだけ使う。 */
    private record WaitEdge(long requester, long blocker,
                            String requesterIndex, String requesterType, String requesterMode,
                            String requesterStatus, String requesterData,
                            String blockerLockId, String blockerIndex, String blockerType, String blockerMode,
                            String blockerStatus, String blockerData) {
        boolean structurallyMatches() {
            return requester != blocker
                    && UNIQUE_INDEX.equals(requesterIndex) && UNIQUE_INDEX.equals(blockerIndex)
                    && "RECORD".equals(requesterType) && "WAITING".equals(requesterStatus)
                    && RECORD_REQUEST_MODES.contains(requesterMode)
                    && "RECORD".equals(blockerType) && "GRANTED".equals(blockerStatus)
                    && EXCLUSIVE_RECORD_MODES.contains(blockerMode);
        }

        String describe() {
            return "requester=" + requester + " " + requesterIndex + "," + requesterType + "," + requesterMode + ","
                    + requesterStatus + ",key=" + requesterData + " -> blocker=" + blocker + " " + blockerIndex + ","
                    + blockerType + "," + blockerMode + "," + blockerStatus + ",key=" + blockerData;
        }
    }

    // ===== preflight / DDL helpers =====

    private Connection rootConnection(String schema) throws SQLException {
        String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema;
        return DriverManager.getConnection(url, "root", MYSQL.getPassword());
    }

    private JsonNode duplicateFinding(Connection connection) throws Exception {
        JsonNode report = new ObjectMapper().readTree(Nf01PreflightTestAccess.activeResignationDuplicatesJson(connection));
        for (JsonNode finding : report.path("findings")) {
            if ("LIFECYCLE_RESIGNATION_ACTIVE_DUPLICATE".equals(finding.path("checkId").asText())) {
                return finding;
            }
        }
        throw new AssertionError("LIFECYCLE_RESIGNATION_ACTIVE_DUPLICATE のfindingがありません: " + report);
    }

    /** classpath上のV187をそのまま実行する（注釈行を除き、文ごとに実行する）。 */
    private void applyV187(Connection connection) throws Exception {
        String script = new ClassPathResource("db/migration/V187__lifecycle_case_active_resignation_unique.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        StringBuilder body = new StringBuilder();
        for (String line : script.split("\n")) {
            if (!line.trim().startsWith("--")) {
                body.append(line).append('\n');
            }
        }
        try (Statement st = connection.createStatement()) {
            for (String statement : body.toString().split(";")) {
                if (!statement.isBlank()) {
                    st.execute(statement);
                }
            }
        }
    }

    private long indexCount(Connection connection, String schema) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 't_lifecycle_case' AND INDEX_NAME = ?")) {
            statement.setString(1, schema);
            statement.setString(2, UNIQUE_INDEX);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    // ===== assertions =====

    private static void awaitLatch(CountDownLatch latch, String label) {
        awaitLatch(latch, label, WAIT_SECONDS);
    }

    private static void awaitLatch(CountDownLatch latch, String label, long seconds) {
        try {
            if (!latch.await(seconds, TimeUnit.SECONDS)) {
                throw new AssertionError(label + " がtimeoutした");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void assertNoLockFailure(String label, Object outcome) {
        if (!(outcome instanceof Throwable failure)) {
            return;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PessimisticLockingFailureException) {
                fail(label + " がlock失敗で終了した（deadlock/lock wait timeout）: " + failure);
            }
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205)) {
                fail(label + " がMySQL " + sql.getErrorCode() + " で終了した: " + failure);
            }
        }
    }

    private static void assertBusinessRejection(String label, Object outcome, int code, String messageKey) {
        assertTrue(outcome instanceof BusinessException, label + " は業務例外で拒否されること: " + outcome);
        BusinessException rejected = (BusinessException) outcome;
        assertEquals(code, rejected.getCode(), label);
        assertEquals(messageKey, rejected.getMessageKey(), label);
    }

    private record Fixture(String tenant, SysUser admin, Engineer engineer, Long templateId) {
    }
}
