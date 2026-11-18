package com.ses.common.i18n;

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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ko・zh_CN の bundle に日本語の原文が残らないことの守衛（RB-POSTLAUNCH A-1・A-2・P3-C・E-2）。
 *
 * <p>{@link MessageBundleConsistencyTest} は key の集合と placeholder だけを比べ、値の言語を見ない。そのため
 * 日本語の値を写しただけの key（一括督促の「スキップ」「送信済」「失敗」など）が ko・zh_CN に残っても通っていた。
 *
 * <ul>
 *   <li>ko・zh_CN: 値に仮名（平仮名・片仮名・中黒「・」・半角片仮名）を含まない。中黒は韓国語では「·」、
 *       中国語では「、」等で書く。</li>
 *   <li>ko: 値に漢字を含まない。韓国語の画面文言は漢字（漢字語の表記）を使わないため、漢字が残るのは
 *       日本語の写しである（「請求書」「対象月」等が実際に残っていた）。</li>
 *   <li>zh_CN: 値に日本の新字体・繁体字の字形を含まない。漢字語は日本語と簡体字で字形が一致し得るため
 *       （「操作」「保存」等）値の一致では判定できない。簡体字には現れない字形（「請」「経」「対」「円」等）だけを禁じる。</li>
 * </ul>
 *
 * <p>例外として許す key は {@link #ALLOWED} に理由付きで置く（現時点では無い）。ブランド名・人名など、
 * 日本語の表記のまま出すことが正しい値に限る。
 */
class KoZhCnBundleJapaneseResidueTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    /** 平仮名・片仮名（中黒・長音符を含む）・片仮名拡張・半角の中黒と片仮名。 */
    private static final Pattern KANA = Pattern.compile("[\\u3040-\\u30ff\\u31f0-\\u31ff\\uff65-\\uff9f]");

    /** CJK 統合漢字（拡張A・互換漢字を含む）。 */
    private static final Pattern HAN = Pattern.compile("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]");

    /**
     * 簡体字の文章に現れない字形（日本の新字体、または簡体字で別の字形になる繁体字）。
     * 簡体字としても使う字形（「号」「係」「虚」「残」「滞」等）は入れない。
     */
    private static final String JAPANESE_OR_TRADITIONAL_GLYPHS =
            "請書経歴覧報間開終現約動記録種済払計費発員額確実績円備認値説時評価結敗後対働営業帳続読収納様関"
                    + "変転専択選総類際険貨簡単頭題顧図誤調査証責質購販売買覚観規視訳語課談論議護識譲豊農辺込達遅遠"
                    + "適鉄長門閉問陸隠雑難電響順須預領頼風飛験騒鳥黒絡細給紹継緒線締編縮織繰習職聴脳臓舗薬衆処仮枠"
                    + "応検権気満為県拠拡戦歳駅毎歩";

    /** lang → (key → 日本語の表記を許す理由)。 */
    private static final Map<String, Map<String, String>> ALLOWED = Map.of(
            "ko", Map.of(),
            "zh_CN", Map.of());

    @Test
    void koとzhCNの値は仮名を含まない() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String lang : List.of("ko", "zh_CN")) {
            Properties bundle = load(lang);
            for (String key : new TreeSet<>(bundle.stringPropertyNames())) {
                String value = bundle.getProperty(key);
                if (KANA.matcher(value).find() && !ALLOWED.get(lang).containsKey(key)) {
                    violations.add(lang + ": " + key + "=" + value);
                }
            }
        }
        assertThat(violations).as("ko・zh_CN に残った仮名（日本語の写し）").isEmpty();
    }

    @Test
    void koの値は漢字を含まない() throws IOException {
        Properties ko = load("ko");
        List<String> violations = new ArrayList<>();
        for (String key : new TreeSet<>(ko.stringPropertyNames())) {
            String value = ko.getProperty(key);
            if (HAN.matcher(value).find() && !ALLOWED.get("ko").containsKey(key)) {
                violations.add(key + "=" + value);
            }
        }
        assertThat(violations).as("ko に残った漢字（日本語の写し）").isEmpty();
    }

    @Test
    void zhCNの値は日本の新字体や繁体字の字形を含まない() throws IOException {
        Set<Integer> glyphs = glyphs();
        assertThat(glyphs).as("禁止字形は全て漢字").allMatch(cp -> HAN.matcher(Character.toString(cp)).matches());
        Properties zh = load("zh_CN");
        List<String> violations = new ArrayList<>();
        for (String key : new TreeSet<>(zh.stringPropertyNames())) {
            String value = zh.getProperty(key);
            String found = value.codePoints().filter(glyphs::contains)
                    .mapToObj(Character::toString).distinct().collect(Collectors.joining());
            if (!found.isEmpty() && !ALLOWED.get("zh_CN").containsKey(key)) {
                violations.add(key + " [" + found + "]=" + value);
            }
        }
        assertThat(violations).as("zh_CN に残った日本語の字形").isEmpty();
    }

    /** 許可した key が bundle から消えたり、日本語の表記でなくなったりしたら、許可を外す。 */
    @Test
    void 許可リストの理由は空でなくkeyは今も日本語の表記を持つ() throws IOException {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> lang : ALLOWED.entrySet()) {
            Properties bundle = load(lang.getKey());
            lang.getValue().forEach((key, reason) -> {
                String value = bundle.getProperty(key);
                boolean japanese = value != null && (KANA.matcher(value).find()
                        || ("ko".equals(lang.getKey()) && HAN.matcher(value).find())
                        || ("zh_CN".equals(lang.getKey()) && value.codePoints().anyMatch(glyphs()::contains)));
                if (reason.isBlank() || !japanese) {
                    stale.add(lang.getKey() + ": " + key);
                }
            });
        }
        assertThat(stale).isEmpty();
    }

    private static Set<Integer> glyphs() {
        return JAPANESE_OR_TRADITIONAL_GLYPHS.codePoints().boxed().collect(Collectors.toSet());
    }

    private static Properties load(String lang) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(RESOURCES.resolve("messages_" + lang + ".properties"),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
