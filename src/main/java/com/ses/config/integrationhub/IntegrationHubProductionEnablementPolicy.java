package com.ses.config.integrationhub;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * productionにおけるNF-05の開放判定（N5-P2-3、Q-12）。
 *
 * <p>inbound（public-api）とoutbound（external-transport）を独立に評価する。各方向は自分の決定IDと
 * allow-listだけを見て、もう一方の方向の設定には依存しない。outboundを開けるためにpublic-apiを
 * trueにする迂回はinboundの承認項目欠落として拒否される。
 *
 * <p>正本の承認項目と決定IDの形式は {@code .kiro/specs/integration-hub-public-api/dg-05-enablement-decision-draft.md}。
 * 既定値（両方向OFF、provider.mode=MOCK）は承認なしで常に通る。
 */
final class IntegrationHubProductionEnablementPolicy {
    /** DG-05 inbound決定ID。日付は署名日、末尾は同日の連番。 */
    static final Pattern INBOUND_DECISION_ID = Pattern.compile("DG-05-IN-[0-9]{8}-[0-9]{2}");
    /** DG-05 outbound決定ID。inboundと別番号にし、片方向の決定でもう片方向を開けない。 */
    static final Pattern OUTBOUND_DECISION_ID = Pattern.compile("DG-05-OUT-[0-9]{8}-[0-9]{2}");
    /** 送信先は小文字FQDN + 明示port。IP literal、単一label（localhost等）、userinfo、pathは受け付けない。 */
    private static final Pattern DESTINATION = Pattern.compile(
            "(?=.{1,253}:)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?:([0-9]{1,5})");
    /** 承認済み送信元CIDRの最小prefix。これより広い範囲は取引先の送信元として承認できない。 */
    static final int MIN_IPV4_CLIENT_PREFIX = 16;
    static final int MIN_IPV6_CLIENT_PREFIX = 32;

    private IntegrationHubProductionEnablementPolicy() {
    }

