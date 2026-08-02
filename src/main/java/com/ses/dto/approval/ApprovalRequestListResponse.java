package com.ses.dto.approval;

import java.util.List;

/** ページング済み承認申請一覧。 */
public record ApprovalRequestListResponse(List<ApprovalRequestListItem> records, long total,
                                          long current, long pages) {
}
