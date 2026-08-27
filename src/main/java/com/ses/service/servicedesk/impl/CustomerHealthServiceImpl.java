package com.ses.service.servicedesk.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.servicedesk.CustomerHealthScoreDto;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.CustomerCsat;
import com.ses.entity.CustomerHealthSnapshot;
import com.ses.entity.CustomerQbr;
import com.ses.entity.ServiceRequest;
import com.ses.entity.ServiceSlaClock;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerCsatMapper;
import com.ses.mapper.CustomerHealthSnapshotMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.CustomerQbrMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.mapper.ServiceSlaClockMapper;
import com.ses.service.security.DataScopeService;
import com.ses.service.servicedesk.CustomerHealthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerHealthServiceImpl implements CustomerHealthService {

    private final CustomerMapper customerMapper;
    private final ServiceRequestMapper serviceRequestMapper;
    private final ServiceSlaClockMapper slaClockMapper;
    private final CustomerCsatMapper csatMapper;
    private final ContractMapper contractMapper;
    private final CustomerQbrMapper qbrMapper;
    private final CustomerHealthSnapshotMapper snapshotMapper;
    private final DataScopeService dataScopeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional(readOnly = true)
    public CustomerHealthScoreDto calculateCustomerHealth(Long customerId) {
        Customer customer = customerMapper.selectById(customerId);
        if (customer == null) {
            throw BusinessException.of(404, "指定された顧客が見つかりません");
        }
        if (dataScopeService.isScoped()) {
            dataScopeService.assertAllowedCustomer(customerId);
        }

        return computeScore(customer);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, CustomerHealthScoreDto> getHealthMapForCustomers(Set<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return Map.of();
        }

        List<Customer> customers = customerMapper.selectBatchIds(customerIds);
        Map<Long, CustomerHealthScoreDto> result = new HashMap<>();
        for (Customer c : customers) {
            result.put(c.getId(), computeScore(c));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerHealthScoreDto> listCustomerHealthSummaries(String healthStatus, String keyword) {
        LambdaQueryWrapper<Customer> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Customer::getCompanyName, keyword);
        }
        if (dataScopeService.isScoped()) {
            Set<Long> allowed = dataScopeService.allowedCustomerIds();
            if (allowed == null || allowed.isEmpty()) {
                return List.of();
            }
            wrapper.in(Customer::getId, allowed);
        }

        List<Customer> customers = customerMapper.selectList(wrapper);
        List<CustomerHealthScoreDto> dtos = customers.stream()
                .map(this::computeScore)
                .collect(Collectors.toList());

        if (StringUtils.hasText(healthStatus)) {
            dtos = dtos.stream()
                    .filter(d -> Objects.equals(d.getHealthStatus(), healthStatus))
                    .collect(Collectors.toList());
        }

        return dtos;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void generateMonthlySnapshot(String snapshotMonth) {
        LocalDate snapshotDate = LocalDate.now();
        if (StringUtils.hasText(snapshotMonth)) {
            try {
                snapshotDate = LocalDate.parse(snapshotMonth + "-01");
            } catch (Exception ignored) {
            }
        }

        List<Customer> allCustomers = customerMapper.selectList(null);
        LocalDateTime now = LocalDateTime.now();

        for (Customer c : allCustomers) {
            CustomerHealthScoreDto scoreDto = computeScore(c);

            String breakdownStr = "{}";
            try {
                breakdownStr = objectMapper.writeValueAsString(scoreDto.getFactorBreakdown());
            } catch (JsonProcessingException ignored) {
            }

            Object avgCsatObj = scoreDto.getFactorBreakdown().get("averageCsat");
            java.math.BigDecimal avgCsat = (avgCsatObj instanceof Number) 
                    ? java.math.BigDecimal.valueOf(((Number) avgCsatObj).doubleValue()) 
                    : null;

            CustomerHealthSnapshot snapshot = CustomerHealthSnapshot.builder()
                    .customerId(c.getId())
                    .snapshotDate(snapshotDate)
                    .healthStatus(scoreDto.getHealthStatus())
                    .totalScore(scoreDto.getHealthScore())
                    .slaBreachCount30d(((Number) scoreDto.getFactorBreakdown().getOrDefault("breachedRequests90d", 0)).intValue())
                    .avgCsatScore(avgCsat)
                    .factorsExplanation(breakdownStr)
                    .missingInputsJson("{}")
                    .createdAt(now)
                    .build();

            // 既存スナップショットの重複更新対応
            snapshotMapper.delete(
                    new LambdaQueryWrapper<CustomerHealthSnapshot>()
                            .eq(CustomerHealthSnapshot::getCustomerId, c.getId())
                            .eq(CustomerHealthSnapshot::getSnapshotDate, snapshotDate)
            );
            snapshotMapper.insert(snapshot);
        }
    }

    private CustomerHealthScoreDto computeScore(Customer customer) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime ninetyDaysAgo = now.minusDays(90);
        LocalDate today = LocalDate.now();

        // 1. SLA 遵守度 (max 30)
        List<ServiceRequest> recentRequests = serviceRequestMapper.selectList(
                new LambdaQueryWrapper<ServiceRequest>()
                        .eq(ServiceRequest::getCustomerId, customer.getId())
                        .ge(ServiceRequest::getCreatedAt, ninetyDaysAgo)
        );

        double slaScore = 30.0;
        int totalRequests = recentRequests.size();
        int breachedRequests = 0;

        if (totalRequests > 0) {
            List<Long> reqIds = recentRequests.stream().map(ServiceRequest::getId).collect(Collectors.toList());
            List<ServiceSlaClock> clocks = slaClockMapper.selectList(
                    new LambdaQueryWrapper<ServiceSlaClock>()
                            .in(ServiceSlaClock::getServiceRequestId, reqIds)
            );
            Set<Long> breachedIds = clocks.stream()
                    .filter(c -> Boolean.TRUE.equals(c.getResponseBreached()) || Boolean.TRUE.equals(c.getResolveBreached()))
                    .map(ServiceSlaClock::getServiceRequestId)
                    .collect(Collectors.toSet());
            breachedRequests = breachedIds.size();
            double complianceRate = 1.0 - ((double) breachedRequests / totalRequests);
            slaScore = Math.max(0.0, Math.min(30.0, 30.0 * complianceRate));
        }

        // 2. サポート満足度 CSAT (max 25)
        double csatFactorScore = 20.0; // default for missing input
        Double avgCsat = null;
        if (totalRequests > 0) {
            List<Long> reqIds = recentRequests.stream().map(ServiceRequest::getId).collect(Collectors.toList());
            List<CustomerCsat> csats = csatMapper.selectList(
                    new LambdaQueryWrapper<CustomerCsat>()
                            .in(CustomerCsat::getServiceRequestId, reqIds)
            );
            if (!csats.isEmpty()) {
                avgCsat = csats.stream().mapToInt(CustomerCsat::getScore).average().orElse(4.0);
                csatFactorScore = (avgCsat / 5.0) * 25.0;
            }
        }

        // 3. 契約・稼働安定度 (max 25)
        long activeContractCount = contractMapper.selectCount(
                new LambdaQueryWrapper<Contract>()
                        .eq(Contract::getCustomerId, customer.getId())
                        .ge(Contract::getEndDate, today)
        );
        double engagementScore = activeContractCount > 0 ? 25.0 : 10.0;

        // 4. コミュニケーション頻度 (max 20)
        long recentQbrCount = qbrMapper.selectCount(
                new LambdaQueryWrapper<CustomerQbr>()
                        .eq(CustomerQbr::getCustomerId, customer.getId())
                        .ge(CustomerQbr::getMeetingDate, today.minusDays(90))
        );
        double communicationScore = (totalRequests > 0 || recentQbrCount > 0) ? 20.0 : 10.0;

        int totalHealthScore = (int) Math.round(slaScore + csatFactorScore + engagementScore + communicationScore);
        totalHealthScore = Math.max(0, Math.min(100, totalHealthScore));

        String healthStatus;
        if (totalHealthScore >= 80) {
            healthStatus = "HEALTHY";
        } else if (totalHealthScore >= 60) {
            healthStatus = "NEUTRAL";
        } else {
            healthStatus = "AT_RISK";
        }

        Map<String, Object> breakdown = new HashMap<>();
        breakdown.put("totalRequests90d", totalRequests);
        breakdown.put("breachedRequests90d", breachedRequests);
        breakdown.put("avgCsat", avgCsat != null ? avgCsat : "N/A");
        breakdown.put("activeContracts", activeContractCount);
        breakdown.put("recentQbrCount", recentQbrCount);
        breakdown.put("slaScore", Math.round(slaScore * 10.0) / 10.0);
        breakdown.put("csatScore", Math.round(csatFactorScore * 10.0) / 10.0);
        breakdown.put("engagementScore", Math.round(engagementScore * 10.0) / 10.0);
        breakdown.put("communicationScore", Math.round(communicationScore * 10.0) / 10.0);

        return CustomerHealthScoreDto.builder()
                .customerId(customer.getId())
                .customerName(customer.getCompanyName())
                .healthScore(totalHealthScore)
                .healthStatus(healthStatus)
                .slaComplianceScore(Math.round(slaScore * 10.0) / 10.0)
                .csatScore(Math.round(csatFactorScore * 10.0) / 10.0)
                .engagementScore(Math.round(engagementScore * 10.0) / 10.0)
                .communicationScore(Math.round(communicationScore * 10.0) / 10.0)
                .factorBreakdown(breakdown)
                .calculatedAt(now)
                .build();
    }
}
