package com.ses.service.attendance;

/** 勤務日/月へ固定する所属snapshot。未知法人は生成しない。 */
public record AttendanceScopeSnapshot(Long legalEntityId, Long organizationId) {
}
