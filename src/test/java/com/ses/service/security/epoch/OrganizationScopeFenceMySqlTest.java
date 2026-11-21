package com.ses.service.security.epoch;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.OrganizationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.InnoDbRecordLockWaits.HeldLock;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M5（NF01細粒度epoch計画 §6.1・§7.4 Demo (2)(3)(5)、W3-16a後半 組織・所属）: 実MySQL 8で、組織階層writer・統合・
 * 所属writerがanchor（組織行・利用者行・要員行）を最初にX lockし、CONFIRMのreader（{@code FOR SHARE}）と両順序で
 * 直列化されること、統合と所属登録の同時実行で循環待ち（1213）にならないこと（§11 F-4）を確かめる。
 *
 * <ul>
 *   <li>親付替・無効化・統合: reader先行ではwriterがreaderの組織行で待ち、解放後に旧・新の祖先鎖だけが+1（無関係な
 *       部分木は不変）。writer先行ではreaderがcommitを待ち+1を読む。</li>
 *   <li>所属writer（登録・上長変更・一括終了）: 本人のactorと連携要員のengineerが+1。reader先行では本人の利用者行または
 *       連携要員の要員行で待つ。上長本人・上長を外された側は不変。</li>
 *   <li>統合は外側transactionなしで呼び、宣言どおりREAD_COMMITTEDで待つ（E-3）。</li>
 *   <li>上限では409で業務更新ごとrollbackし、呼出側が409を握りつぶしてもcommitできない。</li>
 * </ul>
 *
 * <p>待機の判定は {@code performance_schema.data_lock_waits} のrequester/blocker対とlock IDで行い、{@code LOCK_DATA} を
 * 連結条件にしない（{@link EpochFenceMySqlSupport}）。S→X昇格の判定はwriterが排他lockした記録の上の共有lockだけに限り、
 * FK検査が別の行に付けるS lock（例: 所属行のinsertが上長の利用者行に付けるもの）は数えない。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class OrganizationScopeFenceMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_nf01_organization_epoch")
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
    private static final LocalDate VALID_FROM = LocalDate.of(2020, 1, 1);
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrganizationService organizationService;
    @Autowired private AuthorizationEpochReader reader;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Autowired private UserOrganizationMapper userOrganizationMapper;

    private SysUser operator;

    @BeforeEach
    void setUp() {
        operator = insertUser("管理者");
        bindContexts();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    // ===== 組織階層writer =====

    @Test
    void 親付替のreader先行ではwriterが旧親の解放を待ち旧_新の祖先鎖だけを1進める() throws Exception {
        long oldRoot = insertOrganization(null);
        long oldParent = insertOrganization(oldRoot);
        long moved = insertOrganization(oldParent);
        long newRoot = insertOrganization(null);
        long newParent = insertOrganization(newRoot);
        long unrelated = insertOrganization(null);
        List<Long> advanced = List.of(oldRoot, oldParent, moved, newRoot, newParent);
        List<Long> before = organizationEpochs(advanced);
        long unrelatedBefore = organizationEpoch(unrelated);
        OrganizationUnit patch = patchOf(moved, newParent);

        EpochFenceMySqlSupport.assertWriterWaitsForReader(jdbc, transactionManager, "m_organization_unit",
                this::bindContexts, () -> readOrganization(oldParent),
                () -> organizationService.updateOrganization(patch, version(moved)), "親付替 reader先行");

        assertAdvancedByOne(advanced, before);
        assertEquals(unrelatedBefore, organizationEpoch(unrelated), "無関係な部分木は変えない");
        assertEquals(newParent, parentOf(moved));
    }

    @Test
    void 親付替のwriter先行ではreaderが旧親でcommitを待ち1進んだepochを読む() throws Exception {
        long oldRoot = insertOrganization(null);
        long oldParent = insertOrganization(oldRoot);
        long moved = insertOrganization(oldParent);
        long newParent = insertOrganization(null);
        long before = organizationEpoch(oldParent);
        OrganizationUnit patch = patchOf(moved, newParent);
        int version = version(moved);

        long read = assertReaderWaitsForMultiRowWriter("m_organization_unit", oldParent,
                () -> organizationService.updateOrganization(patch, version), () -> readOrganization(oldParent),
                "親付替 writer先行");

        assertEquals(before + 1, read);
    }

    @Test
    void 無効化のreader先行ではwriterが祖先の解放を待ち自身と祖先鎖だけを1進める() throws Exception {
        long root = insertOrganization(null);
        long leaf = insertOrganization(root);
        long unrelated = insertOrganization(null);
        List<Long> advanced = List.of(root, leaf);
        List<Long> before = organizationEpochs(advanced);
        long unrelatedBefore = organizationEpoch(unrelated);
        int version = version(leaf);

        EpochFenceMySqlSupport.assertWriterWaitsForReader(jdbc, transactionManager, "m_organization_unit",
                this::bindContexts, () -> readOrganization(root),
                () -> organizationService.updateStatus(leaf, "無効", version), "無効化 reader先行");

        assertAdvancedByOne(advanced, before);
        assertEquals(unrelatedBefore, organizationEpoch(unrelated));
        assertEquals("無効", jdbc.queryForObject("SELECT status FROM m_organization_unit WHERE id = ?", String.class,
                leaf));
    }

    @Test
    void 無効化のwriter先行ではreaderがcommitを待ち1進んだepochを読む() throws Exception {
        // 親のない組織の無効化は組織行をちょうど1件だけ排他lockする（S→X昇格なしも確かめる）。
        long root = insertOrganization(null);
        long before = organizationEpoch(root);
        int version = version(root);

        long read = EpochFenceMySqlSupport.assertReaderWaitsForWriter(jdbc, transactionManager, "m_organization_unit",
                this::bindContexts, () -> organizationService.updateStatus(root, "無効", version),
                () -> readOrganization(root), "無効化 writer先行");

        assertEquals(before + 1, read);
    }

    @Test
    void 統合のreader先行ではwriterが宣言どおりREAD_COMMITTEDで統合先の解放を待ち関係する行だけを1進める()
            throws Exception {
        MergeFixture f = mergeFixture();
        List<Long> advanced = List.of(f.sourceRoot(), f.source(), f.child(), f.targetRoot(), f.target());
        List<Long> before = organizationEpochs(advanced);
        long unrelatedBefore = organizationEpoch(f.unrelatedOrganization());
        long memberBefore = actorEpoch(f.member());
        long otherUserBefore = actorEpoch(f.otherUser());
        long linkedBefore = engineerEpoch(f.linkedEngineer());
        long inSourceBefore = engineerEpoch(f.engineerInSource());
        long unrelatedEngineerBefore = engineerEpoch(f.unrelatedEngineer());
        int version = version(f.source());

        // 外側transactionを作らずに呼び、serviceが宣言した分離レベルのtransactionを実際に始めさせる（E-3）。
        String isolation = EpochFenceMySqlSupport.assertDirectWriterWaitsForReaderAndReadIsolation(jdbc,
                transactionManager, "m_organization_unit", this::bindContexts, () -> readOrganization(f.target()),
                () -> organizationService.merge(f.source(), f.target(), version), "統合 reader先行");

        assertEquals("READ-COMMITTED", isolation, "統合はREAD_COMMITTEDで実行される");
        assertAdvancedByOne(advanced, before);
        assertEquals(unrelatedBefore, organizationEpoch(f.unrelatedOrganization()));
        assertEquals(memberBefore + 1, actorEpoch(f.member()), "所属が変わる影響ユーザー");
        assertEquals(otherUserBefore, actorEpoch(f.otherUser()));
        assertEquals(linkedBefore + 1, engineerEpoch(f.linkedEngineer()), "影響ユーザーの連携要員");
        assertEquals(inSourceBefore + 1, engineerEpoch(f.engineerInSource()), "統合元所属の要員");
        assertEquals(unrelatedEngineerBefore, engineerEpoch(f.unrelatedEngineer()));
        assertEquals(f.target(), parentOf(f.child()));
        assertEquals(f.target(), jdbc.queryForObject("SELECT organization_id FROM t_engineer WHERE id = ?", Long.class,
                f.engineerInSource()));
    }

    @Test
    void 統合のwriter先行ではreaderが統合先でcommitを待ち1進んだepochを読む() throws Exception {
        MergeFixture f = mergeFixture();
        long before = organizationEpoch(f.target());
        int version = version(f.source());

        long read = assertReaderWaitsForMultiRowWriter("m_organization_unit", f.target(),
                () -> organizationService.merge(f.source(), f.target(), version), () -> readOrganization(f.target()),
                "統合 writer先行");

        assertEquals(before + 1, read);
    }

    // ===== 所属writer =====

    @Test
    void 所属登録のreader先行ではwriterが本人の利用者行で待ち本人actorと連携要員engineerを1進める() throws Exception {
        long organization = insertOrganization(null);
        long user = insertUser("HR").getId();
        long engineer = insertEngineer(null);
        insertLink(engineer, user);
        long otherUser = insertUser("HR").getId();
        long actorBefore = actorEpoch(user);
        long engineerBefore = engineerEpoch(engineer);
        long otherBefore = actorEpoch(otherUser);
        long organizationBefore = organizationEpoch(organization);

        EpochFenceMySqlSupport.assertWriterWaitsForReader(jdbc, transactionManager, "sys_user", this::bindContexts,
                () -> reader.readActor(TENANT, user),
                () -> organizationService.assignUser(assignment(user, organization, null, LocalDate.now(), null)),
                "所属登録 reader先行");

        assertEquals(actorBefore + 1, actorEpoch(user));
        assertEquals(engineerBefore + 1, engineerEpoch(engineer));
        assertEquals(otherBefore, actorEpoch(otherUser), "別の利用者は変えない（細粒度）");
        assertEquals(organizationBefore, organizationEpoch(organization), "所属の変化は組織部分木epochを進めない");
    }

    @Test
    void 所属登録のwriter先行ではreaderが連携要員でcommitを待ち1進んだepochを読む() throws Exception {
        long organization = insertOrganization(null);
        long user = insertUser("HR").getId();
        long engineer = insertEngineer(null);
        insertLink(engineer, user);
        long before = engineerEpoch(engineer);

        // 所属行のinsertはFK（user_id → sys_user、organization_id → m_organization_unit）を検査するが、どちらも先に
        // X lock済みの行で、要員行（anchor）のlockではない。
        long read = EpochFenceMySqlSupport.assertReaderWaitsForWriter(jdbc, transactionManager, "t_engineer",
                this::bindContexts,
                () -> organizationService.assignUser(assignment(user, organization, null, LocalDate.now(), null)),
                () -> reader.readEngineer(TENANT, engineer), "所属登録 writer先行");

        assertEquals(before + 1, read);
    }

    @Test
    void 上長変更のreader先行ではwriterが連携要員で待ち本人actorと連携要員engineerを1進め上長は変えない()
            throws Exception {
        long organization = insertOrganization(null);
        long user = insertUser("HR").getId();
        long engineer = insertEngineer(null);
        insertLink(engineer, user);
        long manager = insertUser("マネージャー").getId();
        long assignmentId = insertAssignment(user, organization, null, 1);
        long actorBefore = actorEpoch(user);
        long engineerBefore = engineerEpoch(engineer);
        long managerBefore = actorEpoch(manager);
        UserOrganization patch = assignmentPatch(assignmentId, organization, manager);

        EpochFenceMySqlSupport.assertWriterWaitsForReader(jdbc, transactionManager, "t_engineer", this::bindContexts,
                () -> reader.readEngineer(TENANT, engineer),
                () -> organizationService.updateUserOrganization(patch, assignmentVersion(assignmentId)),
                "上長変更 reader先行");

        assertEquals(actorBefore + 1, actorEpoch(user));
        assertEquals(engineerBefore + 1, engineerEpoch(engineer), "上長経路は連携要員のengineer epochで覆う");
        assertEquals(managerBefore, actorEpoch(manager), "上長の設定では上長本人を進めない");
        assertEquals(manager, jdbc.queryForObject("SELECT manager_user_id FROM t_user_organization WHERE id = ?",
                Long.class, assignmentId));
    }

    @Test
    void 上長変更のwriter先行ではreaderが本人の利用者行でcommitを待ち1進んだepochを読む() throws Exception {
        // writerは本人をX、上長をSでlockする（上長のS lockは別の記録なのでS→X昇格の判定に数えない）。
        long organization = insertOrganization(null);
        long user = insertUser("HR").getId();
        long manager = insertUser("マネージャー").getId();
        long assignmentId = insertAssignment(user, organization, null, 1);
        long before = actorEpoch(user);
        UserOrganization patch = assignmentPatch(assignmentId, organization, manager);
        int version = assignmentVersion(assignmentId);

        long read = EpochFenceMySqlSupport.assertReaderWaitsForWriter(jdbc, transactionManager, "sys_user",
                this::bindContexts, () -> organizationService.updateUserOrganization(patch, version),
                () -> reader.readActor(TENANT, user), "上長変更 writer先行");

        assertEquals(before + 1, read);
    }

    @Test
    void 一括終了のreader先行ではwriterが本人の利用者行で待ち自分でactorとengineerを進め上長を外された側は変えない()
            throws Exception {
        long organization = insertOrganization(null);
        long leaver = insertUser("HR").getId();
        long engineer = insertEngineer(null);
        insertLink(engineer, leaver);
        long member = insertUser("HR").getId();
        insertAssignment(leaver, organization, null, 1);
        long managed = insertAssignment(member, organization, leaver, 1);
        long leaverBefore = actorEpoch(leaver);
        long engineerBefore = engineerEpoch(engineer);
        long memberBefore = actorEpoch(member);

        EpochFenceMySqlSupport.assertWriterWaitsForReader(jdbc, transactionManager, "sys_user", this::bindContexts,
                () -> reader.readActor(TENANT, leaver),
                () -> organizationService.closeAssignmentsForUser(leaver, LocalDate.now()), "一括終了 reader先行");

        assertEquals(leaverBefore + 1, actorEpoch(leaver), "呼出元に頼らず一括終了自身がactorを進める（W3-15 P3-6）");
        assertEquals(engineerBefore + 1, engineerEpoch(engineer));
        assertEquals(memberBefore, actorEpoch(member), "上長を外された側は進めない（計画 §2.2）");
        assertNull(jdbc.queryForObject("SELECT manager_user_id FROM t_user_organization WHERE id = ?", Long.class,
                managed));
    }

    @Test
    void 一括終了のwriter先行ではreaderが連携要員でcommitを待ち1進んだepochを読む() throws Exception {
        long organization = insertOrganization(null);
        long leaver = insertUser("HR").getId();
        long engineer = insertEngineer(null);
        insertLink(engineer, leaver);
        insertAssignment(leaver, organization, null, 1);
        long before = engineerEpoch(engineer);

        long read = EpochFenceMySqlSupport.assertReaderWaitsForWriter(jdbc, transactionManager, "t_engineer",
                this::bindContexts, () -> organizationService.closeAssignmentsForUser(leaver, LocalDate.now()),
                () -> reader.readEngineer(TENANT, engineer), "一括終了 writer先行");

        assertEquals(before + 1, read);
    }

    // ===== 統合 × 所属登録（§11 F-4） =====

    @Test
    void 所属登録が先にlockした利用者を統合が待ち循環待ちにならず両方成功する() throws Exception {
        ConflictFixture f = conflictFixture();
        assertNoDeadlock(f, true);
    }

    @Test
    void 統合が先にlockした利用者を所属登録が待ち循環待ちにならず両方成功する() throws Exception {
        ConflictFixture f = conflictFixture();
        assertNoDeadlock(f, false);
    }

    // ===== 上限 =====

    @Test
    void 組織部分木epochが上限なら409で状態変更ごとrollbackし握りつぶしてもcommitできない() {
        long root = insertOrganization(null);
        jdbc.update("UPDATE m_organization_unit SET org_scope_epoch = ? WHERE id = ?", Long.MAX_VALUE, root);
        int version = version(root);

        BusinessException error = assertThrows(BusinessException.class, () -> EpochFenceMySqlSupport
                .required(transactionManager)
                .executeWithoutResult(status -> organizationService.updateStatus(root, "無効", version)));
        assertEquals(409, error.getCode());
        assertEquals(AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY, error.getMessageKey());

        assertThrows(UnexpectedRollbackException.class, () -> EpochFenceMySqlSupport.required(transactionManager)
                .executeWithoutResult(status -> {
                    try {
                        organizationService.updateStatus(root, "無効", version);
                    } catch (BusinessException swallowed) {
                        // 呼出側が409を握りつぶしても、参加transactionはrollback-onlyのためcommitできない。
                    }
                }));

        assertEquals("有効", jdbc.queryForObject("SELECT status FROM m_organization_unit WHERE id = ?", String.class,
                root), "状態の更新は業務更新ごとrollbackされる");
        assertEquals(version, version(root));
        assertEquals(Long.MAX_VALUE, organizationEpoch(root), "上限の値は変えない");
    }

    // ===== 同時実行の補助 =====

    /**
     * 統合と所属登録を、片方をcommit前で止めた状態で実行し、もう片方が利用者行（rank 3、両者の最初のlock）で待つこと、
     * 解放後に両方が成功することを確かめる（旧実装は 所属登録: 所属行 → 組織行、統合: 組織行 → 所属行 で循環し得た）。
     */
    private void assertNoDeadlock(ConflictFixture f, boolean assignFirst) throws Exception {
        String label = assignFirst ? "所属登録先行 × 統合" : "統合先行 × 所属登録";
        int version = version(f.source());
        Runnable assign = () -> organizationService.assignUser(
                assignment(f.user(), f.target(), null, LocalDate.now().plusDays(30), null));
        Runnable merge = () -> organizationService.merge(f.source(), f.target(), version);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong firstConnection = new AtomicLong();
        AtomicLong secondConnection = new AtomicLong();
        try {
            Future<Object> first = pool.submit(() -> {
                bindContexts();
                return EpochFenceMySqlSupport.readCommitted(transactionManager).execute(status -> {
                    firstConnection.set(EpochFenceMySqlSupport.connectionId(jdbc));
                    (assignFirst ? assign : merge).run();
                    firstDone.countDown();
                    awaitQuietly(releaseFirst);
                    return null;
                });
            });
            if (!firstDone.await(EpochFenceMySqlSupport.WAIT_SECONDS, TimeUnit.SECONDS) || first.isDone()) {
                fail(label + ": 先行側が書込みを終えてcommit前に止まらなかった: " + outcome(first));
            }
            Future<Object> second = pool.submit(() -> {
                bindContexts();
                return EpochFenceMySqlSupport.readCommitted(transactionManager).execute(status -> {
                    secondConnection.set(EpochFenceMySqlSupport.connectionId(jdbc));
                    (assignFirst ? merge : assign).run();
                    return null;
                });
            });
            InnoDbRecordLockWaits.awaitRequesterAnchoredWait(jdbc,
                    () -> secondConnection.get() > 0 ? secondConnection.get() : null, firstConnection::get,
                    "sys_user", "PRIMARY", InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES,
                    InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES, InnoDbRecordLockWaits.primaryKey(f.user()),
                    TimeUnit.SECONDS.toMillis(EpochFenceMySqlSupport.WAIT_SECONDS), () -> {
                        if (second.isDone()) {
                            fail(label + ": 後続側が利用者行で待たずに終了した: " + outcome(second));
                        }
                    }, label);
            assertFalse(second.isDone(), label + ": 後続側は先行側のcommitまで待つこと");
            releaseFirst.countDown();
            first.get(EpochFenceMySqlSupport.WAIT_SECONDS, TimeUnit.SECONDS);
            second.get(EpochFenceMySqlSupport.WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
        assertEquals(f.target(), jdbc.queryForObject("SELECT merged_into FROM m_organization_unit WHERE id = ?",
                Long.class, f.source()), label + ": 統合が成功する");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_user_organization WHERE user_id = ? "
                + "AND organization_id = ? AND deleted_flag = 0", Integer.class, f.user(), f.target()),
                label + ": 統合で移した所属と、新しく登録した所属の両方が統合先に残る");
    }

    /**
     * writerが組織行を複数排他lockする（祖先鎖・統合）場合のwriter先行: writerはcommit前に止まり、readerが指定の組織行で
     * 待ち、commit後に新しい値を読む。writerが排他lockした記録の上に共有lockを持たないこと（S→X昇格なし）も確かめる。
     */
    private long assertReaderWaitsForMultiRowWriter(String table, long anchorId, Runnable writerCall,
                                                    Supplier<Long> readerCall, String label) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch writerDone = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicLong writerConnection = new AtomicLong();
        AtomicLong readerConnection = new AtomicLong();
        try {
            Future<Object> writer = pool.submit(() -> {
                bindContexts();
                return EpochFenceMySqlSupport.required(transactionManager).execute(status -> {
                    writerConnection.set(EpochFenceMySqlSupport.connectionId(jdbc));
                    writerCall.run();
                    writerDone.countDown();
                    awaitQuietly(releaseWriter);
                    return null;
                });
            });
            if (!writerDone.await(EpochFenceMySqlSupport.WAIT_SECONDS, TimeUnit.SECONDS) || writer.isDone()) {
                fail(label + ": writerが書込みを終えてcommit前に止まらなかった: " + outcome(writer));
            }
            assertNoSharedLockOnExclusiveRecords(writerConnection.get(), table, label);
            Future<Long> readerFuture = pool.submit(() -> {
                bindContexts();
                return EpochFenceMySqlSupport.readCommitted(transactionManager).execute(status -> {
                    readerConnection.set(EpochFenceMySqlSupport.connectionId(jdbc));
                    Long value = readerCall.get();
                    status.setRollbackOnly();
                    return value;
                });
            });
            InnoDbRecordLockWaits.awaitRequesterAnchoredWait(jdbc,
                    () -> readerConnection.get() > 0 ? readerConnection.get() : null, writerConnection::get,
                    table, "PRIMARY", EpochFenceMySqlSupport.SHARED_RECORD_MODES,
                    InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES, InnoDbRecordLockWaits.primaryKey(anchorId),
                    TimeUnit.SECONDS.toMillis(EpochFenceMySqlSupport.WAIT_SECONDS), () -> {
                        if (readerFuture.isDone()) {
                            fail(label + ": readerがwriterのcommitを待たずに終了した: " + outcome(readerFuture));
                        }
                    }, label);
            assertFalse(readerFuture.isDone(), label + ": readerはwriterのcommitまで待つこと");
            releaseWriter.countDown();
            writer.get(EpochFenceMySqlSupport.WAIT_SECONDS, TimeUnit.SECONDS);
            return readerFuture.get(EpochFenceMySqlSupport.WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseWriter.countDown();
            pool.shutdownNow();
        }
    }

    /** writerが排他lockしている各記録の上に、同じwriterの共有lockが無いこと（別の記録のFK検査のS lockは数えない）。 */
    private void assertNoSharedLockOnExclusiveRecords(long writerConnection, String table, String label) {
        List<HeldLock> exclusive = InnoDbRecordLockWaits.grantedRecordLocks(jdbc, writerConnection, table, "PRIMARY",
                InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES);
        assertTrue(!exclusive.isEmpty(), label + ": writerが" + table + "の主キーで排他lockを保持していること");
        List<HeldLock> shared = InnoDbRecordLockWaits.grantedRecordLocks(jdbc, writerConnection, table, "PRIMARY",
                EpochFenceMySqlSupport.SHARED_RECORD_MODES);
        List<String> problems = new ArrayList<>();
        for (HeldLock lock : exclusive) {
            problems.addAll(EpochFenceMySqlSupport.sharedLocksOnAnchorRecord(lock, shared));
        }
        assertEquals(List.of(), problems, label + ": writerはanchor記録を最初から排他lockで取る（S→X昇格なし）");
    }

    // ===== fixture =====

    /** 統合の前提: 統合元（親あり・子あり）、統合先（親あり）、影響ユーザーと連携要員、統合元所属の要員、無関係な行。 */
    private MergeFixture mergeFixture() {
        long sourceRoot = insertOrganization(null);
        long source = insertOrganization(sourceRoot);
        long child = insertOrganization(source);
        long targetRoot = insertOrganization(null);
        long target = insertOrganization(targetRoot);
        long unrelatedOrganization = insertOrganization(null);
        long member = insertUser("HR").getId();
        long otherUser = insertUser("HR").getId();
        long linkedEngineer = insertEngineer(null);
        insertLink(linkedEngineer, member);
        long engineerInSource = insertEngineer(source);
        long unrelatedEngineer = insertEngineer(null);
        insertAssignment(member, source, null, 1);
        insertAssignment(otherUser, unrelatedOrganization, null, 1);
        return new MergeFixture(sourceRoot, source, child, targetRoot, target, unrelatedOrganization, member, otherUser,
                linkedEngineer, engineerInSource, unrelatedEngineer);
    }

    private record MergeFixture(long sourceRoot, long source, long child, long targetRoot, long target,
                                long unrelatedOrganization, long member, long otherUser, long linkedEngineer,
                                long engineerInSource, long unrelatedEngineer) {
    }

    /**
     * 統合 × 所属登録の前提: 利用者は統合元に期間限定（今日+10日まで）の副所属を持ち、所属登録は統合先へ今日+30日から
     * 副所属を追加する（どちらが先でも期間・主所属が重ならず、両方が業務上成功する）。
     */
    private ConflictFixture conflictFixture() {
        long source = insertOrganization(null);
        long target = insertOrganization(null);
        long user = insertUser("HR").getId();
        userOrganizationMapper.insert(assignment(user, source, null, VALID_FROM, LocalDate.now().plusDays(10)));
        return new ConflictFixture(source, target, user);
    }

    private record ConflictFixture(long source, long target, long user) {
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

    private long readOrganization(long organizationId) {
        return reader.readOrganizations(List.of(organizationId)).get(organizationId);
    }

    /** 組織と初版の親子履歴を作る（writerを通さない。各文はautocommit）。 */
    private long insertOrganization(Long parentId) {
        String code = "W316A2-" + SEQUENCE.incrementAndGet();
        jdbc.update("INSERT INTO m_organization_unit (legal_entity_id, code, name, type, parent_id, valid_from, status) "
                + "VALUES (?, ?, ?, '部', ?, ?, '有効')", LEGAL_ENTITY_ID, code, "W3-16a後半組織 " + code, parentId, VALID_FROM);
        long id = jdbc.queryForObject("SELECT id FROM m_organization_unit WHERE code = ?", Long.class, code);
        jdbc.update("INSERT INTO t_organization_relation_history (organization_id, parent_id, status, valid_from) "
                + "VALUES (?, ?, '有効', ?)", id, parentId, VALID_FROM);
        return id;
    }

    private OrganizationUnit patchOf(long organizationId, Long parentId) {
        OrganizationUnit patch = jdbc.queryForObject("SELECT code, name, type, legal_entity_id, valid_from, status "
                        + "FROM m_organization_unit WHERE id = ?", (rs, row) -> OrganizationUnit.builder()
                        .code(rs.getString("code")).name(rs.getString("name")).type(rs.getString("type"))
                        .legalEntityId(rs.getLong("legal_entity_id")).validFrom(rs.getDate("valid_from").toLocalDate())
                        .status(rs.getString("status")).build(), organizationId);
        patch.setId(organizationId);
        patch.setParentId(parentId);
        return patch;
    }

    private SysUser insertUser(String role) {
        long seq = SEQUENCE.incrementAndGet();
        SysUser user = SysUser.builder().tenantId(TENANT).username("w316a2_org_" + seq).password("pass")
                .realName("W3-16a後半試験" + seq).email("w316a2-" + seq + "@example.com").role(role).status(1).build();
        sysUserMapper.insert(user);
        return sysUserMapper.selectById(user.getId());
    }

    private long insertEngineer(Long organizationId) {
        Engineer engineer = Engineer.builder().tenantId(TENANT).legalEntityId(LEGAL_ENTITY_ID)
                .organizationId(organizationId).fullName("W3-16a後半要員" + SEQUENCE.incrementAndGet())
                .status("Bench").employmentType("正社員").build();
        engineerMapper.insert(engineer);
        return engineer.getId();
    }

    private void insertLink(long engineerId, long userId) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId(TENANT);
        link.setEngineerId(engineerId);
        link.setSysUserId(userId);
        engineerAccountLinkMapper.insert(link);
    }

    private long insertAssignment(long userId, long organizationId, Long managerUserId, int primaryFlag) {
        UserOrganization row = assignment(userId, organizationId, managerUserId, VALID_FROM, null);
        row.setPrimaryFlag(primaryFlag);
        userOrganizationMapper.insert(row);
        return row.getId();
    }

    private static UserOrganization assignment(long userId, long organizationId, Long managerUserId,
                                               LocalDate validFrom, LocalDate validTo) {
        return UserOrganization.builder().userId(userId).organizationId(organizationId).managerUserId(managerUserId)
                .primaryFlag(0).validFrom(validFrom).validTo(validTo).version(0).build();
    }

    private static UserOrganization assignmentPatch(long assignmentId, long organizationId, Long managerUserId) {
        UserOrganization patch = UserOrganization.builder().organizationId(organizationId).managerUserId(managerUserId)
                .primaryFlag(1).validFrom(VALID_FROM).build();
        patch.setId(assignmentId);
        return patch;
    }

    private List<Long> organizationEpochs(List<Long> organizationIds) {
        return organizationIds.stream().map(this::organizationEpoch).toList();
    }

    private void assertAdvancedByOne(List<Long> organizationIds, List<Long> before) {
        for (int i = 0; i < organizationIds.size(); i++) {
            assertEquals(before.get(i) + 1, organizationEpoch(organizationIds.get(i)),
                    "組織 " + organizationIds.get(i) + " は1だけ進める");
        }
    }

    private long parentOf(long organizationId) {
        return jdbc.queryForObject("SELECT parent_id FROM m_organization_unit WHERE id = ?", Long.class, organizationId);
    }

    private int version(long organizationId) {
        Integer version = jdbc.queryForObject("SELECT version FROM m_organization_unit WHERE id = ?", Integer.class,
                organizationId);
        return version == null ? 0 : version;
    }

    private int assignmentVersion(long assignmentId) {
        Integer version = jdbc.queryForObject("SELECT version FROM t_user_organization WHERE id = ?", Integer.class,
                assignmentId);
        return version == null ? 0 : version;
    }

    private long organizationEpoch(long organizationId) {
        return jdbc.queryForObject("SELECT org_scope_epoch FROM m_organization_unit WHERE id = ?", Long.class,
                organizationId);
    }

    private long actorEpoch(long userId) {
        return jdbc.queryForObject("SELECT actor_authz_epoch FROM sys_user WHERE id = ?", Long.class, userId);
    }

    private long engineerEpoch(long engineerId) {
        return jdbc.queryForObject("SELECT engineer_scope_epoch FROM t_engineer WHERE id = ?", Long.class, engineerId);
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

    private static String outcome(Future<?> future) {
        if (!future.isDone()) {
            return "running";
        }
        try {
            return "result=" + future.get();
        } catch (Exception e) {
            return "failure=" + e;
        }
    }
}
