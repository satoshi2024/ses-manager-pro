package com.ses.importcenter.metadata;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 透過再接続（autoReconnect）が有効なJDBC設定で起動を中止し、例外文言へURL・認証情報を出さないことを確認する。 */
class ImportJdbcReconnectGuardTest {
    private static final String DEFAULT_URL = "jdbc:mysql://localhost:3306/ses_manager_db?useUnicode=true"
            + "&characterEncoding=utf-8&serverTimezone=Asia/Tokyo";

    @Test
    void defaultAndExplicitlyDisabledReconnectSettingsStart() {
        assertDoesNotThrow(() -> guard(new MockEnvironment().withProperty("spring.datasource.url", DEFAULT_URL))
                .afterPropertiesSet());
        assertDoesNotThrow(() -> guard(new MockEnvironment()
                .withProperty("spring.datasource.url", DEFAULT_URL + "&autoReconnect=false")
                .withProperty("spring.datasource.hikari.data-source-properties.autoReconnect", "false"))
                .afterPropertiesSet());
        assertDoesNotThrow(() -> guard(new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:h2:mem:testdb;MODE=MySQL")).afterPropertiesSet());
        assertDoesNotThrow(() -> guard(new MockEnvironment()).afterPropertiesSet());
    }

    @Test
    void autoReconnectInTheJdbcUrlStopsStartupWithoutEchoingTheUrl() {
        for (String parameter : List.of("autoReconnect=true", "AutoReconnect=TRUE", "autoReconnectForPools=true",
                "autoreconnect=yes")) {
            MockEnvironment environment = new MockEnvironment().withProperty("spring.datasource.url",
                    DEFAULT_URL + "&password=secret-in-url&" + parameter);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> guard(environment).afterPropertiesSet(), parameter);

            assertTrue(failure.getMessage().contains("spring.datasource.url"));
            assertFalse(failure.getMessage().contains("secret-in-url"), "URL・認証情報を例外文言へ出さない");
            assertFalse(failure.getMessage().contains("localhost"));
        }
    }

    @Test
    void autoReconnectViaHikariUrlOrDataSourcePropertiesIsAlsoRejected() {
        assertEquals(List.of("spring.datasource.hikari.jdbc-url"), ImportJdbcReconnectGuard.violations(
                new MockEnvironment().withProperty("spring.datasource.hikari.jdbc-url", DEFAULT_URL
                        + "&autoReconnect=true")));
        assertEquals(1, ImportJdbcReconnectGuard.violations(new MockEnvironment()
                .withProperty("spring.datasource.hikari.data-source-properties.autoReconnect", "true")).size());
        assertEquals(1, ImportJdbcReconnectGuard.violations(new MockEnvironment()
                .withProperty("spring.datasource.hikari.data-source-properties.autoReconnectForPools", "YES")).size());
    }

    @Test
    void placeholderResolvedFromAnEnvironmentVariableStyleValueIsChecked() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DB_URL", DEFAULT_URL + "&autoReconnect=true")
                .withProperty("spring.datasource.url", "${DB_URL:" + DEFAULT_URL + "}");

        assertEquals(List.of("spring.datasource.url"), ImportJdbcReconnectGuard.violations(environment));
    }

