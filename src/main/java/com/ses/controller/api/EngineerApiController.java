package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.entity.Engineer;
import com.ses.service.EngineerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * エンジニアAPIコントローラー
 */
@RestController
@RequestMapping("/api/engineers")
@RequiredArgsConstructor
public class EngineerApiController {

    private final EngineerService engineerService;

    /**
     * エンジニア一覧（ページネーション）
     */
    @GetMapping
    public ApiResult<Page<Engineer>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        Page<Engineer> page = new Page<>(current, size);
        return ApiResult.success(engineerService.page(page));
    }

    /**
     * エンジニア詳細
     */
    @GetMapping("/{id}")
    public ApiResult<Engineer> getById(@PathVariable Long id) {
        return ApiResult.success(engineerService.getById(id));
    }

    /**
     * エンジニア登録
     */
    @PostMapping
    public ApiResult<Boolean> save(@RequestBody Engineer engineer) {
        return ApiResult.success(engineerService.save(engineer));
    }

    /**
     * エンジニア更新
     */
    @PutMapping
    public ApiResult<Boolean> update(@RequestBody Engineer engineer) {
        return ApiResult.success(engineerService.updateById(engineer));
    }

    /**
     * エンジニア削除
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        return ApiResult.success(engineerService.removeById(id));
    }
}
