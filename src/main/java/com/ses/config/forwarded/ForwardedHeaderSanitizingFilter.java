package com.ses.config.forwarded;

import com.ses.common.util.ClientIpResolver;
import com.ses.common.util.IpCidrMatcher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Springの {@link ForwardedHeaderFilter} の前段で、clientが送った転送ヘッダーを清浄化する
 * （FWD-SANITIZE PRE-P1-1 / PRE-P2-1）。
 *
 * <p>{@code server.forward-headers-strategy: framework} のForwardedHeaderFilterはTCP peerを確認せず、受信した
 * {@code Forwarded} / {@code X-Forwarded-*} をそのままrequestへ反映する。ALBはX-Forwarded-For/Proto/Port以外を
 * 透過するため、clientが {@code X-Forwarded-Prefix: /x} を送るだけで {@code getRequestURI()} が {@code /x/api/...}
 * になり、URIで判定する自前filter（menu・action権限、監査、rate limit、MFA強制等）が判定を外していた。
 * また {@code getRemoteAddr()} はX-Forwarded-Forの最左（clientが書ける値）になっていた。
 *
 * <ul>
 *   <li>{@code X-Forwarded-Prefix}・{@code X-Forwarded-Host}・{@code X-Forwarded-Ssl}・{@code Forwarded} は、
 *       送信元にかかわらず常に削除する（アプリはroot配置でprefix不要。hostはALBが保持するHost headerを使う）。</li>
 *   <li>{@code X-Forwarded-For}・{@code X-Forwarded-Proto}・{@code X-Forwarded-Port} は、TCP peerが信頼プロキシ
 *       （{@code app.security.trusted-proxies}）のときだけ、プロキシが書いた右端1要素に絞って残す。値が不正・複数行なら
 *       削除する。信頼プロキシでなければ削除する（未設定なら全て削除＝fail-closed）。</li>
 * </ul>
 * 清浄化後に残った値だけを親クラス（ForwardedHeaderFilter）が反映する。解決したclient IPと書換え前のTCP peerは
 * {@link ClientIpResolver} のrequest attributeへ置き、remote address由来の判定はそれを使う。
 *
 * <p>Boot標準のForwardedHeaderFilter登録（{@code @ConditionalOnMissingFilterBean}）は {@link ForwardedHeaderConfiguration}
 * で置き換える。清浄化と反映を同じfilterの中で行うのは、同順位（HIGHEST_PRECEDENCE）の別filterに分けると
 * 実行順序が保証されないためである。ERRORディスパッチでも親クラスの {@code doFilterNestedErrorDispatch} 経由で
 * 同じ清浄化を通る。NF-05公開APIのTomcat engine valveはservlet filterより前に原文を捕捉するため、この清浄化の
 * 影響を受けない。
 */
@Slf4j
public class ForwardedHeaderSanitizingFilter extends ForwardedHeaderFilter {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";
    static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
    static final String X_FORWARDED_PORT = "X-Forwarded-Port";

    /** ForwardedHeaderFilterが処理する全header（小文字）。ここに無い名前は清浄化の対象外。 */
    static final Set<String> FORWARDED_HEADER_NAMES = Set.of(
            "forwarded", "x-forwarded-host", "x-forwarded-port", "x-forwarded-proto",
            "x-forwarded-prefix", "x-forwarded-ssl", "x-forwarded-for");

    /**
     * header名が転送headerのどれかなら、その小文字の正規名を返す（該当しなければnull）。
     *
     * <p>Springは {@code String#equalsIgnoreCase} でも照合する（{@code ForwardedPrefixExtractor}）。
     * {@code toLowerCase} してからの集合照合だと、U+017F（long s）や U+212A（Kelvin sign）を含む名前が
     * 「Springには同じ名前、清浄化には別の名前」に見えるため、照合もequalsIgnoreCaseにそろえる
     * （TomcatはISO-8859-1外・token外の名前を拒否するので実際には届かないが、判定基準を一致させておく）。
     */
    static String canonicalForwardedName(String name) {
        if (name == null) {
            return null;
        }
        for (String canonical : FORWARDED_HEADER_NAMES) {
            if (canonical.equalsIgnoreCase(name)) {
                return canonical;
            }
        }
        return null;
    }

    private final ClientIpResolver clientIpResolver;

