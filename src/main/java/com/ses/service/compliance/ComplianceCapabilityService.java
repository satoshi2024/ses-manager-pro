package com.ses.service.compliance;

import com.ses.common.util.SecurityUtils;
import com.ses.dto.compliance.ComplianceCapabilityDto;
import org.springframework.stereotype.Component;

/**
 * R23-P1-01 §5: capabilityのserver計算。JS role判定をauthorizationに使わない。
 * <ul>
 *   <li>管理者: 全操作可能</li>
 *   <li>HR: approval・policy閲覧・event history閲覧（approval画面へ入れる・serviceでassignment一致必須）</li>
 *   <li>マネージャー: approval・閲覧（同左）</li>
 *   <li>営業・要員: 一切不可（ページ自体403）</li>
 * </ul>
 */
@Component
public class ComplianceCapabilityService {

    public ComplianceCapabilityDto current() {
        return forRole(SecurityUtils.currentRole());
    }

    public ComplianceCapabilityDto forRole(String role) {
        ComplianceCapabilityDto dto = new ComplianceCapabilityDto();
        if ("管理者".equals(role)) {
            dto.setCanManageMapping(true);
            dto.setCanManageReviewerType(true);
            dto.setCanManagePolicy(true);
            dto.setCanManageAssignment(true);
            dto.setCanApprove(true);
            dto.setCanManageExternalReview(true);
            dto.setCanVerify(true);
            dto.setCanManageActive(true);
            dto.setCanViewEventHistory(true);
        } else if ("HR".equals(role) || "マネージャー".equals(role)) {
            dto.setCanApprove(true);
            dto.setCanViewEventHistory(true);
        }
        return dto;
    }
}
