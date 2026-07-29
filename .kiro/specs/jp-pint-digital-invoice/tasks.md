# Implementation Plan — JP PINTデジタルインボイス

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T102〜T107はL0〜L3の定向test・直接回帰、T108でL4全量を実行する。
> canonical model/schema変更はL3、provider sandbox全体受入はMで行う。

- [ ] 0. G5/provider/spec version spike
  - **Objective**: 0. G5/provider/spec version spike を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: provider契約/API/webhook/validator/test participant/spec version/料金/SLA。
  - **Demo**: 契約済みならprovider sandbox送受信、未契約なら公式fixture/mockの証跡とB1/B2/MをPASSにしないblocker記録。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

- [ ] F1. participant/digital invoice/event DDL
  - **Objective**: F1. participant/digital invoice/event DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V74/V1/H2/smoke、state/idempotency。
  - **テスト要件**: unique/status/event order。
  - **Demo**: F1. participant/digital invoice/event DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

- [ ] F2. CanonicalInvoice/renderer/validator
  - **Objective**: F2. CanonicalInvoice/renderer/validator を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: version adapter、XML security、validation report archive。
  - **テスト要件**: official fixture/golden/rounding/XXE。
  - **Demo**: 既存invoiceをvalidatorへ通す。

- [ ] B1. provider送信/status/webhook
  - **Objective**: B1. provider送信/status/webhook を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: accounting job再利用、participant verify、署名、fallback。
  - **テスト要件**: retry/duplicate/fake/out-of-order。
  - **Demo**: sandbox送信→delivered。

- [ ] A1. 設定/送信/状態UI
  - **Objective**: A1. 設定/送信/状態UI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 顧客preference、validation、status、XML/receipt link。
  - **テスト要件**: permission/participant未検証/field mask。
  - **Demo**: PDF顧客とPeppol顧客を別送信。

- [ ] B2. 受信review
  - **Objective**: B2. 受信review を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: secure parse/archive/match/review→purchase候補。
  - **テスト要件**: duplicate/不正XML/照合/人手確定。
  - **Demo**: 受信invoiceをBP支払候補へ。

- [ ] M. provider受入/回帰
  - **Objective**: M. provider受入/回帰 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL/provider official conformance。
  - **Demo**: end-to-end送受信と障害復旧。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
