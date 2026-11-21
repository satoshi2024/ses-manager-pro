package com.ses.common.jdbc;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Connector/J（{@code com.mysql.cj.conf.ConnectionUrl}）による JDBC URL の解析。driver は runtime scope の依存のため
 * reflection で呼ぶ（compile classpath に置かない）。解析で DNS などの外部照会をしないよう、型を先に確かめ、
 * {@code SINGLE_CONNECTION} の場合だけ host の一覧を取る（DNS SRV 型の host 一覧は照会を伴う）。
 *
 * <p>取込 metadata の透過再接続の検査（{@code ImportJdbcReconnectGuard}）の入れ子の enum だったものを、backup の書込み静止の
 * prod の起動時検査（{@code BackupQuiesceProductionValidator}。{@code readOnlyPropagatesToServer}）からも使うために移した
 * （RB-QUIESCE BWQ-007。design.md §12.2）。判定の規則は移す前と同じ。
 */
public enum ConnectorJUrl {
    /** Connector/J が受け付けない URL（H2・MariaDB driver 用など）。前段の文字列判定だけで判断する。 */
    NOT_CONNECTOR_J,
    /** {@code SINGLE_CONNECTION} で host が1件。 */
    SINGLE_HOST,
    /** failover・load-balance・replication・X DevAPI・DNS SRV、または host が1件でない。 */
    MULTIPLE_HOSTS,
    /** Connector/J が受け付けるのに解析に失敗した、または driver を呼べない（MySQL の URL に見える場合）。 */
    PARSE_FAILED;

    private static final String CONNECTION_URL_CLASS = "com.mysql.cj.conf.ConnectionUrl";
    /** driver を呼べないときに MySQL の URL とみなす形（scheme の符号化・空白を含めて大まかに見る）。 */
    private static final Pattern MYSQL_LIKE = Pattern.compile("(?is)\\s*jdbc:(?:mysql|%6d).*");

    /** URL（と driver へ渡る Properties）を Connector/J に解析させ、接続の型と host の件数で分類する。 */
    public static ConnectorJUrl inspect(String url, Properties driverInfo) {
        Class<?> connectionUrl;
        try {
            connectionUrl = Class.forName(CONNECTION_URL_CLASS, false, ConnectorJUrl.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError unavailable) {
            return MYSQL_LIKE.matcher(url).matches() ? PARSE_FAILED : NOT_CONNECTOR_J;
        }
        try {
            Object accepted = connectionUrl.getMethod("acceptsUrl", String.class).invoke(null, url);
            if (!Boolean.TRUE.equals(accepted)) return NOT_CONNECTOR_J;
            Object parsed = connectionUrl.getMethod("getConnectionUrlInstance", String.class,
                    Properties.class).invoke(null, url, copyOf(driverInfo));
            Object type = connectionUrl.getMethod("getType").invoke(parsed);
            if (!(type instanceof Enum<?> kind) || !"SINGLE_CONNECTION".equals(kind.name())) return MULTIPLE_HOSTS;
            Object hosts = connectionUrl.getMethod("getHostsList").invoke(parsed);
            return hosts instanceof List<?> list && list.size() == 1 ? SINGLE_HOST : MULTIPLE_HOSTS;
        } catch (java.lang.reflect.InvocationTargetException parseFailure) {
            // 例外の message は URL（認証情報を含み得る）を含むため使わない。
            return PARSE_FAILED;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unverifiable) {
            return PARSE_FAILED;
        }
    }

    /**
     * 展開後の有効な属性。{@code connectorJ=false} は Connector/J の URL ではない（検査の対象外）。{@code properties=null} は
     * Connector/J の URL なのに属性を確かめられなかった（呼出し側は fail-closed で拒否する）。
     */
    public record Effective(boolean connectorJ, Map<String, String> properties) {
    }

    /**
     * Connector/J に URL と info を解析させ、main host の有効な属性（{@code HostInfo#getHostProperties}。{@code useConfigs}
     * の同梱設定と {@code propertiesTransform} の変換を反映した後のもの）を返す。{@link #inspect} が単一接続と判定した URL
     * にだけ使う（host 一覧の取得で DNS の照会をしない）。
     */
    public static Effective effectiveProperties(String url, Properties driverInfo) {
        Class<?> connectionUrl;
        try {
            connectionUrl = Class.forName(CONNECTION_URL_CLASS, false, ConnectorJUrl.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError unavailable) {
            return MYSQL_LIKE.matcher(url).matches() ? new Effective(true, null) : new Effective(false, null);
        }
        try {
            Object accepted = connectionUrl.getMethod("acceptsUrl", String.class).invoke(null, url);
            if (!Boolean.TRUE.equals(accepted)) return new Effective(false, null);
            Object parsed = connectionUrl.getMethod("getConnectionUrlInstance", String.class,
                    Properties.class).invoke(null, url, copyOf(driverInfo));
            Object mainHost = connectionUrl.getMethod("getMainHost").invoke(parsed);
            Object properties = mainHost.getClass().getMethod("getHostProperties").invoke(mainHost);
            if (!(properties instanceof Map<?, ?> map)) return new Effective(true, null);
            Map<String, String> copy = new java.util.LinkedHashMap<>();
            map.forEach((name, value) -> copy.put(String.valueOf(name), value == null ? null : String.valueOf(value)));
            return new Effective(true, copy);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unverifiable) {
            // 例外の message は URL（認証情報を含み得る）を含むため使わない。
            return new Effective(true, null);
        }
    }

    /** driver に渡す Properties の複製（driver 側の変更を呼出元へ返さない）。 */
    private static Properties copyOf(Properties driverInfo) {
        Properties copy = new Properties();
        if (driverInfo != null) {
            driverInfo.stringPropertyNames().forEach(name -> copy.setProperty(name, driverInfo.getProperty(name)));
        }
        return copy;
    }
}
