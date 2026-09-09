package com.ses.service.security;

/**
 * unresolved ownership の修復を実行できる唯一の認証主体を解決する。
 * 通常の管理者権限と、修復センターの break-glass 権限を混同しないための境界。
 */
public interface RepairAuthorityResolver {

    RepairAuthority requireAuthority();

    record RepairAuthority(String tenantId, Long incidentId, Long actorId, Long approverId) {
    }
}
