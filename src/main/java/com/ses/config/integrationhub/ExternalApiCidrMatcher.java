package com.ses.config.integrationhub;

import java.net.InetAddress;
import java.util.List;

/** DNS解決を行わず、IPv4/IPv6の正規化とCIDR比較だけを行う。 */
public final class ExternalApiCidrMatcher {
    private ExternalApiCidrMatcher() {
    }

    static boolean matchesAny(String address, String csv) {
        if (address == null || csv == null || csv.isBlank()) {
            return false;
        }
        byte[] target = parseIp(address);
        if (target == null) {
            return false;
        }
        for (String candidate : csv.split(",", -1)) {
            if (matches(target, candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesAny(String address, List<String> cidrs) {
        if (address == null || cidrs == null || cidrs.isEmpty()) {
            return false;
        }
        byte[] target = parseIp(address);
        if (target == null) {
            return false;
        }
        return cidrs.stream().anyMatch(cidr -> matches(target, cidr == null ? "" : cidr.trim()));
    }

    static String normalizeIp(String value) {
        byte[] bytes = parseIp(value);
        if (bytes == null) {
            return null;
        }
        try {
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    /** literal input専用。InetAddress.getByNameを使わず、DNS解決経路を持たない。 */
    public static InetAddress parseLiteral(String value) {
        byte[] bytes = parseIp(value);
        if (bytes == null) {
            throw new IllegalArgumentException("IP literal is invalid");
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("IP literal is invalid", e);
        }
    }

    private static boolean matches(byte[] target, String cidr) {
        ParsedCidr parsed = parseCidr(cidr);
        if (parsed == null || parsed.network().length != target.length) {
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

    private static ParsedCidr parseCidr(String cidr) {
        if (cidr == null || cidr.isBlank()) {
            return null;
        }
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || !parts[1].matches("[0-9]{1,3}")) {
            return null;
        }
        byte[] network = parseIp(parts[0]);
        if (network == null) {
            return null;
        }
        try {
            int suppliedPrefix = Integer.parseInt(parts[1]);
            boolean mappedLiteral = parts[0].indexOf(':') >= 0 && network.length == 4;
            int prefix;
            if (mappedLiteral) {
                // A mapped IPv6 CIDR must cover the ::ffff:0:0/96 prefix; compare as IPv4.
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
            return new ParsedCidr(network, prefix);
        } catch (NumberFormatException e) {
            return null;
        }
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
        return java.util.Arrays.copyOfRange(value, 12, 16);
    }

    private record ParsedCidr(byte[] network, int prefixLength) {
    }

    private static byte[] parseIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        byte[] result = new byte[4];
        for (int i = 0; i < octets.length; i++) {
            String octet = octets[i];
            if (octet.isEmpty() || octet.length() > 3 || octet.length() > 1 && octet.startsWith("0")
                    || !octet.matches("[0-9]+")) {
                return null;
            }
            try {
                int number = Integer.parseInt(octet);
                if (number > 255) {
                    return null;
                }
                result[i] = (byte) number;
            } catch (NumberFormatException e) {
                return null;
            }
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
            String mapped = value.substring(0, lastColon + 1)
                    + Integer.toHexString(((ipv4[0] & 0xff) << 8) | (ipv4[1] & 0xff))
                    + ":" + Integer.toHexString(((ipv4[2] & 0xff) << 8) | (ipv4[3] & 0xff));
            value = mapped;
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
            if (parsed == null) return null;
            groups[position++] = parsed;
        }
        if (compression >= 0) {
            position += 8 - total;
        }
        for (String part : rightParts) {
            Integer parsed = parseHextet(part);
            if (parsed == null) return null;
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
        try {
            return Integer.parseInt(value, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
