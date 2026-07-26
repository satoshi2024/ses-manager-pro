package com.ses.common.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

public class MessageBundleConsistencyTest {

    /** 出荷する唯一のバンドル構成。messages.properties 自体が日本語（基底）である。 */
    private static final Set<String> EXPECTED_BUNDLES = new TreeSet<>(List.of(
            "messages.properties", "messages_en.properties",
            "messages_zh_CN.properties", "messages_ko.properties"));

    /**
     * 想定外のバンドルファイルが存在しないことを検証する。
     *
     * <p>他のテストは対象ファイルをハードコードしていたため、リストに無いバンドルは
     * 完全に検査の外に居た。実際 {@code messages_ja.properties} と
     * {@code messages_vi.properties} がそこに紛れ込み、次の不具合を起こしていた。
     *
     * <p><b>messages_ja が基底を上書きしていた問題</b>: Spring の MessageSource は
     * 日本語ロケールに対し {@code messages_ja} → {@code messages} の順で解決するため、
     * {@code messages_ja} に書かれたキーは基底より優先される。一方
     * {@link I18nMessagesLoader} は {@code lang.equals("ja")} のとき locale 別ファイルを
     * 読まない実装なので、JS 側（{@code SES.i18n.t}）には基底の値が渡る。
     * 結果、同じキーがサーバレンダリング（Thymeleaf の {@code #{...}}）と
     * クライアントレンダリングで別の文字列になっていた——案件一覧では
     * 絞り込みの選択肢が「終了」、同じ画面の状態バッジが「クローズ」と表示されていた。
     * 基底が日本語である以上 {@code messages_ja} は存在してはならない。
     *
     * <p>言語を追加するときは、4バンドル全てにキーを揃えた上でこの定数を更新すること。
     */
    @Test
    public void testNoUnexpectedBundleFiles() throws Exception {
        Set<String> found = new TreeSet<>();
        for (org.springframework.core.io.Resource r : new org.springframework.core.io.support
                .PathMatchingResourcePatternResolver().getResources("classpath*:messages*.properties")) {
            found.add(String.valueOf(r.getFilename()));
        }

        Set<String> unexpected = new TreeSet<>(found);
        unexpected.removeAll(EXPECTED_BUNDLES);
        assertTrue(unexpected.isEmpty(),
                "想定外のメッセージバンドルがあります。ロケール別ファイルは基底(messages.properties)より"
                        + "優先されるため、Thymeleaf と JS で表示文言が食い違う原因になります: " + unexpected);

        Set<String> absent = new TreeSet<>(EXPECTED_BUNDLES);
        absent.removeAll(found);
        assertTrue(absent.isEmpty(), "必要なメッセージバンドルが見つかりません: " + absent);
    }

    /**
     * Thymeleafテンプレートが参照している {@code #{...}} のキーが、実際に messages.properties に
     * 存在することを検証する。
     *
     * <p>{@code spring.messages.use-code-as-default-message: true} のため、キーが欠落しても例外にはならず
     * 画面にキー文字列（例: {@code menu.bpAvailability}）がそのまま表示されるだけで、テストも通ってしまう。
     * 「欠落はテストで検出」という application.yml のコメントどおりの検査を、ここで担保する。
     */
    @Test
    public void testTemplateMessageKeysExist() throws Exception {
        Properties ja = PropertiesLoaderUtils.loadProperties(new ClassPathResource("messages.properties"));
        Set<String> definedKeys = ja.stringPropertyNames();

        // 連結（#{a} + ${b}）や式内の動的キーは静的解析できないため、単純キーのみを対象にする
        Pattern keyPattern = Pattern.compile("#\\{([a-zA-Z0-9_.\\-]+)\\}");
        Map<String, Set<String>> missingByTemplate = new TreeMap<>();

        for (org.springframework.core.io.Resource resource : new org.springframework.core.io.support
                .PathMatchingResourcePatternResolver().getResources("classpath:templates/**/*.html")) {
            String content;
            try (InputStream in = resource.getInputStream()) {
                content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Matcher m = keyPattern.matcher(content);
            Set<String> missing = new TreeSet<>();
            while (m.find()) {
                if (!definedKeys.contains(m.group(1))) {
                    missing.add(m.group(1));
                }
            }
            if (!missing.isEmpty()) {
                missingByTemplate.put(String.valueOf(resource.getFilename()), missing);
            }
        }

        assertTrue(missingByTemplate.isEmpty(),
                "テンプレートが参照しているメッセージキーが messages.properties にありません"
                        + "（画面にキー名がそのまま表示されます）: " + missingByTemplate);
    }

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
