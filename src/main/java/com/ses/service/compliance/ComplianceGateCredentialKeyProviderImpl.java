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
 * Compliance gate credential key provider implementation (§6.5).
 * Configured via `compliance.gate.credential-crypto.current-key-version` and `compliance.gate.credential-crypto.keys.<version>`.
 * In prod profile: fail-fast on startup if key config is missing or invalid.
 * In dev/test profiles: fallback to default test key version "v1" if unconfigured.
 */
@Component
public class ComplianceGateCredentialKeyProviderImpl implements ComplianceGateCredentialKeyProvider, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ComplianceGateCredentialKeyProviderImpl.class);

    private static final Pattern KEY_VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final String DEFAULT_TEST_KEY_VERSION = "v1";
    // 32-byte default key encoded in unpadded base64url: "01234567890123456789012345678901"
    private static final String DEFAULT_TEST_KEY_BASE64URL = Base64.getUrlEncoder().withoutPadding().encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));

    private final Environment environment;

    public ComplianceGateCredentialKeyProviderImpl(Environment environment) {
        this.environment = environment;
    }

    private String currentKeyVersion;
    private final Map<String, byte[]> keyMap = new HashMap<>();

    @Override
    public void afterPropertiesSet() {
        init();
    }

    public void init() {
        String configuredVersion = environment.getProperty("compliance.gate.credential-crypto.current-key-version");
        boolean isProd = isProdProfile();

        if (StringUtils.hasText(configuredVersion)) {
            validateKeyVersion(configuredVersion);
            this.currentKeyVersion = configuredVersion;
        }

        // Search for keys under compliance.gate.credential-crypto.keys.<version>
        // Note: Spring Environment allows loading properties dynamically
        if (this.currentKeyVersion != null) {
            String rawKey = environment.getProperty("compliance.gate.credential-crypto.keys." + this.currentKeyVersion);
            if (StringUtils.hasText(rawKey)) {
                keyMap.put(this.currentKeyVersion, decodeKey(rawKey));
            }
        }

        if (isProd) {
            if (!StringUtils.hasText(this.currentKeyVersion) || !keyMap.containsKey(this.currentKeyVersion)) {
                throw new IllegalStateException("Fail-fast: missing or invalid compliance.gate.credential-crypto configuration in prod profile");
            }
        } else {
            // Dev/Test profile fallback
            if (!StringUtils.hasText(this.currentKeyVersion) || !keyMap.containsKey(this.currentKeyVersion)) {
                this.currentKeyVersion = DEFAULT_TEST_KEY_VERSION;
                keyMap.put(DEFAULT_TEST_KEY_VERSION, decodeKey(DEFAULT_TEST_KEY_BASE64URL));
                log.info("Using default test key for compliance gate credential crypto (dev/test mode)");
            }
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
            // Attempt to resolve key from environment if dynamically added
            String rawKey = environment.getProperty("compliance.gate.credential-crypto.keys." + keyVersion);
            if (StringUtils.hasText(rawKey)) {
                key = decodeKey(rawKey);
                keyMap.put(keyVersion, key);
            }
        }
        if (key == null) {
            throw new IllegalArgumentException("Unknown or unconfigured credential key version: " + keyVersion);
        }
        return Arrays.copyOf(key, key.length);
    }

    private boolean isProdProfile() {
        String[] activeProfiles = environment.getActiveProfiles();
        for (String profile : activeProfiles) {
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
        // Reject standard Base64 padding '=' or spaces
        if (rawKey.contains("=") || rawKey.contains(" ") || rawKey.contains("\n") || rawKey.contains("\r")) {
            throw new IllegalArgumentException("Key must be unpadded base64url without spaces or padding '='");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(rawKey.trim());
            if (decoded.length != 32) {
                throw new IllegalArgumentException("Decoded key must be exactly 32 bytes, got: " + decoded.length);
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64url key format: " + e.getMessage(), e);
        }
    }
}
