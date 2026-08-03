package com.ses.service.approval;

import java.util.List;

/** ルート解決結果と、申請時点で解決済みのstep群（route_snapshot_jsonの元データ）。 */
public record ResolvedRoute(Long routeId, Integer versionNo, Long organizationId,
                             List<RouteStepGroup> steps) {
}