    /** productionの境界違反があれば最初の違反で起動を止める。 */
    static void requireProductionBoundary(IntegrationHubExternalApiProperties properties, Environment environment) {
        List<String> violations = violations(properties, environment);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("production NF-05境界: " + violations.get(0));
        }
    }

    /** 全違反を返す。test・runbookの事前確認で、どの承認項目が欠けているかを一度に示すために使う。 */
    static List<String> violations(IntegrationHubExternalApiProperties properties, Environment environment) {
        List<String> violations = new ArrayList<>();
        IntegrationHubExternalApiProperties.ProviderMode mode = properties.getProvider().getMode();
        boolean inbound = Boolean.TRUE.equals(properties.getPublicApi().getEnabled());
        boolean outbound = Boolean.TRUE.equals(properties.getExternalTransport().getEnabled());
        if (mode == IntegrationHubExternalApiProperties.ProviderMode.STUB
                || mode == IntegrationHubExternalApiProperties.ProviderMode.LOOPBACK) {
            violations.add("productionではprovider.mode=STUB/LOOPBACKを使用できません");
        }
        if (!properties.getSecurity().getAllowedLoopbackPorts().isEmpty()) {
            violations.add("productionではallowed-loopback-portsを空にしてください");
        }
        if (!outbound && mode != IntegrationHubExternalApiProperties.ProviderMode.MOCK) {
            violations.add("outbound無効時のprovider.modeはMOCK固定です");
        }
        if (inbound || outbound) {
            violations.addAll(credentialViolations(environment));
        }
        if (inbound) {
            violations.addAll(inboundViolations(properties));
        }
        if (outbound) {
            violations.addAll(outboundViolations(properties));
        }
        return List.copyOf(violations);
    }

    /** inbound（read API + inbound webhook）の承認項目。outbound側の設定は参照しない。 */
    static List<String> inboundViolations(IntegrationHubExternalApiProperties properties) {
        List<String> violations = new ArrayList<>();
        IntegrationHubExternalApiProperties.InboundApproval approval = properties.getPublicApi().getApproval();
        if (approval == null || approval.getDecisionId() == null
                || !INBOUND_DECISION_ID.matcher(approval.getDecisionId()).matches()) {
            violations.add("inbound開放にはDG-05-IN-YYYYMMDD-NN形式のpublic-api.approval.decision-idが必要です");
        }
        List<String> cidrs = approval == null ? List.of() : approval.getAllowedClientCidrs();
        if (cidrs == null || cidrs.isEmpty()) {
            violations.add("inbound開放にはpublic-api.approval.allowed-client-cidrsが必要です");
        } else if (cidrs.stream().anyMatch(cidr -> !narrowEnough(cidr))) {
            violations.add("public-api.approval.allowed-client-cidrsにIPv4 /16・IPv6 /32より広い範囲は指定できません");
        }
        if (properties.getSecurity().getTrustedProxies().isEmpty()) {
            // D-1: production入口はLBのみ。trusted proxy未設定では全requestがLB自身のIPと判定される。
            violations.add("inbound開放にはLBのCIDRをsecurity.trusted-proxiesへ設定してください");
        }
        return violations;
    }

    /** outbound（webhook送信）の承認項目。public-api側の設定は参照しない。 */
    static List<String> outboundViolations(IntegrationHubExternalApiProperties properties) {
        List<String> violations = new ArrayList<>();
        IntegrationHubExternalApiProperties.OutboundApproval approval = properties.getExternalTransport().getApproval();
        if (approval == null || approval.getDecisionId() == null
                || !OUTBOUND_DECISION_ID.matcher(approval.getDecisionId()).matches()) {
            violations.add("outbound開放にはDG-05-OUT-YYYYMMDD-NN形式のexternal-transport.approval.decision-idが必要です");
        }
        List<String> destinations = approval == null ? List.of() : approval.getAllowedDestinations();
        if (destinations == null || destinations.isEmpty()) {
            violations.add("outbound開放にはexternal-transport.approval.allowed-destinationsが必要です");
        } else if (destinations.stream().anyMatch(destination -> !validDestination(destination))) {
            violations.add("external-transport.approval.allowed-destinationsは小文字FQDNのhost:port形式で指定してください");
        }
        if (!productionOutboundCapable(properties.getProvider().getMode())) {
            violations.add("outbound開放には本番用transport modeが必要です（実HTTPS transportはW3-06bで追加）");
        }
        return violations;
    }

    /**
     * 本番で実送信を担えるtransport modeか。MOCKは送らずに成功を返し、STUB/LOOPBACKは開発・test専用のため、
     * 現行modeはすべてfalse。実HTTPS modeを追加したらこのswitchで明示的に判定する（網羅switchで追加漏れを防ぐ）。
     */
    static boolean productionOutboundCapable(IntegrationHubExternalApiProperties.ProviderMode mode) {
        if (mode == null) {
            return false;
        }
        return switch (mode) {
            case MOCK, STUB, LOOPBACK -> false;
        };
    }

    /** 両方向に共通するcredential前提。keyringの現行versionと鍵が環境から注入されていること。 */
    private static List<String> credentialViolations(Environment environment) {
        List<String> violations = new ArrayList<>();
        String version = environment == null ? null
                : environment.getProperty("integration.hub.crypto.current-key-version");
        if (!StringUtils.hasText(version)
                || !StringUtils.hasText(environment.getProperty("integration.hub.crypto.keys." + version))) {
            violations.add("NF-05開放にはintegration.hub.cryptoの現行key versionと鍵の注入が必要です");
        }
        return violations;
    }

    private static boolean narrowEnough(String cidr) {
        if (!ExternalApiCidrMatcher.isValidCidr(cidr)) {
            return false;
        }
        int prefix = ExternalApiCidrMatcher.prefixLength(cidr);
        return ExternalApiCidrMatcher.isIpv4Cidr(cidr)
                ? prefix >= MIN_IPV4_CLIENT_PREFIX : prefix >= MIN_IPV6_CLIENT_PREFIX;
    }

    static boolean validDestination(String destination) {
        if (destination == null || !destination.equals(destination.toLowerCase(Locale.ROOT))) {
            return false;
        }
        var matcher = DESTINATION.matcher(destination);
        if (!matcher.matches()) {
            return false;
        }
        String host = destination.substring(0, destination.lastIndexOf(':'));
        if (host.endsWith(".localhost")) {
            // RFC 6761の予約名はloopbackへ解決されるため、承認済み送信先にできない。
            return false;
        }
        String portText = matcher.group(2);
        if (portText.length() > 1 && portText.startsWith("0")) {
            return false;
        }
        int port = Integer.parseInt(portText);
        return port >= 1 && port <= 65535;
    }
}
