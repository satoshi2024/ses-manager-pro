package com.ses.config;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * guard単体。MockHttpServletRequestへheaderを直接設定し、CharacterEncodingFilterを通さないため、
 * Content-Type headerは送信値のまま判定される。
 */
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

    @Test
    void plainTextCsvIsAcceptedForBothSelectableEncodings() throws Exception {
        for (String encoding : List.of("UTF8", "SHIFT_JIS")) {
            assertAccepted(requestWith("text/csv", encoding));
        }
    }

    @Test
    void charsetParameterIsAcceptedOnlyWhenItMatchesTheSelectedEncoding() throws Exception {
        assertAccepted(requestWith("text/csv; charset=utf-8", "UTF8"));
        assertAccepted(requestWith("TEXT/CSV;charset=UTF-8", "UTF8"));
        assertAccepted(requestWith("text/csv ; charset=Shift_JIS", "SHIFT_JIS"));
        assertAccepted(requestWith("text/csv;charset=SHIFT_JIS", "SHIFT_JIS"));
    }

    @Test
    void contradictingOtherQuotedOrExtraParametersAreRejectedAsUnsupportedMediaType() throws Exception {
        List<String[]> rejected = List.of(
                new String[]{"text/csv; charset=utf-8", "SHIFT_JIS"},
                new String[]{"text/csv; charset=shift_jis", "UTF8"},
                new String[]{"text/csv; charset=iso-8859-1", "UTF8"},
                new String[]{"text/csv; charset=utf8", "UTF8"},
                new String[]{"text/csv; charset=windows-31j", "SHIFT_JIS"},
                new String[]{"text/csv; charset=\"utf-8\"", "UTF8"},
                new String[]{"text/csv; charset = utf-8", "UTF8"},
                new String[]{"text/csv; header=present", "UTF8"},
                new String[]{"text/csv; charset=utf-8; header=present", "UTF8"},
                new String[]{"text/csv; charset=utf-8; charset=utf-8", "UTF8"},
                new String[]{"text/csv;", "UTF8"},
                new String[]{"text/csvx", "UTF8"},
                new String[]{"text/plain; charset=utf-8", "UTF8"});
        for (String[] contentTypeAndEncoding : rejected) {
            TrackingRequest request = requestWith(contentTypeAndEncoding[0], contentTypeAndEncoding[1]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean continued = new AtomicBoolean();

            filter.doFilter(request, response, (req, res) -> continued.set(true));

            assertEquals(415, response.getStatus(), "拒否されるべきContent-Type: " + contentTypeAndEncoding[0]
                    + " / X-Import-Encoding: " + contentTypeAndEncoding[1]);
            assertTrue(response.getContentAsString().contains("error.import.uploadMediaType"));
            assertFalse(continued.get());
            assertFalse(request.bodyTouched());
        }
    }

    @Test
    void duplicatedContentTypeHeadersAreRejectedAsAmbiguous() throws Exception {
        TrackingRequest request = validRequest();
        request.addHeader("Content-Type", "text/csv");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertEquals(415, response.getStatus());
        assertFalse(continued.get());
        assertFalse(request.bodyTouched());
    }

    private void assertAccepted(TrackingRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> continued.set(true));

        assertTrue(continued.get(), "許可されるべきContent-Type: " + request.getHeader("Content-Type")
                + " / X-Import-Encoding: " + request.getHeader("X-Import-Encoding"));
        assertEquals(200, response.getStatus());
        assertFalse(request.bodyTouched());
    }

    private static TrackingRequest validRequest() {
        return validRequest("non-empty-unvalidated-here");
    }

    private static TrackingRequest validRequest(String csrfToken) {
        return request("text/csv", "UTF8", csrfToken);
    }

    private static TrackingRequest requestWith(String contentType, String encoding) {
        return request(contentType, encoding, "non-empty-unvalidated-here");
    }

    private static TrackingRequest request(String contentType, String encoding, String csrfToken) {
        TrackingRequest request = new TrackingRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/import-center/uploads");
        request.addHeader("Content-Type", contentType);
        if (csrfToken != null) request.addHeader("X-XSRF-TOKEN", csrfToken);
        request.addHeader("X-Import-Source-Code", "synthetic-a");
        request.addHeader("X-Import-Encoding", encoding);
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
