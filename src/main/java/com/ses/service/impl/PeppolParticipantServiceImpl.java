package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.PeppolParticipant;
import com.ses.mapper.PeppolParticipantMapper;
import com.ses.service.PeppolParticipantService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.LegalEntityContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PeppolParticipantServiceImpl extends ServiceImpl<PeppolParticipantMapper, PeppolParticipant> implements PeppolParticipantService {

    private final LegalEntityContextService legalEntityContextService;

    @Override
    public boolean save(PeppolParticipant participant) {
        if (participant == null) {
            return false;
        }
        String tenantId = requireCurrentTenant();
        Long legalEntityId = legalEntityContextService.requireCurrentLegalEntityId();
        if (participant.getTenantId() == null && participant.getLegalEntityId() == null) {
            participant.setTenantId(tenantId);
            participant.setLegalEntityId(legalEntityId);
        } else if (!tenantId.equals(participant.getTenantId())
                || !legalEntityId.equals(participant.getLegalEntityId())) {
            throw BusinessException.of(403, "TENANT_CONTEXT_MISMATCH");
        }
        return super.save(participant);
    }

    @Override
    public void assertVerified(String ownerType, Long ownerId) {
        PeppolParticipant participant = findCurrent(ownerType, ownerId);

        if (participant == null || participant.getVerifiedAt() == null) {
            throw new BusinessException("宛先のPeppol Participant IDが未検証のため、送信できません。");
        }
    }

    @Override
    public PeppolParticipant findCurrent(String ownerType, Long ownerId) {
        String tenantId = requireCurrentTenant();
        Long legalEntityId = legalEntityContextService.requireCurrentLegalEntityId();
        return lambdaQuery()
                .eq(PeppolParticipant::getOwnerType, ownerType)
                .eq(PeppolParticipant::getOwnerId, ownerId)
                .eq(PeppolParticipant::getTenantId, tenantId)
                .eq(PeppolParticipant::getLegalEntityId, legalEntityId)
                .one();
    }

    @Override
    public PeppolParticipant resolveVerifiedCallbackParticipant(String provider, String schemeId, String participantId) {
        if (provider == null || provider.isBlank() || schemeId == null || schemeId.isBlank()
                || participantId == null || participantId.isBlank()) {
            throw BusinessException.of(403, "TENANT_CONTEXT_REQUIRED");
        }
        List<PeppolParticipant> participants = lambdaQuery()
                .eq(PeppolParticipant::getProvider, provider.trim())
                .eq(PeppolParticipant::getSchemeId, schemeId.trim())
                .eq(PeppolParticipant::getParticipantId, participantId.trim())
                .eq(PeppolParticipant::getOwnerType, "ORGANIZATION")
                .eq(PeppolParticipant::getStatus, "VERIFIED")
                .isNotNull(PeppolParticipant::getVerifiedAt)
                .isNotNull(PeppolParticipant::getTenantId)
                .isNotNull(PeppolParticipant::getLegalEntityId)
                .list();
        if (participants.size() != 1) {
            throw BusinessException.of(403, "TENANT_CONTEXT_REQUIRED");
        }
        return participants.get(0);
    }

    private String requireCurrentTenant() {
        String securityTenantId = legalEntityContextService.requireTenantId();
        String explicitTenantId = AccountingTenantContextHolder.requireTenantContext();
        if (!securityTenantId.equals(explicitTenantId)) {
            throw BusinessException.of(403, "TENANT_CONTEXT_MISMATCH");
        }
        return securityTenantId;
    }
}
