package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.PeppolParticipant;

public interface PeppolParticipantService extends IService<PeppolParticipant> {
    
    /**
     * 指定された宛先が検証済みかチェックする。未検証の場合は例外をスローする。
     */
    void assertVerified(String ownerType, Long ownerId);
}
