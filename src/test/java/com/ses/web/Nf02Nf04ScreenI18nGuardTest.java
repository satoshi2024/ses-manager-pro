package com.ses.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NF-02〜04 の画面に日本語の固定文言が戻らないことの守衛（RB-I18N P3-N2-8・P3-N3-5・P3-N4-6）。
 *
 * <ul>
 *   <li>template: 日本語は {@code th:text}/{@code th:placeholder} 等が付いた要素の natural template の既定値としてだけ置ける。</li>
 *   <li>JS: 日本語はコメントだけに置ける。画面文言は message key で引き、その key は4つの bundle にある。</li>
 *   <li>pwa-queue.js・offline.html: 静的ページでも表示できるよう日本語の既定文言を持つが、その文字列は ja の bundle と同じでなければならない。</li>
 *   <li>en・ko・zh_CN の値は日本語をそのまま写していない（{@code MessageBundleConsistencyTest} は値の言語を見ない）。</li>
 * </ul>
 */
class Nf02Nf04ScreenI18nGuardTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    private static final List<String> TEMPLATES = List.of(
            "templates/service-desk/list.html",
            "templates/service-desk/detail.html",
            "templates/customer-success/health.html",
            "templates/certification-learning-skill-gap/list.html",
            "templates/my/certification-learning-skill-gap.html");

    private static final List<String> SCRIPTS = List.of(
            "static/js/modules/service-desk.js",
            "static/js/modules/customer-health.js",
            "static/js/modules/certification-learning-skill-gap.js",
            "static/js/modules/my-certification-learning-skill-gap.js",
            "static/portal/js/portal-service-desk.js");

    /** 顧客ポータル画面のうち、NF-02の子スイッチで出し分ける部分（問い合わせタブ・モーダル・文言・script）。 */
    private static final String PORTAL_CUSTOMER_TEMPLATE = "templates/portal/customer/index.html";
    private static final String NF02_PORTAL_SWITCH = "contains('nf02-customer-portal')";
    private static final Pattern PORTAL_MESSAGE_ENTRY = Pattern.compile("'([A-Za-z0-9_.]+)': \\[\\[#\\{([A-Za-z0-9_.]+)}]]");

    private static final Pattern JAPANESE = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff\\uff01-\\uff5e]");
    private static final Pattern KANA = Pattern.compile("[\\u3040-\\u30ff]");
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern TAG_OR_TEXT = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9-]*)([^>]*)>|([^<]+)");
    private static final Pattern ATTRIBUTE = Pattern.compile("([a-zA-Z:-]+)=\"([^\"]*)\"");
    private static final Set<String> VOID_ELEMENTS = Set.of("input", "br", "hr", "img", "meta", "link", "area",
            "base", "col", "embed", "source", "track", "wbr");
    /** 値として許すDBコード・role名を持つ属性（表示文言ではない）。 */
    private static final Set<String> NON_TEXT_ATTRIBUTES = Set.of("value", "sec:authorize", "class", "id", "style",
            "onclick", "onsubmit", "onchange", "type", "href", "src", "accept");
    /** 画面で使う message key の候補（JSの文字列リテラル）。 */
    private static final Pattern KEY_LITERAL = Pattern.compile(
            "'((?:servicedesk|customerSuccess|certGap|myCertGap|pwa|portal\\.serviceDesk|common|error|enum)\\.[A-Za-z0-9_.]*[A-Za-z0-9_])'");
    private static final Pattern UI_TEXT_CALL = Pattern.compile(
            "\\b(?:uiText|uiError|T)\\(\\s*'([A-Za-z0-9_.]+)'\\s*,\\s*'([^']*)'");
    private static final Pattern DATA_PWA_TEXT = Pattern.compile("data-pwa-text=\"([A-Za-z0-9_.]+)\">([^<]*)<");

    @Test
    void 対象templateの日本語はmessage式を持つ要素の既定値だけにある() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String template : TEMPLATES) {
            violations.addAll(templateViolations(template, read(template)));
        }
        assertThat(violations).as("th:text等の無い日本語の固定文言").isEmpty();
    }

    /**
     * P3-N2-9・P3-N2-8: 顧客ポータルの問い合わせ部分は日本語の固定文言を持たず、詳細・返信は専用page routeの無いモーダルで開く。
     * portal画面は /js/i18n.js を読まないため、portal-service-desk.js が引くkeyは全て画面がserver側で解決して渡す。
     */
    @Test
    void 顧客ポータルの問い合わせ部分は固定文言を持たずscriptのkeyを全て画面から渡す() throws IOException {
        String html = read(PORTAL_CUSTOMER_TEMPLATE);
        List<String> blocks = nf02PortalBlocks(html);
        assertThat(blocks).as("子スイッチで出し分ける要素").hasSizeGreaterThanOrEqualTo(6);
        List<String> violations = new ArrayList<>();
        for (String block : blocks) {
            violations.addAll(templateViolations(PORTAL_CUSTOMER_TEMPLATE, block));
        }
        assertThat(violations).isEmpty();

        Map<String, String> passed = new LinkedHashMap<>();
        Matcher entry = PORTAL_MESSAGE_ENTRY.matcher(html);
        while (entry.find()) {
            assertThat(entry.group(2)).as("渡すkeyと解決するkeyは同じ").isEqualTo(entry.group(1));
            passed.put(entry.group(1), entry.group(2));
        }
        Set<String> used = usedKeys(stripJsComments(read("static/portal/js/portal-service-desk.js")));
        assertThat(used).isNotEmpty();
        assertThat(passed.keySet()).as("portal-service-desk.js が引くkeyを画面が全て渡す").containsAll(used);
        // 専用のpage routeが無い詳細画面へのリンクを作らない（以前は「詳細・返信」が404になっていた）。
        // APIの /api/portal/customer/service-desk/requests/... は使う。画面のURL（/apiの付かないもの）だけを禁じる。
        assertThat(read("static/portal/js/portal-service-desk.js"))
                .doesNotContainPattern("(?<!/api)/portal/customer/service-desk/requests/")
                .contains("/api/portal/customer/service-desk/requests/");
        assertThat(Files.exists(RESOURCES.resolve("templates/portal/customer/service-desk"))).isFalse();
    }

    @Test
    void 対象JSの日本語はコメントだけにありmessage_keyは4つのbundleにある() throws IOException {
        Map<String, Properties> bundles = bundles();
        List<String> violations = new ArrayList<>();
        for (String script : SCRIPTS) {
            String code = stripJsComments(read(script));
            Matcher japanese = JAPANESE.matcher(code);
            if (japanese.find()) {
                violations.add(script + ": コメント外の日本語 '" + around(code, japanese.start()) + "'");
            }
            violations.addAll(missingKeys(script, code, bundles));
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void pwaの日本語既定文言はjaのbundleと同じでありkeyは4つのbundleにある() throws IOException {
        Map<String, Properties> bundles = bundles();
        Properties ja = bundles.get("ja");
        List<String> violations = new ArrayList<>();
        for (String file : List.of("static/js/pwa-queue.js", "static/offline.html")) {
            String source = read(file);
            String rest = stripJsComments(file.endsWith(".html") ? HTML_COMMENT.matcher(source).replaceAll("") : source);
            Map<String, String> fallbacks = new LinkedHashMap<>();
            for (Pattern pattern : List.of(UI_TEXT_CALL, DATA_PWA_TEXT)) {
                Matcher matcher = pattern.matcher(rest);
                while (matcher.find()) {
                    fallbacks.put(matcher.group(1) + "@" + matcher.start(), matcher.group(2));
                }
                rest = pattern.matcher(rest).replaceAll("");
            }
            assertThat(fallbacks).as(file + " の既定文言").isNotEmpty();
            fallbacks.forEach((keyAndOffset, fallback) -> {
                String key = keyAndOffset.substring(0, keyAndOffset.indexOf('@'));
                if (!fallback.equals(ja.getProperty(key))) {
                    violations.add(file + ": " + key + " の既定文言がjaのbundleと違う: '" + fallback + "' / '"
                            + ja.getProperty(key) + "'");
                }
            });
            violations.addAll(missingKeys(file, source, bundles));
            // 静的ページのtitleはJSが pwa.offline.title で置き換える（既定値はjaの値 + " | SES Manager Pro"）。
            rest = rest.replace("<title>" + ja.getProperty("pwa.offline.title") + " | SES Manager Pro</title>", "");
            Matcher japanese = JAPANESE.matcher(rest);
            if (japanese.find()) {
                violations.add(file + ": 既定文言以外の日本語 '" + around(rest, japanese.start()) + "'");
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void 対象画面のkeyのen_ko_zhCNの値は日本語を写していない() throws IOException {
        Map<String, Properties> bundles = bundles();
        Set<String> keys = new TreeSet<>();
        for (String file : concat(TEMPLATES, SCRIPTS, List.of("static/js/pwa-queue.js", "static/offline.html"))) {
            keys.addAll(usedKeys(read(file)));
        }
        for (String block : nf02PortalBlocks(read(PORTAL_CUSTOMER_TEMPLATE))) {
            keys.addAll(usedKeys(block));
        }
        bundles.get("ja").stringPropertyNames().stream()
                .filter(key -> key.startsWith("enum.engineerStatus.") || key.startsWith("error.http.")
                        || key.startsWith("error.request."))
                .forEach(keys::add);
        Properties ja = bundles.get("ja");
        List<String> violations = new ArrayList<>();
        for (String key : keys) {
            String base = ja.getProperty(key);
            for (String lang : List.of("en", "ko", "zh_CN")) {
                String value = bundles.get(lang).getProperty(key);
                if (value == null) {
                    continue; // key欠落は他のtestで検出する
                }
                if (KANA.matcher(value).find()) {
                    violations.add(lang + ": " + key + " に仮名が残る: " + value);
                }
                // en・koは日本語の値と同じなら写し。zh_CNは漢字語が日本語と一致し得るため、既知の欠陥（要員状態）だけ比べる。
                boolean compare = !"zh_CN".equals(lang) || key.startsWith("enum.engineerStatus.");
                if (compare && JAPANESE.matcher(base == null ? "" : base).find() && value.equals(base)) {
                    violations.add(lang + ": " + key + " が日本語の値と同じ: " + value);
                }
            }
        }
        assertThat(keys).isNotEmpty();
        assertThat(violations).isEmpty();
    }

    /** 開始tagのstackを持ち、日本語の本文・表示用属性に対応する th: 属性があるかを見る。script・styleの中は見ない。 */
    private static List<String> templateViolations(String name, String html) {
        List<String> violations = new ArrayList<>();
        String content = HTML_COMMENT.matcher(html).replaceAll("");
        Deque<String> openTags = new ArrayDeque<>();
        Deque<Boolean> hasMessage = new ArrayDeque<>();
        String rawElement = null;
        Matcher matcher = TAG_OR_TEXT.matcher(content);
        while (matcher.find()) {
            if (rawElement != null) {
                if (matcher.group(2) != null && "/".equals(matcher.group(1)) && rawElement.equalsIgnoreCase(matcher.group(2))) {
                    rawElement = null;
                }
                continue;
            }
            if (matcher.group(4) != null) {
                String text = matcher.group(4);
                if (JAPANESE.matcher(text).find() && (hasMessage.isEmpty() || !hasMessage.peek())) {
                    violations.add(name + ": th:text の無い本文 '" + text.strip() + "' in <" + openTags.peek() + ">");
                }
                continue;
            }
            String tag = matcher.group(2).toLowerCase(Locale.ROOT);
            String attributes = matcher.group(3);
            if ("/".equals(matcher.group(1))) {
                while (!openTags.isEmpty()) {
                    hasMessage.pop();
                    if (openTags.pop().equals(tag)) {
                        break;
                    }
                }
                continue;
            }
            Map<String, String> attrs = new LinkedHashMap<>();
            Matcher attribute = ATTRIBUTE.matcher(attributes);
            while (attribute.find()) {
                attrs.put(attribute.group(1), attribute.group(2));
            }
            attrs.forEach((attr, value) -> {
                if (attr.startsWith("th:") || NON_TEXT_ATTRIBUTES.contains(attr) || !JAPANESE.matcher(value).find()) {
                    return;
                }
                if (!attrs.containsKey("th:" + attr)) {
                    violations.add(name + ": th:" + attr + " の無い属性 " + attr + "=\"" + value + "\"");
                }
            });
            if ("script".equals(tag) || "style".equals(tag)) {
                rawElement = tag;
                continue;
            }
            boolean selfClosing = attributes.endsWith("/") || VOID_ELEMENTS.contains(tag);
            if (!selfClosing) {
                openTags.push(tag);
                hasMessage.push(attrs.containsKey("th:text") || attrs.containsKey("th:utext"));
            }
        }
        return violations;
    }

    /** 子スイッチ（nf02-customer-portal）の th:unless を持つ要素を、対応する閉じtagまで切り出す。 */
    private static List<String> nf02PortalBlocks(String html) {
        List<String> blocks = new ArrayList<>();
        int from = 0;
        while (true) {
            int marker = html.indexOf(NF02_PORTAL_SWITCH, from);
            if (marker < 0) {
                return blocks;
            }
            int start = html.lastIndexOf('<', marker);
            Matcher name = Pattern.compile("<([a-zA-Z][a-zA-Z0-9-]*)").matcher(html);
            if (start < 0 || !name.find(start) || name.start() != start) {
                from = marker + 1;
                continue;
            }
            String tag = name.group(1);
            Matcher tags = Pattern.compile("<(/?)" + tag + "\\b[^>]*>").matcher(html);
            int depth = 0;
            int end = html.length();
            tags.region(start, html.length());
            while (tags.find()) {
                depth += "/".equals(tags.group(1)) ? -1 : 1;
                if (depth == 0) {
                    end = tags.end();
                    break;
                }
            }
            blocks.add(html.substring(start, end));
            from = end;
        }
    }

    private static List<String> missingKeys(String file, String code, Map<String, Properties> bundles) {
        List<String> missing = new ArrayList<>();
        for (String key : usedKeys(code)) {
            for (Map.Entry<String, Properties> bundle : bundles.entrySet()) {
                if (!bundle.getValue().containsKey(key)) {
                    missing.add(file + ": " + key + " が " + bundle.getKey() + " に無い");
                }
            }
        }
        return missing;
    }

    private static Set<String> usedKeys(String code) {
        Set<String> keys = new TreeSet<>();
        Matcher literal = KEY_LITERAL.matcher(code);
        while (literal.find()) {
            keys.add(literal.group(1));
        }
        Matcher pwa = DATA_PWA_TEXT.matcher(code);
        while (pwa.find()) {
            keys.add(pwa.group(1));
        }
        Matcher message = Pattern.compile("#\\{([A-Za-z0-9_.]+)").matcher(code);
        while (message.find()) {
            keys.add(message.group(1));
        }
        return keys;
    }

    /** 行コメント・ブロックコメントを除く（文字列中の // は対象JSに無い前提。URLは /api/... の相対パスだけ）。 */
    private static String stripJsComments(String code) {
        String withoutBlocks = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(code).replaceAll("");
        StringBuilder out = new StringBuilder();
        for (String line : withoutBlocks.split("\n", -1)) {
            int comment = line.indexOf("//");
            out.append(comment >= 0 && !line.substring(0, comment).endsWith(":") ? line.substring(0, comment) : line)
                    .append('\n');
        }
        return out.toString();
    }

    private static Map<String, Properties> bundles() throws IOException {
        Map<String, Properties> bundles = new LinkedHashMap<>();
        bundles.put("ja", load("messages.properties"));
        bundles.put("en", load("messages_en.properties"));
        bundles.put("ko", load("messages_ko.properties"));
        bundles.put("zh_CN", load("messages_zh_CN.properties"));
        return bundles;
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(RESOURCES.resolve(name), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static String read(String relative) throws IOException {
        return Files.readString(RESOURCES.resolve(relative), StandardCharsets.UTF_8);
    }

    private static String around(String text, int index) {
        int start = Math.max(0, index - 30);
        int end = Math.min(text.length(), index + 30);
        return text.substring(start, end).replace('\n', ' ');
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> all = new ArrayList<>();
        for (List<String> list : lists) {
            all.addAll(list);
        }
        return all;
    }
}
