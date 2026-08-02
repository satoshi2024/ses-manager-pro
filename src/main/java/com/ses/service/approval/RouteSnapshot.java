package com.ses.service.approval;

import java.util.List;

/** {@code t_approval_request.route_snapshot_json}へ書き込む申請時点のroute全体スナップショット。 */
public record RouteSnapshot(Long routeId, Integer versionNo, Long organizationId,
                             List<RouteStepGroup> steps) {
}
