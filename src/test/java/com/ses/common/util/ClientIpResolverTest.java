package com.ses.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void 信頼プロキシ経由ならXFF先頭を採用する() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.1, 192.168.1.0/24");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void 非信頼remoteAddrの偽造XFFは換桶できない() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.1");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.50");
        request.addHeader("X-Forwarded-For", "198.51.100.1");

        assertEquals("203.0.113.50", resolver.resolve(request));
    }
}
