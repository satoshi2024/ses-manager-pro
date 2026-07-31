package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 文書廃棄申請（t_document_disposal_request）。
 *
 * <p>設計上の不変条件:</p>
 * <ul>
 *   <li>廃棄承認は管理者固定（design §6.2の逸脱。物理削除が組織横断で不可逆なため）。</li>
 *   <li>申請者と承認者は異なる管理者でなければならない。</li>
 *   <li>legal_hold_flag=1の文書への申請は拒否する（R4.2）。</li>
 *   <li>状態機械: PENDING → APPROVED → DISPOSED（終端）/ REJECTED（終端）。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_document_disposal_request")
public class DocumentDisposalRequest extends BaseEntity {

    /** 文書ID */
    private Long documentId;

    /** 廃棄申請者ユーザーID（管理者のみ） */
    private Long requestedBy;

    /**
     * 廃棄承認者ユーザーID（申請者と別の管理者）。
     * 単独管理者の即時物理削除禁止（R4.3）のため、承認者は申請者と異なること。
     */
    private Long approvedBy;

    /**
     * 廃棄申請の状態機械。
     * PENDING（承認待ち）/ APPROVED（承認済）/ REJECTED（却下）
     * / DISPOSED（廃棄実施済）/ FAILED（廃棄失敗）
     */
    private String status;

    /** 廃棄理由 */
    private String reason;

    /** 廃棄承認日時 */
    private LocalDateTime approvedAt;

    /** 廃棄実施日時（storage削除完了時に設定） */
    private LocalDateTime disposedAt;
}
