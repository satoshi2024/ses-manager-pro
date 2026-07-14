package com.ses.common.util;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

@Component
public class MessageUtils {

    private static MessageSource messageSource;
    private final MessageSource injectedMessageSource;

    public MessageUtils(MessageSource injectedMessageSource) {
        this.injectedMessageSource = injectedMessageSource;
    }

    @PostConstruct
    public void init() {
        MessageUtils.messageSource = this.injectedMessageSource;
    }

    public static String getMessage(String key, Object... args) {
        if (messageSource == null) {
            return key;
        }
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
