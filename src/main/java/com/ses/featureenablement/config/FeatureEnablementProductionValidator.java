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
 *       で始まる名前と、{@code FEATUREENABLEMENTALLOWED} 以外の {@code FEATUREENABLEMENT} で始まる名前があれば止める
 *       （{@code SPRING_APPLICATION_JSON}・{@code spring.application.json} の {@code app.feature-enablement} と
 *       {@code FEATURE_ENABLEMENT_*} は総スイッチを含めて止める）。D-5: 配備の設定は鍵と総スイッチだけで、{@code max-staleness} を
 *       大きくして fail-closed を外すことも防ぐ。{@code evaluation-initial-delay}・{@code sign-off-purge-cron} も同じ（review rb-fe-a1
 *       P3-5）。</li>
 *   <li><b>旧い変数</b>（prod）: 目録で DB 管理にした開放単位の旧い開閉の変数（{@link Feature#environmentVariable()}・
 *       {@link Feature#propertyName()} の全ての綴り）が環境変数・system property・{@code SPRING_APPLICATION_JSON} にあれば止める
 *       （§18 J-12。無視すると運用者が開閉を誤解する）。段階 A〜D の間は DB 管理の印のある開放単位だけ、段階 E で全てを拒否する。
 *       開閉以外の旧い変数（署名の値・設定。{@link #LEGACY_SETTING_NAMES}）も、DB 管理の印のある開放単位の分を同じく拒否する。</li>
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
     * 開閉以外の旧い変数（署名の値・設定。環境変数と property の綴り。正規化した名前の前方一致で、list の添字の付いた綴りも拾う）。
     * DB 管理の印のある開放単位の分だけ拒否する（値は system の中の署名・設定へ移した。D-5。段階 B〜D が開放単位ごとに足す）。
     */
    static final Map<Feature, List<String>> LEGACY_SETTING_NAMES;

    static {
        Map<Feature, List<String>> names = new java.util.EnumMap<>(Feature.class);
        // FE-B01: NF01 二者確認の Privacy 署名の参照（署名 NF01_DUAL_PRIVACY へ移した）
        names.put(Feature.NF01_DUAL_CONFIRMATION, List.of("FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF",
                "app.features.nf01-dual-confirmation.privacy-sign-off-ref"));
        LEGACY_SETTING_NAMES = java.util.Collections.unmodifiableMap(names);
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
            if (key.startsWith(OPERATIONAL_PREFIX)
                    || (key.startsWith(SHORT_PREFIX) && (json || !key.equals(ALLOWED_KEY)))) {
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

    /** DB 管理の印のある開放単位の開閉以外の旧い変数（正規化した名前。前方一致で比べる）。 */
    Set<String> legacySettingPrefixes() {
        Set<String> prefixes = new LinkedHashSet<>();
        for (UnitDefinition unit : catalog.units()) {
            if (unit.databaseManaged()) {
                for (String name : LEGACY_SETTING_NAMES.getOrDefault(unit.feature(), List.of())) {
                    prefixes.add(nameKey(name));
                }
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
