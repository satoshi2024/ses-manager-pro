package com.ses.migration;

import com.ses.privacy.foundation.PrivacyCaseState;
import com.ses.privacy.production.dsar.PrivacyDsarVocabulary;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V201（NF07 F1 の状態・区分の列の CHECK。W3-14 review P3-8・RB-NF07-HOLD）の値の集合が、コードの enum と一致することを
 * Docker なしで固定する。enum に値を足して CHECK を広げ忘れると、本番（MySQL）でだけ書込みが 3819 で失敗する。逆に CHECK が
 * enum より広ければ、綴り違いの値を DB が受け付ける。H2 変種（{@code schema-privacy-status-check-h2.sql}）も同じ集合であることを
 * 確かめる（fast の test が本番と違う制約で動かないように）。実 MySQL での拒否は {@code FlywayNf07PrivacyF1MigrationTest} が見る。
 */
class PrivacyStatusCheckConstraintTest {

    private static final String MIGRATION = "db/migration/V201__nf07_privacy_status_check_constraints.sql";
    private static final String H2_VARIANT = "sql/schema-privacy-status-check-h2.sql";
    /** {@code CONSTRAINT [IF NOT EXISTS] 名前 CHECK (... 列 IN ('A', 'B'))}。 */
    private static final Pattern CHECK = Pattern.compile(
            "CONSTRAINT\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s+CHECK\\s*\\((?:[^()]|\\([^()]*\\))*?(\\w+)\\s+IN\\s*\\(([^()]*)\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    @Test
    void V201とH2変種のCHECKはコードのenumと同じ値の集合を持つ() throws IOException {
        Map<String, Constraint> expected = new LinkedHashMap<>();
        expected.put("chk_privacy_request_status", new Constraint("status", names(PrivacyCaseState.values())));
        expected.put("chk_privacy_request_channel",
                new Constraint("channel", names(PrivacyDsarVocabulary.Channel.values())));
        expected.put("chk_privacy_request_delivery_method",
                new Constraint("delivery_method", names(PrivacyDsarVocabulary.DeliveryMethod.values())));
        // hold の種類は D-05 の署名で決まるまで UNKNOWN だけ（application は UNKNOWN しか書かない）
        expected.put("chk_legal_hold_kind", new Constraint("hold_kind", List.of("UNKNOWN")));

        assertThat(constraints(MIGRATION)).as(MIGRATION).containsExactlyEntriesOf(expected);
        assertThat(constraints(H2_VARIANT)).as(H2_VARIANT).containsExactlyEntriesOf(expected);
    }

    @Test
    void 交付方法のCHECKはNULLを明示して受け付ける() throws IOException {
        for (String resource : List.of(MIGRATION, H2_VARIANT)) {
            assertThat(stripComments(read(resource))).as(resource)
                    .containsPattern("chk_privacy_request_delivery_method\\s+CHECK\\s*\\(\\s*delivery_method\\s+IS\\s+NULL\\s+OR");
        }
    }

    private static Map<String, Constraint> constraints(String resource) throws IOException {
        Map<String, Constraint> found = new LinkedHashMap<>();
        Matcher matcher = CHECK.matcher(stripComments(read(resource)));
        while (matcher.find()) {
            List<String> values = new ArrayList<>();
            Matcher quoted = QUOTED.matcher(matcher.group(3));
            while (quoted.find()) {
                values.add(quoted.group(1));
            }
            assertThat(found.put(matcher.group(1), new Constraint(matcher.group(2), values)))
                    .as("制約名の重複: %s", matcher.group(1)).isNull();
        }
        assertThat(found).as("%s から CHECK を読めない", resource).isNotEmpty();
        return found;
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = PrivacyStatusCheckConstraintTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("classpath に %s が無い", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String stripComments(String sql) {
        return Pattern.compile("--[^\\n]*").matcher(sql).replaceAll("");
    }

    private record Constraint(String column, List<String> values) {
    }
}
