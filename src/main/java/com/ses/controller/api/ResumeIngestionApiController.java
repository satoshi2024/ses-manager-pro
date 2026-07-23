package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.dto.resume.ReviewedResumeDto;
import com.ses.entity.ResumeIngestion;
import com.ses.service.ResumeIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * スキルシート取込 API コントローラー。
 * アップロード -> 解析 -> レビュー -> 確定/却下のワークフローを提供する。
 */
@RestController
@RequestMapping("/api/resume-ingestions")
@RequiredArgsConstructor
public class ResumeIngestionApiController {

    private final ResumeIngestionService resumeIngestionService;

    /**
     * ジョブ一覧を取得する（ページネーション・ステータスフィルター）。
     */
    @GetMapping
    public ApiResult<Page<ResumeIngestion>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String status) {
        Page<ResumeIngestion> page = PageUtils.safePage(current, size);
        LambdaQueryWrapper<ResumeIngestion> wrapper = new LambdaQueryWrapper<ResumeIngestion>()
                .eq(status != null && !status.isBlank(), ResumeIngestion::getStatus, status)
                .orderByDesc(ResumeIngestion::getCreatedAt);
        return ApiResult.success(resumeIngestionService.page(page, wrapper));
    }

    /**
     * スキルシートファイルをアップロードし、取込ジョブを作成する。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ResumeIngestion> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "candidateId", required = false) Long candidateId) {
        return ApiResult.success(resumeIngestionService.createJob(file, candidateId));
    }

    /**
     * ジョブ詳細（ステータス・解析結果含む）を取得する。
     */
    @GetMapping("/{id}")
    public ApiResult<ResumeIngestion> getById(@PathVariable Long id) {
        return ApiResult.success(resumeIngestionService.getById(id));
    }

    /**
     * レビュー編集内容を中間保存する。
     */
    @PutMapping("/{id}/review")
    public ApiResult<Boolean> saveReview(
            @PathVariable Long id,
            @RequestBody ReviewedResumeDto dto) {
        resumeIngestionService.saveReview(id, dto);
        return ApiResult.success(true);
    }

    /**
     * 取込を確定し、要員・スキル・経歴を一括生成する。
     */
    @PostMapping("/{id}/confirm")
    public ApiResult<Long> confirm(
            @PathVariable Long id,
            @RequestBody ReviewedResumeDto dto) {
        Long engineerId = resumeIngestionService.confirm(id, dto);
        return ApiResult.success(engineerId);
    }

    /**
     * 取込を却下する。
     */
    @PostMapping("/{id}/reject")
    public ApiResult<Boolean> reject(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "");
        resumeIngestionService.reject(id, reason);
        return ApiResult.success(true);
    }

    /**
     * 失敗/要確認ジョブを再解析する。
     */
    @PostMapping("/{id}/reparse")
    public ApiResult<Boolean> reparse(@PathVariable Long id) {
        resumeIngestionService.reparse(id);
        return ApiResult.success(true);
    }
}
