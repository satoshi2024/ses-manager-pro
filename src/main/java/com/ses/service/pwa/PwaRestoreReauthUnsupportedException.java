package com.ses.service.pwa;

import com.ses.common.exception.BusinessException;

/**
 * ローカルパスワード以外（OIDC等）で認証したsessionからの下書き復元要求（NF04 P1-N4-1 方案A）。
 *
 * <p>本人確認に使えるローカルパスワードを要員が持たない構成では、復元APIをパスワード照合・失敗回数加算・
 * lock判定より前に拒否する。応答は {@link com.ses.controller.api.MyPwaRestoreApiController} が
 * 専用のerror code（{@value #ERROR_CODE}）とmessage key（{@value #MESSAGE_KEY}）で返す。
 * 他の経路へ漏れた場合も、{@link BusinessException} として既定の403文言で安全側に倒れる。</p>
 */
public class PwaRestoreReauthUnsupportedException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /** 応答の {@code data.type}・{@code X-SES-Error-Code}・ログのerrorCodeに使う機械可読code。 */
    public static final String ERROR_CODE = "PWA_RESTORE_REAUTH_UNSUPPORTED";
    /** 利用者向け文言のmessage key（4言語のbundleに定義する）。 */
    public static final String MESSAGE_KEY = "error.pwa.restore.reauthUnsupported";

    public PwaRestoreReauthUnsupportedException() {
        super(403, MESSAGE_KEY);
    }
}
