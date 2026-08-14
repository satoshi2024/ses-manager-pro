package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceCapabilityDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R23-P1-01 §5: capabilityのserver計算テスト。
 * 管理者=全操作 / HR・マネージャー=approval・event historyのみ /
 * 営業・要員=全操作不可（JS role判定はauthorizationに使わない）。
 */
class ComplianceCapabilityServiceTest {

    private final ComplianceCapabilityService service = new ComplianceCapabilityService();

    @Test
    void 管理者は全capabilityを持つ() {
        ComplianceCapabilityDto dto = service.forRole("管理者");
        assertTrue(dto.isCanManageMapping());
        assertTrue(dto.isCanManageReviewerType());
        assertTrue(dto.isCanManagePolicy());
        assertTrue(dto.isCanManageAssignment());
        assertTrue(dto.isCanApprove());
        assertTrue(dto.isCanManageExternalReview());
        assertTrue(dto.isCanVerify());
        assertTrue(dto.isCanManageActive());
        assertTrue(dto.isCanViewEventHistory());
    }

    @Test
    void HRはapprovalとeventHistoryのみ可能で管理操作は不可() {
        ComplianceCapabilityDto dto = service.forRole("HR");
        assertTrue(dto.isCanApprove());
        assertTrue(dto.isCanViewEventHistory());
        assertFalse(dto.isCanManageMapping());
        assertFalse(dto.isCanManageReviewerType());
        assertFalse(dto.isCanManagePolicy());
        assertFalse(dto.isCanManageAssignment());
        assertFalse(dto.isCanManageExternalReview());
        assertFalse(dto.isCanVerify());
        assertFalse(dto.isCanManageActive());
    }

    @Test
    void マネージャーはHRと同じcapabilityを持つ() {
        ComplianceCapabilityDto dto = service.forRole("マネージャー");
        assertTrue(dto.isCanApprove());
        assertTrue(dto.isCanViewEventHistory());
        assertFalse(dto.isCanManageMapping());
        assertFalse(dto.isCanVerify());
    }

    @Test
    void 営業と要員は全capability不可() {
        ComplianceCapabilityDto sales = service.forRole("営業");
        ComplianceCapabilityDto member = service.forRole("要員");
        assertFalse(sales.isCanApprove());
        assertFalse(sales.isCanManageMapping());
        assertFalse(sales.isCanVerify());
        assertFalse(member.isCanApprove());
        assertFalse(member.isCanViewEventHistory());
    }
}
