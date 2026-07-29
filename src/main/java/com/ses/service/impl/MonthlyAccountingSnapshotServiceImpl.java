package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.entity.Contract;
import com.ses.entity.CostCenter;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.Invoice;
import com.ses.entity.InvoiceItem;
import com.ses.entity.BpPayment;
import com.ses.entity.MonthlyAccountingDimension;
import com.ses.entity.UserOrganization;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CostCenterMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.InvoiceItemMapper;
import com.ses.mapper.BpPaymentMapper;
import com.ses.service.MonthlyAccountingSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/** 勤怠の確定値に対する組織・原価部門帰属を月初時点で固定する。 */
@Service
@RequiredArgsConstructor
public class MonthlyAccountingSnapshotServiceImpl implements MonthlyAccountingSnapshotService {

    private final WorkRecordMapper workRecordMapper;
    private final ContractMapper contractMapper;
    private final EngineerAccountLinkMapper engineerAccountLinkMapper;
    /**
     * 要員の所属組織・原価部門を引く。帰属解決の一次情報なので必須依存にする
     * （任意注入だとコンストラクタ注入経路でnullのまま残り、帰属が常にnullになる）。
     */
    private final EngineerMapper engineerMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final CostCenterMapper costCenterMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InvoiceMapper invoiceMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InvoiceItemMapper invoiceItemMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BpPaymentMapper bpPaymentMapper;
    private final MonthlyAccountingDimensionMapper dimensionMapper;

