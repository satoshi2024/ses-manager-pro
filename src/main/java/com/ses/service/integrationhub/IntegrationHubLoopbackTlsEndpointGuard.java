package com.ses.service.integrationhub;

import com.ses.config.integrationhub.ExternalApiCidrMatcher;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.SocketAddress;
import javax.net.ssl.SSLSession;
import java.util.List;

/** 本機TLS fixture専用の宛先検査。Spring beanとして登録しない。 */
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
        if (uri == null || uri.toString().length() > 512 || uri.getUserInfo() != null
                || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getPort() < 1
                || uri.getPort() > 65535 || !allowedPorts.contains(uri.getPort())
                || hasTraversal(uri.getRawPath())) {
            throw new IllegalArgumentException("loopback TLS endpoint is not allow-listed");
        }
        String host = stripBrackets(uri.getHost());
        if (!"127.0.0.1".equals(host) && !"::1".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("loopback TLS endpoint must use an exact IP literal");
        }
        InetAddress address = ExternalApiCidrMatcher.parseLiteral(host);
        if (!address.isLoopbackAddress()) {
            throw new IllegalArgumentException("loopback TLS address is not loopback");
        }
        return new Target(uri, host, uri.getPort(), address);
    }

    void validatePeer(Target target, InetAddress peer, int port) {
        if (target == null || peer == null || !target.address().equals(peer)
                || !peer.isLoopbackAddress() || port != target.port() || !allowedPorts.contains(port)) {
            throw new IllegalArgumentException("loopback TLS peer is not allow-listed");
        }
    }

    Target validateTlsSessionPeer(String peerHost, int peerPort) {
        if (peerHost == null || peerPort < 1 || peerPort > 65535) {
            throw new IllegalArgumentException("loopback TLS peer metadata is invalid");
        }
        String host = stripBrackets(peerHost);
        InetAddress peer = ExternalApiCidrMatcher.parseLiteral(host);
        Target peerTarget = validate(URI.create("https://" + bracketIfIpv6(host) + ":" + peerPort + "/"));
        validatePeer(peerTarget, peer, peerPort);
        return peerTarget;
    }

    void validateTlsSessionPeer(Target expected, SSLSession session) {
        if (expected == null || session == null) {
            throw new IllegalArgumentException("TLS session or expected endpoint is missing");
        }
        Target actual = validateTlsSessionPeer(session.getPeerHost(), session.getPeerPort());
        validatePeer(expected, actual.address(), actual.port());
    }

    void validateConnectedPeer(Target expected, SocketAddress remoteAddress) {
        if (!(remoteAddress instanceof InetSocketAddress actual) || actual.isUnresolved()
                || actual.getAddress() == null) {
            throw new IllegalArgumentException("connected peer address is missing or unresolved");
        }
        validatePeer(expected, actual.getAddress(), actual.getPort());
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
            String lower = segment.toLowerCase(java.util.Locale.ROOT);
            if (".".equals(segment) || "..".equals(segment) || lower.contains("%2e")
                    || lower.contains("%2f") || segment.indexOf('\\') >= 0) return true;
        }
        return false;
    }

    record Target(URI uri, String host, int port, InetAddress address) { }
}
