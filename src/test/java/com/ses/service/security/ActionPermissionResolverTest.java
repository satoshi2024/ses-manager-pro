package com.ses.service.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionPermissionResolverTest {

    @Test
    void resource配下のexportはviewではなくexportActionになる() {
        assertEquals("export.execute",
                ActionPermissionResolver.resolve("GET", "/api/contracts/export"));
        assertEquals("export.execute",
                ActionPermissionResolver.resolve("GET", "/api/engineers/export-csv"));
    }

    @Test
    void 未登録業務ApiもresourceActionへ正規化して判定を迂回させない() {
        assertEquals("users-archive.view", ActionPermissionResolver.resolve("GET", "/api/users-archive"));
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
