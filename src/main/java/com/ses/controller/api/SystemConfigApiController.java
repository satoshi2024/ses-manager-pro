package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.entity.SystemConfig;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * システム設定API（管理者のみ。SecurityConfigで /api/system-configs/** を hasRole("管理者") に制限）。
 */
@RestController
@RequestMapping("/api/system-configs")
@RequiredArgsConstructor
public class SystemConfigApiController {

    private final SystemConfigService systemConfigService;

    /** 全設定一覧 */
    @GetMapping
    public ApiResult<List<SystemConfig>> list() {
        return ApiResult.success(systemConfigService.all());
    }

    /** 設定の一括更新 */
    @PutMapping
    public ApiResult<Boolean> update(@RequestBody List<SystemConfig> configs) {
        if (configs != null) {
            for (SystemConfig c : configs) {
                systemConfigService.put(c.getConfigKey(), c.getConfigValue(), c.getDescription());
            }
        }
        return ApiResult.success(Boolean.TRUE);
    }
}
