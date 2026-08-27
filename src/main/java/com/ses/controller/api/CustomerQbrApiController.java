package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.servicedesk.CustomerQbrCreateRequest;
import com.ses.dto.servicedesk.CustomerQbrDto;
import com.ses.dto.servicedesk.CustomerQbrUpdateRequest;
import com.ses.service.servicedesk.CustomerQbrService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 顧客定例会(QBR)管理 API (/api/customer-success/qbrs)
 */
@RestController
@RequestMapping({"/api/customer-success/qbrs", "/api/service-desk/qbrs"})
@RequiredArgsConstructor
public class CustomerQbrApiController {

    private final CustomerQbrService qbrService;

    /**
     * 定例会一覧検索
     */
    @GetMapping
    public ApiResult<Page<CustomerQbrDto>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String keyword) {
        Page<CustomerQbrDto> result = qbrService.searchQbrs(current, size, customerId, keyword);
        return ApiResult.success(result);
    }

    /**
     * 定例会詳細取得
     */
    @GetMapping("/{id}")
    public ApiResult<CustomerQbrDto> get(@PathVariable Long id) {
        CustomerQbrDto dto = qbrService.getQbr(id);
        return ApiResult.success(dto);
    }

    /**
     * 定例会登録
     */
    @PostMapping
    public ApiResult<CustomerQbrDto> create(@Valid @RequestBody CustomerQbrCreateRequest req) {
        Long userId = SecurityUtils.currentUserId();
        CustomerQbrDto created = qbrService.createQbr(req, userId);
        return ApiResult.success(created);
    }

    /**
     * 定例会更新
     */
    @PutMapping("/{id}")
    public ApiResult<Void> update(@PathVariable Long id, @Valid @RequestBody CustomerQbrUpdateRequest req) {
        qbrService.updateQbr(id, req);
        return ApiResult.success(null);
    }

    /**
     * 定例会削除
     */
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        qbrService.deleteQbr(id);
        return ApiResult.success(null);
    }
}
