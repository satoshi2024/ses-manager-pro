package com.ses.config.integrationhub;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * DG-05 の決定IDへ承認内容を束縛する正規形と digest（FINAL review b-13。W6A-P3-5 / G3-P3-13）。
 *
 * <p>NF06 の取込 admission 承認（{@code ImportAdmissionApproval}）・NF07 の決定ID束縛（{@code PrivacyDecisionBinding}）と
 * 同じ考え方で、署名者が決定書へ記載した承認内容の SHA-256 を運用者が {@code approval-digest} へ転記し、production の
 * 起動時に現行設定から再計算した値と照合する。承認値（送信元CIDR・trusted proxy・送信先・truststore 等）を変えたのに
 * 決定IDを取り直さない設定は、ここで不一致になり起動しない。
 *
 * <p>digest は鍵を持たない完全性の束縛で、署名ではない。設定を変えられる者は正規形から値を作り直せるため、変更の承認は
 * 決定書への記載と、二人がそれぞれ正規形から再計算して一致を確かめる手順（DG-05 草案 §1.1）が担う。
 *
 * <p>正規形（UTF-8、各行を LF で終える。最終行も LF）。1行目は方向ごとの版で、以降は固定順の {@code key=value}:
 * <pre>
 * nf05-integration-hub-inbound-approval-v1
 * decision-id=&lt;DG-05-IN-YYYYMMDD-NN&gt;
 * bound-tenant-id=&lt;topology.bound-tenant-id&gt;
 * allowed-client-cidrs=&lt;一覧&gt;
 * trusted-proxies=&lt;一覧&gt;
 * forwarded-header=&lt;X_FORWARDED_FOR&gt;
 * approved-inbound-providers=&lt;一覧（大文字小文字を保つ）&gt;
 * unauthenticated-audit-max-per-minute=&lt;整数&gt;
 *
 * nf05-integration-hub-outbound-approval-v1
 * decision-id=&lt;DG-05-OUT-YYYYMMDD-NN&gt;
 * bound-tenant-id=&lt;topology.bound-tenant-id&gt;
 * provider-mode=&lt;HTTPS&gt;
 * allowed-destinations=&lt;一覧&gt;
 * truststore-type=&lt;PKCS12|JKS&gt;
 * truststore-sha256=&lt;truststore file の SHA-256（小文字hex）&gt;
 * batch-size=&lt;整数&gt;
 * fixed-delay-ms=&lt;整数&gt;
 * </pre>
 * 一覧は、各要素の前後の空白を除き、空の要素と重複を除いて、ASCII（文字コード）の昇順にカンマで連結する。CIDR・送信先は
 * 小文字化してから並べる（IPv6 の16進。IPv4・数字は変わらない）。provider 名は {@code IntegrationHubInboundProviderCatalog}
 * が大文字小文字を区別して照合するため小文字化しない。決定ID・tenant は前後の空白だけを除く。
 *
 * <p>含めないもの: 秘密値（public-id-key・crypto 鍵・credential pepper・truststore password）と、承認内容ではない
 * 運用上の調整値（timeout・lease・purge 間隔等）。秘密値の注入は DG-05 IN-6・OUT-6 の secret store 記録で確認する。
 */
final class IntegrationHubApprovalDigest {
    /** inbound 正規形の版。正規形を変える場合は版を上げ、決定書の digest も取り直す。 */
    static final String INBOUND_FORMAT_VERSION = "nf05-integration-hub-inbound-approval-v1";
    /** outbound 正規形の版。 */
    static final String OUTBOUND_FORMAT_VERSION = "nf05-integration-hub-outbound-approval-v1";
    /** 決定書の digest は小文字 hex 64 桁（大文字で転記しても小文字化して比べる）。 */
    static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    /** digest の行へ入れる tenant の形式（改行・区切り文字を含めない。NF06・NF07 の tenant と同じ）。 */
    static final Pattern TENANT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    /** truststore を digest のために読む上限。正規の truststore はこれより十分小さい。 */
    static final long MAX_TRUSTSTORE_BYTES = 1024L * 1024L;

    private IntegrationHubApprovalDigest() {
    }

