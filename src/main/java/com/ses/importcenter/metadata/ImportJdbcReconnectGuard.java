package com.ses.importcenter.metadata;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * JDBC接続設定で透過再接続（Connector/Jの{@code autoReconnect} / {@code autoReconnectForPools}）が有効なら起動を中止する。
 *
 * <p>取込metadata commitは「COMMIT前の失敗＝未commit確定（stage破棄）」「COMMIT処理自体の失敗＝結果不明（保全・照合）」
 * を接続が切れたら必ず失敗するという前提で分類している（{@link ImportCsvUploadCommitService}）。透過再接続が有効だと、
 * 実際にはcommit済みのtransactionに対するrollbackが再接続先で「成功」し、commit済みmetadataが参照する原本objectを
 * 補償削除してしまう（N-P3-2）。ProxySQL・MySQL Router等の透過再接続proxyも同じ理由で禁止だが、アプリからは
 * 検出できないため配備文書（{@code .kiro/specs/data-migration-import-center/a1a-ingress-operations.md}）で禁止する。
 *
 * <p>検査対象は{@code spring.datasource.url}、{@code spring.datasource.hikari.jdbc-url}、
 * {@code spring.datasource.hikari.data-source-properties.*}。違反時の例外文言にはproperty名だけを出し、
 * URL（認証情報を含み得る）は出さない。値の大文字小文字・{@code true}/{@code yes}の別は問わない。
 */
@Component
public class ImportJdbcReconnectGuard implements InitializingBean {
    private static final Pattern AUTO_RECONNECT_IN_URL =
            Pattern.compile("(?i)autoReconnect(?:ForPools)?\\s*=\\s*(?:true|yes)(?![A-Za-z0-9])");
    private static final List<String> URL_PROPERTIES =
            List.of("spring.datasource.url", "spring.datasource.hikari.jdbc-url");
    private static final String DATA_SOURCE_PROPERTIES = "spring.datasource.hikari.data-source-properties";

    private final Environment environment;

    public ImportJdbcReconnectGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> violations = violations(environment);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("JDBC接続設定で透過再接続（autoReconnect）が有効なため起動を中止します。"
                    + "取込commitの結果分類が成立しなくなるため、該当設定を削除してください: " + violations);
        }
    }

    static List<String> violations(Environment environment) {
        List<String> violations = new ArrayList<>();
        for (String key : URL_PROPERTIES) {
            String url = environment.getProperty(key);
            if (url != null && AUTO_RECONNECT_IN_URL.matcher(url).find()) violations.add(key);
        }
        Map<String, String> dataSourceProperties = Binder.get(environment)
                .bind(DATA_SOURCE_PROPERTIES, Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        for (Map.Entry<String, String> property : dataSourceProperties.entrySet()) {
            String name = property.getKey() == null ? "" : property.getKey().toLowerCase(Locale.ROOT);
            String value = property.getValue() == null ? "" : property.getValue().trim().toLowerCase(Locale.ROOT);
            if (("autoreconnect".equals(name) || "autoreconnectforpools".equals(name))
                    && ("true".equals(value) || "yes".equals(value))) {
                violations.add(DATA_SOURCE_PROPERTIES + "." + property.getKey());
            }
        }
        return List.copyOf(violations);
    }
}
