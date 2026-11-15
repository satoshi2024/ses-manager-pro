package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.dto.report.ReportScopeSnapshot;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerSales;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.RoleMenu;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.MenuCacheService;
import com.ses.service.SystemConfigService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.lifecycle.LifecycleScopeService;
import com.ses.service.security.ExplicitActorAuthorizationEvaluator.ExplicitActor;
import com.ses.service.security.epoch.AuthorizationConfigKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U6（NF01細粒度epoch計画 §4.1・§6.2、W3-15）: {@link ExplicitActorAuthorizationEvaluator} が、SecurityContextに同じ利用者を
 * 置いたときの既存サービス（LifecycleScopeService・OrganizationScopeService・DataScopeService・AuthorizationService・
 * MenuCacheService）と同じ結果になること、およびSecurityContext・config cache・MenuCacheService・ReportScopeContextを
 * 読まないことを固定する。
 *
 * <p>5 role × 営業の担当データ限定（ON/OFF）× 組織scope（有効/無効）の20通りで比べる。fixtureは試験transaction内で作り
 * rollbackする。集合の比較はこの試験が作った行のIDに限る（共有H2の他の行に依存しない）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
// 組織scopeの有効化flag・config cache・menu cacheのTTLという共有singletonの状態を試験中だけ変えるため、並列実行させない。
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class ExplicitActorAuthorizationEvaluatorParityTest {

    private static final String TENANT = "default";
    private static final LocalDate AS_OF = LocalDate.now();
    private static final List<String> ROLES = List.of("管理者", "HR", "マネージャー", "営業", "要員");
    private static final List<String> ACTION_KEYS = List.of("lifecycle.view", "lifecycle.update", "engineer.view",
            "engineer.delete", "user.view", "payroll.view", "contract.cost.view", "privacy.view",
            "one-on-one.confidential", "my.view", "permission.manage", "profile.view");
    private static final List<String> MENU_KEYS = List.of("dashboard", "engineer", "user", "customer", "contract");

    @Autowired private ExplicitActorAuthorizationEvaluator evaluator;
    @Autowired private LifecycleScopeService lifecycleScopeService;
    @Autowired private OrganizationScopeService organizationScopeService;
    @Autowired private DataScopeService dataScopeService;
    @Autowired private AuthorizationService authorizationService;
    @Autowired private MenuCacheService menuCacheService;
    @Autowired private com.ses.config.MenuPermissionFilter menuPermissionFilter;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private EngineerSalesMapper engineerSalesMapper;
    @Autowired private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Autowired private OrganizationUnitMapper organizationUnitMapper;
    @Autowired private UserOrganizationMapper userOrganizationMapper;
    @Autowired private com.ses.mapper.RoleMenuMapper roleMenuMapper;
    @Autowired private JdbcTemplate jdbc;

    private boolean originalOrganizationScope;
    private Fixture fixture;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        originalOrganizationScope = (Boolean) ReflectionTestUtils.getField(organizationScopeTarget(),
                "organizationScopeEnabled");
        menuCacheService.invalidate();
        fixture = new Fixture();
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(organizationScopeTarget(), "organizationScopeEnabled", originalOrganizationScope);
        resetConfigCache();
        menuCacheService.invalidate();
        AccountingTenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    static Stream<Arguments> combinations() {
        List<Arguments> arguments = new ArrayList<>();
        for (String role : ROLES) {
            for (boolean salesOwnDataOnly : List.of(false, true)) {
                for (boolean organizationScope : List.of(false, true)) {
                    arguments.add(Arguments.of(role, salesOwnDataOnly, organizationScope));
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0}_sales{1}_org{2}")
    @MethodSource("combinations")
    void 明示actorの判定はSecurityContextに同じ利用者を置いた既存サービスの判定と一致する(
            String role, boolean salesOwnDataOnly, boolean organizationScope) {
        setSalesOwnDataOnly(salesOwnDataOnly);
        ReflectionTestUtils.setField(organizationScopeTarget(), "organizationScopeEnabled", organizationScope);
        long userId = fixture.users.get(role);
        Authentication authentication = authenticate(userId);
        ExplicitActor actor = evaluator.resolveActor(TENANT, userId, AS_OF).orElseThrow();
        assertEquals(role, actor.role());

        Map<String, Object> expected = existingDecisions(authentication, userId);
        SecurityContextHolder.clearContext();
        Map<String, Object> actual = explicitDecisions(actor);

        assertEquals(expected, actual, "role=" + role + " sales=" + salesOwnDataOnly + " org=" + organizationScope);
    }

    @Test
    void 明示actorの判定はSecurityContextとReportScopeContextの内容に左右されない() {
        setSalesOwnDataOnly(true);
        ReflectionTestUtils.setField(organizationScopeTarget(), "organizationScopeEnabled", true);
        ExplicitActor manager = evaluator.resolveActor(TENANT, fixture.users.get("マネージャー"), AS_OF).orElseThrow();
        SecurityContextHolder.clearContext();
        Map<String, Object> baseline = explicitDecisions(manager);

        authenticate(fixture.users.get("管理者"));
        assertEquals(baseline, explicitDecisions(manager), "別の利用者のSecurityContextを読まない");

        ReportScopeSnapshot companyWide = new ReportScopeSnapshot();
        companyWide.setCompanyWide(true);
        Map<String, Object> underSavedScope = ReportScopeContext.with(companyWide, () -> explicitDecisions(manager));
        assertEquals(baseline, underSavedScope, "保存済みscope（ReportScopeContext）を読まない");
        assertTrue(ReportScopeContext.with(companyWide, organizationScopeService::hasFullAccess),
                "前提: 既存サービスは保存済みscopeを優先する");
        assertFalse(evaluator.hasOrganizationFullAccess(manager));
    }

    @Test
    void 認可configはcacheではなくDBの現在値を読む() {
        setSalesOwnDataOnly(false);
        authenticate(fixture.users.get("営業"));
        assertFalse(dataScopeService.isSalesDataScoped(), "前提: cacheへfalseを読み込む");

        // afterCommitのcache失効を経ずにDBだけを変える（cacheは古い値のまま）。
        writeSalesOwnDataOnly("true");
        assertFalse(dataScopeService.isSalesDataScoped(), "前提: 既存サービスはcacheの古い値を返す");
        assertTrue(evaluator.isSalesOwnDataOnly(), "明示actorの判定はDBの現在値を読む");
        ExplicitActor sales = evaluator.resolveActor(TENANT, fixture.users.get("営業"), AS_OF).orElseThrow();
        assertTrue(evaluator.isDataScoped(sales));
    }

    @Test
    void role_menuはMenuCacheServiceではなくDBの現在値を読む() {
        ExplicitActor sales = evaluator.resolveActor(TENANT, fixture.users.get("営業"), AS_OF).orElseThrow();
        String menuKey = jdbc.queryForList("SELECT m.menu_key FROM t_role_menu rm JOIN m_menu m ON m.id = rm.menu_id "
                + "WHERE rm.role = '営業' ORDER BY m.menu_key", String.class).get(0);
        // test profileはTTL 0（cacheしない）のため、この試験の間だけ本番相当のTTLにする（tearDownで失効、finallyで戻す）。
        Object menuCacheTarget = AopTestUtils.getUltimateTargetObject(menuCacheService);
        Object originalTtl = ReflectionTestUtils.getField(menuCacheTarget, "ttlMs");
        ReflectionTestUtils.setField(menuCacheTarget, "ttlMs", 600_000L);
        try {
            assertStaleMenuCacheIsNotUsed(sales, menuKey);
        } finally {
            ReflectionTestUtils.setField(menuCacheTarget, "ttlMs", originalTtl);
            menuCacheService.invalidate();
        }
    }

    private void assertStaleMenuCacheIsNotUsed(ExplicitActor sales, String menuKey) {
        menuCacheService.invalidate();
        assertTrue(menuCacheService.getMenuKeysByRole("営業").contains(menuKey), "前提: cacheへ読み込む");

        // 共有H2では同じmenu_keyの行が複数あり得るため、keyに当たる全menu IDの許可を外す。
        // MyBatis経由で削除する（同じtransactionのMyBatis一次cacheを無効にし、MenuCacheServiceのcacheだけを古いまま残す）。
        List<Long> menuIds = jdbc.queryForList("SELECT id FROM m_menu WHERE menu_key = ?", Long.class, menuKey);
        int deleted = roleMenuMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RoleMenu>()
                .eq(RoleMenu::getRole, "営業").in(RoleMenu::getMenuId, menuIds));
        assertTrue(deleted > 0, "前提: 営業の許可行を外す");
        assertTrue(jdbc.queryForList("SELECT m.menu_key FROM t_role_menu rm JOIN m_menu m ON m.id = rm.menu_id "
                + "WHERE rm.role = '営業'", String.class).stream().noneMatch(menuKey::equals), "前提: DBから外れている");
        assertTrue(menuCacheService.getMenuKeysByRole("営業").contains(menuKey), "前提: cacheは古い値のまま");
        assertFalse(evaluator.isMenuAllowed(sales, menuKey), "明示actorの判定はDBの現在値を読む");
    }

    @Test
    void 無効_削除_tenant不一致_未知roleの利用者は明示actorにしない() {
        long disabled = fixture.insertUser("営業", 0);
        long other = fixture.insertUser("HR", 1);
        jdbc.update("UPDATE sys_user SET tenant_id = 'other-tenant' WHERE id = ?", other);
        long deleted = fixture.insertUser("HR", 1);
        jdbc.update("UPDATE sys_user SET deleted_flag = 1 WHERE id = ?", deleted);

        assertTrue(evaluator.resolveActor(TENANT, disabled, AS_OF).isEmpty());
        assertTrue(evaluator.resolveActor(TENANT, other, AS_OF).isEmpty());
        assertTrue(evaluator.resolveActor(TENANT, deleted, AS_OF).isEmpty());
        assertTrue(evaluator.resolveActor(null, fixture.users.get("HR"), AS_OF).isEmpty());
        ExplicitActor admin = evaluator.resolveActor(" " + TENANT + " ", fixture.users.get("管理者"), AS_OF).orElseThrow();
        assertEquals(TENANT, admin.tenantId(), "tenantはDBの値を使う");
    }

    /**
     * routeの到達可否は {@code MenuPermissionFilter} と同じ（W3-15 review P3-1）。portalの拒否、action keyを導けない内部APIの
     * 拒否と認証基盤の例外、menu判定対象外pathの素通し、画面pathのmenu由来action、管理者の迂回を5 roleで対照する。
     */
    @Test
    void routeの到達可否はMenuPermissionFilterと5roleで一致する() throws Exception {
        Map<String, Boolean> expected = new LinkedHashMap<>();
        Map<String, Boolean> actual = new LinkedHashMap<>();
        for (String role : ROLES) {
            long userId = fixture.users.get(role);
            ExplicitActor actor = evaluator.resolveActor(TENANT, userId, AS_OF).orElseThrow();
            for (String[] route : ROUTES) {
                String key = role + " " + route[0] + " " + route[1];
                authenticate(userId);
                expected.put(key, filterAllows(route[0], route[1]));
                SecurityContextHolder.clearContext();
                actual.put(key, evaluator.isRouteAllowed(actor, route[0], route[1]));
            }
        }
        assertEquals(expected, actual, "MenuPermissionFilterとroute判定が一致すること");
        assertFalse(expected.get("管理者 GET /api/portal/me"), "前提: 管理者でもportal APIは拒否される");
        assertFalse(expected.get("管理者 GET /portal/home"), "前提: 管理者でもportal画面は拒否される");
        assertTrue(expected.get("営業 GET /api/notifications"), "前提: menu判定対象外のpathは素通しになる");
    }

    /** route対照表（method, アプリ内path）。 */
    private static final List<String[]> ROUTES = List.of(
            new String[] {"GET", "/api/engineers"},
            new String[] {"DELETE", "/api/engineers/1"},
            new String[] {"GET", "/api/users"},
            new String[] {"GET", "/api/customers"},
            new String[] {"GET", "/api/contracts"},
            new String[] {"POST", "/api/lifecycle/cases"},
            new String[] {"GET", "/api/privacy/dsar"},
            new String[] {"GET", "/api/portal"},
            new String[] {"GET", "/api/portal/me"},
            new String[] {"GET", "/portal"},
            new String[] {"GET", "/portal/home"},
            new String[] {"GET", "/api/security/sessions"},
            new String[] {"GET", "/api/security/mfa/status"},
            new String[] {"POST", "/api/security/mfa/1/reset"},
            new String[] {"GET", "/api/notifications"},
            new String[] {"GET", "/dashboard"},
            new String[] {"GET", "/engineer/list"},
            new String[] {"GET", "/user/list"},
            new String[] {"GET", "/css/common.css"},
            new String[] {"GET", "/"});

    /** SecurityContextに置いた利用者で {@code MenuPermissionFilter} を通し、後続のchainへ進んだかを返す。 */
    private boolean filterAllows(String method, String uri) throws Exception {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest(method, uri);
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        org.springframework.mock.web.MockFilterChain chain = new org.springframework.mock.web.MockFilterChain();
        menuPermissionFilter.doFilter(request, response, chain);
        return chain.getRequest() != null && response.getStatus() != 403;
    }

    // ===== 判定の収集 =====

    private Map<String, Object> existingDecisions(Authentication authentication, long userId) {
        Map<String, Object> decisions = new LinkedHashMap<>();
        SysUser currentUser = sysUserMapper.selectById(userId);
        decisions.put("org.fullAccess", organizationScopeService.hasFullAccess());
        decisions.put("org.organizations", fixture.ownOrganizations(organizationScopeService.allowedOrganizationIds(AS_OF)));
        decisions.put("org.directUsers", fixture.ownUsers(organizationScopeService.allowedDirectUserIds(AS_OF)));
        decisions.put("org.engineers", fixture.ownEngineers(organizationScopeService.allowedEngineerIds(AS_OF)));
        decisions.put("data.scoped", dataScopeService.isScoped());
        decisions.put("data.engineers", fixture.ownEngineers(dataScopeService.allowedEngineerIds(AS_OF)));
        for (Engineer engineer : fixture.engineers) {
            LifecycleCase lcCase = fixture.caseOf(engineer);
            decisions.put("case." + engineer.getFullName(), lifecycleScopeService.canViewCase(currentUser, lcCase, engineer));
            for (LifecycleTask task : fixture.tasksFor(userId)) {
                String key = engineer.getFullName() + "/" + task.getTaskCode();
                decisions.put("task.visible." + key, lifecycleScopeService.isTaskVisibleToUser(currentUser, task));
                decisions.put("task.edit." + key, outcome(() ->
                        lifecycleScopeService.assertCanEditTask(currentUser, task, lcCase, engineer)));
            }
        }
        for (String actionKey : ACTION_KEYS) {
            decisions.put("action." + actionKey, authorizationService.isAllowed(authentication, actionKey));
        }
        for (String menuKey : MENU_KEYS) {
            decisions.put("menu." + menuKey, "管理者".equals(currentUser.getRole())
                    || menuCacheService.getMenuKeysByRole(currentUser.getRole()).contains(menuKey));
        }
        return decisions;
    }

    private Map<String, Object> explicitDecisions(ExplicitActor actor) {
        Map<String, Object> decisions = new LinkedHashMap<>();
        decisions.put("org.fullAccess", evaluator.hasOrganizationFullAccess(actor));
        decisions.put("org.organizations", fixture.ownOrganizations(evaluator.allowedOrganizationIds(actor)));
        decisions.put("org.directUsers", fixture.ownUsers(evaluator.allowedDirectUserIds(actor)));
        decisions.put("org.engineers", fixture.ownEngineers(evaluator.organizationAllowedEngineerIds(actor)));
        decisions.put("data.scoped", evaluator.isDataScoped(actor));
        decisions.put("data.engineers", fixture.ownEngineers(evaluator.dataScopeAllowedEngineerIds(actor)));
        for (Engineer engineer : fixture.engineers) {
            LifecycleCase lcCase = fixture.caseOf(engineer);
            decisions.put("case." + engineer.getFullName(), evaluator.canViewCase(actor, lcCase, engineer));
            for (LifecycleTask task : fixture.tasksFor(actor.userId())) {
                String key = engineer.getFullName() + "/" + task.getTaskCode();
                decisions.put("task.visible." + key, evaluator.isTaskVisible(actor, task));
                decisions.put("task.edit." + key, outcome(() -> evaluator.assertCanEditTask(actor, task, lcCase, engineer)));
            }
        }
        for (String actionKey : ACTION_KEYS) {
            decisions.put("action." + actionKey, evaluator.isActionAllowed(actor, actionKey));
        }
        for (String menuKey : MENU_KEYS) {
            decisions.put("menu." + menuKey, evaluator.isMenuAllowed(actor, menuKey));
        }
        return decisions;
    }

    private static String outcome(Runnable check) {
        try {
            check.run();
            return "ALLOW";
        } catch (BusinessException e) {
            return e.getCode() + ":" + e.getMessageKey();
        }
    }

    // ===== 設定・context =====

    private Object organizationScopeTarget() {
        return AopTestUtils.getUltimateTargetObject(organizationScopeService);
    }

    private void setSalesOwnDataOnly(boolean value) {
        writeSalesOwnDataOnly(Boolean.toString(value));
        resetConfigCache();
    }

    private void writeSalesOwnDataOnly(String value) {
        int updated = jdbc.update("UPDATE m_system_config SET config_value = ? WHERE config_key = ?",
                value, AuthorizationConfigKeys.SALES_OWN_DATA_ONLY);
        if (updated == 0) {
            jdbc.update("INSERT INTO m_system_config (config_key, config_value, description) VALUES (?, ?, ?)",
                    AuthorizationConfigKeys.SALES_OWN_DATA_ONLY, value, "U6試験");
        }
    }

    /** SystemConfigServiceImplのcacheを空にする（試験transactionはcommitしないためafterCommitの失効が起きない）。 */
    private void resetConfigCache() {
        Object target = AopTestUtils.getUltimateTargetObject(systemConfigService);
        Object cache = ReflectionTestUtils.getField(target, "cache");
        if (cache instanceof ConcurrentHashMap<?, ?> map) {
            map.clear();
        }
        ReflectionTestUtils.setField(target, "loaded", false);
    }

    private Authentication authenticate(long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())), TENANT);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }

    // ===== fixture =====

    /**
     * 組織P（親）・C（Pの子）・X（別系統）、5 roleの利用者とマネージャーの直属利用者D、要員E1〜E5。
     * <ul>
     *   <li>マネージャーはPを主所属にし、DはXを主所属・マネージャーを上長にする（直属経路）。</li>
     *   <li>E1=C所属、E2=X所属で営業の担当、E3=組織なし、E4=組織なしでDと連携（連携ユーザーの主所属Xへfallback）、
     *       E5=組織なしで要員本人と連携。</li>
     * </ul>
     */
    private final class Fixture {
        final Map<String, Long> users = new LinkedHashMap<>();
        final List<Engineer> engineers = new ArrayList<>();
        final Set<Long> organizationIds = new HashSet<>();
        final Set<Long> userIds = new HashSet<>();
        final Set<Long> engineerIds = new HashSet<>();

        Fixture() {
            long parent = insertOrganization(null);
            long child = insertOrganization(parent);
            long other = insertOrganization(null);
            for (String role : ROLES) {
                users.put(role, insertUser(role, 1));
            }
            long direct = insertUser("営業", 1);
            insertAssignment(users.get("マネージャー"), parent, null);
            insertAssignment(direct, other, users.get("マネージャー"));
            Engineer e1 = insertEngineer("E1", child);
            Engineer e2 = insertEngineer("E2", other);
            insertEngineer("E3", null);
            Engineer e4 = insertEngineer("E4", null);
            Engineer e5 = insertEngineer("E5", null);
            insertSales(e2.getId(), users.get("営業"));
            insertLink(e4.getId(), direct);
            insertLink(e5.getId(), users.get("要員"));
            // 営業はgroup割当あり（group判定の経路）、他のroleは割当なし（legacy fallbackの経路）。
            Long salesGroup = jdbc.queryForList("SELECT id FROM m_permission_group WHERE tenant_id = ? "
                    + "AND group_key = 'role-sales' AND enabled = 1 AND deleted_flag = 0", Long.class, TENANT)
                    .stream().findFirst().orElseGet(() -> {
                        jdbc.update("INSERT INTO m_permission_group (tenant_id, group_key, group_name, enabled, deleted_flag) "
                                + "VALUES (?, 'role-sales', 'U6試験', 1, 0)", TENANT);
                        return jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id = ? "
                                + "AND group_key = 'role-sales'", Long.class, TENANT);
                    });
            jdbc.update("INSERT INTO t_user_permission_group (tenant_id, user_id, group_id, deleted_flag) VALUES (?, ?, ?, 0)",
                    TENANT, users.get("営業"), salesGroup);
            assertTrue(e1.getId() != null && e2.getId() != null);
        }

        long insertUser(String role, int status) {
            SysUser user = SysUser.builder()
                    .tenantId(TENANT)
                    .username("u6-" + System.nanoTime())
                    .password("x")
                    .realName("U6試験")
                    .role(role)
                    .status(status)
                    .build();
            sysUserMapper.insert(user);
            userIds.add(user.getId());
            return user.getId();
        }

        long insertOrganization(Long parentId) {
            OrganizationUnit unit = new OrganizationUnit();
            unit.setTenantId(1L);
            unit.setLegalEntityId(1L);
            unit.setCode("U6-" + System.nanoTime());
            unit.setName("U6試験組織");
            unit.setType("部");
            unit.setParentId(parentId);
            unit.setValidFrom(LocalDate.of(2020, 1, 1));
            unit.setStatus("有効");
            unit.setVersion(0);
            organizationUnitMapper.insert(unit);
            organizationIds.add(unit.getId());
            return unit.getId();
        }

        void insertAssignment(long userId, long organizationId, Long managerUserId) {
            UserOrganization assignment = new UserOrganization();
            assignment.setTenantId(TENANT);
            assignment.setUserId(userId);
            assignment.setOrganizationId(organizationId);
            assignment.setManagerUserId(managerUserId);
            assignment.setPrimaryFlag(1);
            assignment.setValidFrom(LocalDate.of(2020, 1, 1));
            assignment.setVersion(0);
            userOrganizationMapper.insert(assignment);
        }

        Engineer insertEngineer(String label, Long organizationId) {
            Engineer engineer = Engineer.builder()
                    .tenantId(TENANT)
                    .legalEntityId(1L)
                    .fullName("U6-" + label + "-" + System.nanoTime())
                    .fullNameKana("ユーロク")
                    .employmentType("正社員")
                    .status("Bench")
                    .organizationId(organizationId)
                    .build();
            engineerMapper.insert(engineer);
            engineers.add(engineer);
            engineerIds.add(engineer.getId());
            return engineer;
        }

        void insertSales(long engineerId, long salesUserId) {
            EngineerSales sales = new EngineerSales();
            sales.setEngineerId(engineerId);
            sales.setSalesUserId(salesUserId);
            sales.setPrimaryFlag(1);
            sales.setAssignedAt(LocalDate.of(2020, 1, 1));
            engineerSalesMapper.insert(sales);
        }

        void insertLink(long engineerId, long userId) {
            EngineerAccountLink link = new EngineerAccountLink();
            link.setTenantId(TENANT);
            link.setEngineerId(engineerId);
            link.setSysUserId(userId);
            engineerAccountLinkMapper.insert(link);
        }

        LifecycleCase caseOf(Engineer engineer) {
            LifecycleCase lcCase = new LifecycleCase();
            lcCase.setId(engineer.getId());
            lcCase.setTenantId(TENANT);
            lcCase.setEngineerId(engineer.getId());
            lcCase.setStatus("ACTIVE");
            return lcCase;
        }

        /** 公開・内部（営業担当・HR担当）・本人担当（公開・非公開）の5種のタスク。 */
        List<LifecycleTask> tasksFor(long userId) {
            return List.of(
                    task("VISIBLE_HR", 1, "HR", null),
                    task("INTERNAL_SALES", 0, "営業", null),
                    task("INTERNAL_HR", 0, "HR", null),
                    task("OWN_VISIBLE", 1, null, userId),
                    task("OWN_HIDDEN", 0, null, userId));
        }

        private LifecycleTask task(String code, int visible, String assigneeRole, Long assigneeUserId) {
            LifecycleTask task = new LifecycleTask();
            task.setTaskCode(code);
            task.setIsEngineerVisible(visible);
            task.setAssigneeRole(assigneeRole);
            task.setAssigneeUserId(assigneeUserId);
            task.setStatus("IN_PROGRESS");
            return task;
        }

        Set<Long> ownOrganizations(Set<Long> ids) {
            Set<Long> own = new HashSet<>(ids);
            own.retainAll(organizationIds);
            return own;
        }

        Set<Long> ownUsers(Set<Long> ids) {
            Set<Long> own = new HashSet<>(ids);
            own.retainAll(userIds);
            return own;
        }

        Set<Long> ownEngineers(Set<Long> ids) {
            Set<Long> own = new HashSet<>(ids);
            own.retainAll(engineerIds);
            return own;
        }
    }
}
