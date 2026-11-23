package com.ses.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 承認の値の properties の getter を property の源だけが呼ぶ（feature-enablement-console design §8.5 の 1。review P3-15。FE-C01）。
 *
 * <p>承認の値（決定 ID・署名日・失効日・approval digest・承認 tenant・承認 capability と、DSAR の期限規則・保存期間）は、DB の源
 * では確認済みの署名から読む。読む側が properties の getter を直接呼ぶと、DB の源で空の値を読み続けても誰も気付かない。そこで
 * src/main を走査し、守る型ごとに、その型で宣言した変数・field・引数（{@code 型 名前}）の getter と method 参照を呼んでよい file を
 * 固定する。DSAR の期限規則・保存期間の getter は名前で探す（{@code PrivacyFeatureProperties.Dsar} の getter。他の型に同名が無い）。</p>
 *
 * <p>守る型の行は段階ごとに足す（NF06 の {@code ImportAdmissionApprovalProperties} は段階 B、NF05 の approval の
 * {@code decision-id}・{@code approval-digest} は段階 D。各段階の property の源だけを許す）。</p>
 */
class ApprovalValueReadGuardTest {

    private static final Path MAIN = Paths.get("src/main/java");

    /** 守る型（単純名）と、getter を呼んでよい file（src/main/java からの相対の path）。 */
    private static final List<GuardedType> GUARDED = List.of(
            new GuardedType("PrivacyApprovalProperties", "get\\w+|reference",
                    Set.of("com/ses/privacy/production/PrivacyApprovalProperties.java",
                            "com/ses/privacy/production/PropertyPrivacyApprovalSource.java")));

    /** DSAR の期限規則・保存期間の getter（D-06 の署名の構造化された値に移した。§18 J-15）。 */
    private static final Pattern DSAR_VALUE_GETTER = Pattern.compile("\\.\\s*(getDueDays|getPackageMaxAge)\\s*\\(");
    private static final Set<String> DSAR_VALUE_READERS = Set.of(
            "com/ses/privacy/production/PrivacyFeatureProperties.java",
            "com/ses/privacy/production/PropertyPrivacyApprovalSource.java");

    @Test
    void 承認の値のpropertiesのgetterを呼ぶのはpropertyの源だけである() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String relative = MAIN.relativize(file).toString().replace('\\', '/');
            String source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            for (GuardedType guarded : GUARDED) {
                if (guarded.allowed().contains(relative) || !source.contains(guarded.type())) {
                    continue;
                }
                for (String variable : declaredNames(source, guarded.type())) {
                    Matcher call = Pattern.compile("\\b" + Pattern.quote(variable) + "\\s*\\.\\s*(" + guarded.getters()
                            + ")\\s*\\(").matcher(source);
                    while (call.find()) {
                        violations.add(relative + ": " + variable + "." + call.group(1) + "(");
                    }
                }
                Matcher reference = Pattern.compile("\\b" + Pattern.quote(guarded.type()) + "\\s*::\\s*(" + guarded.getters()
                        + ")\\b").matcher(source);
                while (reference.find()) {
                    violations.add(relative + ": " + guarded.type() + "::" + reference.group(1));
                }
            }
            if (!DSAR_VALUE_READERS.contains(relative)) {
                Matcher getter = DSAR_VALUE_GETTER.matcher(source);
                while (getter.find()) {
                    violations.add(relative + ": ." + getter.group(1) + "(");
                }
            }
        }
        assertThat(violations).as("承認の値の properties の getter は property の源だけが呼ぶ（他は PrivacyApprovalSource を読む）")
                .isEmpty();
    }

    @Test
    void 走査はgetterの直接の呼出しを検出する() {
        String sample = "class A { void f(PrivacyApprovalProperties approval) { approval.getD06().getDecisionId(); "
                + "x.map(PrivacyApprovalProperties::getApprovalDigest); } }";
        assertThat(declaredNames(sample, "PrivacyApprovalProperties")).containsExactly("approval");
        assertThat(Pattern.compile("\\bapproval\\s*\\.\\s*(get\\w+|reference)\\s*\\(").matcher(sample).find()).isTrue();
        assertThat(Pattern.compile("\\bPrivacyApprovalProperties\\s*::\\s*(get\\w+|reference)\\b").matcher(sample).find())
                .isTrue();
        assertThat(DSAR_VALUE_GETTER.matcher("features.getDsar().getDueDays()").find()).isTrue();
        assertThat(stripComments("/** approval.getD06() */ int a; // approval.getD01()")).doesNotContain("getD0");
    }

    /** {@code 型 名前} で宣言した名前（field・変数・引数。generic の型引数の中は数えない）。 */
    private static Set<String> declaredNames(String source, String type) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?<![\\w.<])" + Pattern.quote(type) + "\\s+(\\w+)").matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(MAIN)) {
            List<Path> files = stream.filter(path -> path.toString().endsWith(".java")).sorted().toList();
            assertThat(files).as("src/main/java を読めること").isNotEmpty();
            return files;
        }
    }

    private record GuardedType(String type, String getters, Set<String> allowed) {
    }
}
