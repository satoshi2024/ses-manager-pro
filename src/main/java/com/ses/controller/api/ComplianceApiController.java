package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.compliance.ContractComplianceDto;
import com.ses.service.compliance.LaborComplianceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 労務コンプライアンスリスク一覧API（FR-10）。管理者/マネージャーのみアクセス可
 * （t_role_menu で compliance メニューを両ロールにのみ割当。MenuPermissionFilter が他ロールを403で遮断）。
 */
@RestController
@RequestMapping("/api/compliance")
@RequiredArgsConstructor
public class ComplianceApiController {

    private final LaborComplianceService laborComplianceService;

    @GetMapping("/findings")
    public ApiResult<List<ContractComplianceDto>> findings() {
        return ApiResult.success(laborComplianceService.findCurrentRisks());
    }
}
