package com.ses.service.ai.copilot.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.ses.dto.ai.ResolvedCitationDto;
import com.ses.service.ai.AiPiiMasker;
import com.ses.service.ai.copilot.IntentParser;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import com.ses.service.ai.copilot.summary.CopilotSummaryValidationException;
import com.ses.service.ai.copilot.summary.CopilotSummaryValidator;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 固定 adversarial fixture の各 case を pipeline コンポーネントへ投入する。
 */
public final class CopilotAdversarialCaseRunner {

    private final IntentParser intentParser = new IntentParser();

    public boolean runCase(JsonNode cse) {
        if (cse == null || cse.isMissingNode()) {
            return false;
        }
        return switch (cse.path("type").asText("")) {
            case "INTENT" -> runIntent(cse);
            case "SUMMARY" -> runSummary(cse);
            case "PII" -> runPii(cse);
            case "SCOPE" -> runScope(cse);
            case "CITATION" -> runCitation(cse);
            default -> false;
        };
    }

    public boolean hasDeniedPiiLeak(JsonNode fixture) {
        for (JsonNode cse : fixture.withArray("cases")) {
            if (!"PII".equals(cse.path("type").asText())) {
                continue;
            }
            if (hasDeniedLeak(cse.path("allowlistedFields"))) {
                return true;
            }
        }
        return false;
    }

    private boolean runIntent(JsonNode cse) {
        IntentParser.ParsedIntent parsed = intentParser.parse(cse.path("question").asText());
        String expectedQueryId = cse.path("expectedQueryId").asText(null);
        String expectedReason = cse.path("expectedReasonCode").asText(null);
        return expectedQueryId != null
                && expectedQueryId.equals(parsed.queryId())
                && expectedReason != null
                && expectedReason.equals(parsed.reasonCode());
    }

    private boolean runSummary(JsonNode cse) {
        String summaryText = cse.path("summaryText").asText(null);
        List<String> allowed = readStringList(cse.path("allowedClaimKeys"));
        List<String> claims = readStringList(cse.path("claimKeys"));
        boolean expectedRejected = cse.path("expectedRejected").asBoolean(false);
        try {
            CopilotSummaryValidator.validateSummaryText(summaryText);
            CopilotSummaryValidator.validateClaimKeys(claims, allowed);
            return !expectedRejected;
        } catch (CopilotSummaryValidationException ex) {
            return expectedRejected;
        }
    }

    private boolean runPii(JsonNode cse) {
        JsonNode fields = cse.path("allowlistedFields");
        if (!fields.isObject()) {
            return false;
        }
        return !hasDeniedLeak(fields);
    }

    private boolean runScope(JsonNode cse) {
        String queryId = cse.path("queryId").asText(null);
        if (queryId == null) {
            return false;
        }
        SemanticCatalogEntry entry = SemanticCatalogRegistry.find(queryId).orElse(null);
        if (entry == null) {
            return false;
        }
        boolean expectedEnabled = cse.path("expectedEnabled").asBoolean(true);
        return entry.enabled() == expectedEnabled;
    }

    private boolean runCitation(JsonNode cse) {
        String citationKey = cse.path("citationKey").asText(null);
        String role = cse.path("role").asText(null);
        boolean expectedAvailable = cse.path("expectedAvailable").asBoolean(false);
        ResolvedCitationDto resolved = resolveCitation(citationKey, role);
        return resolved.available() == expectedAvailable;
    }

    static ResolvedCitationDto resolveCitation(String citationKey, String role) {
        if (citationKey == null || citationKey.isBlank()) {
            return ResolvedCitationDto.unavailable(citationKey);
        }
        SemanticCatalogEntry entry = SemanticCatalogRegistry.find(citationKey).orElse(null);
        if (entry == null || !entry.enabled()) {
            return ResolvedCitationDto.unavailable(citationKey);
        }
        if (role == null || !entry.allowedRoles().contains(role)) {
            return ResolvedCitationDto.unavailable(citationKey);
        }
        if ("HR".equals(role) || "要員".equals(role)) {
            return ResolvedCitationDto.unavailable(citationKey);
        }
        return new ResolvedCitationDto(citationKey, citationKey, "/fixture", true);
    }

    private static boolean hasDeniedLeak(JsonNode fields) {
        if (!fields.isObject()) {
            return true;
        }
        Iterator<Map.Entry<String, JsonNode>> it = fields.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String key = entry.getKey();
            if (AiPiiMasker.NEVER_SEND.contains(key) || !AiPiiMasker.ALLOWED_SEND.contains(key)) {
                return true;
            }
            String value = entry.getValue() == null ? "" : entry.getValue().asText("");
            if (containsDeniedToken(key, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDeniedToken(String key, String value) {
        String hay = (key + " " + value).toLowerCase(Locale.ROOT);
        return hay.contains("gender") || hay.contains("birthdate") || hay.contains("nationality")
                || hay.contains("fullname") || hay.contains("age") || hay.contains("religion");
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }
}
