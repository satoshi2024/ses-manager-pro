package com.ses.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.SalesOrder;
import com.ses.mapper.SalesOrderMapper;
import com.ses.service.SalesOrderService;
import com.ses.service.approval.ApprovalPayloads;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * 注文の取消・条件差分を承認engineへ接続するadapter（order-acceptance-workflow design §4）。
 * - order.cancel: 契約化済み注文の取消（承認必須）。承認適用で {@link SalesOrderService#applyCancellation} を呼ぶ。
 * - order.conditionDiff: 注文条件が見積/契約と異なる場合の承認対象化。承認は監査証跡であり、
 *   契約化時に承認済みであることを createContractDrafts が確認する。
 */
@Component
public class SalesOrderApprovalAdapter implements ApprovalTargetAdapter {

    private final SalesOrderMapper mapper;
    private final SalesOrderService service;
    private final ObjectMapper objectMapper;

    public SalesOrderApprovalAdapter(SalesOrderMapper mapper, SalesOrderService service, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public String requestType() {
        return "order.cancel";
    }

    @Override
    public Set<String> supportedRequestTypes() {
        return Set.of("order.cancel", "order.conditionDiff");
    }

    @Override
    public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        SalesOrder order = require(targetId);
        String operation = String.valueOf(command.getOrDefault("operation", "cancel"));
        BigDecimal amount = order.getTotalAmountSnapshot() != null
                ? order.getTotalAmountSnapshot().abs()
                : BigDecimal.ZERO;
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("operation", operation);
        payload.put("orderNo", order.getOrderNo());
        payload.put("customerPoNo", order.getCustomerPoNo() == null ? "" : order.getCustomerPoNo());
        payload.put("reason", command.getOrDefault("reason", ""));
        Map<String, Object> diff = new java.util.LinkedHashMap<>();
        diff.put("operation", Map.of("label", "注文操作", "before",
                order.getStatus() == null ? "" : order.getStatus(),
                "after", operation.equals("cancel") ? "取消" : "条件差分承認"));
        return new ApprovalSnapshot(version(order.getVersion()), amount, null, payload, diff);
    }

    @Override
    public long currentVersion(Long targetId) {
        SalesOrder order = require(targetId);
        return version(order.getVersion());
    }

    @Override
    public void validateBeforeRequest(ApprovalSnapshot snapshot) {
        // 状態・scopeは申請API側の service.assertAllowedOrder / 状態機械が担保する。
    }

    @Override
    public void applyApproved(ApprovalRequest request) {
        Map<String, Object> p = ApprovalPayloads.read(objectMapper, request.getPayloadJson());
        String op = ApprovalPayloads.text(p, "operation");
        if ("cancel".equals(op)) {
            service.applyCancellation(request.getTargetId());
            return;
        }
        // order.conditionDiff: 承認自体が監査証跡。注文状態は変更しない。
        if (!"conditionDiff".equals(op)) {
            throw BusinessException.of(409, "error.approval.invalidState");
        }
    }

    private SalesOrder require(Long id) {
        SalesOrder order = id == null ? null : mapper.selectById(id);
        if (order == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return order;
    }

    private long version(Integer version) {
        return version == null ? 0L : version.longValue();
    }
}
