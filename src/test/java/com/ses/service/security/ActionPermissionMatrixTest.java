package com.ses.service.security;

import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * role別のaction権限matrixを、実際のseed状態に対してH2上で検証する。
 *
 * <p>この観点のtestが無かったためV64のseedがR3.1の後方互換を破っていた。
 * V64は各roleへ許可actionを列挙したが、action keyは{@link ActionPermissionResolver}が
 * URI rootから機械生成するため列挙しきれず、{@code dashboard.view}・{@code analytics.view}・
 * {@code quotation.view}・{@code *.delete}などが全roleで拒否されていた。
 *
 * <p>V67以降はinventory登録済みresource wildcardだけを許可し、未知rootは拒否する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/permission-group-seed-h2.sql")
class ActionPermissionMatrixTest {

    /** 旧menu権限で到達できた既知業務action。 */
    private static final List<String> BUSINESS_ACTIONS = List.of(
            "dashboard.view", "analytics.view", "quotation.view", "quotation.create",
            "work-record.view", "sales-performance.view", "monthly-closing.view",
            "email.view", "skill-tag.view", "organization.view",
            "invoice.view", "engineer.view", "engineer.delete", "customer.delete",
            "contract.delete", "export.execute", "file.download");

    @Autowired
    private AuthorizationService authorizationService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void group割当済みの営業は旧menu相当の業務actionを全て実行できる() {
        Authentication authentication = authenticate(9001L, "営業");
        for (String action : BUSINESS_ACTIONS) {
            assertTrue(authorizationService.isAllowed(authentication, action), action + " が拒否された");
        }
    }

    @Test
    void group割当済みのHRも旧menu相当の業務actionを全て実行できる() {
        Authentication authentication = authenticate(9002L, "HR");
        for (String action : BUSINESS_ACTIONS) {
            assertTrue(authorizationService.isAllowed(authentication, action), action + " が拒否された");
        }
    }

    @Test
    void 既知resource許可を持つroleでも機密actionは拒否指定が優先される() {
        Authentication sales = authenticate(9001L, "営業");
        assertFalse(authorizationService.isAllowed(sales, "user.view"));
        assertFalse(authorizationService.isAllowed(sales, "user.delete"));
        assertFalse(authorizationService.isAllowed(sales, "permission.manage"));
        assertFalse(authorizationService.isAllowed(sales, "audit.security.view"));
        assertFalse(authorizationService.isAllowed(sales, "file.scan.retry"));
        assertFalse(authorizationService.isAllowed(sales, "payroll.view"));
    }

    @Test
    void マネージャーは全権限wildcardを持たず機密actionを実行できない() {
        Authentication manager = authenticate(9003L, "マネージャー");
        // V64はrole-managerへ '*' をseedしており、action層が素通しになっていた。
        assertFalse(authorizationService.isAllowed(manager, "user.update"));
        assertFalse(authorizationService.isAllowed(manager, "permission.manage"));
        assertFalse(authorizationService.isAllowed(manager, "payroll.view"));
        assertFalse(authorizationService.isAllowed(manager, "audit.security.view"));
        assertFalse(authorizationService.isAllowed(manager, "file.scan.retry"));
        // 業務actionは従来どおり通る
        assertTrue(authorizationService.isAllowed(manager, "dashboard.view"));
        assertTrue(authorizationService.isAllowed(manager, "contract.cost.view"));
    }

    @Test
    void 原価参照はHRだけ拒否される() {
        assertFalse(authorizationService.isAllowed(authenticate(9002L, "HR"), "contract.cost.view"));
        assertTrue(authorizationService.isAllowed(authenticate(9001L, "営業"), "contract.cost.view"));
    }

    @Test
    void payrollはHRだけ許可される() {
        assertTrue(authorizationService.isAllowed(authenticate(9002L, "HR"), "payroll.view"));
        assertFalse(authorizationService.isAllowed(authenticate(9001L, "営業"), "payroll.view"));
        assertFalse(authorizationService.isAllowed(authenticate(9003L, "マネージャー"), "payroll.view"));
    }

    @Test
    void 要員は本人向け経路だけ実行できる() {
        Authentication member = authenticate(9004L, "要員");
        assertTrue(authorizationService.isAllowed(member, "my.view"));
        assertTrue(authorizationService.isAllowed(member, "my.update"));
        assertTrue(authorizationService.isAllowed(member, "file.download"));
        assertTrue(authorizationService.isAllowed(member, "profile.update"));
        assertFalse(authorizationService.isAllowed(member, "engineer.view"));
        assertFalse(authorizationService.isAllowed(member, "dashboard.view"));
        assertFalse(authorizationService.isAllowed(member, "contract.cost.view"));
    }

    /** group未割当（新規作成直後・移行漏れ）でも同じ結論になること。 */
    @Test
    void group未割当のlegacyFallbackもbaseline相当の範囲を維持する() {
        Authentication sales = authenticate(9999L, "営業");
        for (String action : BUSINESS_ACTIONS) {
            assertTrue(authorizationService.isAllowed(sales, action), "legacy: " + action + " が拒否された");
        }
        assertFalse(authorizationService.isAllowed(sales, "user.view"));
        assertFalse(authorizationService.isAllowed(sales, "payroll.view"));
        assertFalse(authorizationService.isAllowed(sales, "permission.manage"));

        Authentication member = authenticate(9998L, "要員");
        assertTrue(authorizationService.isAllowed(member, "my.view"));
        assertFalse(authorizationService.isAllowed(member, "dashboard.view"));

        Authentication hr = authenticate(9997L, "HR");
        assertTrue(authorizationService.isAllowed(hr, "payroll.view"));
        assertFalse(authorizationService.isAllowed(hr, "contract.cost.view"));
    }

    @Test
    void group割当済みとlegacyFallbackの双方で未知actionを拒否する() {
        assertFalse(authorizationService.isAllowed(
                authenticate(9001L, "営業"), "future-sensitive.view"));
        assertFalse(authorizationService.isAllowed(
                authenticate(9999L, "営業"), "future-sensitive.view"));
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void permissionGroupSeedの内容そのものを検査する() {
        // 非管理者group (2, 3, 4, 5) に action_key='*' AND deny_flag=0 が存在しないこと
        Integer nonAdminWildcardCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_permission_group_action WHERE group_id IN (2, 3, 4, 5) AND action_key = '*' AND deny_flag = 0",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, nonAdminWildcardCount, "非管理者groupに全局*許可が存在する");

        // role-admin (group 1) に baseline ('*') が1件存在すること
        Integer adminBaselineCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_permission_group_action WHERE group_id = 1 AND action_key = '*' AND deny_flag = 0",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, adminBaselineCount, "管理者groupにbaselineが未セット");

        // manager(4) と sales(2) の deny 行がそれぞれ5件存在すること
        Integer salesDenyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_permission_group_action WHERE group_id = 2 AND deny_flag = 1", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(5, salesDenyCount, "営業groupのdeny行数が不一致");

        Integer managerDenyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_permission_group_action WHERE group_id = 4 AND deny_flag = 1", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(5, managerDenyCount, "マネージャーgroupのdeny行数が不一致");
    }

    private Authentication authenticate(Long id, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setRole(role);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                new LoginUser(user, List.of()), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }
}
