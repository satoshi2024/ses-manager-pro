package com.ses.service.ai.impl;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.AiFeedback;
import com.ses.service.ai.AiFeedbackService;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class AiFeedbackServiceImpl implements AiFeedbackService {

    public static final Set<String> REASON_CODES = Set.of(
            "SKILL_MISMATCH", "PRICE_MISMATCH", "AVAILABILITY", "LOCATION",
            "CUSTOMER_REQUEST", "ALREADY_ASSIGNED", "OTHER_REDACTED");

    private static final Set<String> DECISIONS = Set.of("ACCEPT", "REJECT", "HOLD");

    private final CopilotFeatureGate featureGate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiFeedback record(FeedbackCommand command, CopilotExecutionContext context) {
        // この旧推薦feedbackのscope binding schema/reauthorization contractは未完成。
        // feature flagだけでなくservice直呼出しも停止し、itemId/runIdだけでの認可・更新を防ぐ。
        if (command == null || command.itemId() == null || command.runId() == null
                || context == null || context.effectiveScopeSnapshot() == null
                || context.scope() == null || context.scope() != context.effectiveScopeSnapshot().scope()
                || context.queryId() == null || context.parameters() == null
                || !context.queryId().equals(context.parameters().queryId())
                || !context.tenantId().equals(context.effectiveScopeSnapshot().tenantId())
                || !context.legalEntityId().equals(context.effectiveScopeSnapshot().legalEntityId())
                || !context.asOfDate().equals(context.effectiveScopeSnapshot().asOf())
                || context.scopeHash() == null
                || context.effectiveScopeSnapshot().scopeHash() == null
                || context.scope().scopeHash() == null
                || !context.scopeHash().equals(context.effectiveScopeSnapshot().scopeHash())
                || !context.scopeHash().equals(context.scope().scopeHash())
                || SecurityUtils.currentUserId() == null) {
            throw BusinessException.of(403, "SCOPE_CONTEXT_REQUIRED");
        }
        if (command.decision() != null && !command.decision().isBlank()
                && !DECISIONS.contains(command.decision())) {
            throw new BusinessException(400, "decision が不正です");
        }
        if (command.reasonCode() != null && !command.reasonCode().isBlank()
                && !REASON_CODES.contains(command.reasonCode())) {
            throw new BusinessException(400, "reasonCode が不正です");
        }
        featureGate.assertQueryAllowed();
        throw BusinessException.of(503, "AI_FEEDBACK_SCOPE_CONTRACT_REQUIRED");
    }
}
