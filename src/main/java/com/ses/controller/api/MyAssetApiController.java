package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.LicenseAssignment;
import com.ses.mapper.SysUserMapper;
import com.ses.service.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/my/assets")
@RequiredArgsConstructor
@PreAuthorize("hasRole('要員')")
public class MyAssetApiController {

    private final AssetAssignmentService assetAssignmentService;
    private final AssetService assetService;
    private final ExternalAccountService externalAccountService;
    private final LicenseService licenseService;
    private final EngineerAccountLinkService engineerAccountLinkService;

    private final SysUserMapper sysUserMapper;

    private Long resolveCurrentEngineerId() {
        Long userId = resolveCurrentUserId();
        if (userId == null) {
            throw new BusinessException(401, "未ログイン状態です。");
        }
        Long engineerId = engineerAccountLinkService.findEngineerIdByUserId(userId);
        if (engineerId == null) {
            throw new BusinessException(403, "要員アカウントに紐付いていません。");
        }
        return engineerId;
    }

    private Long resolveCurrentUserId() {
        Long userId = SecurityUtils.currentUserId();
        if (userId != null) {
            return userId;
        }
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            com.ses.entity.SysUser user = sysUserMapper.selectByUsername(auth.getName());
            if (user != null) {
                return user.getId();
            }
        }
        return null;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> getMyAssetsSummary() {
        Long engineerId = resolveCurrentEngineerId();

        // 1. 有効貸与資産
        List<AssetAssignment> assignments = assetAssignmentService.getActiveAssignmentsByAssignee("ENGINEER", engineerId);
        List<Map<String, Object>> assetList = new ArrayList<>();
        for (AssetAssignment as : assignments) {
            Asset asset = assetService.getById(as.getAssetId());
            if (asset != null) {
                Map<String, Object> map = new HashMap<>();
                map.put("assignmentId", as.getId());
                map.put("assetId", asset.getId());
                map.put("assetTag", asset.getAssetTag());
                map.put("assetName", asset.getAssetName());
                map.put("category", asset.getCategory());
                map.put("serialNo", asset.getSerialNo());
                map.put("startDate", as.getStartDate());
                map.put("expectedReturnDate", as.getExpectedReturnDate());
                map.put("status", asset.getStatus());
                map.put("note", as.getNote());
                assetList.add(map);
            }
        }

        // 2. 有効外部アカウント参照
        List<ExternalAccountReference> accounts = externalAccountService.getActiveAccountsByAssignee("ENGINEER", engineerId);

        // 3. 有効ライセンス割当
        List<LicenseAssignment> licenses = licenseService.getActiveAssignmentsByAssignee("ENGINEER", engineerId);

        Map<String, Object> data = new HashMap<>();
        data.put("assets", assetList);
        data.put("accounts", accounts);
        data.put("licenses", licenses);

        return ApiResult.success(data);
    }

    @PostMapping("/report-lost")
    public ApiResult<Asset> reportLostSelf(@RequestBody LostReportRequest req) {
        Long engineerId = resolveCurrentEngineerId();
        Long userId = SecurityUtils.currentUserId();

        if (req.getAssetId() == null) {
            throw new BusinessException("資産IDは必須です。");
        }

        // 本人に貸与されている資産か検証
        List<AssetAssignment> activeList = assetAssignmentService.getActiveAssignmentsByAssignee("ENGINEER", engineerId);
        boolean isMine = activeList.stream().anyMatch(a -> a.getAssetId().equals(req.getAssetId()));
        if (!isMine) {
            throw new BusinessException(403, "ご自身に貸与されている資産のみ紛失報告が可能です。");
        }

        Asset lostAsset = assetService.reportLost(req.getAssetId(), req.getIncidentDetails(), userId, null);

        return ApiResult.success(lostAsset);
    }

    @Data
    public static class LostReportRequest {
        private Long assetId;
        private String incidentDetails;
    }
}
