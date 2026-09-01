package com.ses.service.ai.copilot;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.dto.ai.CopilotQueryResult;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import com.ses.service.ai.copilot.gateway.CatalogQueryGateway;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.parameter.TypedParameterBinder;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.ai.copilot.scope.CopilotScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Intent → parameter → scope → 正本service → typed result のオーケストレーション（F2）。LLMは呼ばない。
 */
@Service
@RequiredArgsConstructor
public class CopilotQueryService {

    private final AiConfig aiConfig;
    private final IntentParser intentParser;
    private final TypedParameterBinder parameterBinder;
    private final CopilotScopeResolver scopeResolver;
    private final CatalogQueryGateway catalogQueryGateway;
    private final CopilotRunService copilotRunService;

    public CopilotQueryResult query(String question) {
        assertCopilotEnabled();
        IntentParser.ParsedIntent parsed = intentParser.parse(question);
        if (!parsed.isSupported()) {
            return unsupported(parsed.queryId(), parsed.reasonCode());
        }

        SemanticCatalogEntry entry = SemanticCatalogRegistry.requireEnabled(parsed.queryId());
        CopilotQueryParameters parameters = parameterBinder.bind(entry.queryId(), question);
        CopilotScopeContext scope = scopeResolver.resolve(entry);
        TypedResultEnvelope envelope = catalogQueryGateway.execute(entry, parameters, scope);

        String parameterHash = sha256(parameterBinder.parameterHash(parameters));
        CopilotRunService.CopilotRunRecord run = copilotRunService.recordQueryRun(
                entry, parameterHash, scope.scopeHash(), envelope.values().size());

        return new CopilotQueryResult(
                entry.queryId(),
                entry.catalogVersion(),
                entry.resultSchemaVersion(),
                "SUCCEEDED",
                "指標を取得しました。",
                run.traceId(),
                run.runId(),
                entry.citationKeys(),
                envelope);
    }

    private CopilotQueryResult unsupported(String queryId, String reasonCode) {
        return new CopilotQueryResult(
                queryId,
                SemanticCatalogRegistry.CATALOG_VERSION,
                SemanticCatalogRegistry.RESULT_SCHEMA_VERSION,
                reasonCode,
                messageFor(reasonCode),
                null,
                null,
                List.of(),
                null);
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
            case "CATALOG_DISABLED" -> "この分析queryは現在利用できません。";
            default -> "分析queryを処理できませんでした。";
        };
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
