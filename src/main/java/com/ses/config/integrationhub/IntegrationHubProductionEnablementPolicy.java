package com.ses.config.integrationhub;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>決定IDは形式だけでなく、決定書に記載された承認内容の digest（{@code approval-digest}）へ束縛する（b-13）。
 * 正規形は {@link IntegrationHubApprovalDigest}。不一致・未設定・形式不正は開放する方向の違反として起動を止める。
 * 違反文言は固定で、設定値・再計算したdigestを含めない。
 *
 * <p>純粋化（feature-enablement-console design §9.5。FE-D01）: 検査する方向と承認の値（{@link IntegrationHubApprovals}）を引数で
 * 受け、flag と承認の値を properties から読まない（property の源の呼出し {@link #violations(IntegrationHubExternalApiProperties,
 * Environment)} だけが properties から作る）。truststore の検査は読んだ byte 列だけを見て、properties へ何も記録しない（記録は
 * 起動時の値が開の時の {@code validateBoundaries} だけが行う）。違反は固定の code（{@link Violation#code()}）と固定の文言を持ち、
 * boot resolver と評価器は code を、起動時検査は文言を使う。outbound が閉じている間の mode は MOCK か HTTPS を許す（承認の前に
 * HTTPS と承認の内容を配備の設定に入れるため。transport の bean は outbound が閉じていれば作られない）。</p>
 */
final class IntegrationHubProductionEnablementPolicy {
    /** DG-05 inbound決定ID。日付は署名日、末尾は同日の連番。 */
    static final Pattern INBOUND_DECISION_ID = Pattern.compile("DG-05-IN-[0-9]{8}-[0-9]{2}");
    /** DG-05 outbound決定ID。inboundと別番号にし、片方向の決定でもう片方向を開けない。 */
    static final Pattern OUTBOUND_DECISION_ID = Pattern.compile("DG-05-OUT-[0-9]{8}-[0-9]{2}");
    /** 承認済み送信元CIDRの最小prefix。これより広い範囲は取引先の送信元として承認できない。 */
    static final int MIN_IPV4_CLIENT_PREFIX = 16;
    static final int MIN_IPV6_CLIENT_PREFIX = 32;

    /**
     * 1つの違反（code は固定の文字列で 64 文字以内。文言も固定で値を含めない）。
     *
     * @param code    違反の code（{@code nf05.} で始まる。開放状態の {@code closed_reason} にも入る）
     * @param message 起動時検査の文言
     */
    record Violation(String code, String message) {
    }

    private IntegrationHubProductionEnablementPolicy() {
    }

    /** productionの境界違反があれば最初の違反で起動を止める（property の源。flag と承認の値を properties から読む）。 */
    static void requireProductionBoundary(IntegrationHubExternalApiProperties properties, Environment environment) {
        List<String> violations = violations(properties, environment);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("production NF-05境界: " + violations.get(0));
        }
    }

    /**
     * 全違反の文言を返す（property の源の呼出し。flag と承認の値を properties から作る）。test・runbookの事前確認で、どの承認項目が
     * 欠けているかを一度に示すために使う。truststore は読むだけで記録しない。
     */
    static List<String> violations(IntegrationHubExternalApiProperties properties, Environment environment) {
        boolean inbound = Boolean.TRUE.equals(properties.getPublicApi().getEnabled());
        boolean outbound = Boolean.TRUE.equals(properties.getExternalTransport().getEnabled());
        byte[] truststore = outbound
                ? IntegrationHubApprovalDigest.readTruststore(properties.getExternalTransport().getTruststorePath()) : null;
        return messages(violationList(properties, environment, inbound, outbound,
                IntegrationHubApprovals.fromProperties(properties), truststore));
    }

    /**
     * 純粋な検査（FE-D01）。{@code inbound}・{@code outbound} は検査する方向（閉じている process でも、開いた場合の違反を求める）。
     * 承認の値は引数、truststore は読んだ byte 列（読めなければ null）。
     */
    static List<Violation> violationList(IntegrationHubExternalApiProperties properties, Environment environment,
                                         boolean inbound, boolean outbound, IntegrationHubApprovals approvals,
                                         byte[] truststoreContent) {
        List<Violation> violations = new ArrayList<>(flagIndependentViolations(properties, outbound));
        if (inbound || outbound) {
            violations.addAll(credentialViolations(environment));
        }
        IntegrationHubApprovals values = approvals == null ? IntegrationHubApprovals.none() : approvals;
        if (inbound) {
            violations.addAll(inboundViolationList(properties, values.inbound()));
        }
        if (outbound) {
            violations.addAll(outboundViolationList(properties, values.outbound(), truststoreContent));
        }
        return List.copyOf(violations);
    }

    /**
     * flag に依らない検査（{@code provider.mode} が STUB・LOOPBACK でない、loopback の port が空）と、outbound が閉じている時の mode の
     * 規則（MOCK か HTTPS）。設定の誤りで機能の条件ではないため、DB の源でも起動を止める。
     */
    static List<Violation> flagIndependentViolations(IntegrationHubExternalApiProperties properties, boolean outbound) {
        List<Violation> violations = new ArrayList<>();
        IntegrationHubExternalApiProperties.ProviderMode mode = properties.getProvider().getMode();
        if (mode == IntegrationHubExternalApiProperties.ProviderMode.STUB
                || mode == IntegrationHubExternalApiProperties.ProviderMode.LOOPBACK) {
            violations.add(new Violation("nf05.providerMode.devOnly", "productionではprovider.mode=STUB/LOOPBACKを使用できません"));
        }
        if (!properties.getSecurity().getAllowedLoopbackPorts().isEmpty()) {
            violations.add(new Violation("nf05.loopbackPorts", "productionではallowed-loopback-portsを空にしてください"));
        }
        if (!outbound && mode != IntegrationHubExternalApiProperties.ProviderMode.MOCK
                && mode != IntegrationHubExternalApiProperties.ProviderMode.HTTPS) {
            violations.add(new Violation("nf05.providerMode.closedOutbound", "outbound無効時のprovider.modeはMOCKかHTTPSです"));
        }
        return violations;
    }

    /** inbound（read API + inbound webhook）の承認項目の文言（承認の値は properties から作る。property の源と試験の呼出し）。 */
    static List<String> inboundViolations(IntegrationHubExternalApiProperties properties) {
        return messages(inboundViolationList(properties, IntegrationHubApprovals.fromProperties(properties).inbound()));
    }

    /** inbound（read API + inbound webhook）の承認項目。outbound側の設定は参照しない。 */
    static List<Violation> inboundViolationList(IntegrationHubExternalApiProperties properties,
                                                IntegrationHubApprovals.Decision decision) {
        List<Violation> violations = new ArrayList<>();
        IntegrationHubApprovals.Decision value = decision == null ? IntegrationHubApprovals.Decision.NONE : decision;
        IntegrationHubExternalApiProperties.InboundApproval approval = properties.getPublicApi().getApproval();
        if (value.decisionId() == null || !INBOUND_DECISION_ID.matcher(value.decisionId()).matches()) {
            violations.add(new Violation("nf05.inbound.decisionId",
                    "inbound開放にはDG-05-IN-YYYYMMDD-NN形式のpublic-api.approval.decision-idが必要です"));
        }
        List<String> cidrs = approval == null ? List.of() : approval.getAllowedClientCidrs();
        if (cidrs == null || cidrs.isEmpty()) {
            violations.add(new Violation("nf05.inbound.allowedClientCidrs.missing",
                    "inbound開放にはpublic-api.approval.allowed-client-cidrsが必要です"));
        } else if (cidrs.stream().anyMatch(cidr -> !narrowEnough(cidr))) {
            violations.add(new Violation("nf05.inbound.allowedClientCidrs.tooWide",
                    "public-api.approval.allowed-client-cidrsにIPv4 /16・IPv6 /32より広い範囲は指定できません"));
        }
        if (properties.getSecurity().getForwardedHeader()
                != IntegrationHubExternalApiProperties.ForwardedHeader.X_FORWARDED_FOR) {
            violations.add(new Violation("nf05.inbound.forwardedHeader", "ALBのinboundはX_FORWARDED_FORだけを使用できます"));
        }
        for (String proxy : properties.getSecurity().getTrustedProxies()) {
            int minimum = ExternalApiCidrMatcher.isIpv4Cidr(proxy) ? 24 : 64;
            if (!ExternalApiCidrMatcher.isValidCidr(proxy) || ExternalApiCidrMatcher.prefixLength(proxy) < minimum) {
                violations.add(new Violation("nf05.inbound.trustedProxy.tooWide",
                        "trusted proxyはALB専用のIPv4 /24・IPv6 /64以上の狭い範囲へ限定してください"));
            }
            if (cidrs != null && cidrs.stream().anyMatch(client -> overlaps(proxy, client))) {
                violations.add(new Violation("nf05.inbound.trustedProxy.overlap", "trusted proxyとclient CIDRの重複は許可できません"));
            }
        }
        if (properties.getSecurity().getTrustedProxies().isEmpty()) {
            // D-1: production入口はLBのみ。trusted proxy未設定では全requestがLB自身のIPと判定される。
            violations.add(new Violation("nf05.inbound.trustedProxies.missing",
                    "inbound開放にはLBのCIDRをsecurity.trusted-proxiesへ設定してください"));
        }
        String configured = IntegrationHubApprovalDigest.normalizeConfiguredDigest(value.approvalDigest());
        String canonical = IntegrationHubApprovalDigest.canonicalInbound(properties, value.decisionId());
        if (configured == null) {
            violations.add(new Violation("nf05.inbound.approvalDigest.missing",
                    "inbound開放にはpublic-api.approval.approval-digest（決定書の承認内容のSHA-256、小文字hex 64桁）が必要です"));
        } else if (canonical == null) {
            violations.add(new Violation("nf05.inbound.approvalDigest.tenant",
                    "inbound承認digestの算出にはtopology.bound-tenant-idを英数字と._:-の100文字以内にしてください"));
        } else if (!configured.equals(IntegrationHubApprovalDigest.digest(canonical))) {
            // 決定IDを取り直さずに承認値（CIDR・trusted proxy・provider等）を変えた設定を止める。
            violations.add(new Violation("nf05.inbound.approvalDigest.mismatch",
                    "public-api.approval.approval-digestが現行のinbound承認内容と一致しません（決定書と設定を照合してください）"));
        }
        return violations;
    }

    /**
     * outbound（webhook送信）の承認項目の文言（承認の値は properties から作る。property の源と試験の呼出し）。truststore は読むだけで
     * 記録しない。
     */
    static List<String> outboundViolations(IntegrationHubExternalApiProperties properties) {
        return messages(outboundViolationList(properties, IntegrationHubApprovals.fromProperties(properties).outbound(),
                IntegrationHubApprovalDigest.readTruststore(properties.getExternalTransport().getTruststorePath())));
    }

    /** outbound（webhook送信）の承認項目。public-api側の設定は参照しない。truststore は読んだ byte 列（読めなければ null）。 */
    static List<Violation> outboundViolationList(IntegrationHubExternalApiProperties properties,
                                                 IntegrationHubApprovals.Decision decision, byte[] truststoreContent) {
        List<Violation> violations = new ArrayList<>();
        IntegrationHubApprovals.Decision value = decision == null ? IntegrationHubApprovals.Decision.NONE : decision;
        IntegrationHubExternalApiProperties.OutboundApproval approval = properties.getExternalTransport().getApproval();
        if (value.decisionId() == null || !OUTBOUND_DECISION_ID.matcher(value.decisionId()).matches()) {
            violations.add(new Violation("nf05.outbound.decisionId",
                    "outbound開放にはDG-05-OUT-YYYYMMDD-NN形式のexternal-transport.approval.decision-idが必要です"));
        }
        List<String> destinations = approval == null ? List.of() : approval.getAllowedDestinations();
        if (destinations == null || destinations.isEmpty()) {
            violations.add(new Violation("nf05.outbound.allowedDestinations.missing",
                    "outbound開放にはexternal-transport.approval.allowed-destinationsが必要です"));
        } else if (destinations.stream().anyMatch(destination -> !validDestination(destination))) {
            violations.add(new Violation("nf05.outbound.allowedDestinations.invalid",
                    "external-transport.approval.allowed-destinationsは小文字FQDNのhost:port形式で指定してください"));
        }
        if (!productionOutboundCapable(properties.getProvider().getMode())) {
            violations.add(new Violation("nf05.outbound.providerMode", "outbound開放にはprovider.mode=HTTPSが必要です"));
        }
        String configured = IntegrationHubApprovalDigest.normalizeConfiguredDigest(value.approvalDigest());
        String truststore = IntegrationHubApprovalDigest.sha256Hex(truststoreContent);
        if (configured == null) {
            violations.add(new Violation("nf05.outbound.approvalDigest.missing",
                    "outbound開放にはexternal-transport.approval.approval-digest（決定書の承認内容のSHA-256、小文字hex 64桁）が必要です"));
        } else if (truststore == null) {
            // truststoreの内容（信頼する発行者）も承認内容に含めるため、読めなければ照合できない。
            violations.add(new Violation("nf05.outbound.truststore.unreadable",
                    "outbound承認digestの照合にはexternal-transport.truststore-pathのfileを読める必要があります（1MiB以下）"));
        } else {
            String canonical = IntegrationHubApprovalDigest.canonicalOutbound(properties, value.decisionId(), truststore);
            if (canonical == null) {
                violations.add(new Violation("nf05.outbound.approvalDigest.tenant",
                        "outbound承認digestの算出にはtopology.bound-tenant-idを英数字と._:-の100文字以内にしてください"));
            } else if (!configured.equals(IntegrationHubApprovalDigest.digest(canonical))) {
                // 送信先・truststore・送信量を決定書と違う値へ変えた設定を止める。
                violations.add(new Violation("nf05.outbound.approvalDigest.mismatch",
                        "external-transport.approval.approval-digestが現行のoutbound承認内容と一致しません（決定書と設定を照合してください）"));
            }
        }
        return violations;
    }

    /**
     * 本番で実送信を担えるtransport modeか。MOCKは送らずに成功を返し、STUB/LOOPBACKは開発・test専用のため、
     * HTTPSだけを許可する。網羅switchで追加漏れを防ぐ。
     */
    static boolean productionOutboundCapable(IntegrationHubExternalApiProperties.ProviderMode mode) {
        if (mode == null) {
            return false;
        }
        return switch (mode) {
            case MOCK, STUB, LOOPBACK -> false;
            case HTTPS -> true;
        };
    }

    /**
     * 両方向に共通するcredential前提。keyringの現行versionと鍵、credential hashのpepper（N5-P3-2）が環境から
     * 注入されていること。pepperは32 byteを{@code =}無しのbase64urlで書き、暗号鍵と同じ値にしない（用途を分ける）。
     *
     * <p>同じ値かどうかは復号後のbyte列で比べ、現行versionだけでなく{@code integration.hub.crypto.keys.*}の全versionの
     * 鍵と比べる（RB-NF05 review P3-5）。32 byteのbase64urlは末尾の文字に使われない2 bitがあり、同じbyte列に4通りの
     * 表記があるため、文字列の比較では表記を変えた同じ鍵を見逃す。
     */
    static List<Violation> credentialViolations(Environment environment) {
        List<Violation> violations = new ArrayList<>();
        String version = environment == null ? null
                : environment.getProperty("integration.hub.crypto.current-key-version");
        String key = StringUtils.hasText(version)
                ? environment.getProperty("integration.hub.crypto.keys." + version) : null;
        if (!StringUtils.hasText(key)) {
            violations.add(new Violation("nf05.credential.cryptoKey",
                    "NF-05開放にはintegration.hub.cryptoの現行key versionと鍵の注入が必要です"));
        }
        String pepper = environment == null ? null
                : environment.getProperty(CREDENTIAL_HASH_PEPPER_PROPERTY);
        byte[] pepperBytes = decodePepper(pepper);
        if (pepperBytes == null) {
            violations.add(new Violation("nf05.credential.pepper",
                    "NF-05開放にはintegration.hub.crypto.credential-hash-pepper（32 byteのbase64url、=無し）の注入が必要です"));
        } else {
            try {
                List<String> keys = new ArrayList<>(cryptoKeys(environment));
                if (StringUtils.hasText(key)) keys.add(key);
                if (keys.stream().anyMatch(candidate -> sameBytes(pepperBytes, candidate))) {
                    violations.add(new Violation("nf05.credential.pepperReused",
                            "integration.hub.crypto.credential-hash-pepperには暗号鍵（全version）と別の値を設定してください"));
                }
            } finally {
                java.util.Arrays.fill(pepperBytes, (byte) 0);
            }
        }
        return violations;
    }

    /** 違反の文言の一覧。 */
    static List<String> messages(List<Violation> violations) {
        return violations.stream().map(Violation::message).toList();
    }

    /** credential hashのpepperを読むproperty名。keyringと同じ名前を使う。 */
    static final String CREDENTIAL_HASH_PEPPER_PROPERTY =
            com.ses.service.integrationhub.crypto.EnvironmentIntegrationHubKeyring.CREDENTIAL_HASH_PEPPER_PROPERTY;

    /** pepperを復号する。32 byteの{@code =}無しbase64urlでなければnull。 */
    private static byte[] decodePepper(String encoded) {
        if (!StringUtils.hasText(encoded) || encoded.contains("=") || encoded.matches(".*\\s.*")) {
            return null;
        }
        try {
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(encoded);
            if (decoded.length == 32) return decoded;
            java.util.Arrays.fill(decoded, (byte) 0);
            return null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** {@code integration.hub.crypto.keys.*}の全versionの鍵（環境変数の{@code INTEGRATION_HUB_CRYPTO_KEYS_<VERSION>}を含む）。 */
    private static List<String> cryptoKeys(Environment environment) {
        if (environment == null) return List.of();
        try {
            return List.copyOf(org.springframework.boot.context.properties.bind.Binder.get(environment)
                    .bind("integration.hub.crypto.keys",
                            org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, String.class))
                    .orElse(java.util.Map.of()).values().stream().filter(StringUtils::hasText).toList());
        } catch (RuntimeException unbindable) {
            // 鍵の一覧を作れなくても、現行versionの鍵とは呼出元が比べる。
            return List.of();
        }
    }

    /** 鍵を復号した byte 列がpepperと一致するか（定数時間比較。復号できない鍵は一致しない）。 */
    private static boolean sameBytes(byte[] pepper, String encodedKey) {
        byte[] key;
        try {
            key = java.util.Base64.getUrlDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        try {
            return java.security.MessageDigest.isEqual(pepper, key);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    private static boolean narrowEnough(String cidr) {
        if (!ExternalApiCidrMatcher.isValidCidr(cidr)) {
            return false;
        }
        int prefix = ExternalApiCidrMatcher.prefixLength(cidr);
        return ExternalApiCidrMatcher.isIpv4Cidr(cidr)
                ? prefix >= MIN_IPV4_CLIENT_PREFIX : prefix >= MIN_IPV6_CLIENT_PREFIX;
    }

    private static boolean overlaps(String left, String right) {
        if (!ExternalApiCidrMatcher.isValidCidr(left) || !ExternalApiCidrMatcher.isValidCidr(right)) return true;
        return ExternalApiCidrMatcher.matchesAny(left.substring(0, left.indexOf('/')), List.of(right))
                || ExternalApiCidrMatcher.matchesAny(right.substring(0, right.indexOf('/')), List.of(left));
    }

    static boolean validDestination(String destination) {
        return com.ses.service.integrationhub.IntegrationHubHttpsEndpointGuard.validDestination(destination);
    }
}
