package com.ses.service.ai.copilot.scope;

import java.util.Set;

/** scope hash 計算用の許可 ID 集合。回答・ログ・provider には渡さない。 */
record CopilotScopePopulation(
        Set<Long> customerIds,
        Set<Long> contractIds,
        Set<Long> engineerIds,
        Set<Long> organizationIds,
        Set<Long> directUserIds
) {
}
