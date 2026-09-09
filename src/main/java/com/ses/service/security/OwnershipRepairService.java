package com.ses.service.security;

import com.ses.entity.OwnershipRepairQueue;

import java.util.List;
import java.util.Map;

public interface OwnershipRepairService {
    List<OwnershipRepairQueue> listPending();
    Map<String, Object> summary();
    void resolve(Long queueId, String tenantId, String reason, String evidence);
    void assign(Long queueId, Long assigneeUserId);
}
