package com.ses.service.integrationhub.crypto;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * integration.hub.crypto.* をdeployment secret storeから解決するkeyring。
 * 未設定時に開発用鍵へfallbackせず、利用時にfail closedする。
 */
@Component
public class EnvironmentIntegrationHubKeyring implements IntegrationHubKeyring {
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private final Environment environment;
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    public EnvironmentIntegrationHubKeyring(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String currentKeyVersion() {
        String version = environment.getProperty("integration.hub.crypto.current-key-version");
        if (!StringUtils.hasText(version) || !VERSION_PATTERN.matcher(version).matches()) {
            throw new IllegalStateException("integration hub crypto key version is not configured");
        }
        return version;
    }

    @Override
    public byte[] key(String keyVersion) {
        if (!StringUtils.hasText(keyVersion) || !VERSION_PATTERN.matcher(keyVersion).matches()) {
            throw new IllegalArgumentException("invalid crypto key version");
        }
        byte[] cached = cache.get(keyVersion);
        if (cached != null) {
            return Arrays.copyOf(cached, cached.length);
        }
        String encoded = environment.getProperty("integration.hub.crypto.keys." + keyVersion);
        if (!StringUtils.hasText(encoded) || encoded.contains("=") || encoded.matches(".*[\\s].*")) {
            throw new IllegalStateException("integration hub crypto key is not configured");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            if (decoded.length != 32) {
                throw new IllegalArgumentException("crypto key must be 32 bytes");
            }
            cache.putIfAbsent(keyVersion, decoded);
            return Arrays.copyOf(decoded, decoded.length);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("integration hub crypto key is invalid", e);
        }
    }
}
