package com.ses.web;

import com.ses.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locale切替基盤(CookieLocaleResolver + LocaleChangeInterceptor)の結合テスト。
 * /login はSecurityConfigでpermitAll済みのため未認証でも検証可能。
 */
@DisplayName("i18n Locale切替 結合テスト")
public class I18nLocaleSwitchTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Cookieなしで /login にアクセスすると日本語表示になる（フォールバック）")
    void noCookie_defaultsToJapanese() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ログイン")));
    }

    @Test
    @DisplayName("?lang=en で SES_LOCALE Cookie が書き込まれ、英語表示になる")
    void langParam_en_setsCookieAndRendersEnglish() throws Exception {
        var result = mockMvc.perform(get("/login").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Login")))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        Cookie localeCookie = response.getCookie("SES_LOCALE");
        assertThat(localeCookie).isNotNull();
        assertThat(localeCookie.getValue()).contains("en");
    }

    @Test
    @DisplayName("SES_LOCALE=zh-CN Cookie を送ると中国語で描画される")
    void cookieZhCN_rendersChinese() throws Exception {
        mockMvc.perform(get("/login").cookie(new Cookie("SES_LOCALE", "zh-CN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("登录")));
    }

    @Test
    @DisplayName("SES_LOCALE=ko Cookie を送ると韓国語で描画される")
    void cookieKo_rendersKorean() throws Exception {
        mockMvc.perform(get("/login").cookie(new Cookie("SES_LOCALE", "ko")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인")));
    }

    @Test
    @DisplayName("不正な lang 値でも 500 にならない（ignoreInvalidLocale）")
    void invalidLangParam_doesNotFail() throws Exception {
        mockMvc.perform(get("/login").param("lang", "not-a-locale"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("<html lang> 属性が現在ロケールに追従する")
    void htmlLangAttribute_followsLocale() throws Exception {
        mockMvc.perform(get("/login").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("lang=\"en\"")));
    }
}
