package com.ses.web;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

/** account linkを書き込むbrowser Demo・統合testが、別classと共有するH2へ行を混入させる再発を防ぐ。 */
class AccountLinkBrowserTestIsolationGuardTest {
    /** t_engineer_account_link を書き込むため、共有H2では別classの行と衝突するclass。 */
    private static final List<String> ACCOUNT_LINK_WRITERS = List.of(
            "src/test/java/com/ses/web/AccountingIntegrationBrowserDemoTest.java",
            "src/test/java/com/ses/web/EngineerSelfServiceBrowserMTest.java",
            "src/test/java/com/ses/web/PwaRestoreChromeAuthenticationBrowserTest.java",
            "src/test/java/com/ses/web/PwaRestoreServletBoundaryIntegrationTest.java",
            "src/test/java/com/ses/service/pwa/PwaRestoreTransactionIntegrationTest.java");
    /** @SpringBootTest の properties で固定名のH2を指定する形。 */
    private static final Pattern FIXED_URL = Pattern.compile("spring\\.datasource\\.url=jdbc:h2:mem:([^;\"\\s]+)");
    /** @DynamicPropertySource で変数に入れたH2名を指定する形。 */
    private static final Pattern DYNAMIC_URL = Pattern.compile(
            "registry\\.add\\(\\s*\"spring\\.datasource\\.url\"\\s*,\\s*\\(\\)\\s*->\\s*\"jdbc:h2:mem:\"\\s*\\+\\s*(\\w+)");

    @Test
    void account_linkを書き込むclassは別名の専用H2を使用する() throws Exception {
        Set<String> databases = new HashSet<>();
        for (String file : ACCOUNT_LINK_WRITERS) {
            String source = Files.readString(Path.of(file));
            assertThat(source).as(file + "はt_engineer_account_linkを扱うclassです").contains("t_engineer_account_link");
            String database = dedicatedDatabase(file, source);
            assertThat(database).as(file + "は共有H2を使いません").isNotIn("testdb", "test");
            assertThat(databases.add(database)).as(file + ": classの間で同名DBを共用しません").isTrue();
        }
    }

    /** 固定名ならその名前を、実行ごとの一意名ならUUIDを連結する前の接頭辞を返す。どちらでもなければ失敗させる。 */
    private static String dedicatedDatabase(String file, String source) {
        Matcher fixed = FIXED_URL.matcher(source);
        if (fixed.find()) return fixed.group(1);
        Matcher dynamic = DYNAMIC_URL.matcher(source);
        assertThat(dynamic.find()).as(file + "は専用H2を明示してください").isTrue();
        Matcher prefix = Pattern.compile("String\\s+" + Pattern.quote(dynamic.group(1))
                + "\\s*=\\s*\"([^\"]+)\"\\s*\\+\\s*UUID\\.randomUUID\\(\\)").matcher(source);
        assertThat(prefix.find()).as(file + "のH2名は実行ごとに一意（UUID連結）にしてください").isTrue();
        return prefix.group(1);
    }
}
