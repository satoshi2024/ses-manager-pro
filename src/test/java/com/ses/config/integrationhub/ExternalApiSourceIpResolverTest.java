package com.ses.config.integrationhub;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N5-P2-2: source IPはvalveが捕捉したTCP peerと転送header原文だけから解決し、
 * trusted proxyが追記した右端1 hopだけを信頼する。
 */
class ExternalApiSourceIpResolverTest {
    private final ExternalApiSourceIpResolver resolver = new ExternalApiSourceIpResolver();

    @Test
    void directPeerIsUsedWhenNoForwardedHeaderExists() {
        MockHttpServletRequest request = captured("2001:db8::10", List.of(), List.of());
        assertEquals("2001:db8:0:0:0:0:0:10", resolver.resolve(request, security()));
    }

    @Test
    void trustedProxyUsesOnlyTheRightmostAppendedHopAndIgnoresClientWrittenLeftHops() {
        // clientが「allow-list内のIP」を先頭に書き、ALBが実際のpeerを右端へ追記した状態。
        MockHttpServletRequest spoofed = captured("10.0.0.1", List.of("203.0.113.10, 198.51.100.7"), List.of());
        assertEquals("198.51.100.7", resolver.resolve(spoofed, security("10.0.0.1/32")));

        MockHttpServletRequest singleHop = captured("10.0.0.1", List.of("203.0.113.10"), List.of());
        assertEquals("203.0.113.10", resolver.resolve(singleHop, security("10.0.0.1/32")));
    }

    @Test
    void multipleForwardedForLinesAreCombinedInReceiveOrderBeforeTakingTheRightmost() {
        MockHttpServletRequest request = captured("10.0.0.1",
                List.of("203.0.113.10", "198.51.100.7"), List.of());
        assertEquals("198.51.100.7", resolver.resolve(request, security("10.0.0.1/32")));
    }

    @Test
    void trustedProxyWithoutForwardedHeaderIsRejectedInsteadOfTrustingRewrittenServletAddr() {
        // 旧実装はForwardedHeaderFilterが書き換えたremoteAddrを返していた（誤った期待値の修正）。
        MockHttpServletRequest request = captured("10.0.0.1", List.of(), List.of());
        request.setRemoteAddr("203.0.113.10");
        assertDecision("PROXY_HEADER_MISSING", () -> resolver.resolve(request, security("10.0.0.1/32")));
    }

    @Test
    void servletViewWithoutValveCaptureIsNeverTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        assertDecision("SOURCE_IP_INVALID", () -> resolver.resolve(request, security("10.0.0.1/32")));

