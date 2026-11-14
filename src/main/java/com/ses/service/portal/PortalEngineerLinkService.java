package com.ses.service.portal;

import com.ses.dto.portal.PortalUserEngineerLinkDto;

import java.util.List;

/**
 * ポータルユーザーと要員の紐付けの登録・解除（NF01 N1-4 / 決定 Q-10）。
 *
 * <p>管理者だけがポータル管理画面から操作する。紐付け・ポータルユーザー（の組織）・要員はすべて操作者のtenantに
 * 限り、他tenantの対象は存在しないものと同じ404で拒否する。登録・解除は t_audit_log へ同一transactionで必須記録し、
 * 記録できなければ操作ごと取り消す。退社自動処理（ResignationGateChecker）はここで登録した現行の紐付けだけを対象にする。</p>
 */
public interface PortalEngineerLinkService {

    /** ポータルユーザーの紐付け（現行と解除済みの履歴）を新しい順に返す。 */
    List<PortalUserEngineerLinkDto> listForPortalUser(Long portalUserId);

    /** ポータルユーザーを要員へ紐付ける。既に現行の紐付けがあれば409（変更は解除してから登録し直す）。 */
    PortalUserEngineerLinkDto register(Long portalUserId, Long engineerId);

    /** 現行の紐付けを解除する（行は履歴として残す）。解除済みなら409。 */
    PortalUserEngineerLinkDto release(Long linkId);
}
