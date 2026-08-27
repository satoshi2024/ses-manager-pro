package com.ses.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PwaNoStoreFilterTest {

    @Test
    void api応答にはnoStoreを付与する() throws Exception {
        PwaNoStoreFilter filter = new PwaNoStoreFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/my/expenses");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-cache", response.getHeader("Pragma"));
        assertEquals("0", response.getHeader("Expires"));
        verify(chain).doFilter(request, response);
    }

    @Test
    void portalとmyのページもnoStoreにする() throws Exception {
        PwaNoStoreFilter filter = new PwaNoStoreFilter();
        for (String path : new String[]{"/my/timesheet", "/portal/customer", "/portal",
                "/document/list", "/payroll", "/contract-document", "/js/i18n.js"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, mock(FilterChain.class));
            assertEquals("no-store", response.getHeader("Cache-Control"), path);
        }
    }

    @Test
    void 静的資産にはnoStoreを付与しない() throws Exception {
        PwaNoStoreFilter filter = new PwaNoStoreFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/js/common.js");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertNull(response.getHeader("Cache-Control"));

        for (String path : new String[]{"/manifest.webmanifest", "/offline.html", "/data/station_names.json"}) {
            MockHttpServletRequest staticRequest = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse staticResponse = new MockHttpServletResponse();
            filter.doFilter(staticRequest, staticResponse, mock(FilterChain.class));
            assertNull(staticResponse.getHeader("Cache-Control"), path);
        }
    }
}
