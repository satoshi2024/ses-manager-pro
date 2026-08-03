package com.ses.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Quotation;
import com.ses.mapper.QuotationMapper;
import com.ses.service.QuotationService;
import com.ses.service.approval.ApprovalPayloads;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** 見積提出/受注を承認engineへ接続するadapter。確定処理は既存QuotationServiceへ一度だけ委譲する。 */
@Component
public class QuotationApprovalAdapter implements ApprovalTargetAdapter {
    private final QuotationMapper mapper;
    private final QuotationService service;
    private final ObjectMapper objectMapper;

    public QuotationApprovalAdapter(QuotationMapper mapper, QuotationService service, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override public String requestType() { return "quotation.submit"; }
    @Override public Set<String> supportedRequestTypes() { return Set.of("quotation.submit", "quotation.accept", "quotation.status"); }

    @Override
    public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        Quotation q = require(targetId);
        String status = ApprovalPayloads.text(command, "status");
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("operation", command.getOrDefault("operation", "status"));
        payload.put("status", status == null ? "" : status);
        payload.put("createDraft", Boolean.TRUE.equals(command.get("createDraft")));
        Map<String, Object> diff = new java.util.LinkedHashMap<>();
        diff.put("status", Map.of("label", "見積ステータス", "before", q.getStatus() == null ? "" : q.getStatus(), "after", status == null ? "" : status));
        return new ApprovalSnapshot(version(q.getVersion()), q.getUnitPrice(), null, payload, diff);
    }

    @Override
    public long currentVersion(Long targetId) {
        Quotation q = targetId == null ? null : mapper.selectByIdForUpdate(targetId);
        if (q == null) throw BusinessException.of(404, "error.scope.notFound");
        return version(q.getVersion());
    }

    @Override public void validateBeforeRequest(ApprovalSnapshot snapshot) { }

    @Override
    public void applyApproved(ApprovalRequest request) {
        Map<String, Object> p = ApprovalPayloads.read(objectMapper, request.getPayloadJson());
        Long id = request.getTargetId();
        String status = ApprovalPayloads.text(p, "status");
        if (status != null && !status.isBlank()) service.changeStatus(id, status);
        if (Boolean.TRUE.equals(p.get("createDraft"))) service.createDraftFromQuotation(id);
    }

    private Quotation require(Long id) {
        Quotation q = id == null ? null : mapper.selectById(id);
        if (q == null) throw BusinessException.of(404, "error.scope.notFound");
        return q;
    }

    private long version(Integer version) { return version == null ? 0L : version.longValue(); }
}
