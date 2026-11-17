package com.ses.service.lifecycle.dual;

import java.util.Objects;

/**
 * 二者確認の業務comment（提出comment {@code business_comment}・確認/差戻しcomment {@code decision_comment}）の表示範囲
 * （W3-21a review P3-3、W3-21b 統括の決定。Privacyの署名で確定または緩和するまでの既定＝最小公開）。
 *
 * <table>
 *   <caption>本文を返す相手（A1・A5の応答で判定する。画面で隠すだけにしない）</caption>
 *   <tr><th>閲覧者</th><th>提出comment</th><th>確認・差戻しcomment</th></tr>
 *   <tr><td>確認者のrole（管理者・HR）</td><td>本文</td><td>本文</td></tr>
 *   <tr><td>その提出の提出者本人（社内の提出者、本人画面で提出した要員）</td><td>本文</td><td>本文（差戻し理由を知る必要がある）</td></tr>
 *   <tr><td>上記以外（範囲内の他の営業・マネージャー、営業が代理提出したときの要員本人）</td><td>有無だけ</td><td>有無だけ</td></tr>
 * </table>
 *
 * <p>「有無」は値が空白以外の文字を含むかで判定する。本文を返さない場合も有無は返すため、画面は「コメントあり」と表示できる。
 * 判定は照会者のDB上のrole・ユーザーIDと、提出行の提出者IDだけを使う（入口の種類・案件の担当は使わない）。</p>
 */
public final class DualConfirmationCommentDisclosure {

    private DualConfirmationCommentDisclosure() {
    }

    /** 照会者がその提出のcomment本文（提出・確認/差戻しの両方）を読めるか。 */
    public static boolean canReadBody(String viewerRole, long viewerUserId, Long submitterUserId) {
        if (LifecycleDualConfirmationAttemptService.ROLE_ADMIN.equals(viewerRole)
                || LifecycleDualConfirmationAttemptService.ROLE_HR.equals(viewerRole)) {
            return true;
        }
        return submitterUserId != null && Objects.equals(submitterUserId, viewerUserId);
    }

    /** commentが記入されているか（空白だけはなし）。 */
    public static boolean present(String comment) {
        return comment != null && !comment.isBlank();
    }
}