    @Override
    @Transactional
    public int snapshotMonth(String workMonth) {
        YearMonth month = com.ses.common.util.DateUtils.parseYearMonth(workMonth);
        LocalDate asOf = month.atDay(1);
        List<WorkRecord> records = workRecordMapper.selectList(new QueryWrapper<WorkRecord>()
                .eq("work_month", workMonth).eq("status", "確定").orderByAsc("id"));
        if (records.isEmpty()) {
            return snapshotBenchCosts(asOf);
        }
        List<Long> recordIds = records.stream().map(WorkRecord::getId).filter(java.util.Objects::nonNull).toList();
        List<MonthlyAccountingDimension> existing = dimensionMapper.selectList(new LambdaQueryWrapper<MonthlyAccountingDimension>()
                        .eq(MonthlyAccountingDimension::getWorkMonth, asOf)
                        .eq(MonthlyAccountingDimension::getSourceType, "work-record")
                        .in(MonthlyAccountingDimension::getSourceId, recordIds));
        java.util.Set<Long> existingIds = new java.util.HashSet<>();
        if (existing != null) {
            existingIds.addAll(existing.stream().map(MonthlyAccountingDimension::getSourceId).toList());
        } else {
            for (Long id : recordIds) {
                MonthlyAccountingDimension item = dimensionMapper.selectOne(new LambdaQueryWrapper<MonthlyAccountingDimension>()
                        .eq(MonthlyAccountingDimension::getWorkMonth, asOf)
                        .eq(MonthlyAccountingDimension::getSourceType, "work-record")
                        .eq(MonthlyAccountingDimension::getSourceId, id));
                if (item != null) existingIds.add(id);
            }
        }
        List<Long> contractIds = records.stream().map(WorkRecord::getContractId).filter(java.util.Objects::nonNull).distinct().toList();
        List<Contract> contracts = contractIds.isEmpty() ? List.of() : contractMapper.selectBatchIds(contractIds);
        if (contracts == null) {
            contracts = contractIds.stream().map(contractMapper::selectById).filter(java.util.Objects::nonNull).toList();
        }
        java.util.Map<Long, Contract> contractById = contracts == null ? java.util.Map.of()
                : contracts.stream().collect(java.util.stream.Collectors.toMap(Contract::getId, c -> c, (a, b) -> a));
        List<Long> engineerIds = contracts == null ? List.of() : contracts.stream().map(Contract::getEngineerId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        List<Engineer> engineers = engineerIds.isEmpty() ? List.of() : engineerMapper.selectBatchIds(engineerIds);
        java.util.Map<Long, Engineer> engineerById = engineers == null ? java.util.Map.of()
                : engineers.stream().collect(java.util.stream.Collectors.toMap(Engineer::getId, e -> e, (a, b) -> a));
        List<EngineerAccountLink> links = engineerIds.isEmpty() ? List.of()
                : engineerAccountLinkMapper.selectByEngineerIds(engineerIds);
        if (links == null) {
            links = engineerIds.stream().map(engineerAccountLinkMapper::selectByEngineerId)
                    .filter(java.util.Objects::nonNull).toList();
        }
        java.util.Map<Long, EngineerAccountLink> linkByEngineer = links.stream().collect(
                java.util.stream.Collectors.toMap(EngineerAccountLink::getEngineerId, l -> l, (a, b) -> a));
        List<Long> userIds = linkByEngineer.values().stream().map(EngineerAccountLink::getSysUserId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        List<UserOrganization> assignments = userIds.isEmpty() ? List.of() : userOrganizationMapper.selectList(
                new LambdaQueryWrapper<UserOrganization>().in(UserOrganization::getUserId, userIds)
                        .eq(UserOrganization::getPrimaryFlag, 1).le(UserOrganization::getValidFrom, asOf)
                        .and(w -> w.isNull(UserOrganization::getValidTo).or().ge(UserOrganization::getValidTo, asOf))
                        .orderByDesc(UserOrganization::getValidFrom));
        java.util.Map<Long, UserOrganization> assignmentByUser = new java.util.HashMap<>();
        if (assignments != null) {
            assignments.forEach(a -> assignmentByUser.putIfAbsent(a.getUserId(), a));
        } else {
            for (Long userId : userIds) {
                UserOrganization assignment = userOrganizationMapper.selectOne(new LambdaQueryWrapper<UserOrganization>()
                        .eq(UserOrganization::getUserId, userId).eq(UserOrganization::getPrimaryFlag, 1)
                        .le(UserOrganization::getValidFrom, asOf)
                        .and(w -> w.isNull(UserOrganization::getValidTo).or().ge(UserOrganization::getValidTo, asOf))
                        .orderByDesc(UserOrganization::getValidFrom).last("LIMIT 1"));
                if (assignment != null) assignmentByUser.put(userId, assignment);
            }
        }
        List<Long> invoiceRecordIds = recordIds;
        List<InvoiceItem> invoiceItems = invoiceRecordIds.isEmpty() || invoiceItemMapper == null ? List.of() : invoiceItemMapper.selectList(
                new LambdaQueryWrapper<InvoiceItem>().in(InvoiceItem::getWorkRecordId, invoiceRecordIds));
        List<Long> invoiceIds = invoiceItems == null ? List.of() : invoiceItems.stream().map(InvoiceItem::getInvoiceId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        List<Invoice> invoices = invoiceIds.isEmpty() || invoiceMapper == null ? List.of() : invoiceMapper.selectBatchIds(invoiceIds);
        java.util.Map<Long, Invoice> invoiceById = invoices == null ? java.util.Map.of()
                : invoices.stream().collect(java.util.stream.Collectors.toMap(Invoice::getId, i -> i, (a, b) -> a));
        java.util.Map<Long, Long> invoiceCostByRecord = new java.util.HashMap<>();
        if (invoiceItems != null) {
            for (InvoiceItem item : invoiceItems) {
                Invoice invoice = invoiceById.get(item.getInvoiceId());
                if (invoice != null && invoice.getCostCenterId() != null) {
                    invoiceCostByRecord.put(item.getWorkRecordId(), invoice.getCostCenterId());
                }
            }
        }
        List<BpPayment> bpPayments = recordIds.isEmpty() || bpPaymentMapper == null ? List.of()
                : bpPaymentMapper.selectByWorkRecordIds(recordIds);
        if (bpPayments == null) bpPayments = List.of();
        java.util.Map<Long, Long> bpCostByRecord = new java.util.HashMap<>();
        for (BpPayment payment : bpPayments) {
            if (payment.getCostCenterId() != null) bpCostByRecord.putIfAbsent(payment.getWorkRecordId(), payment.getCostCenterId());
        }
        int inserted = 0;
        for (WorkRecord record : records) {
            if (record.getId() == null || existingIds.contains(record.getId())) {
                continue;
            }
            Contract contract = contractById.get(record.getContractId());
            Long organizationId = record.getOrganizationId();
            Long costCenterId = record.getCostCenterId();
            Long salesUserId = contract == null ? null : contract.getSalesUserId();
            // 確定時に凍結済みなら現在の要員/契約を参照しない。旧レコードだけ従来の
            // 対象月所属を優先し、過去月に履歴が無い現在マスタは推測に使わない。
            Engineer engineer = contract == null ? null : engineerById.get(contract.getEngineerId());
            boolean frozen = Integer.valueOf(1).equals(record.getAccountingDimensionFrozen());
            if (!frozen && organizationId == null) {
                if (contract != null && contract.getEngineerId() != null) {
                    EngineerAccountLink link = linkByEngineer.get(contract.getEngineerId());
                    if (link != null && link.getSysUserId() != null) {
                        UserOrganization assignment = assignmentByUser.get(link.getSysUserId());
                        if (assignment != null) {
                            organizationId = assignment.getOrganizationId();
                        }
                    }
                }
                if (organizationId == null && engineer != null
                        && !asOf.isBefore(LocalDate.now().withDayOfMonth(1))) {
                    organizationId = engineer.getOrganizationId();
                }
            }
            if (!frozen && costCenterId == null) {
                if (!asOf.isBefore(LocalDate.now().withDayOfMonth(1)) && contract != null
                        && contract.getCostCenterId() != null) {
                    costCenterId = contract.getCostCenterId();
                } else if (!asOf.isBefore(LocalDate.now().withDayOfMonth(1)) && engineer != null
                        && engineer.getCostCenterId() != null) {
                    costCenterId = engineer.getCostCenterId();
                } else {
                    costCenterId = invoiceCostByRecord.get(record.getId());
                    if (costCenterId == null) {
                        costCenterId = bpCostByRecord.get(record.getId());
                    }
                }
            }
            if (!frozen && costCenterId != null) {
                CostCenter center = costCenterMapper.selectById(costCenterId);
                if (center == null || (organizationId != null && !organizationId.equals(center.getOrganizationId()))) {
                    costCenterId = null;
                }
            }
            MonthlyAccountingDimension snapshot = MonthlyAccountingDimension.builder()
                    .workMonth(asOf)
                    .sourceType("work-record")
                    .sourceId(record.getId())
                    .organizationId(organizationId)
                    .costCenterId(costCenterId)
                    .salesUserId(salesUserId)
                    .revenue(zeroIfNull(record.getBillingAmount()))
                    .cost(zeroIfNull(record.getPaymentAmount()))
                    .snapshotAt(LocalDateTime.now())
                    .build();
            try {
                inserted += dimensionMapper.insert(snapshot);
            } catch (DuplicateKeyException ignored) {
                // 同一月次締めの並行実行は一意キーで一度だけ確定する。
            }
        }
        inserted += snapshotBenchCosts(asOf);
        return inserted;
    }

    /**
     * 確定実績が無い月でもBenchの帰属を月次snapshotへ保存する。
     * 後日の要員異動・原価部門変更で過去月の待機原価が移動しないよう、要員単位で一意に保存する。
     */
    private int snapshotBenchCosts(LocalDate asOf) {
        List<com.ses.dto.accounting.AccountingWaitCostSnapshotRow> rows =
                engineerMapper.selectAccountingWaitCostByEngineer(asOf, asOf.withDayOfMonth(asOf.lengthOfMonth()));
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (com.ses.dto.accounting.AccountingWaitCostSnapshotRow row : rows) {
            if (row.getEngineerId() == null) {
                continue;
            }
            Long costCenterId = row.getCostCenterId();
            if (costCenterId != null) {
                CostCenter center = costCenterMapper.selectById(costCenterId);
                if (center == null || (row.getOrganizationId() != null
                        && !row.getOrganizationId().equals(center.getOrganizationId()))) {
                    costCenterId = null;
                }
            }
            MonthlyAccountingDimension snapshot = MonthlyAccountingDimension.builder()
                    .workMonth(asOf)
                    .sourceType("bench-engineer")
                    .sourceId(row.getEngineerId())
                    .organizationId(row.getOrganizationId())
                    .costCenterId(costCenterId)
                    .revenue(BigDecimal.ZERO)
                    .cost(zeroIfNull(row.getWaitCost()))
                    .snapshotAt(LocalDateTime.now())
                    .build();
            try {
                inserted += dimensionMapper.insert(snapshot);
            } catch (DuplicateKeyException ignored) {
                // 同一月次の再実行では既存snapshotを上書きしない。
            }
        }
        return inserted;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
