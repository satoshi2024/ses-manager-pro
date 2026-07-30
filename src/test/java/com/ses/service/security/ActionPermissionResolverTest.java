package com.ses.service.security;

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
}
