package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文書アクセス監査ログ（t_document_access_log）。
 * 税務調査対応のため、閲覧・DL・export・登録・訂正・廃棄操作を記録する（R3.2）。
 * 論理削除なし・append-onlyで扱い、BaseEntityは継承しない（deletedFlagを持たない）。
 */
@Data
@TableName("t_document_access_log")
public class DocumentAccessLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文書ID */
    private Long documentId;

    /** 文書版ID（特定版への操作の場合） */
    private Long versionId;

    /**
     * アクション。
     * VIEW / DOWNLOAD / EXPORT / REGISTER / AMEND / LEGAL_HOLD / DISPOSE
     */
    private String action;

    /** 操作ユーザーID */
    private Long userId;

    /**
     * IPアドレスのSHA-256ハッシュ（生IPは保存しない）。
     * platform-invariants §4（秘密情報非ログ出力）に準拠。
     */
    private String ipHash;

    /** 操作日時 */
    private LocalDateTime occurredAt;
}
