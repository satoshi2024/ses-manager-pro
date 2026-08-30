package com.ses.config.integrationhub;

import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tomcat connectorのraw bytesがservlet filterへ供給される境界を検証する。 */
class ExternalApiRawRequestTargetValveTest {
    @Test
    void connectorBytesAreCopiedBeforeServletNormalization() throws Exception {
        org.apache.coyote.Request coyote = new org.apache.coyote.Request();
        byte[] path = "/external-api/v1/projects".getBytes(StandardCharsets.US_ASCII);
        byte[] query = "b=2&a=1".getBytes(StandardCharsets.US_ASCII);
        coyote.requestURI().setBytes(path, 0, path.length);
        coyote.queryString().setBytes(query, 0, query.length);
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        when(request.getCoyoteRequest()).thenReturn(coyote);
        AtomicReference<byte[]> captured = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            captured.set((byte[]) invocation.getArgument(1));
            return null;
        }).when(request).setAttribute(org.mockito.ArgumentMatchers.eq(ExternalApiCanonicalRequest.RAW_TARGET_ATTRIBUTE),
                org.mockito.ArgumentMatchers.any());
        Valve next = mock(Valve.class);
        org.mockito.Mockito.doNothing().when(next).invoke(request, response);

        ExternalApiRawRequestTargetValve valve = new ExternalApiRawRequestTargetValve();
        valve.setNext(next);
        valve.invoke(request, response);

        assertArrayEquals("/external-api/v1/projects?b=2&a=1".getBytes(StandardCharsets.US_ASCII), captured.get());
    }

    @Test
    void missingConnectorBytesRemainUnavailableAndAreRejectedByCanonicalizer() throws Exception {
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        when(request.getCoyoteRequest()).thenReturn(new org.apache.coyote.Request());
        Valve next = mock(Valve.class);
        ExternalApiRawRequestTargetValve valve = new ExternalApiRawRequestTargetValve();
        valve.setNext(next);
        valve.invoke(request, response);
        org.mockito.Mockito.verify(request, org.mockito.Mockito.never())
                .setAttribute(org.mockito.ArgumentMatchers.eq(ExternalApiCanonicalRequest.RAW_TARGET_ATTRIBUTE),
                        org.mockito.ArgumentMatchers.any());
    }
}
