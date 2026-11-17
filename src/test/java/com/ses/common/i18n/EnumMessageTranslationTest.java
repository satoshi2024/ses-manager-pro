package com.ses.common.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code enum.*}（DB値の表示名。{@code SES.i18n.e} と template が使う）の en・ko・zh_CN の値が、日本語の値をそのまま写して
 * いないこと（RB-I18N）。ko・zh_CN の要員状態・契約状態・ロール・性別・提案状態等が日本語のまま残っており、
 * {@code MessageBundleConsistencyTest} は値の言語を見ないため検出できなかった。
 *
 * <p>略語・記号など言語に依らず同じ表記が正しいものだけを {@link #SAME_IN_ALL_LANGUAGES} に明示して除外する。</p>
 */
class EnumMessageTranslationTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    /** 全言語で同じ表記が正しい値（略語・ランク記号）。追加するときは理由が言えるものだけにする。 */
    private static final Set<String> SAME_IN_ALL_LANGUAGES = Set.of(
            "enum.userRole.hr",
            "enum.customerTrustLevel.S", "enum.customerTrustLevel.A", "enum.customerTrustLevel.B",
            "enum.customerTrustLevel.C",
            "enum.employmentType.bp",
            "enum.skillCategory.fw", "enum.skillCategory.db", "enum.skillCategory.os");

    @Test
    void enumの表示名はen_ko_zhCNで日本語の値を写さない() throws IOException {
        Properties ja = load("messages.properties");
        List<String> copied = new ArrayList<>();
        int checked = 0;
        for (String lang : List.of("en", "ko", "zh_CN")) {
            Properties bundle = load("messages_" + lang + ".properties");
            for (String key : ja.stringPropertyNames()) {
                if (!key.startsWith("enum.") || SAME_IN_ALL_LANGUAGES.contains(key)) {
                    continue;
                }
                checked++;
                String value = bundle.getProperty(key);
                if (value != null && value.equals(ja.getProperty(key))) {
                    copied.add(lang + ": " + key + "=" + value);
                }
            }
        }
        assertThat(checked).isGreaterThan(100);
        assertThat(copied).as("日本語の値を写したenumの表示名").isEmpty();
    }

    @Test
    void 除外は略語だけで全言語で実際に同じ表記である() throws IOException {
        Properties ja = load("messages.properties");
        for (String lang : List.of("en", "ko", "zh_CN")) {
            Properties bundle = load("messages_" + lang + ".properties");
            for (String key : SAME_IN_ALL_LANGUAGES) {
                assertThat(ja.getProperty(key)).as(key).matches("[A-Z]{1,3}");
                assertThat(bundle.getProperty(key)).as(lang + ": " + key).isEqualTo(ja.getProperty(key));
            }
        }
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(RESOURCES.resolve(name), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
