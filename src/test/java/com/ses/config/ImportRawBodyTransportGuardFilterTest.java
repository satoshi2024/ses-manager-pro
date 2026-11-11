package com.ses.config;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportRawBodyTransportGuardFilterTest {
    private final ImportRawBodyTransportGuardFilter filter = new ImportRawBodyTransportGuardFilter();

    @Test
    void rejectsUnsupportedTransportBeforeTouchingBodyOrParameters() throws Exception {
        TrackingRequest request = validRequest();
        request.setContentType("multipart/form-data; boundary=x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertEquals(415, response.getStatus());
        assertFalse(continued.get());
        assertFalse(request.bodyTouched());
    }

    @Test
    void rejectsMissingCsrfAndOversizeLengthWithoutReadingBody() throws Exception {
        TrackingRequest missingCsrf = validRequest(null);
        MockHttpServletResponse csrfResponse = new MockHttpServletResponse();
        filter.doFilter(missingCsrf, csrfResponse, (req, res) -> { throw new AssertionError("must reject"); });
        assertEquals(403, csrfResponse.getStatus());
        assertFalse(missingCsrf.bodyTouched());

        TrackingRequest tooLarge = validRequest();
        tooLarge.declareContentLength(ImportRawBodyTransportGuardFilter.MAX_RAW_BYTES + 1);
        MockHttpServletResponse sizeResponse = new MockHttpServletResponse();
        filter.doFilter(tooLarge, sizeResponse, (req, res) -> { throw new AssertionError("must reject"); });
        assertEquals(413, sizeResponse.getStatus());
        assertFalse(tooLarge.bodyTouched());
    }

    @Test
    void allowsOnlyExactRawCsvHeaderShapeAndDoesNotReadBody() throws Exception {
        TrackingRequest request = validRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertTrue(continued.get());
        assertFalse(request.bodyTouched());
    }

    private static TrackingRequest validRequest() {
        return validRequest("non-empty-unvalidated-here");
    }

    private static TrackingRequest validRequest(String csrfToken) {
        TrackingRequest request = new TrackingRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/import-center/uploads");
        request.addHeader("Content-Type", "text/csv");
        if (csrfToken != null) request.addHeader("X-XSRF-TOKEN", csrfToken);
        request.addHeader("X-Import-Source-Code", "synthetic-a");
        request.addHeader("X-Import-Encoding", "UTF8");
        request.setContent(new byte[]{1, 2, 3});
        return request;
    }

    private static final class TrackingRequest extends MockHttpServletRequest {
        private boolean touched;
        private long declaredContentLength = -1;

        void declareContentLength(long contentLength) { declaredContentLength = contentLength; }

        @Override public long getContentLengthLong() {
            return declaredContentLength >= 0 ? declaredContentLength : super.getContentLengthLong();
        }

        @Override public ServletInputStream getInputStream() {
            touched = true;
            throw new AssertionError("guard must not read request body");
        }

        @Override public Collection<Part> getParts() {
            touched = true;
            throw new AssertionError("guard must not parse multipart body");
        }

        @Override public String getParameter(String name) {
            touched = true;
            throw new AssertionError("guard must not parse request parameters");
        }

        boolean bodyTouched() { return touched; }
    }
}
