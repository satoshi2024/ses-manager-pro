package com.ses.featureenablement.nf05;

import com.ses.config.feature.Feature;
import com.ses.config.integrationhub.IntegrationHubApprovals;
import com.ses.config.integrationhub.IntegrationHubEnablementDecision;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.SignOffRequirement;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * NF05 の起動の前の読取り（feature-enablement-console design §9.1。FE-D02）。NF05 の bean の有無は {@code @Conditional} が bean の定義の
 * 登録の時に決め、その時には DataSource の bean がまだ無いため、context の refresh の前に DB を読み、NF05 の property を決める。
 *
 * <ul>
 *   <li>対象: 有効な profile に {@code prod} を含むか、{@code app.feature-enablement.source=database}。それ以外（dev・test の property の
 *       源）は何もしない。</li>
 *   <li>順序: {@code ConfigDataEnvironmentPostProcessor} の後（{@code application*.yml} と環境変数を読み込んだ後）。</li>
 *   <li>読取り: {@code spring.datasource.*} で JDBC の接続を1本だけ開く（pool を作らない。接続・socket・照会の timeout は5秒）。
 *       MySQL の URL だけを読む（他の driver は {@code READ_FAILED}）。V203 の表と既存の列だけを使い、表・列が無ければ
 *       {@code READ_FAILED}（app はその後 schema の検査で止まる）。読むだけで書かない。</li>
 *   <li>判定: {@link IntegrationHubEnablementDecision#decide}（評価器と同じ純粋な関数）。</li>
 *   <li>書く property（最も優先する {@value #PROPERTY_SOURCE}）: 両方向の enabled を常に（閉でも {@code false} を）書く
 *       （{@code validateBoundaries} は明示を要する）。{@code integration.hub.retention-purge.enabled}（{@code READ_FAILED} でも true。
 *       review P3-N3）、{@code app.feature-enablement.nf05-boot.<方向>.result}（{@code OPENED} / {@code CLOSED:<code>} /
 *       {@code READ_FAILED}）・{@code .state}・{@code .version}・{@code .decision-id}・{@code .approval-digest}（確認済みの署名の値。
 *       秘密ではない）。static な holder を使わない。</li>
 *   <li>接続の失敗・例外は {@code READ_FAILED} で両方向を閉にする（fail-closed）。log は code だけ（値・URL・例外の本文を出さない）。</li>
 * </ul>
 *
 * <p>既知の制約: DB の account を context の前に使う（同じ account・同じ secret。新しい権限は要らない）。DBA・Security の受理は RB §1.2
 * に書いた（未取得）。</p>
 */
public class IntegrationHubEnablementBootResolver implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE = "featureEnablementNf05Boot";
    public static final String INBOUND_ENABLED = "integration.hub.public-api.enabled";
    public static final String OUTBOUND_ENABLED = "integration.hub.external-transport.enabled";
    public static final String PURGE_ENABLED = "integration.hub.retention-purge.enabled";
    public static final String BOOT_PREFIX = IntegrationHubApprovals.BOOT_PREFIX;
    /** {@code ConfigDataEnvironmentPostProcessor} の後。 */
    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 10;
    static final int TIMEOUT_SECONDS = 5;
    /** NF05 の保存期間の対象の表（行があれば purge を作る）。 */
    static final List<String> RETENTION_TABLES = List.of("t_api_delivery", "t_api_delivery_replay_audit",
            "t_api_nonce_replay", "t_api_read_snapshot", "t_inbound_event", "t_api_idempotency_record");
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final Log log;
    private final JdbcReader reader;

    public IntegrationHubEnablementBootResolver(DeferredLogFactory logFactory) {
        this(logFactory.getLog(IntegrationHubEnablementBootResolver.class), IntegrationHubEnablementBootResolver::connect);
    }

    /** 試験用（接続を差し替える）。 */
    IntegrationHubEnablementBootResolver(Log log, JdbcReader reader) {
        this.log = log;
        this.reader = reader;
    }

    /** 接続を開く部品（試験で差し替える）。 */
    @FunctionalInterface
    interface JdbcReader {
        Connection open(String url, String username, String password) throws SQLException;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!targeted(environment)) {
            return;
        }
        Map<String, Object> values = resolve(environment);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, values));
        log.info("[機能の開放] NF05 の起動時の判定: inbound=" + values.get(BOOT_PREFIX + "inbound.result")
                + " outbound=" + values.get(BOOT_PREFIX + "outbound.result") + " purge=" + values.get(PURGE_ENABLED));
    }

    /** 対象の起動か（prod か DB の源）。 */
    static boolean targeted(ConfigurableEnvironment environment) {
        String source = environment.getProperty("app.feature-enablement.source");
        return environment.acceptsProfiles(Profiles.of("prod"))
                || (source != null && "database".equalsIgnoreCase(source.trim()));
    }

    /** 判定して property の値を作る（読めなければ両方向を閉・purge を開）。 */
    Map<String, Object> resolve(ConfigurableEnvironment environment) {
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            IntegrationHubEnablementDecision.BootInputs inputs = read(environment);
            IntegrationHubEnablementDecision.Decision decision =
                    IntegrationHubEnablementDecision.decide(inputs, FeatureEnablementCatalog.standard());
            IntegrationHubApprovals approvals = inputs.effectiveApprovals();
            put(values, "inbound", decision.inbound(), inputs.inbound(), approvals.inbound());
            put(values, "outbound", decision.outbound(), inputs.outbound(), approvals.outbound());
            values.put(PURGE_ENABLED, Boolean.toString(decision.purgeEnabled()));
        } catch (Exception failure) {
            log.warn("[機能の開放] NF05 の起動の前の読取りに失敗したため、この起動では NF05 を閉じます: "
                    + "errorCode=NF05_BOOT_READ_FAILED exceptionClass=" + failure.getClass().getSimpleName());
            values.clear();
            values.put(INBOUND_ENABLED, "false");
            values.put(OUTBOUND_ENABLED, "false");
            values.put(PURGE_ENABLED, "true");
            values.put(BOOT_PREFIX + "inbound.result", IntegrationHubEnablementDecision.RESULT_READ_FAILED);
            values.put(BOOT_PREFIX + "outbound.result", IntegrationHubEnablementDecision.RESULT_READ_FAILED);
        }
        return values;
    }

    private static void put(Map<String, Object> values, String direction, IntegrationHubEnablementDecision.Result result,
                            IntegrationHubEnablementDecision.DirectionState state,
                            IntegrationHubApprovals.Decision approval) {
        values.put("inbound".equals(direction) ? INBOUND_ENABLED : OUTBOUND_ENABLED, Boolean.toString(result.open()));
        values.put(BOOT_PREFIX + direction + ".result", result.propertyValue());
        values.put(BOOT_PREFIX + direction + ".state", state.state() == null ? "NONE" : state.state());
        values.put(BOOT_PREFIX + direction + ".version", Integer.toString(state.version()));
        if (approval.decisionId() != null) {
            values.put(BOOT_PREFIX + direction + ".decision-id", approval.decisionId());
        }
        if (approval.approvalDigest() != null) {
            values.put(BOOT_PREFIX + direction + ".approval-digest", approval.approvalDigest());
        }
    }

    /** DB と環境から判定の入力を作る（読むだけ）。 */
    IntegrationHubEnablementDecision.BootInputs read(ConfigurableEnvironment environment) throws Exception {
        String allowed = environment.getProperty("app.feature-enablement.allowed", "true");
        boolean masterAllowed = "true".equals(allowed.trim());
        String tenantId = environment.getProperty("app.security.oidc.tenant-id");
        tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
        IntegrationHubExternalApiProperties properties = Binder.get(environment)
                .bind("integration.hub", Bindable.of(IntegrationHubExternalApiProperties.class))
                .orElseGet(IntegrationHubExternalApiProperties::new);
        properties.setEnvironment(environment);
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith("jdbc:mysql:")) {
            // 起動の前の読取りは本番の MySQL だけ（H2 等では schema がまだ無い。design §9.1 の 0）
            throw new SQLException("unsupported datasource");
        }
        try (Connection connection = reader.open(url, environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"))) {
            connection.setReadOnly(true);
            Map<Feature, IntegrationHubEnablementDecision.DirectionState> states = readStates(connection, tenantId);
            Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs = readSignOffs(connection, tenantId);
            String barrier = scalarString(connection,
                    "SELECT config_value FROM m_system_config WHERE config_key = ?",
                    IntegrationHubEnablementDecision.BARRIER_CONFIG_KEY);
            IntegrationHubEnablementDecision.DirectionState outbound = states.getOrDefault(Feature.NF05_OUTBOUND,
                    IntegrationHubEnablementDecision.DirectionState.NONE);
            Boolean offAudit = null;
            if ("OPEN_PENDING_RESTART".equals(outbound.state())) {
                // idx_auditlog_reference (reference_type, reference_id) を使う（review P3-N8。付けないと全表を走査する）
                offAudit = exists(connection, "SELECT 1 FROM t_audit_log WHERE reference_type = ? AND reference_id = 0 "
                        + "AND application_code = ? LIMIT 1", IntegrationHubEnablementDecision.AUDIT_REFERENCE_TYPE,
                        IntegrationHubEnablementDecision.OFF_AUDIT_CODE);
            }
            boolean retentionRows = false;
            for (String table : RETENTION_TABLES) {
                if (exists(connection, "SELECT 1 FROM " + table + " LIMIT 1")) {
                    retentionRows = true;
                    break;
                }
            }
            IntegrationHubEnablementDecision.TruststoreRead truststore =
                    IntegrationHubEnablementDecision.TruststoreRead.notAttempted();
            if ("OPEN".equals(outbound.state()) || "OPEN_PENDING_RESTART".equals(outbound.state())) {
                truststore = new IntegrationHubEnablementDecision.TruststoreRead(true,
                        IntegrationHubEnablementDecision.readTruststore(properties.getExternalTransport().getTruststorePath()));
            }
            return new IntegrationHubEnablementDecision.BootInputs(tenantId, LocalDate.now(JST), masterAllowed,
                    states.getOrDefault(Feature.NF05_INBOUND, IntegrationHubEnablementDecision.DirectionState.NONE),
                    outbound, signOffs, new IntegrationHubEnablementDecision.BarrierRead(true, barrier), offAudit,
                    retentionRows, properties, environment, null, truststore);
        }
    }

    private static Map<Feature, IntegrationHubEnablementDecision.DirectionState> readStates(Connection connection,
                                                                                            String tenantId)
            throws SQLException {
        Map<Feature, IntegrationHubEnablementDecision.DirectionState> states = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT feature_key, state, version, opened_at "
                + "FROM t_feature_enablement_state WHERE tenant_id = ? AND feature_key IN (?, ?)")) {
            statement.setQueryTimeout(TIMEOUT_SECONDS);
            statement.setString(1, tenantId);
            statement.setString(2, Feature.NF05_INBOUND.name());
            statement.setString(3, Feature.NF05_OUTBOUND.name());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Feature feature = Feature.valueOf(rows.getString("feature_key"));
                    states.put(feature, new IntegrationHubEnablementDecision.DirectionState(rows.getString("state"),
                            rows.getInt("version"), rows.getTimestamp("opened_at") != null));
                }
            }
        }
        return states;
    }

    private static Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> readSignOffs(Connection connection,
                                                                                         String tenantId)
            throws SQLException {
        Set<String> kinds = new LinkedHashSet<>();
        FeatureEnablementCatalog catalog = FeatureEnablementCatalog.standard();
        for (Feature feature : List.of(Feature.NF05_INBOUND, Feature.NF05_OUTBOUND)) {
            for (SignOffRequirement requirement : catalog.unit(feature).signOffs()) {
                kinds.add(requirement.kindCode());
            }
        }
        List<String> ordered = new ArrayList<>(kinds);
        StringBuilder sql = new StringBuilder("SELECT id, version, kind_code, reference, signer_role, signed_on, "
                + "expires_on, structured_values, kind_class FROM t_feature_sign_off WHERE tenant_id = ? "
                + "AND status = 'CONFIRMED' AND kind_code IN (");
        for (int i = 0; i < ordered.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(")");
        Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setQueryTimeout(TIMEOUT_SECONDS);
            statement.setString(1, tenantId);
            for (int i = 0; i < ordered.size(); i++) {
                statement.setString(i + 2, ordered.get(i));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    signOffs.put(rows.getString("kind_code"), new FeatureEnablementSnapshot.ConfirmedSignOff(
                            rows.getLong("id"), rows.getInt("version"), rows.getString("kind_code"),
                            rows.getString("reference"), rows.getString("signer_role"),
                            rows.getDate("signed_on").toLocalDate(), rows.getDate("expires_on").toLocalDate(),
                            rows.getString("structured_values"), rows.getString("kind_class")));
                }
            }
        }
        return signOffs;
    }

    private static String scalarString(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(TIMEOUT_SECONDS);
            statement.setString(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static boolean exists(Connection connection, String sql, String... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(TIMEOUT_SECONDS);
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** 接続を1本だけ開く（pool を作らない。接続と socket の timeout は5秒）。 */
    static Connection connect(String url, String username, String password) throws SQLException {
        Properties info = new Properties();
        if (username != null) {
            info.setProperty("user", username);
        }
        if (password != null) {
            info.setProperty("password", password);
        }
        info.setProperty("connectTimeout", Integer.toString(TIMEOUT_SECONDS * 1000));
        info.setProperty("socketTimeout", Integer.toString(TIMEOUT_SECONDS * 1000));
        return DriverManager.getConnection(url, info);
    }
}
