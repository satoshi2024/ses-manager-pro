package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import org.springframework.stereotype.Component;
import com.ses.common.security.NonGlobalAddressPolicy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 承認済みFQDN、公開IP、接続済みpeerを同じ規則で検査する。DNSは上限付き専用poolで実行する。 */
@Component
public final class IntegrationHubHttpsEndpointGuard implements AutoCloseable {
    private static final Pattern DESTINATION = Pattern.compile(
            "(?=.{1,253}:)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?:([0-9]{1,5})");
    private final Set<String> allowed;
    private final int dnsTimeoutMs;
    private final Map<String, List<InetAddress>> pinned = new ConcurrentHashMap<>();
    private final Map<String, Map<InetAddress, java.time.Instant>> recent = new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired private java.time.Clock clock = java.time.Clock.systemUTC();
    private final ThreadPoolExecutor resolver = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "integration-hub-dns");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public IntegrationHubHttpsEndpointGuard(IntegrationHubExternalApiProperties properties) {
        this.allowed = Set.copyOf(properties.getExternalTransport().getApproval().getAllowedDestinations());
        this.dnsTimeoutMs = properties.getExternalTransport().getDnsTimeoutMs();
    }

    public static boolean validDestination(String destination) {
        if (destination == null || !DESTINATION.matcher(destination).matches()) return false;
        String portText = destination.substring(destination.lastIndexOf(':') + 1);
        if (portText.length() > 1 && portText.startsWith("0")) return false;
        if (destination.substring(0, destination.lastIndexOf(':')).endsWith(".localhost")) return false;
        int port = Integer.parseInt(portText);
        return port >= 1 && port <= 65535;
    }

    /** subscription登録と送信の両方から呼ぶ。全DNS回答を検査し、混在回答も拒否する。 */
    public Target validate(URI uri) {
        Target target = syntax(uri);
        Future<InetAddress[]> answer = null;
        try {
            answer = resolver.submit(() -> InetAddress.getAllByName(target.host()));
            InetAddress[] addresses = answer.get(dnsTimeoutMs, TimeUnit.MILLISECONDS);
            if (addresses.length == 0 || addresses.length > 32
                    || Arrays.stream(addresses).anyMatch(address -> !isPublicAddress(address))) {
                throw new IllegalArgumentException("HTTPS送信先のDNS回答を許可できません");
            }
            remember(target.host(), addresses);
            return target;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResolutionUnavailable();
        } catch (IllegalArgumentException forbidden) {
            throw forbidden;
        } catch (Exception rejected) {
            throw new ResolutionUnavailable();
        } finally {
            if (answer != null) answer.cancel(true);
        }
    }

    private synchronized void remember(String host, InetAddress[] addresses) {
        var now = clock.instant();
        Map<InetAddress, java.time.Instant> union = new java.util.HashMap<>();
        recent.getOrDefault(host, Map.of()).forEach((address, expiry) -> { if (expiry.isAfter(now)) union.put(address, expiry); });
        for (InetAddress address : addresses) union.put(address, now.plusSeconds(120));
        if (union.size() > 64) throw new IllegalArgumentException("検証済みDNS回答の一時集合が上限を超えています");
        recent.put(host, Map.copyOf(union));
        pinned.put(host, List.copyOf(Arrays.asList(addresses)));
    }

    /** HC5はOSの再解決をせず、送信直前に検査した公開IPだけに接続する。 */
    InetAddress[] pinnedAddresses(String host) throws java.net.UnknownHostException {
        List<InetAddress> addresses = pinned.get(host);
        if (addresses == null || addresses.isEmpty()) throw new java.net.UnknownHostException("未検証の送信先です");
        return addresses.toArray(InetAddress[]::new);
    }

    Target validateNamedEndpoint(String host, int port) {
        return syntax(URI.create("https://" + host + ":" + port + "/"));
    }

    void validateConnectedPeer(Target expected, SocketAddress remote) {
        if (!(remote instanceof InetSocketAddress actual) || actual.isUnresolved()
                || actual.getPort() != expected.port() || !isPublicAddress(actual.getAddress())
                || !recent.getOrDefault(expected.host(), Map.of()).getOrDefault(actual.getAddress(), java.time.Instant.MIN).isAfter(clock.instant())) {
            throw new IllegalArgumentException("接続済みpeerが検証済みDNS回答と一致しません");
        }
    }

    private Target syntax(URI uri) {
        if (uri == null || uri.toString().length() > 512 || !"https".equals(uri.getScheme())
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !validDestination(uri.getHost() + ":" + uri.getPort())
                || !allowed.contains(uri.getHost() + ":" + uri.getPort()) || hasTraversal(uri.getRawPath())) {
            throw new IllegalArgumentException("HTTPS送信先が承認一覧と一致しません");
        }
        return new Target(uri, uri.getHost(), uri.getPort());
    }

    /** DB transaction中の再検査用。名前解決は呼出し前のvalidateで完了している。 */
    public void validateApprovedSyntax(URI uri) {
        syntax(uri);
        if (!pinned.containsKey(uri.getHost())) throw new IllegalArgumentException("名前解決が未検証です");
    }

    private static boolean hasTraversal(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")
                || path.contains("\\") || path.contains(";")
                || Arrays.stream(path.split("/", -1)).anyMatch(part -> ".".equals(part) || "..".equals(part));
    }

    /** RFC1918/特殊用途/metadata/IPv4変換トンネルは許可しない。IPv6はglobal unicastだけに限定する。 */
    static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && (bytes[0] & 0xe0) != 0x20) return false;
        // Azure platform virtual IPはIANA特殊用途表の対象外だがmetadataと同様にegressを拒否する。
        boolean azurePlatform = bytes.length == 4 && (bytes[0] & 255) == 168 && (bytes[1] & 255) == 63
                && (bytes[2] & 255) == 129 && (bytes[3] & 255) == 16;
        return !azurePlatform && !NonGlobalAddressPolicy.isNonGlobal(address);
    }

    @Override
    public void close() {
        resolver.shutdownNow();
        try {
            resolver.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        pinned.clear();
        recent.clear();
    }

    static final class ResolutionUnavailable extends RuntimeException {
        ResolutionUnavailable() { super("HTTPS送信先の名前解決を完了できません"); }
    }

    record Target(URI uri, String host, int port) { }
}
