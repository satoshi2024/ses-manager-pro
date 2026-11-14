package com.ses.web;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

/** 同時に登録するaccount linkが別browser Demoの共有H2へ混入する再発を防ぐ。 */
class BrowserDemoDatabaseIsolationGuardTest {
    @Test
    void 会計と要員browser_Demoは別名の専用H2を使用する() throws Exception {
        Set<String> databases = new HashSet<>();
        Pattern url = Pattern.compile("spring\\.datasource\\.url=jdbc:h2:mem:([^;\"\\s]+)");
        for (String file : List.of("AccountingIntegrationBrowserDemoTest.java", "EngineerSelfServiceBrowserMTest.java")) {
            String source = Files.readString(Path.of("src/test/java/com/ses/web", file));
            var match = url.matcher(source);
            assertThat(match.find()).as(file + "は専用H2を明示してください").isTrue();
            assertThat(match.group(1)).isNotIn("testdb", "test");
            assertThat(databases.add(match.group(1))).as("browser Demo間で同名DBを共用しません").isTrue();
        }
    }
}
