package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.batch.BatchOperationResultDTO;
import com.ses.dto.batch.BatchStatusUpdateRequestDTO;
import com.ses.service.BatchOperationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 一括操作 REST API コントローラー
 */
@RestController
@RequestMapping("/api/batch-operations")
public class BatchOperationApiController {

    @Autowired
    private BatchOperationService batchOperationService;

    /**
     * 要員ステータスの一括更新
     */
    @PostMapping("/engineers/status")
    public ApiResult<BatchOperationResultDTO> updateEngineerStatusBatch(@RequestBody BatchStatusUpdateRequestDTO request) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        BatchOperationResultDTO result = batchOperationService.batchUpdateEngineerStatus(
                request.getIds(), request.getStatus(), userId);
        return ApiResult.success(result);
    }

    /**
     * 案件ステータスの一括更新
     */
    @PostMapping("/projects/status")
    public ApiResult<BatchOperationResultDTO> updateProjectStatusBatch(@RequestBody BatchStatusUpdateRequestDTO request) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        BatchOperationResultDTO result = batchOperationService.batchUpdateProjectStatus(
                request.getIds(), request.getStatus(), userId);
        return ApiResult.success(result);
    }
}
