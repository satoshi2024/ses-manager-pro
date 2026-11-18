package com.ses.config.integrationhub;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * b-13（W6A-P3-8）: 実 Tomcat connector を通した X-Forwarded-For の E2E。
 *
 * <p>Spring Boot の {@link TomcatServletWebServerFactory} に production の customizer
 * （{@link ExternalApiTomcatConfiguration}。engine valve で TCP peer と転送 header の原文を捕捉する）を掛け、production と
 * 同じ {@code forward-headers-strategy: framework} の実 {@link ForwardedHeaderFilter} を前段に置いた servlet から
 * {@link ExternalApiSourceIpResolver} を呼ぶ。request は raw socket で書くため、header の行・順序・重複はそのまま connector へ届く。
 * Spring の application context と DB は使わない（fast suite で動く）。loopback の ephemeral port だけを使い、外部へは接続しない。
 *
 * <p>connector の TCP peer は常に 127.0.0.1 なので、trusted proxy を 127.0.0.1/32 にすると「ALB 経由」、
 * 10.0.0.0/24 にすると「ALB を経由しない直接接続」を表す。
 */
class ExternalApiForwardedHeaderConnectorTest {
    private static final String PEER = "127.0.0.1";
    private static final String SPOOFED_ALLOWED = "203.0.113.10";
    private static final String REAL_CLIENT = "198.51.100.7";

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path tomcatBase;

    private static WebServer server;
    private static int port;
    private static volatile IntegrationHubExternalApiProperties.Security security;

