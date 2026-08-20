package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.PeppolParticipant;
import com.ses.mapper.PeppolParticipantMapper;
import com.ses.service.PeppolParticipantService;
import org.springframework.stereotype.Service;

@Service
public class PeppolParticipantServiceImpl extends ServiceImpl<PeppolParticipantMapper, PeppolParticipant> implements PeppolParticipantService {

    @Override
    public void assertVerified(String ownerType, Long ownerId) {
        PeppolParticipant participant = lambdaQuery()
                .eq(PeppolParticipant::getOwnerType, ownerType)
                .eq(PeppolParticipant::getOwnerId, ownerId)
                .one();

        if (participant == null || participant.getVerifiedAt() == null) {
            throw new BusinessException("宛先のPeppol Participant IDが未検証のため、送信できません。");
        }
    }
}
