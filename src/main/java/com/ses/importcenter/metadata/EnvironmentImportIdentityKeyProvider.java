package com.ses.importcenter.metadata;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.regex.Pattern;

/** import identity専用鍵namespace。未設定・不明versionは操作時に拒否する。 */
@Component
public final class EnvironmentImportIdentityKeyProvider implements ImportIdentityKeyProvider {
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private final Environment environment;

    public EnvironmentImportIdentityKeyProvider(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String currentLookupKeyVersion() {
        return requireVersion("import-center.identity-crypto.lookup-current-key-version");
    }

    @Override
    public byte[] lookupKey(String keyVersion) {
        return requireKey("import-center.identity-crypto.lookup.keys." + validateVersion(keyVersion));
    }

    @Override
    public String currentEncryptionKeyVersion() {
        return requireVersion("import-center.identity-crypto.encryption-current-key-version");
    }

    @Override
    public byte[] encryptionKey(String keyVersion) {
        return requireKey("import-center.identity-crypto.encryption.keys." + validateVersion(keyVersion));
    }

    private String requireVersion(String property) {
        String value = environment.getProperty(property);
        if (value == null || !VERSION.matcher(value).matches()) {
            throw new IllegalStateException("取込identity鍵versionを解決できません");
        }
        return value;
    }

    private byte[] requireKey(String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank() || value.contains("=") || value.contains(" ")
                || value.contains("\n") || value.contains("\r")) {
            throw new IllegalStateException("取込identity鍵を解決できません");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length != 32) {
                java.util.Arrays.fill(decoded, (byte) 0);
                throw new IllegalStateException("取込identity鍵の長さが不正です");
            }
            return decoded;
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("取込identity鍵の形式が不正です");
        }
    }

    private String validateVersion(String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("取込identity鍵versionの形式が不正です");
        }
        return version;
    }
}
