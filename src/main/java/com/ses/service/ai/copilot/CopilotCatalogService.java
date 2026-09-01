package com.ses.service.ai.copilot;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.dto.ai.CopilotCatalogResult;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * catalog解決とrun記録のオーケストレーション（F1）。正本service呼出しはF2。
 */
@Service
@RequiredArgsConstructor
public class CopilotCatalogService {

    private final AiConfig aiConfig;
    private final IntentParser intentParser;
    private final CopilotRunService copilotRunService;

    public CopilotCatalogResult resolveAndRecord(String question) {
        assertCopilotEnabled();
        IntentParser.ParsedIntent parsed = intentParser.parse(question);
        if (!parsed.isSupported()) {
            return new CopilotCatalogResult(
                    parsed.queryId(),
                    SemanticCatalogRegistry.CATALOG_VERSION,
                    SemanticCatalogRegistry.RESULT_SCHEMA_VERSION,
                    parsed.reasonCode(),
                    messageFor(parsed.reasonCode()),
                    null,
                    null,
                    List.of());
        }

        SemanticCatalogEntry entry = SemanticCatalogRegistry.requireEnabled(parsed.queryId());
        String parameterHash = sha256("question-only:" + normalizeHashInput(question));
        String scopeHash = sha256("scope:pending-f2");
        CopilotRunService.CopilotRunRecord run = copilotRunService.recordCatalogRun(entry, parameterHash, scopeHash);

        return new CopilotCatalogResult(
                entry.queryId(),
                entry.catalogVersion(),
                entry.resultSchemaVersion(),
                "CATALOG_RESOLVED",
                "catalog queryを解決しました。指標取得は次段階で実行されます。",
                run.traceId(),
                run.runId(),
                entry.citationKeys());
    }

    private void assertCopilotEnabled() {
        if (!aiConfig.isManagementCopilotEnabled()) {
            throw new BusinessException(503, "経営コパイロットは現在無効化されています。");
        }
        if (aiConfig.isExternalSendEnabled()) {
            throw new BusinessException(503, "コパイロットの外部provider送信は許可されていません。");
        }
    }

    private static String messageFor(String reasonCode) {
        return switch (reasonCode) {
            case "CATALOG_NOT_FOUND" -> "対応可能な分析queryを特定できませんでした。";
            case "AMBIGUOUS_PARAMETER" -> "質問の対象が複数の分析queryに該当します。対象を一つに絞ってください。";
            case "EMPTY_QUESTION" -> "質問を入力してください。";
            default -> "分析queryを処理できませんでした。";
        };
    }

    private static String normalizeHashInput(String question) {
        return question == null ? "" : question.trim();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return "0".repeat(64);
        }
    }
}
