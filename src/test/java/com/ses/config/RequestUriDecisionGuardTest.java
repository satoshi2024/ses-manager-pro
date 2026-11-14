package com.ses.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FWD-SANITIZE P2-2 / r3 D-P3-1: 認可・監査・rate limit・MFA強制等の判定に、servletの生のpath
 * （{@code getRequestURI()}・{@code getServletPath()}・{@code getRequestURL()}）を使わせないソース検査（Docker不要）。
 *
 * <p>{@code getRequestURI()} は未復号で、context path（framework strategyでは {@code X-Forwarded-Prefix}）を含む。
 * Spring SecurityのmatcherとMVCは復号後・context path除去後のpathで照合するため、これで判定すると
 * {@code /%61pi/...} や prefix付きの要求がcontrollerへ届くのに判定だけ外れる（本batch以前は転送ヘッダー無しの
 * {@code /%61pi/...} だけで営業がm_menu・action denyを迂回し、更新系の監査も残らなかった）。
 * {@code getServletPath()} はservlet mapping次第で空になり、{@code getRequestURL()} はhost・schemeを含むため、
 * どちらも判定の口径にしない。判定には {@code com.ses.common.util.RequestPaths} を使うこと。
 *
 * <p>走査範囲は {@code src/main/java} 全体（共通util・controller・privacy等を含む）。メソッド呼出し
 * （{@code .getRequestURI(}）とメソッド参照（{@code ::getRequestURI}）の両方を数える。
 * 例外は下の許可表だけで、file単位の出現回数まで固定する（許可済みfileへの判定用途の追加も検出する）。
 * 新しい用途で許可が必要になったら、判定に使わない理由を書いて追加すること。
 */
class RequestUriDecisionGuardTest {

    private static final Pattern ACCESSOR = Pattern.compile(
            "(?:\\.\\s*(?:getRequestURI|getServletPath|getRequestURL)\\s*\\()"
                    + "|(?:::\\s*(?:getRequestURI|getServletPath|getRequestURL)\\b)");

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /** 判定に使わないため許可する箇所（src/main/java からの相対path → 出現回数と理由）。 */
    private static final Map<String, Allowed> ALLOWED = Map.of(
            "com/ses/common/util/RequestPaths.java",
            new Allowed(2, "判定用のアプリ内pathを作る工具そのもの（context pathを差し引き、復号・正規化する）"),
            "com/ses/config/CorrelationIdFilter.java",
            new Allowed(1, "log用: 相関IDのlog contextへpath上のIDを載せるだけで、認可・監査対象の判定に使わない"),
            "com/ses/config/GlobalControllerAdvice.java",
            new Allowed(3, "表示用: 現在URI・nav選択・言語切替link。context pathを含むlinkが必要で、prefixは清浄化で除去済み"),
            "com/ses/config/InternalTenantContextFilter.java",
            new Allowed(1, "contextPath考慮済み: metrics scraperのallow-listをcontext path＋固定pathとの完全一致で判定し、"
                    + "符号化変体は一致せず拒否側（fail-closed）"),
            "com/ses/config/feature/FeatureSwitchFilter.java",
            new Allowed(1, "contextPath差引き済み: 生pathと復号後pathの両方で照合し、どちらかが一致すれば遮断する（遮断側にだけ働く）"),
            "com/ses/controller/api/PwaMutationApiController.java",
            new Allowed(1, "ledger用: client側のhashと結び付くoperation文字列。表記が変われば不一致として拒否する（fail-closed）"));

    /** directory単位で許可する範囲（理由付き）。 */
    private static final Map<String, String> ALLOWED_DIRECTORIES = Map.of(
            "com/ses/config/integrationhub/",
            "NF-05公開API（W3-06a laneで扱い本batchでは変更しない）。外部API判定が外れても、chainのsecurityMatcherと"
                    + "authenticated/denyAll（anonymous無効）で401になる（fail-closed）");

    private record Allowed(int occurrences, String reason) {
    }

    @Test
    void servletの生のpathを判定に使う箇所は許可表以外に存在しない() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                int count = countAccessors(Files.readString(file, StandardCharsets.UTF_8));
                if (count > 0) {
                    found.put(SOURCE_ROOT.relativize(file).toString().replace('\\', '/'), count);
                }
            }
        }

        Map<String, String> violations = new TreeMap<>();
        found.forEach((file, count) -> {
            if (ALLOWED_DIRECTORIES.keySet().stream().anyMatch(file::startsWith)) {
                return;
            }
            Allowed allowed = ALLOWED.get(file);
            if (allowed == null) {
                violations.put(file, count + "件（RequestPaths.pathWithinApplicationで判定すること）");
            } else if (allowed.occurrences() != count) {
                violations.put(file, count + "件（許可は" + allowed.occurrences() + "件: " + allowed.reason() + "）");
            }
        });
        assertThat(violations).as("getRequestURI()/getServletPath()/getRequestURL()を判定に使っている可能性がある箇所")
                .isEmpty();
        assertThat(found).as("走査が実際にsourceを読んでいること（許可表の箇所は必ず見つかる）")
                .containsKeys(ALLOWED.keySet().toArray(String[]::new));
    }

    @Test
    void 判定の要となる共通utilとfilterは生のpathを使わない() throws IOException {
        // PRE-P1-1の実際の迂回点（静的資源・ERRORディスパッチのallow-list等）。許可表へ入れてはならない。
        for (String file : new String[]{
                "com/ses/common/util/SecurityInfrastructureUtils.java",
                "com/ses/config/MenuPermissionFilter.java",
                "com/ses/config/ApiAuditFilter.java",
                "com/ses/config/MfaEnforcementFilter.java",
                "com/ses/config/PasswordChangeEnforcementFilter.java",
                "com/ses/config/BootstrapSessionFilter.java",
                "com/ses/config/PersistentSessionFilter.java",
                "com/ses/config/portal/PortalRateLimitFilter.java",
                "com/ses/config/portal/PortalSessionFilter.java",
                "com/ses/service/security/impl/BreakGlassServiceImpl.java"}) {
            assertThat(ALLOWED).as("許可表へ入れてはならない: %s", file).doesNotContainKey(file);
            assertThat(countAccessors(Files.readString(SOURCE_ROOT.resolve(file), StandardCharsets.UTF_8)))
                    .as(file).isZero();
        }
    }

    @Test
    void 許可表の箇所は実在し理由を持つ() {
        ALLOWED.forEach((file, allowed) -> {
            assertThat(SOURCE_ROOT.resolve(file)).as("許可表のfileが実在すること").isRegularFile();
            assertThat(allowed.reason()).isNotBlank();
        });
        ALLOWED_DIRECTORIES.forEach((directory, reason) -> {
            assertThat(SOURCE_ROOT.resolve(directory)).as("許可表のdirectoryが実在すること").isDirectory();
            assertThat(reason).isNotBlank();
        });
    }

    @Test
    void 検出patternはメソッド呼出しとメソッド参照を数えコメントの説明文は数えない() {
        assertThat(countAccessors("String uri = request.getRequestURI();")).isEqualTo(1);
        assertThat(countAccessors("x(request.getRequestURI ());")).isEqualTo(1);
        assertThat(countAccessors("Optional.of(request).map(HttpServletRequest::getRequestURI)")).isEqualTo(1);
        assertThat(countAccessors("String p = request.getServletPath() + request.getPathInfo();")).isEqualTo(1);
        assertThat(countAccessors("StringBuffer url = request.getRequestURL();")).isEqualTo(1);
        assertThat(countAccessors("stream.map(HttpServletRequest::getServletPath)")).isEqualTo(1);
        assertThat(countAccessors("// {@code getRequestURI()} は未復号")).isZero();
        assertThat(countAccessors("String getRequestURIs() { return null; }")).isZero();
    }

    private static int countAccessors(String source) {
        Matcher matcher = ACCESSOR.matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
