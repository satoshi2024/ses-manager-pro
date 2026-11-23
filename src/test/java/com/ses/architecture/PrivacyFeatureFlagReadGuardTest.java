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
    /**
     * {@code PrivacyFeatureProperties.Retention}・{@code Dsar} の型で宣言した名前（{@code Retention r = props.getRetention();}）と、
     * {@code var} で {@code getRetention()}・{@code getDsar()} を受けた名前（review rb-fe-c P3-5）。型の単純名は他の class
     * （{@code AiConfig.Retention}）と重なるため、{@code PrivacyFeatureProperties} を参照する file だけを見る。
     */
    static final Pattern TOGGLE_DECLARATION = Pattern.compile(
            "(?:(?<![\\w<])(?:PrivacyFeatureProperties\\s*\\.\\s*)?(?:Retention|Dsar)|\\bvar)\\s+(\\w+)\\s*=\\s*[^;]*?"
                    + "get(?:Retention|Dsar)\\(\\)\\s*;|(?<![\\w<.])(?:PrivacyFeatureProperties\\s*\\.\\s*)?(?:Retention|Dsar)"
                    + "\\s+(\\w+)\\s*[,)]");
    /** 設定名の直接の読取り（{@code getProperty}・{@code @Value}・{@code @ConditionalOnProperty}。review rb-fe-c P3-5）。 */
    static final Pattern PROPERTY_READ = Pattern.compile("(?:getProperty\\s*\\(\\s*|@Value\\s*\\(\\s*\"\\$\\{\\s*"
            + "|@ConditionalOnProperty\\s*\\([^)]*)\"?(privacy\\.(?:retention|dsar)\\.enabled)");

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
                if (source.contains("PrivacyFeatureProperties")) {
                    for (String name : toggleNames(source)) {
                        Matcher read = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\.\\s*isEnabled\\s*\\(")
                                .matcher(source);
                        while (read.find()) {
                            violations.add(relative + ": " + name + ".isEnabled(");
                        }
                    }
                }
                Matcher property = PROPERTY_READ.matcher(source);
                while (property.find()) {
                    violations.add(relative + ": " + property.group(1));
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

    /** review rb-fe-c P3-5: 変数に受けてからの読取りと、設定名の直接の読取りも検出する。 */
    @Test
    void 走査は変数に受けた読取りと設定名の読取りも検出する() {
        assertThat(toggleNames("PrivacyFeatureProperties.Retention r = props.getRetention(); var d = props.getDsar(); "
                + "void f(PrivacyFeatureProperties.Dsar given) {}")).containsExactly("r", "d", "given");
        assertThat(toggleNames("private Retention retention = new Retention();")).as("field の生成は読取りではない").isEmpty();
        assertThat(PROPERTY_READ.matcher("environment.getProperty(\"privacy.retention.enabled\", Boolean.class)").find())
                .isTrue();
        assertThat(PROPERTY_READ.matcher("@Value(\"${privacy.dsar.enabled:false}\")").find()).isTrue();
        assertThat(PROPERTY_READ.matcher("@ConditionalOnProperty(name = \"privacy.retention.enabled\")").find()).isTrue();
        assertThat(PROPERTY_READ.matcher("return \"privacy.retention.enabled\";").find()).as("名前を返すだけは読取りではない")
                .isFalse();
    }

    private static List<String> toggleNames(String source) {
        List<String> names = new ArrayList<>();
        Matcher matcher = TOGGLE_DECLARATION.matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }
        return names;
    }
}
