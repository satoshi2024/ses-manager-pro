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
     * 一覧API（内部・ポータル）の1ページの上限件数（NF-02 P3-N2-3）。
     * 一覧は1行ごとにSLA clock・顧客・担当者等を個別に引くため、全体上限（{@code PageUtils.MAX_PAGE_SIZE}=1000）
     * のままでは1要求がDB接続を長く占有する。画面の指定（内部10件・ポータル50件）より十分大きい値にする。
     * CSV出力はこの上限を使わない（{@code ServiceRequestExportServiceImpl}）。
     */
    int LIST_MAX_PAGE_SIZE = 100;

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
     * 内部向け一覧検索（ページネーション・DataScope適用）。page・sizeは {@code PageUtils.safePage} で正規化する
     * （0以下は既定値、上限は {@code PageUtils.MAX_PAGE_SIZE}）。一覧APIは呼出し前に {@link #LIST_MAX_PAGE_SIZE} へ丸める。
     */
    Page<ServiceRequestDto> searchInternalRequests(int page, int size, String keyword, String status, String priority, String category, Long customerId);

    /**
     * ポータル向け一覧検索（自社スコープ限定）。page・sizeの正規化は {@link #searchInternalRequests} と同じ。
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
