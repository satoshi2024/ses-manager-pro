package com.ses.common.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Webhook 等の外向き HTTPS 向け: グローバルにルーティング可能なアドレス以外を拒否する
 * 集中メンテナンスの special-use / non-global CIDR 一覧（REV-B2.2-P1-001 / REV-B2.3-P3-001）。
 * <p>
 * IANA IPv4/IPv6 Special-Purpose Address Registry スナップショット日付: <b>2025-10-09</b>
 * （Globally Reachable=False / N/A、Destination=False、Deprecated、マルチキャスト、リンクローカルを拒否）。
 * {@code 64:ff9b::/96} は Registry 上 Globally Reachable=True だが、埋め込み IPv4 への SSRF を
 * fail-closed で塞ぐため拒否する（::ffff:0:0/96 と同様の二重防衛方針）。
 * <p>
 * {@code 2001::/23}（IETF Protocol Assignments）は <b>fail-closed</b>：当該 /23 内は原則拒否し、
 * IANA 上 Globally Reachable=True の carve-out のみ最長一致で許可する（REV-B2.3-P3-001）。
 * 未知のアドレス長は fail-closed。
 */
public final class NonGlobalAddressPolicy {

    /** 本ポリシーが参照した IANA Special-Purpose Registry の最終更新日（コメントとテストで共有）。 */
    public static final String IANA_REGISTRY_SNAPSHOT_DATE = "2025-10-09";

    private static final List<Cidr> IPV4_BLOCKS;
    private static final List<Cidr> IPV6_BLOCKS;
    /** 2001::/23 親プレフィックス（deny 後の carve-out 判定に使用）。 */
    private static final Cidr IETF_PROTOCOL_ASSIGNMENTS_2001;
    /**
     * 2001::/23 内の Globally Reachable=True carve-out（IANA 2025-10-09）。
     * 最長プレフィックス一致で判定するため prefixLength 降順に保持する。
     */
    private static final List<Cidr> IPV6_ALLOW_WITHIN_2001_SLASH23;

