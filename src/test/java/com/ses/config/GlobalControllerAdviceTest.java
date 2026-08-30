package com.ses.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalControllerAdviceTest {

    private final GlobalControllerAdvice advice = new GlobalControllerAdvice(null, null);

    @Test
    void 複数階層のパスからナビゲーションキーを解決する() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        request.setRequestURI("/my/timesheet/123");
        assertEquals("/my/timesheet", advice.currentNavKey(request));

        request.setRequestURI("/candidate/list");
        assertEquals("/candidate", advice.currentNavKey(request));

        request.setRequestURI("/project-ingestion");
        assertEquals("/project-ingestion", advice.currentNavKey(request));

        request.setRequestURI("/accounting/integration");
        assertEquals("/accounting/integration", advice.currentNavKey(request));
    }

    @Test
    void 競合する接頭辞は境界と最長一致で一意に解決する() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        request.setRequestURI("/my/attendance");
        assertEquals("/my/attendance", advice.currentNavKey(request));
        request.setRequestURI("/my/leave");
        assertEquals("/my/leave", advice.currentNavKey(request));
        request.setRequestURI("/work-record/attendance");
        assertEquals("/work-record/attendance", advice.currentNavKey(request));
        request.setRequestURI("/leave");
        assertEquals("/leave", advice.currentNavKey(request));
        request.setRequestURI("/approval/routes");
        assertEquals("/approval/routes", advice.currentNavKey(request));

        request.setRequestURI("/engineer-change-requests/42");
        assertEquals("/engineer-change-requests", advice.currentNavKey(request));
        request.setRequestURI("/engineer/detail");
        assertEquals("/engineer", advice.currentNavKey(request));
        request.setRequestURI("/contract-document");
        assertEquals("/contract-document", advice.currentNavKey(request));
        request.setRequestURI("/contract/42");
        assertEquals("/contract", advice.currentNavKey(request));
        request.setRequestURI("/compliance-gate");
        assertEquals("/compliance-gate", advice.currentNavKey(request));
        request.setRequestURI("/compliance");
        assertEquals("/compliance", advice.currentNavKey(request));
    }
}
