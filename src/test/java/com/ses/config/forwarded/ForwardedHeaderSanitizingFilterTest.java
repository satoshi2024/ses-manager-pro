package com.ses.config.forwarded;

import com.ses.common.util.ClientIpResolver;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FWD-SANITIZE: clientが送った転送ヘッダーを、ForwardedHeaderFilterが反映する前に清浄化する。
 */
class ForwardedHeaderSanitizingFilterTest {

    private static final String TRUSTED_PEER = "10.0.0.5";
    private static final String UNTRUSTED_PEER = "203.0.113.50";

    private final ForwardedHeaderSanitizingFilter filter =
            new ForwardedHeaderSanitizingFilter(new ClientIpResolver("10.0.0.0/24"));

    private HttpServletRequest run(MockHttpServletRequest request) throws Exception {
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));
        return seen.get();
    }

    private MockHttpServletRequest request(String peer) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/compliance/findings");
        request.setRemoteAddr(peer);
        request.setServerName("app.example.jp");
        request.setServerPort(8080);
        request.addHeader("Host", "app.example.jp:8080");
        return request;
    }

    @Test
    void 信頼しないpeerのPrefixとHostとForwardedとSslは削除されURIも書き換わらない() throws Exception {
        MockHttpServletRequest request = request(UNTRUSTED_PEER);
        request.addHeader("X-Forwarded-Prefix", "/x");
        request.addHeader("X-Forwarded-Host", "evil.example");
        request.addHeader("X-Forwarded-Ssl", "on");
        request.addHeader("Forwarded", "for=198.51.100.1;host=evil.example;proto=https");

        HttpServletRequest seen = run(request);

        assertEquals("/api/compliance/findings", seen.getRequestURI());
        assertEquals("", seen.getContextPath());
        assertEquals("app.example.jp", seen.getServerName());
        assertEquals(UNTRUSTED_PEER, seen.getRemoteAddr());
        assertFalse(seen.isSecure());
        for (String name : List.of("X-Forwarded-Prefix", "X-Forwarded-Host", "X-Forwarded-Ssl", "Forwarded",
                "x-forwarded-prefix")) {
            assertNull(seen.getHeader(name), name);
            assertFalse(seen.getHeaders(name).hasMoreElements(), name);
        }
        assertFalse(Collections.list(seen.getHeaderNames()).stream()
                .anyMatch(name -> name.toLowerCase().startsWith("x-forwarded") || name.equalsIgnoreCase("forwarded")));
    }

    @Test
    void 信頼プロキシ経由でもPrefixとHostとForwardedは反映しない() throws Exception {
        MockHttpServletRequest request = request(TRUSTED_PEER);
        request.addHeader("X-Forwarded-Prefix", "/mfa");
        request.addHeader("X-Forwarded-Host", "evil.example");
        request.addHeader("Forwarded", "for=198.51.100.1;proto=https");

        HttpServletRequest seen = run(request);

        assertEquals("/api/compliance/findings", seen.getRequestURI());
        assertEquals("", seen.getContextPath());
        assertEquals("app.example.jp", seen.getServerName());
        assertEquals(TRUSTED_PEER, seen.getRemoteAddr());
        assertFalse(seen.isSecure());
    }

    @Test
    void 信頼しないpeerのXFFとProtoとPortは削除されremoteAddrはTCPpeerのまま() throws Exception {
        MockHttpServletRequest request = request(UNTRUSTED_PEER);
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Port", "443");

        HttpServletRequest seen = run(request);

        assertEquals(UNTRUSTED_PEER, seen.getRemoteAddr());
        assertFalse(seen.isSecure());
        assertEquals("http", seen.getScheme());
        assertEquals(8080, seen.getServerPort());
        assertNull(seen.getHeader("X-Forwarded-For"));
        assertEquals(UNTRUSTED_PEER, seen.getAttribute(ClientIpResolver.CLIENT_IP_ATTRIBUTE));
        assertEquals(UNTRUSTED_PEER, seen.getAttribute(ClientIpResolver.CONNECTOR_PEER_ATTRIBUTE));
    }

    @Test
    void 信頼プロキシ経由ならXFF右端とProtoとPortだけを反映する() throws Exception {
        MockHttpServletRequest request = request(TRUSTED_PEER);
        request.addHeader("X-Forwarded-For", "203.0.113.9, 198.51.100.7");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Port", "443");

        HttpServletRequest seen = run(request);

        assertEquals("198.51.100.7", seen.getRemoteAddr());
        assertTrue(seen.isSecure());
        assertEquals("https", seen.getScheme());
        assertEquals(443, seen.getServerPort());
        assertEquals("app.example.jp", seen.getServerName());
        assertEquals("https://app.example.jp/api/compliance/findings", seen.getRequestURL().toString());
        assertEquals("198.51.100.7", seen.getAttribute(ClientIpResolver.CLIENT_IP_ATTRIBUTE));
        assertEquals(TRUSTED_PEER, seen.getAttribute(ClientIpResolver.CONNECTOR_PEER_ATTRIBUTE));
    }

    @Test
    void 信頼プロキシ経由でもProtoとPortとXFFの不正値は反映しない() throws Exception {
        MockHttpServletRequest request = request(TRUSTED_PEER);
        request.addHeader("X-Forwarded-For", "198.51.100.7, unknown");
        request.addHeader("X-Forwarded-Proto", "javascript");
        request.addHeader("X-Forwarded-Port", "99999");

        HttpServletRequest seen = run(request);

        assertEquals(TRUSTED_PEER, seen.getRemoteAddr());
        assertFalse(seen.isSecure());
        assertEquals(8080, seen.getServerPort());
        assertEquals(TRUSTED_PEER, seen.getAttribute(ClientIpResolver.CLIENT_IP_ATTRIBUTE));
    }

    @Test
    void 信頼プロキシ経由でもXFFやProtoが複数行なら反映しない() throws Exception {
        MockHttpServletRequest request = request(TRUSTED_PEER);
        request.addHeader("X-Forwarded-For", "198.51.100.7");
        request.addHeader("X-Forwarded-For", "203.0.113.1");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Proto", "http");

        HttpServletRequest seen = run(request);

        assertEquals(TRUSTED_PEER, seen.getRemoteAddr());
        assertFalse(seen.isSecure());
    }

    @Test
    void Protoが複数要素なら右端を使う() throws Exception {
        MockHttpServletRequest request = request(TRUSTED_PEER);
        request.addHeader("X-Forwarded-Proto", "http, HTTPS");

        HttpServletRequest seen = run(request);

        assertTrue(seen.isSecure());
    }

    @Test
    void 転送ヘッダーが無ければrequestをそのまま渡しclientIPを置く() throws Exception {
        MockHttpServletRequest request = request(UNTRUSTED_PEER);

        HttpServletRequest seen = run(request);

        assertSame(request, seen);
        assertEquals(UNTRUSTED_PEER, request.getAttribute(ClientIpResolver.CLIENT_IP_ATTRIBUTE));
    }

    @Test
    void ERRORディスパッチでも清浄化する() throws Exception {
        MockHttpServletRequest request = request(UNTRUSTED_PEER);
        request.setDispatcherType(DispatcherType.ERROR);
        request.setRequestURI("/error");
        request.addHeader("X-Forwarded-Prefix", "/x");
        request.addHeader("X-Forwarded-For", "198.51.100.1");

        HttpServletRequest seen = run(request);

        assertEquals("/error", seen.getRequestURI());
        assertEquals(UNTRUSTED_PEER, seen.getRemoteAddr());
    }

    @Test
    void 未設定なら全ての転送ヘッダーを信頼しない() throws Exception {
        ForwardedHeaderSanitizingFilter failClosed = new ForwardedHeaderSanitizingFilter(new ClientIpResolver(""));
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.7");
        request.addHeader("X-Forwarded-Proto", "https");
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();

        failClosed.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));

        assertEquals("127.0.0.1", seen.get().getRemoteAddr());
        assertFalse(seen.get().isSecure());
        assertEquals("127.0.0.1", seen.get().getAttribute(ClientIpResolver.CLIENT_IP_ATTRIBUTE));
    }
}
