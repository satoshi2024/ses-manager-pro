package com.ses.common.security;

import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 外向きHTTPリクエストの宛先URLを検証する共有ヘルパー（SSRF対策）。
 * <p>
 * 2種類の検証モードを提供する。
 * <ul>
 *   <li>{@link #validatePublicHttpsUrl(String)} — 任意の公開HTTPS宛先を許可しつつ、
 *       内部・予約済みネットワークへの到達を拒否する（Webhook等、宛先が運用時に変更される用途向け）。</li>
 *   <li>{@link #validateExactHostHttpsUrl(String, Set)} — ホスト名の完全一致allowlistで
 *       固定の外部APIのみを許可する（Gemini等の既知エンドポイント向け）。</li>
 * </ul>
 *
 * <h3>DNSリバインディング対策</h3>
 * URL文字列の妥当性検証だけでなく、ホスト名を実際にDNS解決し、
 * A/AAAAレコードで返る<strong>全アドレス</strong>を危険判定にかける。
 * いずれか1つでも内部・予約域に該当すれば拒否する（fail-closed）。
 * 保存時と送信時の双方で検証する。
 * <p>
 * ただし「先に解決して安全判定 → HTTPクライアントが独立に再解決」だけでは
 * 判定と接続の間にDNSが差し替わる余地が残る。Webhook送信境界では
 * {@link #validateAndResolvePublicHttpsUrl(String)} が返したアドレスへ
 * <strong>IPピン留め接続</strong>し、Host/SNIは元ホスト名を使うこと
 * （{@code PinningNoRedirectClientHttpRequestFactory}）。
 * AI等の固定allowlist宛先もリダイレクト非追跡とし、送信直前に本ガードを再実行する。
 */
@Component
public class OutboundUrlGuard {

    /** HTTPSの既定ポート。URIにポート指定が無い場合の暗黙値。 */
    private static final int HTTPS_DEFAULT_PORT = 443;

    /**
     * 公開HTTPS宛先を検証する（内部・予約域は拒否）。
     * Webhook等、宛先URLが運用時に管理者設定で変わりうる用途に使う。
     *
     * @param url 検証対象URL
     * @throws OutboundUrlException 検証に失敗した場合（安全でない宛先）
     */
    public void validatePublicHttpsUrl(String url) {
        validateAndResolvePublicHttpsUrl(url);
    }

    /**
     * 公開HTTPS宛先を検証し、安全と判定した解決済みアドレス一覧を返す。
     * 送信境界ではこの戻り値のIPへ直接接続し、クライアント側の再DNS解決を避けること。
     *
     * @param url 検証対象URL
     * @return 危険判定を通過した A/AAAA アドレス（不変リスト、空にならない）
     * @throws OutboundUrlException 検証に失敗した場合（安全でない宛先）
     */
    public List<InetAddress> validateAndResolvePublicHttpsUrl(String url) {
        URI uri = parseHttpsUri(url);
        String host = normalizeHost(uri.getHost());
        requireDefaultOrHttpsPort(uri);

        InetAddress[] addresses = resolveAll(host);
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                // 解決済みIPそのものはログ/例外に含めず、ホスト名のみで理由を示す。
                throw new OutboundUrlException("宛先ホストが内部・予約済みネットワークへ解決されるため拒否しました: host=" + host);
            }
        }
        return Collections.unmodifiableList(Arrays.asList(addresses));
    }

    /**
     * ホスト名の完全一致allowlistでHTTPS宛先を検証する。
     * 既知の外部API（Gemini等）のみを許可し、サブドメイン詐称・IPリテラル・別名を拒否する。
     *
     * @param url          検証対象URL
     * @param allowedHosts 許可するホスト名（完全一致、大文字小文字は無視）
     * @throws OutboundUrlException 検証に失敗した場合
     */
    public void validateExactHostHttpsUrl(String url, Set<String> allowedHosts) {
        URI uri = parseHttpsUri(url);
        String host = normalizeHost(uri.getHost());
        // IPリテラル（IPv4/IPv6）はallowlistのホスト名と一致しないため実質拒否されるが、
        // 明示的にも弾いて意図を明確にする。
        if (isIpLiteral(host)) {
            throw new OutboundUrlException("IPアドレス直接指定は許可されていません");
        }
        boolean allowed = allowedHosts.stream()
                .map(this::normalizeHost)
                .anyMatch(host::equals);
        if (!allowed) {
            throw new OutboundUrlException("許可されていない宛先ホストです: host=" + host);
        }
        // allowlist方式ではポートも443固定を要求する（-1=未指定は既定443として許可）。
        int port = uri.getPort();
        if (port != -1 && port != HTTPS_DEFAULT_PORT) {
            throw new OutboundUrlException("許可されていないポートです: port=" + port);
        }
    }

    /**
     * HTTPSのURIとして基本検証を行い、URIを返す。
     * scheme=https / userinfo無し / host有り を満たさなければ例外。
     */
    private URI parseHttpsUri(String url) {
        if (url == null || url.isBlank()) {
            throw new OutboundUrlException("URLが空です");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new OutboundUrlException("URLの形式が不正です", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new OutboundUrlException("HTTPS以外のURLは許可されていません");
        }
        if (uri.getUserInfo() != null) {
            throw new OutboundUrlException("認証情報（userinfo）を含むURLは許可されていません");
        }
        // getRawAuthority に '@' が残る（getUserInfoで拾えない）ケースも念のため拒否。
        String authority = uri.getRawAuthority();
        if (authority != null && authority.indexOf('@') >= 0) {
            throw new OutboundUrlException("認証情報（userinfo）を含むURLは許可されていません");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new OutboundUrlException("ホスト名が不正です");
        }
        return uri;
    }

    private void requireDefaultOrHttpsPort(URI uri) {
        int port = uri.getPort();
        if (port != -1 && port != HTTPS_DEFAULT_PORT) {
            // 既定外ポートは明示的に許可されていない限り拒否する（推奨は443のみ）。
            throw new OutboundUrlException("許可されていないポートです: port=" + port);
        }
    }

    private InetAddress[] resolveAll(String host) {
        try {
            InetAddress[] addresses = resolve(host);
            if (addresses == null || addresses.length == 0) {
                throw new OutboundUrlException("ホスト名を解決できませんでした: host=" + host);
            }
            return addresses;
        } catch (UnknownHostException e) {
            // 解決不能は「安全と確認できない」ためfail-closedで拒否する。
            throw new OutboundUrlException("ホスト名を解決できませんでした: host=" + host, e);
        }
    }

    /**
     * ホスト名をA/AAAAへ解決する。テストではこのメソッドを差し替えて
     * 複数レコード（multi-A）解決の挙動を検証できる（DNSへの実依存を避ける seam）。
     */
    protected InetAddress[] resolve(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    private String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        // 末尾ドット（FQDN絶対表記）や角括弧（IPv6リテラル）を除去して比較を安定させる。
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h;
    }

    private boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        // IPv6は':'を含む。IPv4は全ラベルが数字。
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return host.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.');
    }

    /**
     * 解決済みアドレスが内部・予約済み・非グローバルネットワークに該当するか判定する。
     * loopback / unspecified / link-local / private(site-local) / multicast に加え、
     * CGNAT(100.64/10) / IPv6 ULA(fc00::/7) / IPv4-mapped IPv6 / 予約域(240/4) /
     * IETF ベンチマーク(198.18/15) / TEST-NET(192.0.2/24, 198.51.100/24, 203.0.113/24) /
     * IPv6 documentation(2001:db8::/32) も拒否する（REV-B2-P1-001）。
     */
    boolean isBlockedAddress(InetAddress address) {
        if (address.isLoopbackAddress()       // 127.0.0.0/8, ::1
                || address.isAnyLocalAddress() // 0.0.0.0, ::
                || address.isLinkLocalAddress()// 169.254.0.0/16, fe80::/10
                || address.isSiteLocalAddress()// 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress()) { // 224.0.0.0/4, ff00::/8
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int o0 = b[0] & 0xff;
            int o1 = b[1] & 0xff;
            if (o0 == 0) {
                return true; // 0.0.0.0/8
            }
            if (o0 == 100 && o1 >= 64 && o1 <= 127) {
                return true; // CGNAT 100.64.0.0/10
            }
            if (o0 == 198 && (o1 == 18 || o1 == 19)) {
                return true; // IETF Benchmarking 198.18.0.0/15
            }
            if (o0 == 192 && o1 == 0 && (b[2] & 0xff) == 2) {
                return true; // TEST-NET-1 192.0.2.0/24
            }
            if (o0 == 198 && o1 == 51 && (b[2] & 0xff) == 100) {
                return true; // TEST-NET-2 198.51.100.0/24
            }
            if (o0 == 203 && o1 == 0 && (b[2] & 0xff) == 113) {
                return true; // TEST-NET-3 203.0.113.0/24
            }
            if (o0 == 192 && o1 == 88 && (b[2] & 0xff) == 99) {
                return true; // 6to4 relay anycast 192.88.99.0/24（deprecated）
            }
            if (o0 >= 240) {
                return true; // 240.0.0.0/4（予約・limited broadcast 255.255.255.255含む）
            }
            return false;
        }
        if (address instanceof Inet6Address) {
            // ULA fc00::/7（fc00::/8, fd00::/8）
            if ((b[0] & 0xfe) == 0xfc) {
                return true;
            }
            // Documentation 2001:db8::/32
            if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01
                    && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
                return true;
            }
            // IPv4-mapped IPv6 ::ffff:0:0/96 （先頭10バイト0, 11-12バイト目0xff）
            boolean mappedPrefix = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    mappedPrefix = false;
                    break;
                }
            }
            if (mappedPrefix && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                // 埋め込み IPv4 も同じ非グローバル判定にかける
                try {
                    byte[] v4 = new byte[]{b[12], b[13], b[14], b[15]};
                    if (isBlockedAddress(InetAddress.getByAddress(v4))) {
                        return true;
                    }
                } catch (UnknownHostException ignored) {
                    return true;
                }
                return true; // mapped 自体も拒否（従来どおり）
            }
            // IPv4-compatible IPv6 ::a.b.c.d（非推奨だが内部詐称に使われうる）
            Inet6Address v6 = (Inet6Address) address;
            if (v6.isIPv4CompatibleAddress()) {
                return true;
            }
            return false;
        }
        // 未知のアドレス種別はfail-closed。
        return true;
    }
}
