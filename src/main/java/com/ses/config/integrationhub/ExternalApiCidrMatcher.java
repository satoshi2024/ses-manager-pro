package com.ses.config.integrationhub;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** DNS解決を行わず、IPv4/IPv6の正規化とCIDR比較だけを行う。 */
final class ExternalApiCidrMatcher {
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

    private static boolean matches(byte[] target, String cidr) {
        if (cidr.isBlank()) {
            return false;
        }
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2) {
            return false;
        }
        byte[] network = parseIp(parts[0]);
        if (network == null || network.length != target.length) {
            return false;
        }
        try {
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > network.length * 8) {
                return false;
            }
            int full = prefix / 8;
            int bits = prefix % 8;
            for (int i = 0; i < full; i++) {
                if (target[i] != network[i]) {
                    return false;
                }
            }
            return bits == 0 || (target[full] & (0xff << (8 - bits)))
                    == (network[full] & (0xff << (8 - bits)));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static byte[] parseIp(String value) {
        if (value == null || value.isBlank() || value.contains("%") || value.contains(" ")) {
            return null;
        }
        boolean ipv4 = value.matches("[0-9.]+");
        boolean ipv6 = value.matches("[0-9A-Fa-f:.]+") && value.contains(":");
        if (!ipv4 && !ipv6) {
            return null;
        }
        try {
            byte[] result = InetAddress.getByName(value).getAddress();
            if (ipv4 && result.length != 4) {
                return null;
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }
}
