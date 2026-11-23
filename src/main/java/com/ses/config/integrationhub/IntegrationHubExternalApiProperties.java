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

    /**
     * 起動時の値が開で違反があり、DB の源の prod の安全網で閉じた方向の違反の code（inbound・outbound。閉じていなければ null。
     * feature-enablement-console design §9.5）。boot resolver が同じ関数で判定するため通常は起きない。
     */
    @lombok.Getter(lombok.AccessLevel.NONE)
    @lombok.Setter(lombok.AccessLevel.NONE)
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    private volatile String inboundBootClosure;

    @lombok.Getter(lombok.AccessLevel.NONE)
    @lombok.Setter(lombok.AccessLevel.NONE)
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    private volatile String outboundBootClosure;

    /** 安全網で閉じた方向の違反の code（閉じていなければ null）。 */
    public String bootClosureCode(boolean inbound) {
        return inbound ? inboundBootClosure : outboundBootClosure;
    }

    /**
     * 起動の境界の検査。flag に依る検査（public-id-key・DEDICATED_DATABASE と bound-tenant-id・HTTPS の truststore・LOOPBACK の
     * port）は {@link #boundaryViolations} の静的な関数と同じ規則で、boot resolver と評価器も同じ関数を使う（FE-D01）。DB の源の prod
     * では、起動時の値が開でも flag に依る違反があれば例外にせず、その方向を閉じて code を残す（{@link #bootClosureCode}。起動の後の
     * 反映が {@code AUTO_CLOSED} にする）。flag に依らない検査は従来どおり起動を止める。
     */
    @PostConstruct
    void validateBoundaries() {
        if (publicApi.enabled == null || externalTransport.enabled == null || provider.mode == null) {
            throw new IllegalStateException("integration.hubのpublic-api/external-transport/provider.modeは明示設定が必要です");
        }
        boolean production = environment != null
                && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"));
        boolean safetyNet = production && databaseSource(environment);
        for (IntegrationHubProductionEnablementPolicy.Violation violation
                : leadingBoundaryViolations(this, publicApi.enabled, externalTransport.enabled)) {
            rejectOrClose(violation, safetyNet, true, true);
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
        long providerTimeoutSeconds = (externalTransport.dnsTimeoutMs + externalTransport.connectTimeoutMs
                + (long) externalTransport.readTimeoutMs + 2_000L + 999L) / 1000L;
        if (120L + providerTimeoutSeconds + 1 >= 300 - 30)
            throw new IllegalStateException("NF05のbatch総期限がscheduler lockを超えています");
        if (externalTransport.leaseSeconds <= providerTimeoutSeconds) {
            throw new IllegalStateException("leaseはDNS/connect/read/closeの合計より長く設定してください");
        }
        if (externalTransport.dnsTimeoutMs < 100 || externalTransport.dnsTimeoutMs > 5_000) {
            throw new IllegalStateException("DNS timeoutは100〜5000msで指定してください");
        }
        for (IntegrationHubProductionEnablementPolicy.Violation violation
                : transportBoundaryViolations(this, externalTransport.enabled)) {
            rejectOrClose(violation, safetyNet, false, true);
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
        if (production) {
            productionBoundary(safetyNet);
        }
    }

    /**
     * prod の承認の検査。property の源は配備の設定の承認の値で検査し、最初の違反で起動を止める。DB の源は boot resolver が確認済みの
     * 署名から写した承認の値（{@link IntegrationHubApprovals#fromBootProperties}）で検査し、違反のある方向を閉じる（安全網）。truststore は
     * 1回だけ読み、outbound が開いたままなら同じ byte 列を HTTPS transport のために記録する（RB-NF05 review P3-6。記録はここだけ）。
     */
    private void productionBoundary(boolean safetyNet) {
        boolean inbound = Boolean.TRUE.equals(publicApi.enabled);
        boolean outbound = Boolean.TRUE.equals(externalTransport.enabled);
        byte[] truststore = outbound ? IntegrationHubApprovalDigest.readTruststore(externalTransport.truststorePath) : null;
        if (!safetyNet) {
            List<IntegrationHubProductionEnablementPolicy.Violation> violations =
                    IntegrationHubProductionEnablementPolicy.violationList(this, environment, inbound, outbound,
                            IntegrationHubApprovals.fromProperties(this), truststore);
            if (!violations.isEmpty()) {
                throw new IllegalStateException("production NF-05境界: " + violations.get(0).message());
            }
        } else {
            List<IntegrationHubProductionEnablementPolicy.Violation> common =
                    IntegrationHubProductionEnablementPolicy.flagIndependentViolations(this, outbound);
            if (!common.isEmpty()) {
                throw new IllegalStateException("production NF-05境界: " + common.get(0).message());
            }
            IntegrationHubApprovals approvals = IntegrationHubApprovals.fromBootProperties(environment);
            List<IntegrationHubProductionEnablementPolicy.Violation> credential = inbound || outbound
                    ? IntegrationHubProductionEnablementPolicy.credentialViolations(environment) : List.of();
            if (inbound) {
                List<IntegrationHubProductionEnablementPolicy.Violation> violations = new ArrayList<>(credential);
                violations.addAll(IntegrationHubProductionEnablementPolicy.inboundViolationList(this, approvals.inbound()));
                if (!violations.isEmpty()) {
                    rejectOrClose(violations.get(0), true, true, false);
                }
            }
            if (outbound) {
                List<IntegrationHubProductionEnablementPolicy.Violation> violations = new ArrayList<>(credential);
                violations.addAll(IntegrationHubProductionEnablementPolicy.outboundViolationList(this,
                        approvals.outbound(), truststore));
                if (!violations.isEmpty()) {
                    rejectOrClose(violations.get(0), true, false, true);
                }
            }
        }
        if (Boolean.TRUE.equals(externalTransport.enabled) && truststore != null) {
            externalTransport.recordApprovedTruststore(externalTransport.truststorePath, truststore);
        }
    }

    /** 違反を起動の停止にするか、DB の源の prod の安全網でその方向を閉じる。 */
    private void rejectOrClose(IntegrationHubProductionEnablementPolicy.Violation violation, boolean safetyNet,
                               boolean inbound, boolean outbound) {
        if (!safetyNet) {
            throw new IllegalStateException(violation.message());
        }
        if (inbound && Boolean.TRUE.equals(publicApi.enabled)) {
            publicApi.enabled = false;
            inboundBootClosure = violation.code();
        }
        if (outbound && Boolean.TRUE.equals(externalTransport.enabled)) {
            externalTransport.enabled = false;
            outboundBootClosure = violation.code();
        }
    }

    /** 開閉の源が DB か（{@code app.feature-enablement.source=database}）。 */
    static boolean databaseSource(Environment environment) {
        String source = environment == null ? null : environment.getProperty("app.feature-enablement.source");
        return source != null && "database".equalsIgnoreCase(source.trim());
    }

    /**
     * flag に依る境界の違反（boot resolver・評価器・起動時検査が共用する純粋な関数。FE-D01）。{@code inbound}・{@code outbound} は
     * 検査する方向（開いた場合の違反を求める）。
     */
    static List<IntegrationHubProductionEnablementPolicy.Violation> boundaryViolations(
            IntegrationHubExternalApiProperties properties, boolean inbound, boolean outbound) {
        List<IntegrationHubProductionEnablementPolicy.Violation> violations =
                new ArrayList<>(leadingBoundaryViolations(properties, inbound, outbound));
        violations.addAll(transportBoundaryViolations(properties, outbound));
        return violations;
    }

    /** public-id-key と DEDICATED_DATABASE・bound-tenant-id（どちらかの方向が開く時）。 */
    static List<IntegrationHubProductionEnablementPolicy.Violation> leadingBoundaryViolations(
            IntegrationHubExternalApiProperties properties, boolean inbound, boolean outbound) {
        List<IntegrationHubProductionEnablementPolicy.Violation> violations = new ArrayList<>();
        boolean anyDirection = inbound || outbound;
        String key = properties.getPublicApi().getPublicIdKey();
        // public IDはread APIの応答とoutbound payloadのprimary bindingの両方で使うため、方向を問わず要求する。
        if (anyDirection && (key == null || key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)) {
            violations.add(new IntegrationHubProductionEnablementPolicy.Violation("nf05.boundary.publicIdKey",
                    "public-api.enabledまたはexternal-transport.enabled=trueには32 byte以上のpublic-id-keyが必要です"));
        }
        Topology topology = properties.getTopology();
        if (anyDirection && (topology == null || !"DEDICATED_DATABASE".equals(topology.getMode())
                || !StringUtils.hasText(topology.getBoundTenantId()))) {
            violations.add(new IntegrationHubProductionEnablementPolicy.Violation("nf05.boundary.topology",
                    "公開API/外部transportはDEDICATED_DATABASEとbound-tenant-idの明示設定が必要です"));
        }
        return violations;
    }

    /** HTTPS の truststore と LOOPBACK の許可 port（outbound が開く時）。 */
    static List<IntegrationHubProductionEnablementPolicy.Violation> transportBoundaryViolations(
            IntegrationHubExternalApiProperties properties, boolean outbound) {
        List<IntegrationHubProductionEnablementPolicy.Violation> violations = new ArrayList<>();
        ProviderMode mode = properties.getProvider().getMode();
        ExternalTransport transport = properties.getExternalTransport();
        if (outbound && mode == ProviderMode.HTTPS
                && (!StringUtils.hasText(transport.getTruststorePath())
                || !SetHolder.TRUSTSTORE_TYPES.contains(transport.getTruststoreType()))) {
            violations.add(new IntegrationHubProductionEnablementPolicy.Violation("nf05.boundary.truststore",
                    "HTTPS transportにはtruststoreが必要です"));
        }
        if (outbound && mode == ProviderMode.LOOPBACK && properties.getSecurity().getAllowedLoopbackPorts().isEmpty()) {
            violations.add(new IntegrationHubProductionEnablementPolicy.Violation("nf05.boundary.loopbackPorts",
                    "LOOPBACK transportには明示的な許可portが必要です"));
        }
        return violations;
    }

    private static class SetHolder {
        static final java.util.Set<String> TRUSTSTORE_TYPES = java.util.Set.of("PKCS12", "JKS");
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
        /**
         * 決定書に記載されたinbound承認内容のSHA-256（小文字hex 64桁）。productionのinbound有効時は必須で、
         * 現行設定から {@link IntegrationHubApprovalDigest#canonicalInbound} で再計算した値と一致しなければ起動しない。
         */
        private String approvalDigest;
    }

    @Data
    public static class ExternalTransport {
        private Boolean enabled;
        private int batchSize = 32;
        private int leaseSeconds = 60;
        private long fixedDelayMs = 1_000L;
        private int connectTimeoutMs = 3_000;
        private int readTimeoutMs = 3_000;
        private int dnsTimeoutMs = 2_000;
        private String truststorePath;
        private String truststorePassword;
        private String truststoreType = "PKCS12";
        /** outbound方向のproduction承認（DG-05）。dev/testでは任意。 */
        private OutboundApproval approval = new OutboundApproval();
        /**
         * production起動時の承認照合（{@link IntegrationHubProductionEnablementPolicy}）がdigestを計算したtruststoreの
         * pathとbyte列。HTTPS transportはfileを読み直さずこのbyte列を読み込み、照合後にfileが差し替えられても照合した内容
         * だけを信頼する（RB-NF05 review P3-6。TOCTOUを無くす）。設定からは束縛しない。
         */
        @lombok.Getter(lombok.AccessLevel.NONE)
        @lombok.Setter(lombok.AccessLevel.NONE)
        @lombok.EqualsAndHashCode.Exclude
        @lombok.ToString.Exclude
        private volatile ApprovedTruststore approvedTruststore;

        /** 承認照合でdigestを計算したtruststoreのbyte列を記録する（照合だけが呼ぶ。byte列は複製して持つ）。 */
        public void recordApprovedTruststore(String path, byte[] content) {
            this.approvedTruststore = path == null || content == null ? null
                    : new ApprovedTruststore(path, content.clone());
        }

        /**
         * 承認照合でdigestを計算したtruststoreのbyte列の複製。照合していない（dev/test・OFF）か、その後に
         * {@code truststore-path} が変わっていればnullを返し、呼出元はfileを読む。
         */
        public byte[] approvedTruststoreContent() {
            ApprovedTruststore current = approvedTruststore;
            if (current == null || !current.path().equals(truststorePath)) return null;
            return current.content().clone();
        }

        private record ApprovedTruststore(String path, byte[] content) { }
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
        /**
         * 決定書に記載されたoutbound承認内容のSHA-256（小文字hex 64桁）。productionのoutbound有効時は必須で、
         * 現行設定とtruststoreの内容から {@link IntegrationHubApprovalDigest#canonicalOutbound} で再計算した値と
         * 一致しなければ起動しない。
         */
        private String approvalDigest;
    }

    @Data
    public static class Provider {
        private ProviderMode mode;
        /** Ownerが承認したinbound providerだけを受け付ける。空はdefault deny。 */
        private List<String> approvedInboundProviders = new ArrayList<>();
    }

    public enum ProviderMode {
        MOCK, STUB, LOOPBACK, HTTPS
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
