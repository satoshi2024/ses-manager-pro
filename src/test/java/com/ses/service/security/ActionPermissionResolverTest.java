package com.ses.service.security;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.util.PageUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionPermissionResolverTest {

    @Test
    void resource配下のexportはviewではなくexportActionになる() {
        assertEquals("export.execute",
                ActionPermissionResolver.resolve("GET", "/api/contracts/export"));
        assertEquals("export.execute",
                ActionPermissionResolver.resolve("GET", "/api/engineers/export-csv"));
    }

    @Test
    void 定期管理レポートApiは既知のreportActionへ解決される() {
        assertEquals("management-report.view",
                ActionPermissionResolver.resolve("GET", "/api/management-reports/runs/10"));
        assertEquals("management-report.create",
                ActionPermissionResolver.resolve("POST", "/api/management-reports/runs"));
        assertTrue(ActionPermissionResolver.isKnownAction("management-report.view"));
    }

    @Test
    void 未登録業務Apiはactionを生成せず未知として扱う() {
        assertNull(ActionPermissionResolver.resolve("GET", "/api/users-archive"));
        assertNull(ActionPermissionResolver.resolve("POST", "/api/future-sensitive"));
        assertNull(ActionPermissionResolver.resolve("GET", "/api/future-sensitive/export"));
        assertNull(ActionPermissionResolver.resolve("GET", "/api/future-sensitive/download"));
        assertNull(ActionPermissionResolver.resolve("GET", "/api/future-sensitive/report.pdf"));
        assertNull(ActionPermissionResolver.resolve("GET", "/api/export"));
        assertNull(ActionPermissionResolver.resolve("GET", "/api/security/future/export"));
        assertEquals("management-accounting.create",
                ActionPermissionResolver.resolve("POST", "/api/management-accounting/budgets"));
        assertEquals("organization.update",
                ActionPermissionResolver.resolve("PUT", "/api/organizations/12"));
        assertEquals("invoice.view", ActionPermissionResolver.resolve("GET", "/api/invoices"));
        assertEquals("candidate.view", ActionPermissionResolver.resolve("GET", "/api/candidates/12"));
        assertEquals("certification-learning-gap.view",
                ActionPermissionResolver.resolve("GET", "/api/certification-learning-gap"));
        assertEquals("export.execute",
                ActionPermissionResolver.resolve("GET", "/api/certification-learning-gap/export"));
        assertEquals("profile.update", ActionPermissionResolver.resolve("PUT", "/api/profile/password"));
    }

    @Test
    void file再scanはuploadと別actionになる() {
        assertEquals("file.scan.retry",
                ActionPermissionResolver.resolve("POST", "/api/files/sample.pdf/rescan"));
    }

    @Test
    void 管理者MfaResetは認証基盤の無判定pathに含めない() {
        assertEquals("mfa.reset",
                ActionPermissionResolver.resolve("POST", "/api/security/mfa/123/reset"));
    }

    @Test
    void 認証基盤allowListは本人flowだけに限定する() {
        assertTrue(ActionPermissionResolver.isAuthenticationInfrastructure(
                "POST", "/logout"));
        assertFalse(ActionPermissionResolver.isAuthenticationInfrastructure(
                "GET", "/logout"));
        assertTrue(ActionPermissionResolver.isAuthenticationInfrastructure(
                "GET", "/mfa/setup"));
        assertFalse(ActionPermissionResolver.isAuthenticationInfrastructure(
                "POST", "/mfa/setup"));
        assertTrue(ActionPermissionResolver.isAuthenticationInfrastructure(
                "GET", "/mfa/challenge"));
        assertFalse(ActionPermissionResolver.isAuthenticationInfrastructure(
                "POST", "/mfa/challenge"));
        assertTrue(ActionPermissionResolver.isAuthenticationInfrastructure(
                "GET", "/api/security/mfa/status"));
        assertTrue(ActionPermissionResolver.isAuthenticationInfrastructure(
                "POST", "/api/security/mfa/verify"));
        assertFalse(ActionPermissionResolver.isAuthenticationInfrastructure(
                "POST", "/api/security/mfa/123/reset"));
        assertFalse(ActionPermissionResolver.isAuthenticationInfrastructure(
                "GET", "/api/security/future"));
    }

    @Test
    void 勤怠承認は一般更新と別actionになる() {
        assertEquals("work-record.approve",
                ActionPermissionResolver.resolve("POST", "/api/work-records/10/approve"));
    }

    @Test
    void 全resource配下のexportとdownloadを専用actionへ正規化する() {
        assertEquals("export.execute",
                ActionPermissionResolver.resolve("GET", "/api/management-accounting/export"));
        assertEquals("export.execute",
                ActionPermissionResolver.resolve("POST", "/api/proposals/10/skill-sheet/export"));
        assertEquals("file.download",
                ActionPermissionResolver.resolve("GET", "/api/contract-documents/10/download"));
        assertEquals("file.download",
                ActionPermissionResolver.resolve("GET", "/api/work-records/10/report.pdf"));
    }

    @Test
    void 顧客ヘルスの二つのsnapshotURLは同一actionへ正規化する() {
        assertEquals("customer-health.snapshot",
                ActionPermissionResolver.resolve("POST", "/api/customer-success/health/snapshots"));
        assertEquals("customer-health.snapshot",
                ActionPermissionResolver.resolve("POST", "/api/service-desk/health/snapshots"));
        assertEquals("customer-health.view",
                ActionPermissionResolver.resolve("GET", "/api/customer-success/health"));
        assertEquals("customer-health.view",
                ActionPermissionResolver.resolve("GET", "/api/service-desk/health"));
    }

    @Test
    void 資産証跡Documentのdetailとdownloadは本人向けactionへ正規化する() {
        assertEquals("my.asset.view",
                ActionPermissionResolver.resolve("GET", "/api/documents/10"));
        assertEquals("my.asset.view",
                ActionPermissionResolver.resolve("GET", "/api/documents/10/versions/2/download"));
    }

    @Test
    void 注文請発行_download_検収uploadをmethodと実URLで分離する() {
        assertEquals("sales-order.edit", ActionPermissionResolver.resolve(
                "POST", "/api/sales-orders/10/acknowledgement-pdf"));
        assertEquals("file.download", ActionPermissionResolver.resolve(
                "GET", "/api/sales-orders/10/acknowledgement-pdf/download"));
        assertEquals("file.download", ActionPermissionResolver.resolve(
                "GET", "/api/sales-orders/10/documents/20/download"));
        assertEquals("file.upload", ActionPermissionResolver.resolve(
                "POST", "/api/acceptances/30/document"));
        assertEquals("file.download", ActionPermissionResolver.resolve(
                "GET", "/api/acceptances/30/document/download"));
    }

    /** CROSS-P1-01: 未登録rootだとMenuPermissionFilterが管理者bypassより前にdenyする。 */
    @Test
    void bpAffiliationsとbpMigrationsのURIが既知actionへ解決される() {
        assertEquals("bp-company.view",
                ActionPermissionResolver.resolve("GET", "/api/bp-affiliations/engineer/1"));
        assertEquals("bp-company.view",
                ActionPermissionResolver.resolve("GET", "/api/bp-affiliations/engineer/1/active"));
        assertEquals("bp-company.create",
                ActionPermissionResolver.resolve("POST", "/api/bp-affiliations"));
        assertEquals("bp-migration.view",
                ActionPermissionResolver.resolve("GET", "/api/bp-migrations/exceptions"));
        assertEquals("bp-migration.create",
                ActionPermissionResolver.resolve("POST", "/api/bp-migrations/run"));
        assertEquals("bp-migration.create",
                ActionPermissionResolver.resolve("POST", "/api/bp-migrations/resolve"));
        assertTrue(ActionPermissionResolver.isKnownAction("bp-company.view"));
        assertTrue(ActionPermissionResolver.isKnownAction("bp-migration.view"));
    }

    /**
     * CROSS-P1-02: Math.min(size,1000) では size&lt;=0 の全件取得を止められない。
     * 一覧実装が委譲する PageUtils.safePage の契約を固定する。
     */
    @Test
    void safePageはsize0をdefaultへ99999を上限へ正規化する() {
        Page<Object> zero = PageUtils.safePage(1, 0);
        assertEquals(1, zero.getCurrent());
        assertEquals(PageUtils.DEFAULT_PAGE_SIZE, zero.getSize());

        Page<Object> huge = PageUtils.safePage(1, 99999);
        assertEquals(1, huge.getCurrent());
        assertEquals(PageUtils.MAX_PAGE_SIZE, huge.getSize());
        assertTrue(huge.getSize() <= 1000);
    }
}
