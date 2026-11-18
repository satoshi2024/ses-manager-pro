package com.ses.privacy.production.retention.trust;

import com.ses.testsupport.JavaSourceText;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PrivacyTrustSource} の {@code policyAnchors()}・{@code holdReleaseAnchors()}・{@code revocations()} を、main の source で
 * {@link PrivacyTrustSource} の内部（{@code policyAnchorsOf}・{@code holdReleaseAnchorsOf}・{@code revocationsOf} の遅延評価の包み）
 * 以外から呼ばせない（RB-NF07-REG review P2-1 の契約）。
 *
 * <p>構築時に1回呼んで結果を verifier へ渡すと、anchor の削除・失効状態の変化が再起動まで反映されない（NF08 の送信判定も起動時の
 * snapshot に戻る）。説明照会・登録・解除・NF07-NF08-LINK の Spring 登録は全て包みを使う。呼出し（{@code x.policyAnchors(}）と
 * method 参照（{@code ::policyAnchors}）を検出し、宣言（{@code policyAnchors() {}）と comment は対象外にする（comment は literal を
 * 保って除く。{@link JavaSourceText#stripComments}）。
 *
 * <p>包みの結果を構築時に読んで固定する書き方（{@code policyAnchorsOf(s).anchors()}・{@code revocationsOf(s).statusOf(..)} を
 * 同じ式で呼ぶ）も検出する（RB-NF08-LINK review P3-3(a)）。<b>静的な走査で検出しないもの</b>: reflection（{@code getMethod(...)}）
 * による呼出し、包みを変数へ入れてから別の文で {@code anchors()} を呼ぶスナップショット。これらを防ぐため、包みの呼出し元は
 * {@link #WRAPPER_CALL_SITES} の一覧に固定し、一覧の各 file には「構築時に trust を読まず、判定のたびに読む」挙動 test を対応させる。
 * <b>呼出し元を足すときは、その挙動 test を書いてから一覧へ足す</b>（一覧が実際の呼出し元と食い違えば本 test が失敗する）。
 */
class PrivacyTrustSourceCallSiteTest {

    /** 呼出し（{@code .name(}）か method 参照（{@code ::name}）。field の参照（{@code this.revocations =}）は拾わない。 */
    private static final Pattern CALL = Pattern.compile(
            "(?:\\.\\s*(policyAnchors|holdReleaseAnchors|revocations)\\s*\\(|::\\s*(policyAnchors|holdReleaseAnchors|revocations)\\b)");
    /** 包みの結果を同じ式で読んで固定するスナップショット（引数の括弧は1段まで）。 */
    private static final Pattern SNAPSHOT = Pattern.compile(
            "(?:(?:policyAnchorsOf|holdReleaseAnchorsOf)\\s*\\((?:[^()]|\\([^()]*\\))*\\)\\s*\\.\\s*anchors\\s*\\("
                    + "|revocationsOf\\s*\\((?:[^()]|\\([^()]*\\))*\\)\\s*\\.\\s*statusOf\\s*\\()");
    /** 包みの呼出し（呼出し元の一覧の照合用）。 */
    private static final Pattern WRAPPER = Pattern.compile("\\b(?:policyAnchorsOf|holdReleaseAnchorsOf|revocationsOf)\\s*\\(");
    private static final String ALLOWED = "com/ses/privacy/production/retention/trust/PrivacyTrustSource.java";

    /** 包みの呼出し元（main）と、構築時に trust を読まず判定のたびに読むことを確かめる挙動 test（src/test/java の相対 path）。 */
    private static final Map<String, String> WRAPPER_CALL_SITES = Map.of(
            "com/ses/privacy/production/PrivacyProductionConfiguration.java",
            "com/ses/privacy/production/retention/policy/PrivacyPolicyRegistrationConfigurationTest.java",
            "com/ses/privacy/production/retention/policy/PrivacyPolicyRegistrationConfiguration.java",
            "com/ses/privacy/production/retention/policy/PrivacyPolicyRegistrationConfigurationTest.java",
            "com/ses/privacy/production/retention/hold/PrivacyHoldConfiguration.java",
            "com/ses/privacy/production/retention/trust/PrivacyTrustSourceTest.java",
            "com/ses/privacy/production/PrivacyPolicyEnforcementConfiguration.java",
            "com/ses/privacy/production/PrivacyPolicyEnforcementConfigurationTest.java");

    @Test
    void trust_sourceのanchorと失効状態はPrivacyTrustSourceの遅延評価の包みからだけ呼ぶ() throws Exception {
        Path root = Path.of("src/main/java");
        List<String> violations = new ArrayList<>();
        Set<String> wrapperCallers = new TreeSet<>();
        boolean allowedSiteFound = false;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                String source = JavaSourceText.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher matcher = CALL.matcher(source);
                while (matcher.find()) {
                    if (ALLOWED.equals(relative)) {
                        allowedSiteFound = true;
                    } else {
                        violations.add(relative + " " + (matcher.group(1) != null ? matcher.group(1) : matcher.group(2)));
                    }
                }
                Matcher snapshot = SNAPSHOT.matcher(source);
                while (snapshot.find()) {
                    violations.add(relative + " 構築時のスナップショット: " + snapshot.group());
                }
                if (!ALLOWED.equals(relative) && WRAPPER.matcher(source).find()) {
                    wrapperCallers.add(relative);
                }
            }
        }
        assertTrue(violations.isEmpty(), "PrivacyTrustSource の policyAnchorsOf / holdReleaseAnchorsOf / revocationsOf を"
                + "判定のたびに読む形で使ってください（構築時に取ると変化が反映されない）: " + violations);
        // 検査が空振りしていない（包みの中の呼出しを検出できている）
        assertTrue(allowedSiteFound, "PrivacyTrustSource 内の呼出しを検出できません");
        assertEquals(new TreeSet<>(WRAPPER_CALL_SITES.keySet()), wrapperCallers,
                "包みの呼出し元が一覧と違います。新しい呼出し元には挙動 test を書いて WRAPPER_CALL_SITES へ足してください");
        for (Map.Entry<String, String> entry : WRAPPER_CALL_SITES.entrySet()) {
            assertTrue(Files.exists(Path.of("src/test/java").resolve(entry.getValue())),
                    entry.getKey() + " の挙動 test が見つかりません: " + entry.getValue());
        }
    }

    @Test
    void 検出規則は呼出しとmethod参照と同じ式のスナップショットを拾い宣言と包みとcommentは拾わない() {
        for (String call : List.of("source.policyAnchors().anchors()", "trust.revocations()", "x . holdReleaseAnchors()",
                "PrivacyTrustSource::policyAnchors", "this.revocations()")) {
            assertTrue(CALL.matcher(call).find(), call);
        }
        for (String other : List.of("public ProductionPolicyTrustRegistry policyAnchors() {",
                "PrivacyTrustSource.policyAnchorsOf(source)", "PrivacyTrustSource.revocationsOf(source)",
                "PrivacyTrustSource.holdReleaseAnchorsOf(source)", "this.revocations = revocations;",
                "PrivacyTrustSource::revocationsOf")) {
            assertFalse(CALL.matcher(other).find(), other);
        }
        for (String snapshot : List.of("Map<String, TrustedAuthority> fixed = PrivacyTrustSource.policyAnchorsOf(src).anchors();",
                "PrivacyTrustSource.holdReleaseAnchorsOf(holder.get()) . anchors()",
                "RevocationStatus s = PrivacyTrustSource.revocationsOf(src).statusOf(\"ref\");")) {
            assertTrue(SNAPSHOT.matcher(snapshot).find(), snapshot);
        }
        assertFalse(SNAPSHOT.matcher("PrivacyTrustSource.policyAnchorsOf(privacyTrustSource),").find());
        assertFalse(SNAPSHOT.matcher("return () -> source.policyAnchors().anchors();").find());

        String javadoc = "/** {@code source.policyAnchors()} を呼ぶ */ int x; // trust.revocations()";
        assertFalse(CALL.matcher(JavaSourceText.stripComments(javadoc)).find());
        // 文字列の中の "/*" から後の実 code を comment として消さない（review P3-3(c)）
        String literal = "String glob = \"/*\"; Object r = trust.revocations(); String end = \"*/\";";
        assertTrue(CALL.matcher(JavaSourceText.stripComments(literal)).find());
        assertTrue(JavaSourceText.stripComments("char c = '\"'; x.policyAnchors();").contains("x.policyAnchors()"));
    }
}
