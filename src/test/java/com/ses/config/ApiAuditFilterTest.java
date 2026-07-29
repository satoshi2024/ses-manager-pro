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
}

