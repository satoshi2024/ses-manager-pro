package com.ses.common.i18n;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
            
            // Default（.propertiesはUTF-8で明示的に読む。既定のISO-8859-1では日本語・中国語・韓国語が文字化けする）
            Resource defaultRes = resolver.getResource("classpath:messages.properties");
            if (defaultRes.exists()) {
                PropertiesLoaderUtils.fillProperties(merged, new EncodedResource(defaultRes, StandardCharsets.UTF_8));
            }

            // Locale specific
            if (!lang.equals("ja") && !lang.isEmpty()) {
                Resource locRes = resolver.getResource("classpath:messages_" + lang + ".properties");
                if (locRes.exists()) {
                    PropertiesLoaderUtils.fillProperties(merged, new EncodedResource(locRes, StandardCharsets.UTF_8));
                }
            }

            return mapper.writeValueAsString(merged);
        } catch (IOException e) {
            return "{}";
        }
    }
}
