package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.bpcompany.BpBankAccountDto;
import com.ses.entity.ApprovalRequest;
import com.ses.mapper.BpBankAccountMapper;
import com.ses.service.BpCompanyService;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * BP口座変更を承認engineへ接続するadapter（external-customer-bp-portal design §3・R3.4）。
 * - 申請: BP portalがPENDING口座を作成し、本adapter経由で承認申請する。
 * - 承認適用: {@link BpCompanyService#updateBankAccountApproval}（PENDING→APPROVED）を呼ぶ。
 *   承認前は口座はPENDINGのまま（支払先として未反映: R3.4）。
 */
@Component
public class BpBankAccountChangeAdapter implements ApprovalTargetAdapter {

    private final BpBankAccountMapper bankAccountMapper;
    private final BpCompanyService bpCompanyService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public BpBankAccountChangeAdapter(BpBankAccountMapper bankAccountMapper,
                                      BpCompanyService bpCompanyService,
                                      com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.bankAccountMapper = bankAccountMapper;
        this.bpCompanyService = bpCompanyService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String requestType() {
        return "bp_bank_account.change";
    }

    @Override
    public Set<String> supportedRequestTypes() {
        return Set.of("bp_bank_account.change");
    }

    @Override
    public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        com.ses.entity.BpBankAccount account = require(targetId);
        return new ApprovalSnapshot(
                1L,
                null,
                null,
                Map.of(
                        "bankAccountId", account.getId(),
                        "bpCompanyId", account.getBpCompanyId(),
                        "maskedLabel", account.getMaskedLabel(),
                        "bankName", account.getBankName(),
                        "branchName", account.getBranchName(),
                        "accountType", account.getAccountType(),
                        "accountHolder", account.getAccountHolder()),
                Map.of(
                        "before", Map.of("approvalStatus", account.getApprovalStatus()),
                        "after", Map.of("approvalStatus", "APPROVED")));
    }

    @Override
    public long currentVersion(Long targetId) {
        // 口座行には楽観ロックversionがないため固定1（適用時のPENDING CASが排他を担う）
        return 1L;
    }

    @Override
    public void validateBeforeRequest(ApprovalSnapshot snapshot) {
        Object bankAccountId = snapshot.payload().get("bankAccountId");
        if (bankAccountId == null) {
            throw BusinessException.of(400, "error.portal.bp.accountInvalid");
        }
        com.ses.entity.BpBankAccount account = bankAccountMapper.selectById(Long.valueOf(bankAccountId.toString()));
        if (account == null) {
            throw BusinessException.of(400, "error.portal.bp.accountInvalid");
        }
        if (!"PENDING".equals(account.getApprovalStatus())) {
            throw BusinessException.of(409, "error.portal.bp.accountNotPending");
        }
    }

    @Override
    public void applyApproved(ApprovalRequest request) {
        Map<String, Object> payload = com.ses.service.approval.ApprovalPayloads.read(objectMapper,
                request.getPayloadJson());
        Object bankAccountId = payload == null ? null : payload.get("bankAccountId");
        if (bankAccountId == null) {
            throw new IllegalStateException("口座変更のpayloadにbankAccountIdがありません");
        }
        // PENDING→APPROVEDのCAS。申請者（BP担当営業）を承認操作者として記録する
        bpCompanyService.updateBankAccountApproval(Long.valueOf(bankAccountId.toString()),
                "APPROVED", request.getApplicantId());
    }

    private com.ses.entity.BpBankAccount require(Long targetId) {
        com.ses.entity.BpBankAccount account = targetId == null ? null : bankAccountMapper.selectById(targetId);
        if (account == null) {
            throw BusinessException.of(404, "error.portal.bp.accountInvalid");
        }
        return account;
    }
}
