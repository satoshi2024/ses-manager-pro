package com.ses.controller.api;

import com.ses.backup.quiesce.BackupWriteQuiescedException;
import com.ses.common.result.ApiResult;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

/**
 * RB-QUIESCE 2b review P2-1 の試験だけで使う API（{@code @Import} した context にだけ登録する。{@code @TestConfiguration} の
 * 入れ子のため component scan では拾わない）。{@code com.ses.controller.api} の package に置き、{@code GlobalExceptionHandler}
 * の対象にする。path は既存の root（{@code /api/engineers}）の下にし、menu・action の権限の解決を既存の規則に任せる。
 */
@TestConfiguration
public class BackupQuiesceRejectionProbeConfiguration {

    public static final String BASE = "/api/engineers/backup-quiesce-probe";

    @RestController
    @RequestMapping(BASE)
    static class Probe {

        /** controller の中で業務の接続が読取り専用だった（最後の防線の server の 1792）。 */
        @GetMapping("/readonly")
        ApiResult<Void> readOnly() {
            throw new IllegalStateException("probe", new SQLException(
                    "Cannot execute statement in a READ ONLY transaction.", "25006", 1792));
        }

        /** 同じく、Connector/J の client 側の拒否（S1009。実 MySQL の業務の接続の書込みの形。fix E）。MyBatis の包みを模す。 */
        @GetMapping("/readonly-client")
        ApiResult<Void> readOnlyClient() {
            throw new org.springframework.jdbc.UncategorizedSQLException("update", "UPDATE t_engineer SET name = ?",
                    com.ses.test.MySqlReadOnlyRejections.connectorJPrepared());
        }

        /** uploads の file の変更の明示の拒否。 */
        @GetMapping("/uploads")
        ApiResult<Void> uploads() {
            throw new BackupWriteQuiescedException("uploads:probe");
        }

        /** 必須の監査等の明示の拒否。 */
        @GetMapping("/required-audit")
        ApiResult<Void> requiredAudit() {
            throw new BackupWriteQuiescedException("required-audit");
        }
    }
}
