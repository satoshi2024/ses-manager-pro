package com.ses.service.portal;

import com.ses.dto.portal.PortalMfaSetupDto;
import com.ses.dto.portal.PortalMfaCompleteDto;

/**
 * portalユーザー向けTOTP MFA（全portal user必須: G3）。
 * TOTP secretは暗号化してt_portal_userへ保存し、recovery codeは1回限りhash保存する。
 * 内部break-glass用MFA（MfaService）とは別実装（portalは別identityのため）。
 */
public interface PortalMfaService {

    /**
     * TOTP secretを生成して準備状態（未enable）で保存し、設定画面用secret/URIを返す。
     * 同一userの再呼び出しは直前の準備値を上書きする（login毎の再設定）。
     */
    PortalMfaSetupDto setup(Long portalUserId);

    /**
     * 準備済みsecretの6桁コードを検証してMFAを有効化し、1回限りrecovery codeを発行する。
     * 同一stepのコード再使用はCASで拒否する。成功時はsession発行を呼出側が行う。
     */
    PortalMfaCompleteDto enable(Long portalUserId, String code);

    /**
     * login時TOTP/ recovery code検証。trueで受理（session発行は呼出側）。
     * recovery codeは使用済みになると以降のloginで拒否される。
     */
    boolean verify(Long portalUserId, String code);
}
