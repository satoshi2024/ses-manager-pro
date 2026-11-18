package com.ses.common.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * DNS解決を行わない、IPリテラルの正規化とCIDR照合（転送ヘッダーの信頼判定用。FWD-SANITIZE）。
 *
 * <p>{@link InetAddress#getByName(String)} はhost名を渡すとDNS解決し、{@code 010.0.0.1} や {@code 1.2.3} のような
 * 曖昧な表記も受け付けるため使わない。IPv4は10進4組（先頭0なし）、IPv6は {@code ::} 省略とIPv4埋込みを含む表記
 * （zone ID・角括弧・空白なし）だけを受け付ける。IPv4-mapped IPv6（{@code ::ffff:a.b.c.d}）はIPv4として扱う。
 *
 * <p>規則はNF-05の {@code ExternalApiCidrMatcher} と同じ。範囲指定は {@code a.b.c.d/n}・{@code x::/n} のCIDRに加え、
 * 単一IP（全長prefixとして扱う）も受け付ける。
 */
public final class IpCidrMatcher {

    private IpCidrMatcher() {
    }

    /** 正規化したIP文字列。IPリテラルとして不正ならnull。 */
    public static String normalizeIp(String value) {
        byte[] bytes = parseIp(value);
        if (bytes == null) {
            return null;
        }
        try {
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** CIDRまたは単一IPとして有効か。 */
    public static boolean isValidRange(String range) {
        return parseRange(range) != null;
    }

    /** prefix長（IPv4は0〜32、IPv6は0〜128。単一IPは全長）。不正は-1。 */
    public static int prefixLength(String range) {
        ParsedRange parsed = parseRange(range);
        return parsed == null ? -1 : parsed.prefixLength();
    }

    /** IPv4範囲（IPv4-mapped表記を含む）ならtrue。不正はfalse。 */
    public static boolean isIpv4Range(String range) {
        ParsedRange parsed = parseRange(range);
        return parsed != null && parsed.network().length == 4;
    }

    /** addressがrangeに含まれるか。どちらかが不正ならfalse。 */
    public static boolean matches(String address, String range) {
        byte[] target = parseIp(address);
        ParsedRange parsed = parseRange(range);
        return target != null && parsed != null && contains(parsed, target);
    }

    /** rangeの全体がblockに含まれるか（同じaddress familyで、blockより狭いか等しい）。どちらかが不正ならfalse。 */
    public static boolean isContainedIn(String range, String block) {
        ParsedRange inner = parseRange(range);
        ParsedRange outer = parseRange(block);
        return inner != null && outer != null && inner.prefixLength() >= outer.prefixLength()
                && contains(outer, inner.network());
    }

    /** CIDRのprefixより後ろ（host部）に1のbitがあるか（{@code 10.0.1.5/24} 等）。不正はfalse。 */
    public static boolean hasHostBits(String range) {
        ParsedRange parsed = parseRange(range);
        if (parsed == null) {
            return false;
        }
        byte[] network = parsed.network();
        for (int bit = parsed.prefixLength(); bit < network.length * 8; bit++) {
            if ((network[bit / 8] & (0x80 >>> (bit % 8))) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * IPv6アドレスの/64 network（例 {@code 2001:db8:1:2:0:0:0:0/64}）。IPv4（IPv4-mappedを含む）や不正値はnull。
     * IPv6は1利用者が/64を丸ごと持つのが通例のため、IP単位のrate limit keyを/64へ集約するのに使う。
     */
    public static String ipv6Network64(String address) {
        byte[] bytes = parseIp(address);
        if (bytes == null || bytes.length != 16) {
            return null;
        }
        byte[] network = Arrays.copyOf(bytes, 16);
        Arrays.fill(network, 8, 16, (byte) 0);
        try {
            return InetAddress.getByAddress(network).getHostAddress() + "/64";
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean contains(ParsedRange parsed, byte[] target) {
        if (parsed.network().length != target.length) {
            return false;
        }
        int prefix = parsed.prefixLength();
        int full = prefix / 8;
        int bits = prefix % 8;
        for (int i = 0; i < full; i++) {
            if (target[i] != parsed.network()[i]) {
                return false;
            }
        }
        return bits == 0 || (target[full] & (0xff << (8 - bits)))
                == (parsed.network()[full] & (0xff << (8 - bits)));
    }

    private static ParsedRange parseRange(String range) {
        if (range == null) {
            return null;
        }
        String value = range.trim();
        if (value.isEmpty()) {
            return null;
        }
        int slash = value.indexOf('/');
        if (slash < 0) {
            byte[] address = parseIp(value);
            return address == null ? null : new ParsedRange(address, address.length * 8);
        }
        String[] parts = value.split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || !parts[1].matches("[0-9]{1,3}")) {
            return null;
        }
        byte[] network = parseIp(parts[0]);
        if (network == null) {
            return null;
        }
        int suppliedPrefix = Integer.parseInt(parts[1]);
        boolean mappedLiteral = parts[0].indexOf(':') >= 0 && network.length == 4;
        int prefix;
        if (mappedLiteral) {
            // IPv4-mapped IPv6のCIDRは ::ffff:0:0/96 を含む必要がある。IPv4として比較する。
            if (suppliedPrefix < 96 || suppliedPrefix > 128) {
                return null;
            }
            prefix = suppliedPrefix - 96;
        } else {
            prefix = suppliedPrefix;
        }
        if (prefix < 0 || prefix > network.length * 8) {
            return null;
        }
        return new ParsedRange(network, prefix);
    }

    private static byte[] parseIp(String value) {
        if (value == null || value.isBlank() || value.contains("%") || value.contains(" ")
                || value.contains("[") || value.contains("]")) {
            return null;
        }
        if (value.indexOf(':') >= 0) {
            return collapseMappedIpv6(parseIpv6(value));
        }
        return value.indexOf('.') >= 0 ? parseIpv4(value) : null;
    }

    private static byte[] collapseMappedIpv6(byte[] value) {
        if (value == null || value.length != 16) {
            return value;
        }
        for (int i = 0; i < 10; i++) {
            if (value[i] != 0) {
                return value;
            }
        }
        if ((value[10] & 0xff) != 0xff || (value[11] & 0xff) != 0xff) {
            return value;
        }
        return Arrays.copyOfRange(value, 12, 16);
    }

    private static byte[] parseIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        byte[] result = new byte[4];
        for (int i = 0; i < octets.length; i++) {
            String octet = octets[i];
            if (octet.isEmpty() || octet.length() > 3 || (octet.length() > 1 && octet.startsWith("0"))
                    || !octet.matches("[0-9]+")) {
                return null;
            }
            int number = Integer.parseInt(octet);
            if (number > 255) {
                return null;
            }
            result[i] = (byte) number;
        }
        return result;
    }

    private static byte[] parseIpv6(String value) {
        if (value.indexOf('.') >= 0) {
            int lastColon = value.lastIndexOf(':');
            if (lastColon < 0 || lastColon == value.length() - 1) {
                return null;
            }
            byte[] ipv4 = parseIpv4(value.substring(lastColon + 1));
            if (ipv4 == null) {
                return null;
            }
            value = value.substring(0, lastColon + 1)
                    + Integer.toHexString(((ipv4[0] & 0xff) << 8) | (ipv4[1] & 0xff))
                    + ":" + Integer.toHexString(((ipv4[2] & 0xff) << 8) | (ipv4[3] & 0xff));
        }
        int compression = value.indexOf("::");
        if (compression >= 0 && value.indexOf("::", compression + 2) >= 0) {
            return null;
        }
        String left = compression < 0 ? value : value.substring(0, compression);
        String right = compression < 0 ? "" : value.substring(compression + 2);
        String[] leftParts = left.isEmpty() ? new String[0] : left.split(":", -1);
        String[] rightParts = right.isEmpty() ? new String[0] : right.split(":", -1);
        int total = leftParts.length + rightParts.length;
        if (compression < 0 ? total != 8 : total >= 8) {
            return null;
        }
        int[] groups = new int[8];
        int position = 0;
        for (String part : leftParts) {
            Integer parsed = parseHextet(part);
            if (parsed == null) {
                return null;
            }
            groups[position++] = parsed;
        }
        if (compression >= 0) {
            position += 8 - total;
        }
        for (String part : rightParts) {
            Integer parsed = parseHextet(part);
            if (parsed == null) {
                return null;
            }
            groups[position++] = parsed;
        }
        byte[] result = new byte[16];
        for (int i = 0; i < groups.length; i++) {
            result[i * 2] = (byte) (groups[i] >>> 8);
            result[i * 2 + 1] = (byte) groups[i];
        }
        return result;
    }

    private static Integer parseHextet(String value) {
        if (value == null || value.isEmpty() || value.length() > 4 || !value.matches("[0-9A-Fa-f]{1,4}")) {
            return null;
        }
        return Integer.parseInt(value, 16);
    }

    private record ParsedRange(byte[] network, int prefixLength) {
    }
}
