package com.ses.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 本番環境のHTTPS、HSTS、Cookieセキュリティ設定を検証する。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "prod"})
class ProductionSecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Test
    @WithMockUser(roles = "管理者")
    void 非HTTPSリクエストはHTTPSへリダイレクトされる() throws Exception {
        mockMvc.perform(get("/login").secure(false))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string(HttpHeaders.LOCATION, "https://localhost/login"));
    }

    @Test
    void XForwardedProtoがHTTPSならログインページへアクセスできる() throws Exception {
        mockMvc.perform(get("/login").header("X-Forwarded-Proto", "https"))
            .andExpect(status().isOk())
            .andExpect(header().string("Strict-Transport-Security",
                containsString("max-age=31536000")));
    }

    @Test
    void 本番Cookie設定が有効になっている() {
        org.junit.jupiter.api.Assertions.assertEquals("true",
            environment.getProperty("app.security.require-https"));
        org.junit.jupiter.api.Assertions.assertEquals("true",
            environment.getProperty("server.servlet.session.cookie.secure"));
        org.junit.jupiter.api.Assertions.assertEquals("lax",
            environment.getProperty("server.servlet.session.cookie.same-site"));
    }
}
