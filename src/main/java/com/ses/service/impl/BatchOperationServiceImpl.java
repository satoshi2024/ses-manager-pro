package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.batch.BatchOperationResultDTO;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.service.BatchOperationService;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchOperationServiceImpl implements BatchOperationService {

    private final EngineerMapper engineerMapper;
    private final ProjectMapper projectMapper;
    private final DataScopeService dataScopeService;

    private static final int MAX_BATCH_SIZE = 100;

    @Override
    public BatchOperationResultDTO batchUpdateEngineerStatus(List<Long> ids, String targetStatus, Long currentUserId) {
        validateBatchSize(ids);

        BatchOperationResultDTO result = new BatchOperationResultDTO();
        if (ids == null || ids.isEmpty()) {
            return result;
        }

        result.setTotalCount(ids.size());

        for (Long id : ids) {
            try {
                if (dataScopeService.isScoped() && !dataScopeService.allowedEngineerIds().contains(id)) {
                    throw new BusinessException(403, "指定の要員に対する変更権限がありません (ID: " + id + ")");
                }

                Engineer engineer = engineerMapper.selectById(id);
                if (engineer == null) {
                    throw new BusinessException(404, "対象要員が見つかりません (ID: " + id + ")");
                }

                engineer.setStatus(targetStatus);
                engineerMapper.updateById(engineer);
                result.setSuccessCount(result.getSuccessCount() + 1);
            } catch (Exception e) {
                String errorMsg = e instanceof BusinessException ? e.getMessage() : "更新処理に失敗しました";
                result.setFailureCount(result.getFailureCount() + 1);
                result.getErrors().add(new BatchOperationResultDTO.ItemError(id, errorMsg));
                log.warn("要員一括更新で一部失敗が発生しました: engineerId={}, err={}", id, errorMsg);
            }
        }

        return result;
    }

    @Override
    public BatchOperationResultDTO batchUpdateProjectStatus(List<Long> ids, String targetStatus, Long currentUserId) {
        validateBatchSize(ids);

        BatchOperationResultDTO result = new BatchOperationResultDTO();
        if (ids == null || ids.isEmpty()) {
            return result;
        }

        result.setTotalCount(ids.size());

        for (Long id : ids) {
            try {
                if (dataScopeService.isScoped() && !dataScopeService.allowedProjectIds().contains(id)) {
                    throw new BusinessException(403, "指定の案件に対する変更権限がありません (ID: " + id + ")");
                }

                Project project = projectMapper.selectById(id);
                if (project == null) {
                    throw new BusinessException(404, "対象案件が見つかりません (ID: " + id + ")");
                }

                project.setStatus(targetStatus);
                projectMapper.updateById(project);
                result.setSuccessCount(result.getSuccessCount() + 1);
            } catch (Exception e) {
                String errorMsg = e instanceof BusinessException ? e.getMessage() : "更新処理に失敗しました";
                result.setFailureCount(result.getFailureCount() + 1);
                result.getErrors().add(new BatchOperationResultDTO.ItemError(id, errorMsg));
                log.warn("案件一括更新で一部失敗が発生しました: projectId={}, err={}", id, errorMsg);
            }
        }

        return result;
    }

    private void validateBatchSize(List<Long> ids) {
        if (ids != null && ids.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(400, "一括処理の上限件数 (" + MAX_BATCH_SIZE + "件) を超えています");
        }
    }
}
