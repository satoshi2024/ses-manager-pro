package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.lifecycle.CompleteLifecycleTaskCommand;
import com.ses.dto.lifecycle.LifecycleCaseDto;
import com.ses.dto.lifecycle.LifecycleTaskDto;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.entity.SysUser;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.lifecycle.LifecycleCaseService;
import com.ses.service.lifecycle.LifecycleTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 要員セルフサービス - マイライフサイクル REST API コントローラー
 */
@RestController
@RequestMapping("/api/my/lifecycle")
@RequiredArgsConstructor
public class MyLifecycleApiController {

    private final LifecycleCaseService caseService;
    private final LifecycleTaskService taskService;
    private final LifecycleCaseMapper caseMapper;
    private final LifecycleTaskMapper taskMapper;
    private final EngineerAccountLinkService linkService;
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

    private Long currentEngineerId() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            throw BusinessException.of(401, "error.unauthorized", "ログインが必要です");
        }
        Long engineerId = linkService.findEngineerIdByUserId(userId);
        if (engineerId == null) {
            throw BusinessException.of(403, "error.my.notLinked", "要員アカウントが紐付けられていません");
        }
        return engineerId;
    }

    /**
     * 要員本人の案件一覧取得 (社内タスクはフィルタリング済み)
     */
    @GetMapping("/cases")
    public ApiResult<List<LifecycleCaseDto>> listMyCases() {
        Long engineerId = currentEngineerId();
        SysUser user = getCurrentUser();
        List<LifecycleCaseDto> cases = caseService.listCases(null, null, engineerId, null, null, user);
        return ApiResult.success(cases);
    }

    /**
     * 要員本人の案件詳細取得 (社内タスクはフィルタリング済み)
     */
    @GetMapping("/cases/{id}")
    public ApiResult<LifecycleCaseDto> getMyCaseDetail(@PathVariable("id") Long id) {
        Long engineerId = currentEngineerId();
        LifecycleCase lcCase = caseMapper.selectById(id);
        if (lcCase == null || !engineerId.equals(lcCase.getEngineerId())) {
            throw BusinessException.of(404, "error.lifecycle.caseNotFound", "案件が見つかりません");
        }
        SysUser user = getCurrentUser();
        LifecycleCaseDto detail = caseService.getCaseDetail(id, user);
        return ApiResult.success(detail);
    }

    /**
     * 要員本人の未完了タスク一覧
     */
    @GetMapping("/tasks/pending")
    public ApiResult<List<LifecycleTaskDto>> getMyPendingTasks() {
        Long engineerId = currentEngineerId();
        SysUser user = getCurrentUser();
        List<LifecycleTaskDto> tasks = taskService.getMyPendingTasks(user).stream()
                .filter(t -> {
                    LifecycleCase c = caseMapper.selectById(t.getCaseId());
                    return c != null && engineerId.equals(c.getEngineerId());
                })
                .collect(Collectors.toList());
        return ApiResult.success(tasks);
    }

    /**
     * 要員本人によるタスク完了報告 (自己申告・提出物添付)
     */
    @PostMapping("/tasks/{id}/complete")
    public ApiResult<Void> completeMyTask(@PathVariable("id") Long taskId,
                                          @RequestBody(required = false) CompleteLifecycleTaskCommand cmd) {
        Long engineerId = currentEngineerId();
        Long userId = getCurrentUserId();

        LifecycleTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound", "タスクが見つかりません");
        }

        // 社内専用タスク（非公開タスク）への直接アクセスは403拒否
        if (task.getIsEngineerVisible() == null || task.getIsEngineerVisible() == 0) {
            throw BusinessException.of(403, "error.lifecycle.forbiddenTask", "このタスクへのアクセス権限がありません");
        }

        LifecycleCase lcCase = caseMapper.selectById(task.getCaseId());
        if (lcCase == null || !engineerId.equals(lcCase.getEngineerId())) {
            throw BusinessException.of(403, "error.lifecycle.forbiddenCase", "他要員の案件タスクは操作できません");
        }

        taskService.completeTask(taskId, userId, cmd);
        return ApiResult.success("タスクを完了しました", null);
    }
}
