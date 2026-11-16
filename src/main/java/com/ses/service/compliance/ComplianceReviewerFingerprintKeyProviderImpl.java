package com.ses.service.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * R23-P1-01 §9: reviewer fingerprint key provider implementation（P1-01b対応）。
 *
 * <p>tenant別専用key namespace（§9 HMAC契約）:
 * <ul>
 *   <li>config: {@code compliance.gate.fingerprint-keys.{tenantId}.current-key-version} と
 *       {@code compliance.gate.fingerprint-keys.{tenantId}.keys.{version}}</li>
 *   <li>prod profile: 起動時fail-fast（必須tenantのkey設定欠損・不正で起動しない・ソース内蔵secretなし）</li>
 *   <li>dev/test profile: tenant未設定時のみ既定テスト鍵へfallback（prodはfallbackしない）</li>
 *   <li>key rotation: version別に解決・古いversionも参照可能・currentは1つ</li>
 *   <li>未知key version・tenant設定欠損はfail-closed（nullを返さず例外）</li>
 *   <li>tenantの列挙（FINAL-FIX）: 名前に {@code compliance.gate.fingerprint-keys.} を含むMap型property source（設定file・
 *       {@code SPRING_APPLICATION_JSON}・試験のproperty）と、tenant一覧 {@code compliance.gate.fingerprint-key-tenants}
 *       （カンマ区切り。OS環境変数 {@code COMPLIANCE_GATE_FINGERPRINT_KEY_TENANTS}）の和集合。OS環境変数の名前は
 *       {@code COMPLIANCE_GATE_FINGERPRINT_KEYS_...} の形で上の接頭辞に一致せず、tenant IDも名前から復元できない
 *       （{@code .}・{@code -} が {@code _} になり大文字化される）ため、環境変数だけで渡すときは一覧が要る。一覧のtenantの
 *       version・鍵は {@code Environment#getProperty} の緩い名前解決で読むため、
 *       {@code COMPLIANCE_GATE_FINGERPRINT_KEYS_<TENANT>_CURRENT_KEY_VERSION}・{@code ..._KEYS_<VERSION>} で渡せる。</li>
 * </ul>
 */
@Component
public class ComplianceReviewerFingerprintKeyProviderImpl
        implements ComplianceReviewerFingerprintKeyProvider, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ComplianceReviewerFingerprintKeyProviderImpl.class);

    private static final Pattern KEY_VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final String DEFAULT_TEST_KEY_VERSION = "v1";
    // 32-byte default test key（unpadded base64url・dev/testのみ）
    private static final String DEFAULT_TEST_KEY_BASE64URL = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));

    private final Environment environment;

    /** tenantId → 現行key version。 */
    private final Map<String, String> currentVersions = new HashMap<>();
    /** tenantId → (keyVersion → key bytes)。 */
    private final Map<String, Map<String, byte[]>> keysByTenant = new HashMap<>();

    public ComplianceReviewerFingerprintKeyProviderImpl(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        init();
    }

    public void init() {
        boolean isProd = isProdProfile();
        // 設定済みtenantを列挙: compliance.gate.fingerprint-keys.<tenant>.current-key-version
        for (String tenant : configuredTenants()) {
            resolveTenant(tenant, isProd);
        }
        if (isProd && keysByTenant.isEmpty()) {
            throw new IllegalStateException(
                    "Fail-fast: missing compliance.gate.fingerprint-keys configuration in prod profile"
                            + " (OS環境変数で渡す場合は compliance.gate.fingerprint-key-tenants も設定する)");
        }
        // dev/test: 'default' tenantのみ既定テスト鍵へfallback（他tenantは未設定のまま・解決時fail-closed）
        if (!isProd && !currentVersions.containsKey("default")) {
            Map<String, byte[]> map = new HashMap<>();
            map.put(DEFAULT_TEST_KEY_VERSION, decodeKey(DEFAULT_TEST_KEY_BASE64URL));
            keysByTenant.put("default", map);
            currentVersions.put("default", DEFAULT_TEST_KEY_VERSION);
            log.info("Using default test fingerprint key for tenant 'default' (dev/test mode)");
        }
    }

    @Override
    public String getCurrentKeyVersion(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            throw new IllegalArgumentException("tenantId cannot be empty");
        }
        String version = currentVersions.get(tenantId);
        if (version == null && !isProdProfile()) {
            // dev/test: 未設定tenantは既定テスト鍵で解決（テストfixture等の利便性）
            Map<String, byte[]> map = new HashMap<>();
            map.put(DEFAULT_TEST_KEY_VERSION, decodeKey(DEFAULT_TEST_KEY_BASE64URL));
            keysByTenant.put(tenantId, map);
            currentVersions.put(tenantId, DEFAULT_TEST_KEY_VERSION);
            version = DEFAULT_TEST_KEY_VERSION;
        }
        if (version == null) {
            throw new IllegalStateException("Unknown tenant fingerprint key: " + tenantId + " (fail-closed)");
        }
        return version;
    }

    @Override
    public byte[] getKey(String tenantId, String keyVersion) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(keyVersion)) {
            throw new IllegalArgumentException("tenantId and keyVersion are required");
        }
        validateKeyVersion(keyVersion);
        Map<String, byte[]> tenantKeys = keysByTenant.get(tenantId);
        if (tenantKeys == null && !isProdProfile()) {
            tenantKeys = new HashMap<>();
            tenantKeys.put(DEFAULT_TEST_KEY_VERSION, decodeKey(DEFAULT_TEST_KEY_BASE64URL));
            keysByTenant.put(tenantId, tenantKeys);
            if (!currentVersions.containsKey(tenantId)) {
                currentVersions.put(tenantId, DEFAULT_TEST_KEY_VERSION);
            }
        }
        if (tenantKeys == null) {
            throw new IllegalArgumentException("Unknown tenant fingerprint key: " + tenantId + " (fail-closed)");
        }
        byte[] key = tenantKeys.get(keyVersion);
        if (key == null) {
            // 動的追加（secret store rotation相当）: environmentから解決を試みる
            String rawKey = environment.getProperty(
                    "compliance.gate.fingerprint-keys." + tenantId + ".keys." + keyVersion);
            if (StringUtils.hasText(rawKey)) {
                key = decodeKey(rawKey);
                tenantKeys.put(keyVersion, key);
            }
        }
        if (key == null) {
            throw new IllegalArgumentException(
                    "Unknown fingerprint key version for tenant " + tenantId + ": " + keyVersion + " (fail-closed)");
        }
        return Arrays.copyOf(key, key.length);
    }

    private void resolveTenant(String tenant, boolean isProd) {
        String configuredVersion = environment.getProperty(
                "compliance.gate.fingerprint-keys." + tenant + ".current-key-version");
        if (!StringUtils.hasText(configuredVersion)) {
            if (isProd) {
                throw new IllegalStateException(
                        "Fail-fast: missing fingerprint current-key-version for tenant " + tenant + " in prod profile");
            }
            return;
        }
        validateKeyVersion(configuredVersion);
        String rawKey = environment.getProperty(
                "compliance.gate.fingerprint-keys." + tenant + ".keys." + configuredVersion);
        if (!StringUtils.hasText(rawKey)) {
            if (isProd) {
                throw new IllegalStateException(
                        "Fail-fast: missing fingerprint key for tenant " + tenant + " version " + configuredVersion);
            }
            return;
        }
        Map<String, byte[]> map = keysByTenant.computeIfAbsent(tenant, k -> new HashMap<>());
        map.put(configuredVersion, decodeKey(rawKey));
        currentVersions.put(tenant, configuredVersion);
    }

    /** OS環境変数でも渡せるtenant一覧（カンマ区切り。クラスの説明）。 */
    static final String TENANT_LIST_PROPERTY = "compliance.gate.fingerprint-key-tenants";

    private java.util.Set<String> configuredTenants() {
        java.util.Set<String> tenants = new java.util.TreeSet<>();
        String listed = environment.getProperty(TENANT_LIST_PROPERTY);
        if (StringUtils.hasText(listed)) {
            for (String tenant : listed.split(",")) {
                if (StringUtils.hasText(tenant)) {
                    tenants.add(tenant.trim());
                }
            }
        }
        if (!(environment instanceof org.springframework.core.env.ConfigurableEnvironment)) {
            return tenants;
        }
        org.springframework.core.env.ConfigurableEnvironment configurable =
                (org.springframework.core.env.ConfigurableEnvironment) environment;
        for (org.springframework.core.env.PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source.getSource() instanceof java.util.Map)) {
                continue;
            }
            for (Object keyObj : ((java.util.Map<?, ?>) source.getSource()).keySet()) {
                String key = String.valueOf(keyObj);
                if (key.startsWith("compliance.gate.fingerprint-keys.")) {
                    String rest = key.substring("compliance.gate.fingerprint-keys.".length());
                    int dot = rest.indexOf('.');
                    if (dot > 0) {
                        tenants.add(rest.substring(0, dot));
                    }
                }
            }
        }
        return tenants;
    }

    private boolean isProdProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private void validateKeyVersion(String version) {
        if (!KEY_VERSION_PATTERN.matcher(version).matches()) {
            throw new IllegalArgumentException("Invalid key version format: " + version);
        }
    }

    private byte[] decodeKey(String rawKey) {
        if (!StringUtils.hasText(rawKey)) {
            throw new IllegalArgumentException("Raw key cannot be empty");
        }
        if (rawKey.contains("=") || rawKey.contains(" ") || rawKey.contains("\n") || rawKey.contains("\r")) {
            throw new IllegalArgumentException("Key must be unpadded base64url without spaces or padding '='");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(rawKey.trim());
            if (decoded.length != 32) {
                throw new IllegalArgumentException("Decoded fingerprint key must be exactly 32 bytes, got: " + decoded.length);
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64url fingerprint key format: " + e.getMessage(), e);
        }
    }
}
