package com.ses.migration;

/**
 * MySQL migration testが期待する「最新versioned migration」の単一定義。
 *
 * <p>新しい {@code db/migration/Vxxx__*.sql} を追加したら本定数だけを更新する。各testへ同じ数値を
 * 直書きすると、無関係な機能のtestまで新migrationと結合し、並行branchの統合時に衝突する。
 * {@code target("184")} のように特定の歴史versionを固定して検証するassertionは対象外とし、
 * 各testに残す（ここでは「最新版まで適用されたこと」だけを表す）。
 *
 * <p>本番（{@code db/migration} + {@code db/migration-prod}）の最新versionも同じ値である。
 * {@code db/migration-prod} はRepeatableだけを持ち、versioned migrationを置かない
 * （{@code MigrationScriptIntegrityTest} が固定する）。起動時schema guardの期待値との一致も
 * {@code MigrationScriptIntegrityTest} が確認する。
 */
final class LatestMigrationVersion {

    /** 現在の最新versioned migration（V188: ポータルユーザーと要員の紐付け）。 */
    static final String LATEST = "188";

    private LatestMigrationVersion() {
    }
}
