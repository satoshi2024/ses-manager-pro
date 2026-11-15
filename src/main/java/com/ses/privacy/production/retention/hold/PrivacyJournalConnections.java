package com.ses.privacy.production.retention.hold;

import com.ses.privacy.foundation.JournalUnavailableException;
import com.ses.privacy.foundation.PrivacyJournalPort;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 独立 Journal インスタンスへの接続を閉じ込める専用 holder（NF07-A1J、journal-witness-grant-design.md §2.1）。
 *
 * <p>内部に connection pool を持つが、Spring には {@code DataSource} 型の Bean として公開しない。{@code DataSource} 型で
 * 登録すると {@code DataSourceAutoConfiguration}（{@code @ConditionalOnMissingBean(DataSource.class)}）が application 用の
 * DataSource を作らなくなり、単一候補を前提にする {@code JdbcTemplate}・MyBatis-Plus の自動構成も退くか誤って結び付くため。
 * pool は public な method から取り出せず、同じ package の起動時検査と、{@link #openJournalPort} で生成する本番 adapter
 * にだけ渡す。
 *
 * <ul>
 *   <li>journal 設定（url・username・password・journal-id のいずれか）が無い: pool を作らず、接続を一度も試みない。</li>
 *   <li>設定はあるが契約（{@link #contractViolations}）に反する: pool を作らない（不適合な設定で接続しない）。</li>
 *   <li>契約に適合: flag とは独立に pool を作る（flag を OFF に戻しても、破壊的経路が既存 hold の効力を Journal で
 *       確認できるようにするため）。pool は起動時に接続せず（{@code minimumIdle=0}・初期化時の接続確認なし）、
 *       接続は起動時検査と adapter の呼出しのときだけ行う。</li>
 * </ul>
 * session time zone は {@code +00:00} に固定し、driver の時刻変換も UTC にする（{@code TIMESTAMP(6)} とマイクロ秒正規化の
 * hash 再計算を一致させるため。§2.2）。透過再接続は使わない。socket の読み待ちは {@link #SOCKET_TIMEOUT} で打ち切る
 * （statement timeout が届かない網分断や、statement timeout の対象外の COMMIT でも接続を返させるため。§2.4）。
 * 接続失敗の例外は {@link SanitizingJournalDriverDataSource} が固定文言に置き換え、principal・接続先を log と cause に出さない。
 *
 * <p>principal は1つ（{@code privacy.journal.datasource.*}）。retention flag ON（hold 開始を使う）では appender、OFF
 * （既存 hold の効力確認だけ）では reader または appender を使う（§2.1・§3.1、総括決定の方式A）。どちらかは起動時検査が
 * 実際の権限で判定する。
 */
public final class PrivacyJournalConnections implements AutoCloseable {

    /** pool 名（log に出る。接続先・principal を含めない）。 */
    static final String POOL_NAME = "privacy-journal";

    static final int MIN_POOL_SIZE = 1;
    static final int MAX_POOL_SIZE = 8;
    static final Duration MIN_CONNECTION_TIMEOUT = Duration.ofMillis(250);
    static final Duration MAX_CONNECTION_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Connector/J の {@code socketTimeout}（設定 key は持たない固定値）。append の transaction 上限
     * （{@link ProductionPrivacyComponents#journalAppendTimeout()}、10秒）より長くし、正常な statement を切らない。
     */
    static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(20);

    private static final String URL_PREFIX = "jdbc:mysql://";
    private static final Pattern SCHEMA_NAME = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final Pattern JOURNAL_ID = Pattern.compile("[A-Za-z0-9._-]{4,64}");
    /** NF06 の {@code ImportJdbcReconnectGuard} と同じ判定（B-NF06-5）。 */
    private static final Pattern AUTO_RECONNECT =
            Pattern.compile("(?i)autoReconnect(?:ForPools)?\\s*=\\s*(?:true|yes)(?![A-Za-z0-9])");
    /** URL で driver の時刻変換を上書きさせない（UTC 固定と hash 再計算の一致を崩すため）。 */
    private static final Set<String> TIME_ZONE_PARAMETERS = Set.of(
            "connectiontimezone", "servertimezone", "forceconnectiontimezonetosession", "preserveinstants");
    /** URL で holder が決める timeout を上書きさせない（append の在途時間の上限が崩れるため）。 */
    private static final Set<String> TIMEOUT_PARAMETERS = Set.of("sockettimeout", "connecttimeout");

    private final boolean configured;
    private final List<String> contractViolations;
    private final String expectedSchema;
    private final String journalId;
    private final Duration appendInFlightUpperBound;
    private final HikariDataSource pool;

    PrivacyJournalConnections(PrivacyJournalProperties properties, Function<HikariConfig, HikariDataSource> poolFactory) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(poolFactory, "poolFactory");
        this.configured = isConfigured(properties);
        this.contractViolations = configured ? contractViolations(properties) : List.of();
        this.expectedSchema = properties.getExpectedSchema();
        this.journalId = properties.getJournalId();
        this.appendInFlightUpperBound = appendInFlightUpperBound(connectionTimeoutOf(properties));
        this.pool = configured && contractViolations.isEmpty() ? poolFactory.apply(poolConfig(properties)) : null;
    }

    /** 設定から holder を作る。契約に適合する場合だけ pool を作る（接続はしない）。 */
    public static PrivacyJournalConnections create(PrivacyJournalProperties properties) {
        return new PrivacyJournalConnections(properties, HikariDataSource::new);
    }

    /** journal 設定が与えられているか（url・username・password・journal-id のいずれか）。 */
    public boolean isConfigured() {
        return configured;
    }

    /** 内部 pool を作ったか（設定があり契約に適合する場合だけ true）。 */
    public boolean isPoolCreated() {
        return pool != null;
    }

    /** 設定契約の違反（固定の code。接続文字列・principal・password を含めない）。未設定なら空。 */
    public List<String> contractViolations() {
        return contractViolations;
    }

    public String expectedSchema() {
        return expectedSchema;
    }

    public String journalId() {
        return journalId;
    }

    /**
     * 1回の append が Journal で確定し得る時間の上限（{@link #appendInFlightUpperBound(Duration)}）。
     * A1H の reconciler は {@code pending_since} からこの時間（実際には {@code privacy.journal.pending-timeout}。契約で
     * これより長いことを要求する）が過ぎるまで「Journal に command が無い」pending を消してはならない。
     */
    public Duration appendInFlightUpperBound() {
        return appendInFlightUpperBound;
    }

    /**
     * append の在途時間の上限 = pool の接続待ち（{@code connection-timeout}）+ append の transaction 上限（10秒）
     * + socket timeout（20秒。statement timeout の対象外の COMMIT と、statement timeout が届かない網分断の余裕）。
     * Spring の transaction timeout は接続を得た後から数えるため、接続待ちを別に足す。
     */
    static Duration appendInFlightUpperBound(Duration connectionTimeout) {
        return connectionTimeout.plus(ProductionPrivacyComponents.journalAppendTimeout()).plus(SOCKET_TIMEOUT);
    }

    private static Duration connectionTimeoutOf(PrivacyJournalProperties properties) {
        PrivacyJournalProperties.Datasource datasource = properties.getDatasource();
        Duration timeout = datasource == null ? null : datasource.getConnectionTimeout();
        // 範囲外の値は契約違反（pool を作らない）。上限の計算では保守側の最大値を使う
        if (timeout == null || timeout.compareTo(MIN_CONNECTION_TIMEOUT) < 0 || timeout.compareTo(MAX_CONNECTION_TIMEOUT) > 0) {
            return MAX_CONNECTION_TIMEOUT;
        }
        return timeout;
    }

    /**
     * 本番 Journal adapter を生成する（{@link ProductionPrivacyComponents#productionPrivacyJournal} 経由。prod profile 必須）。
     * 起動時検査が {@code AVAILABLE} でない間に使う判断（gate の {@code UNAVAILABLE}）は呼出し側（A1H）が行う。
     */
    public PrivacyJournalPort openJournalPort(Environment environment, Clock clock) {
        if (pool == null) {
            throw new JournalUnavailableException("journal接続が構成されていません", null);
        }
        return ProductionPrivacyComponents.productionPrivacyJournal(environment, pool, clock);
    }

    /** 同じ package の起動時検査だけが使う。public にしない（{@code DataSource} を外へ出さない）。 */
    DataSource dataSource() {
        return pool;
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.close();
        }
    }

    static boolean isConfigured(PrivacyJournalProperties properties) {
        if (StringUtils.hasText(properties.getJournalId())) {
            return true;
        }
        PrivacyJournalProperties.Datasource datasource = properties.getDatasource();
        return datasource != null && (StringUtils.hasText(datasource.getUrl())
                || StringUtils.hasText(datasource.getUsername()) || StringUtils.hasText(datasource.getPassword()));
    }

    /** §2.1 の設定契約。違反を固定 code で返す（設定値そのものは返さない）。 */
    static List<String> contractViolations(PrivacyJournalProperties properties) {
        Set<String> violations = new LinkedHashSet<>();
        PrivacyJournalProperties.Datasource datasource = properties.getDatasource() == null
                ? new PrivacyJournalProperties.Datasource() : properties.getDatasource();
        String url = datasource.getUrl();
        if (!StringUtils.hasText(url)) {
            violations.add("datasource.url.missing");
        } else {
            urlViolations(url.trim(), violations);
        }
        if (!StringUtils.hasText(datasource.getUsername())) {
            violations.add("datasource.username.missing");
        }
        if (!StringUtils.hasText(datasource.getPassword())) {
            violations.add("datasource.password.missing");
        }
        if (datasource.getMaxPoolSize() < MIN_POOL_SIZE || datasource.getMaxPoolSize() > MAX_POOL_SIZE) {
            violations.add("datasource.maxPoolSize.outOfRange");
        }
        Duration timeout = datasource.getConnectionTimeout();
        if (timeout == null || timeout.compareTo(MIN_CONNECTION_TIMEOUT) < 0
                || timeout.compareTo(MAX_CONNECTION_TIMEOUT) > 0) {
            violations.add("datasource.connectionTimeout.outOfRange");
        }
        if (properties.getExpectedSchema() == null || !SCHEMA_NAME.matcher(properties.getExpectedSchema()).matches()) {
            violations.add("expectedSchema.invalid");
        }
        if (properties.getJournalId() == null || !JOURNAL_ID.matcher(properties.getJournalId()).matches()) {
            violations.add("journalId.invalid");
        }
        Duration pendingTimeout = properties.getPendingTimeout();
        if (pendingTimeout != null
                && pendingTimeout.compareTo(appendInFlightUpperBound(connectionTimeoutOf(properties))) <= 0) {
            // reconciler が pending を消した後に在途の append が確定し得ないよう、在途時間の上限より長くする
            violations.add("pendingTimeout.notLongerThanAppendInFlight");
        }
        return List.copyOf(violations);
    }

    private static void urlViolations(String url, Set<String> violations) {
        if (!url.toLowerCase(Locale.ROOT).startsWith(URL_PREFIX)) {
            // jdbc:mysql:loadbalance:// 等の多重 host 形式も含めて拒否する（primary に限る。§2.2）
            violations.add("datasource.url.scheme");
            return;
        }
        String rest = url.substring(URL_PREFIX.length());
        int query = rest.indexOf('?');
        String hostAndPath = query < 0 ? rest : rest.substring(0, query);
        int slash = hostAndPath.indexOf('/');
        String authority = slash < 0 ? hostAndPath : hostAndPath.substring(0, slash);
        if (authority.isBlank() || authority.contains(",") || authority.contains("(") || authority.contains("@")) {
            // 複数 host（failover）・address=(...) 形式・URL 内の認証情報は使わない
            violations.add("datasource.url.host");
        }
        if (AUTO_RECONNECT.matcher(url).find()) {
            violations.add("datasource.url.autoReconnect");
        }
        if (url.indexOf('%') >= 0) {
            // driver は parameter の key・値を percent-decode するが、本検査は decode しない。
            // 符号化で検査を迂回させない（例: sslM%6Fde=DISABLED、autoReconnect=tr%75e）ため一律に拒否する
            violations.add("datasource.url.percentEncoded");
        }
        Map<String, String> parameters = parameters(query < 0 ? "" : rest.substring(query + 1), violations);
        if (!"VERIFY_IDENTITY".equalsIgnoreCase(parameters.get("sslmode"))) {
            violations.add("datasource.url.sslModeNotVerifyIdentity");
        }
        if ("false".equalsIgnoreCase(parameters.get("usessl")) || "false".equalsIgnoreCase(parameters.get("requiressl"))) {
            violations.add("datasource.url.sslDisabled");
        }
        if (parameters.keySet().stream().anyMatch(TIME_ZONE_PARAMETERS::contains)) {
            violations.add("datasource.url.timeZoneOverride");
        }
        if (parameters.keySet().stream().anyMatch(TIMEOUT_PARAMETERS::contains)) {
            violations.add("datasource.url.timeoutOverride");
        }
        if (parameters.keySet().stream().anyMatch(key -> key.equals("user") || key.startsWith("password"))) {
            // principal と password は username / password の設定（secret store）だけで渡す。URL に secret を入れない
            violations.add("datasource.url.credentials");
        }
    }

    /** query を key（小文字）→ 値にする。同じ key が2回以上あれば違反にする（driver と本検査で採る値がずれ得るため）。 */
    private static Map<String, String> parameters(String query, Set<String> violations) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = (equals < 0 ? pair : pair.substring(0, equals)).trim().toLowerCase(Locale.ROOT);
            String value = equals < 0 ? "" : pair.substring(equals + 1).trim();
            if (parameters.put(key, value) != null) {
                violations.add("datasource.url.duplicateParameter");
            }
        }
        return parameters;
    }

    /**
     * pool の設定（契約の検査は {@link #contractViolations} が先に行う。同じ package の test も同じ設定を使う）。
     * driver への接続は {@link SanitizingJournalDriverDataSource} を通し、principal・password・driver property は
     * その中にだけ持つ（Hikari の設定には username・password・URL を入れない）。
     */
    static HikariConfig poolConfig(PrivacyJournalProperties properties) {
        PrivacyJournalProperties.Datasource datasource = properties.getDatasource();
        long timeoutMillis = datasource.getConnectionTimeout().toMillis();
        Properties driverProperties = new Properties();
        driverProperties.setProperty("user", datasource.getUsername());
        driverProperties.setProperty("password", datasource.getPassword());
        // session time zone は下の init SQL で +00:00 に固定し、driver 側の変換も UTC にそろえる（§2.2）
        driverProperties.setProperty("connectionTimeZone", "UTC");
        driverProperties.setProperty("forceConnectionTimeZoneToSession", "false");
        driverProperties.setProperty("preserveInstants", "true");
        driverProperties.setProperty("autoReconnect", "false");
        driverProperties.setProperty("connectTimeout", Long.toString(timeoutMillis));
        // statement timeout の対象外の COMMIT・網分断で読み待ちが続かないよう、socket の読み待ちを打ち切る（§2.4）
        driverProperties.setProperty("socketTimeout", Long.toString(SOCKET_TIMEOUT.toMillis()));

        HikariConfig config = new HikariConfig();
        config.setPoolName(POOL_NAME);
        config.setDataSource(new SanitizingJournalDriverDataSource(datasource.getUrl().trim(), driverProperties));
        config.setMaximumPoolSize(datasource.getMaxPoolSize());
        // 起動時・待機中に接続を作らない（接続は起動時検査と adapter の呼出しのときだけ）
        config.setMinimumIdle(0);
        config.setInitializationFailTimeout(-1);
        config.setConnectionTimeout(timeoutMillis);
        config.setAutoCommit(true);
        config.setRegisterMbeans(false);
        config.setConnectionInitSql("SET SESSION time_zone = '+00:00'");
        return config;
    }
}
