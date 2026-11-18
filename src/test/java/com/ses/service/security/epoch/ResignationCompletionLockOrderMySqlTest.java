package com.ses.service.security.epoch;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.dto.portal.PortalUserEngineerLinkDto;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerChangeRequest;
import com.ses.entity.EngineerSales;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.ExternalAccountSystem;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.entity.LifecycleTemplate;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalUser;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerChangeRequestMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.ExternalAccountSystemMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.mapper.PortalOrganizationMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerService;
import com.ses.service.OrganizationService;
import com.ses.service.SysUserService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.changerequest.EngineerChangeRequestService;
import com.ses.service.impl.EngineerChangeRequestApprovalAdapter;
import com.ses.service.lifecycle.LifecycleCaseService;
import com.ses.service.lifecycle.evidence.LifecycleLockExecutor;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.LockKey;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.Mode;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.Rank;
import com.ses.service.portal.PortalEngineerLinkService;
import com.ses.service.provider.ExternalAccountProviderClient;
import com.ses.service.security.LegalEntityContextService;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;

/**
 * M6（NF01細粒度epoch計画 §3.4「completeCase」・§6.1・§7.5 Demo (3)(4)、W3-16b）: 実MySQL 8で、並べ替えた退社完了
 * （3 S{操作者}・X{連携ユーザー} → 4 → 5 → 7 → 8 → 9 → 10 → 14 を1つのplanで1回だけ取る）が、連携ユーザーを要員より先に
 * lockする全writer（3 → 7）と両順序で循環待ち（1213）・lock待ちtimeout（1205）にならず、どちらかが先に確定してから他方が
 * 進む（または依存先の変化を設計どおり409で返す）ことを確かめる。
 *
 * <p>対象のwriter（引継ぎ: G4r review P3-1、W3-16a前半 review P3-1、W3-16a後半 review P2-1）: 紐付け解除（3 S → 7 X）、
 * 要員削除（3 X → 7 X）、所属登録・所属更新・異動（3 X → 6 X → 7 X → 8）、所属解除（3 X → 7 X → 8）、組織統合
 * （3 X → 6 X → 7 X → 8）、利用者の無効化・削除（3 X → 所属close 7 X → 8）、変更申請の承認によるprofile反映
 * （3 X{email} → 6 S → 7 X）。旧実装の退社完了は 4 X → 7 X → 3 X で、いずれとも同じ要員・連携ユーザーで循環し得た。
 * CONFIRMのreaderを模すplan（1 S → 3 S → 4 X → 5 X → 7 S）とも両順序で直列化されることを確かめる。
 * ポータル紐付けの登録（7 X → 12、READ_COMMITTED。W3-02 P2-1(b)）は、退社完了が先行したとき要員行で待ち、lock後の読取で
 * 完了を見て「要確認」を返すことを確かめる（登録はREAD_COMMITTEDの後続側transactionに参加させ、待機を後続側の接続で特定する）。</p>
 *
 * <p>待ちの判定は {@code performance_schema.data_lock_waits} のrequester/blocker対（{@link InnoDbRecordLockWaits}）で行い、
 * {@code LOCK_DATA} を連結条件にしない（見えている場合だけ期待の主キーと照合する）。外部キー検査が別の記録・別の表に付ける
 * S lock（例: eventの {@code actor_user_id}、所属行の上長）は、requesterの単一の待機lockとblockerの対で判定するため
 * 誤って待機の証拠にならない。待ちの対象はどの組合せも既存の行（連携ユーザーの {@code sys_user} 行・案件行）で、先行側は
 * その行を主キー一点の {@code FOR UPDATE}／{@code FOR SHARE}（または主キー一点のUPDATE）で明示lockしている。未commitの
 * insert行の暗黙lock（{@code data_locks.THREAD_ID} が所有者ではなく明示化した待機側になる）に待つ組合せは使わない。
 * 各テストは専用のMySQL container（クラスごと）に行をcommitする。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class ResignationCompletionLockOrderMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_nf01_resignation_lock_order")
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
    private static final AtomicInteger TEMPLATE_VERSION = new AtomicInteger(800_000);
    private static final Set<String> EXCLUSIVE = InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES;
    private static final Set<String> SHARED = EpochFenceMySqlSupport.SHARED_RECORD_MODES;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private LifecycleCaseService caseService;
    @Autowired private EngineerAccountLinkService engineerAccountLinkService;
    @Autowired private EngineerService engineerService;
    @Autowired private OrganizationService organizationService;
    @Autowired private SysUserService sysUserService;
    @Autowired private EngineerChangeRequestApprovalAdapter changeRequestAdapter;
    @Autowired private LifecycleLockExecutor lockExecutor;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Autowired private EngineerSalesMapper engineerSalesMapper;
    @Autowired private UserOrganizationMapper userOrganizationMapper;
    @Autowired private LifecycleTemplateMapper templateMapper;
    @Autowired private LifecycleCaseMapper caseMapper;
    @Autowired private LifecycleTaskMapper taskMapper;
    @Autowired private EngineerChangeRequestMapper changeRequestMapper;
    @Autowired private ExternalAccountSystemMapper externalAccountSystemMapper;
    @Autowired private ExternalAccountReferenceMapper externalAccountReferenceMapper;
    @Autowired private PortalEngineerLinkService portalEngineerLinkService;
    @Autowired private PortalOrganizationMapper portalOrganizationMapper;
    @Autowired private PortalUserMapper portalUserMapper;

    @MockBean private LegalEntityContextService legalEntityContextService;
    @MockBean private ExternalAccountProviderClient providerClient;

    private SysUser operator;

    @BeforeEach
    void setUp() {
        reset(legalEntityContextService, providerClient);
        lenient().when(legalEntityContextService.requireCurrentDate()).thenReturn(LocalDate.now());
        lenient().when(legalEntityContextService.requireCurrentLegalEntityId()).thenReturn(LEGAL_ENTITY_ID);
        lenient().when(legalEntityContextService.requireTenantId()).thenReturn(TENANT);
        lenient().when(providerClient.requestRevoke(any())).thenReturn(true);
        operator = insertUser("管理者");
        bindContexts();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    // ===== 単独の完了（Demo (3)） =====

    @Test
    void 外側transactionなしの退社完了は連携ユーザーと要員の世代を1つずつ進め基準日で閉じ失効要求を同じcommitで登録する() {
        Fixture f = fixture();
        ExternalAccountReference account = externalAccount(f);
        long actorBefore = actorEpoch(f.linkedUser());
        long engineerBefore = engineerEpoch(f.engineer());
        long operatorBefore = actorEpoch(operator.getId());

        caseService.completeCase(f.caseId(), operator.getId());

        assertEquals("COMPLETED", caseStatus(f.caseId()));
        assertEquals(0, userStatus(f.linkedUser()), "連携ユーザーは停止される");
        assertEquals(actorBefore + 1, actorEpoch(f.linkedUser()), "停止と所属の終了で合わせて1回");
        assertEquals(engineerBefore + 1, engineerEpoch(f.engineer()), "所属の終了・紐付け解除・担当営業の解除で合わせて1回");
        assertEquals(operatorBefore, actorEpoch(operator.getId()), "操作者は共有lockだけで世代を変えない");
        assertEquals(LocalDate.now(), jdbc.queryForObject("SELECT valid_to FROM t_user_organization WHERE id = ?",
                LocalDate.class, f.assignment()), "所属の終了日は退社基準日");
        assertEquals(LocalDate.now(), jdbc.queryForObject("SELECT released_at FROM t_engineer_sales WHERE id = ?",
                LocalDate.class, f.salesAssignment()), "担当営業の解除日は退社基準日");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_engineer_account_link WHERE engineer_id = ?",
                Integer.class, f.engineer()));
        assertEquals("lifecycle-resign:" + f.caseId() + ":" + account.getId() + ":g1",
                externalAccountReferenceMapper.selectById(account.getId()).getIdempotencyKey(),
                "外部アカウントの失効要求は完了と同じcommitで登録される");
    }

    @Test
    void 退社完了がrollbackされると停止_所属終了_紐付け解除_営業解除_失効要求とepochの増分が残らない() {
        Fixture f = fixture();
        ExternalAccountReference account = externalAccount(f);
        long actorBefore = actorEpoch(f.linkedUser());
        long engineerBefore = engineerEpoch(f.engineer());

        EpochFenceMySqlSupport.readCommitted(transactionManager).executeWithoutResult(status -> {
            caseService.completeCase(f.caseId(), operator.getId());
            status.setRollbackOnly();
        });

        assertEquals("ACTIVE", caseStatus(f.caseId()));
        assertEquals(1, userStatus(f.linkedUser()));
        assertEquals(actorBefore, actorEpoch(f.linkedUser()));
        assertEquals(engineerBefore, engineerEpoch(f.engineer()));
        assertNull(jdbc.queryForObject("SELECT valid_to FROM t_user_organization WHERE id = ?", LocalDate.class,
                f.assignment()));
        assertNull(jdbc.queryForObject("SELECT released_at FROM t_engineer_sales WHERE id = ?", LocalDate.class,
                f.salesAssignment()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_engineer_account_link WHERE engineer_id = ?",
                Integer.class, f.engineer()));
        assertNull(externalAccountReferenceMapper.selectById(account.getId()).getIdempotencyKey());
    }

    @Test
    void 操作者自身に連携された要員の退社完了は400でlockを取らずに拒否し何も変えない() {
        Fixture f = fixture();
        // 操作者を別の要員へ連携し、その要員の退社案件を操作者が完了しようとする。
        long selfEngineer = insertEngineer(f.sourceOrganization());
        insertLink(selfEngineer, operator.getId());
        long selfCase = insertResignationCase(selfEngineer);

        Throwable outcome = capture(() -> caseService.completeCase(selfCase, operator.getId()));

        assertBusiness(outcome, 400, "error.lifecycle.selfResignationCompletion");
        assertEquals("ACTIVE", caseStatus(selfCase));
        assertEquals(1, userStatus(operator.getId()));
    }

    // ===== 紐付け解除（3 S → 7 X） =====

    @Test
    void 退社完了が先行すると紐付け解除は連携ユーザーの共有lockで待ち解除済みとして成功する() throws Exception {
        Fixture f = fixture();
        Outcomes o = race("退社完了先行 × 紐付け解除", complete(f), readCommitted(() ->
                engineerAccountLinkService.unlinkByEngineerId(f.engineer())), "sys_user", f.linkedUser(), SHARED, EXCLUSIVE);
        assertSucceeded("退社完了", o.first());
        assertSucceeded("紐付け解除", o.second());
        assertCompleted(f);
    }

    // ===== ポータル紐付けの登録（7 X → 12。W3-02 P2-1(b)） =====

    @Test
    void 退社完了が先行するとポータル紐付けの登録は要員行で待ち完了を読んで要確認を返す() throws Exception {
        Fixture f = fixture();
        long portalUser = insertPortalUser();
        AtomicReference<PortalUserEngineerLinkDto> registered = new AtomicReference<>();

        // 登録は READ_COMMITTED の外側transaction（race の後続側）に参加させ、待機辺を後続側の接続IDで特定する
        // （FINAL-FIX P3-4: 任意のrequesterを許すと、別の接続の待ちを登録の待ちと取り違え得る）。register の宣言
        // （isolation=READ_COMMITTED）は WriterIsolationPolicyTest と PortalEngineerLinkServiceLockOrderTest が固定する
        Outcomes o = race("退社完了先行 × ポータル紐付け登録", complete(f),
                readCommitted(() -> registered.set(portalEngineerLinkService.register(portalUser, f.engineer()))),
                "t_engineer", f.engineer(), EXCLUSIVE, EXCLUSIVE);

        assertSucceeded("退社完了", o.first());
        assertSucceeded("ポータル紐付け登録", o.second());
        assertCompleted(f);
        assertTrue(registered.get().isEngineerResigned(), "lock待ちの間にcommitされた退社完了を読み「要確認」を返す");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log WHERE application_code = "
                + "'PORTAL_ENGINEER_LINK_REGISTERED' AND reference_id = ? AND after_state LIKE '%engineerResigned=true%'",
                Integer.class, registered.get().getId()), "監査にも要確認が残る");
    }

    @Test
    void 紐付け解除が先行すると退社完了は連携ユーザーで待ち消えた紐付けを依存先の変化として409にする() throws Exception {
        Fixture f = fixture();
        Outcomes o = race("紐付け解除先行 × 退社完了", readCommitted(() ->
                engineerAccountLinkService.unlinkByEngineerId(f.engineer())), complete(f), "sys_user", f.linkedUser(),
                EXCLUSIVE, SHARED);
        assertSucceeded("紐付け解除", o.first());
        assertBusiness(o.second(), 409, "error.common.optimisticLock");
        assertEquals("ACTIVE", caseStatus(f.caseId()));
    }

    // ===== 要員削除（3 X → 7 X） =====

    @Test
    void 退社完了が先行すると要員削除は連携ユーザーで待ち紐付けの変化を409にする() throws Exception {
        Fixture f = fixture();
        int version = engineerVersion(f.engineer());
        Outcomes o = race("退社完了先行 × 要員削除", complete(f), readCommitted(() ->
                engineerService.removeById(f.engineer(), version)), "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("退社完了", o.first());
        assertBusiness(o.second(), 409, "error.common.optimisticLock");
        assertCompleted(f);
    }

    @Test
    void 要員削除が先行すると退社完了は連携ユーザーで待ち消えた紐付けを409にする() throws Exception {
        Fixture f = fixture();
        int version = engineerVersion(f.engineer());
        Outcomes o = race("要員削除先行 × 退社完了", readCommitted(() -> engineerService.removeById(f.engineer(), version)),
                complete(f), "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("要員削除", o.first());
        assertBusiness(o.second(), 409, "error.common.optimisticLock");
        assertEquals(1, jdbc.queryForObject("SELECT deleted_flag FROM t_engineer WHERE id = ?", Integer.class,
                f.engineer()));
    }

    // ===== 所属登録（3 X → 6 X → 7 X → 8） =====

    @Test
    void 退社完了が先行すると所属登録は連携ユーザーで待ちlock失敗にならない() throws Exception {
        Fixture f = fixture();
        Outcomes o = race("退社完了先行 × 所属登録", complete(f), readCommitted(() -> organizationService.assignUser(
                        assignment(f.linkedUser(), f.targetOrganization(), LocalDate.now().plusDays(30), 0))),
                "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("退社完了", o.first());
        assertSucceededOrBusiness("所属登録", o.second());
        assertCompleted(f);
    }

    @Test
    void 所属登録が先行すると退社完了は連携ユーザーで待ち増えた所属行を依存先の変化として409にする() throws Exception {
        Fixture f = fixture();
        Outcomes o = race("所属登録先行 × 退社完了", readCommitted(() -> organizationService.assignUser(
                        assignment(f.linkedUser(), f.targetOrganization(), LocalDate.now().plusDays(30), 0))),
                complete(f), "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("所属登録", o.first());
        assertBusiness(o.second(), 409, "error.common.optimisticLock");
        assertEquals("ACTIVE", caseStatus(f.caseId()));
    }

    // ===== 所属更新（上長の変更。3 X・S{上長} → 6 X → 7 X → 8） =====

    @Test
    void 退社完了が先行すると所属更新は連携ユーザーで待ち古い版を拒否する() throws Exception {
        Fixture f = fixture();
        int version = assignmentVersion(f.assignment());
        Outcomes o = race("退社完了先行 × 所属更新", complete(f), readCommitted(() ->
                        organizationService.updateUserOrganization(managerPatch(f), version)),
                "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("退社完了", o.first());
        assertSucceededOrBusiness("所属更新", o.second());
        assertCompleted(f);
    }

    @Test
    void 所属更新が先行すると退社完了は連携ユーザーで待ち同じ所属行のまま完了する() throws Exception {
        Fixture f = fixture();
        int version = assignmentVersion(f.assignment());
        Outcomes o = race("所属更新先行 × 退社完了", readCommitted(() ->
                        organizationService.updateUserOrganization(managerPatch(f), version)), complete(f),
                "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("所属更新", o.first());
        assertSucceeded("退社完了", o.second());
        assertCompleted(f);
    }

    // ===== 異動（3 X → 6 X{旧・新} → 7 X → 8） =====

    @Test
    void 退社完了が先行すると異動は連携ユーザーで待ちlock失敗にならない() throws Exception {
        Fixture f = fixture();
        int version = assignmentVersion(f.assignment());
        Outcomes o = race("退社完了先行 × 異動", complete(f), readCommitted(() -> organizationService.transferUser(
                        assignment(f.linkedUser(), f.targetOrganization(), LocalDate.now().plusDays(1), 1), version)),
                "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("退社完了", o.first());
        assertSucceededOrBusiness("異動", o.second());
        assertCompleted(f);
    }

    @Test
    void 異動が先行すると退社完了は連携ユーザーで待ち増えた所属行を409にする() throws Exception {
        Fixture f = fixture();
        int version = assignmentVersion(f.assignment());
        Outcomes o = race("異動先行 × 退社完了", readCommitted(() -> organizationService.transferUser(
                        assignment(f.linkedUser(), f.targetOrganization(), LocalDate.now().plusDays(1), 1), version)),
                complete(f), "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("異動", o.first());
        assertBusiness(o.second(), 409, "error.common.optimisticLock");
        assertEquals("ACTIVE", caseStatus(f.caseId()));
    }

    // ===== 所属解除（3 X → 7 X → 8） =====

    @Test
    void 退社完了が先行すると所属解除は連携ユーザーで待ち古い版を409にする() throws Exception {
        Fixture f = fixture();
        int version = assignmentVersion(f.assignment());
        Outcomes o = race("退社完了先行 × 所属解除", complete(f), readCommitted(() ->
                        organizationService.releaseAssignment(f.assignment(), LocalDate.now(), version)),
                "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("退社完了", o.first());
        assertBusiness(o.second(), 409, "error.organization.versionConflict");
        assertCompleted(f);
    }

    @Test
    void 所属解除が先行すると退社完了は連携ユーザーで待ち同じ所属行のまま完了する() throws Exception {
        Fixture f = fixture();
        int version = assignmentVersion(f.assignment());
        Outcomes o = race("所属解除先行 × 退社完了", readCommitted(() ->
                        organizationService.releaseAssignment(f.assignment(), LocalDate.now(), version)), complete(f),
                "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("所属解除", o.first());
        assertSucceeded("退社完了", o.second());
        assertCompleted(f);
    }

    // ===== 組織統合（3 X → 6 X → 7 X → 8） =====

    @Test
    void 退社完了が先行すると組織統合は連携ユーザーで待ちlock失敗にならない() throws Exception {
        Fixture f = fixture();
        int version = organizationVersion(f.sourceOrganization());
        Outcomes o = race("退社完了先行 × 組織統合", complete(f), readCommitted(() ->
                        organizationService.merge(f.sourceOrganization(), f.targetOrganization(), version)),
                "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("退社完了", o.first());
        assertSucceededOrBusiness("組織統合", o.second());
        assertCompleted(f);
    }

    @Test
    void 組織統合が先行すると退社完了は連携ユーザーで待ちlock失敗にならない() throws Exception {
        Fixture f = fixture();
        int version = organizationVersion(f.sourceOrganization());
        Outcomes o = race("組織統合先行 × 退社完了", readCommitted(() ->
                        organizationService.merge(f.sourceOrganization(), f.targetOrganization(), version)), complete(f),
                "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("組織統合", o.first());
        // 統合が所属行を付け替えた場合は依存先の変化として409、同じ行のまま移した場合は完了する（どちらも設計どおり）。
        assertSucceededOrBusiness("退社完了", o.second());
    }

    // ===== 利用者の無効化（3 X → 所属close 7 X → 8） =====

    @Test
    void 退社完了が先行すると利用者の無効化は連携ユーザーで待ち停止済みとして成功する() throws Exception {
        Fixture f = fixture();
        Outcomes o = race("退社完了先行 × 無効化", complete(f), readCommitted(() ->
                        sysUserService.updateUserStatus(f.linkedUser(), 0, authentication())),
                "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("退社完了", o.first());
        assertSucceeded("無効化", o.second());
        assertCompleted(f);
    }

    @Test
    void 利用者の無効化が先行すると退社完了は連携ユーザーで待ち同じ所属行のまま完了する() throws Exception {
        Fixture f = fixture();
        Outcomes o = race("無効化先行 × 退社完了", readCommitted(() ->
                        sysUserService.updateUserStatus(f.linkedUser(), 0, authentication())), complete(f),
                "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("無効化", o.first());
        assertSucceeded("退社完了", o.second());
        assertCompleted(f);
    }

    // ===== 利用者の削除（3 X → 連携中は拒否 / 解除後は所属close） =====

    @Test
    void 退社完了が先行すると利用者の削除は連携ユーザーで待ち紐付け解除後として削除できる() throws Exception {
        Fixture f = fixture();
        Outcomes o = race("退社完了先行 × 削除", complete(f), readCommitted(() ->
                        sysUserService.deleteUser(f.linkedUser(), authentication())),
                "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("退社完了", o.first());
        assertSucceeded("削除", o.second());
        assertEquals(1, jdbc.queryForObject("SELECT deleted_flag FROM sys_user WHERE id = ?", Integer.class,
                f.linkedUser()));
    }

    @Test
    void 利用者の削除が先行し連携中として拒否されるまで退社完了は連携ユーザーで待ち解放後に完了する() throws Exception {
        Fixture f = fixture();
        // 削除は連携中のため400で拒否されるが、外側transactionの終わりまで利用者行のX lockを保持する。
        Outcomes o = race("削除先行 × 退社完了", readCommitted(() ->
                        sysUserService.deleteUser(f.linkedUser(), authentication())), complete(f),
                "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertBusiness(o.first(), 400, "error.engineerAccount.linkedUserDelete");
        assertSucceeded("退社完了", o.second());
        assertCompleted(f);
    }

    // ===== 変更申請の承認（profile。2 X → 3 X{email} → 6 S → 7 X） =====

    @Test
    void 退社完了が先行すると変更申請の反映は連携ユーザーで待ち古い要員の版を409にする() throws Exception {
        Fixture f = fixture();
        ApprovalRequest request = profileChange(f);
        Outcomes o = race("退社完了先行 × 変更申請の反映", complete(f), required(() ->
                changeRequestAdapter.applyApproved(request)), "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("退社完了", o.first());
        assertBusiness(o.second(), 409, "error.common.optimisticLock");
        assertCompleted(f);
    }

    @Test
    void 変更申請の反映が先行すると退社完了は連携ユーザーで待ち反映後に完了する() throws Exception {
        Fixture f = fixture();
        ApprovalRequest request = profileChange(f);
        Outcomes o = race("変更申請の反映先行 × 退社完了", required(() -> changeRequestAdapter.applyApproved(request)),
                complete(f), "sys_user", f.linkedUser(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("変更申請の反映", o.first());
        assertSucceeded("退社完了", o.second());
        assertCompleted(f);
        assertEquals("resign-approved@example.com",
                jdbc.queryForObject("SELECT email FROM sys_user WHERE id = ?", String.class, f.linkedUser()));
    }

    // ===== CONFIRMのreader（1 S → 3 S → 4 X → 5 X → 7 S）× 退社完了 =====

    @Test
    void readerが先行すると退社完了は案件行で待ち解放後に完了する() throws Exception {
        Fixture f = fixture();
        long engineerBefore = engineerEpoch(f.engineer());
        Outcomes o = race("reader先行 × 退社完了", reader(f, new AtomicLong()), complete(f), "t_lifecycle_case",
                f.caseId(), EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("reader", o.first());
        assertSucceeded("退社完了", o.second());
        assertEquals(engineerBefore + 1, engineerEpoch(f.engineer()));
    }

    @Test
    void 退社完了が先行するとreaderは案件行で待ちcommit後に1進んだengineer_epochを読む() throws Exception {
        Fixture f = fixture();
        long engineerBefore = engineerEpoch(f.engineer());
        AtomicLong read = new AtomicLong(-1);
        Outcomes o = race("退社完了先行 × reader", complete(f), reader(f, read), "t_lifecycle_case", f.caseId(),
                EXCLUSIVE, EXCLUSIVE);
        assertSucceeded("退社完了", o.first());
        assertSucceeded("reader", o.second());
        assertEquals(engineerBefore + 1, read.get(), "readerはcommit後の値を読む");
    }

    // ===== 同時実行の補助 =====

    /** 1つのtransactionで実行する手順（{@code template} はworker threadで作る）。 */
    private record Step(Supplier<TransactionTemplate> template, Runnable action) {
    }

    /** 先行側・後続側の結果（成功は {@code null}）。 */
    private record Outcomes(Throwable first, Throwable second) {
    }

    private Step complete(Fixture f) {
        // 案件完了は宣言どおりREAD_COMMITTEDで動く（外側transactionもRCにして待ちの観測用に止める）。
        return readCommitted(() -> caseService.completeCase(f.caseId(), operator.getId()));
    }

    private Step readCommitted(Runnable action) {
        return new Step(() -> EpochFenceMySqlSupport.readCommitted(transactionManager), action);
    }

    private Step required(Runnable action) {
        return new Step(() -> EpochFenceMySqlSupport.required(transactionManager), action);
    }

    /** CONFIRMのconfirm attemptと同じrankを取るreader（W3-21a前の模擬）。lock後に要員のepochを通常読取で読む。 */
    private Step reader(Fixture f, AtomicLong read) {
        return readCommitted(() -> {
            List<LockKey> keys = new ArrayList<>();
            keys.add(LockKey.global(Mode.SHARED));
            keys.add(LockKey.shared(Rank.SYS_USER, TENANT, f.salesUser()));
            keys.add(LockKey.exclusive(Rank.LIFECYCLE_CASE, TENANT, f.caseId()));
            for (LifecycleTask task : taskMapper.selectByCaseId(f.caseId())) {
                keys.add(LockKey.exclusive(Rank.LIFECYCLE_TASK, TENANT, task.getId()));
            }
            keys.add(LockKey.shared(Rank.ENGINEER, TENANT, f.engineer()));
            lockExecutor.acquire(LifecycleLockPlan.from(keys));
            read.set(engineerEpoch(f.engineer()));
        });
    }

    /**
     * 先行側を自身のtransactionで実行してcommit前に止め（業務例外で終わった場合もrollback-onlyにしてlockを保持する）、
     * 後続側が {@code table} の主キー {@code rowId} で先行側を待つことを確かめてから先行側を解放し、両方の結果を返す。
     * どちらの結果も1213・1205であってはならない。
     */
    private Outcomes race(String label, Step first, Step second, String table, long rowId,
                          Set<String> requestModes, Set<String> blockerModes) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong firstConnection = new AtomicLong();
        AtomicLong secondConnection = new AtomicLong();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        try {
            Future<Throwable> firstFuture = pool.submit(() -> capture(() -> {
                bindContexts();
                first.template().get().executeWithoutResult(status -> {
                    firstConnection.set(EpochFenceMySqlSupport.connectionId(jdbc));
                    try {
                        first.action().run();
                    } catch (RuntimeException failure) {
                        firstFailure.set(failure);
                        status.setRollbackOnly();
                    }
                    firstDone.countDown();
                    awaitQuietly(releaseFirst);
                });
            }));
            if (!firstDone.await(EpochFenceMySqlSupport.WAIT_SECONDS, TimeUnit.SECONDS) || firstFuture.isDone()) {
                fail(label + ": 先行側がcommit前で止まらなかった: " + outcome(firstFuture));
            }
            Future<Throwable> secondFuture = pool.submit(() -> capture(() -> {
                bindContexts();
                second.template().get().executeWithoutResult(status -> {
                    secondConnection.set(EpochFenceMySqlSupport.connectionId(jdbc));
                    second.action().run();
                });
            }));
            InnoDbRecordLockWaits.awaitRequesterAnchoredWait(jdbc,
                    () -> secondConnection.get() > 0 ? secondConnection.get() : null,
                    () -> firstConnection.get() > 0 ? firstConnection.get() : null,
                    table, "PRIMARY", requestModes, blockerModes, InnoDbRecordLockWaits.primaryKey(rowId),
                    TimeUnit.SECONDS.toMillis(EpochFenceMySqlSupport.WAIT_SECONDS), () -> {
                        if (secondFuture.isDone()) {
                            fail(label + ": 後続側が" + table + "#" + rowId + "で待たずに終了した: " + outcome(secondFuture));
                        }
                    }, label);
            assertFalse(secondFuture.isDone(), label + ": 後続側は先行側のcommitまで待つこと");
            releaseFirst.countDown();
            Throwable firstOutcome = firstFuture.get(EpochFenceMySqlSupport.WAIT_SECONDS, TimeUnit.SECONDS);
            Throwable secondOutcome = secondFuture.get(EpochFenceMySqlSupport.WAIT_SECONDS, TimeUnit.SECONDS);
            Throwable firstResult = firstOutcome != null ? firstOutcome : firstFailure.get();
            assertNoLockFailure(label + "（先行側）", firstResult);
            assertNoLockFailure(label + "（後続側）", secondOutcome);
            return new Outcomes(firstResult, secondOutcome);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    private static Throwable capture(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    /**
     * 結果がlock失敗（1213・1205）でないこと。rollbackの失敗で {@link TransactionSystemException} に覆われた場合、元の例外は
     * {@link TransactionSystemException#getApplicationException()} にだけ残るため、cause chainと合わせて調べる。
     */
    private static void assertNoLockFailure(String label, Throwable outcome) {
        List<Throwable> pending = new ArrayList<>();
        java.util.Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (outcome != null) {
            pending.add(outcome);
        }
        while (!pending.isEmpty()) {
            Throwable cause = pending.remove(pending.size() - 1);
            if (cause == null || !visited.add(cause)) {
                continue;
            }
            if (cause instanceof PessimisticLockingFailureException) {
                fail(label + " がlock失敗（deadlock・lock待ちtimeout）で終了した: " + outcome);
            }
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205)) {
                fail(label + " がMySQL " + sql.getErrorCode() + " で終了した: " + outcome);
            }
            if (cause instanceof TransactionSystemException system) {
                pending.add(system.getApplicationException());
            }
            pending.add(cause.getCause());
        }
    }

    private static void assertSucceeded(String label, Throwable outcome) {
        if (outcome != null) {
            throw new AssertionError(label + " が失敗した: " + outcome, outcome);
        }
    }

    private static void assertBusiness(Throwable outcome, int code, String messageKey) {
        assertTrue(outcome instanceof BusinessException, "業務例外で終わること: " + outcome);
        BusinessException rejected = (BusinessException) outcome;
        assertEquals(code, rejected.getCode(), String.valueOf(rejected.getMessageKey()));
        assertEquals(messageKey, rejected.getMessageKey());
    }

    /** 後続側が先行側の確定を見て業務上成功するか、業務例外（400・404・409）で拒否するか（どちらもlock失敗ではない）。 */
    private static void assertSucceededOrBusiness(String label, Throwable outcome) {
        if (outcome == null) {
            return;
        }
        assertTrue(outcome instanceof BusinessException, label + " は成功または業務例外で終わること: " + outcome);
        int code = ((BusinessException) outcome).getCode();
        assertTrue(code == 400 || code == 404 || code == 409, label + " の業務例外のcode: " + code);
    }

    private void assertCompleted(Fixture f) {
        assertEquals("COMPLETED", caseStatus(f.caseId()));
        assertEquals(0, userStatus(f.linkedUser()), "連携ユーザーは停止される");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_engineer_account_link WHERE engineer_id = ?",
                Integer.class, f.engineer()), "紐付けは解除される");
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

    // ===== fixture =====

    /**
     * 退社完了の前提: 統合元組織に主所属を持つ連携ユーザー（要員role）と連携要員、現任の担当営業、退社ゲートを通す完了済み
     * タスク（貸与物返却・文書保管）を持つACTIVEの退社案件、統合先の組織。
     */
    private record Fixture(long caseId, long engineer, long linkedUser, long assignment, long salesUser,
                           long salesAssignment, long sourceOrganization, long targetOrganization) {
    }

    private Fixture fixture() {
        long source = insertOrganization();
        long target = insertOrganization();
        long linkedUser = insertUser("要員").getId();
        long salesUser = insertUser("営業").getId();
        long engineer = insertEngineer(source);
        insertLink(engineer, linkedUser);
        UserOrganization row = assignment(linkedUser, source, VALID_FROM, 1);
        userOrganizationMapper.insert(row);
        EngineerSales sales = EngineerSales.builder().engineerId(engineer).salesUserId(salesUser).primaryFlag(1)
                .assignedAt(VALID_FROM).build();
        engineerSalesMapper.insert(sales);
        long caseId = insertResignationCase(engineer);
        return new Fixture(caseId, engineer, linkedUser, row.getId(), salesUser, sales.getId(), source, target);
    }

    private long insertResignationCase(long engineerId) {
        long seq = SEQUENCE.incrementAndGet();
        LifecycleTemplate template = LifecycleTemplate.builder().templateType("RESIGNATION").name("W3-16b M6 " + seq)
                .versionNo(TEMPLATE_VERSION.incrementAndGet()).status("ACTIVE").validFrom(LocalDate.now().minusDays(1))
                .createdBy(operator.getId()).updatedBy(operator.getId()).build();
        templateMapper.insert(template);
        LifecycleCase lcCase = LifecycleCase.builder().tenantId(TENANT).caseNo("LC-W316B-" + seq)
                .lifecycleType("RESIGNATION").engineerId(engineerId).templateId(template.getId())
                .templateVersion(template.getVersionNo()).anchorDate(LocalDate.now()).status("ACTIVE")
                .title("W3-16b M6").applicantUserId(operator.getId()).engineerSnapshotJson("{}")
                .createdBy(operator.getId()).updatedBy(operator.getId()).build();
        caseMapper.insert(lcCase);
        for (String code : List.of("RESIGN_ASSET_RETURN", "RESIGN_DOC_RETENTION")) {
            taskMapper.insert(LifecycleTask.builder().caseId(lcCase.getId()).taskCode(code).taskName(code)
                    .dueDate(LocalDate.now().plusDays(7)).assigneeRole("HR").isMandatory(1).isBlocking(1)
                    .evidenceType("NONE").isEngineerVisible(1).status("COMPLETED")
                    .createdBy(operator.getId()).updatedBy(operator.getId()).build());
        }
        return lcCase.getId();
    }

    private ExternalAccountReference externalAccount(Fixture f) {
        ExternalAccountSystem system = ExternalAccountSystem.builder().systemCode("W316B-" + SEQUENCE.incrementAndGet())
                .systemName("W3-16b検証").systemType("IDP").build();
        externalAccountSystemMapper.insert(system);
        ExternalAccountReference account = ExternalAccountReference.builder().tenantId(TENANT)
                .legalEntityId(LEGAL_ENTITY_ID).systemId(system.getId()).assigneeType("ENGINEER")
                .assigneeId(f.engineer()).status("ACTIVE").accountIdentifier("w316b-synthetic@example.invalid").build();
        externalAccountReferenceMapper.insert(account);
        return account;
    }

    /** 連携要員のemail変更（profile）の承認済み申請。承認engineと同じく申請行の後に反映する。 */
    private ApprovalRequest profileChange(Fixture f) {
        EngineerChangeRequest change = new EngineerChangeRequest();
        change.setEngineerId(f.engineer());
        change.setRequestType(EngineerChangeRequestService.TYPE_PROFILE);
        change.setPayloadJson("{\"email\":\"resign-approved@example.com\"}");
        change.setDiffJson("{}");
        change.setStatus(EngineerChangeRequestService.STATUS_APPLIED);
        change.setVersion(0);
        changeRequestMapper.insert(change);
        ApprovalRequest request = new ApprovalRequest();
        request.setTargetId(change.getId());
        request.setTargetVersion(changeRequestAdapter.currentVersion(change.getId()));
        return request;
    }

    private UserOrganization managerPatch(Fixture f) {
        UserOrganization patch = UserOrganization.builder().organizationId(f.sourceOrganization())
                .managerUserId(f.salesUser()).primaryFlag(1).validFrom(VALID_FROM).build();
        patch.setId(f.assignment());
        return patch;
    }

    private static UserOrganization assignment(long userId, long organizationId, LocalDate validFrom, int primaryFlag) {
        return UserOrganization.builder().tenantId(TENANT).userId(userId).organizationId(organizationId)
                .primaryFlag(primaryFlag).validFrom(validFrom).version(0).build();
    }

    private void bindContexts() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        SecurityContextHolder.getContext().setAuthentication(authentication());
    }

    private Authentication authentication() {
        LoginUser principal = new LoginUser(operator, List.of(new SimpleGrantedAuthority("ROLE_管理者")), TENANT);
        return new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
    }

    /** 組織と初版の親子履歴を作る（writerを通さない。各文はautocommit）。 */
    private long insertOrganization() {
        String code = "W316B-" + SEQUENCE.incrementAndGet();
        jdbc.update("INSERT INTO m_organization_unit (legal_entity_id, code, name, type, parent_id, valid_from, status) "
                + "VALUES (?, ?, ?, '部', NULL, ?, '有効')", LEGAL_ENTITY_ID, code, "W3-16b組織 " + code, VALID_FROM);
        long id = jdbc.queryForObject("SELECT id FROM m_organization_unit WHERE code = ?", Long.class, code);
        jdbc.update("INSERT INTO t_organization_relation_history (organization_id, parent_id, status, valid_from) "
                + "VALUES (?, NULL, '有効', ?)", id, VALID_FROM);
        return id;
    }

    private SysUser insertUser(String role) {
        long seq = SEQUENCE.incrementAndGet();
        SysUser user = SysUser.builder().tenantId(TENANT).username("w316b_m6_" + seq).password("pass")
                .realName("W3-16b試験" + seq).email("w316b-" + seq + "@example.com").role(role).status(1).build();
        sysUserMapper.insert(user);
        return sysUserMapper.selectById(user.getId());
    }

    private long insertEngineer(Long organizationId) {
        Engineer engineer = Engineer.builder().tenantId(TENANT).legalEntityId(LEGAL_ENTITY_ID)
                .organizationId(organizationId).fullName("W3-16b要員" + SEQUENCE.incrementAndGet())
                .status("Bench").employmentType("正社員").build();
        engineerMapper.insert(engineer);
        return engineer.getId();
    }

    /** 操作者のtenantの顧客組織に属するポータルユーザー（紐付けは持たない）。各文はautocommit。 */
    private long insertPortalUser() {
        long seq = SEQUENCE.incrementAndGet();
        String customer = "W302-PEL-" + seq;
        jdbc.update("INSERT INTO m_customer (company_name, tenant_id) VALUES (?, ?)", customer, TENANT);
        long customerId = jdbc.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, customer);
        PortalOrganization org = new PortalOrganization();
        org.setType("CUSTOMER");
        org.setCustomerId(customerId);
        org.setTenantId(TENANT);
        org.setStatus("ACTIVE");
        portalOrganizationMapper.insert(org);
        PortalUser user = new PortalUser();
        user.setPortalOrgId(org.getId());
        user.setEmail("w302-pel-" + seq + "@example.test");
        user.setDisplayName("W3-02紐付け検証");
        user.setPasswordHash("not-a-login-hash");
        user.setStatus("ACTIVE");
        user.setMfaPolicy("REQUIRED");
        portalUserMapper.insert(user);
        return user.getId();
    }

    private void insertLink(long engineerId, long userId) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId(TENANT);
        link.setEngineerId(engineerId);
        link.setSysUserId(userId);
        engineerAccountLinkMapper.insert(link);
    }

    private String caseStatus(long caseId) {
        return jdbc.queryForObject("SELECT status FROM t_lifecycle_case WHERE id = ?", String.class, caseId);
    }

    private int userStatus(long userId) {
        return jdbc.queryForObject("SELECT status FROM sys_user WHERE id = ?", Integer.class, userId);
    }

    private int engineerVersion(long engineerId) {
        Integer version = jdbc.queryForObject("SELECT version FROM t_engineer WHERE id = ?", Integer.class, engineerId);
        return version == null ? 0 : version;
    }

    private int assignmentVersion(long assignmentId) {
        Integer version = jdbc.queryForObject("SELECT version FROM t_user_organization WHERE id = ?", Integer.class,
                assignmentId);
        return version == null ? 0 : version;
    }

    private int organizationVersion(long organizationId) {
        Integer version = jdbc.queryForObject("SELECT version FROM m_organization_unit WHERE id = ?", Integer.class,
                organizationId);
        return version == null ? 0 : version;
    }

    private long actorEpoch(long userId) {
        return jdbc.queryForObject("SELECT actor_authz_epoch FROM sys_user WHERE id = ?", Long.class, userId);
    }

    private long engineerEpoch(long engineerId) {
        return jdbc.queryForObject("SELECT engineer_scope_epoch FROM t_engineer WHERE id = ?", Long.class, engineerId);
    }
}
