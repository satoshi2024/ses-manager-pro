# Implementation Plan — 承認ワークフロー・内部統制

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T041〜T046はL0〜L3の定向test・直接回帰、T047でL4全量を実行する。
> 共通approval adapter/state machine合流時はL3、昇格条件該当時だけ中間L4とする。

- [ ] 0. G7と対象操作inventory
  - **Objective**: 0. G7と対象操作inventory を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: 操作、現endpoint/service、申請field、route、SLA、職務分離表。
  - **Demo**: 財務/管理者レビュー。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

- [ ] F1. route/request/action/delegation DDL
  - **Objective**: F1. route/request/action/delegation DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V66/V1/H2/smoke、engine core/CAS。
  - **テスト要件**: route/自己承認/並列/代理/競合。
  - **Demo**: F1. route/request/action/delegation DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

- [ ] F2. 5 target adapters
  - **Objective**: F2. 5 target adapters を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 既存service委譲、version snapshot、idempotency、outbox。
  - **テスト要件**: adapterごと正常/競合/rollback/再送。
  - **Demo**: curlで各対象申請→承認。

- [ ] A1. inbox/request/diff/history UI
  - **Objective**: A1. inbox/request/diff/history UI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 一覧、差分、comment、対象link、mobile。
  - **テスト要件**: requester/approver scope、field masking。
  - **Demo**: 差戻し→修正→再申請→承認。

- [ ] A2. route/代理管理
  - **Objective**: A2. route/代理管理 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: version/有効日、approver preview、代理期間。
  - **テスト要件**: 進行中snapshot不変、解決不能拒否。
  - **Demo**: route変更前後の2申請で承認者が異なる。

- [ ] B1. 通知/SLA/escalation
  - **Objective**: B1. 通知/SLA/escalation を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: recipient限定、冪等scheduler、NotificationLinks。
  - **テスト要件**: 期限境界/重複なし/tenant scope。
  - **Demo**: overdueを上位責任者へ通知。

- [ ] M. 対象画面統合/回帰
  - **Objective**: M. 対象画面統合/回帰 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/5業務browser通し。
  - **Demo**: 申請者単独確定不可と二重実行0を確認。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
