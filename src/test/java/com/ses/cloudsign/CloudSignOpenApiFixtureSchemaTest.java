package com.ses.cloudsign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 固定OpenAPI 0.36.0 のfixtureが公式契約と一致していることを検証するschema test（HFP-02-AC-01-02）。
 * 公式OpenAPIが更新された場合、このtestの固定値とfixture metaを同時に更新するまで
 * provider adapterの更新を止める（HFP-02-AC-01-04）。
 */
class CloudSignOpenApiFixtureSchemaTest {

    /** research.md §2.1 で固定した公式OpenAPI pin。 */
    static final String PINNED_VERSION = "0.36.0";
    static final String PINNED_SHA256 = "f832681318e67b9fb5fe9a0bb368a570762401dcd4a62b98a934deebb192a240";
    static final String PINNED_LAST_MODIFIED = "2026-08-04 09:40:39 UTC";
    static final String PINNED_URL = "https://api.swaggerhub.com/apis/CloudSign/cloudsign-web_api/0.36.0/swagger.json";

    private final ObjectMapper mapper = new ObjectMapper();

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private JsonNode fixture() throws Exception {
        return mapper.readTree(new ClassPathResource("cloudsign/cloudsign-openapi-0.36.0-fixture.json").getInputStream());
    }

    @Test
    void fixtureMetaは固定OpenAPIのpinと一致する() throws Exception {
        JsonNode meta = fixture().get("meta");
        assertEquals(PINNED_URL, meta.get("source").asText());
        assertEquals(PINNED_VERSION, meta.get("version").asText());
        assertEquals(PINNED_LAST_MODIFIED, meta.get("lastModified").asText());
        assertEquals(PINNED_SHA256, meta.get("sha256").asText());
        assertFalse(meta.get("fetchedAt").asText().isBlank());
    }

    @Test
    void tokenはformUrlEncodedでclientId必須の契約に一致する() throws Exception {
        JsonNode token = fixture().get("token").get("request");
        assertEquals("POST", token.get("method").asText());
        assertEquals("/token", token.get("path").asText());
        assertEquals("application/x-www-form-urlencoded", token.get("contentType").asText());
        assertTrue(strings(token.get("requiredFields")).contains("client_id"));

        JsonNode success = fixture().get("token").get("success200");
        assertEquals("application/json", success.get("contentType").asText());
        JsonNode body = success.get("body");
        assertTrue(body.has("access_token"));
        assertTrue(body.has("expires_in"));
        assertTrue(body.has("token_type"));
        assertEquals(3600, body.get("expires_in").asInt());

        assertTrue(fixture().get("token").has("error400"));
        assertTrue(fixture().get("token").has("error403"));
        assertTrue(fixture().get("token").has("error500"));
    }

    @Test
    void createDocumentはformUrlEncodedの確認済みfieldのみを含む() throws Exception {
        JsonNode req = fixture().get("createDocument").get("request");
        assertEquals("POST", req.get("method").asText());
        assertEquals("/documents", req.get("path").asText());
        assertEquals("application/x-www-form-urlencoded", req.get("contentType").asText());
        List<String> fields = strings(req.get("optionalFields"));
        assertTrue(fields.containsAll(List.of("title", "note", "message", "template_id", "can_transfer", "private")),
                "CREATE fieldは公式0.36.0のものに限定: " + fields);
        assertEquals(fields.size(), 6, "未確認のfieldを追加しない");
        assertEquals(0, req.get("requiredFields").size(), "公式schemaにrequiredは無い");

        JsonNode success = fixture().get("createDocument").get("success200");
        assertEquals("application/json", success.get("contentType").asText());
        JsonNode body = success.get("body");
        assertTrue(body.has("id"));
        assertTrue(body.has("status"));
        assertEquals(0, body.get("status").asInt(), "作成直後はstatus=0（下書き）");
    }

    @Test
    void uploadFileはmultipartでnameとuploadfileの二parts契約に一致する() throws Exception {
        JsonNode req = fixture().get("uploadFile").get("request");
        assertEquals("POST", req.get("method").asText());
        assertEquals("/documents/{documentID}/files", req.get("path").asText());
        assertEquals("multipart/form-data", req.get("contentType").asText());
        assertTrue(strings(req.get("parts")).containsAll(List.of("name", "uploadfile")));
        assertEquals("uploadfile", req.get("binaryPart").asText());

        JsonNode limits = req.get("limits");
        assertEquals(50L * 1024 * 1024, limits.get("bodyMaxBytes").asLong());
        assertEquals(200L * 1024 * 1024, limits.get("totalFilesMaxBytes").asLong());
        assertEquals(100, limits.get("maxFiles").asInt());

        JsonNode body = fixture().get("uploadFile").get("success200").get("body");
        assertTrue(body.get("files").isArray());
        assertEquals("abcdef0123456789abcdef012345678901", body.get("files").get(0).get("id").asText());
        assertTrue(fixture().get("uploadFile").has("error400"));
        assertTrue(fixture().get("uploadFile").has("error413"));
        assertTrue(fixture().get("uploadFile").has("error415"));
    }

