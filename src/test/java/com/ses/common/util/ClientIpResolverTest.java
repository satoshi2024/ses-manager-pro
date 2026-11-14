package com.ses.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S13-XFF-01 / FWD-SANITIZE PRE-P2-1: client IPは「信頼プロキシが追記したXFF右端1 hop」だけを採用する。
 */
class ClientIpResolverTest {

    @Test
    void 信頼プロキシが空ならXFFを無視してremoteAddrを返す() {
        ClientIpResolver resolver = new ClientIpResolver("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");

        assertEquals("10.0.0.8", resolver.resolve(request));
    }

    @Test
    void 信頼プロキシ経由ならXFF右端の1hopを採用する() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.1, 192.168.1.0/24");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        // ALBのappend mode: clientが送った値の後ろにALBが見た送信元を追記する
        request.addHeader("X-Forwarded-For", "203.0.113.9, 198.51.100.7");

        assertEquals("198.51.100.7", resolver.resolve(request));
    }

    @Test
    void 非信頼remoteAddrの偽造XFFは採用しない() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.50");
        request.addHeader("X-Forwarded-For", "198.51.100.1");

        assertEquals("203.0.113.50", resolver.resolve(request));
    }

    @Test
    void 信頼プロキシ経由でも左端を入れ替えた偽装値は結果を変えない() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/24");
        for (int i = 1; i <= 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.5");
            request.addHeader("X-Forwarded-For", "203.0.113." + i + ", 198.51.100.77");

            assertEquals("198.51.100.77", resolver.resolve(request));
        }
    }

    @Test
    void XFFが複数行なら信頼プロキシ経由でもTCPpeerを返す() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/24");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.77");
        request.addHeader("X-Forwarded-For", "203.0.113.1");

        assertEquals("10.0.0.5", resolver.resolve(request));
    }

    @Test
    void XFF右端が不正値や空要素ならTCPpeerを返す() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/24");
        for (String value : List.of("unknown", "198.51.100.77, ", "[2001:db8::1]", "198.51.100.77:443",
                "example.com", "010.0.0.1")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.5");
            request.addHeader("X-Forwarded-For", value);

            assertEquals("10.0.0.5", resolver.resolve(request), value);
        }
    }

    @Test
    void 清浄化filterのattributeがあればそれを返す() {
        ClientIpResolver resolver = new ClientIpResolver("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        // framework strategyではgetRemoteAddr()がXFF最左に書き換わっているため使わない
        request.setRemoteAddr("203.0.113.99");
        request.setAttribute(ClientIpResolver.CLIENT_IP_ATTRIBUTE, "198.51.100.7");

        assertEquals("198.51.100.7", resolver.resolve(request));
    }

    @Test
    void IPv6とIPv4mappedの信頼プロキシCIDRに一致する() {
        ClientIpResolver resolver = new ClientIpResolver("2001:db8:10::/64, ::ffff:10.0.0.0/120, ::1");

        assertTrue(resolver.isTrustedProxy("2001:db8:10:0:0:0:0:5"));
        assertTrue(resolver.isTrustedProxy("10.0.0.200"));
        assertTrue(resolver.isTrustedProxy("0:0:0:0:0:0:0:1"));
        assertFalse(resolver.isTrustedProxy("2001:db8:11::5"));
        assertFalse(resolver.isTrustedProxy("10.0.1.1"));
    }

    @Test
    void IPv6の右端hopは正規化して返す() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/24");

        assertEquals("2001:db8:0:0:0:0:0:1", resolver.resolveFromConnection("10.0.0.5",
                Collections.enumeration(List.of("203.0.113.1, 2001:db8::1"))));
    }

    @Test
    void 構文不正の信頼プロキシ設定は起動時に拒否する() {
        for (String csv : List.of("10.0.0.0/33", "localhost", "10.0.0.1,,10.0.0.2", "10.0.0.0/24,", "10.0.0/24",
                "::/129", "10.0.0.0/8/1")) {
            assertThrows(IllegalStateException.class, () -> new ClientIpResolver(csv), csv);
        }
    }

    @Test
    void host名はDNS解決せず信頼プロキシとして扱わない() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1/32");

        assertFalse(resolver.isTrustedProxy("localhost"));
        assertEquals("localhost", resolver.resolveFromConnection("localhost",
                Collections.enumeration(List.of("198.51.100.7"))));
    }

    @Test
    void requestやTCPpeerが無ければunknownを返す() {
        ClientIpResolver resolver = new ClientIpResolver("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("");

        assertEquals("unknown", resolver.resolve(null));
        assertEquals("unknown", resolver.resolve(request));
    }
}
