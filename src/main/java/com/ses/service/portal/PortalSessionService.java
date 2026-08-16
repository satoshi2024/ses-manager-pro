package com.ses.service.portal;

import com.ses.portal.PortalLoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

/**
 * portal専用DB session管理（t_portal_session）。
 * session cookie名/実装を内部（JSESSIONID + t_user_session）と完全分離する（G3）。
 * 失効（logout / 管理者 / user・組織停止 / MFA reset）はDB行のrevoked_atで即時反映される。
 */
public interface PortalSessionService {

    /**
     * login成功後にsessionを発行し、cookieへ生tokenを設定する。
     * 生tokenはレスポンス・ログへ出さない。
     */
    void issue(HttpServletRequest request, HttpServletResponse response, Long portalUserId);

    /**
     * リクエストcookieのsession tokenを検証してprincipalを解決する。
     * 無効・失効・期限切れ・user/組織停止はnull（未認証扱い）。
     */
    PortalLoginUser resolve(HttpServletRequest request);

    /**
     * 現在のsessionを失効させcookieを削除する。
     */
    void revokeCurrent(HttpServletRequest request, HttpServletResponse response);

    /**
     * userの全sessionを失効させる（user停止・MFA reset・password変更・管理者操作）。
     */
    void revokeAllForUser(Long portalUserId, String reason);

    /**
     * 組織配下の全userの全sessionを失効させる（組織停止時）。
     */
    void revokeAllForOrg(Long portalOrgId, String reason);

    /**
     * 管理者向け: userの有効session一覧（B1）。
     */
    List<com.ses.entity.PortalSession> listActive(Long portalUserId);
}
