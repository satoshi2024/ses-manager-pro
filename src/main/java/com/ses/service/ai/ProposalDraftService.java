package com.ses.service.ai;

import com.ses.dto.ai.ProposalDraftDto;

public interface ProposalDraftService {
    /**
     * エンジニアIDと案件IDから提案の下書き文を生成する
     * @param engineerId エンジニアID
     * @param projectId 案件ID
     * @return 提案下書き情報
     */
    ProposalDraftDto generateDraft(Long engineerId, Long projectId);
}
