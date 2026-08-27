package com.ses.service.servicedesk.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.servicedesk.CustomerHealthScoreDto;
import com.ses.entity.Customer;
import com.ses.entity.CustomerCsat;
import com.ses.entity.CustomerHealthSnapshot;
import com.ses.entity.Invoice;
import com.ses.entity.ServiceRequest;
import com.ses.entity.ServiceSlaClock;
import com.ses.mapper.CustomerCsatMapper;
import com.ses.mapper.CustomerHealthSnapshotMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.mapper.ServiceSlaClockMapper;
import com.ses.service.security.DataScopeService;
import com.ses.service.servicedesk.CustomerHealthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 顧客ヘルススコア算定サービス (100点減点モデル: WIP-3, WIP-4)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerHealthServiceImpl implements CustomerHealthService {

    private final CustomerMapper customerMapper;
    private final ServiceRequestMapper serviceRequestMapper;
    private final ServiceSlaClockMapper slaClockMapper;
    private final CustomerCsatMapper csatMapper;
    private final ObjectProvider<InvoiceMapper> invoiceMapperProvider;
    private final CustomerHealthSnapshotMapper snapshotMapper;
    private final DataScopeService dataScopeService;
    private final Clock clock;
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
        LocalDate snapshotDate = LocalDate.now(clock);
        if (StringUtils.hasText(snapshotMonth)) {
            try {
                snapshotDate = LocalDate.parse(snapshotMonth + "-01");
            } catch (Exception ignored) {
            }
        }

        List<Customer> allCustomers = customerMapper.selectList(null);
        LocalDateTime now = LocalDateTime.now(clock);

        for (Customer c : allCustomers) {
            CustomerHealthScoreDto scoreDto = computeScore(c);

            String breakdownStr = "{}";
            try {
                breakdownStr = objectMapper.writeValueAsString(scoreDto.getFactorBreakdown());
            } catch (JsonProcessingException ignored) {
            }

            String missingJson = "[]";
            try {
                missingJson = objectMapper.writeValueAsString(scoreDto.getMissingInputs());
            } catch (JsonProcessingException ignored) {
            }

            CustomerHealthSnapshot existing = snapshotMapper.selectOne(
                    new LambdaQueryWrapper<CustomerHealthSnapshot>()
                            .eq(CustomerHealthSnapshot::getCustomerId, c.getId())
                            .eq(CustomerHealthSnapshot::getSnapshotDate, snapshotDate)
                            .last("LIMIT 1"));

            if (existing != null) {
                // 非破壊更新 (WIP-3: deleteせずに既存行を更新)
                existing.setHealthStatus(scoreDto.getHealthStatus());
                existing.setTotalScore(scoreDto.getHealthScore());
                existing.setOpenCriticalIssuesCount(scoreDto.getOpenCriticalIssuesCount());
                existing.setSlaBreachCount30d(scoreDto.getSlaBreachCount30d());
                existing.setAvgCsatScore(scoreDto.getAvgCsatScore());
                existing.setArOverdueFlag(Boolean.TRUE.equals(scoreDto.getArOverdueFlag()));
                existing.setMissingInputsJson(missingJson);
                existing.setFactorsExplanation(breakdownStr);
                snapshotMapper.updateById(existing);
            } else {
                CustomerHealthSnapshot snapshot = CustomerHealthSnapshot.builder()
                        .customerId(c.getId())
                        .snapshotDate(snapshotDate)
                        .healthStatus(scoreDto.getHealthStatus())
                        .totalScore(scoreDto.getHealthScore())
                        .openCriticalIssuesCount(scoreDto.getOpenCriticalIssuesCount())
                        .slaBreachCount30d(scoreDto.getSlaBreachCount30d())
                        .avgCsatScore(scoreDto.getAvgCsatScore())
                        .arOverdueFlag(Boolean.TRUE.equals(scoreDto.getArOverdueFlag()))
                        .factorsExplanation(breakdownStr)
                        .missingInputsJson(missingJson)
                        .createdAt(now)
                        .build();
                snapshotMapper.insert(snapshot);
            }
        }
    }

    /**
     * 100点減点モデルによるヘルススコア算定 (WIP-3)
     */
    private CustomerHealthScoreDto computeScore(Customer customer) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDateTime ninetyDaysAgo = now.minusDays(90);
        LocalDateTime oneEightyDaysAgo = now.minusDays(180);

        List<String> missingInputs = new ArrayList<>();
        List<String> deductionExplanations = new ArrayList<>();
        int totalDeduction = 0;

        // 1. 未解決 P0 / P1 リクエスト件数 (P0: -20点/件, P1: -10点/件)
        List<ServiceRequest> openCriticalRequests = serviceRequestMapper.selectList(
                new LambdaQueryWrapper<ServiceRequest>()
                        .eq(ServiceRequest::getCustomerId, customer.getId())
                        .in(ServiceRequest::getPriority, List.of("P0", "P1"))
                        .notIn(ServiceRequest::getStatus, List.of("RESOLVED", "CLOSED")));

        int openP0Count = 0;
        int openP1Count = 0;
        for (ServiceRequest r : openCriticalRequests) {
            if ("P0".equals(r.getPriority())) {
                openP0Count++;
            } else if ("P1".equals(r.getPriority())) {
                openP1Count++;
            }
        }
        int openCriticalCount = openP0Count + openP1Count;
        int criticalDeduction = (openP0Count * 20) + (openP1Count * 10);
        if (criticalDeduction > 0) {
            totalDeduction += criticalDeduction;
            deductionExplanations.add(String.format("未解決重大障害減点: -%d点 (P0:%d件, P1:%d件)", criticalDeduction, openP0Count, openP1Count));
        }

        // 2. 直近90日 SLA 違反件数 (-10点/件)
        List<ServiceRequest> recentRequests = serviceRequestMapper.selectList(
                new LambdaQueryWrapper<ServiceRequest>()
                        .eq(ServiceRequest::getCustomerId, customer.getId())
                        .ge(ServiceRequest::getCreatedAt, ninetyDaysAgo));

        int breachedCount = 0;
        if (!recentRequests.isEmpty()) {
            List<Long> reqIds = recentRequests.stream().map(ServiceRequest::getId).collect(Collectors.toList());
            List<ServiceSlaClock> clocks = slaClockMapper.selectList(
                    new LambdaQueryWrapper<ServiceSlaClock>()
                            .in(ServiceSlaClock::getServiceRequestId, reqIds));

            Set<Long> breachedIds = clocks.stream()
                    .filter(c -> Boolean.TRUE.equals(c.getResponseBreached()) || Boolean.TRUE.equals(c.getResolveBreached()))
                    .map(ServiceSlaClock::getServiceRequestId)
                    .collect(Collectors.toSet());
            breachedCount = breachedIds.size();
        }
        int slaDeduction = breachedCount * 10;
        if (slaDeduction > 0) {
            totalDeduction += slaDeduction;
            deductionExplanations.add(String.format("直近90日SLA違反減点: -%d点 (違反:%d件)", slaDeduction, breachedCount));
        }

        // 3. 直近180日 CSAT 平均 (4.0以上: 0点, 3.0-3.9: -10点, 2.0-2.9: -20点, <2.0: -30点)
        List<CustomerCsat> recentCsats = csatMapper.selectList(
                new LambdaQueryWrapper<CustomerCsat>()
                        .eq(CustomerCsat::getCustomerId, customer.getId())
                        .ge(CustomerCsat::getAnsweredAt, oneEightyDaysAgo));

        BigDecimal avgCsat = null;
        int csatDeduction = 0;
        if (!recentCsats.isEmpty()) {
            double avg = recentCsats.stream().mapToInt(CustomerCsat::getScore).average().orElse(0.0);
            avgCsat = BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP);
            if (avg < 2.0) {
                csatDeduction = 30;
            } else if (avg < 3.0) {
                csatDeduction = 20;
            } else if (avg < 4.0) {
                csatDeduction = 10;
            }
            if (csatDeduction > 0) {
                totalDeduction += csatDeduction;
                deductionExplanations.add(String.format("CSAT評価低迷減点: -%d点 (平均CSAT: %.2f)", csatDeduction, avg));
            }
        } else {
            missingInputs.add("CSAT");
        }

        // 4. 売掛金延滞 (AR Overdue: -20点)
        boolean hasOverdue = false;
        InvoiceMapper invoiceMapper = invoiceMapperProvider.getIfAvailable();
        if (invoiceMapper != null) {
            List<Invoice> invoices = invoiceMapper.selectList(
                    new LambdaQueryWrapper<Invoice>()
                            .eq(Invoice::getCustomerId, customer.getId()));
            if (invoices.isEmpty()) {
                missingInputs.add("INVOICE");
            } else {
                hasOverdue = invoices.stream().anyMatch(inv ->
                        inv.getDueDate() != null &&
                        inv.getDueDate().isBefore(today) &&
                        !"PAID".equalsIgnoreCase(inv.getStatus()) &&
                        !"CANCELLED".equalsIgnoreCase(inv.getStatus()));
                if (hasOverdue) {
                    totalDeduction += 20;
                    deductionExplanations.add("売掛金(請求)延滞減点: -20点");
                }
            }
        } else {
            missingInputs.add("INVOICE");
        }

        // 総合スコア (100 - 減点、下限0点)
        int finalScore = Math.max(0, 100 - totalDeduction);

        // ステータス判定: HEALTHY (>=80), WARNING (50-79), CRITICAL (<50)
        String healthStatus;
        if (finalScore >= 80) {
            healthStatus = "HEALTHY";
        } else if (finalScore >= 50) {
            healthStatus = "WARNING";
        } else {
            healthStatus = "CRITICAL";
        }

        Map<String, Object> breakdown = new HashMap<>();
        breakdown.put("openP0Count", openP0Count);
        breakdown.put("openP1Count", openP1Count);
        breakdown.put("openCriticalCount", openCriticalCount);
        breakdown.put("breachedRequests90d", breachedCount);
        breakdown.put("averageCsat", avgCsat != null ? avgCsat.doubleValue() : "N/A");
        breakdown.put("arOverdue", hasOverdue);
        breakdown.put("totalDeduction", totalDeduction);
        breakdown.put("deductions", deductionExplanations);

        return CustomerHealthScoreDto.builder()
                .customerId(customer.getId())
                .customerName(customer.getCompanyName())
                .healthScore(finalScore)
                .healthStatus(healthStatus)
                .openCriticalIssuesCount(openCriticalCount)
                .slaBreachCount30d(breachedCount)
                .avgCsatScore(avgCsat)
                .arOverdueFlag(hasOverdue)
                .missingInputs(missingInputs)
                .factorsExplanation(String.join("; ", deductionExplanations))
                .factorBreakdown(breakdown)
                .calculatedAt(now)
                .build();
    }
}
