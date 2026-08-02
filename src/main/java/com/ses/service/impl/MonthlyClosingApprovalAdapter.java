package com.ses.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.ApprovalAction;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.SysUser;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.approval.ApprovalPayloads;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** 月次締め/reopenを承認engineへ接続するadapter。金額なしrouteを使用する。 */
@Component
public class MonthlyClosingApprovalAdapter implements ApprovalTargetAdapter {
    private final MonthlyClosingService service;
    private final ObjectMapper objectMapper;
    private final ApprovalActionMapper approvalActionMapper;
    private final SysUserMapper sysUserMapper;

    public MonthlyClosingApprovalAdapter(MonthlyClosingService service, ObjectMapper objectMapper,
                                         ApprovalActionMapper approvalActionMapper, SysUserMapper sysUserMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.approvalActionMapper = approvalActionMapper;
        this.sysUserMapper = sysUserMapper;
    }
    @Override public String requestType() { return "closing.confirm"; }
    @Override public Set<String> supportedRequestTypes() { return Set.of("closing.confirm", "closing.reopen"); }
    @Override public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        String month = ApprovalPayloads.text(command, "month");
        if (month == null || month.isBlank()) throw BusinessException.of(400, "error.validation.required");
        service.summary(month);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("operation", command.getOrDefault("operation", "confirm")); payload.put("month", month);
        Map<String, Object> diff = new java.util.LinkedHashMap<>();
        diff.put("month", Map.of("label", "対象月", "before", "", "after", month));
        return new ApprovalSnapshot(null, null, null, payload, diff);
    }
    @Override public void validateBeforeRequest(ApprovalSnapshot snapshot) { }
    @Override public void applyApproved(ApprovalRequest request) {
        Map<String, Object> p = ApprovalPayloads.read(objectMapper, request.getPayloadJson());
        String month = ApprovalPayloads.text(p, "month");
        ApprovalAction finalAction = approvalActionMapper.selectList(new LambdaQueryWrapper<ApprovalAction>()
                        .eq(ApprovalAction::getRequestId, request.getId())
                        .eq(ApprovalAction::getStepNo, request.getCurrentStep())
                        .eq(ApprovalAction::getAction, "APPROVE")
                        .orderByDesc(ApprovalAction::getId))
                .stream().findFirst()
                .orElseThrow(() -> BusinessException.of(500, "error.approval.approverUnresolved"));
        SysUser approver = sysUserMapper.selectById(finalAction.getApproverUserId());
        if (approver == null || approver.getRole() == null) {
            throw BusinessException.of(500, "error.approval.approverUnresolved");
        }
        // 申請者ではなく、最終承認actionの実行者を既存締めサービスの監査主体へ渡す。
        // 申請者IDを流用すると、申請者単独確定と同じ監査結果になるため禁止する。
        if ("reopen".equals(ApprovalPayloads.text(p, "operation"))) {
            service.reopenClosing(month, finalAction.getApproverUserId(), approver.getRole());
        } else {
            service.confirmClosing(month, finalAction.getApproverUserId(), approver.getRole());
        }
    }
}
