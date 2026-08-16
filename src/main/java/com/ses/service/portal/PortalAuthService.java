package com.ses.service.portal;

import com.ses.dto.portal.PortalAcceptInvitationRequest;
import com.ses.dto.portal.PortalLoginRequest;
import com.ses.dto.portal.PortalLoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * portal認証（login / MFA / 招待受諾 / 規約同意 / logout）。
 * パスワードは常にBCryptで照合する（内部のprofile切替encoderとは独立。portalは外部公開のため）。
 */
public interface PortalAuthService {

    /**
     * login: パスワード検証 → MFA未設定ならMFA_SETUP（secret発行）／設定済みならMFAコード検証 → session発行。
     */
    PortalLoginResponse login(PortalLoginRequest request, HttpServletRequest httpRequest,
                              HttpServletResponse httpResponse);

    /**
     * MFA有効化（loginでMFA_SETUPを受けた後の続き）。成功時にrecovery codeを返しsessionを発行する。
     */
    com.ses.dto.portal.PortalMfaCompleteDto completeMfa(String email, String code,
                                                        HttpServletRequest httpRequest,
                                                        HttpServletResponse httpResponse);

    /**
     * 招待受諾: 4条件（未使用・期限内・email一致・組織一致）を検証し、DB CASで一回性を保証して
     * portal userを作成（論理削除済みemailはreactivate）する。
     */
    void acceptInvitation(PortalAcceptInvitationRequest request, HttpServletRequest httpRequest);

    /**
     * 利用規約同意を記録する（version一致時のみ。UNIQUE(user_id, terms_version)で二重同意を防ぐ）。
     */
    void consentTerms(Long portalUserId, String termsVersion, HttpServletRequest httpRequest);

    /**
     * logout: 現在のsessionを失効させcookieを削除する。
     */
    void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse);

    /**
     * 通知設定を更新する（R4.1: email通知設定。1=通知する）。
     */
    void updatePreferences(Long portalUserId, boolean notifyEmail);
}
