package com.ses.cloudsign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.enums.CloudSignErrorCode;
import com.ses.config.CloudSignProperties;
import com.ses.dto.cloudsign.AddParticipantRequest;
import com.ses.dto.cloudsign.CloudSignDocument;
import com.ses.dto.cloudsign.CreateDocumentRequest;
import com.ses.dto.cloudsign.PdfDownload;
import com.ses.service.cloudsign.CloudSignApiClientImpl;
import com.ses.service.cloudsign.CloudSignApiException;
import com.ses.service.cloudsign.CloudSignErrorClassifier;
import com.ses.service.cloudsign.CloudSignTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * 固定OpenAPI 0.36.0に閉じたtyped client（HFP-02-03）のwire契約test。
 * 実際のHTTP requestをcaptureし、method/path/media type/form/multipart/Bearer/順序/binary hash/call countを検査する。
 * Mockが核心external callを迂回せず、source PDF bytesの同一性をrequest captureで証明する。
 */
class CloudSignClientContractTest {

    private static final String TOKEN_BODY =
            "{\"access_token\":\"01234567-89ab-cdef-0123-456789abcdef\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";
    private static final String DOC_ID = "0123456789abcdef0123456789abcdef01";
    private static final String FILE_ID = "abcdef0123456789abcdef012345678901";
    private static final String BASE = "https://api-sandbox.cloudsign.jp";

    private RestTemplate rest;
    private MockRestServiceServer server;
    private CloudSignProperties properties;
    private CloudSignApiClientImpl client;

    @BeforeEach
    void setUp() {
        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        properties = new CloudSignProperties();
        properties.setEnabled(true);
        properties.setEnvironment("SANDBOX");
        properties.setClientId("masked-client-id");
        CloudSignErrorClassifier classifier = new CloudSignErrorClassifier(new ObjectMapper());
        CloudSignTokenProvider tokenProvider = new CloudSignTokenProvider(properties, rest, classifier);
        client = new CloudSignApiClientImpl(properties, rest, tokenProvider, classifier);
    }

    private static byte[] samplePdf() {
        return ("%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte v : digest) {
            sb.append(String.format("%02x", v));
        }
        return sb.toString();
    }

