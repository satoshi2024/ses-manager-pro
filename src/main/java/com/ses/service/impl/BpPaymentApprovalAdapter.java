package com.ses.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.BpPayment;
import com.ses.mapper.BpPaymentMapper;
import com.ses.service.InvoiceService;
import com.ses.service.approval.ApprovalPayloads;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/** BP支払確定を承認engineへ接続するadapter。 */
@Component
public class BpPaymentApprovalAdapter implements ApprovalTargetAdapter {
    private final BpPaymentMapper mapper; private final InvoiceService service; private final ObjectMapper objectMapper;
    public BpPaymentApprovalAdapter(BpPaymentMapper mapper, InvoiceService service, ObjectMapper objectMapper) { this.mapper = mapper; this.service = service; this.objectMapper = objectMapper; }
    @Override public String requestType() { return "bp_payment.confirm"; }
    @Override public Set<String> supportedRequestTypes() { return Set.of("bp_payment.confirm"); }
    @Override public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        BpPayment p = require(targetId);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("status", command.getOrDefault("status", "支払済")); payload.put("paidDate", command.getOrDefault("paidDate", ""));
        Object nextStatus = command.get("status");
        if (nextStatus == null) nextStatus = "支払済";
        Map<String, Object> diff = new java.util.LinkedHashMap<>();
        Map<String, Object> statusDiff = new java.util.LinkedHashMap<>();
        statusDiff.put("label", "BP支払ステータス");
        statusDiff.put("before", p.getStatus() == null ? "" : p.getStatus());
        statusDiff.put("after", nextStatus);
        diff.put("status", statusDiff);
        return new ApprovalSnapshot(version(p.getUpdatedAt()), p.getAmount(), null, payload, diff);
    }
    @Override public void validateBeforeRequest(ApprovalSnapshot snapshot) { }
    @Override public void applyApproved(ApprovalRequest request) {
        Map<String, Object> p = ApprovalPayloads.read(objectMapper, request.getPayloadJson());
        service.changeBpPaymentStatus(request.getTargetId(), ApprovalPayloads.text(p, "status"), parseDate(ApprovalPayloads.text(p, "paidDate")));
    }
    private BpPayment require(Long id) { BpPayment p = id == null ? null : mapper.selectById(id); if (p == null) throw BusinessException.of(404, "error.scope.notFound"); return p; }
    private java.time.LocalDate parseDate(String v) { return v == null || v.isBlank() ? null : java.time.LocalDate.parse(v); }
    private Long version(LocalDateTime t) { return t == null ? null : t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(); }
}
