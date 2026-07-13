package com.ses.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.AuditLog;

/**
 * API操作監査ログサービス。
 */
public interface AuditLogService {

    /** 監査ログを1件記録する。書き込み失敗は呼び出し元の処理に影響させない。 */
    void record(String username, String method, String uri, int status);

    /** 監査ログを条件検索する（username部分一致・method完全一致、created_at降順）。 */
    Page<AuditLog> page(long current, long size, String username, String method);
}
