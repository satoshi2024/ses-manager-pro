package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.BpPayment;
import com.ses.entity.Contract;
import com.ses.entity.WorkRecord;
import com.ses.common.exception.BusinessException;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.InvoiceItemMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.NotificationService;
import com.ses.service.WorkRecordService;
import com.ses.service.billing.SettlementCalculator;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import com.ses.common.constant.StatusConstants;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkRecordServiceImpl extends ServiceImpl<WorkRecordMapper, WorkRecord> implements WorkRecordService {

    private static final Logger log = LoggerFactory.getLogger(WorkRecordServiceImpl.class);

    private final ContractMapper contractMapper;
    private final BpPaymentMapper bpPaymentMapper;
    private final InvoiceItemMapper invoiceItemMapper;
    private final NotificationService notificationService;

    /** 対象月の末日文字列(yyyy-MM-dd)。方言依存の CONCAT(...,'-31') を避けるため Java 側で確定する。 */
    private static String monthEndOf(String workMonth) {
        return YearMonth.parse(workMonth).atEndOfMonth().toString();
    }

    @Override
    public List<WorkRecordGridDto> monthlyGrid(String workMonth) {
        return baseMapper.selectMonthlyGrid(workMonth, monthEndOf(workMonth));
    }

    @Override
    @Transactional
    public WorkRecord saveHours(Long contractId, String workMonth, BigDecimal actualHours, String remarks) {
        WorkRecord record = this.getOne(new QueryWrapper<WorkRecord>()
                .eq("contract_id", contractId)
                .eq("work_month", workMonth));

        if (record != null && "確定".equals(record.getStatus())) {
            throw BusinessException.of("error.workRecord.confirmedEdit2");
        }

        if (record != null) {
            List<String> nos = invoiceItemMapper.selectActiveInvoiceNosByWorkRecordIds(List.of(record.getId()));
            if (!nos.isEmpty()) {
                throw BusinessException.of("error.workRecord.invoicedEdit2", nos.get(0));
            }
        }

        Contract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            throw BusinessException.of("error.workRecord.noContract2");
        }

        // 縦深防御: グリッド外からのAPI直叩きで契約期間外・非稼動契約に実績を作られないよう検証する。
        // 判定条件は勤怠グリッド(selectMonthlyGrid の WHERE)と同一に揃える。
        YearMonth ym;
        try {
            ym = YearMonth.parse(workMonth);
        } catch (DateTimeParseException e) {
            throw BusinessException.of("error.workRecord.invalidMonth");
        }
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        boolean inPeriod = contract.getStartDate() != null && !contract.getStartDate().isAfter(monthEnd)
                && (contract.getEndDate() == null || !contract.getEndDate().isBefore(monthStart));
        boolean statusOk = StatusConstants.CONTRACT_ACTIVE.equals(contract.getStatus())
                || StatusConstants.CONTRACT_ENDED.equals(contract.getStatus());
        if (!inPeriod || !statusOk) {
            throw BusinessException.of("error.workRecord.contractNotBillable");
        }

        BigDecimal billingAmount = SettlementCalculator.calc(
                contract.getSellingPrice(),
                contract.getSettlementHoursMin(),
                contract.getSettlementHoursMax(),
                actualHours
        );

        BigDecimal paymentAmount = null;
        if (contract.getCostPrice() != null) {
            paymentAmount = SettlementCalculator.calc(
                    contract.getCostPrice(),
                    contract.getSettlementHoursMin(),
                    contract.getSettlementHoursMax(),
                    actualHours
            );
        }

        if (record == null) {
            record = new WorkRecord();
            record.setContractId(contractId);
            record.setWorkMonth(workMonth);
            record.setStatus("入力中");
        }

        record.setActualHours(actualHours);
        record.setBillingAmount(billingAmount);
        record.setPaymentAmount(paymentAmount);
        record.setRemarks(remarks);

        try {
            this.saveOrUpdate(record);
        } catch (DuplicateKeyException e) {
            throw BusinessException.of("error.workRecord.userNotFound2");
        }
        return record;
    }

    @Override
    @Transactional
    public void confirmMonth(String workMonth) {
        List<WorkRecord> records = baseMapper.selectList(new QueryWrapper<WorkRecord>()
                .eq("work_month", workMonth)
                .eq("status", "入力中"));

        if (records.isEmpty()) {
            return;
        }

        for (WorkRecord record : records) {
            record.setStatus("確定");
            baseMapper.updateById(record);
        }

        // BP支払を生成(雇用形態がBPの要員に紐づく契約の確定実績について)
        List<WorkRecordGridDto> grid = baseMapper.selectMonthlyGrid(workMonth, monthEndOf(workMonth));
        for (WorkRecordGridDto dto : grid) {
            if ("BP".equals(dto.getEmploymentType()) && dto.getWorkRecordId() != null) {
                WorkRecord record = records.stream()
                        .filter(r -> r.getId().equals(dto.getWorkRecordId()))
                        .findFirst().orElse(null);

                if (record != null && record.getPaymentAmount() != null) {
                    Long count = bpPaymentMapper.selectCount(new QueryWrapper<BpPayment>()
                            .eq("work_record_id", record.getId()));
                    if (count == 0) {
                        BpPayment bp = new BpPayment();
                        bp.setWorkRecordId(record.getId());
                        bp.setAmount(record.getPaymentAmount());
                        bp.setStatus("未払");
                        bpPaymentMapper.insert(bp);
                    } else {
                        // 既存のBP支払がある(入力中段階で手動登録済み等)。1階層目(parent NULL)の金額が
                        // 最新の payment_amount とずれていれば、未払なら追従更新し、支払済なら更新せず通知する。
                        syncRootBpAmount(record);
                    }
                }
            }
        }
    }

    /**
     * 自動生成1階層目(parent_payment_id IS NULL)のBP支払金額を最新の payment_amount に同期する。
     * 未払の1階層目のみ更新し、支払済で不一致の場合は更新せず warn ログ + 通知に留める(確定済み金額を保護)。
     */
    private void syncRootBpAmount(WorkRecord record) {
        List<BpPayment> roots = bpPaymentMapper.selectList(new QueryWrapper<BpPayment>()
                .eq("work_record_id", record.getId())
                .isNull("parent_payment_id")
                .eq("layer_order", 1));
        for (BpPayment root : roots) {
            if (root.getAmount() == null || root.getAmount().compareTo(record.getPaymentAmount()) == 0) {
                continue;
            }
            if ("未払".equals(root.getStatus())) {
                bpPaymentMapper.update(null, new UpdateWrapper<BpPayment>()
                        .eq("id", root.getId())
                        .set("amount", record.getPaymentAmount()));
            } else {
                log.warn("支払済BP支払(id={})の金額 {} が最新の支払額 {} と不一致ですが、確定済みのため更新しません",
                        root.getId(), root.getAmount(), record.getPaymentAmount());
                notificationService.publish(
                        "BP_AMOUNT_MISMATCH",
                        "BP支払金額の不一致",
                        "[\"notification.msg.BP_AMOUNT_MISMATCH\", \"" + root.getId() + "\"]",
                        com.ses.common.constant.NotificationLinks.INVOICE,
                        "bp-amount-mismatch-" + root.getId());
            }
        }
    }

    @Override
    @Transactional
    public void reopenMonth(String workMonth) {
        List<WorkRecord> records = this.list(new QueryWrapper<WorkRecord>()
                .eq("work_month", workMonth)
                .eq("status", "確定"));

        if (records.isEmpty()) {
            return;
        }

        List<Long> ids = records.stream().map(WorkRecord::getId).collect(Collectors.toList());

        List<String> nos = invoiceItemMapper.selectActiveInvoiceNosByWorkRecordIds(ids);
        if (!nos.isEmpty()) {
            throw BusinessException.of("error.workRecord.invoicedDelete2", String.join(", ", nos));
        }

        Long paidCount = bpPaymentMapper.selectCount(new QueryWrapper<BpPayment>()
                .in("work_record_id", ids)
                .eq("status", "支払済"));
        if (paidCount > 0) {
            throw BusinessException.of("error.workRecord.paidBpDelete", paidCount);
        }

        // 手動登録された多段BP階層(2次請以降=layer_order>1、または親を持つ行)は自動再生成で復元できないため、
        // 未払であっても黙って物理削除せず、存在すれば解除を拒否する(手動データの破壊防止)。
        List<BpPayment> manualTiers = bpPaymentMapper.selectList(new QueryWrapper<BpPayment>()
                .in("work_record_id", ids)
                .eq("status", "未払")
                .and(w -> w.gt("layer_order", 1).or().isNotNull("parent_payment_id")));
        if (!manualTiers.isEmpty()) {
            throw BusinessException.of("error.workRecord.manualBpDelete", manualTiers.size());
        }

        for (WorkRecord record : records) {
            record.setStatus("入力中");
        }
        this.updateBatchById(records);

        bpPaymentMapper.delete(new QueryWrapper<BpPayment>()
                .in("work_record_id", ids)
                .eq("status", "未払"));
    }
}

















