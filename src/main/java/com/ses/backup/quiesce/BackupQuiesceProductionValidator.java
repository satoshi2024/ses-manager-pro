package com.ses.backup.quiesce;

import com.ses.common.jdbc.ConnectorJUrl;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * backup の書込み静止の起動時検査（design.md §12.2。U-7・D-P2-6）。port を開く前（{@link SmartInitializingSingleton}）に、
 * 静止応答が instance を登録するより前に動く（{@link BackupQuiesceResponder} が登録の前に {@link #validate()} を呼ぶ。
 * 検査は1回だけ行い、どちらの順で呼ばれても結果は同じ）。
 *
 * <ul>
 *   <li><b>停止口</b>: prod の {@code enabled=false} は、{@code disabled-ticket} が変更 ticket の形式
 *       （{@link #TICKET}）のときだけ許す。無い・形式が違えば起動を中止する。許した場合の動き（DISABLED の登録・heartbeat・
 *       REFUSED（DISABLED）・ERROR と gauge）は {@link BackupQuiesceLifecycleAnchor}・{@link BackupQuiesceResponder}・
 *       {@link BackupQuiesceMetrics} が行う。prod 以外の {@code enabled=false} は何もしない（INERT）。</li>
 *   <li><b>最後の防線を消す JDBC 設定</b>: 業務の DataSource の JDBC URL（{@code spring.datasource.url}・
 *       {@code spring.datasource.hikari.jdbc-url}・data-source-properties の {@code url}）と
 *       {@code spring.datasource.hikari.data-source-properties} で、Connector/J の {@code readOnlyPropagatesToServer} が
 *       有効でなければ起動を中止する。この属性が無効だと、静止中の {@code setReadOnly(true)} が
 *       {@code SET SESSION TRANSACTION READ ONLY} を送らず、permit の無い書込みが DB に届く（§6.5.1）。URL の query と
 *       data-source-properties の key に加え、Connector/J が解析の時に展開した後の有効な属性（{@code useConfigs}・
 *       {@code propertiesTransform} の結果）も見る（{@link ConnectorJUrl#effectiveProperties}）。prod では常に、prod 以外では
 *       {@code enabled=true} のときに行う。</li>
 * </ul>
 *
 * <p>prod の判定は {@code Environment.acceptsProfiles}（property の {@code spring.profiles.active} だけを読まない。CLAUDE.md）。
 * 例外の文言には property 名と違反の種類だけを出し、URL（認証情報を含み得る）と ticket の値は出さない。
 * 値の範囲と相互の関係は {@link BackupQuiesceProperties}（{@code @Validated}）が bind の時に全 profile で検査する。
 */
public class BackupQuiesceProductionValidator implements SmartInitializingSingleton {

    /** 停止口の変更 ticket の形式（U-7。例 {@code CHG-1234}）。 */
    public static final Pattern TICKET = Pattern.compile("^[A-Z][A-Z0-9]+-[0-9]+$");
    /** Connector/J の属性名（大文字小文字を問わずに照合する）。 */
    static final String READ_ONLY_PROPAGATES = "readOnlyPropagatesToServer";

    private static final List<String> URL_PROPERTIES =
            List.of("spring.datasource.url", "spring.datasource.hikari.jdbc-url");
    private static final String DATA_SOURCE_PROPERTIES = "spring.datasource.hikari.data-source-properties";

    private final Environment environment;
    private final BackupQuiesceProperties properties;
    private volatile boolean validated;

    public BackupQuiesceProductionValidator(Environment environment, BackupQuiesceProperties properties) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    /** 検査する（1回だけ。違反があれば {@link IllegalStateException} で起動を中止する）。 */
    public synchronized void validate() {
        if (validated) {
            return;
        }
        List<String> violations = violations();
        if (!violations.isEmpty()) {
            throw new IllegalStateException("[backup quiesce] 起動を中止します: " + String.join("。", violations)
                    + "（deploy-runbook §1.0 の backup の書込み静止）");
        }
        validated = true;
    }

    /** 検査を通ったか（試験用）。 */
    boolean isValidated() {
        return validated;
    }

    List<String> violations() {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        List<String> violations = new ArrayList<>();
        if (prod && !properties.isEnabled()) {
            String ticket = properties.getDisabledTicket();
            if (ticket == null || ticket.isEmpty()) {
                violations.add("prod で BACKUP_QUIESCE_ENABLED=false（停止口）にするには、承認済みの変更 ticket を "
                        + "BACKUP_QUIESCE_DISABLED_TICKET に設定してください");
            } else if (!TICKET.matcher(ticket).matches()) {
                violations.add("BACKUP_QUIESCE_DISABLED_TICKET の形式が違います（英大文字で始まる英大文字・数字の2文字以上、"
                        + "「-」、数字。例 CHG-1234）");
            }
        }
        if (prod || properties.isEnabled()) {
            List<String> readOnly = readOnlyPropagationViolations(environment);
            if (!readOnly.isEmpty()) {
                violations.add("業務の DataSource の JDBC 設定で readOnlyPropagatesToServer が有効であることを確かめられません: "
                        + readOnly + "。この属性が無効だと静止中の読取り専用（最後の防線）が DB に届かないため、外してください");
            }
        }
        return violations;
    }

    /**
     * {@code readOnlyPropagatesToServer} を無効にしている（または確かめられない）property の名前。印は
     * {@code 名前}（URL の query・data-source-properties の key に書いた無効の値。URL は展開後の属性で見る）、
     * {@code 名前(unparsable)}（Connector/J の URL なのに属性を確かめられない・単一の接続でない）。値は返さない。
     */
    static List<String> readOnlyPropagationViolations(Environment environment) {
        Map<String, String> dataSourceProperties = Binder.get(environment)
                .bind(DATA_SOURCE_PROPERTIES, Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        // Hikari は jdbcUrl での接続時に data-source-properties をそのまま driver の Properties として渡す
        // （ImportJdbcReconnectGuard と同じ入力。url 自身は除く）。
        Properties driverInfo = new Properties();
        dataSourceProperties.forEach((name, value) -> {
            if (name != null && value != null && !"url".equalsIgnoreCase(name)) driverInfo.setProperty(name, value);
        });
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> property : dataSourceProperties.entrySet()) {
            if (property.getKey() != null && READ_ONLY_PROPAGATES.equalsIgnoreCase(property.getKey())
                    && !enabled(property.getValue())) {
                violations.add(DATA_SOURCE_PROPERTIES + "." + property.getKey());
            }
        }
        for (String key : URL_PROPERTIES) {
            String url = environment.getProperty(key);
            if (url != null) {
                urlViolation(key, url, driverInfo).ifPresent(violations::add);
            }
        }
        for (Map.Entry<String, String> property : dataSourceProperties.entrySet()) {
            if ("url".equalsIgnoreCase(property.getKey()) && property.getValue() != null) {
                urlViolation(DATA_SOURCE_PROPERTIES + "." + property.getKey(), property.getValue(), driverInfo)
                        .ifPresent(violations::add);
            }
        }
        return List.copyOf(violations);
    }

    private static java.util.Optional<String> urlViolation(String key, String url, Properties driverInfo) {
        ConnectorJUrl kind = ConnectorJUrl.inspect(url, driverInfo);
        if (kind == ConnectorJUrl.NOT_CONNECTOR_J) {
            return java.util.Optional.empty();
        }
        if (kind != ConnectorJUrl.SINGLE_HOST) {
            // 展開後の属性を DNS の照会なしに確かめられない（複数 host の URL は ImportJdbcReconnectGuard も拒否する）
            return java.util.Optional.of(key + "(unparsable)");
        }
        ConnectorJUrl.Effective effective = ConnectorJUrl.effectiveProperties(url, driverInfo);
        if (!effective.connectorJ()) {
            return java.util.Optional.empty();
        }
        if (effective.properties() == null) {
            return java.util.Optional.of(key + "(unparsable)");
        }
        for (Map.Entry<String, String> property : effective.properties().entrySet()) {
            if (property.getKey() != null && READ_ONLY_PROPAGATES.equalsIgnoreCase(property.getKey())
                    && !enabled(property.getValue())) {
                return java.util.Optional.of(key);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Connector/J の boolean の属性が有効か。Connector/J は {@code true}・{@code yes} を有効、{@code false}・{@code no} を無効とし、
     * 他の値では接続に失敗する。有効と言える値（前後の空白と大文字小文字は問わない）以外は全て無効とみなす（fail-closed）。
     */
    static boolean enabled(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "yes".equals(normalized);
    }
}