    static {
        List<Cidr> v4 = new ArrayList<>();
        // --- IANA IPv4 Special-Purpose (snapshot 2025-10-09): Globally Reachable False / Destination False ---
        v4.add(Cidr.parse("0.0.0.0/8"));          // This network
        v4.add(Cidr.parse("10.0.0.0/8"));         // Private-Use
        v4.add(Cidr.parse("100.64.0.0/10"));      // Shared Address Space (CGNAT)
        v4.add(Cidr.parse("127.0.0.0/8"));        // Loopback
        v4.add(Cidr.parse("169.254.0.0/16"));     // Link Local
        v4.add(Cidr.parse("172.16.0.0/12"));      // Private-Use
        v4.add(Cidr.parse("192.0.0.0/24"));       // IETF Protocol Assignments（より具体的な True も fail-closed で包含）
        v4.add(Cidr.parse("192.0.2.0/24"));       // TEST-NET-1
        v4.add(Cidr.parse("192.88.99.0/24"));     // Deprecated 6to4 Relay Anycast
        v4.add(Cidr.parse("192.168.0.0/16"));     // Private-Use
        v4.add(Cidr.parse("198.18.0.0/15"));      // Benchmarking
        v4.add(Cidr.parse("198.51.100.0/24"));    // TEST-NET-2
        v4.add(Cidr.parse("203.0.113.0/24"));     // TEST-NET-3
        v4.add(Cidr.parse("240.0.0.0/4"));        // Reserved（Limited Broadcast 含む）
        // Registry 外だが非グローバル: マルチキャスト
        v4.add(Cidr.parse("224.0.0.0/4"));
        IPV4_BLOCKS = Collections.unmodifiableList(v4);

        List<Cidr> v6 = new ArrayList<>();
        // --- IANA IPv6 Special-Purpose (snapshot 2025-10-09) ---
        v6.add(Cidr.parse("::/128"));            // Unspecified
        v6.add(Cidr.parse("::1/128"));           // Loopback
        v6.add(Cidr.parse("::ffff:0:0/96"));    // IPv4-mapped（埋め込み IPv4 は再判定）
        v6.add(Cidr.parse("64:ff9b::/96"));     // NAT64 WKP（SSRF harden）
        v6.add(Cidr.parse("64:ff9b:1::/48"));   // NAT64 local-use
        v6.add(Cidr.parse("100::/64"));         // Discard-Only
        v6.add(Cidr.parse("100:0:0:1::/64"));   // Dummy IPv6 Prefix (RFC9780)
        // 2001::/23: fail-closed（Teredo N/A・Benchmarking False・deprecated ORCHID 等を包含）。
        // Globally Reachable=True の例外のみ IPV6_ALLOW_WITHIN_2001_SLASH23 で許可する。
        v6.add(Cidr.parse("2001::/23"));
        v6.add(Cidr.parse("2001:db8::/32"));    // Documentation（/23 外）
        v6.add(Cidr.parse("2002::/16"));        // 6to4（Globally Reachable N/A）
        v6.add(Cidr.parse("3fff::/20"));        // Documentation (RFC9637)
        v6.add(Cidr.parse("5f00::/16"));        // SRv6 SIDs (RFC9602)
        v6.add(Cidr.parse("fc00::/7"));         // Unique-Local
        v6.add(Cidr.parse("fe80::/10"));        // Link-Local Unicast
        v6.add(Cidr.parse("ff00::/8"));         // Multicast（Registry 外）
        IPV6_BLOCKS = Collections.unmodifiableList(v6);

        IETF_PROTOCOL_ASSIGNMENTS_2001 = Cidr.parse("2001::/23");

        // IANA 2025-10-09: 2001::/23 内の Globally Reachable=True carve-out
        List<Cidr> allow = new ArrayList<>();
        allow.add(Cidr.parse("2001:1::1/128"));   // Port Control Protocol Anycast
        allow.add(Cidr.parse("2001:1::2/128"));   // TURN Anycast
        allow.add(Cidr.parse("2001:1::3/128"));   // DNS-SD SRP Anycast
        allow.add(Cidr.parse("2001:3::/32"));     // AMT
        allow.add(Cidr.parse("2001:4:112::/48")); // AS112-v6
        allow.add(Cidr.parse("2001:20::/28"));    // ORCHIDv2（True）
        allow.add(Cidr.parse("2001:30::/28"));    // Drone Remote ID（True）
        // 最長一致: より具体的なプレフィックスを先に評価する
        allow.sort(Comparator.comparingInt(Cidr::prefixLength).reversed());
        IPV6_ALLOW_WITHIN_2001_SLASH23 = Collections.unmodifiableList(allow);
    }

    private NonGlobalAddressPolicy() {
    }

