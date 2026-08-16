package com.ses.service.portal;

import com.ses.entity.PortalAccessLog;
import com.ses.entity.PortalInvitation;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalSession;
import com.ses.entity.PortalUser;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Set;

/**
 * portal管理（B1: 内部管理者・営業がportal組織/user/招待/session/access logを管理）。
 * 管理者は全件、営業は自担当顧客のportal組織のみ（DataScope。design §6.2）。
 * HR/要員はメニュー権限で到達不可。
 */
public interface PortalAdminService {

    // ===== 組織 =====

    com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalOrganization> orgs(
            long current, long size, Set<Long> allowedCustomerIds, boolean fullAccess);

    /** 単純取得（可視性判定は呼出側）。 */
    PortalOrganization orgById(Long orgId);

    PortalUser userById(Long userId);

    PortalOrganization createOrg(String type, Long customerId, Long bpCompanyId);

    /** 停止/再開。停止時は全session失効（G3）。 */
    void setOrgStatus(Long orgId, String status);

    // ===== user =====

    com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalUser> users(
            long current, long size, Long orgId);

    /** 停止/再開。停止時は全session失効。 */
    void setUserStatus(Long userId, String status);

    /** MFA reset: TOTP/recovery codeをクリアし全session失効（G3）。 */
    void resetUserMfa(Long userId);

    /** 組織管理者権限（org.admin）の付与/剥奪。 */
    void setUserOrgAdmin(Long userId, boolean orgAdmin);

    // ===== 招待 =====

    PortalInvitation createInvitation(Long orgId, String email, String role, HttpServletRequest request);

    /**
     * 招待一覧。allowedOrgIds=null は全件（管理者）。営業は可視の顧客組織のみ
     * （SQL境界で絞る。design §6.2）。
     */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalInvitation> invitations(
            long current, long size, Long orgId, Set<Long> allowedOrgIds);

    // ===== session =====

    List<PortalSession> sessions(Long userId);

    void revokeSession(Long sessionId, Long userId);

    // ===== access log =====

    /**
     * 監査ログ一覧。allowedOrgIds=null は全件（管理者）。営業は可視の顧客組織のみ（design §6.2）。
     */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalAccessLog> accessLogs(
            long current, long size, Long orgId, String action, Set<Long> allowedOrgIds);

    // ===== 利用規約 =====

    String currentTermsVersion();

    /** 新versionを発行する（現行より新しい場合のみ）。未同意userへ再同意を強制する。 */
    void publishTerms(String version);
}
