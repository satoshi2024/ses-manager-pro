package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.result.ApiResult;
import com.ses.common.util.CsvUtils;
import com.ses.dto.csv.CsvImportResultDto;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.service.EngineerService;
import com.ses.service.ProjectService;
import com.ses.service.csv.EngineerCsvService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

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
    private final com.ses.service.security.DataScopeService dataScopeService;
    private final org.springframework.context.MessageSource messageSource;

    /** 要員一覧CSV出力（一覧の検索条件を反映）。 */
    @GetMapping("/api/engineers/export-csv")
    public ResponseEntity<byte[]> exportEngineers(
            @RequestParam(required = false) String fullName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String employmentType,
            @RequestParam(required = false) List<Long> skillIds) {

        LambdaQueryWrapper<Engineer> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(fullName)) {
            qw.like(Engineer::getFullName, fullName);
        }
        if (StringUtils.hasText(status)) {
            qw.eq(Engineer::getStatus, status);
        }
        if (StringUtils.hasText(employmentType)) {
            qw.eq(Engineer::getEmploymentType, employmentType);
        }
        if (skillIds != null && !skillIds.isEmpty()) {
            for (Long skillId : skillIds) {
                qw.inSql(Engineer::getId,
                        "SELECT engineer_id FROM t_engineer_skill WHERE skill_id = " + skillId);
            }
        }
        // スコープONの営業には担当要員のみ出力する（全件CSV流出防止 / R3R-31）。
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> ids = dataScopeService.allowedEngineerIds();
            if (ids.isEmpty()) return csvResponse(engineerCsvService.exportCsv(List.of()), "要員一覧");
            qw.in(Engineer::getId, ids);
        }
        qw.orderByDesc(Engineer::getId);
        byte[] bytes = engineerCsvService.exportCsv(engineerService.list(qw));
        return csvResponse(bytes, "要員一覧");
    }

    /** 案件一覧CSV出力。 */
    @GetMapping("/api/projects/export-csv")
    public ResponseEntity<byte[]> exportProjects(
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String status) {

        LambdaQueryWrapper<Project> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(projectName)) {
            qw.like(Project::getProjectName, projectName);
        }
        if (StringUtils.hasText(status)) {
            qw.eq(Project::getStatus, status);
        }
        // スコープONの営業には担当案件のみ出力する（全件CSV流出防止 / R3R-31）。
        boolean scopedEmpty = false;
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> ids = dataScopeService.allowedProjectIds();
            if (ids.isEmpty()) scopedEmpty = true;
            else qw.in(Project::getId, ids);
        }
        qw.orderByDesc(Project::getId);

        StringBuilder sb = new StringBuilder(CsvUtils.UTF8_BOM);
        CsvUtils.appendLine(sb, "案件名", "商流", "ステータス", "優先度",
                "単価下限", "単価上限", "勤務地", "開始日", "終了日");
        for (Project p : (scopedEmpty ? java.util.List.<Project>of() : projectService.list(qw))) {
            CsvUtils.appendLine(sb,
                    nz(p.getProjectName()), nz(p.getCommercialFlow()), nz(p.getStatus()), nz(p.getPriority()),
                    p.getUnitPriceMin() != null ? p.getUnitPriceMin().toPlainString() : "",
                    p.getUnitPriceMax() != null ? p.getUnitPriceMax().toPlainString() : "",
                    nz(p.getWorkLocation()),
                    p.getStartDate() != null ? p.getStartDate().toString() : "",
                    p.getEndDate() != null ? p.getEndDate().toString() : "");
        }
        return csvResponse(sb.toString().getBytes(StandardCharsets.UTF_8), "案件一覧");
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

    private ResponseEntity<byte[]> csvResponse(byte[] bytes, String baseName) {
        String fileName = baseName + "_" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".csv";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(bytes);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}