    /** @return true なら Webhook 宛先として拒否（非グローバル / special-use） */
    public static boolean isNonGlobal(InetAddress address) {
        if (address == null) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address) {
            return matchesAny(IPV4_BLOCKS, raw);
        }
        if (address instanceof Inet6Address) {
            Inet6Address v6 = (Inet6Address) address;
            if (v6.isIPv4CompatibleAddress()) {
                return true;
            }
            if (matchesAny(IPV6_BLOCKS, raw)) {
                // 2001::/23 は fail-closed。列挙した Globally Reachable=True carve-out のみ許可。
                if (IETF_PROTOCOL_ASSIGNMENTS_2001.contains(raw)
                        && matchesAllowWithin2001Slash23(raw)) {
                    return false;
                }
                return true;
            }
            // IPv4-mapped: 埋め込み IPv4 も同じポリシーで再判定（二重防衛）
            if (isIpv4Mapped(raw)) {
                try {
                    byte[] v4 = new byte[]{raw[12], raw[13], raw[14], raw[15]};
                    return isNonGlobal(InetAddress.getByAddress(v4));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    static List<Cidr> ipv4BlocksForTest() {
        return IPV4_BLOCKS;
    }

    static List<Cidr> ipv6BlocksForTest() {
        return IPV6_BLOCKS;
    }

    private static boolean matchesAny(List<Cidr> blocks, byte[] address) {
        for (Cidr block : blocks) {
            if (block.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /** 2001::/23 内 carve-out を最長プレフィックス一致で判定する。 */
    private static boolean matchesAllowWithin2001Slash23(byte[] address) {
        for (Cidr allow : IPV6_ALLOW_WITHIN_2001_SLASH23) {
            if (allow.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIpv4Mapped(byte[] b) {
        if (b.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    /** 不変 CIDR。テストから境界アドレスを生成できるよう package-visible。 */
    static final class Cidr {
        private final byte[] network;
        private final int prefixLength;
        private final String literal;

        private Cidr(byte[] network, int prefixLength, String literal) {
            this.network = network;
            this.prefixLength = prefixLength;
            this.literal = literal;
        }

        static Cidr parse(String cidr) {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid CIDR: " + cidr);
            }
            try {
                String host = parts[0];
                int prefix = Integer.parseInt(parts[1]);
                byte[] network;
                if (host.indexOf(':') >= 0) {
                    InetAddress addr = InetAddress.getByName(host);
                    if (addr instanceof Inet4Address) {
                        byte[] v4 = addr.getAddress();
                        network = new byte[16];
                        network[10] = (byte) 0xff;
                        network[11] = (byte) 0xff;
                        System.arraycopy(v4, 0, network, 12, 4);
                    } else {
                        network = addr.getAddress();
                    }
                } else {
                    network = InetAddress.getByName(host).getAddress();
                }
                int max = network.length * 8;
                if (prefix < 0 || prefix > max) {
                    throw new IllegalArgumentException("invalid prefix: " + cidr);
                }
                return new Cidr(maskNetwork(network, prefix), prefix, cidr);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("invalid CIDR host: " + cidr, e);
            }
        }

        private static byte[] maskNetwork(byte[] address, int prefixLength) {
            byte[] network = address.clone();
            int fullBytes = prefixLength / 8;
            int remBits = prefixLength % 8;
            for (int i = fullBytes + (remBits > 0 ? 1 : 0); i < network.length; i++) {
                network[i] = 0;
            }
            if (remBits > 0 && fullBytes < network.length) {
                int mask = (0xff << (8 - remBits)) & 0xff;
                network[fullBytes] = (byte) (network[fullBytes] & mask);
            }
            return network;
        }

        boolean contains(byte[] address) {
            if (address == null || address.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            if (remBits == 0) {
                return true;
            }
            int mask = (0xff << (8 - remBits)) & 0xff;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        int prefixLength() {
            return prefixLength;
        }

        byte[] network() {
            return network.clone();
        }

        String literal() {
            return literal;
        }

        InetAddress networkAddress() throws UnknownHostException {
            return InetAddress.getByAddress(network.clone());
        }

        InetAddress lastAddress() throws UnknownHostException {
            byte[] end = network.clone();
            int fullBytes = prefixLength / 8;
            int remBits = prefixLength % 8;
            if (remBits > 0 && fullBytes < end.length) {
                int hostMask = (0xff >>> remBits);
                end[fullBytes] = (byte) (end[fullBytes] | hostMask);
                fullBytes++;
            }
            for (int i = fullBytes; i < end.length; i++) {
                end[i] = (byte) 0xff;
            }
            return InetAddress.getByAddress(end);
        }

        InetAddress representativeAddress() throws UnknownHostException {
            byte[] rep = network.clone();
            for (int i = rep.length - 1; i >= 0; i--) {
                int bitPos = i * 8;
                if (bitPos + 7 >= prefixLength) {
                    int localBit = Math.min(7, bitPos + 7 - prefixLength);
                    if (localBit >= 0) {
                        rep[i] = (byte) (rep[i] | 1);
                        return InetAddress.getByAddress(rep);
                    }
                }
            }
            return InetAddress.getByAddress(rep);
        }

        /** プレフィックス直下の次ネットワーク先頭（境界の外側）。ネットワーク番号に 1 を加算する。 */
        InetAddress addressJustOutside() throws UnknownHostException {
            byte[] outside = network.clone();
            if (prefixLength <= 0) {
                return InetAddress.getByAddress(outside);
            }
            for (int bit = prefixLength - 1; bit >= 0; bit--) {
                int byteIndex = bit / 8;
                int bitInByte = 7 - (bit % 8);
                int mask = 1 << bitInByte;
                if ((outside[byteIndex] & mask) == 0) {
                    outside[byteIndex] = (byte) (outside[byteIndex] | mask);
                    return InetAddress.getByAddress(outside);
                }
                outside[byteIndex] = (byte) (outside[byteIndex] & ~mask);
            }
            return InetAddress.getByAddress(outside);
        }
    }
}
