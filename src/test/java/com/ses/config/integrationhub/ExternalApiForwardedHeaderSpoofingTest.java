package com.ses.config.integrationhub;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * N5-P2-2: 本番と同じ {@code forward-headers-strategy: framework}（実ForwardedHeaderFilter）を前段に置いても、
 * clientが先頭に書いたX-Forwarded-Forでclient CIDR allow-listを通過できないことを示す。
 *
 * <p>ALB（trusted proxy 10.0.0.1）は実際のpeer 198.51.100.7を右端へ追記する。攻撃者は
 * allow-list内の 203.0.113.10 を先頭に書く。ForwardedHeaderFilterはremoteAddrを先頭値へ書き換えるが、
 * resolverはvalveが捕捉した原文の右端だけを使う。
 */
class ExternalApiForwardedHeaderSpoofingTest {
    private static final String ALB = "10.0.0.1";
    private static final String SPOOFED_ALLOWED = "203.0.113.10";
    private static final String REAL_PEER = "198.51.100.7";
    private static final String CLIENT_ALLOWED_CIDRS = "203.0.113.0/24";

    @Test
    void realForwardedHeaderFilterRewriteCannotSpoofTheClientAllowList() throws Exception {
        Map<String, Object> valveAttributes = runValve(ALB, SPOOFED_ALLOWED + ", " + REAL_PEER);
        MockHttpServletRequest servlet = servletRequest(SPOOFED_ALLOWED + ", " + REAL_PEER, valveAttributes);

        AtomicReference<String> rewrittenRemoteAddr = new AtomicReference<>();
        AtomicBoolean forwardedHeaderVisible = new AtomicBoolean(true);
        AtomicReference<String> resolved = new AtomicReference<>();
        new ForwardedHeaderFilter().doFilter(servlet, new MockHttpServletResponse(), (req, res) -> {
            HttpServletRequest wrapped = (HttpServletRequest) req;
            rewrittenRemoteAddr.set(wrapped.getRemoteAddr());
            forwardedHeaderVisible.set(wrapped.getHeader("X-Forwarded-For") != null);
            resolved.set(new ExternalApiSourceIpResolver().resolve(wrapped, security()));
        });

        // 前提の再現: frameworkはremoteAddrをclientが書いた先頭値へ書き換え、headerを隠す。
        assertEquals(SPOOFED_ALLOWED, rewrittenRemoteAddr.get());
        assertFalse(forwardedHeaderVisible.get());
        assertTrue(ExternalApiCidrMatcher.matchesAny(rewrittenRemoteAddr.get(), CLIENT_ALLOWED_CIDRS));
        // 修正後: ALBが追記した実peerで判定され、allow-listを通過しない。
        assertEquals(REAL_PEER, resolved.get());
        assertFalse(ExternalApiCidrMatcher.matchesAny(resolved.get(), CLIENT_ALLOWED_CIDRS));
    }

    @Test
    void legitimateClientBehindTheTrustedProxyIsResolvedToItsRealAddress() throws Exception {
        Map<String, Object> valveAttributes = runValve(ALB, SPOOFED_ALLOWED);
        MockHttpServletRequest servlet = servletRequest(SPOOFED_ALLOWED, valveAttributes);

        AtomicReference<String> resolved = new AtomicReference<>();
        new ForwardedHeaderFilter().doFilter(servlet, new MockHttpServletResponse(), (req, res) ->
                resolved.set(new ExternalApiSourceIpResolver().resolve((HttpServletRequest) req, security())));

        assertEquals(SPOOFED_ALLOWED, resolved.get());
        assertTrue(ExternalApiCidrMatcher.matchesAny(resolved.get(), CLIENT_ALLOWED_CIDRS));
    }

    @Test
    void valveCapturesEveryForwardedLineWithoutChangingTheConnectorRemoteAddr() throws Exception {
        org.apache.coyote.Request coyote = new org.apache.coyote.Request();
        coyote.getMimeHeaders().addValue("X-Forwarded-For").setString(SPOOFED_ALLOWED);
        coyote.getMimeHeaders().addValue("x-forwarded-for").setString(REAL_PEER);
        coyote.getMimeHeaders().addValue("Forwarded").setString("for=" + SPOOFED_ALLOWED);
        Map<String, Object> attributes = invokeValve(coyote, ALB);

        ExternalApiRawForwardedHeaders raw = assertInstanceOf(ExternalApiRawForwardedHeaders.class,
                attributes.get(ExternalApiRawForwardedHeaders.ATTRIBUTE));
        assertEquals(List.of(SPOOFED_ALLOWED, REAL_PEER), raw.xForwardedFor());
        assertEquals(List.of("for=" + SPOOFED_ALLOWED), raw.forwarded());
        assertFalse(raw.overflow());
        assertEquals(ALB, attributes.get(ExternalApiSourceIpResolver.CONNECTOR_PEER_ATTRIBUTE));
        assertFalse(raw.toString().contains(SPOOFED_ALLOWED), "toStringへheader原文を出さない");
    }

    private static Map<String, Object> runValve(String connectorPeer, String xForwardedFor) throws Exception {
        org.apache.coyote.Request coyote = new org.apache.coyote.Request();
        coyote.getMimeHeaders().addValue("X-Forwarded-For").setString(xForwardedFor);
        return invokeValve(coyote, connectorPeer);
    }

    private static Map<String, Object> invokeValve(org.apache.coyote.Request coyote, String connectorPeer)
            throws Exception {
        Request request = mock(Request.class);
        when(request.getCoyoteRequest()).thenReturn(coyote);
        when(request.getRemoteAddr()).thenReturn(connectorPeer);
        Map<String, Object> attributes = new HashMap<>();
        doAnswer(invocation -> {
            attributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(request).setAttribute(anyString(), any());
        Valve next = mock(Valve.class);
        ExternalApiRawRequestTargetValve valve = new ExternalApiRawRequestTargetValve();
        valve.setNext(next);
        valve.invoke(request, mock(Response.class));
        return attributes;
    }

    private static MockHttpServletRequest servletRequest(String xForwardedFor, Map<String, Object> valveAttributes) {
        MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/external-api/v1/projects");
        servlet.setRemoteAddr(ALB);
        servlet.addHeader("X-Forwarded-For", xForwardedFor);
        valveAttributes.forEach(servlet::setAttribute);
        return servlet;
    }

    private static IntegrationHubExternalApiProperties.Security security() {
        IntegrationHubExternalApiProperties.Security security = new IntegrationHubExternalApiProperties.Security();
        security.getTrustedProxies().add(ALB + "/32");
        return security;
    }
}
