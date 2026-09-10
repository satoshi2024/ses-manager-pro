package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.dto.projectingestion.ProjectIngestionResponse;
import com.ses.dto.projectingestion.ReviewedProjectDto;
import com.ses.entity.ProjectIngestion;
import com.ses.service.ProjectIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 案件メール取込 API コントローラー。
 */
@RestController
@RequestMapping("/api/project-ingestions")
@RequiredArgsConstructor
public class ProjectIngestionApiController {

    private final ProjectIngestionService projectIngestionService;

    /**
     * ジョブ一覧を取得する。
     */
    @GetMapping
    public ApiResult<Page<ProjectIngestionResponse>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String status) {
        Page<ProjectIngestion> page = PageUtils.safePage(current, size);
        projectIngestionService.pageForCurrentTenant(page, status);
        Page<ProjectIngestionResponse> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(ProjectIngestionResponse::from)
                .collect(Collectors.toList()));
        return ApiResult.success(result);
    }

    /**
     * emlファイルをアップロードし、取込ジョブを作成する。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ProjectIngestionResponse> upload(@RequestParam("file") MultipartFile file) {
        return ApiResult.success(ProjectIngestionResponse.from(projectIngestionService.createJob(file)));
    }

    /**
     * テキストをペーストし、取込ジョブを作成する。
     */
    @PostMapping("/paste")
    public ApiResult<ProjectIngestionResponse> paste(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        return ApiResult.success(ProjectIngestionResponse.from(projectIngestionService.createJobFromPaste(text)));
    }

    /**
     * ジョブ詳細を取得する。
     */
    @GetMapping("/{id}")
    public ApiResult<ProjectIngestionResponse> getById(@PathVariable Long id) {
        ProjectIngestion job = projectIngestionService.getForCurrentTenant(id);
        if (job == null) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(ProjectIngestionResponse.from(job));
    }

    /**
     * レビュー内容を保存する。
     */
    @PutMapping("/{id}/review")
    public ApiResult<Boolean> saveReview(
            @PathVariable Long id,
            @RequestBody ReviewedProjectDto dto) {
        projectIngestionService.saveReview(id, dto);
        return ApiResult.success(true);
    }

    /**
     * 案件を確定生成する。
     */
    @PostMapping("/{id}/confirm")
    public ApiResult<Long> confirm(
            @PathVariable Long id,
            @RequestBody ReviewedProjectDto dto) {
        Long projectId = projectIngestionService.confirm(id, dto);
        return ApiResult.success(projectId);
    }

    /**
     * 却下する。
     */
    @PostMapping("/{id}/reject")
    public ApiResult<Boolean> reject(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "");
        projectIngestionService.reject(id, reason);
        return ApiResult.success(true);
    }

    /**
     * 再解析する。
     */
    @PostMapping("/{id}/reparse")
    public ApiResult<Boolean> reparse(@PathVariable Long id) {
        projectIngestionService.reparse(id);
        return ApiResult.success(true);
    }
}
