package com.ses.nf01.preflight;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 他packageの実MySQL試験から、preflightの個別検査だけを実行するための試験専用入口。
 * 本番codeにpublicな検査APIを増やさず、package-privateの検査をそのまま呼ぶ。
 */
public final class Nf01PreflightTestAccess {

    private Nf01PreflightTestAccess() {
    }

    /**
     * 未終結退社案件の重複検査（LIFECYCLE_RESIGNATION_ACTIVE_DUPLICATE）だけを実行し、reportのJSONを返す。
     * 接続先schemaの他の表・列の状態には依存しない。
     */
    public static String activeResignationDuplicatesJson(Connection connection) throws SQLException {
        LifecycleAuthorizationPreflight.PreflightReport report =
                new LifecycleAuthorizationPreflight.PreflightReport("test-access", "test-access");
        LifecycleAuthorizationPreflight.activeResignationDuplicates(connection, report);
        return report.toJson();
    }
}
