package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.entity.SystemConfig;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * システム設定API（管理者のみ。SecurityConfigで /api/system-configs/** を hasRole("管理者") に制限）。
 */
@RestController
@RequestMapping("/api/system-configs")
@RequiredArgsConstructor
public class SystemConfigApiController {

    /** マスキング表示対象キー（Webhook URLは漏洩すると第三者が投稿可能になる機微情報のため） */
    private static final Set<String> MASKED_KEYS = Set.of("notification.webhook-url");

    /** マスキング済みであることを示すプレースホルダー値。保存時にこの値のまま送られてきた項目は更新しない。 */
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

    /** scope設定変更時のDashboardキャッシュ世代更新。テストスライスでは未配置でも動作させる。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.security.ScopeChangeInvalidator scopeChangeInvalidator;

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
        String previousScopeValue = systemConfigService.all().stream()
                .filter(c -> "scope.sales-own-data-only".equals(c.getConfigKey()))
                .map(SystemConfig::getConfigValue)
                .findFirst()
                .orElse(null);
        String finalScopeValue = previousScopeValue;
        if (configs != null) {
            for (SystemConfig c : configs) {
                if (isSystemManagedKey(c.getConfigKey())) {
                    throw BusinessException.of(400, "error.config.systemKey");
                }
                if (MASKED_KEYS.contains(c.getConfigKey()) && MASK_PLACEHOLDER.equals(c.getConfigValue())) {
                    // 画面上で変更されていない（マスキング表示のまま）ので既存値を維持する
                    continue;
                }
                if ("scope.sales-own-data-only".equals(c.getConfigKey())) {
                    finalScopeValue = c.getConfigValue();
                }
                systemConfigService.put(c.getConfigKey(), c.getConfigValue(), c.getDescription());
            }
        }
        if (!Objects.equals(previousScopeValue, finalScopeValue) && scopeChangeInvalidator != null) {
            // ScopeChangeInvalidatorはトランザクションのafterCommitでのみ世代を進める。
            scopeChangeInvalidator.invalidate();
        }
        return ApiResult.success(Boolean.TRUE);
    }
}
