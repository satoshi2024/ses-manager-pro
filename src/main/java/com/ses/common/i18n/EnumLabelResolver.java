package com.ses.common.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component("enumLabels")
public class EnumLabelResolver {
    private final MessageSource messageSource;

    public EnumLabelResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String label(String group, String dbValue) {
        if (dbValue == null || dbValue.isEmpty()) {
            return "";
        }
        Map<String, String> groupMap = EnumMappings.GROUPS.get(group);
        if (groupMap == null) {
            return dbValue;
        }
        String code = groupMap.get(dbValue);
        if (code == null) {
            return dbValue;
        }
        String key = "enum." + group + "." + code;
        return messageSource.getMessage(key, null, dbValue, LocaleContextHolder.getLocale());
    }
}
