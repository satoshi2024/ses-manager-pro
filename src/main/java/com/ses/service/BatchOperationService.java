package com.ses.service;

import com.ses.dto.batch.BatchApplyRequestDTO;
import com.ses.dto.batch.BatchOperationResultDTO;
import com.ses.dto.batch.BatchPreviewResultDTO;

import java.util.List;

public interface BatchOperationService {

    /**
     * 要員一括更新 プレビュー（上限200件）
     */
    BatchPreviewResultDTO previewEngineerStatusUpdate(List<Long> ids, String targetStatus, Long currentUserId);

    /**
     * 要員一括更新 適用（署名付き Token 検証、200件上限）
     */
    BatchOperationResultDTO applyEngineerStatusUpdate(BatchApplyRequestDTO request, Long currentUserId);

    /**
     * 案件一括更新 プレビュー（上限200件）
     */
    BatchPreviewResultDTO previewProjectStatusUpdate(List<Long> ids, String targetStatus, Long currentUserId);

    /**
     * 案件一括更新 適用（署名付き Token 検証、200件上限）
     */
    BatchOperationResultDTO applyProjectStatusUpdate(BatchApplyRequestDTO request, Long currentUserId);
}
