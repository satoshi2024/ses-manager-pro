package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.batch.BatchApplyRequestDTO;
import com.ses.dto.batch.BatchOperationResultDTO;
import com.ses.dto.batch.BatchPreviewResultDTO;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.service.BatchOperationService;
import com.ses.service.EngineerService;
import com.ses.service.ProjectService;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchOperationServiceImpl implements BatchOperationService {

    private final EngineerService engineerService;
    private final ProjectService projectService;
    private final DataScopeService dataScopeService;

    private static final int MAX_BATCH_SIZE = 200;
    private static final String SECRET_KEY = "SES_MANAGER_BATCH_TOKEN_SECRET";
    private static final long TOKEN_EXPIRATION_MS = 15 * 60 * 1000L; // 15分

    private static final Set<String> VALID_ENGINEER_STATUSES = Set.of("稼動中", "提案中", "退場予定", "Bench", "待機");
    private static final Set<String> VALID_PROJECT_STATUSES = Set.of("募集中", "選考中", "充足", "クローズ");

    @Override
    public BatchPreviewResultDTO previewEngineerStatusUpdate(List<Long> ids, String targetStatus, Long currentUserId) {
        validateBatchSize(ids);
        validateEngineerStatus(targetStatus);

        BatchPreviewResultDTO dto = new BatchPreviewResultDTO();
        if (ids == null || ids.isEmpty()) {
            dto.setPreviewToken(generateToken("engineer", List.of(), targetStatus));
            return dto;
        }

        dto.setTotalCount(ids.size());

        // P2-07 性能改善: ループ外で一度だけ許可IDリストを取得
        Set<Long> allowedIds = dataScopeService.isScoped() ? dataScopeService.allowedEngineerIds() : null;

        int validCount = 0;
        int invalidCount = 0;

        for (Long id : ids) {
            BatchPreviewResultDTO.PreviewItem item = new BatchPreviewResultDTO.PreviewItem();
            item.setId(id);
            item.setTargetStatus(targetStatus);

            if (allowedIds != null && !allowedIds.contains(id)) {
                item.setValid(false);
                item.setReason("変更権限がありません");
                invalidCount++;
            } else {
                Engineer engineer = engineerService.getById(id);
                if (engineer == null) {
                    item.setValid(false);
                    item.setReason("対象要員が存在しません");
                    invalidCount++;
                } else {
                    item.setCurrentStatus(engineer.getStatus());
                    item.setValid(true);
                    item.setReason("正常");
                    validCount++;
                }
            }
            dto.getItems().add(item);
        }

        dto.setValidCount(validCount);
        dto.setInvalidCount(invalidCount);
        dto.setPreviewToken(generateToken("engineer", ids, targetStatus));
        return dto;
    }

    @Override
    public BatchOperationResultDTO applyEngineerStatusUpdate(BatchApplyRequestDTO request, Long currentUserId) {
        if (request == null) {
            throw new BusinessException(400, "リクエスト情報が空です");
        }
        List<Long> ids = request.getIds();
        String targetStatus = request.getStatus();
        validateBatchSize(ids);
        validateEngineerStatus(targetStatus);
        verifyToken("engineer", ids, targetStatus, request.getPreviewToken());

        BatchOperationResultDTO result = new BatchOperationResultDTO();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        result.setTotalCount(ids.size());

        Set<Long> allowedIds = dataScopeService.isScoped() ? dataScopeService.allowedEngineerIds() : null;

        for (Long id : ids) {
            try {
                if (allowedIds != null && !allowedIds.contains(id)) {
                    throw new BusinessException(403, "指定の要員に対する変更権限がありません (ID: " + id + ")");
                }
                Engineer engineer = engineerService.getById(id);
                if (engineer == null) {
                    throw new BusinessException(404, "対象要員が見つかりません (ID: " + id + ")");
                }
                engineer.setStatus(targetStatus);
                boolean updated = engineerService.updateById(engineer);
                if (!updated) {
                    throw new BusinessException(409, "更新に失敗しました");
                }
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
    public BatchPreviewResultDTO previewProjectStatusUpdate(List<Long> ids, String targetStatus, Long currentUserId) {
        validateBatchSize(ids);
        validateProjectStatus(targetStatus);

        BatchPreviewResultDTO dto = new BatchPreviewResultDTO();
        if (ids == null || ids.isEmpty()) {
            dto.setPreviewToken(generateToken("project", List.of(), targetStatus));
            return dto;
        }

        dto.setTotalCount(ids.size());
        Set<Long> allowedIds = dataScopeService.isScoped() ? dataScopeService.allowedProjectIds() : null;

        int validCount = 0;
        int invalidCount = 0;

        for (Long id : ids) {
            BatchPreviewResultDTO.PreviewItem item = new BatchPreviewResultDTO.PreviewItem();
            item.setId(id);
            item.setTargetStatus(targetStatus);

            if (allowedIds != null && !allowedIds.contains(id)) {
                item.setValid(false);
                item.setReason("変更権限がありません");
                invalidCount++;
            } else {
                Project project = projectService.getById(id);
                if (project == null) {
                    item.setValid(false);
                    item.setReason("対象案件が存在しません");
                    invalidCount++;
                } else {
                    item.setCurrentStatus(project.getStatus());
                    item.setValid(true);
                    item.setReason("正常");
                    validCount++;
                }
            }
            dto.getItems().add(item);
        }

        dto.setValidCount(validCount);
        dto.setInvalidCount(invalidCount);
        dto.setPreviewToken(generateToken("project", ids, targetStatus));
        return dto;
    }

    @Override
    public BatchOperationResultDTO applyProjectStatusUpdate(BatchApplyRequestDTO request, Long currentUserId) {
        if (request == null) {
            throw new BusinessException(400, "リクエスト情報が空です");
        }
        List<Long> ids = request.getIds();
        String targetStatus = request.getStatus();
        validateBatchSize(ids);
        validateProjectStatus(targetStatus);
        verifyToken("project", ids, targetStatus, request.getPreviewToken());

        BatchOperationResultDTO result = new BatchOperationResultDTO();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        result.setTotalCount(ids.size());
        Set<Long> allowedIds = dataScopeService.isScoped() ? dataScopeService.allowedProjectIds() : null;

        for (Long id : ids) {
            try {
                if (allowedIds != null && !allowedIds.contains(id)) {
                    throw new BusinessException(403, "指定の案件に対する変更権限がありません (ID: " + id + ")");
                }
                Project project = projectService.getById(id);
                if (project == null) {
                    throw new BusinessException(404, "対象案件が見つかりません (ID: " + id + ")");
                }
                project.setStatus(targetStatus);
                boolean updated = projectService.updateById(project);
                if (!updated) {
                    throw new BusinessException(409, "更新に失敗しました");
                }
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

    private void validateEngineerStatus(String status) {
        if (!StringUtils.hasText(status) || !VALID_ENGINEER_STATUSES.contains(status)) {
            throw new BusinessException(400, "無効な要員ステータスです: " + status);
        }
    }

    private void validateProjectStatus(String status) {
        if (!StringUtils.hasText(status) || !VALID_PROJECT_STATUSES.contains(status)) {
            throw new BusinessException(400, "無効な案件ステータスです: " + status);
        }
    }

    private String generateToken(String resource, List<Long> ids, String targetStatus) {
        long expiresAt = System.currentTimeMillis() + TOKEN_EXPIRATION_MS;
        String hash = computeIdsHash(ids);
        String payload = resource + ":" + targetStatus + ":" + hash + ":" + expiresAt;
        String signature = hmacSha256(payload);
        return payload + ":" + signature;
    }

    private void verifyToken(String resource, List<Long> ids, String targetStatus, String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(400, "プレビューTokenがありません");
        }
        String[] parts = token.split(":");
        if (parts.length != 5) {
            throw new BusinessException(400, "プレビューTokenの形式が不正です");
        }
        String res = parts[0];
        String status = parts[1];
        String hash = parts[2];
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "プレビューTokenの有効期限が不正です");
        }
        String expectedSignature = parts[4];

        if (System.currentTimeMillis() > expiresAt) {
            throw new BusinessException(400, "プレビューTokenの有効期限が切れています");
        }
        String payload = res + ":" + status + ":" + hash + ":" + expiresAt;
        String actualSignature = hmacSha256(payload);
        if (!Objects.equals(expectedSignature, actualSignature)) {
            throw new BusinessException(400, "プレビューTokenの署名が改ざんされています");
        }
        if (!res.equals(resource) || !status.equals(targetStatus) || !hash.equals(computeIdsHash(ids))) {
            throw new BusinessException(400, "対象リクエストがプレビュー内容と一致しません");
        }
    }

    private String computeIdsHash(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "empty";
        }
        List<Long> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        String str = sorted.toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(str.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(encodedhash);
        } catch (Exception e) {
            return String.valueOf(str.hashCode());
        }
    }

    private String hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new RuntimeException("HMAC計算に失敗しました", e);
        }
    }
}
