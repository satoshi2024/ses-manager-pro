package com.ses.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FWD-SANITIZE P2-2: 認可・監査・rate limit・MFA強制等の判定に {@code getRequestURI()} を使わせないソース検査（Docker不要）。
 *
 * <p>{@code getRequestURI()} は未復号で、context path（framework strategyでは {@code X-Forwarded-Prefix}）を含む。
 * Spring SecurityのmatcherとMVCは復号後・context path除去後のpathで照合するため、これで判定すると
 * {@code /%61pi/...} や prefix付きの要求がcontrollerへ届くのに判定だけ外れる（本batch以前は転送ヘッダー無しの
 * {@code /%61pi/...} だけで営業がm_menu・action denyを迂回し、更新系の監査も残らなかった）。
 * 判定には {@code com.ses.common.util.RequestPaths} を使うこと。
 *
 * <p>例外は下の許可表だけで、file単位の出現回数まで固定する（許可済みfileへの判定用途の追加も検出する）。
 * 新しい用途で許可が必要になったら、判定に使わない理由を書いて追加すること。
 */
class RequestUriDecisionGuardTest {

    private static final Pattern CALL = Pattern.compile("\\.getRequestURI\\s*\\(");

    /** 走査範囲。自前filter・security設定・break-glass等の認可判定が置かれる場所。 */
    private static final List<Path> SCANNED_ROOTS = List.of(
            Path.of("src", "main", "java", "com", "ses", "config"),
            Path.of("src", "main", "java", "com", "ses", "service", "security"));

    /** 判定に使わないため許可する箇所（src/main/java からの相対path → 出現回数と理由）。 */
    private static final Map<String, Allowed> ALLOWED = Map.of(
            "com/ses/config/CorrelationIdFilter.java",
            new Allowed(1, "相関IDのlog contextへpath上のIDを載せるだけで、認可・監査対象の判定に使わない"),
            "com/ses/config/GlobalControllerAdvice.java",
            new Allowed(3, "画面表示（現在URI・nav選択・言語切替link）だけ。context pathを含むlinkが必要で、prefixは清浄化で除去済み"),
            "com/ses/config/InternalTenantContextFilter.java",
            new Allowed(1, "metrics scraperのallow-list。context path＋固定pathとの完全一致で、符号化変体は一致せず拒否側（fail-closed）"),
            "com/ses/config/feature/FeatureSwitchFilter.java",
            new Allowed(1, "復号後pathと生pathの両方で照合し、どちらかが一致すれば遮断する（遮断側にだけ働く）"));

    /** directory単位で許可する範囲（理由付き）。 */
    private static final Map<String, String> ALLOWED_DIRECTORIES = Map.of(
            "com/ses/config/integrationhub/",
            "NF-05公開API（W3-06a laneで扱い本batchでは変更しない）。外部API判定が外れても、chainのsecurityMatcherと"
                    + "authenticated/denyAll（anonymous無効）で401になる（fail-closed）");

    private record Allowed(int occurrences, String reason) {
    }

    @Test
    void 判定用途のgetRequestURIは許可表以外に存在しない() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        Map<String, Integer> found = new TreeMap<>();
        for (Path root : SCANNED_ROOTS) {
            assertThat(root).as("走査範囲が存在すること: %s", root).isDirectory();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    int count = countCalls(Files.readString(file, StandardCharsets.UTF_8));
                    if (count > 0) {
                        found.put(sourceRoot.relativize(file).toString().replace('\\', '/'), count);
                    }
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
        assertThat(violations).as("getRequestURI()を判定に使っている可能性がある箇所").isEmpty();
    }

    @Test
    void 許可表の箇所は実在し理由を持つ() {
        Path sourceRoot = Path.of("src", "main", "java");
        ALLOWED.forEach((file, allowed) -> {
            assertThat(sourceRoot.resolve(file)).as("許可表のfileが実在すること").isRegularFile();
            assertThat(allowed.reason()).isNotBlank();
        });
        ALLOWED_DIRECTORIES.forEach((directory, reason) -> {
            assertThat(sourceRoot.resolve(directory)).as("許可表のdirectoryが実在すること").isDirectory();
            assertThat(reason).isNotBlank();
        });
    }

    @Test
    void 検出patternはメソッド呼出しだけを数えコメントの説明文は数えない() {
        assertThat(countCalls("String uri = request.getRequestURI();")).isEqualTo(1);
        assertThat(countCalls("x(request.getRequestURI ());")).isEqualTo(1);
        assertThat(countCalls("// {@code getRequestURI()} は未復号")).isZero();
    }

    private static int countCalls(String source) {
        Matcher matcher = CALL.matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
