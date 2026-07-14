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

    @GetMapping(value = "/js/i18n.js", produces = "application/javascript")
    public ResponseEntity<String> getI18nJs(@RequestParam(required = false) String lang, @RequestParam(required = false) String v) {
        String messagesJson = messagesLoader.getMessagesJson(LocaleContextHolder.getLocale());
        
        String js = "window.SES_LANG = '" + LocaleContextHolder.getLocale().toLanguageTag() + "';\n" +
                    "window.SES_MESSAGES = " + messagesJson + ";\n" +
                    "window.SES_ENUMS = " + enumsJsonCache + ";\n";

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(24)))
                .body(js);
    }
}
