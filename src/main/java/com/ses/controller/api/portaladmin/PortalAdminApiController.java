package com.ses.controller.api.portaladmin;

import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.portal.PortalInvitationAdminDto;
import com.ses.dto.portal.PortalSessionAdminDto;
import com.ses.dto.portal.PortalUserAdminDto;
import com.ses.entity.PortalAccessLog;
import com.ses.entity.PortalInvitation;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalUser;
import com.ses.service.portal.PortalAdminService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * portal管理API（/api/portal-admin/**。B1）。
 * menu portal-admin（管理者・営業）配下。管理者は全件、営業は自担当顧客のportal組織のみ（DataScope。
 * design §6.2）。BP組織の管理・組織作成・MFA reset・規約発行は管理者のみ。HR/要員はメニュー権限で到達不可。
 */
@RestController
@RequestMapping("/api/portal-admin")
@RequiredArgsConstructor
public class PortalAdminApiController {

    private final PortalAdminService adminService;
    private final com.ses.service.security.DataScopeService dataScopeService;

    private boolean isAdmin() {
        return "管理者".equals(SecurityUtils.currentRole());
    }

    private boolean isSales() {
        return "営業".equals(SecurityUtils.currentRole());
    }

    /** 営業の可視顧客ID集合（DataScope未発動なら全顧客=null）。 */
    private Set<Long> salesCustomerIds() {
        if (dataScopeService.isSalesDataScoped()) {
            Set<Long> ids = dataScopeService.allowedCustomerIds();
            return ids == null ? Set.of() : ids;
        }
        return null; // 全顧客
    }

    /** 営業の可視portal組織ID集合（null=全件（管理者）、空集合=0件）。一覧のSQL境界に使う。 */
    private Set<Long> salesOrgIds() {
        if (isAdmin()) {
            return null;
        }
        Set<Long> customerIds = salesCustomerIds();
        return adminService.orgs(1, 1000, customerIds, false).getRecords().stream()
                .map(PortalOrganization::getId)
                .collect(java.util.stream.Collectors.toSet());
    }

    // ===== 組織 =====

    @GetMapping("/orgs")
    public ApiResult<com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalOrganization>> orgs(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        boolean admin = isAdmin();
        return ApiResult.success(adminService.orgs(current, size,
                admin ? null : salesCustomerIds(), admin));
    }

    @PostMapping("/orgs")
    public ApiResult<PortalOrganization> createOrg(@RequestBody Map<String, Object> body) {
        requireAdmin();
        return ApiResult.success(adminService.createOrg(
                (String) body.get("type"),
                longOrNull(body.get("customerId")),
                longOrNull(body.get("bpCompanyId"))));
    }

