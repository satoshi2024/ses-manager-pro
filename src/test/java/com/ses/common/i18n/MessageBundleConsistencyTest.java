package com.ses.common.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

public class MessageBundleConsistencyTest {

    @Test
    public void testMessageBundleConsistency() throws Exception {
        Properties ja = PropertiesLoaderUtils.loadProperties(new ClassPathResource("messages.properties"));
        Properties en = PropertiesLoaderUtils.loadProperties(new ClassPathResource("messages_en.properties"));
        Properties zh = PropertiesLoaderUtils.loadProperties(new ClassPathResource("messages_zh_CN.properties"));
        Properties ko = PropertiesLoaderUtils.loadProperties(new ClassPathResource("messages_ko.properties"));

        Set<String> jaKeys = ja.stringPropertyNames();
        
        Set<String> missingInEn = new HashSet<>(jaKeys); missingInEn.removeAll(en.stringPropertyNames());
        Set<String> missingInZh = new HashSet<>(jaKeys); missingInZh.removeAll(zh.stringPropertyNames());
        Set<String> missingInKo = new HashSet<>(jaKeys); missingInKo.removeAll(ko.stringPropertyNames());
        
        Set<String> extraInEn = new HashSet<>(en.stringPropertyNames()); extraInEn.removeAll(jaKeys);
        Set<String> extraInZh = new HashSet<>(zh.stringPropertyNames()); extraInZh.removeAll(jaKeys);
        Set<String> extraInKo = new HashSet<>(ko.stringPropertyNames()); extraInKo.removeAll(jaKeys);
        
        assertTrue(missingInEn.isEmpty() && extraInEn.isEmpty(), "EN keys mismatch. Missing: " + missingInEn + ", Extra: " + extraInEn);
        assertTrue(missingInZh.isEmpty() && extraInZh.isEmpty(), "ZH keys mismatch. Missing: " + missingInZh + ", Extra: " + extraInZh);
        assertTrue(missingInKo.isEmpty() && extraInKo.isEmpty(), "KO keys mismatch. Missing: " + missingInKo + ", Extra: " + extraInKo);

        Pattern p = Pattern.compile("\\{\\d+\\}");

        for (String key : jaKeys) {
            String jaVal = ja.getProperty(key);
            String enVal = en.getProperty(key);
            String zhVal = zh.getProperty(key);
            String koVal = ko.getProperty(key);

            assertFalse(jaVal.trim().isEmpty(), "Empty value in JA for key: " + key);
            assertFalse(enVal.trim().isEmpty(), "Empty value in EN for key: " + key);
            assertFalse(zhVal.trim().isEmpty(), "Empty value in ZH for key: " + key);
            assertFalse(koVal.trim().isEmpty(), "Empty value in KO for key: " + key);

            Set<String> jaArgs = extractArgs(p, jaVal);
            assertEquals(jaArgs, extractArgs(p, enVal), "EN args mismatch for key: " + key);
            assertEquals(jaArgs, extractArgs(p, zhVal), "ZH args mismatch for key: " + key);
            assertEquals(jaArgs, extractArgs(p, koVal), "KO args mismatch for key: " + key);
        }

        for (Map.Entry<String, Map<String, String>> entry : EnumMappings.GROUPS.entrySet()) {
            String group = entry.getKey();
            for (String code : entry.getValue().values()) {
                String enumKey = "enum." + group + "." + code;
                assertTrue(jaKeys.contains(enumKey), "Missing enum key in properties: " + enumKey);
            }
        }
    }

    private Set<String> extractArgs(Pattern p, String val) {
        Set<String> args = new HashSet<>();
        Matcher m = p.matcher(val);
        while (m.find()) {
            args.add(m.group());
        }
        return args;
    }
}
