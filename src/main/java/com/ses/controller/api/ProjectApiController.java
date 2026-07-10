package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.entity.Project;
import com.ses.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 案件APIコントローラー
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectApiController {

    private final ProjectService projectService;

    /**
     * 案件一覧（ページネーション）
     */
    @GetMapping
    public ApiResult<Page<Project>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        Page<Project> page = new Page<>(current, size);
        return ApiResult.success(projectService.page(page));
    }

    /**
     * 案件詳細
     */
    @GetMapping("/{id}")
    public ApiResult<Project> getById(@PathVariable Long id) {
        return ApiResult.success(projectService.getById(id));
    }

    /**
     * 案件登録
     */
    @PostMapping
    public ApiResult<Boolean> save(@RequestBody Project project) {
        return ApiResult.success(projectService.save(project));
    }

    /**
     * 案件更新
     */
    @PutMapping
    public ApiResult<Boolean> update(@RequestBody Project project) {
        return ApiResult.success(projectService.updateById(project));
    }

    /**
     * 案件削除
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        return ApiResult.success(projectService.removeById(id));
    }
}
