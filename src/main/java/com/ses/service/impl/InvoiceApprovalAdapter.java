package com.ses.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Invoice;
import com.ses.mapper.InvoiceMapper;
import com.ses.service.InvoiceService;
import com.ses.service.approval.ApprovalPayloads;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** 請求送付/取消を承認engineへ接続するadapter。 */
@Component
public class InvoiceApprovalAdapter implements ApprovalTargetAdapter {
    private final InvoiceMapper mapper; private final InvoiceService service; private final ObjectMapper objectMapper;
    public InvoiceApprovalAdapter(InvoiceMapper mapper, InvoiceService service, ObjectMapper objectMapper) { this.mapper = mapper; this.service = service; this.objectMapper = objectMapper; }
    @Override public String requestType() { return "invoice.send"; }
    @Override public Set<String> supportedRequestTypes() { return Set.of("invoice.send", "invoice.void", "invoice.status"); }
    @Override public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        Invoice i = require(targetId); String op = String.valueOf(command.getOrDefault("operation", "send"));
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("operation", op); payload.put("status", command.getOrDefault("status", "送付済")); payload.put("paidDate", command.getOrDefault("paidDate", ""));
        Object nextStatus = command.get("status");
        if (nextStatus == null) nextStatus = "送付済";
        Map<String, Object> diff = new java.util.LinkedHashMap<>();
        Map<String, Object> statusDiff = new java.util.LinkedHashMap<>();
        statusDiff.put("label", "請求ステータス");
        statusDiff.put("before", i.getStatus() == null ? "" : i.getStatus());
        statusDiff.put("after", nextStatus);
        diff.put("status", statusDiff);
        return new ApprovalSnapshot(version(i.getVersion()), i.getTotal(), null, payload, diff);
    }
    @Override
    public long currentVersion(Long targetId) {
        Invoice i = targetId == null ? null : mapper.selectByIdForUpdate(targetId);
        if (i == null) throw BusinessException.of(404, "error.scope.notFound");
        return version(i.getVersion());
    }
    @Override public void validateBeforeRequest(ApprovalSnapshot snapshot) { }
    @Override public void applyApproved(ApprovalRequest request) {
        Map<String, Object> p = ApprovalPayloads.read(objectMapper, request.getPayloadJson());
        if ("void".equals(ApprovalPayloads.text(p, "operation"))) service.voidInvoice(request.getTargetId());
        else service.changeStatus(request.getTargetId(), ApprovalPayloads.text(p, "status"), parseDate(ApprovalPayloads.text(p, "paidDate")));
    }
    private Invoice require(Long id) { Invoice i = id == null ? null : mapper.selectById(id); if (i == null) throw BusinessException.of(404, "error.scope.notFound"); return i; }
    private java.time.LocalDate parseDate(String v) { return v == null || v.isBlank() ? null : java.time.LocalDate.parse(v); }
    private long version(Integer version) { return version == null ? 0L : version.longValue(); }
}
