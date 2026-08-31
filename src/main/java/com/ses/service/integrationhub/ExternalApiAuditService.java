package com.ses.service.integrationhub;

/** 外部API専用auditのrequired persistence境界。失敗時は公開requestをfail-closedにする。 */
public interface ExternalApiAuditService {
    void recordRequired(ExternalApiAuditRecord record);
}
