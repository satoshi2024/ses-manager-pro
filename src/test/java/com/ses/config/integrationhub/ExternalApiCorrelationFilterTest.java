package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalApiCorrelationFilterTest {
    @Test
    void serverGeneratesCorrelationForSuccessAndMalformedSuppliedId() throws Exception {
        ExternalApiCorrelationFilter filter = new ExternalApiCorrelationFilter(new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/external-api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(204));
        assertNotNull(response.getHeader("X-Correlation-ID"));
        assertEquals(204, response.getStatus());

        request = new MockHttpServletRequest("GET", "/external-api/v1/projects");
        request.addHeader("X-Correlation-ID", "bad space");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(204));
        assertEquals(400, response.getStatus());
        assertNotNull(response.getHeader("X-Correlation-ID"));
        assertTrue(response.getContentAsString().contains("REQUEST_INVALID"),
                "body=" + response.getContentAsString());
    }
}
