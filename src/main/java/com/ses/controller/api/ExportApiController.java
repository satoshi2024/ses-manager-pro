package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.export.ContractExportDto;
import com.ses.dto.export.MonthlyRevenueDto;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.EngineerService;
import com.ses.service.RetentionRiskService;
import com.ses.service.billing.MonthlyRevenueCalcService;
import com.ses.service.export.ExcelExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;

import java.net.URLEncoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Excel帳票出力APIコントローラー。
 * POI依存を1箇所に集約するため、既存の /api/engineers, /api/contracts, /api/dashboard
 * prefix配下にエンドポイントを配置し、既存のメニュー権限(engineer/contract/dashboard)を
 * そのまま引き継ぐ。
 */
@RestController
@RequiredArgsConstructor
public class ExportApiController {

    private static final DateTimeFormatter FILENAME_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final MediaType XLSX_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final EngineerService engineerService;
    private final ContractMapper contractMapper;
    private final ProjectMapper projectMapper;
    private final CustomerMapper customerMapper;
    private final WorkRecordMapper workRecordMapper;
    private final ExcelExportService excelExportService;
    private final com.ses.service.security.AuthorizationService authorizationService;
    private final MonthlyRevenueCalcService monthlyRevenueCalcService;
    private final com.ses.service.security.DataScopeService dataScopeService;
    private final com.ses.service.security.OrganizationScopeService organizationScopeService;
    private final RetentionRiskService retentionRiskService;

    @Value("${app.export.batch-size:500}")
    private int configuredBatchSize;

    @Value("${app.export.max-rows:50000}")
    private int configuredMaxRows;

    @Value("${app.export.max-scan-rows:100000}")
    private int configuredMaxScanRows;

    @Value("${app.export.max-concurrent:2}")
    private int configuredMaxConcurrent;

    @PostConstruct
    void initializeExportLimits() {
        configuredBatchSize = clamp(configuredBatchSize, 100, 2000, 500);
        configuredMaxRows = configuredMaxRows > 0 ? configuredMaxRows : 50000;
        configuredMaxScanRows = configuredMaxScanRows > 0 ? configuredMaxScanRows : 100000;
        configuredMaxConcurrent = configuredMaxConcurrent > 0 ? configuredMaxConcurrent : 2;
        com.ses.service.export.ExportConcurrencyLimiter.configure(configuredMaxConcurrent);
    }

    /**
     * 要員一覧Excel出力。EngineerApiController.page と同じ検索条件を受け付ける(ページングなし)。
     */
    @GetMapping("/api/engineers/export")
    public void exportEngineers(
            @RequestParam(required = false) String fullName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String employmentType,
            @RequestParam(required = false) List<Long> skillIds,
            @RequestParam(required = false) Long salesUserId,
            @RequestParam(required = false) String riskLevel,
            HttpServletResponse response) throws IOException {

        com.ses.service.export.ExportConcurrencyLimiter.acquire();
        try {
            exportEngineersInternal(fullName, status, employmentType, skillIds, salesUserId, riskLevel, response);
        } finally {
            com.ses.service.export.ExportConcurrencyLimiter.release();
        }
    }

    private void exportEngineersInternal(String fullName, String status, String employmentType,
                                         List<Long> skillIds, Long salesUserId, String riskLevel,
                                         HttpServletResponse response) throws IOException {

        LambdaQueryWrapper<Engineer> queryWrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(fullName)) {
            queryWrapper.like(Engineer::getFullName, fullName);
        }
        if (StringUtils.hasText(status)) {
            queryWrapper.eq(Engineer::getStatus, status);
        }
        if (StringUtils.hasText(employmentType)) {
            queryWrapper.eq(Engineer::getEmploymentType, employmentType);
        }
        if (skillIds != null && !skillIds.isEmpty()) {
            for (Long skillId : skillIds) {
                if (skillId == null) {
                    continue;
                }
                queryWrapper.apply("EXISTS (SELECT 1 FROM t_engineer_skill es "
                                + "WHERE es.engineer_id = t_engineer.id AND es.skill_id = {0})",
                        skillId);
            }
        }
        if (salesUserId != null) {
            queryWrapper.apply("EXISTS (SELECT 1 FROM t_engineer_sales es "
                    + "WHERE es.engineer_id = t_engineer.id AND es.sales_user_id = {0} "
                    + "AND es.released_at IS NULL AND es.deleted_flag = 0)", salesUserId);
        }
        // 画面と同じ組織scope∩DataScopeの母集団をSQLへ注入する。
        java.util.Set<Long> allowed = effectiveEngineerIds();
        if (allowed != null) {
            if (allowed.isEmpty()) {
                prepareFileResponse(response, "要員一覧", "xlsx");
                excelExportService.streamEngineers(List.<Engineer>of(), response.getOutputStream());
                return;
            }
            queryWrapper.in(Engineer::getId, allowed);
        }
        queryWrapper.orderByDesc(Engineer::getId);

