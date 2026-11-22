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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
@Transactional
class ActionPermissionMatrixTest {

    /** 旧menu権限で到達できた既知業務action。 */
    private static final List<String> BUSINESS_ACTIONS = List.of(
            "dashboard.view", "analytics.view", "quotation.view", "quotation.create",
            "work-record.view", "sales-performance.view", "monthly-closing.view",
            "email.view", "skill-tag.view", "organization.view",
            "invoice.view", "engineer.view", "engineer.delete", "customer.delete",
            "contract.delete", "export.execute", "file.download",
            "document.view", "document.create");

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

    /**
     * CRM(S08)。V73が `/crm/*` menuを 管理者/マネージャー/営業 へ付与しているので、
     * action層の母集団もそれに一致していなければならない（design §6.2）。
     * `crm` を RESOURCE_NAMES へ登録しただけでは足りず、V66_1以降は
     * 既知resource wildcardの列挙制なので `crm.*` のseed（V74）も要る。
     */
    @Test
    void CRMは営業とマネージャーに許可されHRと要員には許可されない() {
        for (String action : List.of("crm.view", "crm.create", "crm.update", "crm.delete")) {
            assertTrue(authorizationService.isAllowed(authenticate(9001L, "営業"), action),
                    "営業に " + action + " が拒否された");
            assertTrue(authorizationService.isAllowed(authenticate(9003L, "マネージャー"), action),
                    "マネージャーに " + action + " が拒否された");
            assertFalse(authorizationService.isAllowed(authenticate(9002L, "HR"), action),
                    "HRに " + action + " が許可された（design §6.2でCRMはHR不可視）");
            assertFalse(authorizationService.isAllowed(authenticate(9004L, "要員"), action),
                    "要員に " + action + " が許可された");
        }
    }

    /**
     * S05(V68/V69)・S06(V70)が m_menu へ足した resource は、`RESOURCE_NAMES` には登録済みだったが
     * `t_permission_group_action` へのseedが無く、group割当済みの非管理者が出荷済み機能で
     * 403になっていた（S08 Round 2 で発見。V74で補完）。
     */
    @Test
    void 生産性向上機能とBP会社管理もmenu付与と同じroleで実行できる() {
        for (String action : List.of("search.view", "task.view", "task.create",
                "saved-view.view", "batch-operation.create")) {
            assertTrue(authorizationService.isAllowed(authenticate(9001L, "営業"), action),
                    "営業に " + action + " が拒否された");
            assertTrue(authorizationService.isAllowed(authenticate(9002L, "HR"), action),
                    "HRに " + action + " が拒否された");
            assertTrue(authorizationService.isAllowed(authenticate(9003L, "マネージャー"), action),
                    "マネージャーに " + action + " が拒否された");
        }
        // V70のmenu付与は 管理者/営業/マネージャー なのでHRは対象外
        assertTrue(authorizationService.isAllowed(authenticate(9001L, "営業"), "bp-company.view"));
        assertTrue(authorizationService.isAllowed(authenticate(9003L, "マネージャー"), "bp-company.view"));
        assertFalse(authorizationService.isAllowed(authenticate(9002L, "HR"), "bp-company.view"));
    }

    /** URI→action keyの解決自体が壊れると、上のmatrixは全て素通り(null)になる。 */
    @Test
    void CRMのURIがaction_keyへ解決される() {
        assertEquals("crm.view", ActionPermissionResolver.resolve("GET", "/api/crm/leads"));
        assertEquals("crm.view", ActionPermissionResolver.resolve("GET", "/api/crm/opportunities"));
        assertEquals("crm.create", ActionPermissionResolver.resolve("POST", "/api/crm/leads"));
        assertEquals("crm.update", ActionPermissionResolver.resolve("PUT", "/api/crm/opportunities/1"));
        assertEquals("crm.delete", ActionPermissionResolver.resolve("DELETE", "/api/crm/leads/1"));
        assertEquals("customer.view", ActionPermissionResolver.resolve("GET", "/api/customers/1/activities"));
        assertEquals("customer.update", ActionPermissionResolver.resolve("PUT", "/api/customers/1/activities/2"));
        assertEquals("crm.view", ActionPermissionResolver.resolve("GET", "/api/customers/1/contacts"));
    }

