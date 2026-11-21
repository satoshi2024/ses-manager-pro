package com.ses.service.servicedesk;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NF02（サービスデスク・顧客ポータル・顧客ヘルス・定例会）の業務例外が日本語の生文言を持たないことの守衛
 * （RB-POSTLAUNCH A-3）。
 *
 * <p>{@code BusinessException.of(code, "日本語")} は key として解決できないため、{@code SafeErrorPolicy} が
 * status 別の汎用文言に置き換え、利用者には理由が伝わらなかった（ログと {@code getMessage()} だけが日本語になる）。
 * そのため対象範囲では:
 * <ul>
 *   <li>{@code BusinessException.of(...)}・{@code new BusinessException(...)} の引数の文字列リテラルに日本語を置かない。</li>
 *   <li>{@code BusinessException.of} の引数中の message key（ドット区切り。三項演算子で選ぶ2つ目の key も含む）は
 *       4つの bundle に存在する。全体共通の内部コード（{@code LEGAL_ENTITY_CONTEXT_REQUIRED} 等）は key ではなく、
 *       status 別の汎用文言になる既存の扱いなので対象外。</li>
 *   <li>{@code new BusinessException(code, "error.x")} は messageKey を持たず key を解決しないため、key を渡さない
 *       （RB-POSTLAUNCH review P3-7）。定数・変数に入れた文言は検出しない（現在の範囲には無い）。</li>
 * </ul>
 * 引数の値を {@code {0}} で埋め込む文言は作らない。{@code SafeErrorPolicy.safeArgs} は数値・真偽値・enum 以外を
 * 「入力値」に置き換えるため、カテゴリ名・状態名などの文字列は表示されないからである。
 *
 * <p>例外として許す呼出しは {@link #ALLOWED} に「相対path:行の内容 → 理由」で置く（現時点では無い）。
 */
class Nf02BusinessExceptionMessageKeyGuardTest {

    private static final Path JAVA = Path.of("src/main/java/com/ses");
    private static final Path RESOURCES = Path.of("src/main/resources");

    /** NF02 の範囲（package は配下全部、個別 file はその file だけ）。 */
    private static final List<String> SCOPE = List.of(
            "service/servicedesk",
            "service/portal",
            "config/portal",
            "controller/api/portal",
            "controller/api/portaladmin",
            "controller/api/ServiceRequestApiController.java",
            "controller/api/CustomerHealthApiController.java",
            "controller/api/CustomerQbrApiController.java",
            "service/scheduler/ServiceSlaScheduler.java",
            "service/scheduler/CustomerHealthScheduler.java");

    /** 呼出し箇所（相対path:行の内容）→ 日本語の生文言を許す理由。 */
    private static final Map<String, String> ALLOWED = Map.of();

    private static final Pattern CALL = Pattern.compile("BusinessException\\s*\\.\\s*of\\s*\\(|new\\s+BusinessException\\s*\\(");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern JAPANESE = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff\\uff01-\\uff5e]");
    /** message key（ドット区切り）。LEGAL_ENTITY_CONTEXT_REQUIRED のような全体共通の内部コードは key ではなく、status 別の汎用文言になる。 */
    private static final Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]+)+");

    @Test
    void NF02の業務例外は日本語の生文言を持たずkeyは4つのbundleにある() throws IOException {
        List<Properties> bundles = List.of(load("messages.properties"), load("messages_en.properties"),
                load("messages_ko.properties"), load("messages_zh_CN.properties"));
        List<String> violations = new ArrayList<>();
        int calls = 0;
        for (Path file : files()) {
            String rel = JAVA.relativize(file).toString().replace('\\', '/');
            String source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher call = CALL.matcher(source);
            while (call.find()) {
                calls++;
                String args = arguments(source, call.end());
                String site = rel + ":" + lineOf(source, call.start()).strip();
                if (ALLOWED.containsKey(site)) {
                    continue;
                }
                Matcher literal = STRING.matcher(args);
                boolean factory = call.group().startsWith("BusinessException");
                while (literal.find()) {
                    String text = literal.group(1);
                    if (JAPANESE.matcher(text).find()) {
                        violations.add(site + " : 日本語の生文言 \"" + text + "\"");
                    } else if (KEY.matcher(text).matches() && !factory) {
                        // new BusinessException(code, "error.x") は messageKey を持たず key を解決しない（汎用文言になる）
                        violations.add(site + " : new BusinessException に key " + text
                                + " を渡している（BusinessException.of を使う）");
                    } else if (KEY.matcher(text).matches()) {
                        // 三項演算子等で key を選ぶ場合も、引数中の全ての key が bundle にあること（RB-POSTLAUNCH review P3-7）
                        for (Properties bundle : bundles) {
                            if (!bundle.containsKey(text)) {
                                violations.add(site + " : key " + text + " が bundle に無い");
                                break;
                            }
                        }
                    }
                }
            }
        }
        assertThat(calls).as("対象範囲の業務例外の呼出し数（範囲が空回りしていないこと）").isGreaterThanOrEqualTo(150);
        assertThat(violations).isEmpty();
    }

    /** 許可リストの理由は空でない。 */
    @Test
    void 許可リストの理由は空でない() {
        assertThat(ALLOWED.values()).allMatch(reason -> !reason.isBlank());
    }

    private static List<Path> files() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String entry : SCOPE) {
            Path path = JAVA.resolve(entry);
            assertThat(path).as("NF02 の範囲 " + entry).exists();
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(files::add);
                }
            } else {
                files.add(path);
            }
        }
        return files;
    }

    /** 開き括弧の直後から対応する閉じ括弧までを返す（文字列リテラル中の括弧は数えない）。 */
    private static String arguments(String source, int from) {
        int depth = 1;
        boolean inString = false;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return source.substring(from, i);
            }
        }
        return source.substring(from);
    }

    private static String lineOf(String source, int index) {
        int start = source.lastIndexOf('\n', index) + 1;
        int end = source.indexOf('\n', index);
        return source.substring(start, end < 0 ? source.length() : end);
    }

    /** ブロックコメントと行コメントを除く（文字列リテラル中の // は対象外の前提で、"://" を含む行は残す）。 */
    private static String stripComments(String source) {
        String withoutBlocks = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(source)
                .replaceAll(match -> "\n".repeat((int) match.group().chars().filter(ch -> ch == '\n').count()));
        StringBuilder out = new StringBuilder();
        for (String line : withoutBlocks.split("\n", -1)) {
            int comment = line.indexOf("//");
            boolean url = comment > 0 && line.charAt(comment - 1) == ':';
            out.append(comment >= 0 && !url ? line.substring(0, comment) : line).append('\n');
        }
        return out.toString();
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(RESOURCES.resolve(name), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
