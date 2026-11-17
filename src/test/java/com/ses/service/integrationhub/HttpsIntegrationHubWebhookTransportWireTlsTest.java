package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * b-13（G3-P3-9 ③④、G3s-P3-9）: production の HTTPS transport を、JVM 内の実 TLS server へ実際の wire で接続する。
 *
 * <p>証明書の検証は合成 CA（class 実行時に keytool で JUnit 管理の一時 directory へ生成し、repository に置かない）と、
 * file に書いた PKCS12 truststore（production と同じ読込経路）で行う。宛先 guard だけを mock にする。guard は loopback を
 * 公開 IP でないとして拒否するため（設計どおり）、DNS の固定先と実 peer の照合は {@code IntegrationHubHttpsEndpointGuardTest}
 * が単体で確かめる。外部への通信は行わない。
 *
 * <p>確かめること: 信頼 CA の正の対照が成功する同じ transport で、(1) 期限切れの leaf、(2) truststore に無い CA、
 * (3) 証明書の名前と接続先 host の不一致を、HTTP request を送る前に恒久障害として拒否する。(4) ClientHello が TLSv1.3/1.2 と
 * AEAD cipher だけを提示し、TLSv1.1 以下へ下げる余地を持たない（JDK の既定の無効化に頼らない）。
 */
class HttpsIntegrationHubWebhookTransportWireTlsTest {
    private static final char[] PASSWORD = "synthetic-test-only".toCharArray();
    /** {@link HttpsIntegrationHubWebhookTransport#TLS_CIPHERS} の IANA 番号。 */
    private static final Set<Integer> APPROVED_CIPHER_CODES = Set.of(0x1301, 0x1302, 0x1303, 0xC02F, 0xC030, 0xC02B, 0xC02C);
    /** JDK が付ける TLS_EMPTY_RENEGOTIATION_INFO_SCSV（cipher ではなく再交渉の信号）。 */
    private static final int RENEGOTIATION_SCSV = 0x00FF;

    @TempDir
    static Path fixtureRoot;

    /** 試験ごとの一時 directory（共有の truststore を書き換えない）。 */
    @TempDir
    Path caseDirectory;

    private static SSLContext validLeaf;
    private static SSLContext expiredLeaf;
    private static Path trustedCaStore;
    private static Path otherCaStore;

    private final List<AutoCloseable> resources = new ArrayList<>();

