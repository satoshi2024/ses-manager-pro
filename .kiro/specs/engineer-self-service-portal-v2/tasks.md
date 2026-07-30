# Implementation Plan — 要員セルフサービスポータルV2

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T088〜T092はL1〜L3の定向test・直接回帰、T093でL4全量を実行する。
> UI Taskは対象browser/MVCを実施し、全画面回帰はMへ集約する。

- [ ] F1. change/expense/1on1/survey DDL
  - **Objective**: F1. change/expense/1on1/survey DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V77/V1/H2/smoke、本人scope、field allowlist。
  - **テスト要件**: A/B、JSON不正、状態、version競合。
  - **Demo**: F1. change/expense/1on1/survey DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

- [ ] A1. my dashboard/profile/skill申請
  - **Objective**: A1. my dashboard/profile/skill申請 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: preview/diff/approval apply、公開契約条件。
  - **テスト要件**: 承認前不変、再送1回、原価非表示。
  - **Demo**: skill申請→HR承認→sheet preview。

- [ ] A2. 本人給与/勤怠導線
  - **Objective**: A2. 本人給与/勤怠導線 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 専用DTO/MFA/no-store、attendance/timesheet統合navigation。
  - **テスト要件**: 本人scope/session再認証/provider failure。
  - **Demo**: 本人が自分の明細だけ表示。

- [ ] B1. 経費申請/承認/archive
  - **Objective**: B1. 経費申請/承認/archive を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: receipt scan、approval、accounting outbox link。
  - **テスト要件**: 金額/receipt/差戻し/二重連携。
  - **Demo**: 経費→承認→会計待ち。

- [ ] B2. 1on1/survey/privacy
  - **Objective**: B2. 1on1/survey/privacy を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 日程、公開/private note、campaign、匿名閾値、retention input。
  - **テスト要件**: visibility/最低回答数/通知。
  - **Demo**: 回答→HR限定相談→followup。

- [ ] M. 回帰
  - **Objective**: M. 回帰 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/mobile/PII leak。
  - **Demo**: 要員loginから全my機能一気通貫。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
