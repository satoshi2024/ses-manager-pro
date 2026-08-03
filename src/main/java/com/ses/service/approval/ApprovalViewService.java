package com.ses.service.approval;

import com.ses.dto.approval.ApprovalRequestListResponse;
import com.ses.dto.approval.ApprovalRequestView;
import org.springframework.security.core.Authentication;

/** A1の一覧・詳細表示を担当する読み取り専用サービス。状態遷移はengineへ委譲する。 */
public interface ApprovalViewService {
    ApprovalRequestListResponse list(String view, String status, long current, long size,
                                     Long userId, String role, Authentication authentication);

    ApprovalRequestView detail(Long requestId, Long userId, String role,
                               Authentication authentication);
}
