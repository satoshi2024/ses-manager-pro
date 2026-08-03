package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.crm.CrmKpiDto;
import com.ses.entity.Lead;
import com.ses.entity.Opportunity;
import com.ses.entity.Proposal;
import com.ses.entity.SalesActivity;
import com.ses.entity.SysUser;
import com.ses.mapper.LeadMapper;
import com.ses.mapper.OpportunityMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.SalesActivityMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.CrmKpiService;
import com.ses.service.SystemConfigService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.CrmScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 商機・lead・活動の同一可視母集団からCRM KPIを生成する。 */
@Service
@RequiredArgsConstructor
public class CrmKpiServiceImpl implements CrmKpiService {
    private static final List<String> STAGES = List.of("見込", "要件確認", "提案準備", "見積提出", "交渉", "受注", "失注");
    private static final Set<String> PROPOSAL_OPEN = Set.of("書類選考中", "一次面接", "二次面接", "結果待ち");
    private final LeadMapper leadMapper;
    private final OpportunityMapper opportunityMapper;
    private final SalesActivityMapper salesActivityMapper;
    private final ProposalMapper proposalMapper;
    private final SysUserMapper sysUserMapper;
    private final DataScopeService dataScopeService;
    private final SystemConfigService systemConfigService;
    private final CrmScopeService crmScopeService;
    private final Clock clock;

    @Override
    public CrmKpiDto summarize(Long requestedOwnerId, String requestedStage) {
        Long effectiveOwner = requestedOwnerId;
        List<Opportunity> opportunities = visibleOpportunities(effectiveOwner, requestedStage);
        List<Lead> leads = visibleLeads(effectiveOwner);
        List<Proposal> proposals = visibleProposalForecast();
        Map<Long, List<SalesActivity>> activities = activitiesByOpportunity(opportunities);

        CrmKpiDto dto = new CrmKpiDto();
        dto.setStages(buildStages(opportunities, proposals, activities));
        dto.setOwners(buildOwners(leads, opportunities));
        dto.setLostReasons(buildLostReasons(opportunities));
        dto.setSources(buildSources(leads, opportunities));
        dto.setForecast(buildForecast(opportunities, proposals));
        return dto;
    }

    private List<Opportunity> visibleOpportunities(Long ownerId, String stage) {
        if (crmScopeService != null && !crmScopeService.canUseCrm()) return Collections.emptyList();
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Opportunity> query = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        if (StringUtilsCompat.hasText(stage)) query.eq("stage", stage);
        if (ownerId != null) {
            if (crmScopeService != null && !crmScopeService.isOwnerAllowed(ownerId, LocalDate.now(clock))) {
                return Collections.emptyList();
            }
            query.eq("owner_user_id", ownerId);
        }
        if (crmScopeService != null) {
            crmScopeService.applyOpportunityScope(query, LocalDate.now(clock));
        } else if (dataScopeService.isScoped()) {
            Set<Long> allowed = dataScopeService.allowedCustomerIds();
            if (allowed == null || allowed.isEmpty()) return Collections.emptyList();
            query.in("customer_id", allowed);
        }
        return opportunityMapper.selectList(query.orderByAsc("stage").orderByDesc("id"));
    }

    private List<Lead> visibleLeads(Long ownerId) {
        if (crmScopeService != null && !crmScopeService.canUseCrm()) return Collections.emptyList();
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Lead> query = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        if (ownerId != null) {
            if (crmScopeService != null && !crmScopeService.isOwnerAllowed(ownerId, LocalDate.now(clock))) {
                return Collections.emptyList();
            }
            query.eq("owner_user_id", ownerId);
        }
        if (crmScopeService != null) {
            crmScopeService.applyLeadScope(query, LocalDate.now(clock));
        } else if (dataScopeService.isScoped()) {
            Long current = SecurityUtils.currentUserId();
            if (current == null) query.isNull("owner_user_id");
            else query.and(w -> w.eq("owner_user_id", current).or().isNull("owner_user_id"));
        }
        return leadMapper.selectList(query.orderByDesc("id"));
    }