    /**
     * CROSS-P1-01: bp-affiliations / bp-migrations が未登録だと管理者も含めfilter 403になる。
     * affiliationsは既存のbp-company権限、migrationsは管理者到達可能な専用resourceへ解決する。
     */
    @Test
    void bpAffiliationsとbpMigrationsのURIがaction_keyへ解決され管理者は実行できる() {
        assertEquals("bp-company.view",
                ActionPermissionResolver.resolve("GET", "/api/bp-affiliations/engineer/1"));
        assertEquals("bp-migration.view",
                ActionPermissionResolver.resolve("GET", "/api/bp-migrations/exceptions"));
        assertTrue(authorizationService.isAllowed(authenticate(9000L, "管理者"), "bp-company.view"));
        assertTrue(authorizationService.isAllowed(authenticate(9000L, "管理者"), "bp-migration.view"));
        assertTrue(authorizationService.isAllowed(authenticate(9001L, "営業"), "bp-company.view"));
    }

    @Test
    void HRの既存活動導線はcustomer権限で維持されCRM専用導線だけ拒否される() {
        Authentication hr = authenticate(9002L, "HR");
        assertTrue(authorizationService.isAllowed(hr, "customer.view"));
        assertTrue(authorizationService.isAllowed(hr, "customer.update"));
        assertFalse(authorizationService.isAllowed(hr, "crm.view"));
    }

    @Test
    void 承認画面の口座actionは営業とマネージャーを拒否し管理者だけ表示できる() {
        assertFalse(authorizationService.isAllowed(authenticate(9001L, "営業"),
                "bp-company.bank-account.view"));
        assertFalse(authorizationService.isAllowed(authenticate(9003L, "マネージャー"),
                "bp-company.bank-account.view"));
        assertTrue(authorizationService.isAllowed(authenticate(9000L, "管理者"),
                "bp-company.bank-account.view"));
    }

    @Test
    void 既知resource許可を持つroleでも機密actionは拒否指定が優先される() {
        Authentication sales = authenticate(9001L, "営業");
        assertFalse(authorizationService.isAllowed(sales, "user.view"));
        assertFalse(authorizationService.isAllowed(sales, "user.delete"));
        assertFalse(authorizationService.isAllowed(sales, "identity-provider.create"));
        assertFalse(authorizationService.isAllowed(sales, "system-config.view"));
        assertFalse(authorizationService.isAllowed(sales, "permission.manage"));
        assertFalse(authorizationService.isAllowed(sales, "audit.security.view"));
        assertFalse(authorizationService.isAllowed(sales, "file.scan.retry"));
        assertFalse(authorizationService.isAllowed(sales, "payroll.view"));
        // NF07 W-5: privacy は既知resourceでも営業には開かない（role検査とV191のdeny）。
        assertFalse(authorizationService.isAllowed(sales, "privacy.view"));
        assertFalse(authorizationService.isAllowed(sales, "privacy.dsar.manage"));
    }

