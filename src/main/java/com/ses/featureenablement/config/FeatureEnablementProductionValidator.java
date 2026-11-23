package com.ses.featureenablement.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.UnitDefinition;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 機能の開放の起動時検査（feature-enablement-console design §10。FE-RQ-007・016）。port を開く前
 * （{@link SmartInitializingSingleton}）に1回だけ検査し、違反があれば {@link IllegalStateException} で起動を中止する（設定の誤りで、
 * 機能の条件ではない）。message には変数名と違反の種類だけを出し、値を出さない。
 *
 * <ul>
 *   <li><b>総スイッチ</b>（全 profile）: {@code FEATURE_ENABLEMENT_ALLOWED}（{@code app.feature-enablement.allowed}）の原文の文字列が
 *       {@code true} か {@code false} でなければ止める（Spring の bind が受け付ける {@code yes}・{@code on}・{@code 1} 等を拒む。
 *       review P3-13）。</li>
 *   <li><b>源</b>（prod）: {@code app.feature-enablement.source} が {@code database} でなければ止める。</li>
 *   <li><b>運用値の上書き</b>（prod）: 環境変数・system property の名前を {@code validate-task-definition.ps1} の {@code NameKey}
 *       （大文字にして {@code _}・{@code .}・{@code -} を除く。ここでは英数字以外を全て除く）で正規化し、{@code APPFEATUREENABLEMENT}
 *       で始まる名前と、綴りが完全に {@code FEATURE_ENABLEMENT_ALLOWED} である名前以外の {@code FEATUREENABLEMENT} で始まる名前があれば止める
 *       （{@code SPRING_APPLICATION_JSON}・{@code spring.application.json} の {@code app.feature-enablement} と
 *       {@code FEATURE_ENABLEMENT_*} は総スイッチを含めて止める）。D-5: 配備の設定は鍵と総スイッチだけで、{@code max-staleness} を
 *       大きくして fail-closed を外すことも防ぐ。{@code evaluation-initial-delay}・{@code sign-off-purge-cron} も同じ（review rb-fe-a1
 *       P3-5）。</li>
 *   <li><b>旧い変数</b>（prod）: 目録で DB 管理にした開放単位の旧い開閉の変数（{@link Feature#environmentVariable()}・
 *       {@link Feature#propertyName()} の全ての綴り）が環境変数・system property・{@code SPRING_APPLICATION_JSON} にあれば止める
 *       （§18 J-12。無視すると運用者が開閉を誤解する）。段階 A〜D の間は DB 管理の印のある開放単位だけ、段階 E で全てを拒否する。
 *       開閉以外の旧い変数（署名・設定へ移した承認の値。{@link #LEGACY_SETTING_RULES}）も、DB 管理の印のある開放単位の分を同じく拒否する。</li>
 * </ul>
 *
 * <p>prod の判定は {@code Environment.acceptsProfiles(Profiles.of("prod"))}（CLAUDE.md。{@code @ActiveProfiles("prod")} の試験でも
 * 走る）。</p>
 */
@Component
public class FeatureEnablementProductionValidator implements SmartInitializingSingleton {

    /** 総スイッチの property と環境変数。 */
    public static final String ALLOWED_PROPERTY = "app.feature-enablement.allowed";
    public static final String ALLOWED_VARIABLE = "FEATURE_ENABLEMENT_ALLOWED";

    static final String OPERATIONAL_PREFIX = "APPFEATUREENABLEMENT";
    static final String SHORT_PREFIX = "FEATUREENABLEMENT";
    static final String ALLOWED_KEY = "FEATUREENABLEMENTALLOWED";
    static final String JSON_VARIABLE = "SPRING_APPLICATION_JSON";
    static final String JSON_PROPERTY = "spring.application.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 開閉以外の旧い変数の規則（値を system の中の署名・設定へ移した承認の値。D-5）。段階 B・C・D がそれぞれ足した拒否（B の
     * 開放単位ごとの名前の前方一致・C の開放単位の集合と接頭辞・D の NF05 の名前の完全一致）を、この1つの表にまとめた（段階の整合 M1）。
     * 各行は開放単位の集合と名前（環境変数と property の綴り）で、集合のどれかが DB 管理なら、正規化した名前がどれかの名前（正規化）で
     * 始まる変数を拒否する（前方一致のため list の添字の付いた綴りも拾う。review rb-fe-c P3-8）。
     * 配備の設定に残る運用値（{@code PRIVACY_DSAR_DEADLINE_WARNING_DAYS}・Journal・trust・行参照の鍵・NF05 の許可の一覧等）は、どの行の
     * 名前でも始まらないように行の名前を選ぶ（運用値に当たると prod はその運用値で起動しなくなる。{@code application*.yml} の
     * placeholder のうち規則に当たるものが移した値だけであることを {@code FeatureEnablementProductionValidatorTest} が確かめる）。
     * <ul>
     *   <li>FE-B01: NF01 二者確認の Privacy 署名の参照（署名 {@code NF01_DUAL_PRIVACY} へ移した）。</li>
     *   <li>FE-B02: NF06 の admission の承認（{@code IMPORT_ADMISSION_*} の6項目。署名 {@code NF06_ADMISSION} へ移した）。行参照の鍵
     *       {@code IMPORT_ROW_REFERENCE_HMAC_KEY} は鍵として配備の設定に残る。</li>
     *   <li>FE-B03: NF02 顧客ポータルの公開範囲（承認で変える設定 {@code nf02-customer-portal.visible-since} へ移した。空の値も拒否する）。</li>
     *   <li>FE-C05: NF07 の {@code PRIVACY_APPROVAL_*}（決定 ID の束縛・D-02・D-03・D-05 は署名の組と署名へ、失効の警告の日数は system 設定
     *       {@code feature-enablement.expiry-warning-days} へ移した。review rb-fe-c P3-4）と、D-06 の {@code PRIVACY_DSAR_DUE_DAYS}・
     *       {@code PRIVACY_DSAR_PACKAGE_MAX_AGE}（D-06 の構造化された値へ移した。§18 J-15）。NF07 の2件のどちらかが DB 管理なら拒否する。</li>
     *   <li>FE-D05: NF05 の決定 ID と approval digest（確認済みの署名から読む）。許可する送信元・送信先の一覧
     *       （{@code allowed-client-cidrs}・{@code allowed-destinations}）は digest の内容で配備の設定に残すため含めない。</li>
     * </ul>
     */
    static final List<LegacySettingRule> LEGACY_SETTING_RULES = List.of(
            new LegacySettingRule(Set.of(Feature.NF01_DUAL_CONFIRMATION), List.of(
                    "FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF",
                    "app.features.nf01-dual-confirmation.privacy-sign-off-ref")),
            new LegacySettingRule(Set.of(Feature.NF06), List.of("IMPORT_ADMISSION_", "app.import-center.admission.")),
            new LegacySettingRule(Set.of(Feature.NF02_CUSTOMER_PORTAL), List.of(
                    "FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE", "app.features.nf02-customer-portal.visible-since")),
            new LegacySettingRule(Set.of(Feature.NF07_RETENTION, Feature.NF07_DSAR), List.of(
                    "PRIVACY_APPROVAL_", "PRIVACY_DSAR_DUE_DAYS", "PRIVACY_DSAR_PACKAGE_MAX_AGE",
                    "privacy.approval.", "privacy.dsar.due-days", "privacy.dsar.package-max-age")),
            new LegacySettingRule(Set.of(Feature.NF05_INBOUND), List.of(
                    "INTEGRATION_HUB_INBOUND_DECISION_ID", "INTEGRATION_HUB_INBOUND_APPROVAL_DIGEST",
                    "integration.hub.public-api.approval.decision-id",
                    "integration.hub.public-api.approval.approval-digest")),
            new LegacySettingRule(Set.of(Feature.NF05_OUTBOUND), List.of(
                    "INTEGRATION_HUB_OUTBOUND_DECISION_ID", "INTEGRATION_HUB_OUTBOUND_APPROVAL_DIGEST",
                    "integration.hub.external-transport.approval.decision-id",
                    "integration.hub.external-transport.approval.approval-digest")));

    /** 開閉以外の旧い変数の規則の1行（{@code units} のどれかが DB 管理なら、正規化した {@code names} のどれかで始まる変数を拒否する）。 */
    record LegacySettingRule(Set<Feature> units, List<String> names) {
    }

    private final Environment environment;
    private final FeatureEnablementProperties properties;
    private final FeatureEnablementCatalog catalog;
    private final Supplier<Map<String, String>> systemEnvironment;
    private final Supplier<Properties> systemProperties;
    private volatile boolean validated;

    @org.springframework.beans.factory.annotation.Autowired
    public FeatureEnablementProductionValidator(Environment environment, FeatureEnablementProperties properties,
                                                FeatureEnablementCatalog catalog) {
        this(environment, properties, catalog, System::getenv, System::getProperties);
    }

    /** 試験用（環境変数と system property を差し替える）。 */
    FeatureEnablementProductionValidator(Environment environment, FeatureEnablementProperties properties,
                                         FeatureEnablementCatalog catalog, Supplier<Map<String, String>> systemEnvironment,
                                         Supplier<Properties> systemProperties) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.systemEnvironment = Objects.requireNonNull(systemEnvironment, "systemEnvironment");
        this.systemProperties = Objects.requireNonNull(systemProperties, "systemProperties");
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
            throw new IllegalStateException("[機能の開放] 起動を中止します: " + String.join("。", violations)
                    + "（deploy-runbook §1.0 の機能の開放）");
        }
        validated = true;
    }

    /** 違反の一覧（変数名と種類だけ。値を含めない）。 */
    List<String> violations() {
        List<String> violations = new ArrayList<>();
        String allowed = environment.getProperty(ALLOWED_PROPERTY);
        if (allowed != null && !"true".equals(allowed) && !"false".equals(allowed)) {
            violations.add(ALLOWED_VARIABLE + "（" + ALLOWED_PROPERTY + "）は true か false で書いてください（yes・on・1 等は"
                    + "受け付けません）");
        }
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return violations;
        }
        if (!properties.databaseSource()) {
            violations.add("prod の app.feature-enablement.source は database だけです（機能の開閉は system の中で行います）");
        }
        Map<String, String> names = configuredNames();
        Set<String> overrides = new TreeSet<>();
        Set<String> legacy = new TreeSet<>();
        Set<String> legacyKeys = legacyKeys();
        Set<String> legacySettingPrefixes = legacySettingPrefixes();
        names.forEach((name, origin) -> {
            String key = nameKey(name);
            boolean json = origin.equals(JSON_VARIABLE);
            // 総スイッチは環境変数・system property とも綴りが完全に一致する名前だけを受ける（placeholder は他の綴りを読まないため、
            // feature_enablement_allowed=false 等は効かずに全開で起動する。照合の ps1 と同じ。実装 review rb-fe-a2 P3-1）
            if (key.startsWith(OPERATIONAL_PREFIX)
                    || (key.startsWith(SHORT_PREFIX) && (json || !name.equals(ALLOWED_VARIABLE)))) {
                overrides.add(describe(name, origin));
            }
            if (legacyKeys.contains(key) || legacySettingPrefixes.stream().anyMatch(key::startsWith)) {
                legacy.add(describe(name, origin));
            }
        });
        if (!overrides.isEmpty()) {
            violations.add("prod の配備の設定で機能の開放の運用値を上書きしないでください（配備の設定に置けるのは "
                    + ALLOWED_VARIABLE + " だけです）: " + String.join(",", overrides));
        }
        if (!legacy.isEmpty()) {
            violations.add("system の中で開閉する機能の旧い変数を設定しないでください（画面の申請と二人の承認で開きます）: "
                    + String.join(",", legacy));
        }
        return violations;
    }

    /** DB 管理の印のある開放単位の旧い開閉の変数（正規化した名前）。 */
    Set<String> legacyKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (UnitDefinition unit : catalog.units()) {
            if (unit.databaseManaged()) {
                keys.add(nameKey(unit.feature().environmentVariable()));
                keys.add(nameKey(unit.feature().propertyName()));
            }
        }
        return keys;
    }

    /** DB 管理の印のある開放単位の開閉以外の旧い変数（{@link #LEGACY_SETTING_RULES}。正規化した名前。前方一致で比べる）。 */
    Set<String> legacySettingPrefixes() {
        Set<String> prefixes = new LinkedHashSet<>();
        for (LegacySettingRule rule : LEGACY_SETTING_RULES) {
            if (rule.units().stream().anyMatch(catalog::databaseManaged)) {
                rule.names().forEach(name -> prefixes.add(nameKey(name)));
            }
        }
        return prefixes;
    }

    /** 名前 → 出処（環境変数・system property・JSON の key）。JSON を読めなければその旨を出処の名前で返す。 */
    private Map<String, String> configuredNames() {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> env = systemEnvironment.get();
        if (env != null) {
            env.keySet().forEach(name -> names.putIfAbsent(name, "env"));
        }
        Properties props = systemProperties.get();
        if (props != null) {
            for (String name : props.stringPropertyNames()) {
                names.putIfAbsent(name, "system-property");
            }
        }
        List<String> jsonSources = new ArrayList<>();
        if (env != null) {
            env.forEach((name, value) -> {
                if (nameKey(name).equals(nameKey(JSON_VARIABLE)) && value != null) {
                    jsonSources.add(value);
                }
            });
        }
        if (props != null && props.getProperty(JSON_PROPERTY) != null) {
            jsonSources.add(props.getProperty(JSON_PROPERTY));
        }
        for (String json : jsonSources) {
            for (String key : jsonKeys(json)) {
                names.putIfAbsent(key, JSON_VARIABLE);
            }
        }
        return names;
    }

    /** JSON の葉の key（{@code a.b.c}）。読めなければ空（Spring 自身が起動の時に拒否する）。 */
    static List<String> jsonKeys(String json) {
        List<String> keys = new ArrayList<>();
        try {
            collect(JSON.readTree(json), "", keys);
        } catch (Exception e) {
            return List.of();
        }
        return keys;
    }

    private static void collect(JsonNode node, String prefix, List<String> keys) {
        if (node != null && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                collect(field.getValue(), prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey(), keys);
            }
        } else if (!prefix.isEmpty()) {
            keys.add(prefix);
        }
    }

    /** {@code validate-task-definition.ps1} の {@code NameKey} と同じ向きの正規化（大文字にし、英数字以外を除く）。 */
    static String nameKey(String name) {
        return name == null ? "" : name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String describe(String name, String origin) {
        return origin.equals(JSON_VARIABLE) ? JSON_VARIABLE + "[" + name + "]" : name;
    }
}
