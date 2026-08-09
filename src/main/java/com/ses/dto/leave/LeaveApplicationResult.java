package com.ses.dto.leave;

/** 休暇申請の受理結果。approvalRequestIdはengineがnullを返す場合null（実装では常に返る）。 */
public record LeaveApplicationResult(Long leaveId, Long approvalRequestId) {
}
