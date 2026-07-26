# Implementation Plan — BP会社マスタ・発注コンプライアンス

- [ ] 0. G2法務確認/既存自由入力profiling
  - **Objective**: 0. G2法務確認/既存自由入力profiling を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: 適用確認票、必須明示項目、支払rule、distinct値/件数/候補衝突。
  - **Demo**: 公式URL/版付き適用確認票の社内責任者確認と移行dry-run報告。外部専門家承認はM/本番gate。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

- [ ] F1. BP master/terms/contact/bank DDL
  - **Objective**: F1. BP master/terms/contact/bank DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V64/V1/H2/smoke、暗号化/masking、service。
  - **テスト要件**: unique、期間、bank非露出、状態。
  - **Demo**: BP法人と個人事業主を登録。

- [ ] F2. 既存在庫/要員/支払移行
  - **Objective**: F2. 既存在庫/要員/支払移行 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: affiliation、bp_company_id、snapshot、例外解決。
  - **テスト要件**: 件数/金額合計、同名別法人、read fallback/write ID必須。
  - **Demo**: staging DBで未解決0件まで解消。

- [ ] A1. BP管理画面
  - **Objective**: A1. BP管理画面 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: detail tabs、document link、評価、停止、autocomplete。
  - **テスト要件**: CRUD/scope/CSRF/PII field。
  - **Demo**: BP→所属要員→支払までdrilldown。

- [ ] B1. 発注コンプライアンスrule/価格協議
  - **Objective**: B1. 発注コンプライアンスrule/価格協議 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: applicability確認、明示項目、60日/支払手段/手数料、交渉履歴。
  - **テスト要件**: 境界と例外承認。
  - **Demo**: 不足発注を拒否し、補完後に警告0。

- [ ] B2. リスクdashboard/通知
  - **Objective**: B2. リスクdashboard/通知 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 期限文書、未確認、低評価、支払期日。
  - **テスト要件**: 通知冪等/recipient scope。
  - **Demo**: BPリスクから対象detailへ遷移。

- [ ] M. 回帰/旧入力廃止判定
  - **Objective**: M. 回帰/旧入力廃止判定 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/migration reconciliation。
  - **Demo**: 新規自由入力不可、既存フロー全通し。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
