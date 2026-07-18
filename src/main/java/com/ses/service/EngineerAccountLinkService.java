package com.ses.service;

import com.ses.entity.EngineerAccountLink;

/**
 * 要員↔ログインアカウント紐付けサービス。
 */
public interface EngineerAccountLinkService {

    /** 要員に要員ロールのログインアカウントを紐付ける（未紐付け・role=要員を検証）。 */
    EngineerAccountLink link(Long engineerId, Long sysUserId, Long linkedBy);

    /** 要員の紐付けを解除する。 */
    void unlinkByEngineerId(Long engineerId);

    /** ユーザーIDから担当要員IDを解決する（未紐付けは null）。 */
    Long findEngineerIdByUserId(Long sysUserId);

    /** 要員に紐付いたアカウントを取得する（未紐付けは null）。 */
    EngineerAccountLink findByEngineerId(Long engineerId);

    /** 指定ユーザーが要員として紐付け中か。 */
    boolean isUserLinked(Long sysUserId);
}
