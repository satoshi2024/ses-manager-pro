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

    /**
     * 決定の参照の getter（{@code getD01()}〜{@code getD09()}。{@code PrivacyApprovalProperties} の外に同名が無い）は名前で探す。宣言を
     * 追えない読み方（{@code var}・provider からの連鎖）も拾う（review rb-fe-c P3-5）。
     */
    private static final Pattern DECISION_GETTER = Pattern.compile("\\.\\s*(getD0[1-9])\\s*\\(");

    /**
     * 承認の値の property 名の直接の読取り（{@code @Value("${privacy.approval…}")}・{@code Environment#getProperty("privacy.approval…")}・
     * DSAR の期限規則・保存期間。review rb-fe-c P3-5）。{@code @ConfigurationProperties(prefix = …)} は数えない。
     */
    static final Pattern PROPERTY_READ = Pattern.compile("(?:@Value\\s*\\(\\s*\"\\$\\{\\s*|getProperty\\s*\\(\\s*\")"
            + "(privacy\\.(?:approval\\.|dsar\\.due-days|dsar\\.package-max-age)[\\w.-]*)");

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
                Set<String> variables = declaredNames(source, guarded.type());
                Set<String> holders = holderNames(source, guarded.type());
                variables.addAll(varNames(source, guarded.type(), holders));
                for (String holder : holders) {
                    // ObjectProvider<型>・Optional<型> から取り出して getter を呼ぶ連鎖（p.getIfAvailable().getD06()）
                    Matcher chained = Pattern.compile("\\b" + Pattern.quote(holder) + "\\s*\\.\\s*\\w+\\s*\\([^()]*\\)\\s*\\.\\s*("
                            + guarded.getters() + ")\\s*\\(").matcher(source);
                    while (chained.find()) {
                        violations.add(relative + ": " + holder + "...." + chained.group(1) + "(");
                    }
                }
                for (String variable : variables) {
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
            if (!GUARDED.get(0).allowed().contains(relative)) {
                Matcher decision = DECISION_GETTER.matcher(source);
                while (decision.find()) {
                    violations.add(relative + ": ." + decision.group(1) + "(");
                }
            }
            Matcher property = PROPERTY_READ.matcher(source);
            while (property.find()) {
                violations.add(relative + ": " + property.group(1));
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

    /** review rb-fe-c P3-5: provider からの連鎖・{@code var}・property 名の直接の読取りも検出する。 */
    @Test
    void 走査はproviderの連鎖とvarとproperty名の読取りも検出する() {
        String chained = "class A { A(ObjectProvider<PrivacyApprovalProperties> approvals, Optional<com.x.PrivacyApprovalProperties> o) "
                + "{ approvals.getIfAvailable().getApprovalDigest(); } }";
        assertThat(holderNames(chained, "PrivacyApprovalProperties")).containsExactly("approvals", "o");
        assertThat(Pattern.compile("\\bapprovals\\s*\\.\\s*\\w+\\s*\\([^()]*\\)\\s*\\.\\s*(get\\w+|reference)\\s*\\(")
                .matcher(chained).find()).isTrue();

        String declaredVar = "class A { void f(ObjectProvider<PrivacyApprovalProperties> p) { var a = p.getObject(); "
                + "var b = new PrivacyApprovalProperties(); a.getTenantId(); } }";
        assertThat(varNames(declaredVar, "PrivacyApprovalProperties",
                holderNames(declaredVar, "PrivacyApprovalProperties"))).containsExactly("a", "b");
        assertThat(DECISION_GETTER.matcher("var d = source.approval().getD05().getDecisionId();").find()).isTrue();

        assertThat(PROPERTY_READ.matcher("@Value(\"${privacy.approval.d05.decision-id:}\") String id;").find()).isTrue();
        assertThat(PROPERTY_READ.matcher("environment.getProperty(\"privacy.approval.approval-digest\")").find()).isTrue();
        assertThat(PROPERTY_READ.matcher("env.getProperty(\"privacy.dsar.due-days\", Integer.class)").find()).isTrue();
        assertThat(PROPERTY_READ.matcher("@ConfigurationProperties(prefix = \"privacy.approval\")").find()).isFalse();
    }

    /** {@code ObjectProvider<型> 名前}・{@code Optional<型> 名前} など、型を1つだけ包む generic で宣言した名前。 */
    private static Set<String> holderNames(String source, String type) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("<\\s*(?:\\w+\\.)*" + Pattern.quote(type) + "\\s*>\\s+(\\w+)").matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** {@code var} で宣言した名前のうち、型の生成か包みの取り出しで初期化したもの。 */
    private static Set<String> varNames(String source, String type, Set<String> holders) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\\bvar\\s+(\\w+)\\s*=\\s*(?:new\\s+(?:\\w+\\.)*" + Pattern.quote(type)
                + "\\s*\\(|(\\w+)\\s*\\.\\s*\\w+\\s*\\()").matcher(source);
        while (matcher.find()) {
            if (matcher.group(2) == null || holders.contains(matcher.group(2))) {
                names.add(matcher.group(1));
            }
        }
        return names;
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
