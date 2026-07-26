package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.result.ApiResult;
import com.ses.common.util.CsvUtils;
import com.ses.dto.csv.CsvImportResultDto;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.service.EngineerService;
import com.ses.service.ProjectService;
import com.ses.service.RetentionRiskService;
import com.ses.service.csv.EngineerCsvService;
import com.ses.service.export.ExportConcurrencyLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URLEncoder;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * CSV入出力API。
 * エンドポイントは既存の api_prefix（/api/engineers・/api/projects）配下に置き、
 * メニュー権限（engineer / project）をそのまま引き継ぐ。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CsvApiController {

    private final EngineerService engineerService;
    private final ProjectService projectService;
    private final EngineerCsvService engineerCsvService;
    private final RetentionRiskService retentionRiskService;
    private final com.ses.service.security.DataScopeService dataScopeService;
    private final org.springframework.context.MessageSource messageSource;

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
        configuredBatchSize = configuredBatchSize <= 0 ? 500 : Math.min(2000, Math.max(100, configuredBatchSize));
        configuredMaxRows = configuredMaxRows > 0 ? configuredMaxRows : 50000;
        configuredMaxScanRows = configuredMaxScanRows > 0 ? configuredMaxScanRows : 100000;
        configuredMaxConcurrent = configuredMaxConcurrent > 0 ? configuredMaxConcurrent : 2;
        ExportConcurrencyLimiter.configure(configuredMaxConcurrent);
    }

    /** 要員一覧CSV出力（一覧の検索条件を反映）。 */
    @GetMapping("/api/engineers/export-csv")
    public void exportEngineers(
            @RequestParam(required = false) String fullName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String employmentType,
            @RequestParam(required = false) List<Long> skillIds,
            @RequestParam(required = false) Long salesUserId,
            @RequestParam(required = false) String riskLevel,
            HttpServletResponse response) throws IOException {

        ExportConcurrencyLimiter.acquire();
        try {
            LambdaQueryWrapper<Engineer> qw = new LambdaQueryWrapper<>();
            if (StringUtils.hasText(fullName)) qw.like(Engineer::getFullName, fullName);
            if (StringUtils.hasText(status)) qw.eq(Engineer::getStatus, status);
            if (StringUtils.hasText(employmentType)) qw.eq(Engineer::getEmploymentType, employmentType);
            if (skillIds != null && !skillIds.isEmpty()) {
                for (Long skillId : skillIds) {
                    if (skillId != null) {
                        qw.apply("EXISTS (SELECT 1 FROM t_engineer_skill es "
                                        + "WHERE es.engineer_id = t_engineer.id AND es.skill_id = {0})",
                                skillId);
                    }
                }
            }
            if (salesUserId != null) {
                qw.apply("EXISTS (SELECT 1 FROM t_engineer_sales es "
                        + "WHERE es.engineer_id = t_engineer.id AND es.sales_user_id = {0} "
                        + "AND es.released_at IS NULL AND es.deleted_flag = 0)", salesUserId);
            }
            if (dataScopeService.isScoped()) {
                java.util.Set<Long> ids = dataScopeService.allowedEngineerIds();
                if (ids.isEmpty()) {
                    prepareCsvResponse(response, "要員一覧");
                    try (BufferedWriter writer = writer(response)) {
                        engineerCsvService.writeCsvHeader(writer);
                    }
                    return;
                }
                qw.in(Engineer::getId, ids);
            }
            qw.orderByDesc(Engineer::getId);
            boolean highRiskOnly = "high".equalsIgnoreCase(riskLevel);
            if (highRiskOnly) {
                assertWithinMaxRows(countHighRiskEngineers(qw));
                prepareCsvResponse(response, "要員一覧");
                try (BufferedWriter writer = writer(response)) {
                    engineerCsvService.writeCsvHeader(writer);
                    for (List<Engineer> rows : highRiskBatches(qw)) {
                        engineerCsvService.writeCsvRows(rows, writer);
                    }
                }
                return;
            }
            assertWithinMaxRows(engineerService.count(qw));
            prepareCsvResponse(response, "要員一覧");
            try (BufferedWriter writer = writer(response)) {
                engineerCsvService.writeCsvHeader(writer);
                for (List<Engineer> rows : new BatchIterable<>(lastId -> {
                    LambdaQueryWrapper<Engineer> batch = qw.clone();
                    if (lastId != null) batch.lt(Engineer::getId, lastId);
                    batch.orderByDesc(Engineer::getId).last("LIMIT " + configuredBatchSize);
                    return engineerService.list(batch);
                }, rows -> highRiskOnly ? filterHighRisk(rows) : rows, Engineer::getId, configuredBatchSize)) {
                    engineerCsvService.writeCsvRows(rows, writer);
                }
            }
        } finally {
            ExportConcurrencyLimiter.release();
        }
    }

    /*
     * 旧実装の全件List + byte[]経路は廃止済み。以下は案件CSVのストリーム出力。
     */
    /** 案件一覧CSV出力。 */
    @GetMapping("/api/projects/export-csv")
    public void exportProjects(
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String status,
            HttpServletResponse response) throws IOException {

        ExportConcurrencyLimiter.acquire();
        try {
            LambdaQueryWrapper<Project> qw = new LambdaQueryWrapper<>();
            if (StringUtils.hasText(projectName)) qw.like(Project::getProjectName, projectName);
            if (StringUtils.hasText(status)) qw.eq(Project::getStatus, status);
            if (dataScopeService.isScoped()) {
                java.util.Set<Long> ids = dataScopeService.allowedProjectIds();
                if (ids.isEmpty()) {
                    prepareCsvResponse(response, "案件一覧");
                    try (BufferedWriter writer = writer(response)) {
                        writeProjectHeader(writer);
                    }
                    return;
                }
                qw.in(Project::getId, ids);
            }
            qw.orderByDesc(Project::getId);
            assertWithinMaxRows(projectService.count(qw));
            prepareCsvResponse(response, "案件一覧");
            try (BufferedWriter writer = writer(response)) {
                writeProjectHeader(writer);
                for (List<Project> rows : new BatchIterable<>(lastId -> {
                    LambdaQueryWrapper<Project> batch = qw.clone();
                    if (lastId != null) batch.lt(Project::getId, lastId);
                    batch.orderByDesc(Project::getId).last("LIMIT " + configuredBatchSize);
                    return projectService.list(batch);
                }, rows -> rows, Project::getId, configuredBatchSize)) {
                    for (Project p : rows) {
                        CsvUtils.appendLine(writer,
                                nz(p.getProjectName()), nz(p.getCommercialFlow()), nz(p.getStatus()), nz(p.getPriority()),
                                p.getUnitPriceMin() != null ? p.getUnitPriceMin().toPlainString() : "",
                                p.getUnitPriceMax() != null ? p.getUnitPriceMax().toPlainString() : "",
                                nz(p.getWorkLocation()),
                                p.getStartDate() != null ? p.getStartDate().toString() : "",
                                p.getEndDate() != null ? p.getEndDate().toString() : "");
                    }
                }
            }
        } finally {
            ExportConcurrencyLimiter.release();
        }
    }

    /** 要員CSVインポート（部分成功可）。 */
    @PostMapping("/api/engineers/import-csv")
    public ApiResult<CsvImportResultDto> importEngineers(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ApiResult.error(400, messageSource.getMessage("error.csv.emptyFile", null, org.springframework.context.i18n.LocaleContextHolder.getLocale()));
        }
        try (var in = file.getInputStream()) {
            return ApiResult.success(engineerCsvService.importCsv(in));
        } catch (IOException e) {
            log.error("CSVインポートに失敗しました", e);
            return ApiResult.error(messageSource.getMessage("error.csv.importFailed", null, org.springframework.context.i18n.LocaleContextHolder.getLocale()));
        }
    }

    private void prepareCsvResponse(HttpServletResponse response, String baseName) {
        String fileName = baseName + "_" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".csv";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8).toString());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
    }

    private BufferedWriter writer(HttpServletResponse response) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8));
    }

    private void writeProjectHeader(BufferedWriter writer) throws IOException {
        writer.write(CsvUtils.UTF8_BOM);
        CsvUtils.appendLine(writer, "案件名", "商流", "ステータス", "優先度", "単価下限", "単価上限", "勤務地", "開始日", "終了日");
    }

    private void assertWithinMaxRows(long count) {
        if (count > configuredMaxRows) {
            throw com.ses.common.exception.BusinessException.of("error.export.maxRows", configuredMaxRows);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private long countHighRiskEngineers(LambdaQueryWrapper<Engineer> queryWrapper) {
        long candidateCount = engineerService.count(queryWrapper);
        if (candidateCount > configuredMaxScanRows) {
            throw com.ses.common.exception.BusinessException.of("error.export.maxScanRows", configuredMaxScanRows);
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
            LambdaQueryWrapper<Engineer> batch = queryWrapper.clone();
            if (lastId != null) batch.lt(Engineer::getId, lastId);
            int limit = (int) Math.min(configuredBatchSize, remaining);
            batch.orderByDesc(Engineer::getId).last("LIMIT " + limit);
            List<Engineer> fetched = engineerService.list(batch);
            if (fetched.size() > remaining) {
                fetched = fetched.subList(0, (int) remaining);
            }
            scannedRows[0] += fetched.size();
            return fetched;
        }, this::filterHighRisk, Engineer::getId, configuredBatchSize);
    }

    private List<Engineer> filterHighRisk(List<Engineer> rows) {
        java.util.Set<Long> highRiskIds = retentionRiskService.scoreBatchFor(rows).entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().isHighRisk())
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        return rows.stream().filter(engineer -> highRiskIds.contains(engineer.getId())).toList();
    }

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
                        if (pending == null || pending.isEmpty()) finished = true;
                    }
                    return pending != null && !pending.isEmpty();
                }

                @Override
                public List<T> next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    List<S> current = pending;
                    pending = null;
                    Long nextId = idExtractor.apply(current.get(current.size() - 1));
                    if (nextId == null || nextId.equals(lastId) || current.size() < batchSize) finished = true;
                    lastId = nextId;
                    return mapper.apply(current);
                }
            };
        }
    }
}


