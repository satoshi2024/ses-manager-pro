package com.ses.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NF07 の flag（{@code privacy.retention.enabled}・{@code privacy.dsar.enabled}）を直接読むのは開放状態の property の源だけ
 * （feature-enablement-console design §8.5 の 2。FE-AC-009-03。FE-C02）。
 *
 * <p>prod の開閉は DB の開放状態で決まる。validator・monitor・trust・Journal・policy source が生の flag を読むと、画面で開閉しても
 * それらだけが property の値で動く（以前の5箇所）。開閉は {@code FeatureSwitches}（実効値）か {@code FeatureEnablementStateSource}
 * （開放状態の源。monitor のように {@code FeatureSwitches} の実行時条件そのものは源を直接読む）から読む。</p>
 */
class PrivacyFeatureFlagReadGuardTest {

    private static final Path MAIN = Paths.get("src/main/java");
    /** {@code getRetention().isEnabled()}・{@code getDsar().isEnabled()}（間に空白・改行があってもよい）。 */
    static final Pattern FLAG_READ = Pattern.compile("get(Retention|Dsar)\\(\\)\\s*\\.\\s*isEnabled\\(\\)");
    /** 読んでよい file（property の源。{@code FeatureSwitchProperties#toggle} の Toggle を読む）。 */
    private static final Set<String> ALLOWED = Set.of(
            "com/ses/featureenablement/state/PropertyFeatureEnablementStateSource.java");

    @Test
    void NF07のflagを直接読むのはpropertyの源だけである() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(MAIN)) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                if (ALLOWED.contains(relative)) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8)
                        .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
                Matcher matcher = FLAG_READ.matcher(source);
                while (matcher.find()) {
                    violations.add(relative + ": " + matcher.group());
                }
            }
        }
        assertThat(violations).as("NF07 の開閉は FeatureSwitches か開放状態の源から読む").isEmpty();
    }

    @Test
    void 走査は改行を挟んだ読取りも検出する() {
        assertThat(FLAG_READ.matcher("features.getRetention()\n        .isEnabled()").find()).isTrue();
        assertThat(FLAG_READ.matcher("features.getDsar().isEnabled()").find()).isTrue();
        assertThat(FLAG_READ.matcher("features.getDsar().getDueDays()").find()).isFalse();
    }
}
