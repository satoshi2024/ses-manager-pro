package com.ses.service.ai.impl;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** ネットワークDNSを行わず、事前承認済みで期限付きのaddress snapshotだけを返すresolver。 */
public final class CopilotDnsSnapshotResolver implements DnsResolver {

    private final AtomicReference<DnsSnapshot> snapshot;
    private final Clock clock;

    public CopilotDnsSnapshotResolver(DnsSnapshot snapshot, Clock clock) {
        this.snapshot = new AtomicReference<>(Objects.requireNonNull(snapshot));
        this.clock = Objects.requireNonNull(clock);
    }

    /** 承認されたaddress snapshotを入れ替える。更新処理はこのresolver外で承認・実施する。 */
    public void replaceSnapshot(DnsSnapshot next) {
        snapshot.set(Objects.requireNonNull(next));
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        AddressSet addressSet = requireAddressSet(host);
        return addressSet.addresses().toArray(InetAddress[]::new);
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        requireAddressSet(host);
        return normalize(host);
    }

    boolean resolvesOnlyToLoopback(String host) throws UnknownHostException {
        return requireAddressSet(host).addresses().stream().allMatch(InetAddress::isLoopbackAddress);
    }

    private AddressSet requireAddressSet(String host) throws UnknownHostException {
        String normalized = normalize(host);
        AddressSet set = snapshot.get().hosts().get(normalized);
        if (set == null || set.addresses().isEmpty() || !set.expiresAt().isAfter(clock.instant())) {
            throw new UnknownHostException("COPILOT_DNS_UNAVAILABLE");
        }
        return set;
    }

    private static String normalize(String host) {
        return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    }

    public record DnsSnapshot(Map<String, AddressSet> hosts) {
        public DnsSnapshot {
            hosts = hosts == null ? Map.of() : hosts.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            entry -> normalize(entry.getKey()), Map.Entry::getValue));
        }

        public static DnsSnapshot empty() {
            return new DnsSnapshot(Map.of());
        }
    }

    public record AddressSet(List<InetAddress> addresses, Instant expiresAt) {
        public AddressSet {
            addresses = addresses == null ? List.of() : List.copyOf(addresses);
            Objects.requireNonNull(expiresAt);
        }

        /** address byteから生成し、正引き・逆引きlookupを行わない。 */
        public static AddressSet fromAddressBytes(String host, List<byte[]> addressBytes, Instant expiresAt)
                throws UnknownHostException {
            if (addressBytes == null || addressBytes.isEmpty()) {
                return new AddressSet(List.of(), expiresAt);
            }
            List<InetAddress> addresses = addressBytes.stream().map(bytes -> {
                try {
                    if (bytes == null || (bytes.length != 4 && bytes.length != 16)) {
                        throw new IllegalArgumentException("DNS snapshot address must be 4 or 16 bytes");
                    }
                    return InetAddress.getByAddress(host, bytes.clone());
                } catch (UnknownHostException ex) {
                    throw new IllegalArgumentException("invalid DNS snapshot address", ex);
                }
            }).toList();
            return new AddressSet(addresses, expiresAt);
        }
    }
}
