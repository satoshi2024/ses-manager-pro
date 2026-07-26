# Implementation Plan — CRM複数担当者・商機管理

- [ ] F1. contact/lead/opportunity DDLと移行
  - **Objective**: F1. contact/lead/opportunity DDLと移行 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V65/V1/H2/smoke、既存contact→初回contact。
  - **テスト要件**: 件数、primary、PII scope、移行値一致。
  - **Demo**: 既存顧客の担当者がdetailに表示。

- [ ] F2. opportunity状態/変換/forecast排他
  - **Objective**: F2. opportunity状態/変換/forecast排他 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: service、project/quotation source、冪等。
  - **テスト要件**: 状態、再送、二重forecastなし。
  - **Demo**: 商機→見積/案件変換を2回実行し1件。

- [ ] A1. 顧客contacts/timeline
  - **Objective**: A1. 顧客contacts/timeline を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 複数担当、役割、activity/mail/document link。
  - **テスト要件**: 宛先候補、退職除外、mask。
  - **Demo**: 請求担当を請求書送付先に選択。

- [ ] A2. lead/opportunity UI
  - **Objective**: A2. lead/opportunity UI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: lead list、opportunity kanban/list、next task。
  - **テスト要件**: filters/scope/mobile/D&D rollback。
  - **Demo**: lead→顧客/商機→見積。

- [ ] B1. CRM KPI
  - **Objective**: B1. CRM KPI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: stage金額/滞留/転換/失注/source ROI。
  - **テスト要件**: 集計口径とscope。
  - **Demo**: 担当別funnel drilldown。

- [ ] M. 回帰
  - **Objective**: M. 回帰 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL smoke/customer/proposal/quotation回帰。
  - **Demo**: 新規leadから受注まで一気通貫。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