        MockHttpServletRequest peerOnly = new MockHttpServletRequest();
        peerOnly.setAttribute(ExternalApiSourceIpResolver.CONNECTOR_PEER_ATTRIBUTE, "203.0.113.10");
        assertDecision("SOURCE_IP_INVALID", () -> resolver.resolve(peerOnly, security()));
    }

    @Test
    void untrustedPeerWithAnyForwardedHeaderIsRejected() {
        MockHttpServletRequest xff = captured("198.51.100.1", List.of("203.0.113.10"), List.of());
        assertDecision("UNTRUSTED_PROXY", () -> resolver.resolve(xff, security("10.0.0.1/32")));

        MockHttpServletRequest forwarded = captured("198.51.100.1", List.of(), List.of("for=203.0.113.10"));
        assertDecision("UNTRUSTED_PROXY", () -> resolver.resolve(forwarded, security("10.0.0.1/32")));
    }

    @Test
    void malformedRightmostHopIsRejected() {
        for (String value : List.of("203.0.113.10, not-an-ip", "203.0.113.10,", "198.51.100.7:443",
                "203.0.113.10, 127.1", " ")) {
            MockHttpServletRequest request = captured("10.0.0.1", List.of(value), List.of());
            assertDecision("INVALID_PROXY_HEADER", () -> resolver.resolve(request, security("10.0.0.1/32")));
        }
    }

    @Test
    void rightmostHopInsideTrustedProxiesIsRejectedAsUnsupportedChain() {
        MockHttpServletRequest request = captured("10.0.0.1", List.of("203.0.113.10, 10.0.0.2"), List.of());
        assertDecision("UNSUPPORTED_PROXY_CHAIN", () -> resolver.resolve(request, security("10.0.0.0/24")));
    }

    @Test
    void onlyTheConfiguredHeaderTypeIsReadFromTheTrustedProxy() {
        IntegrationHubExternalApiProperties.Security forwardedMode = security("10.0.0.1/32");
        forwardedMode.setForwardedHeader(IntegrationHubExternalApiProperties.ForwardedHeader.FORWARDED);
        MockHttpServletRequest forwarded = captured("10.0.0.1", List.of("203.0.113.10"),
                List.of("for=203.0.113.10;proto=https, for=\"[2001:db8::7]\""));
        assertEquals("2001:db8:0:0:0:0:0:7", resolver.resolve(forwarded, forwardedMode));

        // X_FORWARDED_FOR構成でclientが送ったForwardedは読まない。
        MockHttpServletRequest xffMode = captured("10.0.0.1", List.of("198.51.100.7"),
                List.of("for=203.0.113.10"));
        assertEquals("198.51.100.7", resolver.resolve(xffMode, security("10.0.0.1/32")));

        MockHttpServletRequest forwardedMissing = captured("10.0.0.1", List.of("198.51.100.7"), List.of());
        assertDecision("PROXY_HEADER_MISSING", () -> resolver.resolve(forwardedMissing, forwardedMode));

        MockHttpServletRequest duplicateFor = captured("10.0.0.1", List.of(),
                List.of("for=198.51.100.7;for=203.0.113.10"));
        assertDecision("INVALID_PROXY_HEADER", () -> resolver.resolve(duplicateFor, forwardedMode));
    }

    @Test
    void oversizedForwardedHeaderBlockIsRejected() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i <= ExternalApiRawForwardedHeaders.MAX_HEADER_LINES; i++) {
            lines.add("203.0.113." + i);
        }
        MockHttpServletRequest request = captured("10.0.0.1", lines, List.of());
        assertDecision("INVALID_PROXY_HEADER", () -> resolver.resolve(request, security("10.0.0.1/32")));
        assertTrue(ExternalApiRawForwardedHeaders.of(List.of("x".repeat(ExternalApiRawForwardedHeaders.MAX_TOTAL_CHARS + 1)),
                List.of()).overflow());
        assertFalse(ExternalApiRawForwardedHeaders.of(List.of("203.0.113.10"), List.of()).overflow());
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
    void cidrSyntaxHelpersRejectBareAddressesAndReportFamilyPrefix() {
        assertTrue(ExternalApiCidrMatcher.isValidCidr("10.0.0.0/8"));
        assertFalse(ExternalApiCidrMatcher.isValidCidr("10.0.0.1"));
        assertFalse(ExternalApiCidrMatcher.isValidCidr("10.0.0.0/33"));
        assertEquals(24, ExternalApiCidrMatcher.prefixLength("::ffff:203.0.113.0/120"));
        assertTrue(ExternalApiCidrMatcher.isIpv4Cidr("::ffff:203.0.113.0/120"));
        assertFalse(ExternalApiCidrMatcher.isIpv4Cidr("2001:db8::/32"));
        assertEquals(-1, ExternalApiCidrMatcher.prefixLength("not-a-cidr"));
    }

    @Test
    void rewrittenRemoteAddrWithoutTrustedProxyUsesConnectorPeer() {
        MockHttpServletRequest request = captured("198.51.100.1", List.of(), List.of());
        request.setRemoteAddr("203.0.113.10");
        assertEquals("198.51.100.1", resolver.resolve(request, security()));
    }

    /** valveがfilter前に設定する2つのattributeを再現したrequest。 */
    static MockHttpServletRequest captured(String connectorPeer, List<String> xForwardedFor, List<String> forwarded) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(connectorPeer);
        request.setAttribute(ExternalApiSourceIpResolver.CONNECTOR_PEER_ATTRIBUTE, connectorPeer);
        request.setAttribute(ExternalApiRawForwardedHeaders.ATTRIBUTE,
                ExternalApiRawForwardedHeaders.of(xForwardedFor, forwarded));
        return request;
    }

    static IntegrationHubExternalApiProperties.Security security(String... trustedProxies) {
        IntegrationHubExternalApiProperties.Security security = new IntegrationHubExternalApiProperties.Security();
        security.getTrustedProxies().addAll(List.of(trustedProxies));
        return security;
    }

    private static void assertDecision(String decision, org.junit.jupiter.api.function.Executable executable) {
        ExternalApiSecurityException failure = assertThrows(ExternalApiSecurityException.class, executable);
        assertEquals(decision, failure.getDecision());
        assertEquals("AUTHENTICATION_FAILED", failure.getCode());
    }
}
