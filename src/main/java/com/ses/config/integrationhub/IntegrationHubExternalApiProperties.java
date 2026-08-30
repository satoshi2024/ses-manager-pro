package com.ses.config.integrationhub;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** NF-05公開APIの起動境界。暗黙の安全な既定値をコード側へ持ち込まない。 */
@Data
@Component
@ConfigurationProperties(prefix = "integration.hub")
public class IntegrationHubExternalApiProperties {
    @Autowired
    private Environment environment;
    private PublicApi publicApi = new PublicApi();
    private ExternalTransport externalTransport = new ExternalTransport();
    private Provider provider = new Provider();
    private Security security = new Security();

    @PostConstruct
    void validateBoundaries() {
        if (publicApi.enabled == null || externalTransport.enabled == null || provider.mode == null) {
            throw new IllegalStateException("integration.hubのpublic-api/external-transport/provider.modeは明示設定が必要です");
        }
        if (publicApi.enabled && (publicApi.publicIdKey == null
                || publicApi.publicIdKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)) {
            throw new IllegalStateException("public-api.enabled=trueには32 byte以上のpublic-id-keyが必要です");
        }
        if (environment != null && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))
                && (publicApi.enabled || externalTransport.enabled || provider.mode != ProviderMode.MOCK)) {
            throw new IllegalStateException("productionの公開APIと外部transportはoff、provider.modeはMOCK固定です");
        }
        if (security.allowedLoopbackPorts.stream().anyMatch(port -> port < 1 || port > 65535)) {
            throw new IllegalStateException("loopback許可portが不正です");
        }
        if (publicApi.readSnapshotPurgeBatchSize < 1 || publicApi.readSnapshotPurgeBatchSize > 32
                || publicApi.readSnapshotPurgeFixedDelayMs < 1000
                || publicApi.readSnapshotPurgeFixedDelayMs > 86_400_000L) {
            throw new IllegalStateException("read snapshot purge設定が不正です");
        }
        for (String proxy : security.trustedProxies) {
            if (!StringUtils.hasText(proxy)) {
                throw new IllegalStateException("trusted proxyに空要素は指定できません");
            }
        }
    }

    @Data
    public static class PublicApi {
        /** nullを許し、設定バインド漏れを起動時に検知する。 */
        private Boolean enabled;
        /** 公開IDとcursorの用途分離に使う環境注入鍵。平文IDを外部契約へ出さない。 */
        private String publicIdKey;
        /** cursorの有効秒数。短い上限で失効させる。 */
        private int cursorTtlSeconds = 300;
        /** 公開requestとは独立したsnapshot purgeの1回上限。 */
        private int readSnapshotPurgeBatchSize = 32;
        /** snapshot purge schedulerの実行間隔。 */
        private long readSnapshotPurgeFixedDelayMs = 60_000L;
    }

    @Data
    public static class ExternalTransport {
        private Boolean enabled;
    }

    @Data
    public static class Provider {
        private ProviderMode mode;
    }

    public enum ProviderMode {
        MOCK, STUB, LOOPBACK
    }

    @Data
    public static class Security {
        /** Forwarded/X-Forwarded-Forを信頼できるproxyのCIDR。空なら転送ヘッダーを拒否する。 */
        private List<String> trustedProxies = new ArrayList<>();
        /** LOOPBACK接続先で使用できるportのallow-list。 */
        private List<Integer> allowedLoopbackPorts = new ArrayList<>();
    }
}
