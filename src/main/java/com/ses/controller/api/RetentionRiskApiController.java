package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.engineerfollowup.RetentionRiskDto;
import com.ses.service.RetentionRiskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 要員の定着リスクスコアAPI（要員詳細のフォロー履歴カード用）
 */
@RestController
@RequestMapping("/api/engineers/{engineerId}/retention-risk")
@RequiredArgsConstructor
public class RetentionRiskApiController {

    private final RetentionRiskService retentionRiskService;
    private final com.ses.service.security.DataScopeService dataScopeService;

    @GetMapping
    public ApiResult<RetentionRiskDto> get(@PathVariable Long engineerId) {
        dataScopeService.assertAllowedEngineer(engineerId);
        return ApiResult.success(retentionRiskService.score(engineerId));
    }
}
