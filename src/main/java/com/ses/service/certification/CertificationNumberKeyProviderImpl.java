package com.ses.service.certification;

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
 * 資格番号 AES-256-GCM キー提供。Compliance gate とは別の設定名前空間を使用する。
 * prod: 未設定は fail-fast。dev/test: テスト用キーにフォールバック（prod では不可）。
 */
@Component
public class CertificationNumberKeyProviderImpl implements CertificationNumberKeyProvider, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(CertificationNumberKeyProviderImpl.class);

    private static final Pattern KEY_VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final String DEFAULT_TEST_KEY_VERSION = "v1";
    private static final String DEFAULT_TEST_KEY_BASE64URL = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("certification-test-key-32bytes!!".getBytes(StandardCharsets.UTF_8));

    private final Environment environment;
    private String currentKeyVersion;
    private final Map<String, byte[]> keyMap = new HashMap<>();

    public CertificationNumberKeyProviderImpl(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        init();
    }

    void init() {
        String configuredVersion = environment.getProperty("certification.number-crypto.current-key-version");
        boolean isProd = isProdProfile();

        if (StringUtils.hasText(configuredVersion)) {
            validateKeyVersion(configuredVersion);
            currentKeyVersion = configuredVersion;
            loadKey(configuredVersion);
        }

        if (isProd) {
            if (!StringUtils.hasText(currentKeyVersion) || !keyMap.containsKey(currentKeyVersion)) {
                throw new IllegalStateException(
                        "Fail-fast: missing certification.number-crypto configuration in prod profile");
            }
        } else if (!StringUtils.hasText(currentKeyVersion) || !keyMap.containsKey(currentKeyVersion)) {
            currentKeyVersion = DEFAULT_TEST_KEY_VERSION;
            keyMap.put(DEFAULT_TEST_KEY_VERSION, decodeKey(DEFAULT_TEST_KEY_BASE64URL));
            log.info("Using default test key for certification number crypto (non-prod)");
        }
    }

    @Override
    public String getCurrentKeyVersion() {
        return currentKeyVersion;
    }

    @Override
    public byte[] getKey(String keyVersion) {
        if (!StringUtils.hasText(keyVersion)) {
            throw new IllegalArgumentException("Key version cannot be empty");
        }
        validateKeyVersion(keyVersion);
        byte[] key = keyMap.get(keyVersion);
        if (key == null) {
            loadKey(keyVersion);
            key = keyMap.get(keyVersion);
        }
        if (key == null) {
            throw new IllegalArgumentException("Unknown certification number key version: " + keyVersion);
        }
        return Arrays.copyOf(key, key.length);
    }

    private void loadKey(String keyVersion) {
        String rawKey = environment.getProperty("certification.number-crypto.keys." + keyVersion);
        if (StringUtils.hasText(rawKey)) {
            keyMap.put(keyVersion, decodeKey(rawKey));
        }
    }

    private boolean isProdProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("test".equalsIgnoreCase(profile)) {
                return false;
            }
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
        if (rawKey.contains("=") || rawKey.contains(" ") || rawKey.contains("\n")) {
            throw new IllegalArgumentException("Key must be unpadded base64url");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(rawKey.trim());
        if (decoded.length != 32) {
            throw new IllegalArgumentException("Decoded key must be 32 bytes");
        }
        return decoded;
    }
}
