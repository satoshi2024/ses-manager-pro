package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.dto.bpavailability.ReviewedBpAvailabilityDto;
import com.ses.entity.BpAvailabilityIngestion;
import com.ses.service.BpAvailabilityIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 外部要員在庫メール取込 API コントローラー。
 */
@RestController
@RequestMapping("/api/bp-availability-ingestions")
@RequiredArgsConstructor
public class BpAvailabilityIngestionApiController {

    private final BpAvailabilityIngestionService bpAvailabilityIngestionService;

    /**
     * ジョブ一覧を取得する。
     */
    @GetMapping
    public ApiResult<Page<BpAvailabilityIngestion>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String status) {
        Page<BpAvailabilityIngestion> page = PageUtils.safePage(current, size);
        LambdaQueryWrapper<BpAvailabilityIngestion> wrapper = new LambdaQueryWrapper<BpAvailabilityIngestion>()
                .eq(status != null && !status.isBlank(), BpAvailabilityIngestion::getStatus, status)
                .orderByDesc(BpAvailabilityIngestion::getCreatedAt);
        return ApiResult.success(bpAvailabilityIngestionService.page(page, wrapper));
    }

    /**
     * emlファイルをアップロードし、取込ジョブを作成する。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<BpAvailabilityIngestion> upload(@RequestParam("file") MultipartFile file) {
        return ApiResult.success(bpAvailabilityIngestionService.createJob(file));
    }

    /**
     * テキストをペーストし、取込ジョブを作成する。
     */
    @PostMapping("/paste")
    public ApiResult<BpAvailabilityIngestion> paste(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        return ApiResult.success(bpAvailabilityIngestionService.createJobFromPaste(text));
    }

    /**
     * ジョブ詳細を取得する。
     */
    @GetMapping("/{id}")
    public ApiResult<BpAvailabilityIngestion> getById(@PathVariable Long id) {
        return ApiResult.success(bpAvailabilityIngestionService.getById(id));
    }

    /**
     * レビュー内容を保存する。
     */
    @PutMapping("/{id}/review")
    public ApiResult<Boolean> saveReview(
            @PathVariable Long id,
            @RequestBody ReviewedBpAvailabilityDto dto) {
        bpAvailabilityIngestionService.saveReview(id, dto);
        return ApiResult.success(true);
    }

    /**
     * 外部要員在庫を確定生成する。
     */
    @PostMapping("/{id}/confirm")
    public ApiResult<Long> confirm(
            @PathVariable Long id,
            @RequestBody ReviewedBpAvailabilityDto dto) {
        Long availabilityId = bpAvailabilityIngestionService.confirm(id, dto);
        return ApiResult.success(availabilityId);
    }

    /**
     * 却下する。
     */
    @PostMapping("/{id}/reject")
    public ApiResult<Boolean> reject(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "");
        bpAvailabilityIngestionService.reject(id, reason);
        return ApiResult.success(true);
    }

    /**
     * 再解析する。
     */
    @PostMapping("/{id}/reparse")
    public ApiResult<Boolean> reparse(@PathVariable Long id) {
        bpAvailabilityIngestionService.reparse(id);
        return ApiResult.success(true);
    }
}
