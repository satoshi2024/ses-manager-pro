package com.ses.config;

import com.ses.service.security.PersistentSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentSessionFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 未追跡sessionを拒否する際もsession未生成でnull例外にしない() throws Exception {
        PersistentSessionService service = mock(PersistentSessionService.class);
        when(service.validateAndTouch(any(), any())).thenReturn(false);
        @SuppressWarnings("unchecked")
        ObjectProvider<PersistentSessionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        PersistentSessionFilter filter = new PersistentSessionFilter(provider);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("7", null, java.util.List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();
        jakarta.servlet.FilterChain chain = mock(jakarta.servlet.FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(302, response.getStatus());
        assertEquals("/login?sessionExpired", response.getRedirectedUrl());
        verify(chain, never()).doFilter(any(), any());
    }
}
