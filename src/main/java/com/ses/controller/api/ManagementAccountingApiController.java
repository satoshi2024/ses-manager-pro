package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.EntityProtectUtil;
import com.ses.dto.organization.BudgetSaveRequest;
import com.ses.dto.accounting.ManagementAccountingSummaryDto;
import com.ses.entity.ManagementBudget;
import com.ses.entity.MonthlyAccountingDimension;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.service.ManagementBudgetService;
import com.ses.service.ManagementAccountingService;
import com.ses.service.security.OrganizationScopeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;

/** 月次snapshotと予算API。読取は組織scopeをSQL条件として適用する。 */
@RestController
@RequestMapping("/api/management-accounting")
@RequiredArgsConstructor
public class ManagementAccountingApiController {

    private final ManagementBudgetService budgetService;
    private final ManagementAccountingService managementAccountingService;
    private final ManagementBudgetMapper budgetMapper;
    private final MonthlyAccountingDimensionMapper dimensionMapper;
    private final OrganizationScopeService organizationScopeService;

    /** CSV一括取込の最大行数（ヘッダー除く）。 */
    private static final int MAX_CSV_ROWS = 200;

    @GetMapping("/summary")
    public ApiResult<ManagementAccountingSummaryDto> summary(@RequestParam String month,
            @RequestParam(required = false) Long legalEntityId, @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) Long costCenterId, @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long projectId, @RequestParam(required = false) Long salesUserId) {
        return ApiResult.success(managementAccountingService.summary(month, legalEntityId, organizationId,
                costCenterId, customerId, projectId, salesUserId));
    }

    /** exportはsummaryと同じSQL母集団をCSV化する。画面取得後の再絞り込みは行わない。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam String month,
            @RequestParam(required = false) Long legalEntityId, @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) Long costCenterId, @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long projectId, @RequestParam(required = false) Long salesUserId) {
        ManagementAccountingSummaryDto summary = managementAccountingService.summary(month, legalEntityId, organizationId,
                costCenterId, customerId, projectId, salesUserId);
        // 予実行と内訳行を1ファイルに出す。level列で粒度を明示し、予算列は予実行にだけ値を入れる
        // （内訳粒度には予算が存在しないため、そこへ予算差を出すと必ず誤った数字になる）。
        StringBuilder csv = new StringBuilder("level,organizationId,organizationName,costCenterId,customerId,projectId,"
                + "salesUserId,revenue,cost,grossProfit,budgetRevenue,budgetGrossProfit,revenueVariance,"
                + "grossProfitVariance,waitCost\n");
        for (ManagementAccountingSummaryDto.Row row : summary.getRows()) {
            csv.append("summary").append(',')
                    .append(csvValue(row.getOrganizationId())).append(',').append(csvValue(row.getOrganizationName())).append(',')
                    .append(csvValue(row.getCostCenterId())).append(',').append(",,")
                    .append(csvValue(row.getRevenue())).append(',').append(csvValue(row.getCost())).append(',')
                    .append(csvValue(row.getGrossProfit())).append(',').append(csvValue(row.getBudgetRevenue())).append(',')
                    .append(csvValue(row.getBudgetGrossProfit())).append(',').append(csvValue(row.getRevenueVariance())).append(',')
                    .append(csvValue(row.getGrossProfitVariance())).append(',').append(csvValue(row.getWaitCost())).append('\n');
        }
        for (ManagementAccountingSummaryDto.Detail detail : summary.getDetails()) {
            csv.append("detail").append(',')
                    .append(csvValue(detail.getOrganizationId())).append(',').append(csvValue(detail.getOrganizationName())).append(',')
                    .append(csvValue(detail.getCostCenterId())).append(',').append(csvValue(detail.getCustomerId())).append(',')
                    .append(csvValue(detail.getProjectId())).append(',').append(csvValue(detail.getSalesUserId())).append(',')
                    .append(csvValue(detail.getRevenue())).append(',').append(csvValue(detail.getCost())).append(',')
                    .append(csvValue(detail.getGrossProfit())).append(",,,,\n");
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=management-accounting-" + month + ".csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/drilldown")
    public ApiResult<ManagementAccountingSummaryDto> drilldown(@RequestParam String month,
            @RequestParam(required = false) Long legalEntityId, @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) Long costCenterId, @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long projectId, @RequestParam(required = false) Long salesUserId) {
        return summary(month, legalEntityId, organizationId, costCenterId, customerId, projectId, salesUserId);
    }

    @GetMapping("/budgets")
    public ApiResult<List<ManagementBudget>> budgets(@RequestParam LocalDate month) {
        LambdaQueryWrapper<ManagementBudget> query = new LambdaQueryWrapper<ManagementBudget>()
                .eq(ManagementBudget::getBudgetMonth, month).orderByAsc(ManagementBudget::getOrganizationId);
        organizationScopeService.applyOrganizationScope(query, ManagementBudget::getOrganizationId, month);
        return ApiResult.success(budgetMapper.selectList(query));
    }

    @PostMapping("/budgets")
    public ApiResult<ManagementBudget> saveBudget(@Valid @RequestBody BudgetSaveRequest request) {
        organizationScopeService.assertAllowedOrganization(request.organizationId());
        ManagementBudget budget = ManagementBudget.builder()
                .organizationId(request.organizationId()).costCenterId(request.costCenterId())
                .budgetMonth(request.budgetMonth()).revenue(request.revenue()).grossProfit(request.grossProfit())
                .utilizationCount(request.utilizationCount()).hireCount(request.hireCount())
                .version(request.version() == null ? 0 : request.version()).build();
        EntityProtectUtil.protectForCreate(budget);
        return ApiResult.success(budgetService.upsert(budget, request.version()));
    }

    /**
     * 予算CSVを全行検証し、既存行はversion付きupsertする。数値列はBigDecimal/Integerで解析するため、
     * 数式文字列や負数を値として受け付けない。
     * ヘッダー: organizationId,costCenterId,budgetMonth,revenue,grossProfit,utilizationCount,hireCount,version
     */
    @PostMapping(value = "/budgets/csv", consumes = "multipart/form-data")
    @Transactional
    public ApiResult<Integer> importBudgetCsv(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.of("error.organization.budget.csvInvalid");
        }
        try {
            String csv = new String(file.getBytes(), StandardCharsets.UTF_8).replace("\uFEFF", "");
            String[] lines = csv.split("\\R");
            // \u4E00\u62EC\u64CD\u4F5C\u306E\u4E0A\u9650\u306F\u5168spec\u5171\u901A\u3067200\u4EF6\uFF08shared-standards \u00A73\uFF09\u3002
            // 1\u884C\u3054\u3068\u306BSELECT+UPDATE\u304C\u8D70\u308B\u305F\u3081\u3001\u4E0A\u9650\u306A\u3057\u3060\u30681\u30C8\u30E9\u30F3\u30B6\u30AF\u30B7\u30E7\u30F3\u3067\u63A5\u7D9A\u3092\u5360\u6709\u3057\u7D9A\u3051\u308B\u3002
            if (lines.length > MAX_CSV_ROWS + 1) {
                throw BusinessException.of("error.organization.budget.csvTooManyRows");
            }
            int imported = 0;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isBlank() || (i == 0 && line.toLowerCase(Locale.ROOT).startsWith("organizationid,"))) {
                    continue;
                }
                String[] columns = line.split(",", -1);
                if (columns.length != 8) {
                    throw BusinessException.of("error.organization.budget.csvInvalid");
                }
                BudgetSaveRequest request = new BudgetSaveRequest(
                        Long.valueOf(columns[0].trim()), nullableLong(columns[1]), LocalDate.parse(columns[2].trim()),
                        new BigDecimal(columns[3].trim()), new BigDecimal(columns[4].trim()),
                        Integer.valueOf(columns[5].trim()), Integer.valueOf(columns[6].trim()), nullableInteger(columns[7]));
                organizationScopeService.assertAllowedOrganization(request.organizationId());
                ManagementBudget budget = ManagementBudget.builder()
                        .organizationId(request.organizationId()).costCenterId(request.costCenterId())
                        .budgetMonth(request.budgetMonth()).revenue(request.revenue()).grossProfit(request.grossProfit())
                        .utilizationCount(request.utilizationCount()).hireCount(request.hireCount())
                        .version(request.version() == null ? 0 : request.version()).build();
                EntityProtectUtil.protectForCreate(budget);
                budgetService.upsert(budget, request.version());
                imported++;
            }
            return ApiResult.success(imported);
        } catch (BusinessException e) {
            throw e;
        } catch (IllegalArgumentException | IOException e) {
            throw BusinessException.of("error.organization.budget.csvInvalid");
        }
    }

    @GetMapping("/snapshots")
    public ApiResult<List<MonthlyAccountingDimension>> snapshots(@RequestParam LocalDate month) {
        LambdaQueryWrapper<MonthlyAccountingDimension> query = new LambdaQueryWrapper<MonthlyAccountingDimension>()
                .eq(MonthlyAccountingDimension::getWorkMonth, month)
                .orderByAsc(MonthlyAccountingDimension::getId);
        organizationScopeService.applyOrganizationScope(query, MonthlyAccountingDimension::getOrganizationId, month);
        return ApiResult.success(dimensionMapper.selectList(query));
    }

    private Long nullableLong(String value) {
        return value == null || value.isBlank() ? null : Long.valueOf(value.trim());
    }

    private Integer nullableInteger(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
    }

    private String csvValue(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        if (text.startsWith("=") || text.startsWith("+") || text.startsWith("-") || text.startsWith("@")) {
            text = "'" + text;
        }
        return text.contains(",") || text.contains("\"") || text.contains("\n")
                ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }
}
