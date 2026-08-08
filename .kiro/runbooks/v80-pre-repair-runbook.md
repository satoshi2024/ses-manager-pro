# Runbook: V80 Pre-Repair Boundary Verification & Recovery

## 目的
`V80__order_acceptance_workflow.sql` の適用途中で早期失敗（マーカー作成前）が発生したデータベース環境に対し、無用な `Flyway repair` による新規契約の誤免除を防止し、確実なリカバリ手順を提供する。

## リカバリ手順

### 1. 状態判定 (Detection)
1. `t_contract_acceptance_backfill` マーカーテーブルの存在を確認する。
2. マーカーテーブルが存在しない場合、V80 はマーカー作成前に失敗している。

### 2. 境界保護 & 手動アロワリスト (Boundary Protection & Allowlist)
1. マーカー不在環境で失敗中に挿入された全ての契約は、安全側に倒して `acceptance_required = 1` に維持する。
2. 業務上真に検収不要と確認された契約のみ、明示的な手動アロワリスト SQL を実行して `acceptance_exemption_reason` を添えて個別更新する。

### 3. Migration Repair & 順方向適用 (Repair & Reconciliation)
1. `Flyway repair` を実行してスキーマ履歴を整合させる。
2. `V81__order_acceptance_remediation.sql` を適用し、INDEX/FK修復および `chk_contract_acceptance_exemption` 制約を追加する。

### 4. ロールバック & 再試行 (Rollback & Retry)
1. 失敗時は `scripts/v80-pre-repair.ps1` を再実行し、境界アサーションを検証する。