    /**
     * b-14（W2-NF06 review P3-2）: 通信異常時に別の接続へ切り替える multi-host・failover・loadbalance・replication・SRV の
     * URL も、透過再接続と同じく結果不明のCOMMITの分類を崩すため起動を中止する。例外文言へURL・host名を出さない。
     */
    @Test
    void multiHostとfailoverとloadbalanceとreplicationとSRVのURLは起動を中止する() {
        for (String url : List.of(
                "jdbc:mysql://db-a:3306,db-b:3306/ses_manager_db",
                "jdbc:mysql://[db-a:3306,db-b:3306]/ses_manager_db?useUnicode=true",
                "jdbc:mysql://address=(host=db-a)(port=3306),address=(host=db-b)(port=3306)/ses_manager_db",
                "jdbc:mysql://(host=db-a,port=3306)/ses_manager_db",
                "jdbc:mysql://app:secret-in-url@db-a:3306,db-b:3306/ses_manager_db",
                "jdbc:mysql:loadbalance://db-a:3306,db-b:3306/ses_manager_db",
                "jdbc:mysql:loadbalance://db-a:3306/ses_manager_db",
                "jdbc:mysql:replication://db-a:3306,db-b:3306/ses_manager_db",
                "JDBC:MYSQL:REPLICATION://db-a/ses_manager_db",
                "jdbc:mysql+srv://_mysql._tcp.db.example/ses_manager_db",
                "jdbc:mysql+srv:loadbalance://_mysql._tcp.db.example/ses_manager_db",
                "jdbc:mysqlx://db-a:33060/ses_manager_db",
                "jdbc:mariadb:failover://db-a,db-b/ses_manager_db",
                "jdbc:mariadb:sequential://db-a,db-b/ses_manager_db",
                "jdbc:mariadb:aurora://cluster.example/ses_manager_db")) {
            MockEnvironment environment = new MockEnvironment().withProperty("spring.datasource.url", url);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> guard(environment).afterPropertiesSet(), url);

            assertTrue(failure.getMessage().contains("spring.datasource.url(multiHost)"), url);
            assertFalse(failure.getMessage().contains("db-a") || failure.getMessage().contains("secret-in-url")
                    || failure.getMessage().contains("db.example"), "URL・host名・認証情報を例外文言へ出さない");
        }
        assertEquals(List.of("spring.datasource.hikari.jdbc-url(multiHost)"), ImportJdbcReconnectGuard.violations(
                new MockEnvironment().withProperty("spring.datasource.hikari.jdbc-url",
                        "jdbc:mysql:replication://db-a,db-b/ses_manager_db")));
    }

    @Test
    void 単一hostのURLはuser情報とIPv6とqueryがあっても通しMySQL以外のURLは対象にしない() {
        for (String url : List.of(DEFAULT_URL,
                "jdbc:mysql://app:p%40ss,word@db-a:3306/ses_manager_db?sessionVariables=a=1,b=2",
                "jdbc:mysql://[2001:db8::10]:3306/ses_manager_db",
                "jdbc:mysql://db-a/ses_manager_db",
                "jdbc:mysql://db-a",
                "jdbc:mariadb://db-a:3306/ses_manager_db",
                "jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "jdbc:postgresql://db-a,db-b/other")) {
            assertEquals(List.of(), ImportJdbcReconnectGuard.violations(
                    new MockEnvironment().withProperty("spring.datasource.url", url)), url);
        }
    }

    /** Connector/J は URL の値を百分率符号化から戻して読むため、符号化した autoReconnect も拒否する（同 P3-2 の3）。 */
    @Test
    void 百分率符号化したautoReconnectも拒否する() {
        for (String parameter : List.of("autoReconnect=%74rue", "auto%52econnect=true", "autoReconnectForPools=%59ES")) {
            assertEquals(List.of("spring.datasource.url"), ImportJdbcReconnectGuard.violations(new MockEnvironment()
                    .withProperty("spring.datasource.url", DEFAULT_URL + "&" + parameter)), parameter);
        }
        assertEquals(List.of(), ImportJdbcReconnectGuard.violations(new MockEnvironment()
                .withProperty("spring.datasource.url", DEFAULT_URL + "&autoReconnect=%66alse")));
        assertEquals(List.of(), ImportJdbcReconnectGuard.violations(new MockEnvironment()
                .withProperty("spring.datasource.url", DEFAULT_URL + "&note=%ZZ")), "不正な符号化は元の文字列だけで判定する");
    }

    private static ImportJdbcReconnectGuard guard(MockEnvironment environment) {
        return new ImportJdbcReconnectGuard(environment);
    }
}
