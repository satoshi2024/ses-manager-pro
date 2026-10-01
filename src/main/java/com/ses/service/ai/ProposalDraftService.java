package com.ses.service.ai;

import com.ses.dto.ai.ProposalDraftDto;
import com.ses.service.ai.copilot.CopilotExecutionContext;

public interface ProposalDraftService {
    /**
     * エンジニアIDと案件IDから提案の下書き文を生成する
     * @param engineerId エンジニアID
     * @param projectId 案件ID
     * @return 提案下書き情報
     */
    ProposalDraftDto generateDraft(Long engineerId, Long projectId);

    /** 同一legacy boundary snapshotをgatewayまで渡す。未実装の互換経路はfail-closed。 */
    default ProposalDraftDto generateDraft(Long engineerId, Long projectId,
                                            CopilotExecutionContext context) {
        throw com.ses.common.exception.BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
    }
}