    @Test
    void マネージャーは全権限wildcardを持たず機密actionを実行できない() {
        Authentication manager = authenticate(9003L, "マネージャー");
        // V64はrole-managerへ '*' をseedしており、action層が素通しになっていた。
        assertFalse(authorizationService.isAllowed(manager, "user.update"));
        assertFalse(authorizationService.isAllowed(manager, "identity-provider.create"));
        assertFalse(authorizationService.isAllowed(manager, "system-config.view"));
        assertFalse(authorizationService.isAllowed(manager, "permission.manage"));
        assertFalse(authorizationService.isAllowed(manager, "payroll.view"));
        assertFalse(authorizationService.isAllowed(manager, "audit.security.view"));
        assertFalse(authorizationService.isAllowed(manager, "file.scan.retry"));
        assertFalse(authorizationService.isAllowed(manager, "privacy.view"));
        assertFalse(authorizationService.isAllowed(manager, "privacy.dsar.manage"));
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
    void identityProviderとSystemConfigは管理者だけ許可される() {
        assertTrue(authorizationService.isAllowed(authenticate(9000L, "管理者"), "identity-provider.create"));
        assertTrue(authorizationService.isAllowed(authenticate(9000L, "管理者"), "system-config.view"));
        assertFalse(authorizationService.isAllowed(authenticate(9002L, "HR"), "identity-provider.create"));
        assertFalse(authorizationService.isAllowed(authenticate(9002L, "HR"), "system-config.view"));
        assertFalse(authorizationService.isAllowed(authenticate(9003L, "マネージャー"), "identity-provider.create"));
        assertFalse(authorizationService.isAllowed(authenticate(9003L, "マネージャー"), "system-config.view"));
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
        assertFalse(authorizationService.isAllowed(sales, "identity-provider.create"));
        assertFalse(authorizationService.isAllowed(sales, "system-config.view"));
        assertFalse(authorizationService.isAllowed(sales, "payroll.view"));
        assertFalse(authorizationService.isAllowed(sales, "permission.manage"));

        Authentication member = authenticate(9998L, "要員");
        assertTrue(authorizationService.isAllowed(member, "my.view"));
        assertFalse(authorizationService.isAllowed(member, "dashboard.view"));

        Authentication hr = authenticate(9997L, "HR");
        assertTrue(authorizationService.isAllowed(hr, "payroll.view"));
        assertFalse(authorizationService.isAllowed(hr, "contract.cost.view"));
        assertFalse(authorizationService.isAllowed(hr, "identity-provider.create"));
        assertFalse(authorizationService.isAllowed(hr, "system-config.view"));
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
        org.junit.jupiter.api.Assertions.assertEquals(0, nonAdminWildcardCount, "非管理者groupにグローバルな*許可が存在する");

        // role-admin (group 1) に baseline ('*') が1件存在すること
        Integer adminBaselineCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_permission_group_action WHERE group_id = 1 AND action_key = '*' AND deny_flag = 0",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, adminBaselineCount, "管理者groupにbaselineが未セット");

        // manager(4) と sales(2) はV66の5件 + V78口座action + Fix Batch 1の2件で計8件。
        // V191（NF07 W-5）で privacy.* の deny、V193（NF-06）で import.metadata.manage の deny が各1件加わり、
        // 営業 10・HR 9・マネージャー 10 件。
        Integer salesDenyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_permission_group_action WHERE group_id = 2 AND deny_flag = 1", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(10, salesDenyCount, "営業groupのdeny行数が不一致");

        Integer hrDenyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_permission_group_action WHERE group_id = 3 AND deny_flag = 1", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(9, hrDenyCount, "HRgroupのdeny行数が不一致");

        Integer managerDenyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_permission_group_action WHERE group_id = 4 AND deny_flag = 1", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(10, managerDenyCount, "マネージャーgroupのdeny行数が不一致");

        Integer salesBankDeny = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_permission_group_action WHERE group_id = 2 "
                        + "AND action_key = 'bp-company.bank-account.view' AND deny_flag = 1", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, salesBankDeny,
                "営業groupにV78の口座action denyが必要");
        Integer managerBankDeny = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_permission_group_action WHERE group_id = 4 "
                        + "AND action_key = 'bp-company.bank-account.view' AND deny_flag = 1", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, managerBankDeny,
                "マネージャーgroupにV78の口座action denyが必要");
    }

    // ---------------------------------------------------------------- NF07 W-5（production-bridge-phased-plan.md §6.5）

    /** privacy の代表action（未知subpathを含む）。BUSINESS_ACTIONS には足さない。 */
    private static final List<String> PRIVACY_ACTIONS = List.of(
            "privacy.view", "privacy.policy.view", "privacy.hold.manage", "privacy.dsar.approve",
            "privacy.unknown-sub.view");

    @Test
    void privacy系actionはprivacy_admin所属の管理者だけに許可される() {
        for (String action : PRIVACY_ACTIONS) {
            assertTrue(authorizationService.isAllowed(authenticate(9005L, "管理者"), action),
                    "privacy-adminだけの管理者に " + action + " が拒否された");
            assertFalse(authorizationService.isAllowed(authenticate(9007L, "管理者"), action),
                    "role-adminだけの管理者に " + action + " が許可された");
            assertFalse(authorizationService.isAllowed(authenticate(9008L, "管理者"), action),
                    "role-adminとprivacy-adminの両方の管理者に " + action + " が許可された");
            assertFalse(authorizationService.isAllowed(authenticate(9000L, "管理者"), action),
                    "group未割当の管理者に " + action + " が許可された");
            assertFalse(authorizationService.isAllowed(authenticate(9001L, "営業"), action));
            assertFalse(authorizationService.isAllowed(authenticate(9002L, "HR"), action));
            assertFalse(authorizationService.isAllowed(authenticate(9003L, "マネージャー"), action));
            assertFalse(authorizationService.isAllowed(authenticate(9004L, "要員"), action));
            // 9006: 営業を privacy-admin へ直接所属させた誤割当の再現。code の role 検査で拒否する。
            assertFalse(authorizationService.isAllowed(authenticate(9006L, "営業"), action),
                    "privacy-adminへ誤割当された営業に " + action + " が許可された");
        }
    }

    /**
     * NF07-A2a: DSAR 受付〜SCOPED の入口は {@code privacy.dsar.view}（GET）と {@code privacy.dsar.manage}（POST・PUT）へ解決され、
     * privacy-admin だけの管理者にしか許可されない。新しい key の列挙ではなく {@code privacy.*} の規則がそのまま効くことを確かめる
     * （{@code BUSINESS_ACTIONS}・{@code PRIVACY_ACTIONS} は変えない）。
     */
    @Test
    void DSARの受付から範囲確定までの入口はview_manageへ解決されprivacy_admin所属の管理者だけに許可される() {
        assertEquals("privacy.dsar.view", ActionPermissionResolver.resolve("GET", "/api/privacy/dsar"));
        assertEquals("privacy.dsar.view", ActionPermissionResolver.resolve("GET", "/api/privacy/dsar/12"));
        assertEquals("privacy.dsar.manage", ActionPermissionResolver.resolve("POST", "/api/privacy/dsar"));
        for (String path : List.of("/identity", "/candidates", "/scope", "/block", "/cancel")) {
            assertEquals("privacy.dsar.manage", ActionPermissionResolver.resolve("POST", "/api/privacy/dsar/12" + path),
                    path);
        }
        assertEquals("privacy.dsar.manage", ActionPermissionResolver.resolve("PUT", "/api/privacy/dsar/12/candidates/3"));
        assertEquals("privacy.view", ActionPermissionResolver.resolve("GET", "/privacy/dsar"));
        assertEquals("privacy.view", ActionPermissionResolver.resolve("GET", "/privacy/dsar/12"));

        for (String action : List.of("privacy.dsar.view", "privacy.dsar.manage")) {
            assertTrue(authorizationService.isAllowed(authenticate(9005L, "管理者"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9007L, "管理者"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9008L, "管理者"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9000L, "管理者"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9001L, "営業"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9002L, "HR"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9003L, "マネージャー"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9004L, "要員"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9006L, "営業"), action), action);
        }
    }

    /**
     * NF07-A2b: 検索〜交付記録の入口は、区画の照会が {@code privacy.dsar.view}、草案・交付記録が {@code privacy.dsar.manage}、承認が
     * {@code privacy.dsar.approve}、専用ダウンロードが {@code privacy.dsar.export} へ解決され、どれも privacy-admin だけの管理者にしか
     * 許可されない（新しい key の列挙ではなく {@code privacy.*} の規則がそのまま効く。{@code BUSINESS_ACTIONS}・
     * {@code PRIVACY_ACTIONS} は変えない）。汎用の file 取得の action（{@code file.download}）には解決されない。
     */
    @Test
    void DSARの草案と承認と専用ダウンロードと交付記録の入口は専用actionへ解決されprivacy_admin所属の管理者だけに許可される() {
        assertEquals("privacy.dsar.view", ActionPermissionResolver.resolve("GET", "/api/privacy/dsar/12/delivery"));
        assertEquals("privacy.dsar.manage", ActionPermissionResolver.resolve("POST", "/api/privacy/dsar/12/preview"));
        assertEquals("privacy.dsar.manage", ActionPermissionResolver.resolve("POST", "/api/privacy/dsar/12/delivery"));
        assertEquals("privacy.dsar.approve", ActionPermissionResolver.resolve("POST", "/api/privacy/dsar/12/approve"));
        // review P2-3: 承認者による草案の確認は承認の action（export・file.download ではない）
        assertEquals("privacy.dsar.approve",
                ActionPermissionResolver.resolve("GET", "/api/privacy/dsar/12/approve/preview?packageId=7"));
        // review P2-2: 開示以外の請求の手動対応の完了は manage
        assertEquals("privacy.dsar.manage", ActionPermissionResolver.resolve("POST", "/api/privacy/dsar/12/resolution"));
        assertEquals("privacy.dsar.export",
                ActionPermissionResolver.resolve("GET", "/api/privacy/dsar/12/package/download?packageId=7"));
        org.junit.jupiter.api.Assertions.assertNotEquals("file.download",
                ActionPermissionResolver.resolve("GET", "/api/privacy/dsar/12/package/download"));

        for (String action : List.of("privacy.dsar.approve", "privacy.dsar.export")) {
            assertTrue(authorizationService.isAllowed(authenticate(9005L, "管理者"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9007L, "管理者"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9008L, "管理者"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9000L, "管理者"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9001L, "営業"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9002L, "HR"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9003L, "マネージャー"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9004L, "要員"), action), action);
            assertFalse(authorizationService.isAllowed(authenticate(9006L, "営業"), action), action);
        }
    }

    @Test
    void privacy_adminとrole_adminの両方に所属すると拒否優先でprivacyは使えない() {
        Authentication both = authenticate(9008L, "管理者");
        for (String action : PRIVACY_ACTIONS) {
            assertFalse(authorizationService.isAllowed(both, action), action);
        }
        // 管理者の業務actionと、短絡許可の対象外で group 評価される one-on-one.confidential は '*' で通る
        assertTrue(authorizationService.isAllowed(both, "dashboard.view"));
        assertTrue(authorizationService.isAllowed(both, "one-on-one.confidential"));
    }

    @Test
    void privacy_admin所属の管理者は管理者の既存actionを失わない() {
        Authentication privacyAdmin = authenticate(9005L, "管理者");
        assertTrue(authorizationService.isAllowed(privacyAdmin, "dashboard.view"));
        assertTrue(authorizationService.isAllowed(privacyAdmin, "user.view"));
        assertTrue(authorizationService.isAllowed(privacyAdmin, "one-on-one.confidential"));
        for (String action : BUSINESS_ACTIONS) {
            assertTrue(authorizationService.isAllowed(privacyAdmin, action), action + " が拒否された");
        }
    }

    @Test
    void legacy_fallbackはprivacy系actionを誰にも許可しない() {
        for (String action : PRIVACY_ACTIONS) {
            assertFalse(authorizationService.isAllowed(authenticate(9000L, "管理者"), action), "管理者: " + action);
            assertFalse(authorizationService.isAllowed(authenticate(9999L, "営業"), action), "営業: " + action);
            assertFalse(authorizationService.isAllowed(authenticate(9997L, "HR"), action), "HR: " + action);
        }
        // legacy の管理者は privacy 以外の既存actionを維持する
        assertTrue(authorizationService.isAllowed(authenticate(9000L, "管理者"), "dashboard.view"));
    }

    @Test
    void 全体baselineはprivacyの許可根拠にならない() {
        // '*' だけを複製し privacy.* の deny を欠いた group（運用上の誤り）を一時的に作る。
        jdbcTemplate.update("INSERT INTO m_permission_group (id, tenant_id, group_key, group_name, enabled, deleted_flag) "
                + "VALUES (99, 'default', 'copied-admin', '複製された管理者group', 1, 0)");
        jdbcTemplate.update("INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) "
                + "VALUES ('default', 99, '*', 0, 0)");
        jdbcTemplate.update("INSERT INTO t_user_permission_group (tenant_id, user_id, group_id, assigned_at, deleted_flag) "
                + "VALUES ('default', 9010, 99, CURRENT_TIMESTAMP, 0)");
        Authentication copiedAdmin = authenticate(9010L, "管理者");

        for (String action : PRIVACY_ACTIONS) {
            assertFalse(authorizationService.isAllowed(copiedAdmin, action), "'*' だけで " + action + " が許可された");
        }
        // privacy 以外では '*' は従来どおり baseline として働く
        assertTrue(authorizationService.isAllowed(copiedAdmin, "one-on-one.confidential"));
    }

    @Test
    void privacyのURIはexportとdownloadと画面判定より先にprivacy_actionへ解決される() {
        assertEquals("privacy.dsar.export",
                ActionPermissionResolver.resolve("GET", "/api/privacy/dsar/1/package/download"));
        assertEquals("privacy.dsar.export", ActionPermissionResolver.resolve("POST", "/api/privacy/dsar/1/export"));
        assertEquals("privacy.dsar.export",
                ActionPermissionResolver.resolve("GET", "/api/privacy/dsar/1/package/x.pdf"));
        assertEquals("privacy.hold.manage", ActionPermissionResolver.resolve("POST", "/api/privacy/holds"));
        assertEquals("privacy.view", ActionPermissionResolver.resolve("GET", "/api/privacy/unknown-sub"));
        assertEquals("privacy.view", ActionPermissionResolver.resolve("GET", "/privacy/holds"));
        assertEquals("privacy.create", ActionPermissionResolver.resolve("POST", "/privacy/holds"));
        org.junit.jupiter.api.Assertions.assertNull(ActionPermissionResolver.resolve("OPTIONS", "/api/privacy/holds"));
        // 営業・要員が持つ汎用actionには解決されない
        for (String action : List.of("export.execute", "file.download")) {
            org.junit.jupiter.api.Assertions.assertNotEquals(action,
                    ActionPermissionResolver.resolve("GET", "/api/privacy/dsar/1/export"));
        }
    }

    @Test
    void privacyの権限seedはprivacy_admin以外の全groupでdenyしprivacy_adminだけが明示許可を持つ() {
        for (int groupId : new int[]{1, 2, 3, 4, 5}) {
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_permission_group_action WHERE group_id = ? "
                            + "AND action_key = 'privacy.*' AND deny_flag = 1", Integer.class, groupId),
                    "group " + groupId + " に privacy.* の deny が必要");
        }
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_permission_group_action "
                + "WHERE group_id = 6 AND action_key = '*' AND deny_flag = 0", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_permission_group_action "
                + "WHERE group_id = 6 AND action_key = 'privacy.*' AND deny_flag = 0", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_permission_group_action "
                + "WHERE group_id = 6 AND deny_flag = 1", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM m_permission_group g "
                + "WHERE g.group_key <> 'privacy-admin' AND NOT EXISTS (SELECT 1 FROM t_permission_group_action a "
                + "WHERE a.group_id = g.id AND a.action_key = 'privacy.*' AND a.deny_flag = 1)", Integer.class),
                "privacy-admin以外で privacy.* の deny を持たない group がある");
        assertEquals("privacy-admin", jdbcTemplate.queryForObject(
                "SELECT group_key FROM m_permission_group WHERE id = 6", String.class));
    }

    // ---------------------------------------------------------------- NF-06（W3-12・V193）

    /**
     * import.metadata.manage は管理者専用 group（role-admin・privacy-admin）だけが明示許可を持ち、他の group は deny する。
     * 取込のservice層は '*' を許可根拠に数えず明示許可行を要求する（ImportMetadataAuthorizationService）ため、
     * seedが無いとNF-06をONにしても全管理者が403になる（W3-05 review P2-3(a)）。
     */
    @Test
    void 取込metadata管理actionは管理者専用groupだけが明示許可を持ち他groupはdenyする() {
        for (int groupId : new int[]{1, 6}) {
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_permission_group_action "
                    + "WHERE group_id = ? AND action_key = 'import.metadata.manage' AND deny_flag = 0 "
                    + "AND deleted_flag = 0", Integer.class, groupId), "group " + groupId + " の明示許可");
        }
        for (int groupId : new int[]{2, 3, 4, 5}) {
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_permission_group_action "
                    + "WHERE group_id = ? AND action_key = 'import.metadata.manage' AND deny_flag = 1", Integer.class,
                    groupId), "group " + groupId + " の deny");
        }
        assertTrue(authorizationService.isAllowed(authenticate(9007L, "管理者"), "import.metadata.manage"));
        assertTrue(authorizationService.isAllowed(authenticate(9005L, "管理者"), "import.metadata.manage"));
        assertFalse(authorizationService.isAllowed(authenticate(9001L, "営業"), "import.metadata.manage"));
        assertFalse(authorizationService.isAllowed(authenticate(9002L, "HR"), "import.metadata.manage"));
        assertFalse(authorizationService.isAllowed(authenticate(9003L, "マネージャー"), "import.metadata.manage"));
        assertFalse(authorizationService.isAllowed(authenticate(9004L, "要員"), "import.metadata.manage"));
        assertFalse(authorizationService.isAllowed(authenticate(9999L, "営業"), "import.metadata.manage"),
                "group未割当のlegacy fallbackも許可しない");
        // 業務actionは従来どおり（V193は他のactionを変えない）
        for (String action : BUSINESS_ACTIONS) {
            assertTrue(authorizationService.isAllowed(authenticate(9001L, "営業"), action), action);
        }
    }

    // ---------------------------------------------------------------- NF-03 資格番号（FINAL P3-4-1・V121・V126）

    /**
     * 資格番号の復号（{@code certification.pii.view}）は本人と HR・管理者だけ（approval-decision:45）。
     * マネージャーは V126 で {@code certification-learning-gap.*} を持つが別resourceであり、
     * {@code certification.pii.view} の候補（action本体・{@code certification.*}・{@code '*'}）には当たらない。
     * 将来のseedが営業・マネージャーへ {@code certification.*} や全体baselineを与えた場合はここで落ちる。
     */
    @Test
    void 資格番号の復号actionはマネージャーと営業を拒否しHRと管理者だけに許可する() {
        String pii = "certification.pii.view";
        assertFalse(authorizationService.isAllowed(authenticate(9003L, "マネージャー"), pii), "マネージャーに許可された");
        assertFalse(authorizationService.isAllowed(authenticate(9001L, "営業"), pii), "営業に許可された");
        assertFalse(authorizationService.isAllowed(authenticate(9004L, "要員"), pii), "要員に許可された");
        // group未割当のlegacy fallbackでも開かない（certification は既知resourceではなく未知actionとして拒否）
        assertFalse(authorizationService.isAllowed(authenticate(9996L, "マネージャー"), pii), "legacyのマネージャーに許可された");
        assertFalse(authorizationService.isAllowed(authenticate(9999L, "営業"), pii), "legacyの営業に許可された");
        // 対照: V121 の明示許可を持つHRと、管理者には許可される（上の拒否がfixtureの欠落によるものでないこと）
        assertTrue(authorizationService.isAllowed(authenticate(9002L, "HR"), pii), "HRが拒否された");
        assertTrue(authorizationService.isAllowed(authenticate(9007L, "管理者"), pii), "管理者が拒否された");
        // マネージャーは同じ画面の別resource（V126）を使える。拒否がresourceの境界によるものであることの確認
        assertTrue(authorizationService.isAllowed(authenticate(9003L, "マネージャー"), "certification-learning-gap.view"));
    }

    @Test
    void 資格番号の復号actionの許可根拠は管理者とHRのgroupだけが持つ() {
        // AuthorizationServiceImpl の候補キー（action本体・resource wildcard・'*'）を許可で持つ非管理者・非HRのgroupは無い
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_permission_group_action "
                + "WHERE group_id IN (2, 4, 5) AND deny_flag = 0 AND deleted_flag = 0 "
                + "AND action_key IN ('certification.pii.view', 'certification.*', '*')", Integer.class));
        for (int groupId : new int[]{1, 3}) {
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_permission_group_action "
                    + "WHERE group_id = ? AND action_key = 'certification.pii.view' AND deny_flag = 0 "
                    + "AND deleted_flag = 0", Integer.class, groupId), "group " + groupId + " の明示許可（V121）");
        }
    }

    /**
     * 機能の開放（feature-enablement-console FE-RQ-012・FE-AC-012-01）: 画面と API の URI は {@code feature-enablement} の action へ
     * 解決され（null にならない）、管理者は group の '*' で通る。営業・HR・マネージャーは group の有無に関わらず拒否する
     * （非管理者の group へ {@code feature-enablement.*} を seed せず、{@code isAdminOnlyAction} が legacy でも拒否する）。
     */
    @Test
    void 機能の開放は管理者だけが使え営業とHRとマネージャーはgroupの有無に関わらず拒否される() {
        assertEquals("feature-enablement.view", ActionPermissionResolver.resolve("GET", "/api/feature-enablement/units"));
        assertEquals("feature-enablement.create",
                ActionPermissionResolver.resolve("POST", "/api/feature-enablement/requests/1/approve"));
        assertEquals("feature-enablement.view", ActionPermissionResolver.resolve("GET", "/feature-enablement"));
        for (String action : List.of("feature-enablement.view", "feature-enablement.create")) {
            assertTrue(authorizationService.isAllowed(authenticate(9007L, "管理者"), action), "管理者（group）: " + action);
            assertTrue(authorizationService.isAllowed(authenticate(9000L, "管理者"), action), "管理者（legacy）: " + action);
            assertFalse(authorizationService.isAllowed(authenticate(9001L, "営業"), action), "営業（group）: " + action);
            assertFalse(authorizationService.isAllowed(authenticate(9002L, "HR"), action), "HR（group）: " + action);
            assertFalse(authorizationService.isAllowed(authenticate(9003L, "マネージャー"), action), "マネージャー（group）: " + action);
            assertFalse(authorizationService.isAllowed(authenticate(9999L, "営業"), action), "営業（legacy）: " + action);
            assertFalse(authorizationService.isAllowed(authenticate(9997L, "HR"), action), "HR（legacy）: " + action);
            assertFalse(authorizationService.isAllowed(authenticate(9996L, "マネージャー"), action),
                    "マネージャー（legacy）: " + action);
            assertFalse(authorizationService.isAllowed(authenticate(9004L, "要員"), action), "要員: " + action);
        }
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
