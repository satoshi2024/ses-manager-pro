package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.entity.Customer;
import com.ses.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 顧客APIコントローラー
 */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerApiController {

    private final CustomerService customerService;

    /**
     * 顧客一覧（ページネーション）
     */
    @GetMapping
    public ApiResult<Page<Customer>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        Page<Customer> page = new Page<>(current, size);
        return ApiResult.success(customerService.page(page));
    }

    /**
     * 顧客詳細
     */
    @GetMapping("/{id}")
    public ApiResult<Customer> getById(@PathVariable Long id) {
        return ApiResult.success(customerService.getById(id));
    }

    /**
     * 顧客登録
     */
    @PostMapping
    public ApiResult<Boolean> save(@RequestBody Customer customer) {
        return ApiResult.success(customerService.save(customer));
    }

    /**
     * 顧客更新
     */
    @PutMapping
    public ApiResult<Boolean> update(@RequestBody Customer customer) {
        return ApiResult.success(customerService.updateById(customer));
    }

    /**
     * 顧客削除
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        return ApiResult.success(customerService.removeById(id));
    }
}
