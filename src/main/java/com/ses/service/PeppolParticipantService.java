package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.PeppolParticipant;

public interface PeppolParticipantService extends IService<PeppolParticipant> {
    
    /**
     * 指定された宛先が検証済みかチェックする。未検証の場合は例外をスローする。
     */
    void assertVerified(String ownerType, Long ownerId);

    /** 現在のtenant・法人に属する参加者を返す。 */
    PeppolParticipant findCurrent(String ownerType, Long ownerId);

    /** Provider callbackで参加者IDから一意の権威scopeを解決する。 */
    PeppolParticipant resolveVerifiedCallbackParticipant(String provider, String schemeId, String participantId);
}
