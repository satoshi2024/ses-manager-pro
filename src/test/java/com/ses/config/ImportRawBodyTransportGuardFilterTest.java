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
            // message keyは応答へ出さない（MessageSource無しの単体ではstatus別の固定文言）。
            assertFalse(response.getContentAsString().contains("error.import."));
            assertTrue(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                    .contains("入力内容を確認してください。"));
            assertFalse(continued.get());
            assertFalse(request.bodyTouched());
        }
    }

    @Test
    void rejectionResponsesNeverExposeRawMessageKeys() throws Exception {
        TrackingRequest wrongMethod = validRequest();
        wrongMethod.setMethod("PUT");
        TrackingRequest missingCsrf = validRequest(null);
        TrackingRequest badHeaders = requestWith("text/csv", "EUC_JP");
        TrackingRequest tooLarge = validRequest();
        tooLarge.declareContentLength(ImportRawBodyTransportGuardFilter.MAX_RAW_BYTES + 1);
        TrackingRequest compressed = validRequest();
        compressed.addHeader("Content-Encoding", "gzip");
        List<Object[]> cases = List.of(
                new Object[]{wrongMethod, 405, "入力内容を確認してください。"},
                new Object[]{missingCsrf, 403, "アクセス権限がありません。"},
                new Object[]{badHeaders, 400, "入力内容を確認してください。"},
                new Object[]{tooLarge, 413, "送信データのサイズが上限を超えています。"},
                new Object[]{compressed, 415, "入力内容を確認してください。"});
        for (Object[] rejection : cases) {
            TrackingRequest request = (TrackingRequest) rejection[0];
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (req, res) -> { throw new AssertionError("must reject"); });

            String body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(rejection[1], response.getStatus());
            assertTrue(body.contains("\"code\":" + rejection[1]), body);
            assertTrue(body.contains((String) rejection[2]), body);
            assertFalse(body.contains("error."), "message keyを応答へ出さない: " + body);
            assertFalse(request.bodyTouched());
        }
    }

    @Test
    void rejectionMessageIsResolvedFromTheBundleWithTheApplicationLocaleResolver() throws Exception {
        org.springframework.context.support.StaticMessageSource messages =
                new org.springframework.context.support.StaticMessageSource();
        messages.addMessage("error.import.uploadMediaType", java.util.Locale.ENGLISH,
                "Only CSV files (text/csv, encoded in UTF-8 or Shift_JIS) can be uploaded.");
        org.springframework.beans.factory.support.StaticListableBeanFactory beans =
                new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("messageSource", messages);
        beans.addBean("localeResolver",
                new org.springframework.web.servlet.i18n.FixedLocaleResolver(java.util.Locale.ENGLISH));
        ImportRawBodyTransportGuardFilter localized = new ImportRawBodyTransportGuardFilter(
                beans.getBeanProvider(org.springframework.context.MessageSource.class),
                beans.getBeanProvider(org.springframework.web.servlet.LocaleResolver.class));
        TrackingRequest request = requestWith("text/csv; header=present", "UTF8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        localized.doFilter(request, response, (req, res) -> { throw new AssertionError("must reject"); });

        assertEquals(415, response.getStatus());
        assertTrue(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("Only CSV files (text/csv, encoded in UTF-8 or Shift_JIS) can be uploaded."));
        assertFalse(request.bodyTouched());
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
