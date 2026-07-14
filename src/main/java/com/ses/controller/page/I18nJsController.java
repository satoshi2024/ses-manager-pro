package com.ses.controller.page;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.i18n.EnumMappings;
import com.ses.common.i18n.I18nMessagesLoader;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Locale;

@RestController
public class I18nJsController {

    private final I18nMessagesLoader messagesLoader;
    private final ObjectMapper mapper = new ObjectMapper();
    private String enumsJsonCache;

    public I18nJsController(I18nMessagesLoader messagesLoader) {
        this.messagesLoader = messagesLoader;
        try {
            this.enumsJsonCache = mapper.writeValueAsString(EnumMappings.GROUPS);
        } catch (JsonProcessingException e) {
            this.enumsJsonCache = "{}";
        }
    }

    @GetMapping(value = "/js/i18n.js", produces = "application/javascript;charset=UTF-8")
    public ResponseEntity<String> getI18nJs(@RequestParam(required = false) String lang, @RequestParam(required = false) String v) {
        // langパラメータを優先(URLの?lang=でキャッシュされるため、配信内容もlangに一致させる)。
        // 未指定時のみCookie由来のロケールにフォールバックする。
        Locale locale = (lang != null && !lang.isBlank())
                ? Locale.forLanguageTag(lang)
                : LocaleContextHolder.getLocale();
        String messagesJson = messagesLoader.getMessagesJson(locale);

        String js = "window.SES_LANG = '" + locale.toLanguageTag() + "';\n" +
                    "window.SES_MESSAGES = " + messagesJson + ";\n" +
                    "window.SES_ENUMS = " + enumsJsonCache + ";\n";

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(24)))
                .body(js);
    }
}