        boolean highRiskOnly = "high".equalsIgnoreCase(riskLevel);
        if (highRiskOnly) {
            assertWithinMaxRows(countHighRiskEngineers(queryWrapper));
            prepareFileResponse(response, "要員一覧", "xlsx");
            excelExportService.streamEngineers(highRiskBatches(queryWrapper), response.getOutputStream());
            return;
        }
        assertWithinMaxRows(engineerService.count(queryWrapper));
        prepareFileResponse(response, "要員一覧", "xlsx");
        excelExportService.streamEngineers(new BatchIterable<>(lastId -> {
            LambdaQueryWrapper<Engineer> batchQuery = queryWrapper.clone();
            if (lastId != null) {
                batchQuery.lt(Engineer::getId, lastId);
            }
            batchQuery.orderByDesc(Engineer::getId).last("LIMIT " + configuredBatchSize);
            return engineerService.list(batchQuery);
        }, rows -> highRiskOnly ? filterHighRisk(rows) : rows, Engineer::getId, configuredBatchSize), response.getOutputStream());
    }

    /**
     * 契約一覧Excel出力。ContractApiController.page と同じ検索条件を受け付ける。
     * 旧パラメータ(customerName/keyword)も互換のため継続して受け付ける。
     */
    @GetMapping("/api/contracts/export")
    public void exportContracts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long salesUserId,
            @RequestParam(required = false) String contractNo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDateTo,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String keyword,
            HttpServletResponse response) throws IOException {

        com.ses.service.export.ExportConcurrencyLimiter.acquire();
        try {
            exportContractsInternal(status, customerId, salesUserId, contractNo, endDateFrom, endDateTo,
                    customerName, keyword, response);
        } finally {
            com.ses.service.export.ExportConcurrencyLimiter.release();
        }
    }

    private void exportContractsInternal(String status, Long customerId, Long salesUserId, String contractNo,
                                         LocalDate endDateFrom, LocalDate endDateTo, String customerName,
                                         String keyword, HttpServletResponse response) throws IOException {

        QueryWrapper<Contract> queryWrapper = new QueryWrapper<>();
        if (StringUtils.hasText(status)) {
            queryWrapper.eq("status", status);
        }
        if (customerId != null) {
            queryWrapper.eq("customer_id", customerId);
        }
        if (salesUserId != null) {
            queryWrapper.eq("sales_user_id", salesUserId);
        }
        if (StringUtils.hasText(contractNo)) {
            queryWrapper.like("contract_no", contractNo);
        }
        if (endDateFrom != null) {
            queryWrapper.ge("end_date", endDateFrom);
        }
        if (endDateTo != null) {
            queryWrapper.le("end_date", endDateTo);
        }
        if (StringUtils.hasText(customerName)) {
            queryWrapper.apply("EXISTS (SELECT 1 FROM m_customer cu "
                    + "WHERE cu.id = customer_id AND cu.deleted_flag = 0 AND cu.company_name LIKE {0})",
                    "%" + customerName + "%");
        }
        if (StringUtils.hasText(keyword)) {
            String keywordPattern = "%" + keyword + "%";
            queryWrapper.and(w -> w.apply("EXISTS (SELECT 1 FROM t_engineer e "
                            + "WHERE e.id = engineer_id AND e.deleted_flag = 0 AND e.full_name LIKE {0})", keywordPattern)
                    .or()
                    .apply("EXISTS (SELECT 1 FROM t_project p "
                            + "WHERE p.id = project_id AND p.deleted_flag = 0 AND p.project_name LIKE {0})", keywordPattern));
        }
        // 画面と同じ組織scope∩DataScopeの母集団をSQLへ注入する。
        java.util.Set<Long> allowed = effectiveContractIds();
        if (allowed != null) {
            if (allowed.isEmpty()) {
                prepareFileResponse(response, "契約一覧", "xlsx");
                excelExportService.streamContracts(List.<ContractExportDto>of(), response.getOutputStream());
                return;
            }
            queryWrapper.in("id", allowed);
        }
        queryWrapper.orderByDesc("id");

        boolean canViewCost = authorizationService.isAllowed(
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication(),
                "contract.cost.view");
        assertWithinMaxRows(contractMapper.selectCount(queryWrapper));
        prepareFileResponse(response, "契約一覧", "xlsx");
        excelExportService.streamContracts(new BatchIterable<>(lastId -> {
            QueryWrapper<Contract> batchQuery = queryWrapper.clone();
            if (lastId != null) {
                batchQuery.lt("id", lastId);
            }
            batchQuery.orderByDesc("id").last("LIMIT " + configuredBatchSize);
            return contractMapper.selectList(batchQuery);
        }, rows -> toContractExportDtos(rows, canViewCost), Contract::getId, configuredBatchSize), response.getOutputStream());
    }

    /**
     * 月次売上レポートExcel出力。指定年度(4月始まり12ヶ月)の売上・粗利・実績/見込み区分を出力する。
     */
    @GetMapping("/api/dashboard/revenue-export")
    public ResponseEntity<byte[]> exportMonthlyRevenue(@RequestParam int fiscalYear) {
        List<MonthlyRevenueDto> rows = buildMonthlyRevenueRows(fiscalYear);
        byte[] bytes = excelExportService.exportMonthlyRevenue(fiscalYear, rows);
        return buildFileResponse(bytes, "月次売上レポート");
    }

    /**
     * 契約エンティティを要員名・案件名・顧客名解決済みのDTOに変換する(N+1回避のためID一括取得)。
     */
    private List<ContractExportDto> toContractExportDtos(List<Contract> contracts, boolean canViewCost) {
        Set<Long> engineerIds = contracts.stream()
                .map(Contract::getEngineerId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<Long> projectIds = contracts.stream()
                .map(Contract::getProjectId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<Long> customerIds = contracts.stream()
                .map(Contract::getCustomerId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());

        // Map.of() は null キーの getOrDefault で NPE になるため、engineerId/projectId/customerId が
        // null の契約(未割当)にも対応できるよう常に HashMap(mutable, null-key許容)を使う。
        Map<Long, String> engineerNames = engineerIds.isEmpty() ? new HashMap<>() :
                engineerService.listByIds(engineerIds).stream()
                        .collect(Collectors.toMap(Engineer::getId, Engineer::getFullName, (a, b) -> a, HashMap::new));
        Map<Long, String> projectNames = projectIds.isEmpty() ? new HashMap<>() :
                projectMapper.selectBatchIds(projectIds).stream()
                        .collect(Collectors.toMap(Project::getId, Project::getProjectName, (a, b) -> a, HashMap::new));
        Map<Long, String> customerNames = customerIds.isEmpty() ? new HashMap<>() :
                customerMapper.selectBatchIds(customerIds).stream()
                        .collect(Collectors.toMap(Customer::getId, Customer::getCompanyName, (a, b) -> a, HashMap::new));

        List<ContractExportDto> result = new ArrayList<>();
        for (Contract c : contracts) {
            result.add(ContractExportDto.builder()
                    .contractNo(c.getContractNo())
                    .engineerName(engineerNames.getOrDefault(c.getEngineerId(), "不明"))
                    .projectName(projectNames.getOrDefault(c.getProjectId(), "不明"))
                    .customerName(customerNames.getOrDefault(c.getCustomerId(), "不明"))
                    .contractType(c.getContractType())
                    .startDate(c.getStartDate())
                    .endDate(c.getEndDate())
                    .sellingPrice(c.getSellingPrice())
                    .costPrice(canViewCost ? c.getCostPrice() : null)
                    .status(c.getStatus())
                    .build());
        }
        return result;
    }

    private long countHighRiskEngineers(LambdaQueryWrapper<Engineer> queryWrapper) {
        long candidateCount = engineerService.count(queryWrapper);
        if (candidateCount > configuredMaxScanRows) {
            throw BusinessException.of("error.export.maxScanRows", configuredMaxScanRows);
        }

        long count = 0;
        for (List<Engineer> rows : highRiskBatches(queryWrapper)) {
            count += rows.size();
            if (count > configuredMaxRows) return count;
        }
        return count;
    }

    private Iterable<List<Engineer>> highRiskBatches(LambdaQueryWrapper<Engineer> queryWrapper) {
        long[] scannedRows = {0L};
        return new BatchIterable<>(lastId -> {
            long remaining = configuredMaxScanRows - scannedRows[0];
            if (remaining <= 0) return List.of();
            LambdaQueryWrapper<Engineer> batchQuery = queryWrapper.clone();
            if (lastId != null) batchQuery.lt(Engineer::getId, lastId);
            int limit = (int) Math.min(configuredBatchSize, remaining);
            batchQuery.orderByDesc(Engineer::getId).last("LIMIT " + limit);
            List<Engineer> fetched = engineerService.list(batchQuery);
            if (fetched.size() > remaining) {
                fetched = fetched.subList(0, (int) remaining);
            }
            scannedRows[0] += fetched.size();
            return fetched;
        }, this::filterHighRisk, Engineer::getId, configuredBatchSize);
    }

    private List<Engineer> filterHighRisk(List<Engineer> rows) {
        Set<Long> highRiskIds = retentionRiskService.scoreBatchFor(rows).entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().isHighRisk())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        return rows.stream().filter(engineer -> highRiskIds.contains(engineer.getId())).toList();
    }

    /**
     * 指定年度(4月始まり12ヶ月)の月次売上・粗利・実績/見込み区分を集計する。
     * 集計ロジックは共通口径サービス({@link MonthlyRevenueCalcService})へ委譲し、Dashboard と数値を一致させる。
     * 確定実績は対象月を一括ロードし月別 grouping する(旧実装の月ごと12クエリを廃止)。
     */
    private List<MonthlyRevenueDto> buildMonthlyRevenueRows(int fiscalYear) {
        List<YearMonth> targetMonths = buildFiscalYearMonths(fiscalYear);
        List<MonthlyRevenueDto> rows = new ArrayList<>();
        // 管理者の全社経路は従来どおり一括取得し、組織scope経路だけ月初asOfごとにSQLを発行する。
        if (organizationScopeService.hasFullAccess() && !dataScopeService.isScoped()) {
            List<Contract> allContracts = contractMapper.selectList(new QueryWrapper<>());
            List<String> monthStrs = targetMonths.stream().map(YearMonth::toString).collect(Collectors.toList());
            Map<String, Map<Long, WorkRecord>> confirmedByMonth = workRecordMapper.selectList(
                            new QueryWrapper<WorkRecord>().in("work_month", monthStrs).eq("status", "確定"))
                    .stream().collect(Collectors.groupingBy(WorkRecord::getWorkMonth,
                            Collectors.toMap(WorkRecord::getContractId, w -> w, (w1, w2) -> w1)));
            for (YearMonth ym : targetMonths) {
                rows.add(toMonthlyRevenueRow(ym, allContracts,
                        confirmedByMonth.getOrDefault(ym.toString(), java.util.Collections.emptyMap())));
            }
            return rows;
        }

        for (YearMonth ym : targetMonths) {
            java.util.Set<Long> allowedContractIds = effectiveContractIds(ym.atDay(1));
            if (allowedContractIds != null && allowedContractIds.isEmpty()) {
                rows.add(toMonthlyRevenueRow(ym, List.of(), java.util.Collections.emptyMap()));
                continue;
            }
            QueryWrapper<Contract> contractQuery = new QueryWrapper<>();
            if (allowedContractIds != null) contractQuery.in("id", allowedContractIds);
            List<Contract> monthContracts = contractMapper.selectList(contractQuery);
            QueryWrapper<WorkRecord> workRecordQuery = new QueryWrapper<WorkRecord>()
                    .eq("work_month", ym.toString()).eq("status", "確定");
            if (allowedContractIds != null) workRecordQuery.in("contract_id", allowedContractIds);
            Map<Long, WorkRecord> confirmed = workRecordMapper.selectList(workRecordQuery).stream()
                    .collect(Collectors.toMap(WorkRecord::getContractId, w -> w, (w1, w2) -> w1));
            rows.add(toMonthlyRevenueRow(ym, monthContracts, confirmed));
        }
        return rows;
    }

    private MonthlyRevenueDto toMonthlyRevenueRow(YearMonth ym, List<Contract> contracts,
                                                   Map<Long, WorkRecord> confirmed) {
        MonthlyRevenueCalcService.MonthlyAmount amount = monthlyRevenueCalcService.calc(ym, contracts, confirmed);
        return MonthlyRevenueDto.builder()
                .label(ym.getYear() + "年" + ym.getMonthValue() + "月")
                .sales(amount.getSales())
                .profit(amount.getProfit())
                .isActual(amount.isHasActual())
                .build();
    }

    private List<YearMonth> buildFiscalYearMonths(int fiscalYear) {
        List<YearMonth> months = new ArrayList<>();
        YearMonth start = YearMonth.of(fiscalYear, 4);
        for (int i = 0; i < 12; i++) {
            months.add(start.plusMonths(i));
        }
        return months;
    }

    private ResponseEntity<byte[]> buildFileResponse(byte[] bytes, String prefix) {
        String filename = prefix + "_" + LocalDate.now().format(FILENAME_DATE_FORMAT) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .body(bytes);
    }

    private java.util.Set<Long> effectiveEngineerIds() {
        java.util.Set<Long> dataIds = dataScopeService.isScoped()
                ? dataScopeService.allowedEngineerIds() : null;
        if (organizationScopeService.hasFullAccess()) {
            return dataIds == null ? null : new java.util.HashSet<>(dataIds);
        }
        return organizationScopeService.intersectWithDataScope(
                organizationScopeService.allowedEngineerIds(java.time.LocalDate.now()), dataIds);
    }

    private java.util.Set<Long> effectiveContractIds() {
        return effectiveContractIds(java.time.LocalDate.now());
    }

    private java.util.Set<Long> effectiveContractIds(java.time.LocalDate asOf) {
        java.util.Set<Long> dataIds = dataScopeService.isScoped()
                ? dataScopeService.allowedContractIds() : null;
        if (organizationScopeService.hasFullAccess()) {
            return dataIds == null ? null : new java.util.HashSet<>(dataIds);
        }
        return organizationScopeService.intersectWithDataScope(
                organizationScopeService.allowedContractIds(asOf), dataIds);
    }

    private void prepareFileResponse(HttpServletResponse response, String prefix, String extension) {
        String filename = prefix + "_" + LocalDate.now().format(FILENAME_DATE_FORMAT) + "." + extension;
        response.setContentType(XLSX_MEDIA_TYPE.toString());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8));
    }

    private void assertWithinMaxRows(long count) {
        if (count > configuredMaxRows) {
            throw BusinessException.of("error.export.maxRows", configuredMaxRows);
        }
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value <= 0 ? fallback : Math.min(max, Math.max(min, value));
    }

    /** キーセットページングを1バッチずつ遅延取得するIterable。全件を保持しない。 */
    private static final class BatchIterable<S, T> implements Iterable<List<T>> {
        private final Function<Long, List<S>> fetcher;
        private final Function<List<S>, List<T>> mapper;
        private final Function<S, Long> idExtractor;
        private final int batchSize;

        private BatchIterable(Function<Long, List<S>> fetcher, Function<List<S>, List<T>> mapper,
                              Function<S, Long> idExtractor, int batchSize) {
            this.fetcher = fetcher;
            this.mapper = mapper;
            this.idExtractor = idExtractor;
            this.batchSize = batchSize;
        }

        @Override
        public Iterator<List<T>> iterator() {
            return new Iterator<>() {
                private Long lastId;
                private List<S> pending;
                private boolean finished;

                @Override
                public boolean hasNext() {
                    if (pending == null && !finished) {
                        pending = fetcher.apply(lastId);
                        if (pending == null || pending.isEmpty()) {
                            finished = true;
                        }
                    }
                    return pending != null && !pending.isEmpty();
                }

                @Override
                public List<T> next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    List<S> current = pending;
                    pending = null;
                    Long nextId = idExtractor.apply(current.get(current.size() - 1));
                    if (nextId == null || nextId.equals(lastId) || current.size() < batchSize) {
                        finished = true;
                    }
                    lastId = nextId;
                    return mapper.apply(current);
                }
            };
        }
    }
}
