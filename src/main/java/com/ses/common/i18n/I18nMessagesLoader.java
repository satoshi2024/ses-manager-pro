package com.ses.common.i18n;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class I18nMessagesLoader {
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public String getMessagesJson(Locale locale) {
        String lang = locale.getLanguage();
        if (locale.getCountry() != null && !locale.getCountry().isEmpty()) {
            lang += "_" + locale.getCountry(); // e.g. zh_CN
        }
        if (lang.equals("zh-CN") || lang.equals("zh")) {
            lang = "zh_CN";
        }
        
        return cache.computeIfAbsent(lang, this::loadAndSerialize);
    }

    private String loadAndSerialize(String lang) {
        try {
            Properties merged = new Properties();
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            
            // Default
            Resource defaultRes = resolver.getResource("classpath:messages.properties");
            if (defaultRes.exists()) {
                PropertiesLoaderUtils.fillProperties(merged, defaultRes);
            }
            
            // Locale specific
            if (!lang.equals("ja") && !lang.isEmpty()) {
                Resource locRes = resolver.getResource("classpath:messages_" + lang + ".properties");
                if (locRes.exists()) {
                    PropertiesLoaderUtils.fillProperties(merged, locRes);
                }
            }

            return mapper.writeValueAsString(merged);
        } catch (IOException e) {
            return "{}";
        }
    }
}
