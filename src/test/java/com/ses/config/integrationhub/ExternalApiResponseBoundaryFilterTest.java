package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalApiResponseBoundaryFilterTest {
    private final ExternalApiResponseBoundaryFilter filter =
            new ExternalApiResponseBoundaryFilter(new ObjectMapper());

    @Test
    void servletWrapperのsecurityCauseだけをstable外部errorへunwrapする() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(), response, (request, ignored) -> {
            throw new ServletException("mvc wrapper", ExternalApiSecurityException.inboundConflict());
        });

        assertEquals(409, response.getStatus());
        assertTrue(response.getContentAsString().contains("INBOUND_PAYLOAD_CONFLICT"));
    }

    @Test
    void securityCauseでないwrapperは詳細を出さず500にする() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(), response, (request, ignored) -> {
            throw new ServletException("mvc wrapper", new IllegalStateException("internal detail"));
        });

        assertEquals(500, response.getStatus());
        assertTrue(response.getContentAsString().contains("INTERNAL_ERROR"));
        assertTrue(!response.getContentAsString().contains("internal detail"));
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/external-api/v1/webhooks/provider-b2");
    }
}
