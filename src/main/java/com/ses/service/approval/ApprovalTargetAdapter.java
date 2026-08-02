package com.ses.service.approval;

import com.ses.entity.ApprovalRequest;

import java.util.Map;
import java.util.Set;

/**
 * 対象業務(見積/契約/請求/BP支払/月次締め)ごとのadapter契約（design §2）。
 * requestType aliasを含むregistry経由で、5つの対象adapterがapplyApprovedを実装する。
 */
public interface ApprovalTargetAdapter {

    /** このadapterが扱う{@code request_type}。 */
    String requestType();

    /** 1 adapterが扱う業務操作。旧実装との互換のためrequestType単体を既定値とする。 */
    default Set<String> supportedRequestTypes() {
        return Set.of(requestType());
    }

    /** 対象entityの現在値からsnapshotを作る。呼出元(F2の申請API)が{@link ApprovalRequestCommand}を組み立てる際に使う。 */
    ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command);

    /** 申請受付前の対象固有バリデーション（既存serviceの入力検証を再利用し、再実装しない）。 */
    void validateBeforeRequest(ApprovalSnapshot snapshot);

    /**
     * 最終承認transaction内で1回だけ呼ばれる。既存service単件methodへ委譲し、
     * 状態機械/金額検証/監査を再実装しない（design §3/§6.4、R2.2）。
     * 外部API/メール送信はここで行わず、outboxへ積んでcommit後に実行する（R2.3）。
     */
    void applyApproved(ApprovalRequest request);
}
