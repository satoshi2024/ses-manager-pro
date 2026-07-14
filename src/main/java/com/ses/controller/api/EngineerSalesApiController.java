package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.engineersales.EngineerSalesDto;
import com.ses.dto.engineersales.SalesUserOptionDto;
import com.ses.service.EngineerSalesService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 要員担当営業API
 * 既存 engineer メニューの api_prefix (/api/engineers) 配下に置き、権限設定を共有する
 */
@RestController
@RequestMapping("/api/engineers")
@RequiredArgsConstructor
public class EngineerSalesApiController {

    private final EngineerSalesService engineerSalesService;

    /** 現任担当営業一覧 */
    @GetMapping("/{id}/sales-reps")
    public ApiResult<List<EngineerSalesDto>> listActive(@PathVariable Long id) {
        return ApiResult.success(engineerSalesService.listActive(id));
    }

    /** 担当営業履歴（解除済み含む） */
    @GetMapping("/{id}/sales-reps/history")
    public ApiResult<List<EngineerSalesDto>> listHistory(@PathVariable Long id) {
        return ApiResult.success(engineerSalesService.listHistory(id));
    }

    /** 担当営業の割当 */
    @PostMapping("/{id}/sales-reps")
    public ApiResult<Boolean> assign(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long salesUserId = body.get("salesUserId") == null ? null
                : Long.valueOf(String.valueOf(body.get("salesUserId")));
        boolean primaryFlag = Boolean.parseBoolean(String.valueOf(body.getOrDefault("primaryFlag", "false")))
                || "1".equals(String.valueOf(body.get("primaryFlag")));
        String remarks = body.get("remarks") == null ? null : String.valueOf(body.get("remarks"));
        engineerSalesService.assign(id, salesUserId, primaryFlag, remarks);
        return ApiResult.success(true);
    }

    /** 主担当の変更 */
    @PutMapping("/{id}/sales-reps/{assignmentId}/primary")
    public ApiResult<Boolean> setPrimary(@PathVariable Long id, @PathVariable Long assignmentId) {
        engineerSalesService.setPrimary(id, assignmentId);
        return ApiResult.success(true);
    }

    /** 担当の解除（released_at 設定） */
    @DeleteMapping("/{id}/sales-reps/{assignmentId}")
    public ApiResult<Boolean> release(@PathVariable Long id, @PathVariable Long assignmentId) {
        engineerSalesService.release(id, assignmentId);
        return ApiResult.success(true);
    }

    /** 営業ユーザー選択肢（担当営業セレクト用） */
    @GetMapping("/sales-user-options")
    public ApiResult<List<SalesUserOptionDto>> salesUserOptions() {
        return ApiResult.success(engineerSalesService.salesUserOptions());
    }
}
