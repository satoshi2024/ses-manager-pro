package com.ses.migration;

/**
 * MySQL migration testが期待する「最新versioned migration」の単一定義。
 *
 * <p>新しい {@code db/migration/Vxxx__*.sql} を追加したら本定数だけを更新する。各testへ同じ数値を
 * 直書きすると、無関係な機能のtestまで新migrationと結合し、並行branchの統合時に衝突する。
 * {@code target("184")} のように特定の歴史versionを固定して検証するassertionは対象外とし、
 * 各testに残す（ここでは「最新版まで適用されたこと」だけを表す）。
 */
final class LatestMigrationVersion {

    /** 現在の最新versioned migration（V185: 定期管理レポートscheduler claim token）。 */
    static final String LATEST = "185";

    private LatestMigrationVersion() {
    }
}
