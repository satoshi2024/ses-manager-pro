package com.ses.importcenter.metadata;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * JDBC接続設定で透過再接続・接続先の切替えが起き得る構成なら起動を中止する。
 *
 * <p>取込metadata commitは「COMMIT前の失敗＝未commit確定（stage破棄）」「COMMIT処理自体の失敗＝結果不明（保全・照合）」
 * を接続が切れたら必ず失敗するという前提で分類している（{@link ImportCsvUploadCommitService}）。透過再接続が有効だと、
 * 実際にはcommit済みのtransactionに対するrollbackが再接続先で「成功」し、commit済みmetadataが参照する原本objectを
 * 補償削除してしまう（N-P3-2）。拒否するのは次の2種類である。
 * <ul>
 *   <li>Connector/J の {@code autoReconnect} / {@code autoReconnectForPools} が有効（URLのquery・Hikariの
 *       data-source-properties。値は百分率符号化を戻してから判定し、大文字小文字・{@code true}/{@code yes}の別は問わない）。</li>
 *   <li>複数hostへ切り替える URL（W2-NF06 review P3-2）: {@code jdbc:mysql:loadbalance://}・{@code jdbc:mysql:replication://}・
 *       {@code jdbc:mysql+srv://}（DNS SRVで複数hostへ展開する）・{@code jdbc:mysqlx://}、MariaDB の
 *       {@code failover}・{@code sequential}・{@code loadbalance}・{@code replication}・{@code aurora}、および host 部に
 *       複数の host を書いた URL（{@code h1,h2}・{@code [h1,h2]}・{@code address=(...)} / {@code (host=...)} の形）。
 *       Connector/J の failover・load-balance proxy は通信異常時に別の接続へ切り替える（{@code pickNewConnection}）ため、
 *       透過再接続と同じく結果不明のCOMMITの分類を崩す。host部の key-value 形は1 hostでも拒否する（host:port 形へ書き直す）。</li>
 * </ul>
 * ProxySQL・MySQL Router等の透過再接続proxyと、Hikari以外のconnection pool（{@code spring.datasource.type}）も同じ理由で
 * 禁止だが、前者はアプリから検出できず、後者は本検査の対象外のため、配備文書
 * （{@code .kiro/specs/data-migration-import-center/a1a-ingress-operations.md} §5、{@code feature-switches.md} 4.8.2 #7）で禁止する。
 *
 * <p>検査対象は{@code spring.datasource.url}、{@code spring.datasource.hikari.jdbc-url}、
 * {@code spring.datasource.hikari.data-source-properties.*}。違反時の例外文言にはproperty名と違反の種類だけを出し、
 * URL（認証情報を含み得る）は出さない。
 */
@Component
public class ImportJdbcReconnectGuard implements InitializingBean {
    private static final Pattern AUTO_RECONNECT_IN_URL =
            Pattern.compile("(?i)autoReconnect(?:ForPools)?\\s*=\\s*(?:true|yes)(?![A-Za-z0-9])");
    /** MySQL・MariaDB の JDBC URL の scheme（{@code jdbc:} から {@code ://} の手前まで）。 */
    private static final Pattern MYSQL_FAMILY_SCHEME = Pattern.compile("(?i)jdbc:(mysql|mariadb)[^/]*");
    /** 単一 host を指す scheme。これ以外（{@code :loadbalance}・{@code +srv} 等）は接続先を切り替え得る。 */
    private static final List<String> SINGLE_HOST_SCHEMES = List.of("jdbc:mysql", "jdbc:mariadb");
    private static final List<String> URL_PROPERTIES =
            List.of("spring.datasource.url", "spring.datasource.hikari.jdbc-url");
    private static final String DATA_SOURCE_PROPERTIES = "spring.datasource.hikari.data-source-properties";
    /** 複数 host の違反の印（例外文言・試験で使う固定値）。 */
    static final String MULTI_HOST = "multiHost";

    private final Environment environment;

    public ImportJdbcReconnectGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> violations = violations(environment);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("JDBC接続設定で透過再接続（autoReconnect）または複数hostへの切替え"
                    + "（failover・loadbalance・replication・multi-host・SRV）が有効なため起動を中止します。"
                    + "取込commitの結果分類が成立しなくなるため、単一hostの jdbc:mysql://host:port/db に直してください: "
                    + violations);
        }
    }

    /** 違反した property 名（複数 host の違反は {@code 名前(multiHost)}）。値は返さない。 */
    static List<String> violations(Environment environment) {
        List<String> violations = new ArrayList<>();
        for (String key : URL_PROPERTIES) {
            String url = environment.getProperty(key);
            if (url == null) continue;
            if (autoReconnect(url)) violations.add(key);
            if (multiHost(url)) violations.add(key + "(" + MULTI_HOST + ")");
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

    /** URL の query に autoReconnect が有効な指定があるか。Connector/J は値を百分率符号化から戻して読むため、戻した形でも見る。 */
    static boolean autoReconnect(String url) {
        if (AUTO_RECONNECT_IN_URL.matcher(url).find()) return true;
        String decoded = percentDecoded(url);
        return decoded != null && AUTO_RECONNECT_IN_URL.matcher(decoded).find();
    }

    /**
     * MySQL・MariaDB の URL が単一 host 以外へ接続し得るか。他の DB（H2 等）の URL は対象外で false。
     * scheme が単一 host のもの以外、または host 部（{@code ://} の後から最初の {@code /}・{@code ?}・{@code ;} まで。
     * user 情報 {@code user:pass@} は除く）に {@code ,}・{@code (}・{@code =} を含むものを複数 host とみなす。
     */
    static boolean multiHost(String url) {
        String trimmed = url.trim();
        int separator = trimmed.indexOf("://");
        String scheme = separator < 0 ? trimmed : trimmed.substring(0, separator);
        if (!MYSQL_FAMILY_SCHEME.matcher(scheme).matches()) {
            return false;
        }
        if (separator < 0 || !SINGLE_HOST_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String rest = trimmed.substring(separator + 3);
        int end = rest.length();
        for (char terminator : new char[]{'/', '?', ';'}) {
            int index = rest.indexOf(terminator);
            if (index >= 0 && index < end) end = index;
        }
        String hosts = rest.substring(0, end);
        int userInfo = hosts.lastIndexOf('@');
        if (userInfo >= 0) hosts = hosts.substring(userInfo + 1);
        return hosts.isEmpty() || hosts.indexOf(',') >= 0 || hosts.indexOf('(') >= 0 || hosts.indexOf('=') >= 0;
    }

    private static String percentDecoded(String url) {
        if (url.indexOf('%') < 0) return url;
        try {
            return URLDecoder.decode(url.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            // 不正な符号化は driver も解釈できない。元の文字列だけで判定する。
            return null;
        }
    }
}
