package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.portal.PortalCsatCreateRequest;
import com.ses.dto.portal.PortalServiceRequestDto;
import com.ses.dto.servicedesk.ServiceCommentCreateRequest;
import com.ses.dto.servicedesk.ServiceCommentDto;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestDto;
import com.ses.dto.servicedesk.ServiceRequestStatusChangeRequest;
import com.ses.dto.servicedesk.ServiceRequestUpdateRequest;
import com.ses.entity.ServiceRequest;

/**
 * サービスリクエスト管理サービス
 */
public interface ServiceRequestService {

    /**
     * 新規サービスリクエスト起票（内部・ポータル共通）
     */
    ServiceRequest createRequest(ServiceRequestCreateRequest req, Long actorUserId, boolean isPortal, Long portalUserId);

    ServiceRequest createRequest(ServiceRequestCreateRequest req, boolean isPortal, Long portalUserId,
                                 ServiceDeskExecutionContext executionContext);

    /**
     * 内部向けサービスリクエスト詳細取得
     */
    ServiceRequestDto getInternalDetail(Long id);

    /**
     * ポータル向けサービスリクエスト詳細取得（内部メモ除外・自社スコープ検証）
     */
    PortalServiceRequestDto getPortalDetail(Long id, Long customerId);

    /**
     * 内部向け一覧検索（ページネーション・DataScope適用）
     */
    Page<ServiceRequestDto> searchInternalRequests(int page, int size, String keyword, String status, String priority, String category, Long customerId);

    /**
     * ポータル向け一覧検索（自社スコープ限定）
     */
    Page<PortalServiceRequestDto> searchPortalRequests(int page, int size, String keyword, String status, Long customerId);

    /**
     * 属性更新（内部用）
     */
    void updateRequest(Long id, ServiceRequestUpdateRequest req);

    /**
     * ステータス変更（状態CAS・SLA計時連動・Reopen対応）
     */
    void changeStatus(Long id, ServiceRequestStatusChangeRequest req, Long actorId, String actorType, String actorName);

    void changeStatus(Long id, ServiceRequestStatusChangeRequest req, ServiceDeskExecutionContext executionContext);

    /**
     * コメント・内部メモ投稿
     */
    ServiceCommentDto addComment(Long id, ServiceCommentCreateRequest req, Long actorId, String authorType, String authorName, boolean isPortal);

    /**
     * 実リクエストのコメント経路。作成時点のtenant・ZoneId・Instantを、WAITING_CUSTOMERからの
     * 自動復帰を含む同一トランザクション全体へ引き渡す。
     */
    ServiceCommentDto addComment(Long id, ServiceCommentCreateRequest req, boolean isPortal,
                                 ServiceDeskExecutionContext executionContext);

    /**
     * 顧客ポータルからのCSAT評価回答投稿（1回限り）
     */
    void submitCsat(Long id, PortalCsatCreateRequest req, Long customerId, Long portalUserId);

    /**
     * 顧客・契約から法人既定カレンダー範囲を解決し、実行コンテキストへ束ねる。
     * クライアントが他法人IDを指定した場合は拒否する。organizationIdは法人既定のため常にnull。
     */
    ServiceDeskExecutionContext bindCalendarScope(ServiceDeskExecutionContext base, Long customerId, Long contractId);
}
