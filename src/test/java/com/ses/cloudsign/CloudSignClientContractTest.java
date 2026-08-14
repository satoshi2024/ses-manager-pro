package com.ses.cloudsign;

import com.ses.entity.ContractDocument;
import com.ses.service.CloudSignClient;
import com.ses.service.impl.CloudSignClientImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 現行 CloudSignClientImpl の wire 契約違反を固定する characterization test（HFP-02-01）。
 * 公式契約（research.md / fixture）との差分を red で再現し、HFP-02-03 の修正基準とする。
 * Mock が核心 external call を迂回せず、実際の HTTP request を capture して検査する。
 */
class CloudSignClientContractTest {

    private RestTemplate rest;
    private CloudSignClientImpl client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        client = new CloudSignClientImpl(rest);
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "baseUrl", "https://api.cloudsign.jp");
        ReflectionTestUtils.setField(client, "token", "01234567-89ab-cdef-0123-456789abcdef");
    }

    private ContractDocument document() {
        ContractDocument d = new ContractDocument();
        d.setId(1L);
        d.setContractId(100L);
        d.setRecipientName("マスク宛先");
        d.setRecipientEmail("recipient-masked@example.invalid");
        d.setPdfPath("C:/fake/uploads/contracts/100/document-1.pdf");
        return d;
    }

    @Test
    void sendは公式契約multipartではなくJSON一回で送信しsourcePDFをuploadしない() {
        server.expect(requestTo("https://api.cloudsign.jp/documents"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    MediaType ct = request.getHeaders().getContentType();
                    assertNotNull(ct, "Content-Typeが存在する");
                    assertTrue(ct.toString().startsWith("multipart/form-data"),
                            "公式契約はmultipart/form-dataだが現実装は " + ct + " を使う");
                    byte[] body = ((MockClientHttpRequest) request).getBodyAsBytes();
                    String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                    assertTrue(text.contains("uploadfile"), "multipart part uploadfile が必須");
                    assertTrue(text.contains("%PDF-"), "送信原本のPDF bytesがmultipartに含まれる");
                })
                .andRespond(withSuccess(
                        "{\"id\":\"0123456789abcdef0123456789abcdef01\",\"file_id\":\"f1\",\"status\":0}",
                        MediaType.APPLICATION_JSON));

        client.send(document());

        server.verify();
        // red: 現行は application/json で /documents を一回呼ぶだけで上記assertが失敗する
    }

    @Test
    void sendは公式4工程_createUploadParticipantSend_の順序で呼び出さない() {
        server.expect(requestTo("https://api.cloudsign.jp/documents"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"id\":\"0123456789abcdef0123456789abcdef01\",\"status\":0}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.cloudsign.jp/documents/0123456789abcdef0123456789abcdef01/files"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"id\":\"0123456789abcdef0123456789abcdef01\",\"status\":0}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.cloudsign.jp/documents/0123456789abcdef0123456789abcdef01/participants"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"id\":\"0123456789abcdef0123456789abcdef01\",\"status\":0}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.cloudsign.jp/documents/0123456789abcdef0123456789abcdef01"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"id\":\"0123456789abcdef0123456789abcdef01\",\"status\":1}",
                        MediaType.APPLICATION_JSON));

        client.send(document());

        // red: 現行は create の1回しか呼ばず、4工程中3工程が欠落するため verify が失敗する
        server.verify();
    }

    @Test
    void sendはclient_idからPOST_tokenでBearerを取得せず静的tokenを使う() {
        server.expect(requestTo("https://api.cloudsign.jp/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    MediaType ct = request.getHeaders().getContentType();
                    assertNotNull(ct);
                    assertTrue(ct.toString().startsWith("application/x-www-form-urlencoded"),
                            "tokenはform-urlencoded契約");
                    String body = new String(((MockClientHttpRequest) request).getBodyAsBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    assertTrue(body.contains("client_id"), "token requestにclient_idを含む");
                })
                .andRespond(withSuccess(
                        "{\"access_token\":\"01234567-89ab-cdef-0123-456789abcdef\",\"expires_in\":3600,\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.cloudsign.jp/documents"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"id\":\"0123456789abcdef0123456789abcdef01\",\"status\":0}",
                        MediaType.APPLICATION_JSON));

        client.send(document());

        // red: 現行は /token を呼ばず静的 token を Authorization へ設定するため verify が失敗する
        server.verify();
    }

    @Test
    void mutationTimeoutは結果不明として区別せず単に失敗にする() {
        server.expect(requestTo("https://api.cloudsign.jp/documents"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withException(new SocketTimeoutException("read timeout")));

        Exception ex = assertThrows(Exception.class, () -> client.send(document()));

        // red: 現行は BusinessException(cloudsignFailed) で握り潰し、結果不明(uncertain)を
        // 型/状態で区別せず、呼び出し側のリカバリ(operation ID照合等)へ情報を渡さない
        assertNotNull(ex);
        assertTrue(ex.getClass().getSimpleName().contains("CloudSign"),
                "結果不明を表現する例外型が必要だが現行は " + ex.getClass().getName());
        server.verify();
    }

    @Test
    void mutationTimeout後に同じmutationを自動retryしない() {
        server.expect(requestTo("https://api.cloudsign.jp/documents"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withException(new SocketTimeoutException("read timeout")));

        try {
            client.send(document());
        } catch (Exception ignored) {
        }

        // red条件: 現行はretryしないが、結果不明を成功へ丸めず、かつ自動再送経路を作らないこと。
        // ここでは送信処理が例外で終わった時点で送信requestが1回しか行われていないことを契約とする。
        server.verify();
    }
}
