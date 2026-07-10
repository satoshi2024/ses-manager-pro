package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.entity.SkillTag;
import com.ses.service.SkillTagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * スキルタグAPIコントローラー
 */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillTagApiController {

    private final SkillTagService skillTagService;

    /**
     * スキルタグ一覧（ページネーション）
     */
    @GetMapping
    public ApiResult<Page<SkillTag>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        Page<SkillTag> page = new Page<>(current, size);
        return ApiResult.success(skillTagService.page(page));
    }

    /**
     * スキルタグ詳細
     */
    @GetMapping("/{id}")
    public ApiResult<SkillTag> getById(@PathVariable Long id) {
        return ApiResult.success(skillTagService.getById(id));
    }

    /**
     * スキルタグ登録
     */
    @PostMapping
    public ApiResult<Boolean> save(@RequestBody SkillTag skillTag) {
        return ApiResult.success(skillTagService.save(skillTag));
    }

    /**
     * スキルタグ更新
     */
    @PutMapping
    public ApiResult<Boolean> update(@RequestBody SkillTag skillTag) {
        return ApiResult.success(skillTagService.updateById(skillTag));
    }

    /**
     * スキルタグ削除
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        return ApiResult.success(skillTagService.removeById(id));
    }
}
