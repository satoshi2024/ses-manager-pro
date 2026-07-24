package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.ai.ProposalDraftDto;
import com.ses.service.ai.ProposalDraftService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI提案文生成APIコントローラー
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiProposalController {

    private final ProposalDraftService proposalDraftService;

    @Data
    public static class ProposalDraftRequest {
        private Long engineerId;
        private Long projectId;
    }

    /**
     * 要員IDと案件IDから提案下書き文・マッチ理由等をAIで生成する。
     */
    @PostMapping("/proposal-draft")
    public ApiResult<ProposalDraftDto> generateDraft(@RequestBody ProposalDraftRequest request) {
        if (request.getEngineerId() == null || request.getProjectId() == null) {
            return ApiResult.error(400, "要員IDと案件IDは必須です");
        }
        
        ProposalDraftDto draft = proposalDraftService.generateDraft(request.getEngineerId(), request.getProjectId());
        return ApiResult.success(draft);
    }
}
