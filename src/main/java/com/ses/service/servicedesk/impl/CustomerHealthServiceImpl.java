package com.ses.service.servicedesk.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
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
import com.ses.service.servicedesk.SnapshotExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerHealthServiceImpl implements CustomerHealthService {

    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private final CustomerMapper customerMapper;
    private final CustomerHealthSnapshotMapper snapshotMapper;
    private final ServiceRequestMapper serviceRequestMapper;
    private final ServiceSlaClockMapper slaClockMapper;
    private final CustomerCsatMapper csatMapper;
    private final CustomerQbrMapper qbrMapper;
    private final InvoiceMapper invoiceMapper;
    private final DataScopeService dataScopeService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<CustomerHealthScoreDto> listCustomerHealthSummaries(String healthStatus, String keyword) {
        LambdaQueryWrapper<Customer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Customer::getDeletedFlag, 0);

        if (dataScopeService.isScoped()) {
            Set<Long> allowedCustomerIds = dataScopeService.allowedCustomerIds();
            if (allowedCustomerIds == null || allowedCustomerIds.isEmpty()) {
                wrapper.eq(Customer::getId, -1L);
            } else {
                wrapper.in(Customer::getId, allowedCustomerIds);
            }
        }

        if (StringUtils.hasText(keyword)) {
            wrapper.like(Customer::getCompanyName, keyword);
        }

        List<Customer> customers = customerMapper.selectList(wrapper);
        if (customers.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> customerIds = customers.stream().map(Customer::getId).collect(Collectors.toSet());
        Map<Long, CustomerHealthScoreDto> scoreMap = getHealthMapForCustomers(customerIds);

        List<CustomerHealthScoreDto> results = new ArrayList<>();
        for (Customer c : customers) {
            CustomerHealthScoreDto dto = scoreMap.get(c.getId());
            if (dto != null) {
                if (healthStatus == null || healthStatus.isBlank() || healthStatus.equalsIgnoreCase(dto.getHealthStatus())) {
                    results.add(dto);
                }
            }
        }

        return results;
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerHealthScoreDto calculateCustomerHealth(Long customerId) {
        if (customerId == null) {
            throw BusinessException.of(400, "顧客IDは必須です");
        }
        if (dataScopeService.isScoped()) {
            dataScopeService.assertAllowedCustomer(customerId);
        }

        Customer customer = customerMapper.selectById(customerId);
        if (customer == null || Integer.valueOf(1).equals(customer.getDeletedFlag())) {
            throw BusinessException.of(404, "指定された顧客が見つかりません");
        }

        Map<Long, CustomerHealthScoreDto> map = getHealthMapForCustomers(Set.of(customerId));
        CustomerHealthScoreDto dto = map.get(customerId);
        if (dto == null) {
            throw BusinessException.of(404, "顧客ヘルススコアの算定に失敗しました");
        }
        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, CustomerHealthScoreDto> getHealthMapForCustomers(Set<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Customer> customers = customerMapper.selectBatchIds(customerIds);
        Map<Long, String> customerNameMap = customers.stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getCompanyName, (a, b) -> a));

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime thirtyDaysAgo = now.minusDays(30);
        LocalDateTime ninetyDaysAgo = now.minusDays(90);
        LocalDate sixtyDaysAgoDate = now.toLocalDate().minusDays(60);

        // 1. 問い合わせデータ一括取得
        List<ServiceRequest> allRequests = serviceRequestMapper.selectList(
                new LambdaQueryWrapper<ServiceRequest>()
                        .in(ServiceRequest::getCustomerId, customerIds)
        );
        Map<Long, List<ServiceRequest>> requestsByCustomer = allRequests.stream()
                .collect(Collectors.groupingBy(ServiceRequest::getCustomerId));

        // 2. SLAクロック一括取得 (直近30日)
        List<Long> allRequestIds = allRequests.stream().map(ServiceRequest::getId).toList();
        List<ServiceSlaClock> allClocks = allRequestIds.isEmpty() ? Collections.emptyList() :
                slaClockMapper.selectList(
                        new LambdaQueryWrapper<ServiceSlaClock>()
                                .in(ServiceSlaClock::getServiceRequestId, allRequestIds)
                                .ge(ServiceSlaClock::getCreatedAt, thirtyDaysAgo)
                );

        Set<Long> breachedRequestIds = new HashSet<>();
        for (ServiceSlaClock clk : allClocks) {
            if (Boolean.TRUE.equals(clk.getResponseBreached()) || Boolean.TRUE.equals(clk.getResolveBreached())) {
                breachedRequestIds.add(clk.getServiceRequestId());
            }
        }

        // 3. CSAT回答一括取得 (直近90日)
        List<CustomerCsat> allCsats = csatMapper.selectList(
                new LambdaQueryWrapper<CustomerCsat>()
                        .in(CustomerCsat::getCustomerId, customerIds)
                        .ge(CustomerCsat::getAnsweredAt, ninetyDaysAgo)
        );
        Map<Long, List<CustomerCsat>> csatByCustomer = allCsats.stream()
                .collect(Collectors.groupingBy(CustomerCsat::getCustomerId));

        // 4. 定例会(QBR)記録一括取得
        List<CustomerQbr> allQbrs = qbrMapper.selectList(
                new LambdaQueryWrapper<CustomerQbr>()
                        .in(CustomerQbr::getCustomerId, customerIds)
        );
        Map<Long, List<CustomerQbr>> allQbrByCustomer = allQbrs.stream()
                .collect(Collectors.groupingBy(CustomerQbr::getCustomerId));

        // 5. 売掛金延滞(Invoice)一括取得
        List<Invoice> allInvoices = invoiceMapper.selectList(
                new LambdaQueryWrapper<Invoice>()
                        .in(Invoice::getCustomerId, customerIds)
                        .eq(Invoice::getDeletedFlag, 0)
        );
        Map<Long, List<Invoice>> allInvoicesByCustomer = allInvoices.stream()
                .collect(Collectors.groupingBy(Invoice::getCustomerId));

        Map<Long, List<Invoice>> overdueInvoicesByCustomer = allInvoices.stream()
                .filter(inv -> {
                    if ("一部入金".equals(inv.getStatus()) || "発行済".equals(inv.getStatus()) || "送付済".equals(inv.getStatus())) {
                        LocalDate dueDate = inv.getDueDate();
                        return dueDate != null && dueDate.isBefore(now.toLocalDate());
                    }
                    return false;
                })
                .collect(Collectors.groupingBy(Invoice::getCustomerId));

        Map<Long, CustomerHealthScoreDto> resultMap = new HashMap<>();

        for (Long custId : customerIds) {
            String companyName = customerNameMap.getOrDefault(custId, "顧客#" + custId);

            List<ServiceRequest> custReqs = requestsByCustomer.getOrDefault(custId, Collections.emptyList());
            int openP0 = 0;
            int openP1 = 0;
            for (ServiceRequest r : custReqs) {
                if (!"RESOLVED".equals(r.getStatus()) && !"CLOSED".equals(r.getStatus())) {
                    if ("P0".equals(r.getPriority())) openP0++;
                    if ("P1".equals(r.getPriority())) openP1++;
                }
            }
            int openCritical = openP0 + openP1;

            int slaBreaches30d = 0;
            List<Long> custReqIds = custReqs.stream().map(ServiceRequest::getId).toList();
            for (Long rId : custReqIds) {
                if (breachedRequestIds.contains(rId)) {
                    slaBreaches30d++;
                }
            }

            List<CustomerCsat> custCsats = csatByCustomer.getOrDefault(custId, Collections.emptyList());
            BigDecimal avgCsat = null;
            if (!custCsats.isEmpty()) {
                double avg = custCsats.stream().mapToInt(CustomerCsat::getScore).average().orElse(0.0);
                avgCsat = BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP);
            }

            List<CustomerQbr> custAllQbrs = allQbrByCustomer.getOrDefault(custId, Collections.emptyList());
            boolean hasRecent60dQbr = custAllQbrs.stream()
                    .anyMatch(q -> q.getMeetingDate() != null && !q.getMeetingDate().isBefore(sixtyDaysAgoDate));

            boolean arOverdue = !overdueInvoicesByCustomer.getOrDefault(custId, Collections.emptyList()).isEmpty();
            boolean hasInvoices = !allInvoicesByCustomer.getOrDefault(custId, Collections.emptyList()).isEmpty();

            // 100点減点算定
            int deductions = 0;
            List<String> missingInputs = new ArrayList<>();
            Map<String, Object> breakdown = new HashMap<>();

            // 1. 未解決重大障害 (P0: -30点/件, P1: -15点/件)
            int p0Deduction = openP0 * 30;
            int p1Deduction = openP1 * 15;
            deductions += (p0Deduction + p1Deduction);
            breakdown.put("openP0Deduction", p0Deduction);
            breakdown.put("openP1Deduction", p1Deduction);

            // 2. 直近30日SLA超過（request単位1カウント: -10点/件、clock無しはmissing）
            int slaDeduction = slaBreaches30d * 10;
            if (custReqIds.isEmpty()) {
                missingInputs.add("SLA");
            }
            deductions += slaDeduction;
            breakdown.put("slaBreachDeduction", slaDeduction);

            // 3. CSAT平均 (<3.0は-15, 3.0-3.9は-5, >=4.0は0, 回答0はmissing)
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

            // 4. 売掛金延滞 (overdue>0が1件以上で-25点, 請求0件はmissing)
            int arDeduction = 0;
            if (arOverdue) {
                arDeduction = 25;
            } else if (!hasInvoices) {
                missingInputs.add("INVOICE");
            }
            deductions += arDeduction;
            breakdown.put("arDeduction", arDeduction);

            // 5. 定例会・QBR (60日QBRなしで-10点, 未登録新規はmissing)
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
        generateMonthlySnapshot(targetMonth, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void generateMonthlySnapshot(String targetMonth, String reason) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_管理者".equals(a.getAuthority()));
        if (!admin) {
            throw BusinessException.of(403, "顧客ヘルススナップショットは認証済み管理者または許可されたschedulerのみ実行可能です");
        }
        generateMonthlySnapshot(targetMonth, reason,
                SnapshotExecutionContext.admin(SecurityUtils.currentUsername(), SecurityUtils.currentUserId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void generateMonthlySnapshot(String targetMonth, String reason,
                                        SnapshotExecutionContext executionContext) {
        validateExecutionContext(executionContext);

        // targetMonth 解決 & フォーマット検証
        String effectiveMonth = targetMonth;
        if (effectiveMonth == null || effectiveMonth.isBlank()) {
            effectiveMonth = YearMonth.now(clock.getZone()).toString();
        }

        if (!MONTH_PATTERN.matcher(effectiveMonth).matches()) {
            throw BusinessException.of(400, "対象月の形式が不正です (YYYY-MM形式で指定してください)");
        }

        LocalDate snapshotDate;
        try {
            snapshotDate = LocalDate.parse(effectiveMonth + "-01");
        } catch (DateTimeParseException e) {
            throw BusinessException.of(400, "対象月の形式が不正です (YYYY-MM形式で指定してください)");
        }

        // 現行の算定器は現在時点のデータを読むため、過去月を現在値で上書きしない。
        // as-of 算定を導入するまでは、対象月を現在月だけに限定する。
        if (!YearMonth.now(clock.getZone()).equals(YearMonth.from(snapshotDate))) {
            throw BusinessException.of(400, "過去または未来の対象月はas-of算定が未提供のため指定できません");
        }

        log.info("顧客ヘルス月次スナップショット生成開始: month={}", effectiveMonth);

        // 実行者情報は検証済みコンテキストからのみ採用する。
        String actorType = executionContext.actorType();
        Long actorId = executionContext.actorId();
        String actorName = executionContext.actorName();

        List<Customer> allCustomers = customerMapper.selectList(
                new LambdaQueryWrapper<Customer>().eq(Customer::getDeletedFlag, 0)
        );
        if (allCustomers.isEmpty()) {
            return;
        }

        Set<Long> customerIds = allCustomers.stream().map(Customer::getId).collect(Collectors.toSet());
        Map<Long, CustomerHealthScoreDto> scoreMap = getHealthMapForCustomers(customerIds);

        LocalDateTime now = LocalDateTime.now(clock);

        for (CustomerHealthScoreDto dto : scoreMap.values()) {
            String missingJson = "[]";
            try {
                missingJson = objectMapper.writeValueAsString(dto.getMissingInputs());
            } catch (JsonProcessingException e) {
                log.warn("missing_inputs JSON変換失敗", e);
            }

            String computedHash = calculateSnapshotHash(
                    dto.getHealthScore(),
                    dto.getHealthStatus(),
                    dto.getOpenCriticalIssuesCount(),
                    dto.getSlaBreachCount30d(),
                    dto.getAvgCsatScore(),
                    dto.getArOverdueFlag(),
                    missingJson,
                    dto.getFactorsExplanation()
            );

            persistSnapshotRevision(dto, snapshotDate, computedHash, missingJson,
                    reason, actorType, actorId, actorName, now, effectiveMonth);
        }
        log.info("顧客ヘルス月次スナップショット生成完了: 件数={}", scoreMap.size());
    }

    private void validateExecutionContext(SnapshotExecutionContext context) {
        if (context == null) {
            throw BusinessException.of(403, "スナップショット実行主体が必要です");
        }
        if ("SYSTEM".equals(context.actorType())) {
            if (!SnapshotExecutionContext.SYSTEM_SCHEDULER.equals(context.source())
                    || context.actorId() != null || !"SYSTEM".equals(context.actorName())) {
                throw BusinessException.of(403, "SYSTEM実行は許可されたスケジューラからのみ可能です");
            }
            return;
        }
        if (!"INTERNAL_USER".equals(context.actorType())) {
            throw BusinessException.of(403, "スナップショット実行主体の種別が不正です");
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_管理者".equals(a.getAuthority()));
        if (!admin || !SnapshotExecutionContext.ADMIN_HTTP.equals(context.source())) {
            throw BusinessException.of(403, "管理者の明示的な実行主体が必要です");
        }
    }

    private void persistSnapshotRevision(CustomerHealthScoreDto dto, LocalDate snapshotDate,
                                         String computedHash, String missingJson, String reason,
                                         String actorType, Long actorId, String actorName,
                                         LocalDateTime now, String effectiveMonth) {
        for (int attempt = 0; attempt < 3; attempt++) {
            CustomerHealthSnapshot latest = snapshotMapper.selectLatestForUpdate(dto.getCustomerId(), snapshotDate);
            if (latest != null && computedHash.equals(latest.getSnapshotHash())) {
                log.debug("顧客ヘルススナップショット同一内容スキップ: customerId={}, month={}",
                        dto.getCustomerId(), effectiveMonth);
                return;
            }
            if (latest != null && !StringUtils.hasText(reason)) {
                throw BusinessException.of(400, "既存スナップショットを修正する場合は修正理由が必須です");
            }

            int version = latest == null ? 1 : latest.getVersionNo() + 1;
            CustomerHealthSnapshot snapshot = CustomerHealthSnapshot.builder()
                    .customerId(dto.getCustomerId())
                    .snapshotDate(snapshotDate)
                    .versionNo(version)
                    .totalScore(dto.getHealthScore())
                    .healthStatus(dto.getHealthStatus())
                    .openCriticalIssuesCount(dto.getOpenCriticalIssuesCount())
                    .slaBreachCount30d(dto.getSlaBreachCount30d())
                    .avgCsatScore(dto.getAvgCsatScore())
                    .arOverdueFlag(dto.getArOverdueFlag())
                    .missingInputsJson(missingJson)
                    .factorsExplanation(dto.getFactorsExplanation())
                    .snapshotHash(computedHash)
                    .revisionReason(StringUtils.hasText(reason) ? reason.trim() : "初回スナップショット生成")
                    .actorType(actorType)
                    .actorId(actorId)
                    .actorName(actorName)
                    .isCurrent(true)
                    .createdAt(now)
                    .build();
            try {
                snapshotMapper.insert(snapshot);
                log.info("顧客ヘルススナップショット追記: customerId={}, month={}, version={}",
                        dto.getCustomerId(), effectiveMonth, version);
                return;
            } catch (DuplicateKeyException duplicate) {
                // 別トランザクションが同じ版を先に挿入した場合は再読込する。
                if (attempt == 2) {
                    throw BusinessException.of(409, "顧客ヘルススナップショットの同時更新が競合しました");
                }
            }
        }
    }

    private String calculateSnapshotHash(Integer score, String status, Integer openIssues,
                                        Integer slaBreaches, BigDecimal avgCsat, Boolean arOverdue,
                                        String missingJson, String factors) {
        String raw = String.format("%s:%s:%s:%s:%s:%s:%s:%s",
                score, status, openIssues, slaBreaches,
                avgCsat != null ? avgCsat.toPlainString() : "null",
                arOverdue != null ? arOverdue : false,
                missingJson != null ? missingJson : "[]",
                factors != null ? factors : "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
