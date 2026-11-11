package com.ses.controller.api;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** raw upload guardの後段にあるCookieCsrfTokenRepository検証も無効化されていないことを確認する。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportCenterApiSecurityTest {
    @Autowired private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "管理者")
    void missingCsrfCookieIsRejectedEvenWhenHeaderIsNonEmpty() throws Exception {
        mockMvc.perform(validRawUpload().header("X-XSRF-TOKEN", "present-but-no-cookie"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void invalidCsrfTokenIsRejectedAfterTransportGuardPasses() throws Exception {
        mockMvc.perform(validRawUpload()
                        .cookie(new Cookie("XSRF-TOKEN", "cookie-token"))
                        .header("X-XSRF-TOKEN", "not-the-cookie-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void cookieHeaderMismatchIsRejected() throws Exception {
        mockMvc.perform(validRawUpload()
                        .cookie(new Cookie("XSRF-TOKEN", "token-a"))
                        .header("X-XSRF-TOKEN", "token-b"))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRawUpload() {
        return post("/api/import-center/uploads")
                .contentType(MediaType.valueOf("text/csv"))
                .header("X-Import-Source-Code", "synthetic-a")
                .header("X-Import-Encoding", "UTF8")
                .content("externalKey,companyName\nC-SYN-1,Example\n");
    }
}
