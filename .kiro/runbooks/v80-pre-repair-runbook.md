# Runbook: V80 Pre-Repair Boundary Verification & Recovery

## 目的
`V80__order_acceptance_workflow.sql` の適用途中で早期失敗（マーカー作成前）が発生したデータベース環境に対し、無用な `Flyway repair` による新規契約の誤免除を防止し、確実なリカバリ手順を提供する。

## リカバリ手順

> 対象は `flyway_schema_history.version='80' AND success=0` が1件あり、かつ
> `t_contract_acceptance_backfill.contract_id=0` が未作成のDBだけである。条件が違う場合は停止し、
> DBA/実装担当へエスカレーションする。公開済みV80のchecksum repairを一般化してはならない。

### 1. 状態判定 (Detection)
1. DBスナップショットまたは論理backupを取得し、復元テスト済みのartifact IDを作業記録へ残す。
2. `flyway_schema_history` のV80 failed rowと、marker sentinelの有無を確認する。
3. 失敗開始時点のbackup/deployment記録から、V80着手前に存在した契約IDを特定する。時刻や連番だけで推測しない。

### 2. 境界保護 & 手動アロワリスト (Boundary Protection & Allowlist)
1. 証明済みIDを `-ApprovedLegacyContractIds` へ明示し、次を実行する。証明できない場合は引数を空にする。
   `powershell -File scripts/v80-pre-repair.ps1 -DbHost <host> -DbName <db> -DbUser <user> -DbPass <secret> -ApprovedLegacyContractIds 101,102`
2. scriptはfailed V80を確認後、sentinel `0` と証明済みIDだけをmarkerへCOMMITする。空allowlistではsentinelのみを作り、全契約を検収要のまま保持する。
3. 件数assertが失敗した場合は `flyway repair` を実行せず、backupへ復元する。

### 3. Migration Repair & 順方向適用 (Repair & Reconciliation)
1. marker/sentinelと承認済みID一覧をexportし、作業記録へ添付する。
2. failed V80だけを対象に `Flyway repair` を実行してスキーマ履歴を整合させる。
3. V80再適用後、V81を順方向適用する。V80のファイル自体は変更しない。
4. `uk_contract_order_line` の全列/順序/prefix/UNIQUE、`fk_contract_order_line` の参照先とCASCADE/SET NULL、CHECK、hash claimをpost-assertする。
5. allowlist外契約が `acceptance_required=1`、allowlist内契約だけが理由付き`0`であることを照合する。

### 4. ロールバック & 再試行 (Rollback & Retry)
1. V80/V81適用途中で失敗した場合、追加の手動DDL/DMLを行わずbackupへ復元する。
2. 原因を修正後、復元DBに対して本runbookを先頭から再実行する。
3. sentinel作成済みDBでscriptを再実行してもmarkerは変更されない。
