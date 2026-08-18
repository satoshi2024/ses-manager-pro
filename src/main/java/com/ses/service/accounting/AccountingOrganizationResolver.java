package com.ses.service.accounting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.*;
import com.ses.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 実在エンティティに基づく会計連携の組織導出コンポーネント (design §5.1)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountingOrganizationResolver {

    private final CostCenterMapper costCenterMapper;
    private final InvoiceItemMapper invoiceItemMapper;
    private final WorkRecordMapper workRecordMapper;
    private final ContractMapper contractMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final EngineerAccountingHistoryMapper engineerAccountingHistoryMapper;
    private final EngineerMapper engineerMapper;

    /**
     * 売上ジョブの組織ID導出 (design §5.1 item 1)。
     */
    public Long resolveInvoiceOrganizationId(Invoice invoice) {
        if (invoice == null) return null;

        LocalDate asOf;
        if (invoice.getIssuedDate() != null) {
            asOf = invoice.getIssuedDate();
        } else if (invoice.getBillingMonth() != null && !invoice.getBillingMonth().isBlank()) {
            asOf = YearMonth.parse(invoice.getBillingMonth()).atEndOfMonth();
        } else {
            asOf = LocalDate.now();
        }

        // 優先度1: t_invoice.cost_center_id -> m_cost_center.organization_id
        if (invoice.getCostCenterId() != null) {
            CostCenter cc = costCenterMapper.selectById(invoice.getCostCenterId());
            if (cc != null && cc.getOrganizationId() != null) {
                return cc.getOrganizationId();
            }
        }

        // 優先度2: 主明細 (最小 ID の t_invoice_item) -> t_work_record.contract_id -> t_contract.sales_user_id -> t_user_organization
        List<InvoiceItem> items = invoiceItemMapper.selectList(new LambdaQueryWrapper<InvoiceItem>()
                .eq(InvoiceItem::getInvoiceId, invoice.getId())
                .orderByAsc(InvoiceItem::getId));
        if (items != null && !items.isEmpty()) {
            for (InvoiceItem item : items) {
                if (item.getWorkRecordId() != null) {
                    WorkRecord wr = workRecordMapper.selectById(item.getWorkRecordId());
                    if (wr != null && wr.getContractId() != null) {
                        Contract contract = contractMapper.selectById(wr.getContractId());
                        if (contract != null && contract.getSalesUserId() != null) {
                            Long orgId = userOrganizationMapper.selectPrimaryOrganizationAt(contract.getSalesUserId(), asOf);
                            if (orgId != null) {
                                return orgId;
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * BP仕入・支払ジョブの組織ID導出 (design §5.1 item 2)。
     */
    public Long resolveBpPaymentOrganizationId(BpPayment bpPayment) {
        if (bpPayment == null) return null;

        WorkRecord wr = bpPayment.getWorkRecordId() != null ? workRecordMapper.selectById(bpPayment.getWorkRecordId()) : null;
        LocalDate asOf;
        if (wr != null && wr.getWorkMonth() != null && !wr.getWorkMonth().isBlank()) {
            asOf = YearMonth.parse(wr.getWorkMonth()).atEndOfMonth();
        } else {
            asOf = LocalDate.now();
        }

        // 優先度1: t_bp_payment.cost_center_id -> m_cost_center.organization_id
        if (bpPayment.getCostCenterId() != null) {
            CostCenter cc = costCenterMapper.selectById(bpPayment.getCostCenterId());
            if (cc != null && cc.getOrganizationId() != null) {
                return cc.getOrganizationId();
            }
        }

        // 優先度2: t_bp_payment.work_record_id -> t_work_record.contract_id -> t_contract.sales_user_id -> t_user_organization
        if (wr != null && wr.getContractId() != null) {
            Contract contract = contractMapper.selectById(wr.getContractId());
            if (contract != null && contract.getSalesUserId() != null) {
                Long orgId = userOrganizationMapper.selectPrimaryOrganizationAt(contract.getSalesUserId(), asOf);
                if (orgId != null) {
                    return orgId;
                }
            }
        }

        return null;
    }

    /**
     * 経費ジョブの組織ID導出 (design §5.1 item 3)。
     */
    public Long resolveExpenseOrganizationId(ExpenseRequest expenseRequest) {
        if (expenseRequest == null || expenseRequest.getEngineerId() == null) return null;

        LocalDate asOf = expenseRequest.getExpenseDate() != null ? expenseRequest.getExpenseDate() : LocalDate.now();

        // 優先度1: t_engineer_accounting_history から該当時点の所属組織を解決
        EngineerAccountingHistory history = engineerAccountingHistoryMapper.selectAt(expenseRequest.getEngineerId(), asOf);
        if (history != null) {
            if ("UNKNOWN".equals(history.getOrganizationHistoryStatus())) {
                // Fail-Closed: organization_id = NULL（全社共通・管理者のみ可視）、現在値へはフォールバックしない
                log.info("Engineer accounting history has UNKNOWN status for engineerId={}, returning null (Fail-Closed)",
                        expenseRequest.getEngineerId());
                return null;
            }
            return history.getOrganizationId();
        }

        // 優先度2: 履歴行が全く存在しない場合（V60以前のレガシー要員データ）: t_engineer.organization_id へフォールバック
        Engineer engineer = engineerMapper.selectById(expenseRequest.getEngineerId());
        if (engineer != null) {
            return engineer.getOrganizationId();
        }

        return null;
    }
}
