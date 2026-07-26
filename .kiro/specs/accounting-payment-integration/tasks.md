# Implementation Plan — 会計・支払連携

- [ ] 0. G4/API spike/canonical mapping
  - **Objective**: 0. G4/API spike/canonical mapping を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: plan/API可否、sandboxまたはofficial fixture response、勘定/税/部門/取引先mapping、rate limit、fallback。
  - **Demo**: sandboxがあれば最小売上/仕入1件、未契約ならWireMock/official fixtureのspikeと本番blocker記録（本番コード変更なし）。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

- [ ] F1. connection/mapping/job DDLと既存connection移行
  - **Objective**: F1. connection/mapping/job DDLと既存connection移行 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V73/V1/H2/smoke、暗号/token race/outbox。
  - **テスト要件**: unique/rotation/claim/CAS/tenant。
  - **Demo**: F1. connection/mapping/job DDLと既存connection移行 の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

- [ ] F2. AccountingProvider/freee/CSV
  - **Objective**: F2. AccountingProvider/freee/CSV を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: canonical DTO、HTTP adapter、error分類、request ID。
  - **テスト要件**: WireMock全status/timeout/秘密非ログ。
  - **Demo**: F2. AccountingProvider/freee/CSV の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

- [ ] A1. mapping/preview/job管理UI
  - **Objective**: A1. mapping/preview/job管理UI を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: connection health、mapping不足、preview、retry/cancel。
  - **テスト要件**: 財務permission、CSRF、二重click。
  - **Demo**: validation error修正→retry成功。

- [ ] B1. 売上/取消連携
  - **Objective**: B1. 売上/取消連携 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: invoice approval後outbox、external ID、訂正/取消。
  - **テスト要件**: 10回再送1件、取消状態、金額照合。
  - **Demo**: 請求→freee sandbox→取消。

- [ ] B2. BP/経費/支払連携
  - **Objective**: B2. BP/経費/支払連携 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: purchase/expense、payment sync、振込guard（採用時）。
  - **テスト要件**: 口座変更/二重支払/税/手数料/源泉。
  - **Demo**: BP支払→外部→支払済sync。

- [ ] B3. 月次照合/closing
  - **Objective**: B3. 月次照合/closing を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 差異matrix、drilldown、closing warning/block。
  - **テスト要件**: 内部のみ/外部のみ/金額差/ignore理由。
  - **Demo**: 不一致解消後に締め可能。

- [ ] M. 回帰/障害訓練
  - **Objective**: M. 回帰/障害訓練 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL/sandbox、429/停止/復旧。
  - **Demo**: provider停止中に内部業務継続→復旧後再送。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
