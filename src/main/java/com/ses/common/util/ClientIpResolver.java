package com.ses.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * クライアントIP解決。X-Forwarded-For は remoteAddr が信頼プロキシのときだけ採用する（S13-XFF-01）。
 */
@Component
public class ClientIpResolver {

    private final List<String> trustedProxies;

    public ClientIpResolver(
            @Value("${app.security.trusted-proxies:}") String trustedProxiesCsv) {
        if (!StringUtils.hasText(trustedProxiesCsv)) {
            this.trustedProxies = Collections.emptyList();
        } else {
            this.trustedProxies = Arrays.stream(trustedProxiesCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remote = request.getRemoteAddr();
        if (isTrustedProxy(remote)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(forwarded)) {
                return forwarded.split(",")[0].trim();
            }
        }
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank() || trustedProxies.isEmpty()) {
            return false;
        }
        for (String trusted : trustedProxies) {
            if (trusted.equals(remoteAddr)) {
                return true;
            }
            if (trusted.contains("/") && matchesCidr(remoteAddr, trusted)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            InetAddress target = InetAddress.getByName(ip);
            InetAddress network = InetAddress.getByName(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            byte[] t = target.getAddress();
            byte[] n = network.getAddress();
            if (t.length != n.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (t[i] != n[i]) {
                    return false;
                }
            }
            if (remBits == 0) {
                return true;
            }
            int mask = (~0) << (8 - remBits);
            return (t[fullBytes] & mask) == (n[fullBytes] & mask);
        } catch (Exception e) {
            return false;
        }
    }
}
