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
import com.ses.service.WorkRecordService;
import com.ses.service.billing.SettlementCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkRecordServiceImpl extends ServiceImpl<WorkRecordMapper, WorkRecord> implements WorkRecordService {

    private final ContractMapper contractMapper;
    private final BpPaymentMapper bpPaymentMapper;
    private final InvoiceItemMapper invoiceItemMapper;

    @Override
    public List<WorkRecordGridDto> monthlyGrid(String workMonth) {
        return baseMapper.selectMonthlyGrid(workMonth);
    }

    @Override
    @Transactional
    public WorkRecord saveHours(Long contractId, String workMonth, BigDecimal actualHours, String remarks) {
        WorkRecord record = this.getOne(new QueryWrapper<WorkRecord>()
                .eq("contract_id", contractId)
                .eq("work_month", workMonth));

        if (record != null && "確定".equals(record.getStatus())) {
            throw new BusinessException("確定済みの月は編集できません");
        }

        if (record != null) {
            List<String> nos = invoiceItemMapper.selectActiveInvoiceNosByWorkRecordIds(List.of(record.getId()));
            if (!nos.isEmpty()) {
                throw new BusinessException("請求書(" + nos.get(0) + ")に計上済みの実績は編集できません");
            }
        }

        Contract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            throw new BusinessException("契約が存在しません");
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
            throw new BusinessException("他のユーザーが同じ実績を登録しました。再読み込みしてください");
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
        List<WorkRecordGridDto> grid = baseMapper.selectMonthlyGrid(workMonth);
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
                    }
                }
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
            throw new BusinessException("請求書(" + String.join(", ", nos) + ")に計上済みの実績が含まれるため解除できません");
        }

        Long paidCount = bpPaymentMapper.selectCount(new QueryWrapper<BpPayment>()
                .in("work_record_id", ids)
                .eq("status", "支払済"));
        if (paidCount > 0) {
            throw new BusinessException("支払済のBP支払が" + paidCount + "件あるため解除できません");
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
