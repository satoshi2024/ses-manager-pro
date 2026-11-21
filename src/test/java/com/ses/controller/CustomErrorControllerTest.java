package com.ses.controller;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 統一エラーコントローラーの挙動を検証する。
 * 画面遷移(HTML)には error ビュー、API/AJAXには ApiResult(JSON) を返すことを確認する。
 */
class CustomErrorControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // ダミーのViewResolverを設定し、論理ビュー名"error"が/errorに再ディスパッチ
        // されて循環参照とみなされるのを防ぐ（実アプリではThymeleafがerror.htmlを解決する）
        InternalResourceViewResolver viewResolver = new InternalResourceViewResolver();
        viewResolver.setPrefix("/WEB-INF/views/");
        viewResolver.setSuffix(".html");
        mockMvc = MockMvcBuilders.standaloneSetup(new CustomErrorController())
                .setViewResolvers(viewResolver)
                .build();
    }

    @Test
    void htmlRequest_rendersUnifiedErrorView() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/engineer/unknown")
                        .header("Accept", "text/html"))
                .andExpect(status().isOk())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("status", 404))
                .andExpect(model().attribute("title", "ページが見つかりません"));
    }

    @Test
    void apiRequest_returnsApiResultJson() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/engineers"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void ajaxRequest_returnsApiResultJson() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 403)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/user/list")
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    /** RB-QUIESCE BWQ-005: backup の書込み静止で 503 にした画面は、静止中の文言と icon を利用者の locale で出す。 */
    @Test
    void 静止中に503にした画面は静止の文言とiconをlocaleに従って描く() throws Exception {
        org.springframework.context.support.ResourceBundleMessageSource source =
                new org.springframework.context.support.ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(true);
        org.springframework.beans.factory.support.StaticListableBeanFactory beans =
                new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("messageSource", source);
        InternalResourceViewResolver viewResolver = new InternalResourceViewResolver();
        viewResolver.setPrefix("/WEB-INF/views/");
        viewResolver.setSuffix(".html");
        MockMvc localized = MockMvcBuilders.standaloneSetup(
                        new CustomErrorController(beans.getBeanProvider(org.springframework.context.MessageSource.class)))
                .setViewResolvers(viewResolver)
                .build();

        localized.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 503)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/engineer/save")
                        .requestAttr(com.ses.backup.quiesce.BackupWriteGate.REJECTED_ATTRIBUTE, Boolean.TRUE)
                        .locale(java.util.Locale.ENGLISH)
                        .header("Accept", "text/html"))
                .andExpect(status().isOk())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("status", 503))
                .andExpect(model().attribute("title", "Updates are temporarily paused"))
                .andExpect(model().attribute("message",
                        "Updates are paused while a backup is taken. Please wait a moment and try again."))
                .andExpect(model().attribute("icon", "bi-hourglass-split"));
        localized.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 503)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/engineer/save")
                        .requestAttr(com.ses.backup.quiesce.BackupWriteGate.REJECTED_ATTRIBUTE, Boolean.TRUE)
                        .locale(java.util.Locale.JAPANESE)
                        .header("Accept", "text/html"))
                .andExpect(model().attribute("title", "一時的に更新を停止しています"));
        // 属性の無い 503（外部サービスの障害等）は従来の文言のまま
        localized.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 503)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/engineer/list")
                        .header("Accept", "text/html"))
                .andExpect(model().attribute("title", "サーバーエラーが発生しました"))
                .andExpect(model().attribute("icon", "bi-exclamation-octagon"));
    }

    @Test
    void 静止の属性があっても単体の構成では日本語の固定文言にする() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 503)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/engineer/save")
                        .requestAttr(com.ses.backup.quiesce.BackupWriteGate.REJECTED_ATTRIBUTE, Boolean.TRUE)
                        .header("Accept", "text/html"))
                .andExpect(model().attribute("title", "一時的に更新を停止しています"))
                .andExpect(model().attribute("icon", "bi-hourglass-split"));
    }
}
