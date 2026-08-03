package com.ses.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Contract;
import com.ses.mapper.ContractMapper;
import com.ses.service.ContractService;
import com.ses.service.approval.ApprovalPayloads;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/** 契約稼動化/単価改定を承認engineへ接続するadapter。 */
@Component
public class ContractApprovalAdapter implements ApprovalTargetAdapter {
    private final ContractMapper mapper;
    private final ContractService service;
    private final ObjectMapper objectMapper;

    public ContractApprovalAdapter(ContractMapper mapper, ContractService service, ObjectMapper objectMapper) {
        this.mapper = mapper; this.service = service; this.objectMapper = objectMapper;
    }
    @Override public String requestType() { return "contract.activate"; }
    @Override public Set<String> supportedRequestTypes() { return Set.of("contract.activate", "contract.revisePrice", "contract.status"); }

    @Override
    public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        Contract c = require(targetId);
        String operation = String.valueOf(command.getOrDefault("operation", "status"));
        BigDecimal amount = c.getSellingPrice();
        if ("revisePrice".equals(operation)) {
            BigDecimal next = ApprovalPayloads.decimal(command, "sellingPrice");
            amount = next == null || c.getSellingPrice() == null ? null : next.subtract(c.getSellingPrice()).abs();
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("operation", operation); payload.put("status", command.getOrDefault("status", ""));
        payload.put("sellingPrice", command.getOrDefault("sellingPrice", "")); payload.put("costPrice", command.getOrDefault("costPrice", ""));
        payload.put("applyFromMonth", command.getOrDefault("applyFromMonth", "")); payload.put("reason", command.getOrDefault("reason", ""));
        payload.put("cancelDate", command.getOrDefault("cancelDate", ""));
        Map<String, Object> diff = new java.util.LinkedHashMap<>();
        diff.put("operation", Map.of("label", "契約操作", "before", c.getStatus() == null ? "" : c.getStatus(), "after", operation));
        return new ApprovalSnapshot(version(c.getUpdatedAt()), amount, null, payload, diff);
    }
    @Override public void validateBeforeRequest(ApprovalSnapshot snapshot) { }

    @Override
    public void applyApproved(ApprovalRequest request) {
        Map<String, Object> p = ApprovalPayloads.read(objectMapper, request.getPayloadJson());
        String op = ApprovalPayloads.text(p, "operation");
        if ("revisePrice".equals(op)) {
            service.revisePrice(request.getTargetId(), ApprovalPayloads.text(p, "applyFromMonth"),
                    ApprovalPayloads.decimal(p, "sellingPrice"), ApprovalPayloads.decimal(p, "costPrice"), ApprovalPayloads.text(p, "reason"));
            return;
        }
        service.changeStatus(request.getTargetId(), ApprovalPayloads.text(p, "status"),
                parseDate(ApprovalPayloads.text(p, "cancelDate")));
    }
    private Contract require(Long id) { Contract c = id == null ? null : mapper.selectById(id); if (c == null) throw BusinessException.of(404, "error.scope.notFound"); return c; }
    private java.time.LocalDate parseDate(String value) { return value == null || value.isBlank() ? null : java.time.LocalDate.parse(value); }
    private Long version(LocalDateTime updatedAt) { return updatedAt == null ? null : updatedAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(); }
}
