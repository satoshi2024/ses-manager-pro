package com.ses.privacy.production.retention.trust;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PrivacyTrustSource} の {@code policyAnchors()}・{@code holdReleaseAnchors()}・{@code revocations()} を、main の source で
 * {@link PrivacyTrustSource} の内部（{@code policyAnchorsOf}・{@code holdReleaseAnchorsOf}・{@code revocationsOf} の惰性の包み）
 * 以外から呼ばせない（RB-NF07-REG review P2-1 の契約）。
 *
 * <p>構築時に1回呼んで結果を verifier へ渡すと、anchor の削除・失効状態の変化が再起動まで反映されない（NF08 の送信判定も起動時の
 * snapshot に戻る）。説明照会・登録・解除・NF07-NF08-LINK の Spring 登録は全て包みを使う。呼出し（{@code x.policyAnchors(}）と
 * method 参照（{@code ::policyAnchors}）を検出し、宣言（{@code policyAnchors() {}）と Javadoc は対象外にする。
 */
class PrivacyTrustSourceCallSiteTest {

    /** 呼出し（{@code .name(}）か method 参照（{@code ::name}）。field の参照（{@code this.revocations =}）は拾わない。 */
    private static final Pattern CALL = Pattern.compile(
            "(?:\\.\\s*(policyAnchors|holdReleaseAnchors|revocations)\\s*\\(|::\\s*(policyAnchors|holdReleaseAnchors|revocations)\\b)");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)^\\s*//.*$");
    private static final String ALLOWED = "com/ses/privacy/production/retention/trust/PrivacyTrustSource.java";

    @Test
    void trust_sourceのanchorと失効状態はPrivacyTrustSourceの惰性の包みからだけ呼ぶ() throws Exception {
        Path root = Path.of("src/main/java");
        List<String> violations = new ArrayList<>();
        boolean allowedSiteFound = false;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                String source = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(
                        Files.readString(file, StandardCharsets.UTF_8)).replaceAll(" ")).replaceAll(" ");
                Matcher matcher = CALL.matcher(source);
                while (matcher.find()) {
                    if (ALLOWED.equals(relative)) {
                        allowedSiteFound = true;
                    } else {
                        violations.add(relative + " " + (matcher.group(1) != null ? matcher.group(1) : matcher.group(2)));
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "PrivacyTrustSource の policyAnchorsOf / holdReleaseAnchorsOf / revocationsOf を"
                + "使ってください（構築時に取ると変化が反映されない）: " + violations);
        // 検査が空振りしていない（包みの中の呼出しを検出できている）
        assertTrue(allowedSiteFound, "PrivacyTrustSource 内の呼出しを検出できません");
    }

    @Test
    void 検出規則は呼出しとmethod参照を拾い宣言と包みとJavadocは拾わない() {
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
        String javadoc = "/** {@code source.policyAnchors()} を呼ぶ */ int x;";
        assertFalse(CALL.matcher(BLOCK_COMMENT.matcher(javadoc).replaceAll(" ")).find());
    }
}
