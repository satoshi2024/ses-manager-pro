package com.ses.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SES.csrf.header()} はヘッダー名ではなく「ヘッダー名→値」の object を返す（common.js）。
 *
 * <p>NF02 の service-desk.js / customer-health.js は {@code { [SES.csrf.header()]: SES.csrf.token() }} と書いており、
 * ヘッダー名が {@code "[object Object]"} になって {@code setRequestHeader} が例外を投げ、一覧・顧客選択・起票などの
 * AJAX が全て送信されなかった（ServiceDeskBrowserMTest で検出）。同じ誤用の再発を静的に止める。
 */
class CsrfHeaderHelperUsageGuardTest {

    private static final Path STATIC_ROOT = Path.of("src", "main", "resources", "static");
    /** header() の戻り値を computed property のキーに使う書き方（空白の有無を問わない）。 */
    private static final Pattern MISUSE = Pattern.compile("\\[\\s*(?:window\\.)?SES\\.csrf\\.header\\(\\)\\s*\\]");

    @Test
    void csrfヘッダー補助の戻り値をヘッダー名として使わない() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(STATIC_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".js")).toList()) {
                if (file.toString().replace('\\', '/').contains("/lib/")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (MISUSE.matcher(lines.get(i)).find()) {
                        offenders.add(STATIC_ROOT.relativize(file) + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(offenders)
                .as("SES.csrf.header() は object を返すため headers へそのまま渡す（例: headers: SES.csrf.header()）: %s", offenders)
                .isEmpty();
    }

    @Test
    void 検出規則は既知の誤用を検出する() {
        assertThat(MISUSE.matcher("headers: { [window.SES.csrf.header()]: window.SES.csrf.token() }").find()).isTrue();
        assertThat(MISUSE.matcher("headers: {[SES.csrf.header()]: t}").find()).isTrue();
        assertThat(MISUSE.matcher("headers: window.SES.csrf.header()").find()).isFalse();
    }
}
