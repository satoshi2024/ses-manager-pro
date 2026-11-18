package com.ses.privacy.foundation;

import java.util.Objects;

/**
 * ポリシー照会要求 DTO。
 * アクターロールは自報させず、asOf 日時の外部指定も構造上排除する。
 */
public record PolicyEvaluationQuery(
    String tenantId,
    String actorId,
    String moduleCode,
    String dataElementId,
    String targetAction,
    String targetProvider
) {
    public PolicyEvaluationQuery {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(moduleCode, "moduleCode must not be null");
        Objects.requireNonNull(dataElementId, "dataElementId must not be null");
        Objects.requireNonNull(targetAction, "targetAction must not be null");
        Objects.requireNonNull(targetProvider, "targetProvider must not be null");
    }
}
