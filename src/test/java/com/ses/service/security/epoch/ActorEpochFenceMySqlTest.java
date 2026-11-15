package com.ses.service.security.epoch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerChangeRequest;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerCareerMapper;
import com.ses.mapper.EngineerChangeRequestMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerService;
import com.ses.service.EngineerSkillService;
import com.ses.service.NotificationService;
import com.ses.service.SysUserService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.changerequest.EngineerChangeRequestService;
import com.ses.service.impl.EngineerChangeRequestApprovalAdapter;
import com.ses.service.security.PermissionGroupManagementService;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.InnoDbRecordLockWaits.PointLock;
import com.ses.test.MySQLContainer;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * M3（NF01細粒度epoch計画 §6.1・§7.3 Demo (3)(4)(6)、W3-15）: 実MySQL 8で、利用者の状態・role・削除・権限group割当の
 * writerがactor epochのanchor（{@code sys_user}行）を最初にX lockし、CONFIRMのreader（{@code FOR SHARE}）と両順序で
 * 直列化されること、増分の回数・範囲・rollbackを確かめる。
 *
 * <ul>
 *   <li>reader先行・writer先行の両順序で待機し、commit後の値が+1。別の利用者のepochは変わらない（細粒度）。</li>
 *   <li>role変更は入れ子の割当置換と合わせて+1。削除は+1。同じ割当集合の再保存は+0。</li>
 *   <li>REQUIRES_NEWの内側でcommitした増分は外側のrollbackで消えず、外側の増分は外側と一緒にrollbackされる。</li>
 *   <li>上限では409で業務更新ごとrollbackし、呼出側が409を握りつぶしてもcommitできない。</li>
 *   <li>変更申請の承認によるemail反映は、並行して確定した無効化を古い値で書き戻さない（F-7）。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class ActorEpochFenceMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf01_actor_epoch")
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
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SysUserService sysUserService;
    @Autowired private PermissionGroupManagementService permissionGroupManagementService;
    @Autowired private AuthorizationEpochReader reader;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Autowired private EngineerChangeRequestMapper changeRequestMapper;
    @Autowired private EngineerSkillMapper engineerSkillMapper;
    @Autowired private EngineerCareerMapper engineerCareerMapper;
    @Autowired private EngineerAccountLinkService engineerAccountLinkService;

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

    @Test
    void 無効化のreader先行ではwriterが解放を待ち対象だけを1進める() throws Exception {
        SysUser target = insertUser("営業");
        SysUser other = insertUser("営業");
        long before = actorEpoch(target.getId());
        long otherBefore = actorEpoch(other.getId());

        EpochFenceMySqlSupport.assertWriterWaitsForReader(jdbc, transactionManager, "sys_user", this::bindContexts,
                () -> reader.readActor(TENANT, target.getId()), () -> {
                    sysUserService.updateUserStatus(target.getId(), 0, authentication());
                    return null;
                }, "利用者無効化 reader先行");

        assertEquals(before + 1, actorEpoch(target.getId()));
        assertEquals(otherBefore, actorEpoch(other.getId()), "別の利用者は変えない");
        assertEquals(0, status(target.getId()));
    }

    @Test
    void 無効化のwriter先行ではreaderがcommitを待ち1進んだactor_epochを読む() throws Exception {
        SysUser target = insertUser("HR");
        long before = actorEpoch(target.getId());

        long read = EpochFenceMySqlSupport.assertReaderWaitsForWriter(jdbc, transactionManager, "sys_user",
                this::bindContexts, () -> sysUserService.updateUserStatus(target.getId(), 0, authentication()),
                () -> reader.readActor(TENANT, target.getId()), "利用者無効化 writer先行");

        assertEquals(before + 1, read);
    }

    @Test
    void role変更は入れ子の割当置換と合わせて1回だけ進め削除は1進め同じ割当は進めない() {
        SysUser changed = insertUser("営業");
        SysUser deleted = insertUser("HR");
        SysUser same = insertUser("マネージャー");
        sysUserService.updateUser(same.getId(), roleOnly("HR"), authentication());
        long changedBefore = actorEpoch(changed.getId());
        long deletedBefore = actorEpoch(deleted.getId());
        long sameBefore = actorEpoch(same.getId());

        sysUserService.updateUser(changed.getId(), roleOnly("HR"), authentication());
        sysUserService.deleteUser(deleted.getId(), authentication());
        permissionGroupManagementService.replaceAssignments(same.getId(),
                permissionGroupManagementService.assignedGroupIds(same.getId()), authentication());

        assertEquals(changedBefore + 1, actorEpoch(changed.getId()), "role変更と割当置換で合わせて1回");
        assertEquals(deletedBefore + 1, actorEpoch(deleted.getId()));
        assertEquals(sameBefore, actorEpoch(same.getId()), "同じ割当集合の再保存では進めない");
    }

    @Test
    void REQUIRES_NEWの内側でcommitした増分は外側のrollbackで消えない() {
        SysUser outerTarget = insertUser("営業");
        SysUser innerTarget = insertUser("営業");
        long outerBefore = actorEpoch(outerTarget.getId());
        long innerBefore = actorEpoch(innerTarget.getId());
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        EpochFenceMySqlSupport.required(transactionManager).executeWithoutResult(status -> {
            sysUserService.updateUserStatus(outerTarget.getId(), 0, authentication());
            requiresNew.executeWithoutResult(inner ->
                    sysUserService.updateUserStatus(innerTarget.getId(), 0, authentication()));
            status.setRollbackOnly();
        });

        assertEquals(outerBefore, actorEpoch(outerTarget.getId()), "外側の増分は業務更新と一緒にrollbackされる");
        assertEquals(1, status(outerTarget.getId()));
        assertEquals(innerBefore + 1, actorEpoch(innerTarget.getId()), "内側でcommitした増分は残る");
        assertEquals(0, status(innerTarget.getId()));
    }

    @Test
    void actor_epochが上限なら409で業務更新ごとrollbackし握りつぶしてもcommitできない() {
        SysUser target = insertUser("営業");
        jdbc.update("UPDATE sys_user SET actor_authz_epoch = ? WHERE id = ?", Long.MAX_VALUE, target.getId());

        BusinessException error = assertThrows(BusinessException.class, () -> EpochFenceMySqlSupport
                .required(transactionManager)
                .executeWithoutResult(status -> sysUserService.updateUserStatus(target.getId(), 0, authentication())));
        assertEquals(409, error.getCode());
        assertEquals(AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY, error.getMessageKey());

        assertThrows(UnexpectedRollbackException.class, () -> EpochFenceMySqlSupport.required(transactionManager)
                .executeWithoutResult(status -> {
                    try {
                        sysUserService.updateUserStatus(target.getId(), 0, authentication());
                    } catch (BusinessException swallowed) {
                        // 呼出側が409を握りつぶしても、参加transactionはrollback-onlyのためcommitできない。
                    }
                }));

        assertEquals(1, status(target.getId()), "業務更新はrollbackされる");
        assertEquals(Long.MAX_VALUE, actorEpoch(target.getId()), "上限の値は変えない");
    }

    @Test
    void 変更申請のemail反映は並行して確定した無効化を書き戻さない() throws Exception {
        SysUser linked = insertUser("要員");
        Engineer engineer = Engineer.builder().tenantId(TENANT).fullName("W3-15要員" + SEQUENCE.incrementAndGet())
                .status("Bench").employmentType("正社員").build();
        engineerMapper.insert(engineer);
        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId(TENANT);
        link.setEngineerId(engineer.getId());
        link.setSysUserId(linked.getId());
        engineerAccountLinkMapper.insert(link);
        EngineerChangeRequest change = new EngineerChangeRequest();
        change.setEngineerId(engineer.getId());
        change.setRequestType(EngineerChangeRequestService.TYPE_PROFILE);
        change.setPayloadJson("{\"email\":\"approved-address@example.com\"}");
        change.setDiffJson("{}");
        change.setStatus(EngineerChangeRequestService.STATUS_APPLIED);
        change.setVersion(0);
        changeRequestMapper.insert(change);
        // 要員の反映（rank 7、法人contextを要する）はmockにし、連携ユーザー行の扱いだけを実DBで確かめる。
        EngineerChangeRequestApprovalAdapter adapter = new EngineerChangeRequestApprovalAdapter(changeRequestMapper,
                engineerMapper, engineerSkillMapper, engineerCareerMapper, mock(EngineerService.class),
                mock(EngineerSkillService.class), engineerAccountLinkService, mock(NotificationService.class),
                sysUserMapper, new ObjectMapper(), Clock.systemDefaultZone());
        ApprovalRequest request = new ApprovalRequest();
        request.setTargetId(change.getId());
        request.setTargetVersion(adapter.currentVersion(change.getId()));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicLong approverConnection = new AtomicLong();
        try (Connection admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            admin.setAutoCommit(false);
            long adminConnection = connectionIdOf(admin);
            // 管理者の無効化（主キー一点のFOR UPDATEで明示lockにしてから status だけを更新し、commit前で止める）。
            try (PreparedStatement lock = admin.prepareStatement("SELECT id FROM sys_user WHERE id = ? FOR UPDATE");
                 PreparedStatement disable = admin.prepareStatement("UPDATE sys_user SET status = 0 WHERE id = ?")) {
                lock.setLong(1, linked.getId());
                lock.executeQuery().close();
                disable.setLong(1, linked.getId());
                assertEquals(1, disable.executeUpdate());
            }
            PointLock anchor = InnoDbRecordLockWaits.requireSinglePointLock(jdbc, adminConnection, "sys_user", "PRIMARY",
                    key -> true, "email反映");
            Future<Object> approval = pool.submit(() -> {
                bindContexts();
                return EpochFenceMySqlSupport.required(transactionManager).execute(status -> {
                    approverConnection.set(EpochFenceMySqlSupport.connectionId(jdbc));
                    adapter.applyApproved(request);
                    return null;
                });
            });
            InnoDbRecordLockWaits.awaitBlockerAnchoredWait(jdbc, anchor,
                    () -> approverConnection.get() > 0 ? approverConnection.get() : null,
                    InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES, InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES,
                    TimeUnit.SECONDS.toMillis(EpochFenceMySqlSupport.WAIT_SECONDS), () -> {
                        if (approval.isDone()) {
                            fail("承認の反映が無効化のcommitを待たずに終了した");
                        }
                    }, "email反映");
            assertFalse(approval.isDone());
            admin.commit();
            approval.get(EpochFenceMySqlSupport.WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, status(linked.getId()), "並行して確定した無効化を古い値で書き戻さない");
        assertEquals("approved-address@example.com",
                jdbc.queryForObject("SELECT email FROM sys_user WHERE id = ?", String.class, linked.getId()));
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

    private SysUser insertUser(String role) {
        long seq = SEQUENCE.incrementAndGet();
        SysUser user = SysUser.builder().tenantId(TENANT).username("w315_actor_" + seq).password("pass")
                .realName("W3-15試験").email("w315-" + seq + "@example.com").role(role).status(1).build();
        sysUserMapper.insert(user);
        return sysUserMapper.selectById(user.getId());
    }

    private static SysUser roleOnly(String role) {
        SysUser body = new SysUser();
        body.setRole(role);
        body.setStatus(null);
        return body;
    }

    private long actorEpoch(long userId) {
        return jdbc.queryForObject("SELECT actor_authz_epoch FROM sys_user WHERE id = ?", Long.class, userId);
    }

    private int status(long userId) {
        return jdbc.queryForObject("SELECT status FROM sys_user WHERE id = ?", Integer.class, userId);
    }

    private static long connectionIdOf(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT CONNECTION_ID()");
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
