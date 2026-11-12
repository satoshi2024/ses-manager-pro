package com.ses.service.accounting;

import com.ses.common.exception.BusinessException;
import com.ses.entity.BpPayment;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.ExpenseRequest;
import com.ses.entity.IntegrationConnection;
import com.ses.entity.Invoice;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.WorkRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 会計連携ジョブの法人（t_integration_job.legal_entity_id）を、送信対象の業務レコードから決める。
 *
 * <p>Worker は法人の無いジョブを {@code SCOPE_MISSING} で実行前に失敗させる（NF09 法人境界）。以前は接続の法人を
 * ジョブへ写していたが、全社共通の freee 接続（legal_entity_id NULL）では常に NULL になり、送信が一件も実行されなかった。
 * 法人はレコード側の正本から取る（NF05 runbook: invoice は customer から、契約・要員は各自の法人）。</p>
 *
 * <ul>
 *   <li>請求書: {@code t_invoice.legal_entity_id}</li>
 *   <li>BP支払: 工数実績 → 契約の {@code t_contract.legal_entity_id}</li>
 *   <li>要員経費: 要員の {@code t_engineer.legal_entity_id}</li>
 * </ul>
 *
 * <p>レコードの法人が未設定なら送信させない（NF05 backfill 未完了の環境では推測で割り当てない）。
 * 接続が特定の法人に束縛されている場合は、レコードの法人と一致しなければ送信させない。</p>
 */
@Component
@RequiredArgsConstructor
public class AccountingJobLegalEntityResolver {

    private final WorkRecordMapper workRecordMapper;
    private final ContractMapper contractMapper;
    private final EngineerMapper engineerMapper;

    /** プレビュー表示用の判定結果。status は OK / MISSING / MISMATCH。 */
    public record LegalEntityCheck(Long legalEntityId, String status) {
        public boolean ok() {
            return "OK".equals(status);
        }
    }

    public Long ofInvoice(Invoice invoice) {
        return invoice == null ? null : invoice.getLegalEntityId();
    }

    public Long ofBpPayment(BpPayment bpPayment) {
        if (bpPayment == null || bpPayment.getWorkRecordId() == null) {
            return null;
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        WorkRecord workRecord = workRecordMapper.selectByIdForTenant(bpPayment.getWorkRecordId(), tenantId);
        if (workRecord == null || workRecord.getContractId() == null) {
            return null;
        }
        Contract contract = contractMapper.selectByIdForTenant(workRecord.getContractId(), tenantId);
        return contract == null ? null : contract.getLegalEntityId();
    }

    public Long ofExpense(ExpenseRequest expense) {
        if (expense == null || expense.getEngineerId() == null) {
            return null;
        }
        Engineer engineer = engineerMapper.selectByIdForTenant(
                expense.getEngineerId(), AccountingTenantContextHolder.requireTenantContext());
        return engineer == null ? null : engineer.getLegalEntityId();
    }

    /** 接続とレコードの法人から、プレビュー用の判定を返す（例外は投げない）。 */
    public LegalEntityCheck check(IntegrationConnection connection, Long recordLegalEntityId) {
        if (recordLegalEntityId == null) {
            return new LegalEntityCheck(null, "MISSING");
        }
        Long bound = connection == null ? null : connection.getLegalEntityId();
        if (bound != null && !bound.equals(recordLegalEntityId)) {
            return new LegalEntityCheck(recordLegalEntityId, "MISMATCH");
        }
        return new LegalEntityCheck(recordLegalEntityId, "OK");
    }

    /** 送信ジョブへ刻印する法人。未設定・接続の法人と不一致なら送信を拒否する。 */
    public Long require(IntegrationConnection connection, Long recordLegalEntityId) {
        LegalEntityCheck check = check(connection, recordLegalEntityId);
        if ("MISSING".equals(check.status())) {
            throw BusinessException.of(409, "error.accounting.legalEntityMissing");
        }
        if ("MISMATCH".equals(check.status())) {
            throw BusinessException.of(409, "error.accounting.legalEntityMismatch");
        }
        return check.legalEntityId();
    }
}
