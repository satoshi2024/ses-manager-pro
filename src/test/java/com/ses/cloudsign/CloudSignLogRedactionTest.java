package com.ses.cloudsign;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.enums.CloudSignErrorCode;
import com.ses.config.CloudSignProperties;
import com.ses.dto.cloudsign.AddParticipantRequest;
import com.ses.dto.cloudsign.CloudSignDocument;
import com.ses.dto.cloudsign.CreateDocumentRequest;
import com.ses.service.cloudsign.CloudSignApiClientImpl;
import com.ses.service.cloudsign.CloudSignApiException;
import com.ses.service.cloudsign.CloudSignErrorClassifier;
import com.ses.service.cloudsign.CloudSignTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HFP-02-08: log redactionの攻撃的検査（HFP-02-AC-02-02 / AC-08-05 / AC-09-03）。
 * token値・client ID・provider raw message内のPII・PDF本文・宛先メール全文が
 * application logへ漏れないことをlog captureで検証する。
 */
class CloudSignLogRedactionTest {

    private static final String TOKEN = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String CLIENT_ID = "top-secret-client-id-123456";
    private static final String SECRET_EMAIL = "victim-secret@example.com";
    private static final String RAW_PDF_BYTES = "%PDF-1.4 secret-pdf-bytes-raw-content %%EOF";
    private static final String BASE = "https://api-sandbox.cloudsign.jp";

    private RestTemplate rest;
    private MockRestServiceServer server;
    private CloudSignProperties properties;
    private CloudSignApiClientImpl client;
    private ListAppender<ILoggingEvent> appender;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        rootLogger = (Logger) LoggerFactory.getLogger("com.ses.service.cloudsign");
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        properties = new CloudSignProperties();
        properties.setEnabled(true);
        properties.setEnvironment("SANDBOX");
        properties.setClientId(CLIENT_ID);
        CloudSignErrorClassifier classifier = new CloudSignErrorClassifier(new ObjectMapper());
        CloudSignTokenProvider tokenProvider = new CloudSignTokenProvider(properties, rest, classifier);
        client = new CloudSignApiClientImpl(properties, rest, tokenProvider, classifier);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(appender);
    }

    private String allLogs() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void token取得ログにtoken値とclientIdを出さない() {
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"0123456789abcdef0123456789abcdef01\",\"status\":0}",
                        MediaType.APPLICATION_JSON));

        client.createDocument(new CreateDocumentRequest("t", "n", null));

        String logs = allLogs();
        assertFalse(logs.contains(TOKEN), "access token値をログへ出さない");
        assertFalse(logs.contains(CLIENT_ID), "client IDをログへ出さない");
    }

    @Test
    void providerエラーmessage内のPIIとPDF本文をログへ出さない() {
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));
        // 500 bodyにPII入りmessageとPDF本文を仕込む
        server.expect(requestTo(BASE + "/documents")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"error\":\"internal_server_error\",\"message\":\""
                                + SECRET_EMAIL + " " + RAW_PDF_BYTES + "\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        CloudSignApiException ex = assertThrows(CloudSignApiException.class,
                () -> client.createDocument(new CreateDocumentRequest("t", "n", null)));

        assertEquals(CloudSignErrorCode.SERVER_ERROR, ex.getCode());
        String logs = allLogs();
        assertFalse(logs.contains(SECRET_EMAIL), "provider message内の宛先メール全文をログへ出さない");
        assertFalse(logs.contains(RAW_PDF_BYTES), "PDF本文をログへ出さない");
        assertFalse(logs.contains(TOKEN), "token値をログへ出さない");
    }

    @Test
    void 例外messageとDTOに宛先メール全文を出さない() {
        server.expect(requestTo(BASE + "/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"0123456789abcdef0123456789abcdef01\",\"status\":0}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/documents/0123456789abcdef0123456789abcdef01/participants"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"0123456789abcdef0123456789abcdef01\",\"status\":0}",
                        MediaType.APPLICATION_JSON));

        CloudSignDocument doc = client.createDocument(new CreateDocumentRequest("t", "n", null));
        client.addParticipant(doc.id(), new AddParticipantRequest("マスク宛先", SECRET_EMAIL, null, "ja"));

        String logs = allLogs();
        assertFalse(logs.contains(SECRET_EMAIL), "宛先メール全文をログへ出さない");
    }
}
