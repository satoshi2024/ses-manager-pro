package com.ses.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REV-B2.2-P1-001 / REV-B2.3-P3-001: IANA Special-Purpose（スナップショット
 * {@link NonGlobalAddressPolicy#IANA_REGISTRY_SNAPSHOT_DATE}）に基づく non-global 拒否。
 * 独立レジストリ表とポリシー表の突き合わせで漏れを検出する（自己循環禁止）。
 * <p>
 * 2001::/23 内の Globally Reachable=True carve-out は独立拒否表に含めない（許可側で別検証）。
 */
class NonGlobalAddressPolicyTest {

    /**
     * IANA IPv4/IPv6 Special-Purpose Registry（Last Updated 2025-10-09）から抽出した
     * 「Webhook 宛先として拒否すべき」代表アドレス。ポリシー実装の List を読み取らない。
     * <p>
     * 条件: Globally Reachable=False / N/A、Destination=False、Deprecated、加えてマルチキャスト。
     * 欠落検知用に 100:0:0:1::/64・3fff::/20・5f00::/16 の代表を必ず含む。
     * <p>
     * 2001::/23 内の Globally Reachable=True carve-out（PCP/TURN/DNS-SD/AMT/AS112/ORCHIDv2/Drone RID）
     * はここに含めない（拒否を要求しない）。
     */
    private static final String[] IANA_INDEPENDENT_MUST_REJECT = {
            // IPv4
            "0.0.0.1",
            "10.1.2.3",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.169.254",
            "172.16.0.1",
            "192.0.0.1",
            "192.0.2.1",
            "192.88.99.1",
            "192.168.1.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.0.0.1",
            "255.255.255.255",
            // IPv6
            "::",
            "::1",
            "::ffff:127.0.0.1",
            "64:ff9b::10.0.0.1",
            "64:ff9b:1::1",
            "100::1",
            "100:0:0:1::1",       // Dummy IPv6 Prefix RFC9780
            "2001::1",            // Teredo / IETF assignments parent（carve-out 外）
            "2001:2::1",          // Benchmarking
            "2001:10::1",         // Deprecated ORCHID
            "2001:db8::1",        // Documentation（/23 外）
            "2002:cb00:7100::",
            "3fff::1",            // Documentation RFC9637
            "5f00::1",            // SRv6 SIDs RFC9602
            "fd12:3456:789a::1",
            "fe80::1",
            "ff02::1"
    };

    static Stream<String> ianaIndependentMustReject() {
        return Stream.of(IANA_INDEPENDENT_MUST_REJECT);
    }

    @Test
    void IANAスナップショット日付が記録されている() {
        assertThat(NonGlobalAddressPolicy.IANA_REGISTRY_SNAPSHOT_DATE).isEqualTo("2025-10-09");
    }

    @ParameterizedTest
    @MethodSource("ianaIndependentMustReject")
    void 独立IANA表の代表アドレスは拒否される(String host) throws Exception {
        assertThat(NonGlobalAddressPolicy.isNonGlobal(InetAddress.getByName(host)))
                .as("IANA独立表ホスト %s がポリシー漏れ", host)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "100:0:0:1::1",
            "3fff::1",
            "5f00::1"
    })
    void 既知の欠落プレフィックス代表は拒否される(String host) throws Exception {
        assertThat(NonGlobalAddressPolicy.isNonGlobal(InetAddress.getByName(host))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8",
            "1.1.1.1",
            "203.0.114.1",
            "2001:4860:4860::8888",
            "2606:4700:4700::1111",
            "2404:6800:4004:800::200e",
            "192.31.196.1",   // AS112-v4 Globally Reachable=True
            "192.52.193.1",   // AMT Globally Reachable=True
            // REV-B2.3-P3-001: 2001::/23 内 Globally Reachable=True carve-out
            "2001:1::1",      // PCP Anycast
            "2001:1::2",      // TURN Anycast
            "2001:1::3",      // DNS-SD SRP Anycast
            "2001:3::1",      // AMT
            "2001:4:112::1",  // AS112-v6
            "2001:20::1",     // ORCHIDv2
            "2001:30::1"      // Drone Remote ID
    })
    void グローバル可ルーティングは許可される(String host) throws Exception {
        assertThat(NonGlobalAddressPolicy.isNonGlobal(InetAddress.getByName(host))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2001::1",        // Teredo
            "2001:2::1",      // Benchmarking
            "2001:10::1",     // Deprecated ORCHID
            "2001:db8::1"     // Documentation（/23 外）
    })
    void slash23内の非carveOutは拒否される(String host) throws Exception {
        assertThat(NonGlobalAddressPolicy.isNonGlobal(InetAddress.getByName(host))).isTrue();
    }

    /**
     * REV-RP-P3-001: 2001::/23 内 Globally Reachable=True carve-out ごとの
     * 先頭・末尾 ALLOW、直前・直後 DENY（隣接 carve-out や /23 外は applicable 外）。
     */
    @Test
    void slash23のGloballyReachableCarveOut境界を検証する() throws Exception {
        assertCarveOutBounds(
                "PCP Anycast 2001:1::1/128",
                "2001:1::1", "2001:1::1",
                "2001:1::", null); // 直後 2001:1::2 は TURN carve-out
        assertCarveOutBounds(
                "TURN Anycast 2001:1::2/128",
                "2001:1::2", "2001:1::2",
                null, null); // 前後とも他 /128 carve-out
        assertCarveOutBounds(
                "DNS-SD SRP Anycast 2001:1::3/128",
                "2001:1::3", "2001:1::3",
                null, "2001:1::4"); // 直前は TURN carve-out
        assertCarveOutBounds(
                "AMT 2001:3::/32",
                "2001:3::", "2001:3:ffff:ffff:ffff:ffff:ffff:ffff",
                "2001:2:ffff:ffff:ffff:ffff:ffff:ffff", "2001:4::");
        assertCarveOutBounds(
                "AS112-v6 2001:4:112::/48",
                "2001:4:112::", "2001:4:112:ffff:ffff:ffff:ffff:ffff",
                "2001:4:111:ffff:ffff:ffff:ffff:ffff", "2001:4:113::");
        assertCarveOutBounds(
                "ORCHIDv2 2001:20::/28",
                "2001:20::", "2001:2f:ffff:ffff:ffff:ffff:ffff:ffff",
                "2001:1f:ffff:ffff:ffff:ffff:ffff:ffff", null); // 直後は Drone RID carve-out
        assertCarveOutBounds(
                "Drone Remote ID 2001:30::/28",
                "2001:30::", "2001:3f:ffff:ffff:ffff:ffff:ffff:ffff",
                null, "2001:40::"); // 直前は ORCHIDv2 末尾
    }

    private static void assertCarveOutBounds(String displayName,
                                             String firstHost, String lastHost,
                                             String beforeHost, String afterHost) throws Exception {
        InetAddress first = InetAddress.getByName(firstHost);
        InetAddress last = InetAddress.getByName(lastHost);
        assertThat(NonGlobalAddressPolicy.isNonGlobal(first))
                .as("%s 先頭は許可 (isNonGlobal=false): %s", displayName, firstHost)
                .isFalse();
        assertThat(NonGlobalAddressPolicy.isNonGlobal(last))
                .as("%s 末尾は許可 (isNonGlobal=false): %s", displayName, lastHost)
                .isFalse();
        if (beforeHost != null) {
            InetAddress before = InetAddress.getByName(beforeHost);
            assertThat(NonGlobalAddressPolicy.isNonGlobal(before))
                    .as("%s 直前は拒否 (isNonGlobal=true): %s", displayName, beforeHost)
                    .isTrue();
        }
        if (afterHost != null) {
            InetAddress after = InetAddress.getByName(afterHost);
            assertThat(NonGlobalAddressPolicy.isNonGlobal(after))
                    .as("%s 直後は拒否 (isNonGlobal=true): %s", displayName, afterHost)
                    .isTrue();
        }
    }

    @Test
    void ポリシー登録CIDRごとの先頭末尾境界外を検証する() throws Exception {
        List<NonGlobalAddressPolicy.Cidr> all = new ArrayList<>();
        all.addAll(NonGlobalAddressPolicy.ipv4BlocksForTest());
        all.addAll(NonGlobalAddressPolicy.ipv6BlocksForTest());
        for (NonGlobalAddressPolicy.Cidr block : all) {
            InetAddress start = block.networkAddress();
            InetAddress end = block.lastAddress();
            InetAddress rep = block.representativeAddress();
            assertThat(NonGlobalAddressPolicy.isNonGlobal(start))
                    .as("%s 先頭", block.literal()).isTrue();
            assertThat(NonGlobalAddressPolicy.isNonGlobal(end))
                    .as("%s 末尾", block.literal()).isTrue();
            assertThat(NonGlobalAddressPolicy.isNonGlobal(rep))
                    .as("%s 代表", block.literal()).isTrue();
            // InetAddress は IPv4-mapped を Inet4 に縮約しうるため、包含判定は生バイトで行う
            assertThat(block.contains(block.network()))
                    .as("%s 先頭バイト", block.literal()).isTrue();
            assertThat(block.contains(lastAddressBytes(block)))
                    .as("%s 末尾バイト", block.literal()).isTrue();

            // /0 や全空間に近いものは外側が別ブロックに入る可能性があるため、
            // 外側が「この CIDR に含まれない」ことだけを検証する。
            if (block.prefixLength() < block.network().length * 8
                    && block.prefixLength() > 0
                    && !isFullSpaceCover(block)) {
                InetAddress outside = block.addressJustOutside();
                assertThat(block.contains(outside.getAddress()))
                        .as("%s 境界外は当該CIDRに含まれない: %s", block.literal(), outside.getHostAddress())
                        .isFalse();
            }
        }
    }

    @Test
    void 独立IANA表の必須CIDRがポリシーに登録されている() {
        // ポリシー表を列挙するのではなく、独立に列挙した CIDR リテラルが「いずれかのブロックに包含」されるか
        String[] requiredCidrLiterals = {
                "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
                "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
                "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "240.0.0.0/4", "224.0.0.0/4",
                "::/128", "::1/128", "::ffff:0:0/96", "64:ff9b::/96", "64:ff9b:1::/48",
                "100::/64", "100:0:0:1::/64", "2001::/23", "2001:db8::/32", "2002::/16",
                "3fff::/20", "5f00::/16", "fc00::/7", "fe80::/10", "ff00::/8"
        };
        List<NonGlobalAddressPolicy.Cidr> policy = new ArrayList<>();
        policy.addAll(NonGlobalAddressPolicy.ipv4BlocksForTest());
        policy.addAll(NonGlobalAddressPolicy.ipv6BlocksForTest());
        for (String required : requiredCidrLiterals) {
            NonGlobalAddressPolicy.Cidr need = NonGlobalAddressPolicy.Cidr.parse(required);
            boolean covered = policy.stream().anyMatch(p ->
                    p.prefixLength() <= need.prefixLength()
                            && p.contains(need.network())
                            && sameFamily(p, need)
                            && coversEntire(p, need));
            assertThat(covered)
                    .as("独立必須 CIDR %s がポリシーに未登録または未カバー", required)
                    .isTrue();
        }
    }

    private static boolean sameFamily(NonGlobalAddressPolicy.Cidr a, NonGlobalAddressPolicy.Cidr b) {
        return a.network().length == b.network().length;
    }

    /** p が need 全体を包含するか（p の方が短い／等しいプレフィックスで need 先頭を含む）。 */
    private static boolean coversEntire(NonGlobalAddressPolicy.Cidr p, NonGlobalAddressPolicy.Cidr need) {
        if (p.prefixLength() > need.prefixLength()) {
            return false;
        }
        return p.contains(need.network());
    }

    private static boolean isFullSpaceCover(NonGlobalAddressPolicy.Cidr block) {
        // 224/4 の外側は 240/4、240/4 の外側はラップしうる等 — 外側の isNonGlobal は検証しない
        String lit = block.literal();
        return lit.startsWith("224.") || lit.startsWith("240.") || lit.startsWith("ff00:");
    }

    /** {@link NonGlobalAddressPolicy.Cidr#lastAddress()} と同内容の生バイト（Inet 縮約を避ける）。 */
    private static byte[] lastAddressBytes(NonGlobalAddressPolicy.Cidr block) throws Exception {
        byte[] end = block.network();
        int prefixLength = block.prefixLength();
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
        return end;
    }
}
