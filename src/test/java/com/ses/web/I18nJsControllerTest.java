package com.ses.web;

import com.ses.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JS辞書エンドポイント(/js/i18n.js)の結合テスト。
 * /js/** はpermitAll済みのため未認証でも検証できる。
 * 特に「辞書内の多言語文言が文字化けしないこと」を回帰テストとして担保する
 * (PropertiesをISO-8859-1で読むと日本語・中国語・韓国語が全滅するため)。
 */
@DisplayName("i18n JS辞書エンドポイント 結合テスト")
public class I18nJsControllerTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("未認証でも200・application/javascript・Cache-Controlを返す")
    void servesJsUnauthenticated() throws Exception {
        mockMvc.perform(get("/js/i18n.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/javascript")))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("charset=UTF-8")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age")));
    }

    @Test
    @DisplayName("SES_MESSAGES / SES_ENUMS / SES_LANG が定義される")
    void definesGlobals() throws Exception {
        String body = mockMvc.perform(get("/js/i18n.js").param("lang", "ja"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("window.SES_LANG");
        assertThat(body).contains("window.SES_MESSAGES");
        assertThat(body).contains("window.SES_ENUMS");
    }

    @Test
    @DisplayName("★回帰: 日本語辞書が文字化けしない(menu.engineer=要員管理)")
    void japaneseDictionaryNotMojibake() throws Exception {
        String body = mockMvc.perform(get("/js/i18n.js").param("lang", "ja"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // ISO-8859-1で読むと "要員管理" は "è¦..." に化ける。正しい原文が載っていることを検証。
        assertThat(body).contains("要員管理");
        assertThat(body).doesNotContain("è¦");
    }

    @Test
    @DisplayName("★回帰: lang=zh-CN で中国語辞書が文字化けせず載る(menu.engineer=人员管理)")
    void chineseDictionaryByLangParam() throws Exception {
        String body = mockMvc.perform(get("/js/i18n.js").param("lang", "zh-CN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("人员管理");
        assertThat(body).contains("window.SES_LANG = 'zh-CN'");
    }

    @Test
    @DisplayName("不正な lang 値でも 500 にならない")
    void invalidLang_doesNotFail() throws Exception {
        mockMvc.perform(get("/js/i18n.js").param("lang", "not-a-locale"))
                .andExpect(status().isOk());
    }
}
