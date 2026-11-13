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

    private static ImportJdbcReconnectGuard guard(MockEnvironment environment) {
        return new ImportJdbcReconnectGuard(environment);
    }
}
