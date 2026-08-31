package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/** LOOPBACK transport専用のDNSなし宛先検証。 */
public final class IntegrationHubLoopbackEndpointGuard {
    private final List<Integer> allowedPorts;

    public IntegrationHubLoopbackEndpointGuard(IntegrationHubExternalApiProperties properties) {
        if (properties == null || properties.getSecurity() == null
                || properties.getSecurity().getAllowedLoopbackPorts() == null) {
            throw new IllegalArgumentException("loopback allow-list is missing");
        }
        this.allowedPorts = List.copyOf(properties.getSecurity().getAllowedLoopbackPorts());
    }

    public URI validate(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank() || endpointUrl.length() > 512) {
            throw new IllegalArgumentException("invalid loopback endpoint");
        }
        final URI uri;
        try {
            uri = new URI(endpointUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid loopback endpoint", e);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPort() <= 0 || !allowedPorts.contains(uri.getPort())
                || !isLiteralLoopback(uri.getHost()) || hasTraversal(uri.getRawPath())) {
            throw new IllegalArgumentException("loopback endpoint is not allow-listed");
        }
        return uri;
    }

    public void validatePeer(InetAddress peer, int port) {
        if (peer == null || !peer.isLoopbackAddress() || !allowedPorts.contains(port)) {
            throw new IllegalArgumentException("loopback peer is not allow-listed");
        }
    }

    private boolean isLiteralLoopback(String host) {
        String normalized = host == null ? "" : host.replace("[", "").replace("]", "");
        return "127.0.0.1".equals(normalized) || "::1".equalsIgnoreCase(normalized);
    }

    private boolean hasTraversal(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }
        for (String segment : rawPath.split("/", -1)) {
            if ("..".equals(segment) || ".".equals(segment) || segment.contains("%2e")
                    || segment.contains("%2E")) {
                return true;
            }
        }
        return false;
    }
}
