package com.ses.service.security.epoch;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.entity.SystemConfig;
import com.ses.mapper.SysUserMapper;
import com.ses.service.RoleMenuService;
import com.ses.service.SysUserService;
import com.ses.service.SystemConfigService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.PermissionGroupManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * U7（NF01細粒度epoch計画 §4.1・§7.3 Demo (1)〜(5)、W3-15）: 実Spring beanとH2で、F1 writerが同じtransactionで
 * 全体epoch・actor epochを進める条件を確かめる。
 *
 * <ul>
 *   <li>同じ内容の再保存・非認可キー・非認可列の更新では進めない。変化があれば1だけ進める。</li>
 *   <li>role変更は入れ子の割当置換と合わせて1回だけ進める。別の利用者は変えない。</li>
 *   <li>利用者のtenantは更新で変えられず（400）、作成時はbodyのtenantではなく操作者のtenantになる（F-1）。</li>
 *   <li>上限では409 {@code error.authorization.epochUnavailable} で止める。</li>
 * </ul>
 *
 * <p>クラス全体を {@code @Transactional} にし、試験の行・epochの変化はすべてrollbackする。fenceは同じtransactionの
 * 同じanchorを1回しか進めないため、「進めない」確認は同じanchorを進める前に行う。epochは差分で比べ、
 * 他の試験が残した値に依存しない。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalActorEpochWriterIntegrationTest {

    private static final String TENANT = "default";

    @Autowired private RoleMenuService roleMenuService;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private SysUserService sysUserService;
    @Autowired private PermissionGroupManagementService permissionGroupManagementService;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private JdbcTemplate jdbc;

    private Authentication operator;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        for (String key : List.of("role-admin", "role-sales", "role-hr", "role-manager", "role-member")) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM m_permission_group WHERE tenant_id = ? "
                    + "AND group_key = ? AND enabled = 1 AND deleted_flag = 0", Integer.class, TENANT, key);
            if (count == null || count == 0) {
                jdbc.update("INSERT INTO m_permission_group (tenant_id, group_key, group_name, enabled, deleted_flag) "
                        + "VALUES (?, ?, ?, 1, 0)", TENANT, key, "W3-15試験 " + key);
            }
        }
        long operatorId = insertUser("管理者");
        operator = authenticate(operatorId);
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void role_menuは同じ内容の再保存で全体epochを変えず集合の変化で1だけ進める() {
        Long dashboard = jdbc.queryForObject("SELECT id FROM m_menu WHERE menu_key = 'dashboard'", Long.class);
        Long customer = jdbc.queryForObject("SELECT id FROM m_menu WHERE menu_key = 'customer'", Long.class);
        // 依存menuの自動追加（engineer/project → skill-tag）が起きない集合へ、試験transaction内で固定する（rollbackで戻る）。
        jdbc.update("DELETE FROM t_role_menu WHERE role = 'マネージャー'");
        jdbc.update("INSERT INTO t_role_menu (role, menu_id) VALUES ('マネージャー', ?)", dashboard);
        long before = globalEpoch();

        roleMenuService.replaceMenus("マネージャー", List.of(dashboard));
        assertEquals(before, globalEpoch(), "同じ集合の再保存では進めない");

        roleMenuService.replaceMenus("マネージャー", List.of(customer, dashboard));
        assertEquals(before + 1, globalEpoch(), "集合が変われば1だけ進める");
        assertEquals(new TreeSet<>(List.of(dashboard, customer)), new TreeSet<>(jdbc.queryForList(
                "SELECT menu_id FROM t_role_menu WHERE role = 'マネージャー'", Long.class)));
        roleMenuService.replaceMenus("マネージャー", List.of(dashboard));
        assertEquals(before + 1, globalEpoch(), "同じtransactionでは2回目以降を進めない（E-4）");
    }

    @Test
    void 認可キーの値の変化だけで全体epochを進め非認可キーと同じ値では変えない() {
        String scope = AuthorizationConfigKeys.SALES_OWN_DATA_ONLY;
        String current = configValue(scope);
        String benchDays = configValue("notice.bench-warn-days");
        long before = globalEpoch();

        systemConfigService.updateAll(List.of(new SystemConfig("notice.bench-warn-days",
                "77".equals(benchDays) ? "78" : "77", "ベンチ警告日数")));
        assertEquals(before, globalEpoch(), "非認可キーの変更では進めない");
        systemConfigService.updateAll(List.of(new SystemConfig(scope, current, "営業の担当データ限定")));
        assertEquals(before, globalEpoch(), "認可キーを同じ値で再保存しても進めない");

        systemConfigService.updateAll(List.of(new SystemConfig(scope, "true".equals(current) ? "false" : "true",
                "営業の担当データ限定")));
        assertEquals(before + 1, globalEpoch(), "認可キーの値が変われば1だけ進める");
    }

    @Test
    void 利用者の無効化は対象のactor_epochだけを1進め状態が変わらない更新では進めない() {
        long target = insertUser("営業");
        long other = insertUser("営業");
        long targetBefore = actorEpoch(target);
        long otherBefore = actorEpoch(other);

        sysUserService.updateUserStatus(target, 1, operator);
        assertEquals(targetBefore, actorEpoch(target), "有効のままの更新では進めない");

        sysUserService.updateUserStatus(target, 0, operator);
        assertEquals(targetBefore + 1, actorEpoch(target));
        assertEquals(otherBefore, actorEpoch(other), "別の利用者は変えない（細粒度）");
        assertEquals(0, sysUserMapper.selectById(target).getStatus());
    }

    @Test
    void role変更は入れ子の割当置換と合わせてactor_epochを1回だけ進め非認可列の更新では進めない() {
        long changed = insertUser("営業");
        long renamed = insertUser("営業");
        long changedBefore = actorEpoch(changed);
        long renamedBefore = actorEpoch(renamed);

        SysUser rename = new SysUser();
        rename.setRealName("表示名だけ変更");
        rename.setEmail("renamed@example.com");
        sysUserService.updateUser(renamed, rename, operator);
        assertEquals(renamedBefore, actorEpoch(renamed), "表示名・emailの変更では進めない");

        SysUser roleChange = new SysUser();
        roleChange.setRole("HR");
        sysUserService.updateUser(changed, roleChange, operator);
        assertEquals(changedBefore + 1, actorEpoch(changed), "role変更と割当置換で合わせて1回");
        assertEquals("HR", sysUserMapper.selectById(changed).getRole());
        assertEquals(Set.of(groupId("role-hr")), permissionGroupManagementService.assignedGroupIds(changed));
    }

    @Test
    void 権限group割当は同じ集合の再保存で進めず集合の変化で1だけ進める() {
        long target = insertUser("営業");
        jdbc.update("INSERT INTO t_user_permission_group (tenant_id, user_id, group_id, assigned_by, deleted_flag) "
                + "VALUES (?, ?, ?, NULL, 0)", TENANT, target, groupId("role-sales"));
        long before = actorEpoch(target);

        permissionGroupManagementService.replaceAssignments(target, Set.of(groupId("role-sales")), operator);
        assertEquals(before, actorEpoch(target), "同じ割当集合では進めない");

        permissionGroupManagementService.replaceAssignments(target,
                Set.of(groupId("role-sales"), groupId("role-manager")), operator);
        assertEquals(before + 1, actorEpoch(target));
    }

    @Test
    void 作成はbodyのtenantを使わず操作者のtenantで作り新規利用者のactor_epochを1にする() {
        SysUser body = new SysUser();
        body.setUsername("w315-new-" + System.nanoTime());
        body.setPassword("Passw0rdW315");
        body.setRealName("新規利用者");
        body.setRole("営業");
        body.setTenantId("other-tenant");

        sysUserService.createUser(body, operator);

        SysUser created = sysUserMapper.selectByUsername(body.getUsername());
        assertEquals(TENANT, created.getTenantId(), "bodyのtenantIdは無視して操作者のtenantにする（F-1）");
        assertEquals(1L, actorEpoch(created.getId()), "既定groupへの割当で1回だけ進める");
    }

    @Test
    void 利用者の削除はactor_epochを1進める() {
        long target = insertUser("HR");
        long before = actorEpoch(target);

        sysUserService.deleteUser(target, operator);

        assertEquals(before + 1, actorEpoch(target));
        assertEquals(1, jdbc.queryForObject("SELECT deleted_flag FROM sys_user WHERE id = ?", Integer.class, target));
    }

    @Test
    void 更新でtenantを変えようとすると400で拒否しtenantとepochを変えない() {
        long target = insertUser("営業");
        long before = actorEpoch(target);

        SysUser sameTenant = new SysUser();
        sameTenant.setTenantId(TENANT);
        sameTenant.setRealName("同じtenantは受け付ける");
        sysUserService.updateUser(target, sameTenant, operator);
        assertEquals("同じtenantは受け付ける", sysUserMapper.selectById(target).getRealName());

        SysUser otherTenant = new SysUser();
        otherTenant.setTenantId("other-tenant");
        BusinessException error = assertThrows(BusinessException.class,
                () -> sysUserService.updateUser(target, otherTenant, operator));
        assertEquals(400, error.getCode());
        assertEquals("error.user.tenantImmutable", error.getMessageKey());
        assertEquals(TENANT, sysUserMapper.selectById(target).getTenantId());
        assertEquals(before, actorEpoch(target));
    }

    @Test
    void actor_epochが上限なら利用者の無効化を409で止める() {
        long target = insertUser("営業");
        jdbc.update("UPDATE sys_user SET actor_authz_epoch = ? WHERE id = ?", Long.MAX_VALUE, target);

        BusinessException error = assertThrows(BusinessException.class,
                () -> sysUserService.updateUserStatus(target, 0, operator));

        assertEquals(409, error.getCode());
        assertEquals(AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY, error.getMessageKey());
        assertEquals(Long.MAX_VALUE, actorEpoch(target), "上限の値は変えない");
    }

    @Test
    void 全体epochが上限ならrole_menuの変更を409で止める() {
        Long dashboard = jdbc.queryForObject("SELECT id FROM m_menu WHERE menu_key = 'dashboard'", Long.class);
        Long customer = jdbc.queryForObject("SELECT id FROM m_menu WHERE menu_key = 'customer'", Long.class);
        jdbc.update("DELETE FROM t_role_menu WHERE role = 'マネージャー'");
        jdbc.update("INSERT INTO t_role_menu (role, menu_id) VALUES ('マネージャー', ?)", customer);
        jdbc.update("UPDATE t_authorization_global_epoch SET epoch = ? WHERE singleton_id = 1", Long.MAX_VALUE);

        BusinessException error = assertThrows(BusinessException.class,
                () -> roleMenuService.replaceMenus("マネージャー", List.of(dashboard)));

        assertEquals(409, error.getCode());
        assertEquals(Long.MAX_VALUE, globalEpoch(), "上限の値は変えない");
    }

    // ===== helper =====

    private long globalEpoch() {
        return jdbc.queryForObject("SELECT epoch FROM t_authorization_global_epoch WHERE singleton_id = 1", Long.class);
    }

    private long actorEpoch(long userId) {
        return jdbc.queryForObject("SELECT actor_authz_epoch FROM sys_user WHERE id = ?", Long.class, userId);
    }

    private String configValue(String key) {
        List<String> values = jdbc.queryForList("SELECT config_value FROM m_system_config WHERE config_key = ?",
                String.class, key);
        return values.isEmpty() ? null : values.get(0);
    }

    private long groupId(String key) {
        return jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id = ? AND group_key = ? "
                + "AND deleted_flag = 0", Long.class, TENANT, key);
    }

    private long insertUser(String role) {
        SysUser user = SysUser.builder()
                .tenantId(TENANT)
                .username("w315-" + System.nanoTime())
                .password("x")
                .realName("W3-15試験")
                .role(role)
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    private Authentication authenticate(long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())), TENANT);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }
}
