package com.ses.service.ai.copilot;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.dto.ai.CopilotQueryResult;
import com.ses.dto.ai.CopilotSummaryView;
import com.ses.dto.ai.ResolvedCitationDto;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.ai.copilot.catalog.SemanticCatalogRegistry;
import com.ses.service.ai.copilot.citation.CitationAuthorizationService;
import com.ses.service.ai.copilot.gateway.CatalogQueryGateway;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.parameter.TypedParameterBinder;
import com.ses.service.ai.copilot.result.TypedResultEnvelope;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;
import com.ses.service.ai.copilot.scope.CopilotScopeResolver;
import com.ses.service.ai.copilot.scope.EffectiveScopeSnapshot;
import com.ses.service.ai.copilot.summary.CopilotSummaryService;
import com.ses.service.ai.copilot.summary.SummaryResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Intent → parameter → scope → 正本service → typed result → citation再認可 → summary（B1）。
 */
@Service
public class CopilotQueryService {

    private final AiConfig aiConfig;
    private final IntentParser intentParser;
    private final TypedParameterBinder parameterBinder;
    private final CopilotScopeResolver scopeResolver;
    private final CatalogQueryGateway catalogQueryGateway;
    private final CopilotRunService copilotRunService;
    private final CitationAuthorizationService citationAuthorizationService;
    private final CopilotSummaryService copilotSummaryService;
    private final CopilotExecutionContextFactory contextFactory;
    private final CopilotFeatureGate featureGate;

    /** 旧構築経路を残す場合も、contextのない実行はfail-closedする。 */
    public CopilotQueryService(AiConfig aiConfig, IntentParser intentParser,
                               com.ses.service.ai.copilot.parameter.TypedParameterBinder parameterBinder,
                               CopilotScopeResolver scopeResolver, CatalogQueryGateway catalogQueryGateway,
                               CopilotRunService copilotRunService,
                               CitationAuthorizationService citationAuthorizationService,
                               CopilotSummaryService copilotSummaryService) {
        this(aiConfig, intentParser, parameterBinder, scopeResolver, catalogQueryGateway,
                copilotRunService, citationAuthorizationService, copilotSummaryService, null, null);
    }

    @Autowired
    public CopilotQueryService(AiConfig aiConfig, IntentParser intentParser,
                               com.ses.service.ai.copilot.parameter.TypedParameterBinder parameterBinder,
                               CopilotScopeResolver scopeResolver, CatalogQueryGateway catalogQueryGateway,
                               CopilotRunService copilotRunService,
                               CitationAuthorizationService citationAuthorizationService,
                               CopilotSummaryService copilotSummaryService,
                               CopilotExecutionContextFactory contextFactory,
                               CopilotFeatureGate featureGate) {
        this.aiConfig = aiConfig;
        this.intentParser = intentParser;
        this.parameterBinder = parameterBinder;
        this.scopeResolver = scopeResolver;
        this.catalogQueryGateway = catalogQueryGateway;
        this.copilotRunService = copilotRunService;
        this.citationAuthorizationService = citationAuthorizationService;
        this.copilotSummaryService = copilotSummaryService;
        this.contextFactory = contextFactory;
        this.featureGate = featureGate;
    }

    public CopilotQueryResult query(String question) {
        if (featureGate == null || contextFactory == null) {
            throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
        }
        featureGate.assertQueryAllowed();
        CopilotExecutionContext context = contextFactory.create();
        IntentParser.ParsedIntent parsed = intentParser.parse(question);
        if (!parsed.isSupported()) {
            return unsupported(parsed.queryId(), parsed.reasonCode());
        }

        SemanticCatalogEntry entry = SemanticCatalogRegistry.requireEnabled(parsed.queryId());
        CopilotQueryParameters parameters = parameterBinder.bind(entry.queryId(), question, context);
        EffectiveScopeSnapshot snapshot = context.effectiveScopeSnapshot();
        if (snapshot == null || context.scope() == null || snapshot.scope() != context.scope()) {
            throw BusinessException.of(403, "SCOPE_CONTEXT_REQUIRED");
        }
        // role/catalog boundaryはresolverで検証するが、集合は既にfactoryが束縛した
        // snapshotからのみ取得する。resolverが別instanceを返した場合もfail-closedする。
        CopilotScopeContext scope = scopeResolver.resolve(entry, context);
        if (scope != snapshot.scope()) {
            throw BusinessException.of(403, "SCOPE_CONTEXT_REQUIRED");
        }
        context.bind(entry.queryId(), parameters, scope);
        TypedResultEnvelope envelope = catalogQueryGateway.execute(entry, parameters, scope, context);

        String parameterHash = sha256(parameterBinder.parameterHash(parameters, context));
        CopilotRunService.CopilotRunRecord run = copilotRunService.recordQueryRun(
                entry, parameterHash, context, envelope.values().size());

        List<ResolvedCitationDto> citations = citationAuthorizationService.authorizeAll(
                entry.citationKeys(), context, parameters, scope, scope.scopeHash());
        List<String> availableCitationKeys = citations.stream()
                .filter(ResolvedCitationDto::available)
                .map(ResolvedCitationDto::key)
                .toList();
        SummaryResponse summaryResponse = copilotSummaryService.summarize(
                envelope, context, scope, scope.scopeHash(), run.traceId());
        CopilotSummaryView summary = toSummaryView(summaryResponse);

        return new CopilotQueryResult(
                entry.queryId(),
                entry.catalogVersion(),
                entry.resultSchemaVersion(),
                "SUCCEEDED",
                "指標を取得しました。",
                run.traceId(),
                run.runId(),
                availableCitationKeys,
                citations,
                envelope,
                summary);
    }

    private CopilotSummaryView toSummaryView(SummaryResponse response) {
        if (response == null || !response.isAvailable()) {
            return CopilotSummaryView.unavailable(
                    response == null ? SummaryResponse.STATUS_UNAVAILABLE : response.providerStatus());
        }
        return new CopilotSummaryView(
                response.summaryText(),
                response.claimKeys(),
                response.providerStatus(),
                response.modelVersion(),
                true);
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
                List.of(),
                null,
                CopilotSummaryView.unavailable(SummaryResponse.STATUS_UNAVAILABLE));
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
