package com.ses.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Acceptance;
import com.ses.entity.ApprovalRequest;
import com.ses.mapper.AcceptanceMapper;
import com.ses.service.AcceptanceService;
import com.ses.service.approval.ApprovalPayloads;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * 検収取消を承認engineへ接続するadapter（order-acceptance-workflow design §4 / R3.4）。
 * 検収済work recordの再open/金額変更には検収取消承認を必要とする。
 */
@Component
public class AcceptanceApprovalAdapter implements ApprovalTargetAdapter {

    private final AcceptanceMapper mapper;
    private final AcceptanceService service;
    private final ObjectMapper objectMapper;

    public AcceptanceApprovalAdapter(AcceptanceMapper mapper, AcceptanceService service, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public String requestType() {
        return "acceptance.cancel";
    }

    @Override
    public Set<String> supportedRequestTypes() {
        return Set.of("acceptance.cancel");
    }

    @Override
    public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        Acceptance acceptance = require(targetId);
        BigDecimal amount = acceptance.getAmountSnapshot() != null
                ? acceptance.getAmountSnapshot().abs() : BigDecimal.ZERO;
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("contractId", acceptance.getContractId());
        payload.put("workMonth", acceptance.getWorkMonth());
        payload.put("reason", command.getOrDefault("reason", ""));
        Map<String, Object> diff = new java.util.LinkedHashMap<>();
        diff.put("status", Map.of("label", "検収状態", "before",
                acceptance.getStatus() == null ? "" : acceptance.getStatus(), "after", "取消（差戻し）"));
        return new ApprovalSnapshot(version(acceptance.getVersion()), amount, null, payload, diff);
    }

    @Override
    public long currentVersion(Long targetId) {
        return version(require(targetId).getVersion());
    }

    @Override
    public void validateBeforeRequest(ApprovalSnapshot snapshot) {
        // 状態・scopeは申請API側で担保する。
    }

    @Override
    public void applyApproved(ApprovalRequest request) {
        service.applyCancellation(request.getTargetId());
    }

    private Acceptance require(Long id) {
        Acceptance acceptance = id == null ? null : mapper.selectById(id);
        if (acceptance == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return acceptance;
    }

    private long version(Integer version) {
        return version == null ? 0L : version.longValue();
    }
}
