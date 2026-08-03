package com.ses.dto.approval;

import java.time.LocalDateTime;

/** 承認履歴の公開用行。user IDだけでなく代理元を表示する。 */
public record ApprovalActionView(Long id, Integer stepNo, Long approverUserId,
                                 Long delegatedFrom, String action, String comment,
                                 LocalDateTime actedAt, boolean delegated) {
}
