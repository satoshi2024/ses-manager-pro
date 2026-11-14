package com.ses.config.forwarded;

import com.ses.common.util.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FWD-SANITIZE: 本番の転送ヘッダー設定の不足・誤りを起動時に拒否する。 */
class ForwardedHeaderProductionValidatorTest {

    private static ForwardedHeaderProductionValidator validator(String strategy, String trustedProxies) {
        MockEnvironment environment = new MockEnvironment();
        if (strategy != null) {
            environment.setProperty("server.forward-headers-strategy", strategy);
        }
        return new ForwardedHeaderProductionValidator(environment, new ClientIpResolver(trustedProxies));
    }

    @Test
    void ALBのsubnetを設定したframeworkは起動できる() {
        assertDoesNotThrow(() -> validator("framework", "10.0.1.0/24, 10.0.2.0/24, 2600:1f18:abc:de00::/64")
                .validate());
        assertDoesNotThrow(() -> validator("framework", "10.0.0.0/16").validate());
    }

    @Test
    void 信頼プロキシ未設定は起動を拒否する() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> validator("framework", "").validate());

        assertTrue(error.getMessage().contains("TRUSTED_PROXIES"), error.getMessage());
    }

    @Test
    void framework以外のstrategyは起動を拒否する() {
        for (String strategy : new String[]{"native", "none", null}) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> validator(strategy, "10.0.1.0/24").validate(), String.valueOf(strategy));

            assertTrue(error.getMessage().contains("forward-headers-strategy"), error.getMessage());
        }
    }

    @Test
    void 広すぎる信頼プロキシ範囲は起動を拒否する() {
        for (String range : new String[]{"0.0.0.0/0", "10.0.0.0/8", "10.0.0.0/15", "::/0", "2600:1f18::/32",
                "::ffff:10.0.0.0/104"}) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> validator("framework", range).validate(), range);

            assertTrue(error.getMessage().contains("広すぎます"), range + ": " + error.getMessage());
        }
    }

    @Test
    void 検査結果は不足項目を全て列挙する() {
        List<String> errors = ForwardedHeaderProductionValidator.check("native", List.of());

        assertEquals(2, errors.size(), errors.toString());
    }

    @Test
    void 単一IPの信頼プロキシは全長prefixとして許可する() {
        assertDoesNotThrow(() -> validator("framework", "10.0.1.10, 2600:1f18:abc:de00::10").validate());
    }

    @Test
    void privateとshared以外のIPv4範囲は起動を拒否する() {
        // P3-7: ALBのnodeはVPC内のprivate IPv4から接続する。公開IPv4やloopbackが信頼プロキシになる構成は無い。
        for (String range : new String[]{"8.8.0.0/16", "203.0.113.0/24", "127.0.0.1/32", "169.254.0.0/16",
                "172.32.0.0/16", "192.169.0.0/16", "100.128.0.0/16", "::ffff:8.8.8.0/120"}) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> validator("framework", range).validate(), range);

            assertTrue(error.getMessage().contains("private address"), range + ": " + error.getMessage());
        }
    }

    @Test
    void RFC1918とRFC6598のALB_subnetは許可する() {
        assertDoesNotThrow(() -> validator("framework",
                "10.20.0.0/24, 172.16.0.0/16, 172.31.255.0/24, 192.168.10.0/24, 100.64.0.0/16, 100.127.255.0/24")
                .validate());
    }

    @Test
    void host部にbitがあるCIDRは起動を拒否する() {
        for (String range : new String[]{"10.0.1.5/24", "2600:1f18:abc:de00::1/64"}) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> validator("framework", range).validate(), range);

            assertTrue(error.getMessage().contains("network address"), range + ": " + error.getMessage());
        }
    }

    private final ApplicationContextRunner prodRunner = new ApplicationContextRunner()
            .withUserConfiguration(ClientIpResolver.class, ForwardedHeaderProductionValidator.class)
            .withPropertyValues("spring.profiles.active=prod", "server.forward-headers-strategy=framework");

    @Test
    void prodのcontextは信頼プロキシ未設定なら起動しない() {
        prodRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("TRUSTED_PROXIES");
        });
    }

    @Test
    void prodのcontextは置換前のplaceholderでも起動しない() {
        prodRunner.withPropertyValues("app.security.trusted-proxies=ALB_SUBNET_CIDRS_PLACEHOLDER")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("IPアドレスまたはCIDR形式");
                });
    }

    @Test
    void prodのcontextはALB_subnetを設定すれば起動しprod以外ではvalidatorを作らない() {
        prodRunner.withPropertyValues("app.security.trusted-proxies=10.0.1.0/24,10.0.2.0/24")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(ForwardedHeaderProductionValidator.class));
        new ApplicationContextRunner()
                .withUserConfiguration(ClientIpResolver.class, ForwardedHeaderProductionValidator.class)
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(ForwardedHeaderProductionValidator.class));
    }
}
