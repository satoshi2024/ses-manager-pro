package com.ses.service.security.impl;

import com.ses.entity.Candidate;
import com.ses.mapper.CandidateMapper;
import com.ses.service.security.CandidateOwnershipResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CandidateOwnershipResolverImpl implements CandidateOwnershipResolver {
    private final CandidateMapper candidateMapper;

    @Override
    public Candidate select(String tenantId, Long candidateId) {
        if (tenantId == null || tenantId.isBlank() || candidateId == null) {
            return null;
        }
        return candidateMapper.selectByIdForTenant(candidateId, tenantId);
    }
}
