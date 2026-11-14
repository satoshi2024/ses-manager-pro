package com.ses.config.forwarded;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static com.ses.config.forwarded.ForwardedHeaderEchoTestConfiguration.ECHO;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FWD-SANITIZE P3-3: 実Tomcatの実connectorで、接続元（loopback）が信頼プロキシに含まれない場合は
 * X-Forwarded-For/Proto/Portも含め転送ヘッダーを一切反映しないことを確かめる（本番でtask内のhealth checkや
 * ALB以外の送信元が送るXFPを信頼しないことの実connector版）。
 *
 * <p>分離は {@link ForwardedHeaderServletContainerIntegrationTest} と同じ（{@code @Transactional} 不使用・DB書込なし・
 * クラス専用H2 DB・{@code @DirtiesContext}）。信頼プロキシにはloopbackを含まない範囲を指定する。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.security.trusted-proxies=10.0.0.0/24")
@Import(ForwardedHeaderEchoTestConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ForwardedHeaderUntrustedPeerServletContainerIntegrationTest {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String database = "forwarded_header_untrusted_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + database
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
    }

    @Test
    void 信頼しない接続元のXFFとProtoとPortは反映されずTCPpeerのまま() throws Exception {
        JsonNode body = ForwardedHeaderServletContainerIntegrationTest.echo(port,
                "X-Forwarded-For", "198.51.100.7",
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Port", "443",
                "X-Forwarded-Prefix", "/x");

        assertThat(body.path("remoteAddr").asText()).isEqualTo("127.0.0.1");
        assertThat(body.path("clientIp").asText()).isEqualTo("127.0.0.1");
        assertThat(body.path("secure").asBoolean()).isFalse();
        assertThat(body.path("requestUrl").asText()).startsWith("http://127.0.0.1:" + port);
        assertThat(body.path("requestUri").asText()).isEqualTo(ECHO);
        assertThat(body.path("forwardedForHeader").asText()).isEqualTo("null");
        assertThat(body.path("prefixHeader").asText()).isEqualTo("null");
    }
}
