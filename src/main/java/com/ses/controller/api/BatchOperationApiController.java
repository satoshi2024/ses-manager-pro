package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.batch.BatchApplyRequestDTO;
import com.ses.dto.batch.BatchOperationResultDTO;
import com.ses.dto.batch.BatchPreviewResultDTO;
import com.ses.dto.batch.BatchStatusUpdateRequestDTO;
import com.ses.service.BatchOperationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 一括操作 REST API コントローラー (2段階 preview / apply 必須化)
 */
@RestController
@RequestMapping("/api/batch-operations")
public class BatchOperationApiController {

    @Autowired
    private BatchOperationService batchOperationService;

    /**
     * 要員ステータス一括更新 プレビュー
     */
    @PostMapping("/engineers/preview")
    public ApiResult<BatchPreviewResultDTO> previewEngineerStatusBatch(@RequestBody BatchStatusUpdateRequestDTO request) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        BatchPreviewResultDTO result = batchOperationService.previewEngineerStatusUpdate(
                request.getIds(), request.getStatus(), userId);
        return ApiResult.success(result);
    }

    /**
     * 要員ステータス一括更新 適用
     */
    @PostMapping("/engineers/apply")
    public ApiResult<BatchOperationResultDTO> applyEngineerStatusBatch(@RequestBody BatchApplyRequestDTO request) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        BatchOperationResultDTO result = batchOperationService.applyEngineerStatusUpdate(request, userId);
        return ApiResult.success(result);
    }

    /**
     * 案件ステータス一括更新 プレビュー
     */
    @PostMapping("/projects/preview")
    public ApiResult<BatchPreviewResultDTO> previewProjectStatusBatch(@RequestBody BatchStatusUpdateRequestDTO request) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        BatchPreviewResultDTO result = batchOperationService.previewProjectStatusUpdate(
                request.getIds(), request.getStatus(), userId);
        return ApiResult.success(result);
    }

    /**
     * 案件ステータス一括更新 適用
     */
    @PostMapping("/projects/apply")
    public ApiResult<BatchOperationResultDTO> applyProjectStatusBatch(@RequestBody BatchApplyRequestDTO request) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        BatchOperationResultDTO result = batchOperationService.applyProjectStatusUpdate(request, userId);
        return ApiResult.success(result);
    }
}
