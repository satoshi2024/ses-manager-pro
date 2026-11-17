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

    /**
     * 現在の最新versioned migration（V201: NF07 F1 の状態・区分の列の CHECK。V198〜V200 は並行の RB batch に割り当てた番号で、
     * 本 branch には無い（統合時に入る）。V197 は履歴・スキルシート取込と取込センターCSVの文書種別。V196 は NF07 F2
     * data element catalog seed、V195 は NF01 B0 二者確認coordinatorの7表と不変6表のtrigger、V194 は NF08 B2c-1 durable claimと永続費用ledger、
     * V193 は NF-06 取込センター画面の menuと import.metadata.manage の seed、V192 は外部API監査の保存期限、
     * V189 は NF01 細粒度認可epoch、V190/V191 は NF07 F1表と権限seed）。
     */
    static final String LATEST = "201";

    private LatestMigrationVersion() {
    }
}
