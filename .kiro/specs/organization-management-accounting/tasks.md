# Implementation Plan — 組織・管理会計

- [x] F1. 組織/所属/cost center/予算DDL
  - **Objective**: F1. 組織/所属/cost center/予算DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V60/V1/H2/smoke、entity/mapper/service。
  - **テスト要件**: 循環、期間、主所属一意、参照中無効化。
  - **Demo**: 法人→事業部→課と上長を登録。

- [x] F2. OrganizationScopeService
  - **Objective**: F2. OrganizationScopeService を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: DataScopeとの結合規則、cache keyにtenant/user/version。
  - **テスト要件**: 管理者/部門長/営業/HRの一覧・件数・export。
  - **Demo**: 部門長が子組織だけ閲覧。

- [x] A1. 組織管理画面
  - **Objective**: A1. 組織管理画面 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: tree CRUD、異動、cost center、user主所属。
  - **テスト要件**: API validation/CSRF/権限。
  - **Demo**: 異動前後の日付で所属が切替。

- [x] B1. 月次帰属snapshotと予算
  - **Objective**: B1. 月次帰属snapshotと予算 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 月次締めhook、予算CSV、訂正監査。
  - **テスト要件**: 異動後も過去snapshot不変、reopen規約。
  - **Demo**: 先月所属と今月所属が別部門集計。

- [x] B2. 管理会計ダッシュボード
  - **Objective**: B2. 管理会計ダッシュボード を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 既存金額service再利用、予実/drilldown/export。
  - **テスト要件**: 全社合計一致、scope漏洩なし。
  - **Demo**: 部門別売上/粗利/待機費/予算差を確認。

- [x] M. 回帰
  - **Objective**: M. 回帰 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/browser mobile。
  - **Demo**: 組織作成→所属→契約→締め→部門損益の一気通貫。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。

---

## 残課題（チェック済みでも未検証のもの）

チェックは「Objective・自動テスト・サービス層Demoを満たした」ことを表す。

1. ~~**Docker MySQL smoke**~~ → **完了（2026-07-27 第十二次Review）**。
   Docker Engine 29.3.1 上で `FlywayMigrationSmokeTest`（空庫V1→V60）と
   `FlywayLegacyV60MigrationSmokeTest`（旧V58形状→V60）を実行し成功。
   この実行で、V60の生成列がSTOREDだと既存DBのALTERが `ERROR 1215` で必ず失敗する
   P0を検出・修正した（詳細は `review-ledger.md` 第十二次）。
   `mvn clean test` は **836 tests / Failures 0 / Errors 0 / Skipped 0**。
2. **実ブラウザDemo**: desktop/390px での組織作成→所属→異動→契約→締め→部門損益の一気通貫は未実施。
   Thymeleafのレンダリングは `MobileResponsiveLayoutTest`、JS構文は `JsSyntaxCheckTest`（Node.js v22で実行済み）
   までの確認にとどまる。**本番リリース前の必須条件**として残す。
