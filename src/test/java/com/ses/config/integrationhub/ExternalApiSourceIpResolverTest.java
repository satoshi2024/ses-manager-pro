package com.ses.config.integrationhub;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalApiSourceIpResolverTest {
    private final ExternalApiSourceIpResolver resolver = new ExternalApiSourceIpResolver();

    @Test
    void directPeerIsUsedWhenNoForwardedHeaderExists() {
        MockHttpServletRequest request = request("2001:db8::10");
        assertEquals("2001:db8:0:0:0:0:0:10", resolver.resolve(request, List.of()));
    }

    @Test
    void forwardedHeaderRequiresOneTrustedProxyAndOneHop() {
        MockHttpServletRequest request = request("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        assertEquals("203.0.113.10", resolver.resolve(request, List.of("10.0.0.1/32")));

        MockHttpServletRequest multiHop = request("10.0.0.1");
        multiHop.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.2");
        assertThrows(ExternalApiSecurityException.class, () -> resolver.resolve(multiHop, List.of("10.0.0.1/32")));
    }

    @Test
    void untrustedForwardedAndMalformedForwardedAreRejected() {
        MockHttpServletRequest request = request("198.51.100.1");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        assertThrows(ExternalApiSecurityException.class, () -> resolver.resolve(request, List.of("10.0.0.1/32")));

        MockHttpServletRequest malformed = request("10.0.0.1");
        malformed.addHeader("Forwarded", "for=_hidden");
        assertThrows(ExternalApiSecurityException.class, () -> resolver.resolve(malformed, List.of("10.0.0.1/32")));
    }

    @Test
    void onlyStrictLiteralIpFormsAreAcceptedWithoutDns() {
        assertEquals("203.0.113.10", ExternalApiCidrMatcher.normalizeIp("203.0.113.10"));
        assertEquals("203.0.113.10", ExternalApiCidrMatcher.normalizeIp("::ffff:203.0.113.10"));
        assertNull(ExternalApiCidrMatcher.normalizeIp("127.1"));
        assertNull(ExternalApiCidrMatcher.normalizeIp("2130706433"));
        assertNull(ExternalApiCidrMatcher.normalizeIp("0127.0.0.1"));
        assertNull(ExternalApiCidrMatcher.normalizeIp("example.invalid"));
        assertNull(ExternalApiCidrMatcher.normalizeIp("fe80::1%lo0"));
    }

    @Test
    void mappedIpv6AndIpv4CidrsCollapseToTheSameFourByteFamily() {
        assertTrue(ExternalApiCidrMatcher.matchesAny("::ffff:203.0.113.10", "203.0.113.0/24"));
        assertTrue(ExternalApiCidrMatcher.matchesAny("203.0.113.10", "::ffff:203.0.113.0/120"));
        assertTrue(ExternalApiCidrMatcher.matchesAny("::ffff:203.0.113.10", "::ffff:0:0/96"));
        assertTrue(ExternalApiCidrMatcher.matchesAny("203.0.113.10", "::ffff:203.0.113.10/128"));
        assertFalse(ExternalApiCidrMatcher.matchesAny("203.0.113.10", "::ffff:203.0.113.0/125"));
    }

    @Test
    void rewrittenRemoteAddrWithoutTrustedProxyUsesConnectorPeer() {
        MockHttpServletRequest request = request("203.0.113.10");
        request.setAttribute(ExternalApiSourceIpResolver.CONNECTOR_PEER_ATTRIBUTE, "198.51.100.1");
        assertEquals("198.51.100.1", resolver.resolve(request, List.of()));
    }

    @Test
    void trustedProxyWithoutHeadersUsesRewrittenServletAddr() {
        MockHttpServletRequest request = request("203.0.113.10");
        request.setAttribute(ExternalApiSourceIpResolver.CONNECTOR_PEER_ATTRIBUTE, "10.0.0.1");
        assertEquals("203.0.113.10", resolver.resolve(request, List.of("10.0.0.1/32")));
    }

    private MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
