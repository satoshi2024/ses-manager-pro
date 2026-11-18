package com.ses.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 初期管理者パスワードhashの検査callback（配備決定-3）をDocker無しで検証する。
 * 実MySQLで「migrationを1件も実行せずに失敗する」ことは {@code FlywayProdFreshInstallMySqlTest} が見る。
 */
class ProductionAdminInitialPasswordFlywayCallbackTest {

    private static final Path ADMIN_SCRIPT =
            Path.of("src/main/resources/db/migration-prod/R__update_admin_password_bcrypt.sql");

    @Test
    void BCryptのhashだけを受け付ける() {
        String hash = new BCryptPasswordEncoder().encode("Initial-Admin-" + System.nanoTime());
        assertThatCode(() -> ProductionAdminInitialPasswordFlywayCallback.validate(hash)).doesNotThrowAnyException();
        assertThatCode(() -> ProductionAdminInitialPasswordFlywayCallback.validate(
                new BCryptPasswordEncoder(12).encode("Initial-Admin-12"))).doesNotThrowAnyException();
    }

    @Test
    void 未設定_空_平文_低cost_公開hashは値をmessageへ出さずに失敗する() {
        String plaintext = "Plain-Initial-Password-2026";
        String lowCost = new BCryptPasswordEncoder(4).encode("Initial-Admin-04");
        for (String invalid : new String[]{null, "", "   ", plaintext, lowCost,
                "${admin_initial_password_hash}", ProductionAdminInitialPasswordFlywayCallback.PUBLISHED_ADMIN123_HASH}) {
            assertThatThrownBy(() -> ProductionAdminInitialPasswordFlywayCallback.validate(invalid))
                    .as("不正な値: %s", invalid == null ? "null" : "length=" + invalid.length())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("初期管理者パスワードhash")
                    .hasMessageContaining("migrationは1件も実行していません")
                    .satisfies(e -> {
                        if (invalid != null && !invalid.isBlank()) {
                            assertThat(e.getMessage()).doesNotContain(invalid);
                        }
                    });
        }
    }

    @Test
    void BEFORE_MIGRATEだけでplaceholderを検査する() {
        ProductionAdminInitialPasswordFlywayCallback callback = new ProductionAdminInitialPasswordFlywayCallback();
        Context context = mock(Context.class);
        when(context.getConfiguration()).thenReturn(Flyway.configure()
                .placeholders(Map.of(ProductionAdminInitialPasswordFlywayCallback.PLACEHOLDER, "")));

        assertThat(callback.supports(Event.BEFORE_MIGRATE, context)).isTrue();
        assertThat(callback.supports(Event.BEFORE_INFO, context)).isFalse();
        assertThat(callback.supports(Event.AFTER_MIGRATE, context)).isFalse();
        assertThatThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE, context))
                .isInstanceOf(FlywayException.class);
        assertThatCode(() -> callback.handle(Event.AFTER_MIGRATE, context)).doesNotThrowAnyException();
    }

    @Test
    void Flywayのcallback名順でlegacy補償callbackより先に実行される() {
        // FlywayのDefaultCallbackExecutorは同じeventのcallbackをgetCallbackName()の昇順で実行する
        assertThat(new ProductionAdminInitialPasswordFlywayCallback().getCallbackName())
                .isLessThan(new LegacyDatabaseFlywayCallback().getCallbackName());
    }

    @Test
    void Spring_beanではない() {
        // beanにするとSpring BootがdevやtestのFlywayにも登録し、placeholderの無い起動を失敗させる
        assertThat(ProductionAdminInitialPasswordFlywayCallback.class.isAnnotationPresent(Component.class)).isFalse();
        assertThat(org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
                ProductionAdminInitialPasswordFlywayCallback.class, Component.class)).isFalse();
    }

    @Test
    void prod_scriptは同じ規則で検査しhashを1文にだけ置く() throws Exception {
        String sql = Files.readString(ADMIN_SCRIPT, StandardCharsets.UTF_8);
        String sqlRegex = "^[$]2[aby][$](1[0-9]|2[0-9]|3[01])[$][./A-Za-z0-9]{53}$";

        assertThat(sql).contains("'" + sqlRegex + "'");
        assertThat(sqlRegex.replace("[$]", "\\$"))
                .as("SQL側の検査とJava側の検査は同じ規則であること")
                .isEqualTo(ProductionAdminInitialPasswordFlywayCallback.BCRYPT.pattern());
        assertThat(sql).contains("'" + ProductionAdminInitialPasswordFlywayCallback.PUBLISHED_ADMIN123_HASH + "' THEN");

        String placeholder = "${" + ProductionAdminInitialPasswordFlywayCallback.PLACEHOLDER + "}";
        assertThat(sql.split(java.util.regex.Pattern.quote(placeholder), -1))
                .as("placeholder（=hash）はscript中に1回だけ現れること")
                .hasSize(2);
        assertThat(sql).contains("SET @ses_admin_initial_password_hash = '" + placeholder + "';");
        assertThat(sql).contains("password_change_required = 1");
        assertThat(sql).contains("SET @ses_admin_initial_password_hash = NULL;");
    }
}
