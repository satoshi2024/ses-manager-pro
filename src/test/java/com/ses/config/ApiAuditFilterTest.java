package com.ses.config;

import com.ses.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiAuditFilterTest {

    @Test
    void fileDownload拒否をsecurityEventとして監査する() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<AuditLogService> provider = mock(ObjectProvider.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        when(provider.getIfAvailable()).thenReturn(auditLogService);
        ApiAuditFilter filter = new ApiAuditFilter(provider);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/unknown.pdf");
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = (req, res) ->
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(403);

        filter.doFilter(request, response, chain);

        verify(auditLogService).record(any(), eq("GET"), eq("/api/files/unknown.pdf"),
                eq(403), eq("FILE_DOWNLOAD_REJECTED"), eq(false));
    }

    @Test
    void breakGlassの参照操作も実行前後を必須監査する() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<AuditLogService> provider = mock(ObjectProvider.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        when(provider.getIfAvailable()).thenReturn(auditLogService);
        ApiAuditFilter filter = new ApiAuditFilter(provider);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/invoices/export");
        request.getSession(true).setAttribute(com.ses.service.security.BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        verify(auditLogService).recordRequired(any(), eq("GET"), eq("/api/invoices/export"),
                eq(102), eq("BREAK_GLASS_ACCESS_ATTEMPT"), eq(false));
        verify(auditLogService).record(any(), eq("GET"), eq("/api/invoices/export"),
                eq(200), eq("BREAK_GLASS_ACCESS"), eq(true));
    }

    @Test
    void breakGlass操作は監査service不在なら実行しない() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<AuditLogService> provider = mock(ObjectProvider.class);
        ApiAuditFilter filter = new ApiAuditFilter(provider);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/invoices");
        request.getSession(true).setAttribute(com.ses.service.security.BreakGlassService.INCIDENT_ID_ATTRIBUTE, 10L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void 注文請archiveDownloadの成功と拒否を実URLで監査する() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<AuditLogService> provider = mock(ObjectProvider.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        when(provider.getIfAvailable()).thenReturn(auditLogService);
        ApiAuditFilter filter = new ApiAuditFilter(provider);

        MockHttpServletRequest success = new MockHttpServletRequest(
                "GET", "/api/sales-orders/1/acknowledgement-pdf/download");
        filter.doFilter(success, new MockHttpServletResponse(), (req, res) -> { });
        verify(auditLogService).record(any(), eq("GET"),
                eq("/api/sales-orders/1/acknowledgement-pdf/download"),
                eq(200), eq("FILE_DOWNLOAD"), eq(true));

        MockHttpServletRequest rejected = new MockHttpServletRequest(
                "GET", "/api/sales-orders/1/documents/2/download");
        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
        filter.doFilter(rejected, rejectedResponse,
                (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(403));
        verify(auditLogService).record(any(), eq("GET"),
                eq("/api/sales-orders/1/documents/2/download"),
                eq(403), eq("FILE_DOWNLOAD_REJECTED"), eq(false));
    }

    @Test
    void 検収書postUploadをfileDownload監査へ誤分類しない() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<AuditLogService> provider = mock(ObjectProvider.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        when(provider.getIfAvailable()).thenReturn(auditLogService);
        ApiAuditFilter filter = new ApiAuditFilter(provider);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/acceptances/1/document");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        verify(auditLogService).record(any(), eq("POST"), eq("/api/acceptances/1/document"),
                eq(200), eq("ses-manager"), eq(true));
    }

    @Test
    void PWAの同一hashreplayは業務監査を二重記録しない() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<AuditLogService> provider = mock(ObjectProvider.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        when(provider.getIfAvailable()).thenReturn(auditLogService);
        ApiAuditFilter filter = new ApiAuditFilter(provider);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/my/pwa/expenses/drafts");
        request.setAttribute(com.ses.service.pwa.PwaClientMutationLedgerService.REPLAY_REQUEST_ATTRIBUTE, true);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        verify(auditLogService, never()).record(any(), any(), any(), any(Integer.class), any(), any(Boolean.class));
    }
}
