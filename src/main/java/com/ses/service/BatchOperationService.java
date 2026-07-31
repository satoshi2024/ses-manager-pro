package com.ses.service;

import com.ses.dto.batch.BatchOperationResultDTO;

import java.util.List;

public interface BatchOperationService {

    /**
     * 要員ステータス一括更新（上限100件、一部失敗許容）
     */
    BatchOperationResultDTO batchUpdateEngineerStatus(List<Long> ids, String targetStatus, Long currentUserId);

    /**
     * 案件ステータス一括更新（上限100件、一部失敗許容）
     */
    BatchOperationResultDTO batchUpdateProjectStatus(List<Long> ids, String targetStatus, Long currentUserId);
}
