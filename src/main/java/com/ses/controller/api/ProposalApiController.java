package com.ses.controller.api;

import com.ses.dto.proposal.ProposalKanbanDto;
import com.ses.service.ProposalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.ses.common.result.ApiResult;
import com.ses.entity.Proposal;

/**
 * 提案APIコントローラー
 */
@RestController
@RequestMapping("/api/proposals")
@RequiredArgsConstructor
public class ProposalApiController {

    private final ProposalService proposalService;

    /**
     * かんばんリスト取得
     *
     * @return 提案かんばんDTOリスト
     */
    @GetMapping("/kanban")
    public ApiResult<List<ProposalKanbanDto>> getKanbanList() {
        return ApiResult.success(proposalService.getKanbanList());
    }

    /**
     * ステータス変更
     *
     * @param id 提案ID
     * @param request body (ステータスを含む)
     * @return 結果
     */
    @PutMapping("/{id}/status")
    public ApiResult<Boolean> changeStatus(@PathVariable Long id, @RequestBody Map<String, String> request) {
        String newStatus = request.get("status");
        proposalService.changeStatus(id, newStatus);
        return ApiResult.success(true);
    }

    /**
     * 新規提案
     */
    @PostMapping
    public ApiResult<Boolean> save(@RequestBody Proposal proposal) {
        return ApiResult.success(proposalService.save(proposal));
    }
}