    private List<CrmKpiDto.StageKpi> buildStages(List<Opportunity> opportunities,
                                                  List<Proposal> proposals,
                                                  Map<Long, List<SalesActivity>> activities) {
        LocalDate today = LocalDate.now(clock);
        List<CrmKpiDto.StageKpi> result = new ArrayList<>();
        for (String stage : STAGES) {
            List<Opportunity> rows = opportunities.stream().filter(o -> Objects.equals(stage, o.getStage())).toList();
            CrmKpiDto.StageKpi item = new CrmKpiDto.StageKpi();
            item.setStage(stage);
            item.setCount(rows.size());
            item.setAmount(rows.stream().map(this::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
            item.setWeightedAmount(forecastOpportunities(rows, proposals).stream()
                    .map(this::weightedAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
            item.setAverageStagnationDays(averageDays(rows, today, true));
            item.setAverageNoActivityDays(averageNoActivityDays(rows, activities, today));
            result.add(item);
        }
        return result;
    }

    private List<CrmKpiDto.OwnerKpi> buildOwners(List<Lead> leads, List<Opportunity> opportunities) {
        Map<Long, CrmKpiDto.OwnerKpi> map = new LinkedHashMap<>();
        leads.forEach(lead -> owner(map, lead.getOwnerUserId()).setLeadCount(owner(map, lead.getOwnerUserId()).getLeadCount() + 1));
        leads.stream().filter(l -> "転換済".equals(l.getStatus())).forEach(lead -> {
            CrmKpiDto.OwnerKpi item = owner(map, lead.getOwnerUserId());
            item.setConvertedLeadCount(item.getConvertedLeadCount() + 1);
        });
        opportunities.forEach(opportunity -> {
            CrmKpiDto.OwnerKpi item = owner(map, opportunity.getOwnerUserId());
            item.setOpportunityCount(item.getOpportunityCount() + 1);
            if ("受注".equals(opportunity.getStage())) item.setWonCount(item.getWonCount() + 1);
            if ("失注".equals(opportunity.getStage())) item.setLostCount(item.getLostCount() + 1);
        });
        resolveOwnerNames(map);
        map.values().forEach(item -> {
            item.setLeadConversionRate(rate(item.getConvertedLeadCount(), item.getLeadCount()));
            item.setOpportunityConversionRate(rate(item.getWonCount(), item.getWonCount() + item.getLostCount()));
        });
        return new ArrayList<>(map.values());
    }

    private List<CrmKpiDto.LostReasonKpi> buildLostReasons(List<Opportunity> opportunities) {
        Map<String, CrmKpiDto.LostReasonKpi> map = new LinkedHashMap<>();
        opportunities.stream().filter(o -> "失注".equals(o.getStage())).forEach(o -> {
            String reason = o.getLostReason() == null || o.getLostReason().isBlank() ? "未入力" : o.getLostReason();
            CrmKpiDto.LostReasonKpi item = map.computeIfAbsent(reason, key -> { CrmKpiDto.LostReasonKpi v = new CrmKpiDto.LostReasonKpi(); v.setReason(key); v.setAmount(BigDecimal.ZERO); return v; });
            item.setCount(item.getCount() + 1);
            item.setAmount(item.getAmount().add(amount(o)));
        });
        return new ArrayList<>(map.values());
    }

    private List<CrmKpiDto.SourceRoiKpi> buildSources(List<Lead> leads, List<Opportunity> opportunities) {
        Map<Long, Opportunity> byId = opportunities.stream().filter(o -> o.getId() != null).collect(Collectors.toMap(Opportunity::getId, Function.identity(), (a, b) -> a));
        Map<String, CrmKpiDto.SourceRoiKpi> map = new LinkedHashMap<>();
        leads.forEach(lead -> {
            String source = lead.getSource() == null || lead.getSource().isBlank() ? "不明" : lead.getSource();
            CrmKpiDto.SourceRoiKpi item = map.computeIfAbsent(source, key -> { CrmKpiDto.SourceRoiKpi v = new CrmKpiDto.SourceRoiKpi(); v.setSource(key); v.setPipelineAmount(BigDecimal.ZERO); v.setWonAmount(BigDecimal.ZERO); return v; });
            item.setLeadCount(item.getLeadCount() + 1);
            if (lead.getSourceCost() != null) {
                item.setSourceCost((item.getSourceCost() == null ? BigDecimal.ZERO : item.getSourceCost())
                        .add(lead.getSourceCost()));
            }
            if ("転換済".equals(lead.getStatus())) item.setConvertedLeadCount(item.getConvertedLeadCount() + 1);
            Opportunity opportunity = byId.get(lead.getConvertedOpportunityId());
            if (opportunity != null) {
                item.setPipelineAmount(item.getPipelineAmount().add(amount(opportunity)));
                if ("受注".equals(opportunity.getStage())) { item.setWonCount(item.getWonCount() + 1); item.setWonAmount(item.getWonAmount().add(amount(opportunity))); }
            }
        });
        map.values().forEach(item -> {
            BigDecimal cost = item.getSourceCost();
            item.setRoiRate(cost == null || cost.signum() == 0 ? null
                    : item.getWonAmount().subtract(cost).multiply(BigDecimal.valueOf(100))
                    .divide(cost, 1, RoundingMode.HALF_UP));
        });
        return new ArrayList<>(map.values());
    }

    private CrmKpiDto.ForecastKpi buildForecast(List<Opportunity> opportunities, List<Proposal> proposals) {
        CrmKpiDto.ForecastKpi forecast = new CrmKpiDto.ForecastKpi();
        List<Opportunity> forecastOpportunities = forecastOpportunities(opportunities, proposals);
        BigDecimal opportunityAmount = forecastOpportunities.stream()
                .map(this::weightedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        forecast.setOpportunityAmount(opportunityAmount);
        forecast.setOpportunityCount(forecastOpportunities.size());
        forecast.setProposalAmount(proposals.stream().map(this::weightedProposalAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        forecast.setProposalCount(proposals.size());
        return forecast;
    }

    private List<Proposal> visibleProposalForecast() {
        if (crmScopeService != null && !crmScopeService.canUseCrm()) return Collections.emptyList();
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Proposal> query =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Proposal>()
                .in("status", PROPOSAL_OPEN);
        if (dataScopeService.isScoped()) {
            Set<Long> allowed = dataScopeService.allowedProposalIds();
            if (allowed == null || allowed.isEmpty()) return Collections.emptyList();
            query.in("id", allowed);
        }
        if (crmScopeService != null) {
            crmScopeService.applyProposalScope(query, LocalDate.now(clock));
        }
        return proposalMapper.selectList(query);
    }

    private List<Opportunity> forecastOpportunities(List<Opportunity> opportunities, List<Proposal> proposals) {
        Set<Long> proposalOpportunityIds = proposals.stream().map(Proposal::getSourceOpportunityId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        return opportunities.stream().filter(this::isOpenUnconverted)
                .filter(o -> !proposalOpportunityIds.contains(o.getId())).toList();
    }

    private BigDecimal weightedProposalAmount(Proposal proposal) {
        BigDecimal rate = switch (proposal.getStatus()) {
            case "書類選考中" -> systemConfigService.getDecimal("forecast.win-rate.screening", BigDecimal.valueOf(20));
            case "一次面接" -> systemConfigService.getDecimal("forecast.win-rate.first-interview", BigDecimal.valueOf(40));
            case "二次面接" -> systemConfigService.getDecimal("forecast.win-rate.second-interview", BigDecimal.valueOf(60));
            case "結果待ち" -> systemConfigService.getDecimal("forecast.win-rate.awaiting", BigDecimal.valueOf(80));
            default -> BigDecimal.ZERO;
        };
        return proposal.getProposedUnitPrice() == null ? BigDecimal.ZERO : proposal.getProposedUnitPrice().multiply(rate).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
    }

    private BigDecimal amount(Opportunity opportunity) {
        if (opportunity.getExpectedAmount() != null) return opportunity.getExpectedAmount();
        if (opportunity.getUnitPrice() == null) return BigDecimal.ZERO;
        return opportunity.getUnitPrice().multiply(BigDecimal.valueOf(opportunity.getRequiredCount() == null ? 1 : opportunity.getRequiredCount()));
    }

    private BigDecimal weightedAmount(Opportunity opportunity) {
        int probability = opportunity.getProbability() == null ? defaultProbability(opportunity.getStage()) : opportunity.getProbability();
        return amount(opportunity).multiply(BigDecimal.valueOf(probability)).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
    }

    private int defaultProbability(String stage) {
        return switch (stage) { case "見込" -> 20; case "要件確認" -> 30; case "提案準備" -> 40; case "見積提出" -> 60; case "交渉" -> 80; default -> 0; };
    }

    private boolean isOpenUnconverted(Opportunity opportunity) {
        return !"受注".equals(opportunity.getStage()) && !"失注".equals(opportunity.getStage()) && opportunity.getConvertedQuotationId() == null;
    }

    private long averageDays(List<Opportunity> rows, LocalDate today, boolean stageUpdated) {
        if (rows.isEmpty()) return 0;
        return Math.round(rows.stream().mapToLong(o -> daysSince(stageUpdated
                ? (o.getStageChangedAt() != null ? o.getStageChangedAt() : o.getUpdatedAt())
                : o.getCreatedAt(), today)).average().orElse(0));
    }

    private long averageNoActivityDays(List<Opportunity> rows, Map<Long, List<SalesActivity>> activities, LocalDate today) {
        if (rows.isEmpty()) return 0;
        return Math.round(rows.stream().mapToLong(o -> {
            List<SalesActivity> latest = activities.getOrDefault(o.getId(), List.of());
            LocalDate last = latest.stream().map(SalesActivity::getActivityDate).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            return last == null ? daysSince(o.getCreatedAt(), today) : ChronoUnit.DAYS.between(last, today);
        }).average().orElse(0));
    }

    private long daysSince(LocalDateTime dateTime, LocalDate today) {
        return dateTime == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(dateTime.toLocalDate(), today));
    }

    private Map<Long, List<SalesActivity>> activitiesByOpportunity(List<Opportunity> opportunities) {
        List<Long> ids = opportunities.stream().map(Opportunity::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return Collections.emptyMap();
        return salesActivityMapper.selectList(new LambdaQueryWrapper<SalesActivity>().in(SalesActivity::getOpportunityId, ids))
                .stream().collect(Collectors.groupingBy(SalesActivity::getOpportunityId));
    }

    private CrmKpiDto.OwnerKpi owner(Map<Long, CrmKpiDto.OwnerKpi> map, Long ownerId) {
        return map.computeIfAbsent(ownerId, id -> { CrmKpiDto.OwnerKpi item = new CrmKpiDto.OwnerKpi(); item.setOwnerUserId(id); item.setOwnerName(id == null ? "未割当" : ""); return item; });
    }

    private void resolveOwnerNames(Map<Long, CrmKpiDto.OwnerKpi> map) {
        List<Long> ids = map.keySet().stream().filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return;
        Map<Long, SysUser> users = sysUserMapper.selectByIdsIncludingDeleted(ids).stream().collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));
        map.forEach((id, item) -> { if (id != null) { SysUser user = users.get(id); item.setOwnerName(user == null ? "不明" : user.getRealName()); } });
    }

    private BigDecimal rate(long numerator, long denominator) {
        return denominator == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(numerator * 100.0 / denominator).setScale(1, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(BigDecimal numerator, BigDecimal denominator) {
        return denominator == null || denominator.signum() == 0 ? BigDecimal.ZERO : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 1, RoundingMode.HALF_UP);
    }

    private Long effectiveOwner(Long requestedOwnerId) {
        if (isSalesScoped()) return SecurityUtils.currentUserId();
        return requestedOwnerId;
    }

    private boolean isSalesScoped() {
        return dataScopeService.isSalesDataScoped() && "営業".equals(SecurityUtils.currentRole());
    }

    /** null/blank判定を共通依存なしで行う小さな補助。 */
    private static final class StringUtilsCompat {
        private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    }
}
