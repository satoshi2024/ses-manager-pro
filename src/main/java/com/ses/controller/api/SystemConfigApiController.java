package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.entity.SystemConfig;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * システム設定API（管理者のみ。SecurityConfigで /api/system-configs/** を hasRole("管理者") に制限）。
 */
@RestController
@RequestMapping("/api/system-configs")
@RequiredArgsConstructor
@org.springframework.security.access.prepost.PreAuthorize("hasRole('管理者')")
public class SystemConfigApiController {

    /** マスキング表示対象キー（Webhook URLは漏洩すると第三者が投稿可能になる機微情報のため） */
    private static final Set<String> MASKED_KEYS = Set.of("notification.webhook-url");

    /** マスキング済みであることを示すプレースホルダー値。 */
    private static final String MASK_PLACEHOLDER = "********";

    /** システム管理キー（画面からの直接編集・閲覧を禁止するキー） */
    private static final Set<String> SYSTEM_MANAGED_KEYS = Set.of("closing.confirmed-months",
            "attendance.sync.freee.cursor", "attendance.sync.last-result",
            "attendance.discrepancy.confirmed");

    /** 法人別cursor等、動的システム管理キーのprefix（R5-P2-03） */
    private static final String SYSTEM_MANAGED_PREFIX = "attendance.sync.freee.cursor.le.";

    private boolean isSystemManagedKey(String key) {
        return SYSTEM_MANAGED_KEYS.contains(key) || key.startsWith(SYSTEM_MANAGED_PREFIX);
    }

    private final SystemConfigService systemConfigService;

    /** 全設定一覧（機微情報はマスキングして返す） */
    @GetMapping
    public ApiResult<List<SystemConfig>> list() {
        List<SystemConfig> configs = systemConfigService.all();
        configs.removeIf(c -> isSystemManagedKey(c.getConfigKey()));

        for (SystemConfig c : configs) {
            if (MASKED_KEYS.contains(c.getConfigKey()) && StringUtils.hasText(c.getConfigValue())) {
                c.setConfigValue(MASK_PLACEHOLDER);
            }
        }
        return ApiResult.success(configs);
    }

    @PutMapping
    public ApiResult<Boolean> update(@RequestBody List<SystemConfig> configs) {
        systemConfigService.updateAll(configs);
        return ApiResult.success(Boolean.TRUE);
    }
}