    private static String sha256Unchecked(byte[] data) {
        try {
            return sha256(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String documentJson(String status) {
        return "{\"id\":\"" + DOC_ID + "\",\"title\":\"SES契約書 100\",\"status\":" + status + ","
                + "\"files\":[{\"id\":\"" + FILE_ID + "\",\"name\":\"document-1.pdf\",\"order\":0,\"total_pages\":1}],"
                + "\"participants\":[{\"id\":\"fedcba9876543210fedcba9876543210\",\"name\":\"マスク宛先\","
                + "\"email\":\"recipient-masked@example.invalid\",\"order\":0,\"status\":2}]}";
    }

    private String documentWithoutId() {
        return "{\"title\":\"x\",\"status\":0}";
    }

    @Test
    void tokenはformUrlEncodedでclientIdを送りBearerを返す() {
        server.expect(requestTo(BASE + "/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    MediaType ct = request.getHeaders().getContentType();
                    assertNotNull(ct, "tokenのContent-Typeが必要");
                    assertTrue(ct.toString().startsWith("application/x-www-form-urlencoded"),
                            "tokenはform-urlencoded契約だが " + ct);
                    String body = new String(((MockClientHttpRequest) request).getBodyAsBytes(),
                            StandardCharsets.UTF_8);
                    assertTrue(body.contains("client_id=masked-client-id"),
                            "token requestにclient_idを含む: " + body);
                })
                .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertEquals("Bearer 01234567-89ab-cdef-0123-456789abcdef",
                        request.getHeaders().getFirst("Authorization")))
                .andRespond(withSuccess(documentJson("0"), MediaType.APPLICATION_JSON));

        CloudSignDocument doc = client.createDocument(new CreateDocumentRequest("SES契約書 100", "op:1", null));

        assertEquals(DOC_ID, doc.id());
        assertEquals(0, doc.status());
        server.verify();
    }

    @Test
    void 公式4工程を厳密な順序で直列実行する() {
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(documentJson("0"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents/" + DOC_ID + "/files"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(documentJson("0"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents/" + DOC_ID + "/participants"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(documentJson("0"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents/" + DOC_ID))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(documentJson("1"), MediaType.APPLICATION_JSON));

        CloudSignDocument doc = client.createDocument(new CreateDocumentRequest("SES契約書 100", "op:1", null));
        client.uploadFile(doc.id(), "document-1.pdf", samplePdf());
        client.addParticipant(doc.id(), new AddParticipantRequest("マスク宛先", "recipient-masked@example.invalid", null, "ja"));
        CloudSignDocument sent = client.sendDocument(doc.id());

        assertEquals(1, sent.status(), "送信成功後はstatus=1（先方確認中）");
        server.verify();
    }

    @Test
    void uploadはmultipartで送信原本bytesと同一のPDFを送る() throws Exception {
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(documentJson("0"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents/" + DOC_ID + "/files"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    MediaType ct = request.getHeaders().getContentType();
                    assertNotNull(ct);
                    assertTrue(ct.toString().startsWith("multipart/form-data"),
                            "uploadはmultipart契約だが " + ct);
                    byte[] body = ((MockClientHttpRequest) request).getBodyAsBytes();
                    String text = new String(body, StandardCharsets.ISO_8859_1);
                    assertTrue(text.contains("uploadfile"), "multipart part uploadfile が必須");
                    assertTrue(text.contains("document-1.pdf"), "multipart part name が必須");
                    // multipart body内のPDF bytesが送信原本とbyte一致すること（request captureで証明）
                    byte[] pdf = samplePdf();
                    assertTrue(containsBytes(body, pdf),
                            "multipartに送信原本bytesがそのまま含まれること");
                    assertEquals(sha256Unchecked(pdf), sha256Unchecked(extractPdfFromMultipart(body, pdf.length)),
                            "送信原本のSHA-256がwire上のPDFと一致する");
                })
                .andRespond(withSuccess(documentJson("0"), MediaType.APPLICATION_JSON));

        CloudSignDocument doc = client.createDocument(new CreateDocumentRequest("SES契約書 100", "op:1", null));
        client.uploadFile(doc.id(), "document-1.pdf", samplePdf());

        server.verify();
    }

    @Test
    void mutationTimeoutは結果不明を表現し同じmutationを再送しない() {
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/documents")).andExpect(method(HttpMethod.POST))
                .andRespond(withException(new SocketTimeoutException("read timeout")));

        CloudSignApiException ex = assertThrows(CloudSignApiException.class,
                () -> client.createDocument(new CreateDocumentRequest("t", "n", null)));

        assertEquals(CloudSignErrorCode.TIMEOUT, ex.getCode());
        assertTrue(ex.isUncertain(), "mutationのtimeoutは結果不明として扱う");
        server.verify();
    }

    @Test
    void mutationの504は結果不明として扱う() {
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/documents")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT));

        CloudSignApiException ex = assertThrows(CloudSignApiException.class,
                () -> client.createDocument(new CreateDocumentRequest("t", "n", null)));

        assertEquals(CloudSignErrorCode.SERVER_ERROR, ex.getCode());
        assertTrue(ex.isUncertain(), "mutationの504は結果不明として扱う");
        server.verify();
    }

    @Test
    void status401は一操作につき一回だけtoken再取得して再実行する() {
        // 実flow順: token取得 → documents(401) → token再取得 → documents(200)
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(documentJson("0"), MediaType.APPLICATION_JSON));

        CloudSignDocument doc = client.createDocument(new CreateDocumentRequest("t", "n", null));

        assertEquals(DOC_ID, doc.id());
        server.verify();
    }

    @Test
    void status401が続く場合は繰り返さずUNAUTHORIZEDで停止する() {
        // 実flow順: token取得 → documents(401) → token再取得(1回だけ) → documents(401)で停止
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        CloudSignApiException ex = assertThrows(CloudSignApiException.class,
                () -> client.createDocument(new CreateDocumentRequest("t", "n", null)));

        assertEquals(CloudSignErrorCode.UNAUTHORIZED, ex.getCode());
        assertFalse(ex.isUncertain());
        server.verify();
    }

    @Test
    void error分類_429_403_404_413_415_をsafeCodeへ分類する() {
        // tokenは初回のみ取得（以後cache）。全期待をrequest前に宣言する必要がある。
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents")).andRespond(withStatus(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(BASE + "/documents")).andRespond(withStatus(
                org.springframework.http.HttpStatus.FORBIDDEN));
        server.expect(requestTo(BASE + "/documents")).andRespond(withStatus(
                org.springframework.http.HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE + "/documents")).andRespond(withStatus(
                org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE));
        server.expect(requestTo(BASE + "/documents")).andRespond(withStatus(
                org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE));

        assertEquals(CloudSignErrorCode.RATE_LIMITED, assertThrows(CloudSignApiException.class,
                () -> client.createDocument(new CreateDocumentRequest("t", "n", null))).getCode());
        assertEquals(CloudSignErrorCode.FORBIDDEN, assertThrows(CloudSignApiException.class,
                () -> client.createDocument(new CreateDocumentRequest("t", "n", null))).getCode());
        assertEquals(CloudSignErrorCode.NOT_FOUND, assertThrows(CloudSignApiException.class,
                () -> client.createDocument(new CreateDocumentRequest("t", "n", null))).getCode());
        assertEquals(CloudSignErrorCode.TOO_LARGE, assertThrows(CloudSignApiException.class,
                () -> client.createDocument(new CreateDocumentRequest("t", "n", null))).getCode());
        assertEquals(CloudSignErrorCode.UNSUPPORTED_MEDIA, assertThrows(CloudSignApiException.class,
                () -> client.createDocument(new CreateDocumentRequest("t", "n", null))).getCode());
        server.verify();
    }

    @Test
    void 必須field欠落のresponseはschemaErrorにする() {
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(documentWithoutId(), MediaType.APPLICATION_JSON));

        // 未知fieldは許容し、必須id欠落はschema error（HFP-02-AC-01-03: 成功扱いで黙殺しない）
        CloudSignApiException ex = assertThrows(CloudSignApiException.class,
                () -> client.createDocument(new CreateDocumentRequest("t", "n", null)));

        assertEquals(CloudSignErrorCode.MALFORMED_RESPONSE, ex.getCode());
        server.verify();
    }

    @Test
    void downloadはtempFileへstreamしPDFbytesが回収できる() throws Exception {
        byte[] pdf = samplePdf();
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents/" + DOC_ID + "/files/" + FILE_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(pdf, MediaType.APPLICATION_PDF));

        PdfDownload download = client.downloadFile(DOC_ID, FILE_ID);

        assertTrue(Files.exists(download.tempPath()), "temp quarantineファイルが存在する");
        assertEquals(pdf.length, download.sizeBytes());
        assertArrayEquals(pdf, Files.readAllBytes(download.tempPath()), "download bytesが一致する");
        server.verify();
    }

    @Test
    void downloadはsize上限を超えるとTOO_LARGEで失敗する() throws Exception {
        properties.setMaxPdfBytes(16);
        byte[] pdf = samplePdf();
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents/" + DOC_ID + "/files/" + FILE_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(pdf, MediaType.APPLICATION_PDF));

        CloudSignApiException ex = assertThrows(CloudSignApiException.class,
                () -> client.downloadFile(DOC_ID, FILE_ID));

        assertEquals(CloudSignErrorCode.TOO_LARGE, ex.getCode());
        server.verify();
    }

    @Test
    void tokenはsingleFlightで同時取得を一回にまとめる() throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andExpect(request -> tokenCalls.incrementAndGet())
                .andRespond(withSuccess(TOKEN_BODY, MediaType.APPLICATION_JSON));
        CountDownLatch bothReady = new CountDownLatch(2);
        server.expect(times(2), requestTo(BASE + "/documents")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(documentJson("0"), MediaType.APPLICATION_JSON));

        Runnable task = () -> {
            bothReady.countDown();
            try {
                bothReady.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            client.createDocument(new CreateDocumentRequest("t", "n", null));
        };
        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        t1.join(10000);
        t2.join(10000);

        assertEquals(1, tokenCalls.get(), "同一JVM内のtoken取得はsingle-flightで1回");
        server.verify();
    }

    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** multipart bodyからPDF bytes（needle長）を取り出す（quoted-printable等で分割されない前提のfixture検証）。 */
    private static byte[] extractPdfFromMultipart(byte[] body, int length) {
        String text = new String(body, StandardCharsets.ISO_8859_1);
        int start = text.indexOf("%PDF-");
        assertTrue(start >= 0, "multipart内に%PDF-が見つからない");
        byte[] result = new byte[length];
        System.arraycopy(body, start, result, 0, length);
        return result;
    }
}