    @PutMapping("/orgs/{id}/status")
    public ApiResult<Void> setOrgStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        requireAdmin();
        adminService.setOrgStatus(id, (String) body.get("status"));
        return ApiResult.success(null);
    }

    // ===== user（停止/再開・org-adminは管理者のみ。営業は参照のみ: design §6.2） =====

    @GetMapping("/orgs/{orgId}/users")
    public ApiResult<com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalUserAdminDto>> users(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        assertOrgVisible(orgId);
        var page = adminService.users(current, size, orgId);
        var dtoPage = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalUserAdminDto>(
                page.getCurrent(), page.getSize(), page.getTotal());
        dtoPage.setRecords(page.getRecords().stream().map(PortalUserAdminDto::from).toList());
        return ApiResult.success(dtoPage);
    }

    @PutMapping("/users/{id}/status")
    public ApiResult<Void> setUserStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        requireAdmin();
        assertUserVisible(id);
        adminService.setUserStatus(id, (String) body.get("status"));
        return ApiResult.success(null);
    }

    @PostMapping("/users/{id}/mfa-reset")
    public ApiResult<Void> resetUserMfa(@PathVariable Long id) {
        requireAdmin();
        adminService.resetUserMfa(id);
        return ApiResult.success(null);
    }

    @PutMapping("/users/{id}/org-admin")
    public ApiResult<Void> setOrgAdmin(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        requireAdmin();
        assertUserVisible(id);
        adminService.setUserOrgAdmin(id, Boolean.TRUE.equals(body.get("orgAdmin")));
        return ApiResult.success(null);
    }

    // ===== 招待（発行は管理者のみ。営業は自担当顧客の一覧参照のみ: design §6.2） =====

    @PostMapping("/orgs/{orgId}/invitations")
    public ApiResult<PortalInvitationAdminDto> createInvitation(@PathVariable Long orgId,
                                                                @RequestBody Map<String, Object> body,
                                                                HttpServletRequest request) {
        requireAdmin();
        assertOrgVisible(orgId);
        return ApiResult.success(PortalInvitationAdminDto.from(adminService.createInvitation(orgId,
                (String) body.get("email"), (String) body.get("role"), request)));
    }

    @GetMapping("/invitations")
    public ApiResult<com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalInvitation>> invitations(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long orgId) {
        if (orgId != null) {
            assertOrgVisible(orgId);
        }
        return ApiResult.success(adminService.invitations(current, size, orgId, salesOrgIds()));
    }

    // ===== session（失効は管理者のみ） =====

    @GetMapping("/users/{id}/sessions")
    public ApiResult<List<PortalSessionAdminDto>> sessions(@PathVariable Long id) {
        assertUserVisible(id);
        return ApiResult.success(adminService.sessions(id).stream()
                .map(PortalSessionAdminDto::from)
                .toList());
    }

    @PostMapping("/users/{id}/sessions/revoke")
    public ApiResult<Void> revokeSession(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        requireAdmin();
        assertUserVisible(id);
        adminService.revokeSession(longOrNull(body.get("sessionId")), id);
        return ApiResult.success(null);
    }

    // ===== access log =====

    @GetMapping("/access-logs")
    public ApiResult<com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalAccessLog>> accessLogs(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) String action) {
        if (orgId != null) {
            assertOrgVisible(orgId);
        }
        return ApiResult.success(adminService.accessLogs(current, size, orgId, action, salesOrgIds()));
    }

    // ===== 利用規約 =====

    @GetMapping("/terms")
    public ApiResult<Map<String, String>> terms() {
        return ApiResult.success(Map.of("version", adminService.currentTermsVersion()));
    }

    @PutMapping("/terms")
    public ApiResult<Map<String, String>> publishTerms(@RequestBody Map<String, Object> body) {
        requireAdmin();
        adminService.publishTerms((String) body.get("version"));
        return ApiResult.success(Map.of("version", adminService.currentTermsVersion()));
    }

    // ===== ヘルパー =====

    private void requireAdmin() {
        if (!isAdmin()) {
            throw com.ses.common.exception.BusinessException.of(403, "error.forbidden");
        }
    }

    /** 組織の可視性: 管理者は全件。営業は自担当顧客のCUSTOMER組織のみ（BP組織は404秘匿）。 */
    private void assertOrgVisible(Long orgId) {
        if (isAdmin()) {
            return;
        }
        if (!isSales()) {
            throw com.ses.common.exception.BusinessException.of(403, "error.forbidden");
        }
        PortalOrganization org = adminService.orgById(orgId);
        if (org == null || !"CUSTOMER".equals(org.getType())) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        Set<Long> customerIds = salesCustomerIds();
        if (customerIds != null && !customerIds.contains(org.getCustomerId())) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
    }

    /** userの可視性: 管理者は全件。営業は自担当顧客の組織のuserのみ。 */
    private void assertUserVisible(Long userId) {
        if (isAdmin()) {
            return;
        }
        if (!isSales()) {
            throw com.ses.common.exception.BusinessException.of(403, "error.forbidden");
        }
        PortalUser user = adminService.userById(userId);
        if (user == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        assertOrgVisible(user.getPortalOrgId());
    }

    private Long longOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
