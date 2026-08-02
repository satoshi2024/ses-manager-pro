package com.ses.service.approval;

import java.util.List;

/**
 * route_snapshot_jsonへ書き込む1step分。承認者は申請時点で具体user idまで解決済み
 * （design §6.1: 承認者はroute snapshotから解決し、後のroute変更で変えない）。
 */
public record RouteStepGroup(int stepNo, Integer slaHours, List<Long> approverUserIds) {
}
