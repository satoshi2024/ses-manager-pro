package com.ses.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * クライアントIP解決（S13-XFF-01 / FWD-SANITIZE PRE-P2-1）。
 *
 * <p>採用するのは「TCP peerが信頼プロキシ（{@code app.security.trusted-proxies}）のとき、そのプロキシが
 * X-Forwarded-For の右端に追記した1 hop」だけである。左側のhopはclientが自由に書ける値なので読まない。
 * peerが信頼プロキシでない、X-Forwarded-Forが無い・複数行・不正値のときはTCP peerそのものを返す
 * （信頼プロキシ未設定なら常にTCP peer＝fail-closed）。
 *
 * <p>通常のrequestでは {@code ForwardedHeaderSanitizingFilter}（ForwardedHeaderFilterの前段）が、書換え前の
 * TCP peerから同じ規則で解決した値を {@link #CLIENT_IP_ATTRIBUTE} に置く。framework strategyの
 * ForwardedHeaderFilterは {@code getRemoteAddr()} を書き換えX-Forwarded-Forを隠すため、filterより後で
 * 再計算すると誤る。そのためattributeがあればそれをそのまま返し、無い場合（単体テスト・filterを通らない経路）
 * だけrequestから直接解決する。Portal rate limit・Portal監査・MFA試行・永続sessionのIPは全てこの結果を使う。
 */
@Component
public class ClientIpResolver {

    /** 清浄化filterが解決したclient IP。 */
    public static final String CLIENT_IP_ATTRIBUTE = ClientIpResolver.class.getName() + ".CLIENT_IP";
    /** 清浄化filterが観測した書換え前のTCP peer。 */
    public static final String CONNECTOR_PEER_ATTRIBUTE = ClientIpResolver.class.getName() + ".CONNECTOR_PEER";

    static final String UNKNOWN = "unknown";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final List<String> trustedProxies;

    public ClientIpResolver(
            @Value("${app.security.trusted-proxies:}") String trustedProxiesCsv) {
        this.trustedProxies = parseTrustedProxies(trustedProxiesCsv);
    }

    /**
     * 信頼プロキシ設定（カンマ区切りのCIDRまたは単一IP）を解析する。
     * 構文不正の要素は黙って一致しなくなり、誤設定に気付けないため起動時に拒否する。
     */
    static List<String> parseTrustedProxies(String csv) {
        if (!StringUtils.hasText(csv)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String raw : csv.split(",", -1)) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                throw new IllegalStateException("app.security.trusted-proxiesに空要素は指定できません");
            }
            if (!IpCidrMatcher.isValidRange(entry)) {
                throw new IllegalStateException(
                        "app.security.trusted-proxiesはIPアドレスまたはCIDR形式で指定してください: " + entry);
            }
            result.add(entry);
        }
        return List.copyOf(result);
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        Object resolved = request.getAttribute(CLIENT_IP_ATTRIBUTE);
        if (resolved instanceof String value && !value.isBlank()) {
            return value;
        }
        return resolveFromConnection(request.getRemoteAddr(), request.getHeaders(X_FORWARDED_FOR));
    }

    /**
     * TCP peerと受信したX-Forwarded-Forの各行からclient IPを決める（清浄化filterと共通の規則）。
     *
     * @param connectorPeer 書換え前のTCP peer
     * @param forwardedFor  X-Forwarded-Forの各行（受信順）。nullは「header無し」
     */
    public String resolveFromConnection(String connectorPeer, Enumeration<String> forwardedFor) {
        String peer = normalizeOrRaw(connectorPeer);
        if (peer == null) {
            return UNKNOWN;
        }
        if (isTrustedProxy(peer)) {
            String hop = rightmostHop(forwardedFor);
            if (hop != null) {
                return hop;
            }
        }
        return peer;
    }

    /** addressが信頼プロキシの範囲に含まれるか。未設定・不正値はfalse。 */
    public boolean isTrustedProxy(String address) {
        if (address == null || address.isBlank() || trustedProxies.isEmpty()) {
            return false;
        }
        for (String range : trustedProxies) {
            if (IpCidrMatcher.matches(address.trim(), range)) {
                return true;
            }
        }
        return false;
    }

    /** 解析済みの信頼プロキシ設定（本番validatorの検査用）。 */
    public List<String> trustedProxies() {
        return trustedProxies;
    }

    /**
     * 信頼プロキシが追記した右端1 hop（正規化済み）。header無し・複数行・空要素・不正値はnull。
     * 複数行は「proxyがどの行へ追記したか」が実装依存で、clientが書いた行を右端と誤認し得るため拒否する。
     */
    public static String rightmostHop(Enumeration<String> lines) {
        String token = rightmostToken(lines);
        return token == null ? null : IpCidrMatcher.normalizeIp(token);
    }

    /** 1行だけのheaderの、カンマ区切りの右端要素（trim済み）。header無し・複数行・空要素はnull。 */
    public static String rightmostToken(Enumeration<String> lines) {
        if (lines == null || !lines.hasMoreElements()) {
            return null;
        }
        String line = lines.nextElement();
        if (line == null || lines.hasMoreElements()) {
            return null;
        }
        int comma = line.lastIndexOf(',');
        String token = (comma >= 0 ? line.substring(comma + 1) : line).trim();
        return token.isEmpty() ? null : token;
    }

    private static String normalizeOrRaw(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String normalized = IpCidrMatcher.normalizeIp(address.trim());
        return normalized != null ? normalized : address.trim();
    }
}
