package com.ses.service.integrationhub;

import com.ses.config.integrationhub.ExternalApiCidrMatcher;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 本機TLS fixture専用の宛先検査。Spring beanとして登録しない。
 *
 * <p>許可portは構築時のsnapshotだけを使い、可変なpropertiesを後から読まない。
 * 送信入口のURI検証、HC5 DnsResolverのliteral解決、TLS upgrade時の実socket peer検証の
 * 3箇所がすべてこのsnapshotと同じ規則で判定する。
 */
final class IntegrationHubLoopbackTlsEndpointGuard {
    private final List<Integer> allowedPorts;

    IntegrationHubLoopbackTlsEndpointGuard(IntegrationHubExternalApiProperties properties) {
        if (properties == null || properties.getSecurity() == null
                || properties.getSecurity().getAllowedLoopbackPorts() == null) {
            throw new IllegalArgumentException("loopback allow-list is missing");
        }
        this.allowedPorts = List.copyOf(properties.getSecurity().getAllowedLoopbackPorts());
    }

    Target validate(URI uri) {
        if (uri == null || uri.toString().length() > 512 || uri.getRawUserInfo() != null
                || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !"https".equalsIgnoreCase(uri.getScheme()) || !isAllowedPort(uri.getPort())
                || hasTraversal(uri.getRawPath())) {
            throw new IllegalArgumentException("loopback TLS endpoint is not allow-listed");
        }
        String host = stripBrackets(uri.getHost());
        return new Target(uri, host, uri.getPort(), literalLoopbackAddress(host));
    }

    /**
     * HC5のDnsResolverへ渡されたhost名を、DNSを使わず許可済みloopback literalだけへ解決する。
     * 文字列の完全一致で判定するため、{@code ::ffff:127.0.0.1}や{@code 127.0.0.2}は拒否される。
     */
    InetAddress literalLoopbackAddress(String rawHost) {
        String host = stripBrackets(rawHost);
        if (!"127.0.0.1".equals(host) && !"::1".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("loopback TLS endpoint must use an exact IP literal");
        }
        InetAddress address = ExternalApiCidrMatcher.parseLiteral(host);
        if (!address.isLoopbackAddress()) {
            throw new IllegalArgumentException("loopback TLS address is not loopback");
        }
        return address;
    }

    /** TLS upgrade時にHC5が渡す接続先(NamedEndpoint)を、送信入口と同じ規則で再検証する。 */
    Target validateNamedEndpoint(String hostName, int port) {
        if (hostName == null || !isAllowedPort(port)) {
            throw new IllegalArgumentException("loopback TLS named endpoint is not allow-listed");
        }
        String host = stripBrackets(hostName);
        InetAddress address = literalLoopbackAddress(host);
        return new Target(URI.create("https://" + bracketIfIpv6(host) + ":" + port + "/"), host, port, address);
    }

    void validatePeer(Target target, InetAddress peer, int port) {
        if (target == null || peer == null || !target.address().equals(peer)
                || !peer.isLoopbackAddress() || port != target.port() || !isAllowedPort(port)) {
            throw new IllegalArgumentException("loopback TLS peer is not allow-listed");
        }
    }

    /** 実socketのremote address（kernelが報告する接続相手）を期待宛先と照合する。 */
    void validateConnectedPeer(Target expected, SocketAddress remoteAddress) {
        if (!(remoteAddress instanceof InetSocketAddress actual) || actual.isUnresolved()
                || actual.getAddress() == null) {
            throw new IllegalArgumentException("connected peer address is missing or unresolved");
        }
        validatePeer(expected, actual.getAddress(), actual.getPort());
    }

    private boolean isAllowedPort(int port) {
        return port >= 1 && port <= 65535 && allowedPorts.contains(port);
    }

    private static String stripBrackets(String host) {
        return host != null && host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
    }

    private static String bracketIfIpv6(String host) {
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    private static boolean hasTraversal(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return false;
        for (String segment : rawPath.split("/", -1)) {
            String lower = segment.toLowerCase(Locale.ROOT);
            if (".".equals(segment) || "..".equals(segment) || lower.contains("%2e")
                    || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")
                    || segment.indexOf('\\') >= 0 || segment.indexOf(';') >= 0) return true;
        }
        return false;
    }

    record Target(URI uri, String host, int port, InetAddress address) { }
}
