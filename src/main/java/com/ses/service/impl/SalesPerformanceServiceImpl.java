package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.constant.StatusConstants;
import com.ses.dto.engineersales.SalesUserAssignCountDto;
import com.ses.dto.salesperformance.CommissionRuleDto;
import com.ses.dto.salesperformance.SalesPerformanceDto;
import com.ses.entity.Contract;
import com.ses.entity.Proposal;
import com.ses.entity.SysUser;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.SalesPerformanceService;
import com.ses.service.SysUserService;
import com.ses.service.SystemConfigService;
import com.ses.service.billing.MonthlyRevenueCalcService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SalesPerformanceServiceImpl implements SalesPerformanceService {

    /** 未帰属(担当営業なし)集計用の内部キー。 */
    private static final Long UNATTRIBUTED_ID = -1L;

    private final SysUserService sysUserService;
    private final SystemConfigService systemConfigService;
    private final ContractMapper contractMapper;
    private final ProposalMapper proposalMapper;
    private final WorkRecordMapper workRecordMapper;
    private final EngineerSalesMapper engineerSalesMapper;
    private final SysUserMapper sysUserMapper;
    private final MonthlyRevenueCalcService monthlyRevenueCalcService;

    @Override
    public List<SalesPerformanceDto> calculateMonthlyPerformance(String yearMonth) {
        YearMonth targetMonth;
        if (yearMonth == null || yearMonth.isBlank()) {
            targetMonth = YearMonth.now();
        } else {
            targetMonth = com.ses.common.util.DateUtils.parseYearMonth(yearMonth);
        }
        String ymStr = targetMonth.toString();
        
        LocalDate startOfMonthDate = targetMonth.atDay(1);
        LocalDate endOfMonthDate = targetMonth.atEndOfMonth();
        LocalDateTime startOfMonthTime = startOfMonthDate.atStartOfDay();
        LocalDateTime endOfMonthTime = endOfMonthDate.atTime(23, 59, 59, 999999999);

        List<SysUser> salesUsers = sysUserService.list(new QueryWrapper<SysUser>().eq("role", StatusConstants.ROLE_SALES).eq("status", 1));

        List<Contract> allContracts = contractMapper.selectList(new QueryWrapper<>());

        Set<Long> userIds = salesUsers.stream().map(SysUser::getId).collect(Collectors.toSet());
        for (Contract c : allContracts) {
            if (c.getSalesUserId() != null) {
                userIds.add(c.getSalesUserId());
            }
        }

        Map<Long, String> userNameMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            List<SysUser> allNeededUsers = sysUserMapper.selectByIdsIncludingDeleted(userIds);
            for (SysUser u : allNeededUsers) {
                userNameMap.put(u.getId(), u.getRealName());
            }
        }

        List<SalesUserAssignCountDto> assignCounts = engineerSalesMapper.countActivePrimaryGroupBySalesUser();
        Map<Long, Integer> activePrimaryMap = new HashMap<>();
        for (SalesUserAssignCountDto dto : assignCounts) {
            activePrimaryMap.put(dto.getSalesUserId(), dto.getEngineerCount().intValue());
        }

        List<Proposal> targetProposals = proposalMapper.selectList(new QueryWrapper<Proposal>()
                .ge("closed_at", startOfMonthTime)
                .le("closed_at", endOfMonthTime));
        Map<Long, Integer> proposalWon = new HashMap<>();
        Map<Long, Integer> proposalTotal = new HashMap<>();
        for (Proposal p : targetProposals) {
            if (p.getProposedBy() != null && p.getStatus() != null) {
                Long uid = p.getProposedBy();
                if (StatusConstants.PROPOSAL_CONTRACTED.equals(p.getStatus()) || StatusConstants.PROPOSAL_REJECTED.equals(p.getStatus())) {
                    proposalTotal.put(uid, proposalTotal.getOrDefault(uid, 0) + 1);
                    if (StatusConstants.PROPOSAL_CONTRACTED.equals(p.getStatus())) {
                        proposalWon.put(uid, proposalWon.getOrDefault(uid, 0) + 1);
                    }
                }
            }
        }

        CommissionRuleDto defaultRule = getCommissionRule();

        Map<Long, Integer> closedContractCountMap = new HashMap<>();
        Map<Long, Integer> activeContractCountMap = new HashMap<>();
        Map<Long, BigDecimal> totalSalesMap = new HashMap<>();
        Map<Long, BigDecimal> totalProfitMap = new HashMap<>();
        Map<Long, BigDecimal> totalCommissionMap = new HashMap<>();

        List<WorkRecord> workRecords = workRecordMapper.selectList(new QueryWrapper<WorkRecord>()
                .eq("work_month", ymStr)
                .eq("status", "確定"));
        Map<Long, WorkRecord> workRecordMap = workRecords.stream()
                .collect(Collectors.toMap(WorkRecord::getContractId, w -> w, (w1, w2) -> w1));

        Map<Long, MonthlyRevenueCalcService.ContractAmount> amountBatchMap = monthlyRevenueCalcService.resolveContractAmountBatch(allContracts, workRecordMap, targetMonth);

        for (Contract c : allContracts) {
            boolean unattributed = (c.getSalesUserId() == null);
            // 未帰属(担当営業なし)契約は稼動額・粗利・稼動契約数のみ UNATTRIBUTED_ID キーで集計する。
            // 成約数・成約率・担当要員数・インセンティブは対象外(担当が存在しないため)。
            Long uid = unattributed ? UNATTRIBUTED_ID : c.getSalesUserId();

            if (!unattributed && c.getCreatedAt() != null &&
                    !c.getCreatedAt().isBefore(startOfMonthTime) &&
                    !c.getCreatedAt().isAfter(endOfMonthTime) &&
                    c.getRenewedFromContractId() == null) {
                closedContractCountMap.put(uid, closedContractCountMap.getOrDefault(uid, 0) + 1);
            }

            if (isActiveInMonth(c, startOfMonthDate, endOfMonthDate)) {

                activeContractCountMap.put(uid, activeContractCountMap.getOrDefault(uid, 0) + 1);

                // 1契約分の金額決定は共通口径サービスへ委譲(確定実績優先、なければ契約単価)。
                MonthlyRevenueCalcService.ContractAmount amount =
                        amountBatchMap.get(c.getId());
                BigDecimal sales = amount.getSales();
                BigDecimal profit = amount.getProfit();

                totalSalesMap.put(uid, totalSalesMap.getOrDefault(uid, BigDecimal.ZERO).add(sales));
                totalProfitMap.put(uid, totalProfitMap.getOrDefault(uid, BigDecimal.ZERO).add(profit));

                if (!unattributed) {
                    String baseType = c.getCommissionBaseType() != null ? c.getCommissionBaseType() : defaultRule.getBaseType();
                    BigDecimal rate = c.getCommissionRate() != null ? c.getCommissionRate() : defaultRule.getRate();

                    BigDecimal baseAmount = StatusConstants.COMMISSION_BASE_SALES.equals(baseType) ? sales : profit;
                    if (baseAmount.compareTo(BigDecimal.ZERO) > 0 && rate != null) {
                        BigDecimal commission = baseAmount.multiply(rate).divide(new BigDecimal("100"), 0, RoundingMode.FLOOR);
                        totalCommissionMap.put(uid, totalCommissionMap.getOrDefault(uid, BigDecimal.ZERO).add(commission));
                    }
                }
            }
        }

        List<SalesPerformanceDto> result = new ArrayList<>();
        Long currentUserId = com.ses.common.util.SecurityUtils.currentUserId();
        for (Long uid : userIds) {
            SalesPerformanceDto dto = new SalesPerformanceDto();
            dto.setSalesUserId(uid);
            dto.setSelf(uid != null && uid.equals(currentUserId));
            dto.setSalesUserName(userNameMap.getOrDefault(uid, "Unknown"));
            dto.setActivePrimaryCount(activePrimaryMap.getOrDefault(uid, 0));
            dto.setClosedContractCount(closedContractCountMap.getOrDefault(uid, 0));
            
            Integer w = proposalWon.getOrDefault(uid, 0);
            Integer t = proposalTotal.getOrDefault(uid, 0);
            if (t > 0) {
                dto.setClosedRate(new BigDecimal(w).multiply(BigDecimal.valueOf(100))
                        .divide(new BigDecimal(t), 2, RoundingMode.HALF_UP));
            } else {
                dto.setClosedRate(null);
            }
            
            dto.setActiveContractCount(activeContractCountMap.getOrDefault(uid, 0));
            dto.setTotalSalesAmount(totalSalesMap.getOrDefault(uid, BigDecimal.ZERO));
            dto.setTotalProfitAmount(totalProfitMap.getOrDefault(uid, BigDecimal.ZERO));
            dto.setTotalCommissionAmount(totalCommissionMap.getOrDefault(uid, BigDecimal.ZERO));
            result.add(dto);
        }

        result.sort(Comparator.comparing(SalesPerformanceDto::getSalesUserId));

        // 未帰属(担当営業なし)の稼動契約があれば、全社売上と突合できるよう最終行として合算表示する。
        if (activeContractCountMap.containsKey(UNATTRIBUTED_ID)) {
            SalesPerformanceDto u = new SalesPerformanceDto();
            u.setUnattributed(true);
            u.setSalesUserId(UNATTRIBUTED_ID);
            u.setSalesUserName(null);
            u.setActivePrimaryCount(null); // 担当要員数は対象外(—)
            u.setClosedContractCount(null); // 成約数は対象外(—)
            u.setClosedRate(null); // 成約率は対象外(—)
            u.setActiveContractCount(activeContractCountMap.getOrDefault(UNATTRIBUTED_ID, 0));
            u.setTotalSalesAmount(totalSalesMap.getOrDefault(UNATTRIBUTED_ID, BigDecimal.ZERO));
            u.setTotalProfitAmount(totalProfitMap.getOrDefault(UNATTRIBUTED_ID, BigDecimal.ZERO));
            u.setTotalCommissionAmount(BigDecimal.ZERO);
            result.add(u);
        }
        return result;
    }

    private boolean isActiveInMonth(Contract contract, LocalDate startOfMonth, LocalDate endOfMonth) {
        if (contract.getStartDate() == null || StatusConstants.CONTRACT_PREPARING.equals(contract.getStatus())) {
            return false;
        }
        return !contract.getStartDate().isAfter(endOfMonth)
                && (contract.getEndDate() == null || !contract.getEndDate().isBefore(startOfMonth));
    }

    @Override
    public CommissionRuleDto getCommissionRule() {
        CommissionRuleDto dto = new CommissionRuleDto();
        dto.setBaseType(systemConfigService.getString("commission.base-type", StatusConstants.COMMISSION_BASE_PROFIT));
        dto.setRate(systemConfigService.getDecimal("commission.rate", new BigDecimal("5.0")));
        return dto;
    }
}
