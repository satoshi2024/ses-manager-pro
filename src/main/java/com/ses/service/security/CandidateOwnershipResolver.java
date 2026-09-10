package com.ses.service.security;

import com.ses.entity.Candidate;

/** 候補者を明示的tenant ownershipで解決する。 */
public interface CandidateOwnershipResolver {
    Candidate select(String tenantId, Long candidateId);
}
