package com.ses.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T109 L0: G10 allowlist が禁止属性と交差せず、全fieldに送信可否と根拠があり、
 * provider別に保存期間が定義されていることを文書契約から検証する。本番コードは変更しない。
 */
class AiG10AllowlistDocumentTest {

    private static final Path JSON = Path.of(
            ".kiro", "specs", "ai-feedback-learning", "g10-allowlist.json");
    private static final Path MARKDOWN = Path.of(
            ".kiro", "specs", "ai-feedback-learning", "g10-pii-allowlist.md");
    private static final Path DESIGN = Path.of(
            ".kiro", "specs", "ai-feedback-learning", "design.md");

    private static JsonNode root;
    private static String markdown;
    private static String design;

    @BeforeAll
    static void load() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        root = mapper.readTree(Files.readString(JSON, StandardCharsets.UTF_8));
        markdown = Files.readString(MARKDOWN, StandardCharsets.UTF_8);
        design = Files.readString(DESIGN, StandardCharsets.UTF_8);
    }

    @Test
    void g10KeepsMockRuleAndDisablesProductionExternalSend() {
        JsonNode g10 = root.get("g10");
        assertEquals("MOCK_RULE_DEFAULT_NO_EXTERNAL_SEND", g10.get("decision").asText());
        assertEquals("mock", g10.get("defaultProvider").asText());
        assertFalse(g10.get("productionExternalSend").asBoolean());
        assertEquals("GATE-S17-G10-PROD", g10.get("productionReleaseGate").asText());
        Set<String> allowed = new HashSet<>();
        g10.get("allowedRuntimeProvidersWithoutDpa").forEach(n -> allowed.add(n.asText()));
        assertEquals(Set.of("mock", "rule"), allowed);
        assertFalse(allowed.contains("gemini"));
    }

    @Test
    void everyAllowlistFieldHasSendFlagAndNonBlankRationale() {
        for (String arrayName : new String[] {"matchingFeatures", "sendOnlyFields"}) {
            for (JsonNode field : root.get(arrayName)) {
                String id = field.get("id").asText();
                assertTrue(field.has("send"), id + " に send がない");
                String rationale = field.get("rationale").asText();
                assertFalse(rationale == null || rationale.isBlank(), id + " の根拠が空");
            }
        }
    }

    @Test
    void allowlistDoesNotIntersectDeniedAttributes() {
        Set<String> denied = textSet(root.get("deniedAttributeIds"));
        Set<String> matching = ids(root.get("matchingFeatures"));
        Set<String> sendOnly = ids(root.get("sendOnlyFields"));
        Set<String> neverSend = textSet(root.get("neverSend"));

        assertTrue(disjoint(matching, denied), "matchingFeatures が禁止属性と交差する: "
                + intersection(matching, denied));
        assertTrue(disjoint(sendOnly, denied), "sendOnlyFields が禁止属性と交差する: "
                + intersection(sendOnly, denied));
        assertTrue(disjoint(matching, neverSend), "matchingFeatures が neverSend と交差する: "
                + intersection(matching, neverSend));
        assertTrue(disjoint(sendOnly, neverSend), "sendOnlyFields が neverSend と交差する: "
                + intersection(sendOnly, neverSend));
        assertTrue(neverSend.contains("engineer.fullName"));
        assertTrue(neverSend.contains("engineer.birthDate"));
        assertTrue(neverSend.contains("engineer.nationality"));
        assertTrue(neverSend.contains("engineer.gender"));
        assertTrue(neverSend.contains("engineer.photoUrl"));
        assertTrue(denied.contains("engineer.birthDate"));
        assertTrue(denied.contains("engineer.gender"));
        assertTrue(denied.contains("engineer.nationality"));
        assertTrue(denied.contains("engineer.photoUrl"));
        assertTrue(denied.contains("engineer.fullName"));
    }

    @Test
    void designDeniedTermsBlockPrefixedAllowlistIds() {
        Set<String> allowlistIds = new HashSet<>();
        allowlistIds.addAll(ids(root.get("matchingFeatures")));
        allowlistIds.addAll(ids(root.get("sendOnlyFields")));

        Set<String> requiredTokens = Set.of("age", "gender", "nationality", "birthDate");
        Set<String> allTokens = new HashSet<>(requiredTokens);
        for (JsonNode binding : root.get("designDeniedTermBindings")) {
            String term = binding.get("term").asText();
            assertTrue(design.contains(term), "design.md §5.2 に禁止語がない: " + term);
            binding.get("tokens").forEach(t -> allTokens.add(t.asText()));
        }
        assertTrue(allTokens.containsAll(requiredTokens),
                "対応表に age/gender/nationality/birthDate が無い");

        for (String id : allowlistIds) {
            for (String token : allTokens) {
                assertFalse(idContainsDeniedToken(id, token),
                        id + " が禁止token " + token + " と交差する");
            }
        }
        assertTrue(idContainsDeniedToken("engineer.age", "age"));
        assertTrue(idContainsDeniedToken("engineer.birthDate", "birthDate"));
        assertTrue(idContainsDeniedToken("age", "age"));
        assertFalse(idContainsDeniedToken("engineer.experienceYears", "age"));
        assertFalse(idContainsDeniedToken("engineer.initialName", "fullName"));
    }

    @Test
    void eachProviderDefinesRetentionAndGeminiIsUnsigned() {
        boolean sawMock = false;
        boolean sawRule = false;
        boolean sawGemini = false;
        for (JsonNode provider : root.get("providers")) {
            String id = provider.get("id").asText();
            assertEquals(0, provider.get("retentionDaysRawPrompt").asInt(),
                    id + " の raw prompt 保存期間は 0 でなければならない");
            assertTrue(provider.get("retentionDaysRedacted").asInt() > 0,
                    id + " の redacted 保存期間が未定義");
            assertTrue(provider.get("retentionDaysLegacyAiLog").asInt() > 0,
                    id + " の legacy t_ai_log 保存期間が未定義");
            assertFalse(provider.get("region").asText().isBlank(), id + " の region が空");
            assertFalse(provider.get("dpa").asText().isBlank(), id + " の DPA が空");
            assertFalse(provider.get("productionSend").asBoolean(),
                    id + " は G10 時点で productionSend=false");
            switch (id) {
                case "mock" -> {
                    sawMock = true;
                    assertTrue(provider.get("runtimeAllowedWithoutGate").asBoolean());
                }
                case "rule" -> {
                    sawRule = true;
                    assertTrue(provider.get("runtimeAllowedWithoutGate").asBoolean());
                }
                case "gemini" -> {
                    sawGemini = true;
                    assertEquals("UNSIGNED", provider.get("dpa").asText());
                    assertFalse(provider.get("runtimeAllowedWithoutGate").asBoolean());
                }
                default -> throw new AssertionError("未知のprovider: " + id);
            }
        }
        assertTrue(sawMock && sawRule && sawGemini, "mock/rule/gemini の保存期間定義が不足");
    }

    @Test
    void markdownKeepsDesignDeniedListAndRecordsG10MockDefault() {
        assertTrue(markdown.contains("mock/rule"));
        assertTrue(markdown.contains("GATE-S17-G10-PROD"));
        assertTrue(markdown.contains("SES-PII-CANARY-T109-7f2e9c1a"));
        assertTrue(markdown.contains("730日"));
        assertTrue(design.contains("禁止属性リスト"));
        assertTrue(design.contains("本籍"));
        assertTrue(design.contains("性別"));
        assertTrue(design.contains("顔写真"));
        assertTrue(markdown.contains("本籍"));
        assertTrue(markdown.contains("交差は **0件**") || markdown.contains("交差は 0件"));
    }

    @Test
    void japaneseLevelIsSendOnlyNotMatchingFeatureOrSegment() {
        Set<String> matching = ids(root.get("matchingFeatures"));
        assertFalse(matching.contains("engineer.japaneseLevel"));
        boolean found = false;
        for (JsonNode field : root.get("sendOnlyFields")) {
            if ("engineer.japaneseLevel".equals(field.get("id").asText())) {
                found = true;
                assertTrue(field.get("send").asBoolean());
            }
        }
        assertTrue(found, "japaneseLevel は send-only でなければならない");
        JsonNode workLocation = matchingField("project.workLocation");
        assertTrue(workLocation.get("send").asBoolean());
        assertTrue(workLocation.get("segment").asBoolean());
        assertEquals("prefecture-municipality", workLocation.get("grain").asText());
        assertTrue(markdown.contains("prefecture-municipality")
                || markdown.contains("都道府県および市区町村"));
        JsonNode earlyExit = root.path("outcomes").path("earlyExit");
        assertTrue(earlyExit.get("requireOccurredAtBeforeOriginalEndDate").asBoolean());
        assertTrue(earlyExit.get("sameDayCancellationIsNotEarlyExit").asBoolean());
        assertTrue(markdown.contains("original_end_date"));
        assertTrue(markdown.contains("当日解約"));
        assertTrue(root.path("outcomes").get("winExistsNotCount").asBoolean());
        for (JsonNode field : root.get("matchingFeatures")) {
            if (field.path("segment").asBoolean(false)) {
                String id = field.get("id").asText();
                assertTrue(id.contains("skill") || id.contains("Price") || id.contains("unitPrice")
                                || id.contains("prefecture") || id.contains("workLocation")
                                || id.contains("remoteType") || id.contains("skillNames"),
                        "segment軸が skill/単価/勤務地以外: " + id);
            }
        }
    }

    private static JsonNode matchingField(String id) {
        for (JsonNode field : root.get("matchingFeatures")) {
            if (id.equals(field.get("id").asText())) {
                return field;
            }
        }
        throw new AssertionError("matchingFeatures に無い: " + id);
    }

    static boolean idContainsDeniedToken(String id, String token) {
        if (id == null || token == null || token.isBlank()) {
            return false;
        }
        String needle = token.toLowerCase();
        for (String part : id.split("[._]")) {
            if (part.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> ids(JsonNode array) {
        Set<String> out = new HashSet<>();
        array.forEach(n -> out.add(n.get("id").asText()));
        return out;
    }

    private static Set<String> textSet(JsonNode array) {
        Set<String> out = new HashSet<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static boolean disjoint(Set<String> a, Set<String> b) {
        Set<String> copy = new HashSet<>(a);
        copy.retainAll(b);
        return copy.isEmpty();
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        Set<String> copy = new HashSet<>(a);
        copy.retainAll(b);
        return copy;
    }
}
