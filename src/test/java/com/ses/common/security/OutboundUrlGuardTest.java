package com.ses.common.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OutboundUrlGuard} のSSRF検証テスト。
 * IPv4/IPv6の内部・予約域、multi-A、スキーム/ポート/認証情報などの境界を確認する。
 * DNSへの実依存を避けるため、宛先には主にIPリテラルを用い、
 * multi-Aの検証は {@link OutboundUrlGuard#resolve(String)} を差し替えて行う。
 */
class OutboundUrlGuardTest {

    private final OutboundUrlGuard guard = new OutboundUrlGuard();

    // ---- validatePublicHttpsUrl: 正常系 ----

    @Test
    void 公開IPのHTTPSは許可される() {
        assertThatCode(() -> guard.validatePublicHttpsUrl("https://8.8.8.8/webhook")).doesNotThrowAnyException();
    }

    @Test
    void ポート443明示は許可される() {
        assertThatCode(() -> guard.validatePublicHttpsUrl("https://8.8.8.8:443/webhook")).doesNotThrowAnyException();
    }

    // ---- validatePublicHttpsUrl: スキーム/認証情報/ポート ----

    @Test
    void HTTPは拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("http://8.8.8.8/webhook"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void userinfoを含むURLは拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://user:pass@8.8.8.8/webhook"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void 非443ポートは拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://8.8.8.8:8443/webhook"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void 空URLは拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl(""))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void 形式不正URLは拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://exa mple.com/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    // ---- validatePublicHttpsUrl: IPv4 内部・予約域 ----

    @Test
    void IPv4のloopbackは拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://127.0.0.1/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv4のprivate10は拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://10.0.0.5/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv4のprivate192_168は拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://192.168.1.1/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv4のlinkLocalは拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://169.254.169.254/latest/meta-data"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv4のCGNAT100_64は拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://100.64.1.1/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv4のIETF_Protocol_Assignments_192_0_0は拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://192.0.0.9/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv4のベンチマーク198_18は拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://198.18.0.1/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv4のTEST_NET_1は拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://192.0.2.1/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv4のTEST_NET_2は拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://198.51.100.10/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv4のTEST_NET_3は拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://203.0.113.5/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv4のunspecified0は拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://0.0.0.0/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    // ---- validatePublicHttpsUrl: IPv6 内部・予約域 ----

    @Test
    void IPv6のloopbackは拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://[::1]/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv6のULAは拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://[fd00::1]/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv6のdocumentation_2001_db8は拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://[2001:db8::1]/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv6のdiscard_100は拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://[100::1]/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv6のbenchmarking_2001_2は拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://[2001:2::1]/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv6のlinkLocalは拒否される() {
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://[fe80::1]/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void IPv4mappedIPv6のloopbackは拒否される() {
        // ::ffff:127.0.0.1 は内部(loopback)を指すため拒否する。
        assertThatThrownBy(() -> guard.validatePublicHttpsUrl("https://[::ffff:7f00:1]/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    // ---- multi-A: 1つでも内部なら拒否 ----

    @Test
    void multiAで1つでも内部アドレスなら拒否される() throws Exception {
        InetAddress publicAddr = InetAddress.getByName("8.8.8.8");
        InetAddress privateAddr = InetAddress.getByName("10.0.0.9");
        OutboundUrlGuard multiAGuard = new OutboundUrlGuard() {
            @Override
            protected InetAddress[] resolve(String host) {
                return new InetAddress[]{publicAddr, privateAddr};
            }
        };
        assertThatThrownBy(() -> multiAGuard.validatePublicHttpsUrl("https://multi.example.test/x"))
                .isInstanceOf(OutboundUrlException.class);
    }

    @Test
    void multiAで全て公開なら許可される() throws Exception {
        InetAddress a = InetAddress.getByName("8.8.8.8");
        InetAddress b = InetAddress.getByName("1.1.1.1");
        OutboundUrlGuard multiAGuard = new OutboundUrlGuard() {
            @Override
            protected InetAddress[] resolve(String host) {
                return new InetAddress[]{a, b};
            }
        };
        assertThatCode(() -> multiAGuard.validatePublicHttpsUrl("https://multi.example.test/x"))
                .doesNotThrowAnyException();
    }

    @Test
    void 解決不能ホストは拒否される() {
        OutboundUrlGuard failingGuard = new OutboundUrlGuard() {
            @Override
            protected InetAddress[] resolve(String host) throws UnknownHostException {
                throw new UnknownHostException(host);
            }
        };
        assertThatThrownBy(() -> failingGuard.validatePublicHttpsUrl("https://nx.example.test/x"))
                .isInstanceOf(OutboundUrlException.class);
    }
}
