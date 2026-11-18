package com.ses.config;

import com.ses.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PwaRestoreAuditFilterTest {
    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void restore監査には固定URIとstatusだけを渡しrequest秘密値を含めない() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<AuditLogService> provider = mock(ObjectProvider.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(provider.getIfAvailable()).thenReturn(audit);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-a", "credentials"));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/my/pwa/restore/reauthenticate");
        request.setContent("{\"password\":\"private-password\",\"userScope\":\"private-scope\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        new ApiAuditFilter(provider).doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        verify(audit).record(eq("user-a"), eq("POST"), eq("/api/my/pwa/restore/reauthenticate"),
                eq(200), eq("ses-manager"), eq(true));
    }
}
