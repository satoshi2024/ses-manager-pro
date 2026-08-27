package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.lifecycle.*;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.lifecycle.LifecycleCaseService;
import com.ses.service.lifecycle.LifecycleTaskService;
import com.ses.service.lifecycle.LifecycleTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * ライフサイクル管理 REST API コントローラー
 */
@RestController
@RequestMapping("/api/lifecycle")
@RequiredArgsConstructor
public class LifecycleApiController {

    private final LifecycleCaseService caseService;
    private final LifecycleTaskService taskService;
    private final LifecycleTemplateService templateService;
    private final SysUserMapper sysUserMapper;

    private SysUser getCurrentUser() {
        Long userId = SecurityUtils.currentUserId();
        if (userId != null) {
            return sysUserMapper.selectById(userId);
        }
        String username = SecurityUtils.currentUsername();
        if (username != null) {
            return sysUserMapper.selectByUsername(username);
        }
        return null;
    }

    private Long getCurrentUserId() {
        Long userId = SecurityUtils.currentUserId();
        if (userId != null) {
            return userId;
        }
        SysUser user = getCurrentUser();
        return user != null ? user.getId() : null;
    }

    /**
     * 案件一覧取得
     */
    @GetMapping("/cases")
    public ApiResult<List<LifecycleCaseDto>> listCases(
            @RequestParam(value = "lifecycleType", required = false) String lifecycleType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "engineerId", required = false) Long engineerId,
            @RequestParam(value = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(value = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        SysUser user = getCurrentUser();
        List<LifecycleCaseDto> list = caseService.listCases(lifecycleType, status, engineerId, fromDate, toDate, user);
        return ApiResult.success(list);
    }

    /**
     * 案件詳細取得
     */
    @GetMapping("/cases/{id}")
    public ApiResult<LifecycleCaseDto> getCaseDetail(@PathVariable("id") Long id) {
        SysUser user = getCurrentUser();
        LifecycleCaseDto detail = caseService.getCaseDetail(id, user);
        return ApiResult.success(detail);
    }

    /**
     * 案件起票
     */
    @PostMapping("/cases")
    public ApiResult<LifecycleCaseDto> createCase(@Valid @RequestBody CreateLifecycleCaseCommand cmd) {
        Long userId = getCurrentUserId();
        LifecycleCaseDto created = caseService.createCase(userId, cmd);
        return ApiResult.success("案件を起票しました", created);
    }

    /**
     * 案件保留
     */
    @PostMapping("/cases/{id}/hold")
    public ApiResult<Void> holdCase(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, String> body) {
        Long userId = getCurrentUserId();
        String reason = body != null ? body.get("reason") : "";
        caseService.holdCase(id, userId, reason);
        return ApiResult.success("案件を保留にしました", null);
    }

    /**
     * 案件再開
     */
    @PostMapping("/cases/{id}/resume")
    public ApiResult<Void> resumeCase(@PathVariable("id") Long id) {
        Long userId = getCurrentUserId();
        caseService.resumeCase(id, userId);
        return ApiResult.success("案件を再開しました", null);
    }

    /**
     * 案件完了確定
     */
    @PostMapping("/cases/{id}/complete")
    public ApiResult<Void> completeCase(@PathVariable("id") Long id) {
        Long userId = getCurrentUserId();
        caseService.completeCase(id, userId);
        return ApiResult.success("案件を完了しました", null);
    }

    /**
     * 案件中止
     */
    @PostMapping("/cases/{id}/cancel")
    public ApiResult<Void> cancelCase(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, String> body) {
        Long userId = getCurrentUserId();
        String reason = body != null ? body.get("reason") : "";
        caseService.cancelCase(id, userId, reason);
        return ApiResult.success("案件を中止しました", null);
    }

    /**
     * 退社ゲート検証
     */
    @GetMapping("/cases/{id}/gate")
    public ApiResult<ResignationGateResultDto> evaluateGate(@PathVariable("id") Long id) {
        SysUser user = getCurrentUser();
        ResignationGateResultDto result = caseService.evaluateResignationGate(id, user);
        return ApiResult.success(result);
    }

    /**
     * タスク開始
     */
    @PostMapping("/tasks/{id}/start")
    public ApiResult<Void> startTask(@PathVariable("id") Long id) {
        Long userId = getCurrentUserId();
        taskService.startTask(id, userId);
        return ApiResult.success("タスクを開始しました", null);
    }

    /**
     * タスク完了
     */
    @PostMapping("/tasks/{id}/complete")
    public ApiResult<Void> completeTask(@PathVariable("id") Long id,
                                        @RequestBody(required = false) CompleteLifecycleTaskCommand cmd) {
        Long userId = getCurrentUserId();
        taskService.completeTask(id, userId, cmd);
        return ApiResult.success("タスクを完了しました", null);
    }

    /**
     * タスク免除 (例外処理)
     */
    @PostMapping("/tasks/{id}/waive")
    public ApiResult<Void> waiveTask(@PathVariable("id") Long id,
                                     @RequestBody Map<String, Object> body) {
        Long userId = getCurrentUserId();
        String reason = body != null ? (String) body.get("reason") : "";
        Long approvalRequestId = body != null && body.get("approvalRequestId") != null
                ? Long.parseLong(body.get("approvalRequestId").toString()) : null;
        taskService.waiveTask(id, userId, approvalRequestId, reason);
        return ApiResult.success("タスクを免除しました", null);
    }

    /**
     * タスク担当者再割当
     */
    @PostMapping("/tasks/{id}/reassign")
    public ApiResult<Void> reassignTask(@PathVariable("id") Long id,
                                        @RequestBody Map<String, Object> body) {
        Long userId = getCurrentUserId();
        Long newAssigneeUserId = body != null && body.get("newAssigneeUserId") != null
                ? Long.parseLong(body.get("newAssigneeUserId").toString()) : null;
        String reason = body != null ? (String) body.get("reason") : "";
        taskService.reassignTask(id, newAssigneeUserId, userId, reason);
        return ApiResult.success("タスク担当者を変更しました", null);
    }

    /**
     * 完了済みタスクの訂正記録
     * <p>
     * タスク自体のステータスは変更せず、{@code t_lifecycle_event} に
     * {@code TASK_CORRECTION} イベントを追記する（R4.4準拠）。
     */
    @PostMapping("/tasks/{id}/correct")
    public ApiResult<Void> correctTask(@PathVariable("id") Long id,
                                       @RequestBody Map<String, Object> body) {
        Long userId = getCurrentUserId();
        String correctionNote = body != null ? (String) body.get("correctionNote") : null;
        taskService.correctCompletedTask(id, userId, correctionNote);
        return ApiResult.success("訂正記録を追記しました", null);
    }

    /**
     * 自担当の未完了タスク一覧
     */
    @GetMapping("/tasks/my-pending")
    public ApiResult<List<LifecycleTaskDto>> getMyPendingTasks() {
        SysUser user = getCurrentUser();
        List<LifecycleTaskDto> tasks = taskService.getMyPendingTasks(user);
        return ApiResult.success(tasks);
    }

    /**
     * テンプレート一覧取得
     */
    @GetMapping("/templates")
    public ApiResult<List<LifecycleTemplateDto>> listTemplates(
            @RequestParam(value = "templateType", required = false) String templateType,
            @RequestParam(value = "status", required = false) String status) {
        List<LifecycleTemplateDto> list = templateService.listTemplates(templateType, status);
        return ApiResult.success(list);
    }

    /**
     * テンプレート詳細取得
     */
    @GetMapping("/templates/{id}")
    public ApiResult<LifecycleTemplateDto> getTemplateDetail(@PathVariable("id") Long id) {
        LifecycleTemplateDto detail = templateService.getTemplateDetail(id);
        return ApiResult.success(detail);
    }

    /**
     * テンプレート新規作成
     */
    @PostMapping("/templates")
    public ApiResult<LifecycleTemplateDto> createTemplate(@Valid @RequestBody LifecycleTemplateDto dto) {
        Long userId = getCurrentUserId();
        LifecycleTemplateDto created = templateService.createTemplate(dto, userId);
        return ApiResult.success("テンプレートを作成しました", created);
    }

    /**
     * テンプレート改定 (新バージョン作成)
     */
    @PutMapping("/templates/{id}")
    public ApiResult<LifecycleTemplateDto> updateTemplate(@PathVariable("id") Long id,
                                                          @Valid @RequestBody LifecycleTemplateDto dto) {
        Long userId = getCurrentUserId();
        LifecycleTemplateDto updated = templateService.updateTemplate(id, dto, userId);
        return ApiResult.success("テンプレートを改定しました", updated);
    }

    /**
     * テンプレートステータス切替 (ACTIVE / INACTIVE)
     */
    @PostMapping("/templates/{id}/toggle-status")
    public ApiResult<Void> toggleTemplateStatus(@PathVariable("id") Long id,
                                                @RequestBody Map<String, String> body) {
        Long userId = getCurrentUserId();
        String status = body != null ? body.get("status") : "INACTIVE";
        templateService.toggleStatus(id, status, userId);
        return ApiResult.success("ステータスを更新しました", null);
    }
}
