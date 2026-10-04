package com.ses.service.ai.copilot.summary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 実際にgatewayへ渡すCopilot summary templateと版の単一正本。 */
public final class CopilotSummaryPromptTemplate {

    public static final String VERSION = "nf08-f1";
    public static final String TASK_MARKER = "[TASK:MANAGEMENT_COPILOT]";
    public static final String OUTPUT_SCHEMA_VERSION = "nf08-summary-1";
    public static final String MOCK_RESPONSE_VERSION = "mock-response-v1";
    public static final String TRUSTED_INSTRUCTION = """
            Return JSON only: {"summaryText":"...","claimKeys":["..."]}.
            summaryText must not contain HTML, numbers, currency symbols, or recalculated metrics.
            claimKeys must be chosen only from summary.claimKeys in ALLOWLIST_CONTEXT.
            """;

    private static final String HASH = sha256(String.join("\n", VERSION, TASK_MARKER,
            OUTPUT_SCHEMA_VERSION, TRUSTED_INSTRUCTION, MOCK_RESPONSE_VERSION));

    private CopilotSummaryPromptTemplate() {
    }

    public static String templateHash() {
        return HASH;
    }

    /** local adapter identity is derived from values that actually select its behavior. */
    public static String localConfigHash(String provider, String modelId) {
        return sha256(String.join("\n", "MANAGEMENT_COPILOT", provider, modelId, VERSION, HASH,
                MOCK_RESPONSE_VERSION));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Copilot template identity hash failed", ex);
        }
    }
}