    @Test
    void addParticipantはformUrlEncodedでname必須の契約に一致する() throws Exception {
        JsonNode req = fixture().get("addParticipant").get("request");
        assertEquals("POST", req.get("method").asText());
        assertEquals("/documents/{documentID}/participants", req.get("path").asText());
        assertEquals("application/x-www-form-urlencoded", req.get("contentType").asText());
        assertTrue(strings(req.get("requiredFields")).contains("name"));
        assertTrue(strings(req.get("optionalFields")).contains("email"));
        assertEquals("ja", req.get("defaults").get("language_code").asText());

        JsonNode body = fixture().get("addParticipant").get("success200").get("body");
        assertTrue(body.get("participants").isArray());
        assertTrue(body.get("participants").get(0).has("id"));
        assertTrue(fixture().get("addParticipant").has("error409"));
    }

    @Test
    void sendDocumentはPOSTで下書き送信とリマインドの両義契約を含む() throws Exception {
        JsonNode req = fixture().get("sendDocument").get("request");
        assertEquals("POST", req.get("method").asText());
        assertEquals("/documents/{documentID}", req.get("path").asText());
        JsonNode body = fixture().get("sendDocument").get("success200").get("body");
        assertEquals(1, body.get("status").asInt(), "送信成功後はstatus=1（先方確認中）");
        assertTrue(fixture().get("sendDocument").has("error400"));
        assertTrue(fixture().get("sendDocument").has("error403"));
    }

    @Test
    void getDocumentはstatus0から4と未知値をfixtureに持つ() throws Exception {
        JsonNode get = fixture().get("getDocument");
        assertEquals("GET", get.get("request").get("method").asText());
        assertEquals("/documents/{documentID}", get.get("request").get("path").asText());
        assertEquals(0, get.get("draft0").get("body").get("status").asInt());
        assertEquals(1, get.get("confirming1").get("body").get("status").asInt());
        assertEquals(2, get.get("completed2").get("body").get("status").asInt());
        assertEquals(3, get.get("canceled3").get("body").get("status").asInt());
        assertEquals(4, get.get("template4").get("body").get("status").asInt());
        assertEquals(99, get.get("unknown99").get("body").get("status").asInt());
        assertTrue(get.get("completed2").get("body").get("files").get(0).has("id"));
        assertTrue(get.get("completed2").get("body").get("participants").get(0).has("id"));
        assertTrue(get.has("error429"));
    }

    @Test
    void downloadFileとdownloadCertificateはPDF返却の契約に一致する() throws Exception {
        assertEquals("GET", fixture().get("downloadFile").get("request").get("method").asText());
        assertEquals("/documents/{documentID}/files/{fileID}", fixture().get("downloadFile").get("request").get("path").asText());
        assertEquals("application/pdf", fixture().get("downloadFile").get("request").get("successContentType").asText());
        assertEquals("GET", fixture().get("downloadCertificate").get("request").get("method").asText());
        assertEquals("/documents/{documentID}/certificate", fixture().get("downloadCertificate").get("request").get("path").asText());
        assertEquals("application/pdf", fixture().get("downloadCertificate").get("request").get("successContentType").asText());
        assertTrue(fixture().get("downloadCertificate").has("error429"));
    }

    @Test
    void errorModelはerrorとmessageのみの契約に一致する() throws Exception {
        List<String> fields = strings(fixture().get("errorModel").get("fields"));
        assertEquals(List.of("error", "message"), fields);
        List<String> values = strings(fixture().get("errorModel").get("errorValues"));
        assertTrue(values.containsAll(List.of("bad_request", "unauthorized", "forbidden", "not_found",
                "conflict", "too_large_request", "unsupported_media_type", "too_many_requests",
                "internal_server_error")), "error値は公式errorModelの値に限定: " + values);
    }

    @Test
    void statusMappingは公式の0から4と未知値を安全側に分類する() throws Exception {
        JsonNode map = fixture().get("statusMapping");
        assertEquals("下書き", map.get("0").asText());
        assertEquals("先方確認中", map.get("1").asText());
        assertEquals("締結済", map.get("2").asText());
        assertEquals("取消・却下", map.get("3").asText());
        assertEquals("テンプレート", map.get("4").asText());
        assertEquals("要確認", map.get("other").asText());
    }

    @Test
    void rateLimitは公式の800request毎分を超えない() throws Exception {
        assertEquals(800, fixture().get("rateLimit").get("maxRequestsPerTokenPerMinute").asInt());
        assertEquals(60, fixture().get("rateLimit").get("blockSeconds").asInt());
    }
}