    /** inbound 承認内容の正規形。tenant が digest の形式に合わなければ null。 */
    static String canonicalInbound(IntegrationHubExternalApiProperties properties) {
        IntegrationHubExternalApiProperties.InboundApproval approval = properties.getPublicApi().getApproval();
        String tenant = tenant(properties);
        if (tenant == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        line(sb, null, INBOUND_FORMAT_VERSION);
        line(sb, "decision-id", trim(approval == null ? null : approval.getDecisionId()));
        line(sb, "bound-tenant-id", tenant);
        line(sb, "allowed-client-cidrs", list(approval == null ? null : approval.getAllowedClientCidrs(), true));
        line(sb, "trusted-proxies", list(properties.getSecurity().getTrustedProxies(), true));
        IntegrationHubExternalApiProperties.ForwardedHeader header = properties.getSecurity().getForwardedHeader();
        line(sb, "forwarded-header", header == null ? "" : header.name());
        line(sb, "approved-inbound-providers", list(properties.getProvider().getApprovedInboundProviders(), false));
        line(sb, "unauthenticated-audit-max-per-minute",
                Integer.toString(properties.getPublicApi().getUnauthenticatedAuditMaxPerMinute()));
        return sb.toString();
    }

    /**
     * outbound 承認内容の正規形。tenant が digest の形式に合わない、または truststore の digest が無ければ null。
     *
     * @param truststoreSha256 {@link #truststoreSha256} の値
     */
    static String canonicalOutbound(IntegrationHubExternalApiProperties properties, String truststoreSha256) {
        IntegrationHubExternalApiProperties.OutboundApproval approval = properties.getExternalTransport().getApproval();
        String tenant = tenant(properties);
        if (tenant == null || truststoreSha256 == null) {
            return null;
        }
        IntegrationHubExternalApiProperties.ExternalTransport transport = properties.getExternalTransport();
        IntegrationHubExternalApiProperties.ProviderMode mode = properties.getProvider().getMode();
        StringBuilder sb = new StringBuilder();
        line(sb, null, OUTBOUND_FORMAT_VERSION);
        line(sb, "decision-id", trim(approval == null ? null : approval.getDecisionId()));
        line(sb, "bound-tenant-id", tenant);
        line(sb, "provider-mode", mode == null ? "" : mode.name());
        line(sb, "allowed-destinations", list(approval == null ? null : approval.getAllowedDestinations(), true));
        line(sb, "truststore-type", trim(transport.getTruststoreType()));
        line(sb, "truststore-sha256", truststoreSha256);
        line(sb, "batch-size", Integer.toString(transport.getBatchSize()));
        line(sb, "fixed-delay-ms", Long.toString(transport.getFixedDelayMs()));
        return sb.toString();
    }

    /**
     * truststore file の内容の SHA-256（小文字 hex）。読めない・空・上限超過なら null。
     * 例外の message には path を含めない（呼出元へは null だけを返す）。
     */
    static String truststoreSha256(String truststorePath) {
        if (truststorePath == null || truststorePath.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(truststorePath);
            long size = Files.size(path);
            if (size < 1 || size > MAX_TRUSTSTORE_BYTES) {
                return null;
            }
            MessageDigest digest = sha256();
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_TRUSTSTORE_BYTES) {
                        return null;
                    }
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** 正規形の SHA-256（小文字 hex）。正規形が null なら null。 */
    static String digest(String canonical) {
        if (canonical == null) {
            return null;
        }
        return HexFormat.of().formatHex(sha256().digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    /** 決定書から転記された digest を比較用の形（前後空白除去・小文字）にする。形式外なら null。 */
    static String normalizeConfiguredDigest(String raw) {
        String value = trim(raw).toLowerCase(Locale.ROOT);
        return DIGEST.matcher(value).matches() ? value : null;
    }

    private static String tenant(IntegrationHubExternalApiProperties properties) {
        String tenant = trim(properties.getTopology() == null ? null : properties.getTopology().getBoundTenantId());
        return TENANT.matcher(tenant).matches() ? tenant : null;
    }

    private static String list(List<String> values, boolean lowerCase) {
        TreeSet<String> normalized = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                String item = trim(value);
                if (item.isEmpty()) {
                    continue;
                }
                normalized.add(lowerCase ? item.toLowerCase(Locale.ROOT) : item);
            }
        }
        return String.join(",", normalized);
    }

    private static void line(StringBuilder sb, String key, String value) {
        if (key != null) {
            sb.append(key).append('=');
        }
        sb.append(value).append('\n');
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256を利用できません", unavailable);
        }
    }
}
