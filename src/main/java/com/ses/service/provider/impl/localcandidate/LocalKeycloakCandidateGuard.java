package com.ses.service.provider.impl.localcandidate;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** 明示的に許可された単一loopback fixture endpointの検査。 */
final class LocalKeycloakCandidateGuard {
    private LocalKeycloakCandidateGuard() { }

    static Target target(URI origin, FixtureAllowlist allowlist) {
        if (origin == null || !"http".equalsIgnoreCase(origin.getScheme())
                || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                || origin.getRawFragment() != null || origin.getPort() < 1 || origin.getPort() > 65535
                || (origin.getRawPath() != null && !origin.getRawPath().isEmpty()
                    && !"/".equals(origin.getRawPath()))) {
            throw new IllegalArgumentException("endpoint rejected");
        }
        String host = origin.getHost();
        if (host == null) throw new IllegalArgumentException("literal loopback required");
        host = host.toLowerCase(Locale.ROOT);
        final InetAddress address;
        try {
            if ("127.0.0.1".equals(host)) {
                address = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
            } else if ("[::1]".equals(host) || "::1".equals(host)) {
                address = InetAddress.getByAddress(new byte[] {0, 0, 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 1});
            } else {
                throw new IllegalArgumentException("literal loopback required");
            }
        } catch (java.net.UnknownHostException impossible) {
            throw new IllegalArgumentException("loopback address rejected", impossible);
        }
        if (!address.isLoopbackAddress()) throw new IllegalArgumentException("loopback address rejected");
        InetSocketAddress peer = new InetSocketAddress(address, origin.getPort());
        if (allowlist == null || !allowlist.peers().contains(peer)) {
            throw new IllegalArgumentException("fixture endpoint is not allowlisted");
        }
        return new Target(origin, peer);
    }

    static void validatePathSegment(String value, int maxUtf8Bytes, String name) {
        if (value == null || value.isEmpty() || ".".equals(value) || "..".equals(value)
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || containsEncodedSeparator(value) || containsControl(value)) {
            throw new IllegalArgumentException(name + " rejected");
        }
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            if (bytes.remaining() > maxUtf8Bytes) throw new IllegalArgumentException(name + " too long");
        } catch (CharacterCodingException invalidUnicode) {
            throw new IllegalArgumentException(name + " invalid Unicode", invalidUnicode);
        }
    }

    static String encodePathSegment(String value) {
        validatePathSegment(value, 256, "path segment");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length * 3);
        for (byte item : bytes) {
            int c = item & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%');
                encoded.append("0123456789ABCDEF".charAt(c >>> 4));
                encoded.append("0123456789ABCDEF".charAt(c & 0x0f));
            }
        }
        return encoded.toString();
    }

    private static boolean containsEncodedSeparator(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("%2f") || lower.contains("%5c") || lower.contains("%2e");
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(c -> c < 0x20 || (c >= 0x7f && c <= 0x9f));
    }

    record Target(URI origin, InetSocketAddress peer) {
        Target {
            if (origin == null || peer == null || peer.isUnresolved()) {
                throw new IllegalArgumentException("resolved fixture peer required");
            }
        }

        boolean matches(InetSocketAddress actual) {
            return actual != null && !actual.isUnresolved()
                    && actual.getPort() == peer.getPort()
                    && actual.getAddress().equals(peer.getAddress());
        }
    }

    record FixtureAllowlist(Set<InetSocketAddress> peers) {
        FixtureAllowlist {
            peers = Set.copyOf(peers);
            if (peers.isEmpty() || peers.size() > 2 || peers.stream().anyMatch(peer -> peer == null
                    || peer.isUnresolved() || peer.getPort() < 1 || peer.getPort() > 65535
                    || !peer.getAddress().isLoopbackAddress())) {
                throw new IllegalArgumentException("fixture allowlist rejected");
            }
        }
    }
}
