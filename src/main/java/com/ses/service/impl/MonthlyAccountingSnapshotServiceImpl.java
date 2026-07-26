package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.ses.entity.Contract;
import com.ses.entity.CostCenter;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.MonthlyAccountingDimension;
import com.ses.entity.UserOrganization;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CostCenterMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.mapper.WorkRecordMapper;
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
    private final UserOrganizationMapper userOrganizationMapper;
    private final CostCenterMapper costCenterMapper;
    private final MonthlyAccountingDimensionMapper dimensionMapper;

    @Override
    @Transactional
    public int snapshotMonth(String workMonth) {
        YearMonth month = com.ses.common.util.DateUtils.parseYearMonth(workMonth);
        LocalDate asOf = month.atDay(1);
        List<WorkRecord> records = workRecordMapper.selectList(new QueryWrapper<WorkRecord>()
                .eq("work_month", workMonth).eq("status", "確定").orderByAsc("id"));
        int inserted = 0;
        for (WorkRecord record : records) {
            if (record.getId() == null || dimensionMapper.selectOne(new LambdaQueryWrapper<MonthlyAccountingDimension>()
                    .eq(MonthlyAccountingDimension::getWorkMonth, asOf)
                    .eq(MonthlyAccountingDimension::getSourceType, "work-record")
                    .eq(MonthlyAccountingDimension::getSourceId, record.getId())) != null) {
                continue;
            }
            Contract contract = record.getContractId() == null ? null : contractMapper.selectById(record.getContractId());
            Long organizationId = null;
            Long costCenterId = null;
            Long salesUserId = contract == null ? null : contract.getSalesUserId();
            if (contract != null && contract.getEngineerId() != null) {
                EngineerAccountLink link = engineerAccountLinkMapper.selectByEngineerId(contract.getEngineerId());
                if (link != null && link.getSysUserId() != null) {
                    UserOrganization assignment = userOrganizationMapper.selectOne(new LambdaQueryWrapper<UserOrganization>()
                            .eq(UserOrganization::getUserId, link.getSysUserId())
                            .eq(UserOrganization::getPrimaryFlag, 1)
                            .le(UserOrganization::getValidFrom, asOf)
                            .and(w -> w.isNull(UserOrganization::getValidTo).or().ge(UserOrganization::getValidTo, asOf))
                            .orderByDesc(UserOrganization::getValidFrom)
                            .last("LIMIT 1"));
                    if (assignment != null) {
                        organizationId = assignment.getOrganizationId();
                        CostCenter center = costCenterMapper.selectOne(new LambdaQueryWrapper<CostCenter>()
                                .eq(CostCenter::getOrganizationId, organizationId)
                                .eq(CostCenter::getStatus, "有効")
                                .le(CostCenter::getValidFrom, asOf)
                                .and(w -> w.isNull(CostCenter::getValidTo).or().ge(CostCenter::getValidTo, asOf))
                                .orderByAsc(CostCenter::getCode)
                                .last("LIMIT 1"));
                        costCenterId = center == null ? null : center.getId();
                    }
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
        return inserted;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
