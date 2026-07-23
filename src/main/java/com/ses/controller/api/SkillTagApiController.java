package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.result.ApiResult;
import com.ses.entity.SkillTag;
import com.ses.service.SkillTagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

/**
 * スキルタグAPIコントローラー
 *
 * パス: /api/skill-tags
 * 要員/案件のスキル選択UI（engineer-detail.js / engineer.js / project.js）が
 * この一覧を optgroup 構築のために呼び出す。呼び出し側は `res.data.forEach(...)` で
 * フラットな配列を前提にしているため、ページネーションではなく全件配列を返す。
 */
@RestController
@RequestMapping("/api/skill-tags")
@RequiredArgsConstructor
public class SkillTagApiController {

    private final SkillTagService skillTagService;

    /**
     * スキルタグ全件一覧（カテゴリ・スキル名順）
     */
    @GetMapping
    public ApiResult<List<SkillTag>> list() {
        LambdaQueryWrapper<SkillTag> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(SkillTag::getCategory).orderByAsc(SkillTag::getSkillName);
        return ApiResult.success(skillTagService.list(wrapper));
    }

    /**
     * スキルタグ詳細
     */
    @GetMapping("/{id}")
    public ApiResult<SkillTag> getById(@PathVariable Long id) {
        var entity = skillTagService.getById(id);
        if (entity == null) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(entity);
    }

    /**
     * スキルタグ登録
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('管理者', 'HR', 'マネージャー')")
    public ApiResult<Boolean> save(@Valid @RequestBody com.ses.dto.skill.SkillTagSaveDto skillDto) {
        SkillTag skillTag = new SkillTag();
        org.springframework.beans.BeanUtils.copyProperties(skillDto, skillTag);
        com.ses.common.util.EntityProtectUtil.protectForCreate(skillTag);
        return ApiResult.success(skillTagService.save(skillTag));
    }

    /**
     * スキルタグ更新
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('管理者', 'HR', 'マネージャー')")
    public ApiResult<Boolean> update(@PathVariable Long id, @Valid @RequestBody com.ses.dto.skill.SkillTagSaveDto skillDto) {
        SkillTag skillTag = new SkillTag();
        org.springframework.beans.BeanUtils.copyProperties(skillDto, skillTag);
        skillTag.setId(id);
        boolean success = skillTagService.updateById(skillTag);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }

    /**
     * スキルタグ削除
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('管理者', 'HR', 'マネージャー')")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        boolean success = skillTagService.removeById(id);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }
}