    @BeforeAll
    static void startRealConnector() throws Exception {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        // 公開API有効時のAJP・RemoteIpValve拒否も含め、productionのcustomizerをそのまま通す。
        properties.getPublicApi().setEnabled(true);
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getByName(PEER));
        factory.setBaseDirectory(tomcatBase.toFile());
        new ExternalApiTomcatConfiguration().externalApiRawRequestTargetCustomizer(properties).customize(factory);
        server = factory.getWebServer(context -> {
            context.addFilter("forwardedHeaderFilter", new ForwardedHeaderFilter())
                    .addMappingForUrlPatterns(null, false, "/*");
            context.addServlet("sourceIpProbe", new SourceIpProbe()).addMapping("/*");
        });
        server.start();
        port = server.getPort();
    }

    @AfterAll
    static void stopRealConnector() {
        if (server != null) server.stop();
    }

    @Test
    void 信頼proxy経由では右端の1hopだけを使い先頭のallow_list値と書換え後のremoteAddrを使わない() throws Exception {
        security = trusted(PEER + "/32");

        Response response = send("X-Forwarded-For: " + SPOOFED_ALLOWED + ", " + REAL_CLIENT);

        assertEquals(200, response.status(), response.body());
        assertEquals("resolved=" + REAL_CLIENT, response.line("resolved"));
        // 前提の再現: frameworkはremoteAddrをclientが書いた先頭値へ書き換え、転送headerをservletから隠す。
        assertEquals("remoteAddr=" + SPOOFED_ALLOWED, response.line("remoteAddr"));
        assertEquals("visibleXff=false", response.line("visibleXff"));
    }

    @Test
    void 信頼proxy経由でも転送headerが無い要求と多段proxyと複数行のheaderを拒否する() throws Exception {
        security = trusted(PEER + "/32");

        assertEquals("rejected=PROXY_HEADER_MISSING", send().line("rejected"));
        assertEquals("rejected=UNSUPPORTED_PROXY_CHAIN", send("X-Forwarded-For: " + REAL_CLIENT + ", " + PEER)
                .line("rejected"));
        assertEquals("rejected=DUPLICATE_PROXY_HEADER", send("X-Forwarded-For: " + SPOOFED_ALLOWED,
                "X-Forwarded-For: " + REAL_CLIENT).line("rejected"), "connectorは同名headerの各行を保つ");
        assertEquals("rejected=INVALID_PROXY_HEADER", send("X-Forwarded-For: " + REAL_CLIENT + ", not-an-ip")
                .line("rejected"));
    }

    @Test
    void Forwarded_for_だけではXFFを使う設定の送信元を書き換えられない() throws Exception {
        security = trusted(PEER + "/32");

        Response response = send("Forwarded: for=" + SPOOFED_ALLOWED);

        // frameworkはForwardedのfor=でremoteAddrを書き換えるが、resolverは設定したX-Forwarded-Forだけを読む。
        assertEquals("remoteAddr=" + SPOOFED_ALLOWED, response.line("remoteAddr"));
        assertEquals("rejected=PROXY_HEADER_MISSING", response.line("rejected"));
    }

    @Test
    void 信頼proxyでない接続元は転送headerがあれば拒否し無ければTCP_peerを使う() throws Exception {
        security = trusted("10.0.0.0/24");

        assertEquals("rejected=UNTRUSTED_PROXY", send("X-Forwarded-For: " + SPOOFED_ALLOWED).line("rejected"));
        assertEquals("rejected=UNTRUSTED_PROXY", send("Forwarded: for=" + SPOOFED_ALLOWED).line("rejected"));
        Response direct = send();
        assertEquals(200, direct.status(), direct.body());
        assertEquals("resolved=" + PEER, direct.line("resolved"));
    }

    private static IntegrationHubExternalApiProperties.Security trusted(String cidr) {
        IntegrationHubExternalApiProperties.Security value = new IntegrationHubExternalApiProperties.Security();
        value.setTrustedProxies(new ArrayList<>(List.of(cidr)));
        value.setForwardedHeader(IntegrationHubExternalApiProperties.ForwardedHeader.X_FORWARDED_FOR);
        return value;
    }

    /** raw socket で HTTP/1.1 の GET を1件送る。header 行は与えた順にそのまま書く。 */
    private static Response send(String... headerLines) throws IOException {
        try (Socket socket = new Socket(InetAddress.getByName(PEER), port)) {
            socket.setSoTimeout(10_000);
            StringBuilder request = new StringBuilder("GET /external-api/v1/projects HTTP/1.1\r\n")
                    .append("Host: api.example.test\r\n");
            for (String line : headerLines) request.append(line).append("\r\n");
            request.append("Connection: close\r\n\r\n");
            OutputStream output = socket.getOutputStream();
            output.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            output.flush();
            InputStream input = socket.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            input.transferTo(bytes);
            String raw = bytes.toString(StandardCharsets.UTF_8);
            int status = Integer.parseInt(raw.substring(9, 12));
            int bodyStart = raw.indexOf("\r\n\r\n");
            return new Response(status, bodyStart < 0 ? "" : raw.substring(bodyStart + 4));
        }
    }

    private record Response(int status, String body) {
        /** body の {@code key=value} 行（chunk の区切り行は無視する）。無ければ {@code key=<none>}。 */
        String line(String key) {
            for (String line : body.split("\r?\n")) {
                if (line.startsWith(key + "=")) return line.trim();
            }
            return key + "=<none>";
        }
    }

    /** resolver の結果と、framework が書き換えた後の servlet の見え方を返す。 */
    private static final class SourceIpProbe extends HttpServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
            StringBuilder body = new StringBuilder();
            body.append("remoteAddr=").append(request.getRemoteAddr()).append('\n');
            body.append("visibleXff=").append(request.getHeader("X-Forwarded-For") != null).append('\n');
            try {
                String resolved = new ExternalApiSourceIpResolver().resolve(request, security);
                response.setStatus(200);
                body.append("resolved=").append(resolved).append('\n');
            } catch (ExternalApiSecurityException rejected) {
                response.setStatus(401);
                body.append("rejected=").append(rejected.getDecision()).append('\n');
            }
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            response.setContentType("text/plain;charset=UTF-8");
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        }
    }

}
