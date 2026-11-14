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

/**
 * NF-05公開APIの起動境界。暗黙の安全な既定値をコード側へ持ち込まない。
 *
 * <p>inbound（{@code public-api}：read APIとinbound webhook）とoutbound（{@code external-transport}：
 * outbound webhook送信）は独立に有効化する（Q-12）。両方向に共通する前提（public-id-key、bound-tenant）は
 * どちらか一方が有効なら要求し、production固有の承認項目は {@link IntegrationHubProductionEnablementPolicy}
 * が方向ごとに検査する。
 */
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
    /** 現行の物理トポロジー。共有DBを暗黙に許可しない。 */
    private Topology topology = new Topology();

    @PostConstruct
    void validateBoundaries() {
        if (publicApi.enabled == null || externalTransport.enabled == null || provider.mode == null) {
            throw new IllegalStateException("integration.hubのpublic-api/external-transport/provider.modeは明示設定が必要です");
        }
        boolean anyDirection = publicApi.enabled || externalTransport.enabled;
        // public IDはread APIの応答とoutbound payloadのprimary bindingの両方で使うため、方向を問わず要求する。
        if (anyDirection && (publicApi.publicIdKey == null
                || publicApi.publicIdKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)) {
            throw new IllegalStateException(
                    "public-api.enabledまたはexternal-transport.enabled=trueには32 byte以上のpublic-id-keyが必要です");
        }
        if (anyDirection && (!"DEDICATED_DATABASE".equals(topology.mode)
                || !StringUtils.hasText(topology.boundTenantId))) {
            throw new IllegalStateException("公開API/外部transportはDEDICATED_DATABASEとbound-tenant-idの明示設定が必要です");
        }
        if (provider.approvedInboundProviders == null
                || provider.approvedInboundProviders.stream().anyMatch(value -> value == null
                || !value.matches("[A-Za-z0-9._~-]{1,100}"))) {
            throw new IllegalStateException("approved inbound providerが不正です");
        }
        if (security.allowedLoopbackPorts.stream().anyMatch(port -> port == null || port < 1 || port > 65535)) {
            throw new IllegalStateException("loopback許可portが不正です");
        }
        if (publicApi.readSnapshotPurgeBatchSize < 1 || publicApi.readSnapshotPurgeBatchSize > 32
                || publicApi.readSnapshotPurgeFixedDelayMs < 1000
                || publicApi.readSnapshotPurgeFixedDelayMs > 86_400_000L) {
            throw new IllegalStateException("read snapshot purge設定が不正です");
        }
        if (publicApi.retentionPurgeBatchSize < 1 || publicApi.retentionPurgeBatchSize > 1000
                || publicApi.retentionPurgeFixedDelayMs < 1000
                || publicApi.retentionPurgeFixedDelayMs > 86_400_000L) {
            throw new IllegalStateException("retention purge設定が不正です");
        }
        if (publicApi.inboundLeaseSeconds < 1 || publicApi.inboundLeaseSeconds > 900) {
            throw new IllegalStateException("inbound lease設定が不正です");
        }
        if (publicApi.unauthenticatedAuditMaxPerMinute < 0 || publicApi.unauthenticatedAuditMaxPerMinute > 6000) {
            throw new IllegalStateException("未認証監査の上限設定が不正です");
        }
        if (externalTransport.batchSize < 1 || externalTransport.batchSize > 32
                || externalTransport.leaseSeconds < 1 || externalTransport.leaseSeconds > 900
                || externalTransport.fixedDelayMs < 100 || externalTransport.fixedDelayMs > 86_400_000L
                || externalTransport.connectTimeoutMs < 100 || externalTransport.connectTimeoutMs > 30_000
                || externalTransport.readTimeoutMs < 100 || externalTransport.readTimeoutMs > 30_000) {
            throw new IllegalStateException("external transport設定が不正です");
        }
        long providerTimeoutSeconds = (externalTransport.connectTimeoutMs
                + (long) externalTransport.readTimeoutMs + 999L) / 1000L;
        if (externalTransport.leaseSeconds <= providerTimeoutSeconds) {
            throw new IllegalStateException("leaseはconnect/read timeout合計より長く設定してください");
        }
        if (externalTransport.enabled && provider.mode == ProviderMode.LOOPBACK
                && security.allowedLoopbackPorts.isEmpty()) {
            throw new IllegalStateException("LOOPBACK transportには明示的な許可portが必要です");
        }
        if (security.trustedProxies == null || security.forwardedHeader == null) {
            throw new IllegalStateException("trusted proxy設定が不正です");
        }
        for (String proxy : security.trustedProxies) {
            if (!StringUtils.hasText(proxy)) {
                throw new IllegalStateException("trusted proxyに空要素は指定できません");
            }
            // 構文不正のCIDRは黙って一致しなくなり、全requestをproxy自身のIPとして扱う誤設定になる。
            if (!ExternalApiCidrMatcher.isValidCidr(proxy)) {
                throw new IllegalStateException("trusted proxyはCIDR形式で指定してください");
            }
        }
        if (publicApi.approval == null || publicApi.approval.allowedClientCidrs == null
                || externalTransport.approval == null || externalTransport.approval.allowedDestinations == null) {
            throw new IllegalStateException("integration.hubの承認設定が不正です");
        }
        for (String cidr : publicApi.approval.allowedClientCidrs) {
            if (!ExternalApiCidrMatcher.isValidCidr(cidr)) {
                throw new IllegalStateException("public-api.approval.allowed-client-cidrsはCIDR形式で指定してください");
            }
        }
        if (environment != null && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))) {
            IntegrationHubProductionEnablementPolicy.requireProductionBoundary(this, environment);
        }
    }

    @Data
    public static class PublicApi {
        /** nullを許し、設定バインド漏れを起動時に検知する。 */
        private Boolean enabled;
        /** 公開IDとcursorの用途分離に使う環境注入鍵。平文IDを外部契約へ出さない。outbound payloadのbindingにも使う。 */
        private String publicIdKey;
        /** cursorの有効秒数。短い上限で失効させる。 */
        private int cursorTtlSeconds = 300;
        /** 公開requestとは独立したsnapshot purgeの1回上限。 */
        private int readSnapshotPurgeBatchSize = 32;
        /** snapshot purge schedulerの実行間隔。 */
        private long readSnapshotPurgeFixedDelayMs = 60_000L;
        /** retention/nonce purge schedulerの1回上限。inbound/outboundの両方向で共用する。 */
        private int retentionPurgeBatchSize = 100;
        /** retention/nonce purge schedulerの実行間隔。inbound/outboundの両方向で共用する。 */
        private long retentionPurgeFixedDelayMs = 60_000L;
        /** inbound webhook処理のlease秒数。outboundのexternal-transport.lease-secondsとは独立させる。 */
        private int inboundLeaseSeconds = 60;
        /**
         * 有効時に未認証rejectを監査tableへ書く1分あたりの上限（node単位）。超過分はmetricだけに集約する。
         * 0は未認証rejectを一切永続化しない。認証済みrequestと5xxはこの上限の対象外。
         */
        private int unauthenticatedAuditMaxPerMinute = 60;
        /** inbound方向のproduction承認（DG-05）。dev/testでは任意。 */
        private InboundApproval approval = new InboundApproval();
    }

    @Data
    public static class InboundApproval {
        /** 署名済みDG-05 inbound決定ID（例: DG-05-IN-20261201-01）。productionで必須。 */
        private String decisionId;
        /**
         * 承認済み取引先の送信元CIDR上限。空でなければclientごとのallowed_cidrsに加えてここにも一致する必要がある。
         * productionのinbound有効時は必須。DBのclient行だけで受信元を広げられないようにする。
         */
        private List<String> allowedClientCidrs = new ArrayList<>();
    }

    @Data
    public static class ExternalTransport {
        private Boolean enabled;
        private int batchSize = 32;
        private int leaseSeconds = 60;
        private long fixedDelayMs = 1_000L;
        private int connectTimeoutMs = 3_000;
        private int readTimeoutMs = 3_000;
        /** outbound方向のproduction承認（DG-05）。dev/testでは任意。 */
        private OutboundApproval approval = new OutboundApproval();
    }

    @Data
    public static class OutboundApproval {
        /** 署名済みDG-05 outbound決定ID（例: DG-05-OUT-20261201-01）。productionで必須。 */
        private String decisionId;
        /**
         * 承認済み送信先の {@code host:port}（小文字FQDN、IP literal不可）。productionのoutbound有効時は必須。
         * 送信時のegress照合と登録時検証は実HTTPS transport（W3-06b）で同じ一覧を使う。
         */
        private List<String> allowedDestinations = new ArrayList<>();
    }

    @Data
    public static class Provider {
        private ProviderMode mode;
        /** Ownerが承認したinbound providerだけを受け付ける。空はdefault deny。 */
        private List<String> approvedInboundProviders = new ArrayList<>();
    }

    public enum ProviderMode {
        MOCK, STUB, LOOPBACK
    }

    /** trusted proxyが送信元を追記するheader種別。指定外のheaderはclientが書ける値として読まない。 */
    public enum ForwardedHeader {
        X_FORWARDED_FOR, FORWARDED
    }

    @Data
    public static class Security {
        /** Forwarded/X-Forwarded-Forを信頼できるproxyのCIDR。空なら転送ヘッダーを拒否する。 */
        private List<String> trustedProxies = new ArrayList<>();
        /** trusted proxyが送信元を追記するheader。ALBはX_FORWARDED_FOR（append mode）。 */
        private ForwardedHeader forwardedHeader = ForwardedHeader.X_FORWARDED_FOR;
        /** LOOPBACK接続先で使用できるportのallow-list。 */
        private List<Integer> allowedLoopbackPorts = new ArrayList<>();
    }

    @Data
    public static class Topology {
        /** 現行正式運用は一DB一tenant。共有DBモードは未実装のため拒否する。 */
        private String mode = "DEDICATED_DATABASE";
        /** このプロセスが接続するDBへ束縛されたtenantの不透明な識別子。 */
        private String boundTenantId;
    }

    /** 認証済みclientがこのDBのtenant境界へ束縛されているかを判定する。 */
    public void assertTenantBound(String tenantId) {
        if (!"DEDICATED_DATABASE".equals(topology.mode)
                || !StringUtils.hasText(topology.boundTenantId)
                || !StringUtils.hasText(tenantId)
                || !topology.boundTenantId.equals(tenantId)) {
            throw ExternalApiSecurityException.forbidden("FORBIDDEN_SCOPE");
        }
    }
}
