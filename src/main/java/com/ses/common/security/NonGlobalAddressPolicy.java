package com.ses.common.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Webhook 等の外向き HTTPS 向け: グローバルにルーティング可能なアドレス以外を拒否する
 * 集中メンテナンスの special-use / non-global CIDR 一覧（REV-B2.1-P1-001）。
 * <p>
 * IANA special-purpose / RFC 6890 系および明示指定の IPv6 予約プレフィックスを網羅する。
 * 未知のアドレス長は fail-closed。
 */
public final class NonGlobalAddressPolicy {

    private static final List<Cidr> IPV4_BLOCKS;
    private static final List<Cidr> IPV6_BLOCKS;

    static {
        List<Cidr> v4 = new ArrayList<>();
        // RFC 6890 / IANA IPv4 Special-Purpose
        v4.add(Cidr.parse("0.0.0.0/8"));          // this network
        v4.add(Cidr.parse("10.0.0.0/8"));         // private
        v4.add(Cidr.parse("100.64.0.0/10"));      // CGNAT
        v4.add(Cidr.parse("127.0.0.0/8"));        // loopback
        v4.add(Cidr.parse("169.254.0.0/16"));     // link-local
        v4.add(Cidr.parse("172.16.0.0/12"));      // private
        v4.add(Cidr.parse("192.0.0.0/24"));       // IETF Protocol Assignments
        v4.add(Cidr.parse("192.0.2.0/24"));       // TEST-NET-1
        v4.add(Cidr.parse("192.88.99.0/24"));     // 6to4 relay anycast (deprecated)
        v4.add(Cidr.parse("192.168.0.0/16"));     // private
        v4.add(Cidr.parse("198.18.0.0/15"));      // benchmarking
        v4.add(Cidr.parse("198.51.100.0/24"));    // TEST-NET-2
        v4.add(Cidr.parse("203.0.113.0/24"));     // TEST-NET-3
        v4.add(Cidr.parse("224.0.0.0/4"));        // multicast
        v4.add(Cidr.parse("240.0.0.0/4"));        // reserved / limited broadcast
        IPV4_BLOCKS = Collections.unmodifiableList(v4);

        List<Cidr> v6 = new ArrayList<>();
        v6.add(Cidr.parse("::/128"));            // unspecified
        v6.add(Cidr.parse("::1/128"));           // loopback
        v6.add(Cidr.parse("::ffff:0:0/96"));    // IPv4-mapped
        v6.add(Cidr.parse("64:ff9b::/96"));     // NAT64 well-known prefix
        v6.add(Cidr.parse("64:ff9b:1::/48"));   // NAT64 local-use (discard-adjacent)
        v6.add(Cidr.parse("100::/64"));         // Discard-Only Address Block
        v6.add(Cidr.parse("2001::/23"));        // IETF Protocol Assignments (covers Teredo 2001::/32)
        v6.add(Cidr.parse("2001:2::/48"));      // benchmarking
        v6.add(Cidr.parse("2001:10::/28"));     // ORCHID
        v6.add(Cidr.parse("2001:db8::/32"));    // documentation
        v6.add(Cidr.parse("2002::/16"));        // 6to4
        v6.add(Cidr.parse("fc00::/7"));         // ULA
        v6.add(Cidr.parse("fe80::/10"));        // link-local
        v6.add(Cidr.parse("ff00::/8"));         // multicast
        IPV6_BLOCKS = Collections.unmodifiableList(v6);
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

        private Cidr(byte[] network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
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
                    // IPv6 リテラル。JDK が IPv4-mapped を Inet4Address に縮約する場合があるため 16 バイトへ正規化する。
                    InetAddress addr = InetAddress.getByName(host);
                    if (addr instanceof java.net.Inet4Address) {
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
                return new Cidr(network, prefix);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("invalid CIDR host: " + cidr, e);
            }
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

        /** プレフィックス境界の先頭アドレス（ネットワークアドレス）。 */
        InetAddress networkAddress() throws UnknownHostException {
            return InetAddress.getByAddress(network.clone());
        }

        /** プレフィックス直下の次ネットワーク先頭（境界の外側）。IPv4/v6 両対応の単純加算。 */
        InetAddress addressJustOutside() throws UnknownHostException {
            byte[] outside = network.clone();
            int bitIndex = prefixLength;
            if (bitIndex >= outside.length * 8) {
                // /32 や /128 は「外側」を同長で表現できないため、最終オクテット+1 を試みる
                for (int i = outside.length - 1; i >= 0; i--) {
                    int v = (outside[i] & 0xff) + 1;
                    outside[i] = (byte) v;
                    if (v <= 0xff) {
                        return InetAddress.getByAddress(outside);
                    }
                }
                return InetAddress.getByAddress(outside);
            }
            int byteIndex = bitIndex / 8;
            int bitInByte = 7 - (bitIndex % 8);
            outside[byteIndex] = (byte) (outside[byteIndex] | (1 << bitInByte));
            return InetAddress.getByAddress(outside);
        }
    }
}
