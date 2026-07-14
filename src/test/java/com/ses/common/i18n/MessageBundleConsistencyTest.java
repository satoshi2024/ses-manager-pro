package com.ses.common.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

public class MessageBundleConsistencyTest {

    /**
     * 各プロパティファイルにキーの重複が無いことを検証する。
     * java.util.Properties は重複キーを黙って後勝ちで畳むため上のテストでは検出できない。
     * 並行ブランチのマージで同一キーが二重追記された不具合の再発防止用。
     */
    @Test
    public void testNoDuplicateKeys() throws Exception {
        for (String file : List.of("messages.properties", "messages_en.properties",
                "messages_zh_CN.properties", "messages_ko.properties")) {
            Set<String> seen = new HashSet<>();
            List<String> dups = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) {
                        continue;
                    }
                    String key = t.substring(0, t.indexOf('=')).trim();
                    if (key.isEmpty()) {
                        dups.add("(空キー行) " + t);
                    } else if (!seen.add(key)) {
                        dups.add(key);
                    }
                }
            }
            assertTrue(dups.isEmpty(), file + " に重複/不正キーがあります: " + dups);
        }
    }

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
