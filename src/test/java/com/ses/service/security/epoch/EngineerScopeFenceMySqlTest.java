package com.ses.service.security.epoch;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerAccountingHistory;
import com.ses.entity.SysUser;
import com.ses.mapper.AuthorizationEpochMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerAccountingHistoryMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerSalesService;
import com.ses.service.EngineerService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.LegalEntityContextService;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.InnoDbRecordLockWaits.PointLock;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * M4（NF01細粒度epoch計画 §6.1・§7.4 Demo (1)(4)、W3-16a 要員・link・営業）: 実MySQL 8で、担当営業の割当・解除、
 * account linkの作成・解除、要員の所属組織・会計履歴の変更、要員削除が engineer epoch のanchor（{@code t_engineer}行）を
 * 最初にX lockし、CONFIRMのreader（{@code FOR SHARE}）と両順序で直列化されることを確かめる。
 *
 * <ul>
 *   <li>各writerを reader先行・writer先行の両順序で確かめる（計画 §6.1）。reader先行ではwriterが要員行で待ち、解放後に
 *       対象の要員だけが+1。writer先行ではreaderがcommitを待ち+1を読む。
 *       writerは要員行を最初から排他lockで取る（S→X昇格なし。FK検査が親の別の行に付けるS lockは数えない）。</li>
 *   <li>細粒度: 要員Aと担当営業を共有lockするreaderは、同じ営業を要員Bへ割り当てるwriterを待たせず、Aのepochも変えない
 *       （営業ユーザー行は共有lock、rank 3 S）。</li>
 *   <li>要員削除は外側transactionなしで呼び、宣言どおりREAD_COMMITTEDで待つ。連携ユーザーは status列だけが0になり、
 *       actorとengineerが各+1（§11 F-5）。</li>
 *   <li>並行する利用者削除がcommitした後の紐付けは、共有lock下の読み直しで拒否する（W3-15 review P3-8）。</li>
 *   <li>上限では409で業務更新ごとrollbackし、呼出側が409を握りつぶしてもcommitできない。</li>
 * </ul>
 *
 * <p>計画 §6.1 M4 の「契約の sales_user_id NULL→B でepochが変わらない」は、契約のwriterがepochのanchorに触れないことを
 * {@code ScopeSourceWriterFenceTest} で静的に固定する（契約writerの業務規則の前提が本試験の対象外のため）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class EngineerScopeFenceMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf01_engineer_epoch")
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

    private static final String TENANT = "default";
    private static final long LEGAL_ENTITY_ID = 1L;
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EngineerSalesService engineerSalesService;
    @Autowired private EngineerAccountLinkService engineerAccountLinkService;
    @Autowired private EngineerService engineerService;
    @Autowired private AuthorizationEpochReader reader;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Autowired private EngineerAccountingHistoryMapper engineerAccountingHistoryMapper;
    @Autowired private AuthorizationEpochMapper epochMapper;
    @MockBean private LegalEntityContextService legalEntityContextService;

    private SysUser operator;

    @BeforeEach
    void setUp() {
        reset(legalEntityContextService);
        when(legalEntityContextService.requireCurrentDate()).thenReturn(LocalDate.now());
        when(legalEntityContextService.requireCurrentLegalEntityId()).thenReturn(LEGAL_ENTITY_ID);
        operator = insertUser("管理者", 1);
        bindContexts();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 営業割当のreader先行ではwriterが要員行の解放を待ち対象の要員だけを1進める() throws Exception {
        long target = insertEngineer(null);
        long other = insertEngineer(null);
        long sales = insertUser("営業", 1).getId();
        long otherBefore = engineerEpoch(other);
        long before = engineerEpoch(target);

        EpochFenceMySqlSupport.assertWriterWaitsForReader(jdbc, transactionManager, "t_engineer", this::bindContexts,
                () -> reader.readEngineer(TENANT, target), () -> {
                    engineerSalesService.assign(target, sales, true, null);
                    return null;
                }, "営業割当 reader先行");

        assertEquals(before + 1, engineerEpoch(target));
        assertEquals(otherBefore, engineerEpoch(other), "別の要員は変えない");
    }

    @Test
    void 営業割当のwriter先行ではreaderがcommitを待ち1進んだepochを読む() throws Exception {
        long target = insertEngineer(null);
        long sales = insertUser("営業", 1).getId();
        long before = engineerEpoch(target);

        // 割当insertはFK（engineer_id・sales_user_id）を検査するが、要員行は既にX、営業ユーザー行は既にSで保持している。
        long read = EpochFenceMySqlSupport.assertReaderWaitsForWriter(jdbc, transactionManager, "t_engineer",
                this::bindContexts, () -> engineerSalesService.assign(target, sales, true, null),
                () -> reader.readEngineer(TENANT, target), "営業割当 writer先行");

        assertEquals(before + 1, read);
    }

    @Test
    void 同じ営業を共有lockするreaderは別の要員への営業割当を待たせず要員Aのepochも変えない() throws Exception {
        long engineerA = insertEngineer(null);
        long engineerB = insertEngineer(null);
        long sales = insertUser("営業", 1).getId();
        long beforeA = engineerEpoch(engineerA);
        long beforeB = engineerEpoch(engineerB);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch readerLocked = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        try {
            // CONFIRMのreaderが提出者（担当営業）と要員Aを共有lockしている状態を作る。
            Future<Long> confirmReader = pool.submit(() -> {
                bindContexts();
                return EpochFenceMySqlSupport.readCommitted(transactionManager).execute(status -> {
                    try {
                        reader.readActor(TENANT, sales);
                        return reader.readEngineer(TENANT, engineerA);
                    } finally {
                        readerLocked.countDown();
                        awaitQuietly(releaseReader);
                        status.setRollbackOnly();
                    }
                });
            });
            assertTrue(readerLocked.await(EpochFenceMySqlSupport.WAIT_SECONDS, TimeUnit.SECONDS), "readerがlockを保持する");
            Future<Object> writer = pool.submit(() -> {
                bindContexts();
                return EpochFenceMySqlSupport.required(transactionManager).execute(status -> {
                    engineerSalesService.assign(engineerB, sales, true, null);
                    return null;
                });
            });
            // 営業ユーザー行は共有lock同士で両立し、要員Bの行はreaderがlockしていないため待たない。
            writer.get(EpochFenceMySqlSupport.WAIT_SECONDS, TimeUnit.SECONDS);
            assertFalse(confirmReader.isDone(), "writerはreaderの解放を待たずに完了する");
            releaseReader.countDown();
            assertEquals(beforeA, confirmReader.get(EpochFenceMySqlSupport.WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            releaseReader.countDown();
            pool.shutdownNow();
        }

        assertEquals(beforeA, engineerEpoch(engineerA), "要員Bへの書込みで要員Aのepochは変えない（細粒度）");
        assertEquals(beforeB + 1, engineerEpoch(engineerB));
    }

    @Test
    void 営業解除のreader先行ではwriterが解放を待ち1進める() throws Exception {
        long target = insertEngineer(null);
        long sales = insertUser("営業", 1).getId();
        engineerSalesService.assign(target, sales, true, null);
        Long assignmentId = jdbc.queryForObject("SELECT id FROM t_engineer_sales WHERE engineer_id = ? "
                + "AND released_at IS NULL", Long.class, target);
        long before = engineerEpoch(target);

        EpochFenceMySqlSupport.assertWriterWaitsForReader(jdbc, transactionManager, "t_engineer", this::bindContexts,
                () -> reader.readEngineer(TENANT, target), () -> {
                    engineerSalesService.release(target, assignmentId);
                    return null;
                }, "営業解除 reader先行");

        assertEquals(before + 1, engineerEpoch(target));
        assertNotNull(jdbc.queryForObject("SELECT released_at FROM t_engineer_sales WHERE id = ?", LocalDate.class,
                assignmentId));
    }

    @Test
    void 紐付けのwriter先行ではreaderがcommitを待ち1進んだepochを読む() throws Exception {
        long target = insertEngineer(null);
        long user = insertUser("要員", 1).getId();
        long before = engineerEpoch(target);

        long read = EpochFenceMySqlSupport.assertReaderWaitsForWriter(jdbc, transactionManager, "t_engineer",
                this::bindContexts, () -> engineerAccountLinkService.link(target, user, operator.getId()),
                () -> reader.readEngineer(TENANT, target), "紐付け writer先行");

        assertEquals(before + 1, read);
        assertEquals(1, linkCount(target));
    }

    @Test
    void 紐付け解除のreader先行ではwriterが解放を待ち1進める() throws Exception {
        long target = insertEngineer(null);
        long user = insertUser("要員", 1).getId();
        insertLink(target, user);
        long before = engineerEpoch(target);

        EpochFenceMySqlSupport.assertWriterWaitsForReader(jdbc, transactionManager, "t_engineer", this::bindContexts,
                () -> reader.readEngineer(TENANT, target), () -> {
                    engineerAccountLinkService.unlinkByEngineerId(target);
                    return null;
                }, "紐付け解除 reader先行");

        assertEquals(before + 1, engineerEpoch(target));
        assertEquals(0, linkCount(target));
    }

    @Test
    void 所属組織の変更のreader先行ではwriterが解放を待ち会計履歴と合わせて1進める() throws Exception {
        long from = insertOrganization();
        long to = insertOrganization();
        long target = insertEngineer(null);
        jdbc.update("UPDATE t_engineer SET organization_id = ? WHERE id = ?", from, target);
        engineerAccountingHistoryMapper.insert(EngineerAccountingHistory.builder().engineerId(target)
                .organizationId(from).validFrom(LocalDate.now()).validTo(null).build());
        long before = engineerEpoch(target);
        Engineer moved = Engineer.builder().organizationId(to).build();
        moved.setId(target);
        moved.setVersion(version(target));

        EpochFenceMySqlSupport.assertWriterWaitsForReader(jdbc, transactionManager, "t_engineer", this::bindContexts,
                () -> reader.readEngineer(TENANT, target), () -> engineerService.updateWithStatusGuard(moved),
                "所属組織の変更 reader先行");

        assertEquals(before + 1, engineerEpoch(target), "所属組織と会計履歴の変化で合わせて1回");
        assertEquals(to, jdbc.queryForObject("SELECT organization_id FROM t_engineer_accounting_history "
                + "WHERE engineer_id = ? AND valid_to IS NULL AND deleted_flag = 0", Long.class, target));
    }

    @Test
    void 会計履歴だけが変わる更新のwriter先行ではreaderがcommitを待ち1進んだepochを読む() throws Exception {
        long target = insertEngineer(new BigDecimal("500000"));
        engineerAccountingHistoryMapper.insert(EngineerAccountingHistory.builder().engineerId(target)
                .expectedUnitPrice(new BigDecimal("500000")).validFrom(LocalDate.now()).validTo(null).build());
        long before = engineerEpoch(target);
        Engineer repriced = Engineer.builder().expectedUnitPrice(new BigDecimal("650000")).build();
        repriced.setId(target);
        repriced.setVersion(version(target));

        long read = EpochFenceMySqlSupport.assertReaderWaitsForWriter(jdbc, transactionManager, "t_engineer",
                this::bindContexts, () -> engineerService.updateWithStatusGuard(repriced),
                () -> reader.readEngineer(TENANT, target), "会計履歴の変更 writer先行");

        assertEquals(before + 1, read);
    }

    @Test
    void 要員削除は宣言どおりREAD_COMMITTEDで待ち連携ユーザーのstatusだけを0にしてactorとengineerを各1進める()
            throws Exception {
        long target = insertEngineer(null);
        SysUser linked = insertUser("要員", 1);
        long sales = insertUser("営業", 1).getId();
        insertLink(target, linked.getId());
        engineerSalesService.assign(target, sales, true, null);
        long engineerBefore = engineerEpoch(target);
        long actorBefore = actorEpoch(linked.getId());
        int version = version(target);

        // 外側transactionを作らずに呼び、serviceが宣言した分離レベルのtransactionを実際に始めさせる（E-3）。
        String isolation = EpochFenceMySqlSupport.assertDirectWriterWaitsForReaderAndReadIsolation(jdbc,
                transactionManager, "t_engineer", this::bindContexts, () -> reader.readEngineer(TENANT, target),
                () -> engineerService.removeById(target, version), "要員削除 reader先行");

        assertEquals("READ-COMMITTED", isolation, "要員削除はREAD_COMMITTEDで実行される");
        assertEquals(1, jdbc.queryForObject("SELECT deleted_flag FROM t_engineer WHERE id = ?", Integer.class, target));
        assertEquals(engineerBefore + 1, engineerEpoch(target), "削除・営業解除・紐付け解除の入れ子でも1回");
        assertEquals(actorBefore + 1, actorEpoch(linked.getId()));
        assertEquals(0, linkCount(target));
        SysUser after = sysUserMapper.selectById(linked.getId());
        assertEquals(0, after.getStatus());
        assertEquals(linked.getRole(), after.getRole(), "status以外の列は書き戻さない（F-5）");
        assertEquals(linked.getEmail(), after.getEmail());
        assertEquals(linked.getRealName(), after.getRealName());
    }

    @Test
    void 並行する利用者削除がcommitした後の紐付けは共有lock下で読み直して拒否する() throws Exception {
        long target = insertEngineer(null);
        long user = insertUser("要員", 1).getId();
        long before = engineerEpoch(target);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicLong linkConnection = new AtomicLong();
        try (Connection admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            admin.setAutoCommit(false);
            long adminConnection = connectionIdOf(admin);
            // 利用者削除（rank 3 X → 論理削除）をcommit前で止める。
            try (PreparedStatement lock = admin.prepareStatement("SELECT id FROM sys_user WHERE id = ? FOR UPDATE");
                 PreparedStatement delete = admin.prepareStatement("UPDATE sys_user SET deleted_flag = 1 WHERE id = ?")) {
                lock.setLong(1, user);
                lock.executeQuery().close();
                delete.setLong(1, user);
                assertEquals(1, delete.executeUpdate());
            }
            PointLock anchor = InnoDbRecordLockWaits.requireSinglePointLock(jdbc, adminConnection, "sys_user", "PRIMARY",
                    key -> true, "紐付けと利用者削除");
            Future<Object> linking = pool.submit(() -> {
                bindContexts();
                return EpochFenceMySqlSupport.required(transactionManager).execute(status -> {
                    linkConnection.set(EpochFenceMySqlSupport.connectionId(jdbc));
                    return engineerAccountLinkService.link(target, user, operator.getId());
                });
            });
            InnoDbRecordLockWaits.awaitBlockerAnchoredWait(jdbc, anchor,
                    () -> linkConnection.get() > 0 ? linkConnection.get() : null,
                    EpochFenceMySqlSupport.SHARED_RECORD_MODES, InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES,
                    TimeUnit.SECONDS.toMillis(EpochFenceMySqlSupport.WAIT_SECONDS), () -> {
                        if (linking.isDone()) {
                            fail("紐付けが利用者削除のcommitを待たずに終了した");
                        }
                    }, "紐付けと利用者削除");
            admin.commit();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> linking.get(EpochFenceMySqlSupport.WAIT_SECONDS, TimeUnit.SECONDS));
            BusinessException refused = assertInstanceOf(BusinessException.class, failure.getCause());
            assertEquals("error.engineerAccount.userNotFound", refused.getMessageKey());
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, linkCount(target), "削除済みの利用者へ紐付けない");
        assertEquals(before, engineerEpoch(target));
    }

    @Test
    void engineer_epochが上限なら409で営業割当ごとrollbackし握りつぶしてもcommitできない() {
        long target = insertEngineer(null);
        long sales = insertUser("営業", 1).getId();
        jdbc.update("UPDATE t_engineer SET engineer_scope_epoch = ? WHERE id = ?", Long.MAX_VALUE, target);

        BusinessException error = assertThrows(BusinessException.class, () -> EpochFenceMySqlSupport
                .required(transactionManager)
                .executeWithoutResult(status -> engineerSalesService.assign(target, sales, true, null)));
        assertEquals(409, error.getCode());
        assertEquals(AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY, error.getMessageKey());

        assertThrows(UnexpectedRollbackException.class, () -> EpochFenceMySqlSupport.required(transactionManager)
                .executeWithoutResult(status -> {
                    try {
                        engineerSalesService.assign(target, sales, true, null);
                    } catch (BusinessException swallowed) {
                        // 呼出側が409を握りつぶしても、参加transactionはrollback-onlyのためcommitできない。
                    }
                }));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_engineer_sales WHERE engineer_id = ?",
                Integer.class, target), "割当のinsertは業務更新ごとrollbackされる");
        assertEquals(Long.MAX_VALUE, engineerEpoch(target), "上限の値は変えない");
    }

    // ===== 計画 §6.1 M4「× reader の両順序」の残りの向き（W3-16a review P3-9） =====

    @Test
    void 紐付けのreader先行ではwriterが利用者行の共有lockの後に要員行で待ち1進める() throws Exception {
        long target = insertEngineer(null);
        long user = insertUser("要員", 1).getId();
        long before = engineerEpoch(target);

        // writerは先に連携ユーザー行を共有lockする（readerの要員行S lockとは競合しない）が、紐付け行を書く前に
        // 要員行のX lockで待つ（anchorが子行より先）。
        EpochFenceMySqlSupport.assertWriterWaitsForReader(jdbc, transactionManager, "t_engineer", this::bindContexts,
                () -> reader.readEngineer(TENANT, target),
                () -> engineerAccountLinkService.link(target, user, operator.getId()), "紐付け reader先行");

        assertEquals(before + 1, engineerEpoch(target));
        assertEquals(1, linkCount(target));
    }

    @Test
    void 営業解除のwriter先行ではreaderがcommitを待ち1進んだepochを読む() throws Exception {
        long target = insertEngineer(null);
        long sales = insertUser("営業", 1).getId();
        engineerSalesService.assign(target, sales, true, null);
        Long assignmentId = jdbc.queryForObject("SELECT id FROM t_engineer_sales WHERE engineer_id = ? "
                + "AND released_at IS NULL", Long.class, target);
        long before = engineerEpoch(target);

        long read = EpochFenceMySqlSupport.assertReaderWaitsForWriter(jdbc, transactionManager, "t_engineer",
                this::bindContexts, () -> engineerSalesService.release(target, assignmentId),
                () -> reader.readEngineer(TENANT, target), "営業解除 writer先行");

        assertEquals(before + 1, read);
    }

    @Test
    void 紐付け解除のwriter先行ではreaderがcommitを待ち1進んだepochを読む() throws Exception {
        long target = insertEngineer(null);
        long user = insertUser("要員", 1).getId();
        insertLink(target, user);
        long before = engineerEpoch(target);

        long read = EpochFenceMySqlSupport.assertReaderWaitsForWriter(jdbc, transactionManager, "t_engineer",
                this::bindContexts, () -> engineerAccountLinkService.unlinkByEngineerId(target),
                () -> reader.readEngineer(TENANT, target), "紐付け解除 writer先行");

        assertEquals(before + 1, read);
        assertEquals(0, linkCount(target));
    }

    @Test
    void 所属組織の変更のwriter先行ではreaderがcommitを待ち1進んだepochを読む() throws Exception {
        long from = insertOrganization();
        long to = insertOrganization();
        long target = insertEngineer(null);
        jdbc.update("UPDATE t_engineer SET organization_id = ? WHERE id = ?", from, target);
        engineerAccountingHistoryMapper.insert(EngineerAccountingHistory.builder().engineerId(target)
                .organizationId(from).validFrom(LocalDate.now()).validTo(null).build());
        long before = engineerEpoch(target);
        Engineer moved = Engineer.builder().organizationId(to).build();
        moved.setId(target);
        moved.setVersion(version(target));

        // 要員行のUPDATEはFK（organization_id → m_organization_unit）を検査するが、それは別の表の行へのS lockで数えない。
        long read = EpochFenceMySqlSupport.assertReaderWaitsForWriter(jdbc, transactionManager, "t_engineer",
                this::bindContexts, () -> engineerService.updateWithStatusGuard(moved),
                () -> reader.readEngineer(TENANT, target), "所属組織の変更 writer先行");

        assertEquals(before + 1, read);
    }

    @Test
    void 会計履歴だけが変わる更新のreader先行ではwriterが解放を待ち1進める() throws Exception {
        long target = insertEngineer(new BigDecimal("500000"));
        engineerAccountingHistoryMapper.insert(EngineerAccountingHistory.builder().engineerId(target)
                .expectedUnitPrice(new BigDecimal("500000")).validFrom(LocalDate.now()).validTo(null).build());
        long before = engineerEpoch(target);
        Engineer repriced = Engineer.builder().expectedUnitPrice(new BigDecimal("700000")).build();
        repriced.setId(target);
        repriced.setVersion(version(target));

        EpochFenceMySqlSupport.assertWriterWaitsForReader(jdbc, transactionManager, "t_engineer", this::bindContexts,
                () -> reader.readEngineer(TENANT, target), () -> engineerService.updateWithStatusGuard(repriced),
                "会計履歴の変更 reader先行");

        assertEquals(before + 1, engineerEpoch(target));
    }

    @Test
    void 要員削除のwriter先行ではreaderがcommitを待ち削除済みで1進んだ行を読む() throws Exception {
        long target = insertEngineer(null);
        SysUser linked = insertUser("要員", 1);
        insertLink(target, linked.getId());
        long before = engineerEpoch(target);
        int version = version(target);

        // CONFIRMのreaderは削除済みの要員を409にするため、ここでは同じFOR SHAREの読取をmapperで行い値と削除状態を見る。
        long read = EpochFenceMySqlSupport.assertReaderWaitsForWriter(jdbc, transactionManager, "t_engineer",
                this::bindContexts, () -> engineerService.removeById(target, version), () -> {
                    AuthorizationEpochMapper.EpochRow row = epochMapper.selectEngineerForShare(target, TENANT);
                    assertFalse(row.usable(), "commit後の要員行は論理削除済み（readerは409にする）");
                    return row.getEpoch();
                }, "要員削除 writer先行");

        assertEquals(before + 1, read);
        assertEquals(0, sysUserMapper.selectById(linked.getId()).getStatus());
    }

    // ===== helper =====

    private void bindContexts() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        SecurityContextHolder.getContext().setAuthentication(authentication());
    }

    private Authentication authentication() {
        LoginUser principal = new LoginUser(operator, List.of(new SimpleGrantedAuthority("ROLE_管理者")), TENANT);
        return new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
    }

    private SysUser insertUser(String role, int status) {
        long seq = SEQUENCE.incrementAndGet();
        SysUser user = SysUser.builder().tenantId(TENANT).username("w316a_scope_" + seq).password("pass")
                .realName("W3-16a試験" + seq).email("w316a-" + seq + "@example.com").role(role).status(status).build();
        sysUserMapper.insert(user);
        return sysUserMapper.selectById(user.getId());
    }

    private long insertEngineer(BigDecimal expectedUnitPrice) {
        Engineer engineer = Engineer.builder().tenantId(TENANT).legalEntityId(LEGAL_ENTITY_ID)
                .fullName("W3-16a要員" + SEQUENCE.incrementAndGet()).status("Bench").employmentType("正社員")
                .expectedUnitPrice(expectedUnitPrice).build();
        engineerMapper.insert(engineer);
        return engineer.getId();
    }

    private long insertOrganization() {
        String code = "W316A-" + SEQUENCE.incrementAndGet();
        jdbc.update("INSERT INTO m_organization_unit (legal_entity_id, code, name, type, valid_from, status) "
                + "VALUES (?, ?, ?, '部', '2020-01-01', '有効')", LEGAL_ENTITY_ID, code, "W3-16a組織 " + code);
        return jdbc.queryForObject("SELECT id FROM m_organization_unit WHERE code = ?", Long.class, code);
    }

    private void insertLink(long engineerId, long userId) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId(TENANT);
        link.setEngineerId(engineerId);
        link.setSysUserId(userId);
        engineerAccountLinkMapper.insert(link);
    }

    private int linkCount(long engineerId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM t_engineer_account_link WHERE engineer_id = ?",
                Integer.class, engineerId);
        return count == null ? 0 : count;
    }

    private int version(long engineerId) {
        Integer version = jdbc.queryForObject("SELECT version FROM t_engineer WHERE id = ?", Integer.class, engineerId);
        return version == null ? 0 : version;
    }

    private long engineerEpoch(long engineerId) {
        return jdbc.queryForObject("SELECT engineer_scope_epoch FROM t_engineer WHERE id = ?", Long.class, engineerId);
    }

    private long actorEpoch(long userId) {
        return jdbc.queryForObject("SELECT actor_authz_epoch FROM sys_user WHERE id = ?", Long.class, userId);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(EpochFenceMySqlSupport.WAIT_SECONDS * 3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("解放待ちが時間切れになりました");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long connectionIdOf(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT CONNECTION_ID()");
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
