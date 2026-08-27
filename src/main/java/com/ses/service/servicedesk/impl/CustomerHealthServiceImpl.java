package com.ses.service.servicedesk.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.servicedesk.CustomerHealthScoreDto;
import com.ses.entity.Customer;
import com.ses.entity.CustomerCsat;
import com.ses.entity.CustomerHealthSnapshot;
import com.ses.entity.CustomerQbr;
import com.ses.entity.Invoice;
import com.ses.entity.ServiceRequest;
import com.ses.entity.ServiceSlaClock;
import com.ses.mapper.CustomerCsatMapper;
import com.ses.mapper.CustomerHealthSnapshotMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.CustomerQbrMapper;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 顧客ヘルススコア算定サービス (100点減点モデル & DataScope & N+1解消: WIP-3, WIP-4, design.md §3 完全整合)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerHealthServiceImpl implements CustomerHealthService {

    private final CustomerMapper customerMapper;
    private final ServiceRequestMapper serviceRequestMapper;
    private final ServiceSlaClockMapper slaClockMapper;
    private final CustomerCsatMapper csatMapper;
    private final CustomerQbrMapper qbrMapper;
    private final ObjectProvider<InvoiceMapper> invoiceMapperProvider;
    private final CustomerHealthSnapshotMapper snapshotMapper;
    private final DataScopeService dataScopeService;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional(readOnly = true)
    public CustomerHealthScoreDto calculateCustomerHealth(Long customerId) {
        if (dataScopeService.isScoped()) {
            dataScopeService.assertAllowedCustomer(customerId);
        }

        Customer customer = customerMapper.selectById(customerId);
        if (customer == null) {
            throw BusinessException.of(404, "指定された顧客が見つかりません");
        }

        Map<Long, CustomerHealthScoreDto> scoreMap = getHealthMapForCustomers(Set.of(customerId));
        return scoreMap.get(customerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerHealthScoreDto> listCustomerHealthSummaries(String healthStatus, String keyword) {
        LambdaQueryWrapper<Customer> wrapper = new LambdaQueryWrapper<Customer>().orderByAsc(Customer::getId);
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Customer::getCompanyName, keyword);
        }

        List<Customer> customers = customerMapper.selectList(wrapper);
        if (customers.isEmpty()) {
            return Collections.emptyList();
        }

        // DataScope による顧客絞り込み（WIP-4 回帰防止）
        if (dataScopeService.isScoped()) {
            customers = customers.stream()
                    .filter(c -> {
                        try {
                            dataScopeService.assertAllowedCustomer(c.getId());
                            return true;
                        } catch (BusinessException e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());
        }

        if (customers.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> customerIds = customers.stream().map(Customer::getId).collect(Collectors.toSet());
        Map<Long, CustomerHealthScoreDto> scoreMap = getHealthMapForCustomers(customerIds);

        return customers.stream()
                .map(c -> scoreMap.get(c.getId()))
                .filter(dto -> dto != null && (!StringUtils.hasText(healthStatus) || healthStatus.equalsIgnoreCase(dto.getHealthStatus())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, CustomerHealthScoreDto> getHealthMapForCustomers(Set<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return Collections.emptyMap();
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDateTime thirtyDaysAgo = now.minusDays(30);
        LocalDate sixtyDaysAgoDate = today.minusDays(60);
        LocalDateTime oneEightyDaysAgo = now.minusDays(180);

        // 1. 全指定顧客の未解決リクエスト一括取得
        List<ServiceRequest> openRequests = serviceRequestMapper.selectList(
                new LambdaQueryWrapper<ServiceRequest>()
                        .in(ServiceRequest::getCustomerId, customerIds)
                        .in(ServiceRequest::getStatus, List.of("RECEIVED", "IN_PROGRESS", "WAITING_CUSTOMER", "REOPENED"))
        );
        Map<Long, List<ServiceRequest>> openReqMap = openRequests.stream()
                .collect(Collectors.groupingBy(ServiceRequest::getCustomerId));

        // 2. 直近30日のリクエストおよびSLA違反（リクエスト単位で1カウント）
        List<ServiceRequest> recent30dRequests = serviceRequestMapper.selectList(
                new LambdaQueryWrapper<ServiceRequest>()
                        .in(ServiceRequest::getCustomerId, customerIds)
                        .ge(ServiceRequest::getCreatedAt, thirtyDaysAgo)
        );
        Map<Long, List<Long>> reqIdsByCustomer = recent30dRequests.stream()
                .collect(Collectors.groupingBy(ServiceRequest::getCustomerId,
                        Collectors.mapping(ServiceRequest::getId, Collectors.toList())));

        Set<Long> allRecentReqIds = recent30dRequests.stream().map(ServiceRequest::getId).collect(Collectors.toSet());
        Set<Long> breachedRequestIds = new HashSet<>();
        if (!allRecentReqIds.isEmpty()) {
            List<ServiceSlaClock> breachedClocks = slaClockMapper.selectList(
                    new LambdaQueryWrapper<ServiceSlaClock>()
                            .in(ServiceSlaClock::getServiceRequestId, allRecentReqIds)
                            .and(w -> w.eq(ServiceSlaClock::getResponseBreached, true)
                                    .or().eq(ServiceSlaClock::getResolveBreached, true))
            );
            for (ServiceSlaClock clk : breachedClocks) {
                breachedRequestIds.add(clk.getServiceRequestId());
            }
        }

        // 3. 直近180日のCSAT一括取得
        List<CustomerCsat> csatList = csatMapper.selectList(
                new LambdaQueryWrapper<CustomerCsat>()
                        .in(CustomerCsat::getCustomerId, customerIds)
                        .ge(CustomerCsat::getAnsweredAt, oneEightyDaysAgo)
        );
        Map<Long, List<CustomerCsat>> csatByCustomer = csatList.stream()
                .collect(Collectors.groupingBy(CustomerCsat::getCustomerId));

        // 4. 直近60日のQBRおよび全期間QBR存在チェック（N+1完全解消: 一括取得）
        List<CustomerQbr> allQbrList = qbrMapper.selectList(
                new LambdaQueryWrapper<CustomerQbr>()
                        .in(CustomerQbr::getCustomerId, customerIds)
        );
        Map<Long, List<CustomerQbr>> allQbrByCustomer = allQbrList.stream()
                .collect(Collectors.groupingBy(CustomerQbr::getCustomerId));

        // 5. 請求書 (AR overdue) 一括取得（正本status: '送付済', '一部入金', 'OVERDUE', 'ISSUED'）
        InvoiceMapper invoiceMapper = invoiceMapperProvider.getIfAvailable();
        Map<Long, List<Invoice>> overdueInvoicesByCustomer = Collections.emptyMap();
        Map<Long, List<Invoice>> allInvoicesByCustomer = Collections.emptyMap();
        if (invoiceMapper != null) {
            List<Invoice> invoices = invoiceMapper.selectList(
                    new LambdaQueryWrapper<Invoice>()
                            .in(Invoice::getCustomerId, customerIds)
            );
            allInvoicesByCustomer = invoices.stream().collect(Collectors.groupingBy(Invoice::getCustomerId));
            overdueInvoicesByCustomer = invoices.stream()
                    .filter(inv -> {
                        String st = inv.getStatus();
                        boolean isUnpaidStatus = "送付済".equals(st) || "一部入金".equals(st)
                                || "OVERDUE".equalsIgnoreCase(st) || "ISSUED".equalsIgnoreCase(st);
                        return isUnpaidStatus && inv.getDueDate() != null && inv.getDueDate().isBefore(today) && inv.getPaidDate() == null;
                    })
                    .collect(Collectors.groupingBy(Invoice::getCustomerId));
        }

        // 顧客ごとのスコア算出
        Map<Long, CustomerHealthScoreDto> resultMap = new HashMap<>();
        List<Customer> customerList = customerMapper.selectBatchIds(customerIds);
        Map<Long, Customer> customerMap = customerList.stream().collect(Collectors.toMap(Customer::getId, c -> c));

        for (Long custId : customerIds) {
            Customer cust = customerMap.get(custId);
            String companyName = cust != null ? cust.getCompanyName() : "不明顧客";

            // 未解決 P0 / P1
            List<ServiceRequest> custOpenReqs = openReqMap.getOrDefault(custId, Collections.emptyList());
            int openP0 = (int) custOpenReqs.stream().filter(r -> "P0".equalsIgnoreCase(r.getPriority())).count();
            int openP1 = (int) custOpenReqs.stream().filter(r -> "P1".equalsIgnoreCase(r.getPriority())).count();
            int openCritical = openP0 + openP1;

            // 直近30日SLA違反件数（リクエスト単位で1カウント）
            List<Long> custReqIds = reqIdsByCustomer.getOrDefault(custId, Collections.emptyList());
            int slaBreaches30d = 0;
            for (Long rId : custReqIds) {
                if (breachedRequestIds.contains(rId)) {
                    slaBreaches30d++;
                }
            }

            // 直近180日CSAT平均
            List<CustomerCsat> custCsats = csatByCustomer.getOrDefault(custId, Collections.emptyList());
            BigDecimal avgCsat = null;
            if (!custCsats.isEmpty()) {
                double avg = custCsats.stream().mapToInt(CustomerCsat::getScore).average().orElse(0.0);
                avgCsat = BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP);
            }

            // 直近60日QBR
            List<CustomerQbr> custAllQbrs = allQbrByCustomer.getOrDefault(custId, Collections.emptyList());
            boolean hasRecent60dQbr = custAllQbrs.stream()
                    .anyMatch(q -> q.getMeetingDate() != null && !q.getMeetingDate().isBefore(sixtyDaysAgoDate));

            // AR延滞
            boolean arOverdue = !overdueInvoicesByCustomer.getOrDefault(custId, Collections.emptyList()).isEmpty();
            boolean hasInvoices = !allInvoicesByCustomer.getOrDefault(custId, Collections.emptyList()).isEmpty();

            // 100点減点算定 (design.md §3 完全準拠)
            int deductions = 0;
            List<String> missingInputs = new ArrayList<>();
            Map<String, Object> breakdown = new HashMap<>();

            // 1. 未解決重大障害 (P0: -30点/件, P1: -15点/件)
            int p0Deduction = openP0 * 30;
            int p1Deduction = openP1 * 15;
            deductions += (p0Deduction + p1Deduction);
            breakdown.put("openP0Deduction", p0Deduction);
            breakdown.put("openP1Deduction", p1Deduction);

            // 2. 直近30日SLA超過（request単位1カウント: -10点/件）
            int slaDeduction = slaBreaches30d * 10;
            deductions += slaDeduction;
            breakdown.put("slaBreachDeduction", slaDeduction);

            // 3. CSAT平均 (design.md: <3.0は-15, 3.0-3.9は-5, >=4.0は0, 回答0はmissing)
            int csatDeduction = 0;
            if (avgCsat != null) {
                double scoreVal = avgCsat.doubleValue();
                if (scoreVal < 3.0) {
                    csatDeduction = 15;
                } else if (scoreVal < 4.0) {
                    csatDeduction = 5;
                }
            } else {
                missingInputs.add("CSAT");
            }
            deductions += csatDeduction;
            breakdown.put("csatDeduction", csatDeduction);

            // 4. 売掛金延滞 (design.md: 既存Invoice overdue>0が1件以上で-25点, 請求0件はmissing)
            int arDeduction = 0;
            if (arOverdue) {
                arDeduction = 25;
            } else if (!hasInvoices) {
                missingInputs.add("INVOICE");
            }
            deductions += arDeduction;
            breakdown.put("arDeduction", arDeduction);

            // 5. 定例会・QBR (design.md: 60日QBRなしで-10点, 未登録新規はmissing)
            int qbrDeduction = 0;
            if (!hasRecent60dQbr) {
                if (!custAllQbrs.isEmpty()) {
                    qbrDeduction = 10;
                } else {
                    missingInputs.add("QBR");
                }
            }
            deductions += qbrDeduction;
            breakdown.put("qbrDeduction", qbrDeduction);

            int finalScore = Math.max(0, 100 - deductions);
            String healthStatus = finalScore >= 80 ? "HEALTHY" : (finalScore >= 50 ? "WARNING" : "CRITICAL");

            List<String> explanations = new ArrayList<>();
            if (openCritical > 0) explanations.add("未解決重大障害: " + openCritical + "件 (-" + (p0Deduction + p1Deduction) + "点)");
            if (slaBreaches30d > 0) explanations.add("直近30日SLA違反: " + slaBreaches30d + "件 (-" + slaDeduction + "点)");
            if (csatDeduction > 0) explanations.add("CSAT評価低迷: " + avgCsat + " (-" + csatDeduction + "点)");
            if (arDeduction > 0) explanations.add("売掛金延滞発生中 (-25点)");
            if (qbrDeduction > 0) explanations.add("直近60日定例会(QBR)未開催 (-10点)");
            if (explanations.isEmpty()) explanations.add("特記事項なし（健全稼動）");

            resultMap.put(custId, CustomerHealthScoreDto.builder()
                    .customerId(custId)
                    .customerName(companyName)
                    .healthScore(finalScore)
                    .healthStatus(healthStatus)
                    .openCriticalIssuesCount(openCritical)
                    .slaBreachCount30d(slaBreaches30d)
                    .avgCsatScore(avgCsat)
                    .arOverdueFlag(arOverdue)
                    .missingInputs(missingInputs)
                    .factorsExplanation(String.join(" / ", explanations))
                    .factorBreakdown(breakdown)
                    .build());
        }

        return resultMap;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void generateMonthlySnapshot(String targetMonth) {
        log.info("顧客ヘルス月次スナップショット生成開始: month={}", targetMonth);
        LocalDate snapshotDate = LocalDate.parse(targetMonth + "-01");

        List<Customer> allCustomers = customerMapper.selectList(new LambdaQueryWrapper<Customer>());
        if (allCustomers.isEmpty()) {
            return;
        }

        Set<Long> customerIds = allCustomers.stream().map(Customer::getId).collect(Collectors.toSet());
        Map<Long, CustomerHealthScoreDto> scoreMap = getHealthMapForCustomers(customerIds);

        for (CustomerHealthScoreDto dto : scoreMap.values()) {
            CustomerHealthSnapshot existing = snapshotMapper.selectOne(
                    new LambdaQueryWrapper<CustomerHealthSnapshot>()
                            .eq(CustomerHealthSnapshot::getCustomerId, dto.getCustomerId())
                            .eq(CustomerHealthSnapshot::getSnapshotDate, snapshotDate)
            );

            String missingJson = "[]";
            try {
                missingJson = objectMapper.writeValueAsString(dto.getMissingInputs());
            } catch (JsonProcessingException e) {
                log.warn("missing_inputs JSON変換失敗", e);
            }

            if (existing != null) {
                // 非破壊更新: 既存行を上書き
                existing.setTotalScore(dto.getHealthScore());
                existing.setHealthStatus(dto.getHealthStatus());
                existing.setOpenCriticalIssuesCount(dto.getOpenCriticalIssuesCount());
                existing.setSlaBreachCount30d(dto.getSlaBreachCount30d());
                existing.setAvgCsatScore(dto.getAvgCsatScore());
                existing.setArOverdueFlag(dto.getArOverdueFlag());
                existing.setMissingInputsJson(missingJson);
                existing.setFactorsExplanation(dto.getFactorsExplanation());
                snapshotMapper.updateById(existing);
            } else {
                CustomerHealthSnapshot snapshot = CustomerHealthSnapshot.builder()
                        .customerId(dto.getCustomerId())
                        .snapshotDate(snapshotDate)
                        .totalScore(dto.getHealthScore())
                        .healthStatus(dto.getHealthStatus())
                        .openCriticalIssuesCount(dto.getOpenCriticalIssuesCount())
                        .slaBreachCount30d(dto.getSlaBreachCount30d())
                        .avgCsatScore(dto.getAvgCsatScore())
                        .arOverdueFlag(dto.getArOverdueFlag())
                        .missingInputsJson(missingJson)
                        .factorsExplanation(dto.getFactorsExplanation())
                        .createdAt(LocalDateTime.now(clock))
                        .build();
                snapshotMapper.insert(snapshot);
            }
        }
        log.info("顧客ヘルス月次スナップショット生成完了: 件数={}", scoreMap.size());
    }
}
