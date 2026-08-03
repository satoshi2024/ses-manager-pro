package com.ses.service.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.ApprovalRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/** 画面/APIからadapterを経由してengineへ申請する統合入口。 */
@Service
@RequiredArgsConstructor
public class ApprovalTargetAdapterRegistry {
    private final List<ApprovalTargetAdapter> adapters;
    private final ApprovalEngineService engine;
    private final ObjectMapper objectMapper;

    public ApprovalRequest request(String requestType, String targetType, Long targetId,
                                   Map<String, Object> command) {
        ApprovalTargetAdapter adapter = adapters.stream()
                .filter(a -> a.supportedRequestTypes().contains(requestType))
                .findFirst()
                .orElseThrow(() -> BusinessException.of(400, "error.approval.targetUnsupported"));
        ApprovalSnapshot snapshot = adapter.snapshot(targetId, command == null ? Map.of() : command);
        adapter.validateBeforeRequest(snapshot);
        String idempotencyKey = key(requestType, targetType, targetId, command);
        return engine.request(new ApprovalRequestCommand(requestType, targetType, targetId,
                snapshot.targetVersion(), SecurityUtils.currentUserId(), snapshot.organizationId(),
                snapshot.amountSnapshot(), snapshot.payload(), snapshot.diff(), idempotencyKey));
    }

    private String key(String requestType, String targetType, Long targetId, Map<String, Object> command) {
        try {
            String raw = requestType + "|" + targetType + "|" + targetId + "|"
                    + objectMapper.writeValueAsString(command == null ? Map.of() : command);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("approval-");
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("承認申請の冪等キー生成に失敗しました", e);
        }
    }
}
