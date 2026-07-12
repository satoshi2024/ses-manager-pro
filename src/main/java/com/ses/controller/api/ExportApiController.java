package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import com.ses.service.export.ExcelExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * 要員一覧Excel出力。EngineerApiController.page と同じ検索条件を受け付ける(ページングなし)。
     */
    @GetMapping("/api/engineers/export")
    public ResponseEntity<byte[]> exportEngineers(
            @RequestParam(required = false) String fullName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String employmentType,
            @RequestParam(required = false) List<Long> skillIds) {

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
                queryWrapper.inSql(Engineer::getId,
                        "SELECT engineer_id FROM t_engineer_skill WHERE skill_id = " + skillId);
            }
        }
        queryWrapper.orderByDesc(Engineer::getId);

        List<Engineer> rows = engineerService.list(queryWrapper);
        byte[] bytes = excelExportService.exportEngineers(rows);
        return buildFileResponse(bytes, "要員一覧");
    }

    /**
     * 契約一覧Excel出力。contract/list.html の検索フォーム(status/customerName/keyword)と同じ条件を受け付ける。
     */
    @GetMapping("/api/contracts/export")
    public ResponseEntity<byte[]> exportContracts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String keyword) {

        QueryWrapper<Contract> queryWrapper = new QueryWrapper<>();
        if (StringUtils.hasText(status)) {
            queryWrapper.eq("status", status);
        }
        if (StringUtils.hasText(customerName)) {
            List<Customer> matchedCustomers = customerMapper.selectList(
                    new QueryWrapper<Customer>().like("company_name", customerName));
            List<Long> customerIds = matchedCustomers.stream().map(Customer::getId).collect(Collectors.toList());
            if (customerIds.isEmpty()) {
                // No matching customer: return empty workbook early
                byte[] emptyBytes = excelExportService.exportContracts(List.of());
                return buildFileResponse(emptyBytes, "契約一覧");
            }
            queryWrapper.in("customer_id", customerIds);
        }
        if (StringUtils.hasText(keyword)) {
            List<Engineer> matchedEngineers = engineerService.list(
                    new LambdaQueryWrapper<Engineer>().like(Engineer::getFullName, keyword));
            List<Project> matchedProjects = projectMapper.selectList(
                    new QueryWrapper<Project>().like("project_name", keyword));

            Set<Long> engineerIds = matchedEngineers.stream().map(Engineer::getId).collect(Collectors.toSet());
            Set<Long> projectIds = matchedProjects.stream().map(Project::getId).collect(Collectors.toSet());

            if (engineerIds.isEmpty() && projectIds.isEmpty()) {
                byte[] emptyBytes = excelExportService.exportContracts(List.of());
                return buildFileResponse(emptyBytes, "契約一覧");
            }
            queryWrapper.and(w -> {
                boolean first = true;
                if (!engineerIds.isEmpty()) {
                    w.in("engineer_id", engineerIds);
                    first = false;
                }
                if (!projectIds.isEmpty()) {
                    if (!first) {
                        w.or();
                    }
                    w.in("project_id", projectIds);
                }
            });
        }
        queryWrapper.orderByDesc("id");

        List<Contract> contracts = contractMapper.selectList(queryWrapper);
        List<ContractExportDto> rows = toContractExportDtos(contracts);

        byte[] bytes = excelExportService.exportContracts(rows);
        return buildFileResponse(bytes, "契約一覧");
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
    private List<ContractExportDto> toContractExportDtos(List<Contract> contracts) {
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
                    .costPrice(c.getCostPrice())
                    .status(c.getStatus())
                    .build());
        }
        return result;
    }

    /**
     * 指定年度(4月始まり12ヶ月)の月次売上・粗利・実績/見込み区分を集計する。
     * 確定済み(status='確定')の稼働報告があればその実績値、無ければ契約の売上/原価から見込み値を算出する。
     * (DashboardServiceImpl.getSummary の稼働報告集計ロジックと同様の考え方)
     */
    private List<MonthlyRevenueDto> buildMonthlyRevenueRows(int fiscalYear) {
        List<YearMonth> targetMonths = buildFiscalYearMonths(fiscalYear);
        List<Contract> allContracts = contractMapper.selectList(new QueryWrapper<>());

        List<MonthlyRevenueDto> rows = new ArrayList<>();
        for (YearMonth ym : targetMonths) {
            LocalDate monthStart = ym.atDay(1);
            LocalDate monthEnd = ym.atEndOfMonth();
            String monthStr = ym.toString();
            String label = ym.getYear() + "年" + ym.getMonthValue() + "月";

            List<WorkRecord> confirmedRecords = workRecordMapper.selectList(
                    new QueryWrapper<WorkRecord>().eq("work_month", monthStr).eq("status", "確定"));

            long monthSales = 0;
            long monthProfit = 0;
            boolean isActual = false;

            if (!confirmedRecords.isEmpty()) {
                isActual = true;
                for (WorkRecord wr : confirmedRecords) {
                    long billing = wr.getBillingAmount() != null ? wr.getBillingAmount().longValue() : 0;
                    long payment = wr.getPaymentAmount() != null ? wr.getPaymentAmount().longValue() : 0;
                    monthSales += billing;
                    monthProfit += (billing - payment);
                }
            } else {
                for (Contract c : allContracts) {
                    if (c.getStartDate() != null && !c.getStartDate().isAfter(monthEnd)
                            && (c.getEndDate() == null || !c.getEndDate().isBefore(monthStart))) {
                        long sell = c.getSellingPrice() != null ? c.getSellingPrice().longValue() : 0;
                        long cost = c.getCostPrice() != null ? c.getCostPrice().longValue() : 0;
                        monthSales += sell;
                        monthProfit += (sell - cost);
                    }
                }
            }

            rows.add(MonthlyRevenueDto.builder()
                    .label(label)
                    .sales(monthSales)
                    .profit(monthProfit)
                    .isActual(isActual)
                    .build());
        }
        return rows;
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
}
