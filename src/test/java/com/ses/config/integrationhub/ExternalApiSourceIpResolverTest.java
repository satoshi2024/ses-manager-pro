package com.ses.config.integrationhub;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
