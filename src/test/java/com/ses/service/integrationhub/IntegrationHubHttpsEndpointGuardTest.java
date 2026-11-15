package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class IntegrationHubHttpsEndpointGuardTest {
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "169.254.169.254",
            "100.100.100.200", "168.63.129.16", "0.0.0.0", "224.0.0.1", "::1", "fe80::1", "fc00::1",
            "::ffff:127.0.0.1", "2001:db8::1", "2002:7f00:1::1", "4000::1"})
    void 特殊用途とmetadataのIPを拒否する(String ip) throws Exception {
        assertThat(IntegrationHubHttpsEndpointGuard.isPublicAddress(InetAddress.getByName(ip))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Example.com:443", "127.0.0.1:443", "localhost:443", "web.localhost:443",
            "web.example:0", "web.example:65536", "web.example:0443", "https://web.example:443",
            "web.example:443/path", "web.example:443@other.example"})
    void 宛先設定は小文字FQDNと正規portだけを受け付ける(String destination) {
        assertThat(IntegrationHubHttpsEndpointGuard.validDestination(destination)).isFalse();
    }

    @Test
    void 公開IPと正規宛先の形式を許可する() throws Exception {
        assertThat(IntegrationHubHttpsEndpointGuard.validDestination("web.example.com:443")).isTrue();
        assertThat(IntegrationHubHttpsEndpointGuard.isPublicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(IntegrationHubHttpsEndpointGuard.isPublicAddress(InetAddress.getByName("2606:4700::1111"))).isTrue();
        byte[] mapped = new byte[16]; mapped[10] = (byte) 255; mapped[11] = (byte) 255;
        mapped[12] = 8; mapped[13] = 8; mapped[14] = 8; mapped[15] = 8;
        assertThat(IntegrationHubHttpsEndpointGuard.isPublicAddress(java.net.Inet6Address.getByAddress(null, mapped, -1))).isFalse();
    }

    @Test
    void 未承認URIと未検証DNSを名前解決せず拒否する() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getExternalTransport().getApproval().setAllowedDestinations(List.of("web.example.com:443"));
        try (IntegrationHubHttpsEndpointGuard guard = new IntegrationHubHttpsEndpointGuard(properties)) {
            for (String uri : List.of("http://web.example.com:443/", "https://127.0.0.1:443/",
                    "https://other.example:443/", "https://web.example.com:443/a%2fb",
                    "https://user@web.example.com:443/")) {
                assertThatThrownBy(() -> guard.validate(URI.create(uri))).isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> guard.pinnedAddresses("web.example.com")).isInstanceOf(java.net.UnknownHostException.class);
        }
    }
    @Test
    void fakeDNSの混在回答を拒否し公開snapshotと実peerの一致を必須にする() throws Exception {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getExternalTransport().getApproval().setAllowedDestinations(List.of("web.example.com:443"));
        InetAddress publicIp = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        InetAddress otherPublic = InetAddress.getByAddress(new byte[]{8, 8, 4, 4});
        InetAddress privateIp = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        try (IntegrationHubHttpsEndpointGuard guard = new IntegrationHubHttpsEndpointGuard(properties)) {
            var original = (java.util.concurrent.ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils.getField(guard, "resolver");
            original.shutdownNow();
            class FixedResolver extends java.util.concurrent.ThreadPoolExecutor {
                InetAddress[] answers;
                FixedResolver() { super(0, 1, 0, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1)); }
                @Override public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
                    return java.util.concurrent.CompletableFuture.completedFuture((T) answers);
                }
            }
            FixedResolver resolver = new FixedResolver();
            org.springframework.test.util.ReflectionTestUtils.setField(guard, "resolver", resolver);
            resolver.answers = new InetAddress[]{publicIp, privateIp};
            assertThatThrownBy(() -> guard.validate(URI.create("https://web.example.com:443/hook")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> guard.pinnedAddresses("web.example.com")).isInstanceOf(java.net.UnknownHostException.class);
            java.time.Instant first = java.time.Instant.parse("2026-11-15T00:00:00Z");
            org.springframework.test.util.ReflectionTestUtils.setField(guard, "clock", java.time.Clock.fixed(first, java.time.ZoneOffset.UTC));
            resolver.answers = new InetAddress[]{publicIp};
            var target = guard.validate(URI.create("https://web.example.com:443/hook"));
            resolver.answers[0] = privateIp;
            assertThat(guard.pinnedAddresses("web.example.com")).containsExactly(publicIp);
            guard.validateConnectedPeer(target, new java.net.InetSocketAddress(publicIp, 443));
            for (InetAddress denied : List.of(privateIp, otherPublic))
                assertThatThrownBy(() -> guard.validateConnectedPeer(target, new java.net.InetSocketAddress(denied, 443)))
                        .isInstanceOf(IllegalArgumentException.class);
            resolver.answers = new InetAddress[]{otherPublic};
            guard.validate(URI.create("https://web.example.com:443/hook"));
            assertThat(guard.pinnedAddresses("web.example.com")).containsExactly(otherPublic);
            guard.validateConnectedPeer(target, new java.net.InetSocketAddress(publicIp, 443));
            guard.validateConnectedPeer(target, new java.net.InetSocketAddress(otherPublic, 443));
            org.springframework.test.util.ReflectionTestUtils.setField(guard, "clock", java.time.Clock.fixed(first.plusSeconds(121), java.time.ZoneOffset.UTC));
            guard.validate(URI.create("https://web.example.com:443/hook"));
            assertThatThrownBy(() -> guard.validateConnectedPeer(target, new java.net.InetSocketAddress(publicIp, 443)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> guard.validateConnectedPeer(target, new java.net.InetSocketAddress(publicIp, 444)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
