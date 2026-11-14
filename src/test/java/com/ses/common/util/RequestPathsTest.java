package com.ses.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FWD-SANITIZE PRE-P1-1: 判定用のアプリ内pathはX-Forwarded-Prefix・context path・%エンコードの影響を受けない。 */
class RequestPathsTest {

    @Test
    void 素のForwardedHeaderFilterでprefixが付いてもアプリ内pathは変わらない() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/compliance/findings");
        request.addHeader("X-Forwarded-Prefix", "/x");
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();

        new ForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set((HttpServletRequest) req));

        // 再現の前提: getRequestURI()はprefix付きになる
        assertEquals("/x/api/compliance/findings", seen.get().getRequestURI());
        assertEquals("/api/compliance/findings", RequestPaths.pathWithinApplication(seen.get()));
        assertTrue(RequestPaths.isApiPath(seen.get()));
    }

    @Test
    void contextPathを除き復号と正規化をしたpathを返す() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ses/api/%63ompliance;jsessionid=a//findings");
        request.setContextPath("/ses");

        assertEquals("/api/compliance/findings", RequestPaths.pathWithinApplication(request));
    }

    @Test
    void 不正な百分率エンコードは生のpathで返す() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/compliance/%zz");

        assertEquals("/api/compliance/%zz", RequestPaths.pathWithinApplication(request));
    }

    @Test
    void requestがnullなら空文字でapi扱いしない() {
        assertEquals("", RequestPaths.pathWithinApplication(null));
        assertFalse(RequestPaths.isApiPath(null));
        assertFalse(RequestPaths.isApiPath(new MockHttpServletRequest("GET", "/engineer/list")));
    }
}
