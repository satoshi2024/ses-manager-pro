package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.compliance.ContractComplianceProfileDetailDto;
import com.ses.service.ContractComplianceProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 契約compliance profile API（T063 A1）。
 * /api/contracts 配下のため契約メニュー（4管理ロール）の権限で保護される。
 * field maskはserviceがroleに応じて適用する（design §5.3）。
 * PUTはfull DTO必須（省略reject）＋楽観ロックCAS＋masked roleのsensitive変更reject。
 */
@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
public class ContractComplianceProfileApiController {

    private final ContractComplianceProfileService contractComplianceProfileService;

    @GetMapping("/{id}/compliance-profile")
    public ApiResult<ContractComplianceProfileDetailDto> detail(@PathVariable Long id) {
        return ApiResult.success(contractComplianceProfileService.detail(id));
    }

    @PutMapping("/{id}/compliance-profile")
    public ApiResult<ContractComplianceProfileDetailDto> save(@PathVariable Long id,
                                                              @RequestBody String rawBody) {
        return ApiResult.success(contractComplianceProfileService.save(id, rawBody));
    }
}
