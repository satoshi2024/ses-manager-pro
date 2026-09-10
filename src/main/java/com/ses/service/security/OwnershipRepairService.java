package com.ses.service.security;

import com.ses.entity.OwnershipRepairQueue;
import com.ses.dto.security.OwnershipRepairRequest;
import com.ses.dto.security.OwnershipRepairClaimResponse;

import java.util.List;
import java.util.Map;

public interface OwnershipRepairService {
    List<OwnershipRepairQueue> listPending();
    Map<String, Object> summary();
    void resolve(Long queueId, OwnershipRepairRequest request);
    OwnershipRepairClaimResponse assign(Long queueId, Long assigneeUserId, Integer expectedVersion);
}
