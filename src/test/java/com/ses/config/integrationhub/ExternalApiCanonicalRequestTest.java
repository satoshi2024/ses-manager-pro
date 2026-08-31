package com.ses.config.integrationhub;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalApiCanonicalRequestTest {
    @Test
    void goldenVectorMatchesDesign() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/external-api/v1/project");
        request.setAttribute(ExternalApiCanonicalRequest.RAW_TARGET_ATTRIBUTE,
                "/external-api/v1/project?a=&a=one&b=2&flag".getBytes(StandardCharsets.US_ASCII));
        ExternalApiCanonicalRequest.Parsed parsed = ExternalApiCanonicalRequest.parse(request, new byte[0]);

        assertEquals("/external-api/v1/project?a=&a=one&b=2&flag=", parsed.canonicalTarget());
        byte[] signed = ExternalApiCanonicalRequest.signedBytes("client-a", "1", "key-1",
                "1788048000", "AQIDBAUGBwgJCgsMDQ4PEA", "GET", parsed.canonicalTarget(), parsed.bodySha256());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertEquals("UGMa5HEXan7nOe2RtY8RO_x4TgNXuaBZ0QMA7RaVz2A",
                Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(signed)));
    }

    @Test
    void queryAndPercentEncodingAreDeterministic() {
        assertEquals("/a/%2F/~?a=%2B&empty=&encoded=~&flag=",
                ExternalApiCanonicalRequest.canonicalizeTarget("/a/%2f/%7E?flag&encoded=%7e&empty=&a=+"));
    }

    @Test
    void malformedQueryAndRawTargetFailClosed() {
        assertThrows(ExternalApiSecurityException.class,
                () -> ExternalApiCanonicalRequest.canonicalizeTarget("/a?x=1&&y=2"));
        assertThrows(ExternalApiSecurityException.class,
                () -> ExternalApiCanonicalRequest.canonicalizeTarget("/a?x=%0"));
        assertThrows(ExternalApiSecurityException.class,
                () -> ExternalApiCanonicalRequest.canonicalizeTarget("https://example.test/a"));
    }
}
