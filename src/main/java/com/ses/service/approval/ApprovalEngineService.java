package com.ses.service.approval;

import com.ses.entity.ApprovalRequest;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 承認engine core（design §3）。route解決から状態機械・CASまでを担当する。
 * 5対象adapterの登録（F2）が無い間は、approve()が最終stepまで到達しても
 * 業務adapterは何も呼ばれず、申請は{@code approved}で終端するだけになる。
 */
public interface ApprovalEngineService {

    /** 申請を作成し、route解決・自己承認除外を経て最初のstepまで進める（R1.1〜R1.4、R3.4）。 */
    ApprovalRequest request(ApprovalRequestCommand command);

    /** 承認。並列group全員承認で次stepへ、最終stepなら終端（design §6.4）。 */
    void approve(Long requestId, Long actingUserId, String comment);

    /** 却下。1人の却下で即終端。 */
    void reject(Long requestId, Long actingUserId, String comment);

    /** 差戻し。申請者による修正・再申請待ちの状態にする。 */
    void returnForRevision(Long requestId, Long actingUserId, String comment);

    /** 差戻し後の再申請。current_stepを先頭へ戻し、routeを再解決せずsnapshotのまま進める。 */
    ApprovalRequest resubmit(Long requestId, Long applicantId, Map<String, Object> updatedPayload,
                              Map<String, Object> updatedDiff, BigDecimal updatedAmountSnapshot);

    /** 取下げ。申請者本人のみ、終端状態でなければ可能。 */
    void withdraw(Long requestId, Long applicantId);
}
