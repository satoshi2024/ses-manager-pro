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

    @Test
    void 監査用pathは制御文字と書式文字だけを百分率表記へ戻す() {
        // P3-4: ESC・bidi制御（RLO/LRI）・ZWSP（書式文字）・行区切り・DEL・TABは表示を偽装できるため戻す
        assertEquals("/api/x%1B%5B31m", RequestPaths.forAudit("/api/x\u001B%5B31m"));
        assertEquals("/api/a%E2%80%AEb%E2%81%A6c", RequestPaths.forAudit("/api/a‮b⁦c"));
        assertEquals("/api/%E2%80%8B%E2%80%A8%7F%09", RequestPaths.forAudit("/api/​ \u007F\t"));
        assertEquals("/api/%EF%BF%BD", RequestPaths.forAudit("/api/\uD800"));
        // 通常の文字（日本語・記号）はそのまま
        assertEquals("/api/files/資料 (1).pdf", RequestPaths.forAudit("/api/files/資料 (1).pdf"));
        assertEquals(null, RequestPaths.forAudit(null));
    }

    @Test
    void 監査用pathは復号後のpathへ同じ変換をかける() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/engineers/%1B%E2%80%AE1");
        request.setCharacterEncoding("UTF-8");

        assertEquals("/api/engineers/\u001B‮1", RequestPaths.pathWithinApplication(request));
        assertEquals("/api/engineers/%1B%E2%80%AE1", RequestPaths.auditPath(request));
    }
}