    public ForwardedHeaderSanitizingFilter(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver, "clientIpResolver");
    }

    /** 転送ヘッダーが無いrequestでもclient IPのattributeを置くため、常に処理する。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String connectorPeer = request.getRemoteAddr();
        String normalizedPeer = IpCidrMatcher.normalizeIp(connectorPeer == null ? null : connectorPeer.trim());
        boolean trustedPeer = normalizedPeer != null && clientIpResolver.isTrustedProxy(normalizedPeer);

        Map<String, String> retained = new LinkedHashMap<>();
        if (trustedPeer) {
            String forwardedFor = ClientIpResolver.rightmostHop(request.getHeaders(X_FORWARDED_FOR));
            if (forwardedFor != null) {
                retained.put(X_FORWARDED_FOR.toLowerCase(Locale.ROOT), forwardedFor);
            }
            String proto = proto(ClientIpResolver.rightmostToken(request.getHeaders(X_FORWARDED_PROTO)));
            if (proto != null) {
                retained.put(X_FORWARDED_PROTO.toLowerCase(Locale.ROOT), proto);
            }
            String port = port(ClientIpResolver.rightmostToken(request.getHeaders(X_FORWARDED_PORT)));
            if (port != null) {
                retained.put(X_FORWARDED_PORT.toLowerCase(Locale.ROOT), port);
            }
        }

        // ASYNCディスパッチでは反映済みのrequest（remoteAddr＝client IP）で再入するため、REQUEST時に置いた
        // TCP peer・client IPを上書きしない（P3-9）。ERRORディスパッチは同じrequestの同じ値になる。
        if (request.getAttribute(ClientIpResolver.CONNECTOR_PEER_ATTRIBUTE) == null) {
            request.setAttribute(ClientIpResolver.CONNECTOR_PEER_ATTRIBUTE, connectorPeer);
        }
        if (request.getAttribute(ClientIpResolver.CLIENT_IP_ATTRIBUTE) == null) {
            request.setAttribute(ClientIpResolver.CLIENT_IP_ATTRIBUTE,
                    clientIpResolver.resolveFromConnection(connectorPeer,
                            trustedPeer ? request.getHeaders(X_FORWARDED_FOR) : null));
        }

        List<String> dropped = droppedHeaderNames(request, retained);
        if (!dropped.isEmpty() && log.isDebugEnabled()) {
            // 値はclientが書ける原文のためlogへ出さない（名前だけ）。
            log.debug("[転送ヘッダー] 信頼しない転送ヘッダーを削除しました: headers={}, trustedPeer={}", dropped, trustedPeer);
        }
        if (retained.isEmpty()) {
            filterChain.doFilter(dropped.isEmpty() ? request : new SanitizedRequest(request, retained), response);
            return;
        }
        super.doFilterInternal(new SanitizedRequest(request, retained), response, filterChain);
    }

    /** 受信したが清浄化後に残らない転送header名（小文字）。残すheaderも値は右端1要素へ絞られている。 */
    private static List<String> droppedHeaderNames(HttpServletRequest request, Map<String, String> retained) {
        List<String> dropped = new ArrayList<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return dropped;
        }
        while (names.hasMoreElements()) {
            String canonical = canonicalForwardedName(names.nextElement());
            if (canonical != null && !retained.containsKey(canonical) && !dropped.contains(canonical)) {
                dropped.add(canonical);
            }
        }
        return dropped;
    }

    private static String proto(String value) {
        if (value == null) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return "https".equals(lower) || "http".equals(lower) ? lower : null;
    }

    private static String port(String value) {
        if (value == null || !value.matches("[0-9]{1,5}")) {
            return null;
        }
        int port = Integer.parseInt(value);
        return port >= 1 && port <= 65535 ? String.valueOf(port) : null;
    }

    /**
     * 転送headerを清浄化後の値だけに見せるwrapper。{@link #FORWARDED_HEADER_NAMES} のうちretainedに無いものは
     * 存在しないものとして扱い、retainedのものは清浄化後の1値だけを返す。header名の大小文字は区別しない。
     */
    static final class SanitizedRequest extends HttpServletRequestWrapper {

        private final Map<String, String> retained;

        SanitizedRequest(HttpServletRequest request, Map<String, String> retained) {
            super(request);
            this.retained = Map.copyOf(retained);
        }

        @Override
        public String getHeader(String name) {
            String canonical = canonicalForwardedName(name);
            if (canonical != null) {
                return retained.get(canonical);
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String canonical = canonicalForwardedName(name);
            if (canonical != null) {
                String value = retained.get(canonical);
                return value == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(value));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>();
            Enumeration<String> original = super.getHeaderNames();
            while (original != null && original.hasMoreElements()) {
                String name = original.nextElement();
                if (canonicalForwardedName(name) == null) {
                    names.add(name);
                }
            }
            names.addAll(retained.keySet());
            return Collections.enumeration(names);
        }

        @Override
        public int getIntHeader(String name) {
            String canonical = canonicalForwardedName(name);
            if (canonical != null) {
                String value = retained.get(canonical);
                return value == null ? -1 : Integer.parseInt(value);
            }
            return super.getIntHeader(name);
        }

        @Override
        public long getDateHeader(String name) {
            if (canonicalForwardedName(name) != null) {
                // 転送headerは日付値を持たない。
                return -1L;
            }
            return super.getDateHeader(name);
        }
    }
}
