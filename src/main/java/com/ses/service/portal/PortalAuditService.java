package com.ses.service.portal;

import com.ses.portal.PortalLoginUser;
import jakarta.servlet.http.HttpServletRequest;

/**
 * ポータル操作の監査記録（R4.2）。
 * 内部のApiAuditFilter（内部chain限定）ではportal操作を捕捉できないため、
 * portal専用のt_portal_access_log（append-only）へ外部user/組織/IP/時刻を記録する。
 */
public interface PortalAuditService {

    /**
     * download/検収/提出/口座変更等の操作を記録する。失敗しても業務処理を妨げない（audit best-effort）。
     */
    void record(PortalLoginUser user, String action, String targetType, Long targetId,
                HttpServletRequest request);
}
