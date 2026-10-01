package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalAction;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.SysUser;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.mapper.MonthlyClosingMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.approval.ApprovalPayloads;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/** 月次締め/reopenを承認engineへ接続するadapter。金額なしrouteを使用する。 */
@Component
public class MonthlyClosingApprovalAdapter implements ApprovalTargetAdapter {

    private final MonthlyClosingService service;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ApprovalActionMapper approvalActionMapper;
    private final SysUserMapper sysUserMapper;
    private final MonthlyClosingMapper monthlyClosingMapper;

    /** 本番DI用。締め済み月は t_monthly_closing を tenant 境界で読む。 */
    @Autowired
    public MonthlyClosingApprovalAdapter(MonthlyClosingService service,
                                         com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                         ApprovalActionMapper approvalActionMapper,
                                         SysUserMapper sysUserMapper,
                                         MonthlyClosingMapper monthlyClosingMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.approvalActionMapper = approvalActionMapper;
        this.sysUserMapper = sysUserMapper;
        this.monthlyClosingMapper = monthlyClosingMapper;
    }

    /** 既存のadapter直接テストconstructorを維持する。 */
    public MonthlyClosingApprovalAdapter(MonthlyClosingService service,
                                         com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                         ApprovalActionMapper approvalActionMapper,
                                         SysUserMapper sysUserMapper) {
        this(service, objectMapper, approvalActionMapper, sysUserMapper, null);
    }

    @Override public String requestType() { return "closing.confirm"; }
    @Override public java.util.Set<String> supportedRequestTypes() {
        return java.util.Set.of("closing.confirm", "closing.reopen");
    }

    @Override
    public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        String month = ApprovalPayloads.text(command, "month");
        if (month == null || month.isBlank()) throw BusinessException.of(400, "error.validation.required");
        service.summary(month);
        List<String> closedMonths = currentClosedMonths();
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("operation", command.getOrDefault("operation", "confirm"));
        payload.put("month", month);
        payload.put("closedMonths", closedMonths);
        Map<String, Object> diff = new java.util.LinkedHashMap<>();
        diff.put("month", Map.of("label", "対象月", "before", "", "after", month));
        return new ApprovalSnapshot(fingerprint(closedMonths), null, null, payload, diff);
    }

    @Override
    public long currentVersion(Long targetId) {
        return fingerprint(currentClosedMonths());
    }

    @Override public void validateBeforeRequest(ApprovalSnapshot snapshot) { }

    @Override
    public void applyApproved(ApprovalRequest request) {
        Map<String, Object> p = ApprovalPayloads.read(objectMapper, request.getPayloadJson());
        String month = ApprovalPayloads.text(p, "month");
        int round = request.getRoundNo() == null ? 1 : request.getRoundNo();
        String tenantId = com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext();
        if (request.getTenantId() == null || !tenantId.equals(request.getTenantId())) {
            throw BusinessException.of(403, "error.tenant.mismatch");
        }
        ApprovalAction finalAction = approvalActionMapper.selectLatestApprovalForStep(
                        request.getId(), tenantId, round, request.getCurrentStep());
        if (finalAction == null) {
            throw BusinessException.of(500, "error.approval.approverUnresolved");
        }
        SysUser approver = sysUserMapper.selectByIdAndTenant(finalAction.getApproverUserId(), tenantId);
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

    /** 現在tenantの締め済み月だけを返す。mapper未配線の直接テストでは空集合。 */
    private List<String> currentClosedMonths() {
        if (monthlyClosingMapper == null) {
            return List.of();
        }
        String tenantId = com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext();
        List<String> months = monthlyClosingMapper.selectClosedMonths(tenantId);
        return months == null ? List.of() : months;
    }

    /** 月集合だけを対象にした安定fingerprint。締め実行者・日時の変更は競合要因にしない。 */
    private long fingerprint(List<String> months) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", months).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (Exception e) {
            throw new IllegalStateException("締め済み月のversion生成に失敗しました", e);
        }
    }
}
