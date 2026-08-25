package com.ses.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REV-B2.1-P1-001: special-use / non-global CIDR の境界と必須プレフィックス。
 */
class NonGlobalAddressPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "192.0.0.1",
            "192.0.0.255",
            "100::1",
            "64:ff9b:1::1",
            "2001:2::1",
            "2001:10::1",
            "2001:db8::1",
            "198.18.0.1",
            "192.0.2.1",
            "198.51.100.1",
            "203.0.113.1",
            "10.0.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "100.64.0.1",
            "fd00::1",
            "fe80::1",
            "::1",
            "::ffff:127.0.0.1"
    })
    void 非グローバルは拒否される(String host) throws Exception {
        assertThat(NonGlobalAddressPolicy.isNonGlobal(InetAddress.getByName(host))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8",
            "1.1.1.1",
            "2001:4860:4860::8888",
            "2606:4700:4700::1111"
    })
    void グローバル可ルーティングは許可される(String host) throws Exception {
        assertThat(NonGlobalAddressPolicy.isNonGlobal(InetAddress.getByName(host))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "192.0.0.0/24, 192.0.0.0, true",
            "192.0.0.0/24, 192.0.0.255, true",
            "192.0.0.0/24, 192.0.1.0, false",
            "100::/64, 100::, true",
            "100::/64, 100::ffff, true",
            "100::/64, 101::, false",
            "64:ff9b:1::/48, 64:ff9b:1::, true",
            "64:ff9b:1::/48, 64:ff9b:2::, false",
            "2001:2::/48, 2001:2::1, true",
            "2001:2::/48, 2001:3::1, false",
            "2001:10::/28, 2001:10::1, true",
            "2001:10::/28, 2001:20::1, false"
    })
    void CIDR境界(String cidr, String address, boolean inside) throws Exception {
        NonGlobalAddressPolicy.Cidr block = NonGlobalAddressPolicy.Cidr.parse(cidr);
        byte[] addr = InetAddress.getByName(address).getAddress();
        assertThat(block.contains(addr)).isEqualTo(inside);
    }

    @Test
    void 必須プレフィックスがポリシーに登録されている() {
        assertThat(NonGlobalAddressPolicy.ipv4BlocksForTest().stream()
                .anyMatch(c -> c.contains(ip4("192.0.0.1")))).isTrue();
        assertThat(NonGlobalAddressPolicy.ipv6BlocksForTest().stream()
                .anyMatch(c -> c.contains(ip6("100::1")))).isTrue();
        assertThat(NonGlobalAddressPolicy.ipv6BlocksForTest().stream()
                .anyMatch(c -> c.contains(ip6("64:ff9b:1::1")))).isTrue();
        assertThat(NonGlobalAddressPolicy.ipv6BlocksForTest().stream()
                .anyMatch(c -> c.contains(ip6("2001:2::1")))).isTrue();
        assertThat(NonGlobalAddressPolicy.ipv6BlocksForTest().stream()
                .anyMatch(c -> c.contains(ip6("2001:10::1")))).isTrue();
    }

    private static byte[] ip4(String host) {
        try {
            return InetAddress.getByName(host).getAddress();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] ip6(String host) {
        return ip4(host);
    }
}
