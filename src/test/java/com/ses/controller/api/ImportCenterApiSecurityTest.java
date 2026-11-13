package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.importcenter.metadata.ImportCsvIngestionService;
import com.ses.importcenter.metadata.ImportMetadataAuthorizationService;
import com.ses.importcenter.metadata.ImportUploadJobDetail;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.AuditLogService;
import com.ses.config.OidcSecurityProperties;
import com.ses.service.security.AuthorizationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * real security chainのCsrfFilterが拒否し、その間raw requestを未読に保つことを確認する。
 *
 * <p>共有H2を汚さないよう{@code @Transactional}で各testをrollbackする（TestIsolationAuditTest）。
 * MockMvcはtest threadで同期実行されるためfilter/controllerもtest transaction内で動くが、
 * ingestionは{@code @MockBean}で実{@code ImportCsvIngestionService}（外側transaction拒否の守衛）や
 * {@code ImportCsvUploadCommitService}へは到達しない。guard/CSRFの判定とGlobalExceptionHandlerの
 * status変換はDB transactionに依存しないため、検証内容は変わらない。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(ImportCenterApiSecurityTest.TrackingFilterConfiguration.class)
class ImportCenterApiSecurityTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private BodyTrackingFilter bodyTrackingFilter;
    @Autowired private OidcSecurityProperties oidcSecurityProperties;
    @MockBean private AuthorizationService authorizationService;
    @MockBean private ImportCsvIngestionService ingestion;
    @MockBean private AuditLogService auditLogService;
    private UserDetails testPrincipal;

    @BeforeEach
    void prepareReachableController() {
        bodyTrackingFilter.reset();
        String tenantId = oidcSecurityProperties.getTenantId();
        if (tenantId == null || tenantId.isBlank()) tenantId = "nf06-csrf-test";
        var authorities = java.util.List.of(new SimpleGrantedAuthority("ROLE_管理者"));
        SysUser user = SysUser.builder().username("nf06-csrf-test").password("test-only")
                .realName("CSRFテスト").role("管理者").status(1).tenantId(tenantId).build();
        testPrincipal = new LoginUser(user, authorities, tenantId);
        when(authorizationService.isAllowed(any(Authentication.class),
                eq(ImportMetadataAuthorizationService.ACTION))).thenReturn(true);
        when(ingestion.upload(any(), any())).thenReturn(
                new ImportUploadJobDetail(1L, "UPLOADED", "CUSTOMER", "0123456789ab", 1, "UTF8"));
    }

    @Test
    void missingCsrfCookieIsRejectedBeforeControllerAndBodyRead() throws Exception {
        mockMvc.perform(validRawUpload().header("X-XSRF-TOKEN", "present-but-no-cookie"))
                .andExpect(status().isForbidden());

        verify(ingestion, never()).upload(any(), any());
        assertFalse(bodyTrackingFilter.bodyTouched());
    }

    @Test
    void invalidAndMismatchedCookieTokensAreRejectedBeforeBodyRead() throws Exception {
        mockMvc.perform(validRawUpload().cookie(new Cookie("XSRF-TOKEN", "cookie-token"))
                        .header("X-XSRF-TOKEN", "invalid-token"))
                .andExpect(status().isForbidden());
        assertFalse(bodyTrackingFilter.bodyTouched());

        bodyTrackingFilter.reset();
        mockMvc.perform(validRawUpload().cookie(new Cookie("XSRF-TOKEN", "token-a"))
                        .header("X-XSRF-TOKEN", "token-b"))
                .andExpect(status().isForbidden());
        verify(ingestion, never()).upload(any(), any());
        assertFalse(bodyTrackingFilter.bodyTouched());
    }

    @Test
    void matchingCookieAndHeaderPassCsrfAndReachControllerWithoutOpeningLazyBody() throws Exception {
        mockMvc.perform(validRawUpload().cookie(new Cookie("XSRF-TOKEN", "matching-token"))
                        .header("X-XSRF-TOKEN", "matching-token"))
                .andExpect(status().isOk());

        verify(ingestion).upload(any(), any());
        assertFalse(bodyTrackingFilter.bodyTouched());
    }

    @Test
    void ingressDeadlineExceededIsReturnedAsServiceUnavailableThroughTheRealErrorHandler() throws Exception {
        when(ingestion.upload(any(), any()))
                .thenThrow(BusinessException.of(503, "error.import.uploadDeadlineExceeded"));

        mockMvc.perform(validRawUpload().cookie(new Cookie("XSRF-TOKEN", "matching-token"))
                        .header("X-XSRF-TOKEN", "matching-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value(
                        "アップロード処理が制限時間（120秒）内に完了しませんでした。時間をおいて再試行してください。"));
    }

    @Test
    void serviceLevelSizeLimitIsReturnedAsPayloadTooLargeEndToEnd() throws Exception {
        when(ingestion.upload(any(), any())).thenThrow(BusinessException.of(413, "error.import.uploadTooLarge"));

        // B-NF06-4：chunked等でservice層に届いた超過も、SafeErrorPolicy/GlobalExceptionHandlerを通って413になる。
        mockMvc.perform(validRawUpload().cookie(new Cookie("XSRF-TOKEN", "matching-token"))
                        .header("X-XSRF-TOKEN", "matching-token"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(413))
                .andExpect(jsonPath("$.message").value("アップロードできるファイルサイズの上限（64MiB）を超えています。"));
    }

    @Test
    void serverSideTempStorageFailureIsReturnedAsServerError() throws Exception {
        when(ingestion.upload(any(), any())).thenThrow(BusinessException.of(500, "error.import.tempStorageFailed"));

        mockMvc.perform(validRawUpload().cookie(new Cookie("XSRF-TOKEN", "matching-token"))
                        .header("X-XSRF-TOKEN", "matching-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void charsetContradictingTheSelectedEncodingIsRejectedBeforeCsrfControllerAndBody() throws Exception {
        mockMvc.perform(rawUpload("text/csv;charset=UTF-8", "SHIFT_JIS")
                        .cookie(new Cookie("XSRF-TOKEN", "matching-token"))
                        .header("X-XSRF-TOKEN", "matching-token"))
                .andExpect(status().isUnsupportedMediaType())
                // A-NF06-1：filterもmessage keyを出さず、bundleの文言（既定locale=日本語）で返す。
                .andExpect(jsonPath("$.message").value(
                        "CSVファイル（text/csv、文字コードはUTF-8またはShift_JIS）のみアップロードできます。"));

        verify(ingestion, never()).upload(any(), any());
        assertFalse(bodyTrackingFilter.bodyTouched());
    }

    @Test
    void contentTypeParameterOtherThanCharsetIsRejectedBeforeCsrfControllerAndBody() throws Exception {
        mockMvc.perform(rawUpload("text/csv; header=present", "UTF8")
                        .cookie(new Cookie("XSRF-TOKEN", "matching-token"))
                        .header("X-XSRF-TOKEN", "matching-token"))
                .andExpect(status().isUnsupportedMediaType());

        verify(ingestion, never()).upload(any(), any());
        assertFalse(bodyTrackingFilter.bodyTouched());
    }

    @Test
    void shiftJisCharsetMatchingTheSelectedEncodingReachesController() throws Exception {
        mockMvc.perform(rawUpload("text/csv;charset=Shift_JIS", "SHIFT_JIS")
                        .cookie(new Cookie("XSRF-TOKEN", "matching-token"))
                        .header("X-XSRF-TOKEN", "matching-token"))
                .andExpect(status().isOk());

        verify(ingestion).upload(any(), any());
        assertFalse(bodyTrackingFilter.bodyTouched());
    }

    /**
     * MockMvcではSpring BootのCharacterEncodingFilter（server.servlet.encoding.force=true）が
     * {@code setCharacterEncoding("UTF-8")}を呼び、MockHttpServletRequestはcharsetを持たないContent-Type headerへ
     * {@code ;charset=UTF-8}を付け足す（実Tomcatではrequest headerは書き換わらない）。guardの判定対象を固定するため、
     * 既定requestではcharsetを明示した{@code text/csv;charset=UTF-8}を送る。素の{@code text/csv}はguard単体testで確認する。
     */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRawUpload() {
        return rawUpload("text/csv;charset=UTF-8", "UTF8");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rawUpload(String contentType,
                                                                                                String encoding) {
        return post("/api/import-center/uploads")
                .with(user(testPrincipal))
                .contentType(contentType)
                .header("X-Import-Source-Code", "synthetic-a")
                .header("X-Import-Encoding", encoding)
                .content("externalKey,companyName\nC-SYN-1,Example\n");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TrackingFilterConfiguration {
        @Bean BodyTrackingFilter bodyTrackingFilter() { return new BodyTrackingFilter(); }

        @Bean FilterRegistrationBean<BodyTrackingFilter> bodyTrackingRegistration(BodyTrackingFilter filter) {
            FilterRegistrationBean<BodyTrackingFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }

    static class BodyTrackingFilter extends OncePerRequestFilter {
        private final AtomicBoolean touched = new AtomicBoolean();
        void reset() { touched.set(false); }
        boolean bodyTouched() { return touched.get(); }

        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                   FilterChain chain) throws ServletException, IOException {
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() throws IOException {
                    touched.set(true);
                    return super.getInputStream();
                }
                @Override public BufferedReader getReader() throws IOException {
                    touched.set(true);
                    return super.getReader();
                }
                @Override public Collection<Part> getParts() throws IOException, ServletException {
                    touched.set(true);
                    return super.getParts();
                }
                @Override public String getParameter(String name) {
                    touched.set(true);
                    return super.getParameter(name);
                }
                @Override public Map<String, String[]> getParameterMap() {
                    touched.set(true);
                    return super.getParameterMap();
                }
                @Override public java.util.Enumeration<String> getParameterNames() {
                    touched.set(true);
                    return super.getParameterNames();
                }
                @Override public String[] getParameterValues(String name) {
                    touched.set(true);
                    return super.getParameterValues(name);
                }
            }, response);
        }
    }
}
