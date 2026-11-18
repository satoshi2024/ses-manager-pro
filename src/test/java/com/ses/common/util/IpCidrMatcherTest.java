package com.ses.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FWD-SANITIZE: DNS解決なしのIPリテラル照合（範囲の包含・host部・IPv6 /64集約）。 */
class IpCidrMatcherTest {

    @Test
    void 範囲の包含は同じfamilyでblock以下の広さのときだけ成立する() {
        assertTrue(IpCidrMatcher.isContainedIn("10.0.1.0/24", "10.0.0.0/8"));
        assertTrue(IpCidrMatcher.isContainedIn("10.0.1.10", "10.0.0.0/8"));
        assertTrue(IpCidrMatcher.isContainedIn("172.31.255.0/24", "172.16.0.0/12"));
        assertTrue(IpCidrMatcher.isContainedIn("::ffff:100.64.1.0/120", "100.64.0.0/10"));
        assertFalse(IpCidrMatcher.isContainedIn("10.0.0.0/7", "10.0.0.0/8"));
        assertFalse(IpCidrMatcher.isContainedIn("172.32.0.0/16", "172.16.0.0/12"));
        assertFalse(IpCidrMatcher.isContainedIn("2600:1f18::/64", "10.0.0.0/8"));
        assertFalse(IpCidrMatcher.isContainedIn("not-a-cidr", "10.0.0.0/8"));
    }

    @Test
    void host部のbitを検出する() {
        assertTrue(IpCidrMatcher.hasHostBits("10.0.1.5/24"));
        assertTrue(IpCidrMatcher.hasHostBits("2600:1f18:abc:de00::1/64"));
        assertFalse(IpCidrMatcher.hasHostBits("10.0.1.0/24"));
        assertFalse(IpCidrMatcher.hasHostBits("10.0.1.5"));
        assertFalse(IpCidrMatcher.hasHostBits("0.0.0.0/0"));
        assertFalse(IpCidrMatcher.hasHostBits("invalid"));
    }

    @Test
    void IPv6アドレスは64bitのnetworkへ集約しIPv4は対象外にする() {
        assertEquals("2001:db8:1:2:0:0:0:0/64", IpCidrMatcher.ipv6Network64("2001:db8:1:2:abcd::9"));
        assertEquals(IpCidrMatcher.ipv6Network64("2001:db8:1:2::1"), IpCidrMatcher.ipv6Network64("2001:db8:1:2:ffff::2"));
        assertNull(IpCidrMatcher.ipv6Network64("198.51.100.7"));
        assertNull(IpCidrMatcher.ipv6Network64("::ffff:198.51.100.7"));
        assertNull(IpCidrMatcher.ipv6Network64("unknown"));
    }

    @Test
    void host名や曖昧なIPv4表記はDNS解決せず不正として扱う() {
        assertNull(IpCidrMatcher.normalizeIp("localhost"));
        assertNull(IpCidrMatcher.normalizeIp("010.0.0.1"));
        assertNull(IpCidrMatcher.normalizeIp("1.2.3"));
        assertNull(IpCidrMatcher.normalizeIp("fe80::1%eth0"));
        assertEquals("2001:db8:0:0:0:0:0:1", IpCidrMatcher.normalizeIp("2001:db8::1"));
        assertEquals("10.0.0.1", IpCidrMatcher.normalizeIp("::ffff:10.0.0.1"));
    }
}
