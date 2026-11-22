package com.ses.importcenter.metadata;

import com.ses.common.jdbc.ConnectorJUrl;
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
 *       透過再接続と同じく結果不明のCOMMITの分類を崩す。host部の key-value 形は1 hostでも拒否する（host:port 形へ書き直す）。
 *       最終判定は Connector/J 自身の解析結果で、単一接続（{@code SINGLE_CONNECTION}）かつ host 1件のものだけを通し、
 *       Connector/J が受け付けるのに解析できない URL も拒否する（RB-NF05 delta review N-P3-1。{@link #connectionTargetViolation}）。
 *       解析には Hikari が driver へ渡す data-source-properties も渡す（{@code dnsSrv=true} は単一 host の URL でも
 *       DNS SRV の failover にする。D-P3-1）。</li>
 *   <li>data-source-properties に置いた、接続の型・接続先・failover／load-balance／replication の動作を変える key
 *       （{@link #CONNECTION_TOPOLOGY_KEYS}。値に関係なく、大文字小文字を問わない）。</li>
 *   <li>Connector/J が解析時に展開した後の有効な属性（{@code getMainHost().getHostProperties()}）で、autoReconnect 系が
 *       有効、または接続の型・接続先・切替えを変える key がある（RB-NF05 delta review E-P3-1）。{@code useConfigs}
 *       （同梱の {@code clusterBase}・{@code coldFusion} は {@code autoReconnect=true} を足す）と {@code propertiesTransform}
 *       （classpath の変換 class が属性を書き換える）は、URL の文字列と data-source-properties の key 名だけを見る検査を
 *       迂回できるため、driver の解析結果そのものを見る（{@link #effectivePropertyViolations}）。</li>
 * </ul>
 * ProxySQL・MySQL Router等の透過再接続proxyと、Hikari以外のconnection pool（{@code spring.datasource.type}）も同じ理由で
 * 禁止だが、前者はアプリから検出できず、後者は本検査の対象外のため、配備文書
 * （{@code .kiro/specs/data-migration-import-center/a1a-ingress-operations.md} §5、{@code feature-switches.md} 4.8.2 #7）で禁止する。
 *
 * <p>検査対象は{@code spring.datasource.url}、{@code spring.datasource.hikari.jdbc-url}、
 * {@code spring.datasource.hikari.data-source-properties.*}（{@code url} は JDBC URL と同じ規則で見る）。違反時の例外文言には
 * property名と違反の種類だけを出し、
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
                    + "（failover・loadbalance・replication・multi-host・SRV）が有効、JDBC driverが解析できないURL、"
                    + "またはdata-source-properties・driverの展開後の属性（useConfigs・propertiesTransform）で"
                    + "接続の型・接続先・切替えを変えているため起動を中止します。"
                    + "取込commitの結果分類が成立しなくなるため、単一hostの jdbc:mysql://host:port/db に直してください: "
                    + violations);
        }
    }

    /**
     * 違反した property 名（複数 host の違反は {@code 名前(multiHost)}、Connector/J が解析できない URL は
     * {@code 名前(unparsable)}、data-source-properties の接続の型・接続先を変える key は {@code 名前(connectionTopology)}、
     * driver の展開後の属性の違反は URL の {@code 名前(effectiveAutoReconnect)}・{@code 名前(connectionTopology)}）。
     * 値は返さない。
     */
    static List<String> violations(Environment environment) {
        List<String> violations = new ArrayList<>();
        Map<String, String> dataSourceProperties = Binder.get(environment)
                .bind(DATA_SOURCE_PROPERTIES, Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        // Hikari は jdbcUrl での接続時に data-source-properties をそのまま driver の Properties（info）として渡す。
        // Connector/J は接続の型を info の値（dnsSrv 等）でも決めるため、URL の解析にも同じ Properties を渡す
        // （RB-NF05 delta review D-P3-1）。url 自身は info から除く（JDBC URL 側の判定で見る）。
        java.util.Properties driverInfo = new java.util.Properties();
        dataSourceProperties.forEach((name, value) -> {
            if (name != null && value != null && !"url".equalsIgnoreCase(name)) driverInfo.setProperty(name, value);
        });
        // Spring 側で driver の Properties に入る他の入力元は username・password だけ（Hikari の DriverDataSource が
        // data-source-properties に無い時だけ user・password として足す）。接続の型・host は変えないが、driver と同じ入力にする。
        addIfAbsent(driverInfo, "user", firstNonNull(environment.getProperty("spring.datasource.hikari.username"),
                environment.getProperty("spring.datasource.username")));
        addIfAbsent(driverInfo, "password", firstNonNull(environment.getProperty("spring.datasource.hikari.password"),
                environment.getProperty("spring.datasource.password")));
        // data-source-properties に直接置いた key は下の key 検査で違反にするため、展開後の属性の検査では重ねて出さない。
        java.util.Set<String> directDataSourceKeys = new java.util.HashSet<>();
        boolean dataSourceAutoReconnect = false;
        for (Map.Entry<String, String> property : dataSourceProperties.entrySet()) {
            if (property.getKey() == null) continue;
            String name = property.getKey().toLowerCase(Locale.ROOT);
            directDataSourceKeys.add(name);
            if (enablesAutoReconnect(name, property.getValue())) dataSourceAutoReconnect = true;
        }
        for (String key : URL_PROPERTIES) {
            String url = environment.getProperty(key);
            if (url == null) continue;
            boolean urlAutoReconnect = autoReconnect(url);
            if (urlAutoReconnect) violations.add(key);
            String target = connectionTargetViolation(url, driverInfo);
            if (target != null) {
                violations.add(key + "(" + target + ")");
            } else {
                violations.addAll(effectivePropertyViolations(key, url, driverInfo, directDataSourceKeys,
                        urlAutoReconnect || dataSourceAutoReconnect));
            }
        }
        for (Map.Entry<String, String> property : dataSourceProperties.entrySet()) {
            String name = property.getKey() == null ? "" : property.getKey().toLowerCase(Locale.ROOT);
            if (enablesAutoReconnect(name, property.getValue())) {
                violations.add(DATA_SOURCE_PROPERTIES + "." + property.getKey());
            }
            // 接続の型・接続先・切替えを変える key は、値に関係なく拒否する（大文字小文字を問わない。D-P3-1）。
            if (CONNECTION_TOPOLOGY_KEYS.contains(name)) {
                violations.add(DATA_SOURCE_PROPERTIES + "." + property.getKey() + "(" + CONNECTION_TOPOLOGY + ")");
            }
            // data source の url property（MysqlDataSource#setUrl）も JDBC URL と同じ規則で検査する（review P3-1）。
            if ("url".equals(name) && property.getValue() != null) {
                boolean urlAutoReconnect = autoReconnect(property.getValue());
                if (urlAutoReconnect) {
                    violations.add(DATA_SOURCE_PROPERTIES + "." + property.getKey());
                }
                String target = connectionTargetViolation(property.getValue(), driverInfo);
                if (target != null) {
                    violations.add(DATA_SOURCE_PROPERTIES + "." + property.getKey() + "(" + target + ")");
                } else {
                    violations.addAll(effectivePropertyViolations(DATA_SOURCE_PROPERTIES + "." + property.getKey(),
                            property.getValue(), driverInfo, directDataSourceKeys,
                            urlAutoReconnect || dataSourceAutoReconnect));
                }
            }
        }
        return List.copyOf(violations);
    }

    /** autoReconnect 系の key（小文字）が有効な値か。Connector/J の boolean は true/yes を有効とする（前後の空白と大文字小文字は問わない）。 */
    private static boolean enablesAutoReconnect(String lowerCaseName, String value) {
        if (!"autoreconnect".equals(lowerCaseName) && !"autoreconnectforpools".equals(lowerCaseName)) return false;
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "yes".equals(normalized);
    }

    /** driver の展開後の属性で autoReconnect 系が有効になる違反の印（URL・data-source-properties の文字列には現れない場合）。 */
    static final String EFFECTIVE_AUTO_RECONNECT = "effectiveAutoReconnect";

    /**
     * 接続先の検査を通った URL について、Connector/J が解析時に展開した後の有効な属性（main host の
     * {@code getHostProperties()}。接続はこの属性で初期化される）を検査する（RB-NF05 delta review E-P3-1）。
     * {@code useConfigs}・{@code propertiesTransform} は解析の中で属性を足し・書き換えるため、展開前の文字列では見えない。
     * <ul>
     *   <li>autoReconnect 系が有効なら {@code 名前(effectiveAutoReconnect)}。URL の query・data-source-properties に直接書いた
     *       有効な指定は既に別の違反として出しているため重ねない（{@code autoReconnectReported}）。</li>
     *   <li>{@link #CONNECTION_TOPOLOGY_KEYS} の key があれば {@code 名前(connectionTopology)}（値に関係なく）。
     *       data-source-properties に直接置いた key は key 検査で出すため除く。URL の query に書いた key と、展開で足された
     *       key（{@code clusterBase} の {@code failOverReadOnly} 等）はここで拒否する。</li>
     *   <li>Connector/J の URL でなければ（H2・MariaDB driver 用）対象外。属性を確かめられなければ {@code 名前(unparsable)}。</li>
     * </ul>
     */
    static List<String> effectivePropertyViolations(String key, String url, java.util.Properties driverInfo,
            java.util.Set<String> directDataSourceKeys, boolean autoReconnectReported) {
        ConnectorJUrl.Effective effective = ConnectorJUrl.effectiveProperties(url, driverInfo);
        if (!effective.connectorJ()) return List.of();
        if (effective.properties() == null) return List.of(key + "(" + UNPARSABLE + ")");
        boolean reconnect = false;
        boolean topology = false;
        for (Map.Entry<String, String> property : effective.properties().entrySet()) {
            String name = property.getKey() == null ? "" : property.getKey().toLowerCase(Locale.ROOT);
            if (enablesAutoReconnect(name, property.getValue())) reconnect = true;
            if (CONNECTION_TOPOLOGY_KEYS.contains(name) && !directDataSourceKeys.contains(name)) topology = true;
        }
        List<String> violations = new ArrayList<>();
        if (reconnect && !autoReconnectReported) violations.add(key + "(" + EFFECTIVE_AUTO_RECONNECT + ")");
        if (topology) violations.add(key + "(" + CONNECTION_TOPOLOGY + ")");
        return violations;
    }

    private static void addIfAbsent(java.util.Properties info, String name, String value) {
        if (value != null && info.getProperty(name) == null) info.setProperty(name, value);
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    /** data-source-properties の接続の型・接続先・切替えを変える key の違反の印。 */
    static final String CONNECTION_TOPOLOGY = "connectionTopology";

    /**
     * data-source-properties に置くと、接続の型・接続先・切替えの動作を変える Connector/J 8.3.0 の key（小文字。{@code PropertyKey}
     * の key 名を照合して選んだ。RB-NF05 delta review D-P3-1）。値に関係なく拒否する。
     * <ul>
     *   <li>型を変える: {@code dnsSrv}（info で true なら DNS SRV の failover になる）、{@code xdevapi.dns-srv}。</li>
     *   <li>接続先を変える: {@code host}・{@code port}・{@code protocol}・{@code path}・{@code address}・{@code priority}・
     *       {@code type}（host の属性。URL の host が空なら info の host を使う）、{@code socketFactory}（接続の作り方を差し替える）。</li>
     *   <li>failover・load-balance・replication の動作: {@code failOverReadOnly}・{@code retriesAllDown}・
     *       {@code secondsBeforeRetrySource}・{@code queriesBeforeRetrySource}・{@code allowSourceDownConnections}・
     *       {@code allowReplicaDownConnections}・{@code readFromSourceWhenNoReplicas}・{@code ha.loadBalanceStrategy}・
     *       {@code ha.enableJMX}・{@code loadBalance*} の10件・{@code replicationConnectionGroup}・{@code serverAffinityOrder}、
     *       再接続の {@code maxReconnects}・{@code reconnectAtTxEnd}。旧名（{@code secondsBeforeRetryMaster} 等）も含める。</li>
     *   <li>{@code PropertyKey} ではないが接続先を決める {@code MysqlDataSource} の bean property: {@code serverName}・
     *       {@code portNumber}（{@code data-source-class-name} で MysqlDataSource を使うと setter で設定され、JDBC URL の代わりに
     *       接続先になる）。</li>
     *   <li>属性そのものを書き換える: {@code propertiesTransform}（classpath の {@code ConnectionPropertiesTransform} 実装が解析時に
     *       任意の属性・host を書き換える。RB-NF05 delta review E-P3-1）。</li>
     * </ul>
     * 接続先は検査した JDBC URL の host:port だけで決め、Properties で上書きさせない（8.3.0 では {@code host}・{@code port} は
     * URL の host 部が空の時に使われる）。照合して拒否の対象から外した key: {@code localSocketAddress}（ローカル側の bind address）、
     * {@code socksProxyHost}・{@code socksProxyPort}・{@code socksProxyRemoteDns}（TCP の中継で、切断は driver へそのまま
     * 失敗として届き、別の接続へ切り替えない）、{@code resourceId}（XA の識別）。
     * {@code autoReconnect}・{@code autoReconnectForPools} は従来どおり値（true/yes）で判定する（false の明示は通す）。
     */
    static final java.util.Set<String> CONNECTION_TOPOLOGY_KEYS = java.util.Set.of(
            "dnssrv", "xdevapi.dns-srv",
            "host", "port", "protocol", "path", "address", "priority", "type", "socketfactory",
            "failoverreadonly", "retriesalldown", "secondsbeforeretrysource", "queriesbeforeretrysource",
            "allowsourcedownconnections", "allowreplicadownconnections", "readfromsourcewhennoreplicas",
            "secondsbeforeretrymaster", "queriesbeforeretrymaster", "allowmasterdownconnections",
            "allowslavedownconnections", "readfrommasterwhennoslaves",
            "ha.loadbalancestrategy", "ha.enablejmx",
            "loadbalanceautocommitstatementregex", "loadbalanceautocommitstatementthreshold",
            "loadbalanceblocklisttimeout", "loadbalanceblacklisttimeout", "loadbalanceconnectiongroup",
            "loadbalanceexceptionchecker", "loadbalancehostremovalgraceperiod", "loadbalancepingtimeout",
            "loadbalancesqlexceptionsubclassfailover", "loadbalancesqlstatefailover",
            "loadbalancevalidateconnectiononswapserver",
            "replicationconnectiongroup", "serveraffinityorder", "maxreconnects", "reconnectattxend",
            "servername", "portnumber", "propertiestransform");

    /** URL の query に autoReconnect が有効な指定があるか。Connector/J は値を百分率符号化から戻して読むため、戻した形でも見る。 */
    static boolean autoReconnect(String url) {
        if (AUTO_RECONNECT_IN_URL.matcher(url).find()) return true;
        String decoded = percentDecoded(url);
        return decoded != null && AUTO_RECONNECT_IN_URL.matcher(decoded).find();
    }

    /**
     * URL の接続先の違反。違反が無ければ null、複数 host へ切り替え得るなら {@link #MULTI_HOST}、Connector/J が受け付ける
     * URL を Connector/J 自身が解析できない・確かめられないなら {@link #UNPARSABLE}。
     *
     * <p>最終判定は Connector/J の解析結果である（RB-NF05 delta review N-P3-1）。Connector/J が受け付ける URL
     * （{@code ConnectionUrl.acceptsUrl}）は {@code ConnectionUrl.getConnectionUrlInstance} で解析し、型が
     * {@code SINGLE_CONNECTION} で host がちょうど1件の場合だけ通す。自前の文字列判定（{@link #multiHost}）は前段の速い拒否で、
     * Connector/J が扱わない MariaDB の URL もここで拒否する。両方を通ったものだけを通す（どちらかが拒否すれば拒否）。
     * 文字列判定だけでは、scheme と {@code //} の間の空白（{@code jdbc:mysql: //h1,h2/db}）、百分率符号化した scheme
     * （{@code jdbc:%6Dysql:loadbalance://}）、user 情報の {@code ,}（Connector/J は {@code ,} で先に分けてから user 情報を取る）を
     * Connector/J と同じに解釈できなかった。
     */
    static String connectionTargetViolation(String url) {
        return connectionTargetViolation(url, new java.util.Properties());
    }

    /**
     * {@link #connectionTargetViolation(String)} を、driver へ渡る Properties（Hikari の data-source-properties）付きで判定する。
     * Connector/J は接続の型を URL と info の両方から決めるため（{@code dnsSrv} 等）、解析にも同じ info を渡す（D-P3-1）。
     */
    static String connectionTargetViolation(String url, java.util.Properties driverInfo) {
        if (url == null) return null;
        if (multiHost(url)) return MULTI_HOST;
        return switch (ConnectorJUrl.inspect(url, driverInfo)) {
            case NOT_CONNECTOR_J, SINGLE_HOST -> null;
            case MULTIPLE_HOSTS -> MULTI_HOST;
            case PARSE_FAILED -> UNPARSABLE;
        };
    }

    /**
     * 自前の前段判定: MySQL・MariaDB の URL が単一 host 以外へ接続し得るか。他の DB（H2 等）の URL は対象外で false。
     * scheme が単一 host のもの以外、または host 部を複数 host とみなす。host 部は Connector/J の authority と同じく
     * {@code ://} の後から最初の {@code /}・{@code ?}・{@code #} の手前まで（{@code ;} では終わらない。RB-NF05 review P3-1）。
     * authority に {@code ,} があれば、user 情報の中でも複数 host とする（Connector/J は {@code ,} で分けてから user 情報を取る）。
     * user 情報 {@code user:pass@} を除いた host 部に {@code (}・{@code =}・{@code ;} を含めば複数 host とする
     * （{@code ;} は host 名に現れないため、含む host 部は解釈の違いで failover へ化け得るものとして拒否する）。
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
        for (char terminator : new char[]{'/', '?', '#'}) {
            int index = rest.indexOf(terminator);
            if (index >= 0 && index < end) end = index;
        }
        String authority = rest.substring(0, end);
        if (authority.indexOf(',') >= 0) return true;
        int userInfo = authority.lastIndexOf('@');
        String hosts = userInfo >= 0 ? authority.substring(userInfo + 1) : authority;
        return hosts.isEmpty() || hosts.indexOf('(') >= 0 || hosts.indexOf('=') >= 0 || hosts.indexOf(';') >= 0;
    }

    /** 解析できない・確かめられない URL の違反の印。 */
    static final String UNPARSABLE = "unparsable";

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