    @BeforeAll
    static void generateSyntheticPki() throws Exception {
        String validFrom = java.time.LocalDate.now().minusDays(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd")) + " 00:00:00";
        Path ca = createCa("wire-ca", "CN=Synthetic NF05 Wire Test CA");
        Path otherCa = createCa("wire-other-ca", "CN=Synthetic NF05 Wire Other CA");
        validLeaf = leafServerContext("valid", "wire-ca", ca, validFrom, 30);
        expiredLeaf = leafServerContext("expired", "wire-ca", ca, "2020/01/01 00:00:00", 1);
        trustedCaStore = truststoreFile("trusted-ca.p12", ca);
        otherCaStore = truststoreFile("other-ca.p12", otherCa);
    }

    @AfterEach
    void closeResources() {
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception ignored) {
                // 後続の資源も必ず閉じる。
            }
        }
        resources.clear();
    }

    @Test
    void 期限切れleafは信頼CAの正の対照と同じtransportでHTTP送信前に恒久障害として拒否する() throws Exception {
        TlsFixture valid = start(validLeaf);
        TlsFixture expired = start(expiredLeaf);
        HttpsIntegrationHubWebhookTransport transport = transport(trustedCaStore, valid.port(), expired.port());

        assertThat(transport.send(request("127.0.0.1", valid.port())).success()).as("正の対照").isTrue();
        IntegrationHubWebhookTransportResult result = transport.send(request("127.0.0.1", expired.port()));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TLS_CERTIFICATE_EXPIRED");
        assertThat(result.retryable()).as("期限切れは待っても直らない").isFalse();
        assertThat(expired.requests()).hasValue(0);
        assertThat(valid.requests()).hasValue(1);
    }

    @Test
    void truststoreに無いCAの証明書はHTTP送信前に未信頼として拒否する() throws Exception {
        TlsFixture valid = start(validLeaf);
        HttpsIntegrationHubWebhookTransport trusting = transport(trustedCaStore, valid.port());
        HttpsIntegrationHubWebhookTransport otherCa = transport(otherCaStore, valid.port());

        assertThat(trusting.send(request("127.0.0.1", valid.port())).success()).as("正の対照").isTrue();
        IntegrationHubWebhookTransportResult result = otherCa.send(request("127.0.0.1", valid.port()));

        assertThat(result.errorCode()).isEqualTo("TLS_CERTIFICATE_UNTRUSTED");
        assertThat(result.retryable()).isFalse();
        assertThat(valid.requests()).as("未信頼の相手へHTTP requestを送らない").hasValue(1);
    }

    @Test
    void 証明書の名前と接続先hostが違えばHTTP送信前に拒否する() throws Exception {
        TlsFixture valid = start(validLeaf);
        HttpsIntegrationHubWebhookTransport transport = transport(trustedCaStore, valid.port());

        assertThat(transport.send(request("127.0.0.1", valid.port())).success()).as("SAN=IP:127.0.0.1と一致").isTrue();
        // localhost も同じ 127.0.0.1 へ固定するが、証明書は IP SAN しか持たないため名前が一致しない。
        IntegrationHubWebhookTransportResult result = transport.send(request("localhost", valid.port()));

        assertThat(result.errorCode()).isEqualTo("TLS_IDENTITY_REJECTED");
        assertThat(result.retryable()).isFalse();
        assertThat(valid.requests()).hasValue(1);
    }

    @Test
    void ClientHelloはTLSv1_3と1_2と承認済みAEAD_cipherだけを提示する() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        resources.add(executor::shutdownNow);
        ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        resources.add(listener);
        listener.setSoTimeout(5000);
        Future<byte[]> captured = executor.submit(() -> {
            try (Socket socket = listener.accept()) {
                socket.setSoTimeout(5000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                byte[] header = new byte[5];
                input.readFully(header);
                int length = ((header[3] & 0xff) << 8) | (header[4] & 0xff);
                byte[] record = new byte[5 + length];
                System.arraycopy(header, 0, record, 0, 5);
                input.readFully(record, 5, length);
                return record;
            }
        });
        HttpsIntegrationHubWebhookTransport transport = transport(trustedCaStore, listener.getLocalPort());

        IntegrationHubWebhookTransportResult result = transport.send(request("127.0.0.1", listener.getLocalPort()));
        ClientHello hello = ClientHello.parse(captured.get(10, TimeUnit.SECONDS));

        assertThat(result.success()).isFalse();
        assertThat(hello.legacyVersion()).as("TLS1.3のClientHelloはlegacy_versionを1.2に固定する").isEqualTo(0x0303);
        assertThat(hello.supportedVersions()).as("supported_versions").containsExactlyInAnyOrder(0x0304, 0x0303);
        assertThat(hello.cipherSuites()).isNotEmpty();
        assertThat(hello.cipherSuites()).allSatisfy(code -> assertThat(code == RENEGOTIATION_SCSV
                || APPROVED_CIPHER_CODES.contains(code)).as("cipher 0x%04X", code).isTrue());
    }

    private TlsFixture start(SSLContext context) throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        TlsFixture fixture = new TlsFixture(server, executor, requests);
        resources.add(fixture);
        return fixture;
    }

    /**
     * RB-NF05 review P3-6: 承認照合が digest を計算した truststore の byte 列を transport が読み込み、照合の後に同じ path の
     * file を別の CA へ差し替えても照合した内容だけを信頼する。対照として、照合の記録が無ければ差し替え後の file を読む。
     */
    @Test
    void 承認照合で読んだtruststoreのbyte列を使い照合後のfileの差替えを読まない() throws Exception {
        TlsFixture valid = start(validLeaf);
        Path swapped = caseDirectory.resolve("swapped-truststore.p12");
        Files.copy(trustedCaStore, swapped);
        IntegrationHubExternalApiProperties approved = transportProperties(swapped);
        approved.getExternalTransport().recordApprovedTruststore(swapped.toString(), Files.readAllBytes(swapped));
        Files.copy(otherCaStore, swapped, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        IntegrationHubWebhookTransportResult result = transport(approved).send(request("127.0.0.1", valid.port()));

        assertThat(result.success()).as("照合した truststore（信頼 CA）で接続する").isTrue();
        IntegrationHubWebhookTransportResult unrecorded = transport(transportProperties(swapped))
                .send(request("127.0.0.1", valid.port()));
        assertThat(unrecorded.errorCode()).as("照合の記録が無ければ file（差し替え後の別 CA）を読む")
                .isEqualTo("TLS_CERTIFICATE_UNTRUSTED");
        approved.getExternalTransport().setTruststorePath(otherCaStore.toString());
        assertThat(approved.getExternalTransport().approvedTruststoreContent()).as("path が変われば記録を使わない").isNull();
        assertThat(valid.requests()).hasValue(1);
    }

    /** production と同じ構成の transport。宛先 guard だけを mock にし、host を 127.0.0.1 へ固定する。 */
    private HttpsIntegrationHubWebhookTransport transport(Path truststore, int... ports) throws Exception {
        return transport(transportProperties(truststore));
    }

    private static IntegrationHubExternalApiProperties transportProperties(Path truststore) {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getExternalTransport().setTruststorePath(truststore.toString());
        properties.getExternalTransport().setTruststorePassword(new String(PASSWORD));
        properties.getExternalTransport().setTruststoreType("PKCS12");
        properties.getExternalTransport().setConnectTimeoutMs(3000);
        properties.getExternalTransport().setReadTimeoutMs(3000);
        return properties;
    }

    private HttpsIntegrationHubWebhookTransport transport(IntegrationHubExternalApiProperties properties) throws Exception {
        IntegrationHubHttpsEndpointGuard guard = mock(IntegrationHubHttpsEndpointGuard.class);
        when(guard.validate(any())).thenAnswer(call -> {
            URI uri = call.getArgument(0);
            return new IntegrationHubHttpsEndpointGuard.Target(uri, uri.getHost(), uri.getPort());
        });
        when(guard.validateNamedEndpoint(anyString(), anyInt())).thenAnswer(call -> new IntegrationHubHttpsEndpointGuard.Target(
                URI.create("https://" + call.getArgument(0) + ":" + call.getArgument(1) + "/"),
                call.getArgument(0), call.getArgument(1)));
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        when(guard.pinnedAddresses("127.0.0.1")).thenReturn(new InetAddress[]{loopback});
        when(guard.pinnedAddresses("localhost")).thenReturn(new InetAddress[]{loopback});
        HttpsIntegrationHubWebhookTransport transport = new HttpsIntegrationHubWebhookTransport(properties, guard);
        resources.add(transport);
        return transport;
    }

    private static IntegrationHubWebhookRequest request(String host, int port) {
        return new IntegrationHubWebhookRequest(URI.create("https://" + host + ":" + port + "/"),
                "{}".getBytes(StandardCharsets.UTF_8), Map.of("Idempotency-Key", "wire-" + host + "-" + port));
    }

    private static Path createCa(String name, String distinguishedName) throws Exception {
        Path keyStore = fixtureRoot.resolve(name + ".p12");
        Path certificate = fixtureRoot.resolve(name + ".cer");
        runKeytool("-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD), "-dname", distinguishedName,
                "-ext", "BC=ca:true", "-ext", "KU=keyCertSign,cRLSign",
                "-keystore", keyStore.toString(), "-storetype", "PKCS12");
        runKeytool("-exportcert", "-rfc", "-alias", name, "-storepass", new String(PASSWORD),
                "-keystore", keyStore.toString(), "-file", certificate.toString());
        return keyStore;
    }

    private static SSLContext leafServerContext(String name, String caAlias, Path caKeyStore, String startDate,
                                                int validityDays) throws Exception {
        Path leafKeyStore = fixtureRoot.resolve(name + "-leaf.p12");
        Path signingRequest = fixtureRoot.resolve(name + "-leaf.csr");
        Path signed = fixtureRoot.resolve(name + "-leaf.cer");
        runKeytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD), "-dname", "CN=127.0.0.1",
                "-keystore", leafKeyStore.toString(), "-storetype", "PKCS12");
        runKeytool("-certreq", "-alias", "server", "-storepass", new String(PASSWORD),
                "-keystore", leafKeyStore.toString(), "-file", signingRequest.toString());
        runKeytool("-gencert", "-alias", caAlias, "-storepass", new String(PASSWORD),
                "-keystore", caKeyStore.toString(), "-infile", signingRequest.toString(),
                "-outfile", signed.toString(), "-startdate", startDate, "-validity", Integer.toString(validityDays),
                "-ext", "SAN=IP:127.0.0.1", "-ext", "KU=digitalSignature,keyEncipherment", "-ext", "EKU=serverAuth");
        KeyStore generated = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(leafKeyStore)) {
            generated.load(input, PASSWORD);
        }
        PrivateKey key = (PrivateKey) generated.getKey("server", PASSWORD);
        // 期限切れleafも keytool の reply 検証を通さずに chain を組めるよう、key entry は JDK API で構成する。
        KeyStore serverKeys = KeyStore.getInstance("PKCS12");
        serverKeys.load(null, PASSWORD);
        serverKeys.setKeyEntry("server", key, PASSWORD, new Certificate[]{certificate(signed),
                certificate(fixtureRoot.resolve(caAlias + ".cer"))});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(serverKeys, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    /** production と同じく file から読む PKCS12 truststore（CA 証明書1件）。 */
    private static Path truststoreFile(String fileName, Path caKeyStore) throws Exception {
        String alias = caKeyStore.getFileName().toString().replace(".p12", "");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, PASSWORD);
        trust.setCertificateEntry(alias, certificate(fixtureRoot.resolve(alias + ".cer")));
        Path file = fixtureRoot.resolve(fileName);
        try (OutputStream output = Files.newOutputStream(file)) {
            trust.store(output, PASSWORD);
        }
        return file;
    }

    private static Certificate certificate(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static void runKeytool(String... arguments) throws Exception {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path windows = bin.resolve("keytool.exe");
        String[] command = new String[arguments.length + 1];
        command[0] = Files.exists(windows) ? windows.toString() : bin.resolve("keytool").toString();
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("synthetic TLS fixture command timed out");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("synthetic TLS fixture command failed");
    }

    private record TlsFixture(HttpsServer server, ExecutorService executor, AtomicInteger requests)
            implements AutoCloseable {
        int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() throws InterruptedException {
            server.stop(0);
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    /** TLS record 1件に入った ClientHello の、検査に要る項目だけを読む。 */
    private record ClientHello(int legacyVersion, List<Integer> cipherSuites, Set<Integer> supportedVersions) {
        static ClientHello parse(byte[] record) {
            ByteBuffer buffer = ByteBuffer.wrap(record);
            assertThat(buffer.get()).as("handshake record").isEqualTo((byte) 0x16);
            buffer.getShort();
            buffer.getShort();
            assertThat(buffer.get()).as("client_hello").isEqualTo((byte) 0x01);
            buffer.position(buffer.position() + 3);
            int legacyVersion = buffer.getShort() & 0xffff;
            buffer.position(buffer.position() + 32);
            int sessionIdLength = buffer.get() & 0xff;
            buffer.position(buffer.position() + sessionIdLength);
            int suitesLength = buffer.getShort() & 0xffff;
            List<Integer> suites = new ArrayList<>();
            for (int i = 0; i < suitesLength / 2; i++) suites.add(buffer.getShort() & 0xffff);
            int compressionLength = buffer.get() & 0xff;
            buffer.position(buffer.position() + compressionLength);
            int extensionsLength = buffer.getShort() & 0xffff;
            int extensionsEnd = buffer.position() + extensionsLength;
            Set<Integer> versions = new LinkedHashSet<>();
            while (buffer.position() < extensionsEnd) {
                int type = buffer.getShort() & 0xffff;
                int length = buffer.getShort() & 0xffff;
                if (type == 0x002b) {
                    int listLength = buffer.get() & 0xff;
                    for (int i = 0; i < listLength / 2; i++) versions.add(buffer.getShort() & 0xffff);
                } else {
                    buffer.position(buffer.position() + length);
                }
            }
            return new ClientHello(legacyVersion, suites, versions);
        }
    }
}
