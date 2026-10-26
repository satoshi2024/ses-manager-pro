# V182 本番マイグレーションおよびデータ修復 実行運用手順書（Runbook Draft）

## 1. 文書概要・目的・安全統制規則 (Overview, Objectives & Safety Rules)

### 1.1 目的と適用範囲
本書は、`V182__nf09_tenant_scope_hardening.sql` の本番データベース適用、およびマイグレーション適用直後に生成される `t_nf09_scope_repair_queue`（NF09 tenant・法人帰属修復キュー）に隔離された未解決行を安全かつ確実に解消するための**実行運用手順書草案（Runbook Draft）**である。

### 1.2 安全絶対規範（Red Lines）
1. **本番 DB への直接 Ad-hoc 更新の絶対禁止**:
   GUI ツールや手動の単発 `UPDATE` 文を本番 DB に直接発行することを固く禁ずる。すべてのデータ修復は、本書に規定されたトランザクション境界、CAS（Compare-And-Swap）、ロック順序、および監査ログ記録に準拠した正式なツール／スクリプト経由でのみ実行する。
2. **未知行の任意・デフォルトテナント割当の絶対禁止**:
   所属テナントや法人が判定できないレコード（孤立インボイス、所有者未確定アカウント等）に対し、**便宜上適当なデフォルトテナントや管理用テナントを推測で割り当てて解決とすることは固く禁止する**。必ず第 7 節に規定する正当な証憑調査および業務 Owner の確認・決裁を得て解決する。
3. **合成 HUMAN サインオフの非代替性**:
   リハーサル演練で用いられた `resolved_by = 1001L` 等の合成値は、技術的 CAS 検証のためのダミー値に過ぎず、**業務 Owner、財務統括責任者、法務・コンプライアンス責任者の実サインオフを法的に代替するものでは決してない**。
4. **本番修復実行プログラム（Repair Executor）の未実装・未承認ステータス**:
   本番環境用のデータ修復自動実行プログラム（Production Repair Executor）は現時点で**未実装・未承認**である。テストコード `V182TenantScopeHardeningRehearsalMySqlTest` 内のヘルパーメソッド群（`repairInvoiceInTransaction`, `repairPeppolConflictInTransaction` 等）は、Disposable コンテナでのリハーサル検証用に作られたテスト専用ヘルパーに過ぎず、**本番運用の実行エントリポイントでは決してない**。
5. **本番 Owner 承認ステータス**:
   本書の作成時点において、本番適用および実データ修復に関する各業務 Owner（財務、営業、EDI、人事、インフラ）の正式サインオフは **PENDING（未承認）** として保持する。独立 Plan Review（Astra）および関連統括責任者の承認完了後に本番運用承認へ移行する。

---

## 2. 事前準備・バックアップ・PITR 要件 (Prerequisites: Backup, Restore & PITR)

本番マイグレーション実行前に、以下の前置条件がすべて満たされていることを確認しなければならない。

### 2.1 前提諸元の区分定義（「確認済みの事実」と「推奨値・計画目標」）
運用計画における重大な誤認を排除するため、本番前提諸元を以下のように厳格に区分する：

| 項目 | 確認済みの事実 (Confirmed Facts) | 推奨値・計画目標 (Suggested / Target SLA) | 判定ステータス |
|---|---|---|---|
| **本番 DB アーキテクチャ** | リハーサルは Docker Disposable `mysql:8.0`（tmpfs マウント、単一ノード）で実施・通過済み。 | 本番は Multi-AZ マネージド MySQL 8 / Amazon Aurora クラスタ、Read Replica 構成を推奨。 | **要インフラ確認 (PENDING)** |
| **データ規模・容量** | 合成テストデータ（数十〜数百件）によるスキーマ遷移・キュー隔離ロジックの成立を確認済み。 | 本番対象テーブルは数万〜数百万円規模の実ビジネスレコードを想定。実データ件数は Preflight で測定。 | **Preflight で確定 (PENDING)** |
| **DDL 実行所要時間** | Disposable コンテナ上で全 DDL/DML が数秒で完了することを確認済み。 | 本番テーブルサイズおよび I/O 負荷に応じ、数分〜数十分の保守ウィンドウ枠を確保。 | **保守計画値 (PENDING)** |
| **Binlog 保持期間** | テスト環境では Binlog 永続保持なし（コンテナ破棄で破棄）。 | `binlog_format = ROW`、保持期間最低 7 日間以上を必須要件として推奨。 | **要 DBA 確認 (PENDING)** |
| **演練結果の有効期限** | Commit `27a000f2225f272fe2c3b5094de6325c7714dd41` にて 5 シナリオ完全合格を確認済み。 | スキーマおよびデータ構造にドリフトがない限り、演練完了後 30 日以内を作業有効枠として推奨。 | **計画基準日設定 (PENDING)** |
| **RTO / RPO** | コンテナ障害時のプロセス中断およびログ退避が即時実行可能であることを確認済み。 | 本番 PITR 復旧目標時間: RTO ≤ 60 分以内、目標復旧時点: RPO = 0 秒（データ欠損ゼロ）。 | **SLA 目標値 (PENDING)** |

### 2.2 フルバックアップとポイントインタイムリカバリ（PITR）要件
1. **直前フルスナップショットの取得**:
   - マイグレーション作業ウィンドウ開始直前に、RDS/Aurora の物理クラスタスナップショット、または整合性のある論理バックアップ（mysqldump / Percona XtraBackup）を取得し、ステータスが `available` であることを確認する。
2. **Binlog（バイナリログ）保持と PITR 境界点の記録**:
   - MySQL のバイナリログが有効（`log_bin = ON`, `binlog_format = ROW`）であり、十分な保持期間（推奨目標: 最低 7 日間以上、実環境設定値は DBA 確認待ち・PENDING）が確保されていることを確認する。
   - マイグレーション直前のバイナリログファイル名およびポジション（`SHOW MASTER STATUS` または `SHOW BINARY LOG STATUS`）、および正確な JST タイムスタンプ（秒単位）を記録・固定する。
3. **リストア演練実績と目標 SLA の確認**:
   - リストア演練実績および復旧目標値（計画目標基準: 30 日以内のリストア演練実績、目標 RTO ≤ 60分、目標 RPO = 0秒。いずれも確定値ではなく目標値・PENDING）についてインフラ／DBA チームと確認を行う。

### 2.3 アプリケーション保守モードとトラフィック遮断
- V182 は複数のテーブル定義変更（`ALTER TABLE`）とインデックス再構築を伴うため、実行中にメタデータロック（MDL）競合が発生するリスクがある。
- マイグレーション実施中は、SES Manager Pro の全バックエンドインスタンスを**保守モード（Maintenance Mode: 書込 API 遮断、スケジューラジョブ完全停止、Web 画面は 503 保守案内）**に移行させ、アクティブな DB コネクションを完全にドレイン（排水）する。

---

## 3. 事前点検（Preflight）読取専用 SQL 仕様 (Preflight Read-Only Inspection)

マイグレーション実行の「Go / No-Go」を判断するため、以下の読取専用クエリを実行し、すべての期待結果を満たすことを確認する。1件でも期待に反する場合は直ちに実行を中止（ABORT）する。

### Preflight SQL-01: Flyway スキーマ履歴ベースラインの確認
- **目的**: 直前のマイグレーションが正常完了しており、V182 が未適用であることを確認する。
```sql
SELECT installed_rank, version, description, type, script, success, installed_on
  FROM flyway_schema_history
 WHERE version IN ('181', '182', '183', '184')
 ORDER BY installed_rank DESC;
```
- **期待結果**:
  - `version = '181'` のレコードが 1 件のみ存在し、`success = 1` であること。
  - `version IN ('182', '183', '184')` の行が存在しないこと。

### Preflight SQL-02: 対象 4 テーブルの存在確認
- **目的**: V182 が変更対象とするテーブルがすべて存在することを確認する。
```sql
SELECT table_name
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('t_peppol_participant', 't_digital_invoice', 't_digital_invoice_event', 't_external_account_reference');
```
- **期待結果**: 正確に 4 行返却されること。

### Preflight SQL-03: 追加予定列の非存在確認（冪等性担保）
- **目的**: 既存テーブルに `tenant_id` 等の対象列が重複して存在しないことを確認する（MySQL の `ADD COLUMN` 重複エラー 1060 を未然に防止）。
```sql
SELECT table_name, column_name
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name IN ('t_peppol_participant', 't_digital_invoice', 't_digital_invoice_event', 't_external_account_reference')
   AND column_name IN ('tenant_id', 'legal_entity_id', 'callback_active_slot');
```
- **期待結果**: **0 行**（1 行も存在しないこと）。

### Preflight SQL-04: 修復キューテーブルの非存在確認
- **目的**: 新規作成テーブル `t_nf09_scope_repair_queue` が未作成であることを確認する。
```sql
SELECT table_name
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name = 't_nf09_scope_repair_queue';
```
- **期待結果**: **0 行**。

### Preflight SQL-05: 顧客マスタスコープ健全性と未解決候補の事前把握
- **目的**: 回填の基点となる `m_customer` において、`tenant_id` または `legal_entity_id` が未設定のレコード件数を把握する。
```sql
SELECT COUNT(*) AS total_active_customers,
       SUM(CASE WHEN tenant_id IS NULL OR tenant_id = '' OR legal_entity_id IS NULL THEN 1 ELSE 0 END) AS unassigned_scope_customers
  FROM m_customer
 WHERE deleted_flag = 0;
```
- **期待結果**: クエリが正常完了すること。`unassigned_scope_customers` の件数は事後点検時のインボイス／Peppol キュー投入想定数と整合性を照合するために記録する。

### Preflight SQL-06: Peppol 重複コールバック識別子の事前検知
- **目的**: マイグレーション実行時に `__ses_v182_peppol_identity_conflicts` により `CONFLICT_REPAIR` に隔離される重複 Peppol レコードを事前特定する。
```sql
SELECT provider, scheme_id, participant_id, COUNT(*) AS duplicate_verified_count
  FROM t_peppol_participant
 WHERE deleted_flag = 0 AND status = 'VERIFIED'
 GROUP BY provider, scheme_id, participant_id
HAVING COUNT(*) > 1;
```
- **期待結果**: クエリが正常完了すること。抽出されたグループ数・レコード件数を記録する（Postflight でのキュー登録数と厳密に一致することを確認）。

### Preflight SQL-07: 不正な監査主体（Actor Pair）レコードの事前検知
- **目的**: V182 の正規化処理により `LEGACY_UNRESOLVED` に置換されるレコード件数を事前把握する。
```sql
SELECT COUNT(*) AS legacy_unresolved_candidate_invoices
  FROM t_digital_invoice
 WHERE actor_type IS NULL OR confirmation_source IS NULL
    OR NOT (
        (actor_type = 'HUMAN' AND confirmation_source = 'MANUAL_API' AND human_user_id IS NOT NULL AND human_user_id > 0)
        OR (actor_type = 'SYSTEM' AND confirmation_source = 'SCHEDULER_POLL' AND human_user_id IS NULL)
        OR (actor_type = 'PROVIDER' AND confirmation_source IN ('PROVIDER_SYNC', 'PROVIDER_CALLBACK') AND human_user_id IS NULL)
        OR (actor_type = 'LEGACY_UNRESOLVED' AND confirmation_source = 'LEGACY_UNRESOLVED' AND human_user_id IS NULL)
    );
```
- **期待結果**: クエリが正常完了し、件数が記録されること。

---

## 4. 本番マイグレーション実行手順（V182 Schema Migration Execution）

### 4.1 実行ステップ
1. **保守モード突入確認**: 全トラフィック遮断および DB アイドル状態の確認。
2. **Flyway マイグレーション実行**:
   Flyway CLI または専用デプロイジョブを用い、`target = 182` を指定して実行する。
   ```bash
   # 例: Flyway CLI 実行コマンド
   flyway -target=182 migrate
   ```
3. **実行ログの保全**: 標準出力および標準エラー出力を `v182_migration_production_$(date +%Y%m%d_%H%M%S).log` として永続ストレージに記録。

---

## 5. 事後点検（Postflight）読取専用 SQL 仕様 (Postflight Read-Only Verification)

マイグレーション実行直後、修復作業に着手する前に以下の検証クエリを実行し、スキーマ・制約・キュー隔離が完全に成立していることを検証する。

### Postflight SQL-01: Flyway 適用成功確認
- **目的**: V182 が `success = 1` で正常完了したことを確認する。
```sql
SELECT installed_rank, version, description, script, checksum, installed_on, execution_time, success
  FROM flyway_schema_history
 WHERE version = '182';
```
- **期待結果**: `version = '182'`、`success = 1` の行が 1 件返却されること。

### Postflight SQL-02: 追加列および型・NULL許容性の検証
- **目的**: 対象 4 テーブルに `tenant_id` と `legal_entity_id` が定義通り追加されたことを確認する。
```sql
SELECT table_name, column_name, data_type, character_maximum_length, is_nullable
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name IN ('t_peppol_participant', 't_digital_invoice', 't_digital_invoice_event', 't_external_account_reference')
   AND column_name IN ('tenant_id', 'legal_entity_id')
 ORDER BY table_name, column_name;
```
- **期待結果**: 正確に 8 行。`tenant_id` は `varchar(100)`、`legal_entity_id` は `bigint`、すべて `is_nullable = 'YES'`。

### Postflight SQL-03: Peppol 仮想生成列の検証
- **目的**: `callback_active_slot` が VIRTUAL GENERATED 列として正しく作成されていることを確認する。
```sql
SELECT table_name, column_name, data_type, extra, generation_expression
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name = 't_peppol_participant'
   AND column_name = 'callback_active_slot';
```
- **期待結果**: 1 行返却。`extra` に `VIRTUAL GENERATED` が含まれ、`generation_expression` が `case when ((deleted_flag = 0) and (status = 'VERIFIED')) then 1 else NULL end` と一致すること。

### Postflight SQL-04: 制約（CHECK・外部キー・一意インデックス）の厳格検証
- **目的**: V182 で追加された CHECK 制約（ENFORCED 状態含む）、3列複合外部キー（列順・参照列・RESTRICT 規則）、および 7 つの複合一意インデックス（列順・一意性）が完全に成立していることを検証する。

#### (a) CHECK 制約および ENFORCED 検証（正しい JOIN 構文）
```sql
SELECT tc.table_name,
       tc.constraint_name,
       cc.check_clause,
       tc.enforced
  FROM information_schema.table_constraints tc
  JOIN information_schema.check_constraints cc
    ON cc.constraint_schema = tc.constraint_schema
   AND cc.constraint_name = tc.constraint_name
 WHERE tc.constraint_schema = DATABASE()
   AND tc.constraint_name IN ('ck_digital_invoice_actor_pair', 'ck_digital_invoice_event_actor_pair')
 ORDER BY tc.table_name;
```
- **期待結果**: 正確に 2 行。
  1. `t_digital_invoice`: `ck_digital_invoice_actor_pair`, `enforced = 'YES'`
  2. `t_digital_invoice_event`: `ck_digital_invoice_event_actor_pair`, `enforced = 'YES'`

#### (b) 3列複合外部キー（列順・参照先列順・RESTRICT 規則）の検証
```sql
SELECT rc.constraint_name,
       rc.table_name,
       kcu.column_name,
       rc.referenced_table_name,
       kcu.referenced_column_name,
       kcu.ordinal_position,
       rc.update_rule,
       rc.delete_rule
  FROM information_schema.referential_constraints rc
  JOIN information_schema.key_column_usage kcu
    ON kcu.constraint_schema = rc.constraint_schema
   AND kcu.constraint_name = rc.constraint_name
 WHERE rc.constraint_schema = DATABASE()
   AND rc.constraint_name = 'fk_digital_invoice_event_scope'
 ORDER BY kcu.ordinal_position;
```
- **期待結果**: 正確に 3 行。
  - 行 1 (`ordinal_position = 1`): `tenant_id` → 参照先 `tenant_id`
  - 行 2 (`ordinal_position = 2`): `legal_entity_id` → 参照先 `legal_entity_id`
  - 行 3 (`ordinal_position = 3`): `digital_invoice_id` → 参照先 `id`
  - すべての行で `update_rule = 'RESTRICT'` かつ `delete_rule = 'RESTRICT'`。

#### (c) 7 つの複合一意インデックス（列順・一意性）の検証
```sql
SELECT table_name,
       index_name,
       non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ', ') AS indexed_columns_in_order
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND index_name IN (
       'uk_digital_invoice_scope_message',
       'uk_digital_invoice_scope_send',
       'uk_digital_invoice_scope_id',
       'uk_peppol_scope_owner',
       'uk_peppol_callback_active',
       'uq_ext_scope_idempotency',
       'uk_nf09_scope_repair_entity'
   )
 GROUP BY table_name, index_name, non_unique
 ORDER BY table_name, index_name;
```
- **期待結果**: 正確に 7 行返却され、すべての行で **`non_unique = 0`（一意）** であり、列順序が以下と完全一致すること：
  1. `t_digital_invoice`: `uk_digital_invoice_scope_id` → `tenant_id, legal_entity_id, id`
  2. `t_digital_invoice`: `uk_digital_invoice_scope_message` → `tenant_id, legal_entity_id, message_id`
  3. `t_digital_invoice`: `uk_digital_invoice_scope_send` → `tenant_id, legal_entity_id, invoice_id, direction, profile, specification_version, send_active_slot`
  4. `t_external_account_reference`: `uq_ext_scope_idempotency` → `tenant_id, legal_entity_id, idempotency_key`
  5. `t_nf09_scope_repair_queue`: `uk_nf09_scope_repair_entity` → `entity_type, entity_id`
  6. `t_peppol_participant`: `uk_peppol_callback_active` → `provider, scheme_id, participant_id, callback_active_slot`
  7. `t_peppol_participant`: `uk_peppol_scope_owner` → `tenant_id, legal_entity_id, owner_type, owner_id`

### Postflight SQL-05: 修復キュー初期隔離件数の内訳確認
- **目的**: V182 の実行によって `t_nf09_scope_repair_queue` に投入された未解決行の内訳を把握する。
```sql
SELECT entity_type, reason, status, COUNT(*) AS queue_count
  FROM t_nf09_scope_repair_queue
 GROUP BY entity_type, reason, status
 ORDER BY entity_type, reason;
```
- **期待結果**: 全レコードの `status` が `'PENDING'` であること。Preflight SQL-06 で検知した重複 Peppol 件数と `CALLBACK_IDENTITY_CONFLICT` 件数が合致すること。

### Postflight SQL-06: 未解決行の完全隔離性検証（漏れゼロの確認）
- **目的**: 業務テーブル上で `tenant_id` または `legal_entity_id` が NULL のレコードが、**例外なくすべて修復キューに PENDING で収容されていること**を検証する。
```sql
-- 1. 電子インボイスの未収容チェック
SELECT COUNT(*) AS unisolated_invoice_count
  FROM t_digital_invoice d
  LEFT JOIN t_nf09_scope_repair_queue q
    ON q.entity_type = 'DIGITAL_INVOICE' AND q.entity_id = d.id
 WHERE (d.tenant_id IS NULL OR d.legal_entity_id IS NULL)
   AND (q.id IS NULL OR q.status <> 'PENDING');

-- 2. イベントの未収容チェック
SELECT COUNT(*) AS unisolated_event_count
  FROM t_digital_invoice_event e
  LEFT JOIN t_nf09_scope_repair_queue q
    ON q.entity_type = 'DIGITAL_INVOICE_EVENT' AND q.entity_id = e.id
 WHERE (e.tenant_id IS NULL OR e.legal_entity_id IS NULL)
   AND (q.id IS NULL OR q.status <> 'PENDING');

-- 3. Peppol参加者の未収容チェック
SELECT COUNT(*) AS unisolated_peppol_count
  FROM t_peppol_participant p
  LEFT JOIN t_nf09_scope_repair_queue q
    ON q.entity_type = 'PEPPOL_PARTICIPANT' AND q.entity_id = p.id
 WHERE (p.tenant_id IS NULL OR p.legal_entity_id IS NULL)
   AND (q.id IS NULL OR q.status <> 'PENDING');

-- 4. 外部アカウント参照の未収容チェック
SELECT COUNT(*) AS unisolated_account_count
  FROM t_external_account_reference a
  LEFT JOIN t_nf09_scope_repair_queue q
    ON q.entity_type = 'EXTERNAL_ACCOUNT' AND q.entity_id = a.id
 WHERE (a.tenant_id IS NULL OR a.legal_entity_id IS NULL)
   AND (q.id IS NULL OR q.status <> 'PENDING');
```
- **期待結果**: 上記 4 クエリの件数がすべて **0 件** であること（1 件でもあれば未収容の野良 NULL レコードが存在するため異常と判定）。

---

## 6. 未解決行（PENDING / 未確定行）の厳密な解消口径 (Strict Resolution Criteria)

データ修復フェーズの完了条件である「`PENDING = 0`」および「未確定行 = 0」の口径を厳密に定義する。

### 6.1 修復キューの結案口径
- `t_nf09_scope_repair_queue` において、`status = 'PENDING'` の行が **0 件**（全件 `status = 'RESOLVED'`）であること。

### 6.2 業務テーブルのスコープ確定口径
以下の 4 クエリがすべて **0 件** を返却すること：
```sql
SELECT COUNT(*) FROM t_digital_invoice WHERE tenant_id IS NULL OR legal_entity_id IS NULL;
SELECT COUNT(*) FROM t_digital_invoice_event WHERE tenant_id IS NULL OR legal_entity_id IS NULL;
SELECT COUNT(*) FROM t_peppol_participant WHERE tenant_id IS NULL OR legal_entity_id IS NULL;
SELECT COUNT(*) FROM t_external_account_reference WHERE tenant_id IS NULL OR legal_entity_id IS NULL;
```

### 6.3 論理削除行（`deleted_flag = 1`）の扱い
- **除外の禁止**: `deleted_flag = 1` の論理削除レコードであっても、監査ログや過去データ集計の整合性を保つため、**スコープが NULL のまま放置することは認められない**。
- **解決方針**:
  - 削除前に紐づいていた顧客／要員／組織情報から正当な `(tenant_id, legal_entity_id)` を特定して回填・結案する。
  - 実体が完全に消滅して特定不能なテストデータ残骸等である場合は、業務 Owner および法務コンプライアンス責任者の承認を得た上で、正規の廃止手順として処理する（決して任意のテナントを埋めない）。

### 6.4 Peppol 修復の 2 分類（通常スコープ未解決／コールバック衝突）と MySQL 1062 制御
`t_peppol_participant` における修復は、隔離要因に応じて以下の 2 分類（Type A / Type B）に明確に分離して取り扱う：

#### 1. Type A: 通常スコープ未解決（`SCOPE_UNRESOLVED`）の修復規程
- **対象**: コールバック識別子の競合を伴わず、単に所属組織や顧客のスコープが未確定なレコード。
- **修復原則**:
  - `owner_type` / `owner_id`（例: 顧客マスタ `m_customer`）に基づき、正当な `(tenant_id, legal_entity_id)` を特定して回填する。
  - **業務状態および削除フラグの原値維持**:
    - **`status`（業務状態）および `deleted_flag`（論理削除状態）は一切変更せず、ロック取得時の原値を完全に維持**する（勝手に `VERIFIED` や `DUPLICATE_DEPRECATED` に変更することは禁止）。
    - 例えば `status = 'PENDING_VERIFICATION'` や `status = 'VERIFIED'`、あるいは `deleted_flag = 1` の行は、その状態を保持したまま `tenant_id` と `legal_entity_id` のみが更新される。

#### 2. Type B: コールバック識別子衝突（`CALLBACK_IDENTITY_CONFLICT`）の修復規程
- **対象**: 同一 `(provider, scheme_id, participant_id)` を共有し、複数の有効レコードが存在することで複合一意制約 `uk_peppol_callback_active` 違反を回避するために `CONFLICT_REPAIR` に隔離されたレコード群。
- **修復原則と境界条件**:
  1. **勝者行および全淘汰（敗者）行の独立個別帰属**:
     - 衝突グループ内の正当勝者（唯一の活性化行）のみならず、**淘汰されるすべての敗者行についても、各レコード自身の `owner_type` / `owner_id` に基づき独立して正当な `(tenant_id, legal_entity_id)` を調査・特定し、回填しなければならない**。
     - **【禁止】敗者行を単に `status = 'DUPLICATE_DEPRECATED'` に変更しただけで、スコープを NULL のまま結案することは固く禁止する**（野良 NULL 残存の禁止、Postflight SQL-06 違反）。
  2. **異 Owner 間のスコープ複製の絶対禁止**:
     - 勝者行のスコープを、異なる `owner_id` を持つ敗者行へ便宜上コピーすることは固く禁止する。各行は必ず各行自身の正当な法的実体へ帰属させなければならない。
  3. **全行確定または全量保留（All-or-Nothing）**:
     - 衝突グループ内の 1 行でも正当なスコープが特定・確認できない場合、**グループ全体の修復を結案せず、全行のキューを `PENDING` に維持**する（部分結案の禁止）。
  4. **論理削除行（`deleted_flag = 1`）の扱い**:
     - 論理削除済みの行であっても、Postflight SQL-06 の全量 0-NULL 規則が例外なく適用され、正当なスコープの回填を必須とする。
  5. **重複活性化の物理遮断（MySQL 1062 Guard）**:
     - 衝突グループのうち、正当と確認された唯一の 1 件のみを `status = 'VERIFIED'`, `deleted_flag = 0` に更新する（これにより DB 仮想生成列 `callback_active_slot = 1` が自動算出される）。
     - 重複する他の全レコードは、各々の正当スコープを回填した上で `status = 'DUPLICATE_DEPRECATED'`（`callback_active_slot IS NULL`）に更新する。
     - 複合一意インデックス `uk_peppol_callback_active` は `(provider, scheme_id, participant_id, callback_active_slot)` で張られており、複数の `VERIFIED` 行が存在すると MySQL エラー `1062 (Duplicate entry '...' for key 'uk_peppol_callback_active')` が発生する。手動・スクリプトを問わず、2件以上を同時に `VERIFIED` とすることは物理的に拒否される。

#### 3. Peppol 修復正例・拒例シナリオマトリクス（Peppol Repair Scenarios）
以下のシナリオに基づき、正当な処理と拒絶（Fail-Stop）の境界を厳格に判定する：

| シナリオ ID | 状況・対象レコード構成 | 適用される修復処置 | 判定結果 | 判定理由・整合性根拠 |
|---|---|---|---|---|
| **PEP-SCN-01**<br/>(正例: 同一Owner重複) | 同一 Customer A (`tenant_1`, `entity_10`) に紐づく同一 Peppol ID の 2 レコード (Row 1, Row 2)。 | Row 2 を勝者とし `tenant_1, entity_10, status='VERIFIED'`。Row 1 を敗者とし `tenant_1, entity_10, status='DUPLICATE_DEPRECATED'`。両キュー結案。 | **ACCEPT（正例合格）** | 両行ともに同一 Owner のスコープで完全に確定し、`callback_active_slot` も 1 件のみ 1 となり一意制約および 0-NULL を完全充足。 |
| **PEP-SCN-02**<br/>(正例: 異Owner誤登録) | Peppol ID が誤って Customer B (`tenant_1`, `entity_20`) に重複登録。真の保有者は Customer A (`tenant_1`, `entity_10`)。 | Row 1 (Owner: A) を `tenant_1, entity_10, status='VERIFIED'`。Row 2 (Owner: B) には Customer B の正当スコープ `tenant_1, entity_20` を付与し `status='DUPLICATE_DEPRECATED'`。両キュー結案。 | **ACCEPT（正例合格）** | 勝者・敗者がそれぞれの正当 Owner へ独立帰属され、越境汚染なし。0-NULL も充足。 |
| **PEP-SCN-03**<br/>(拒例: 敗者NULL放置) | 勝者 Row 1 にスコープを付与して `VERIFIED` とするが、敗者 Row 2 はスコープを NULL のまま `status='DUPLICATE_DEPRECATED'` として結案しようとする。 | 敗者 Row 2 のスコープ未設定を検知し、処理を拒絶。キュー結案をブロック。 | **REJECT（拒絶・Fail-Stop）** | 業務テーブル上に野良 NULL が残存し、Postflight SQL-06 および全量 0-NULL 規則に違反するため。 |
| **PEP-SCN-04**<br/>(拒例: 異Ownerスコープ複製) | 勝者 Row 1 (Owner: Customer A) のスコープ `tenant_1, entity_10` を、Owner が異なる敗者 Row 2 (Owner: Customer B) に便宜上コピーしようとする。 | 異 Owner へのスコープコピーを検知し、処理を拒絶。 | **REJECT（拒絶・Fail-Stop）** | Customer B のレコードが Customer A の法的実体に誤帰属し、テナント・法人分離を破壊するため絶対禁止。 |
| **PEP-SCN-05**<br/>(拒例: 敗者未確定時の部分結案) | 勝者 Row 1 は確定したが、敗者 Row 2 の登録元 Owner が不明でスコープが特定できない。勝者のみ結案し、敗者を放置しようとする。 | 衝突グループ内の未解決残存を検知し、勝者を含めグループ全体の結案を拒絶。 | **REJECT（拒絶・Fail-Stop）** | 衝突グループは不可分の集約境界（Boundary B）であり、全行の帰属が確定しない限りグループ全体を PENDING に維持するため。 |

---

## 7. データ修復（Data Repair）の合法帰属判定・承認権限規程 (Attribution & Sign-Off)

データ修復にあたり、未知行を推測や便宜的デフォルト値で埋めることを防ぐため、カテゴリごとの特定調査手法および確認・承認権限者を以下のように定める。

| エンティティ | 隔離理由（Reason） | 発生要因 | 合法帰属の特定調査手法 | 正規承認権限者（実サインオフ） |
|---|---|---|---|---|
| `DIGITAL_INVOICE` | `SCOPE_CONFLICT` | 請求書（`t_invoice`）、発注書（`t_sales_order`）、契約書（`t_contract`）間で参照している顧客の所属法人が相違している | 取引基本契約書、発注書、納品検収書の実電子文書原本を照合し、真の契約締結法人・請求先法人を特定 | **財務管理責任者 (Finance Manager)** ＋ **顧客担当営業責任者** |
| `DIGITAL_INVOICE` | `SCOPE_UNRESOLVED` | 請求・発注・契約の全親行が存在しない孤立レコード | インボイス XML/PDF の事業者登録番号（T番号）、受領先口座、銀行振込入金照合ログから帰属法人を特定 | **財務統括責任者 (Finance Director)** ＋ **システム管理責任者** |
| `DIGITAL_INVOICE_EVENT` | `PARENT_SCOPE_UNRESOLVED` | 親インボイスが未解決のためスコープ未設定 | 親 `t_digital_invoice` の修復完了後、親の `(tenant_id, legal_entity_id)` を連動回填（単独での別テナント付与は禁止） | 親インボイスの承認に準拠 |
| `PEPPOL_PARTICIPANT` | `SCOPE_UNRESOLVED` | `owner_type != 'CUSTOMER'` または顧客のスコープが未確定 | 顧客マスタ `m_customer` の法人番号・契約書照合、または社内利用アカウントであるかの事実確認 | **営業管理責任者** ＋ **顧客アカウントOwner** |
| `PEPPOL_PARTICIPANT` | `CALLBACK_IDENTITY_CONFLICT` | 同一 Peppol ID が複数顧客／組織で重複登録されている | Peppol サービスプロバイダ（OpenPeppol / SMP ディレクトリ）の正式登録企業名・契約書を照合し、正当な 1 社を特定 | **EDI 統括リード** ＋ **法務コンプライアンス責任者** |
| `EXTERNAL_ACCOUNT` | `SCOPE_UNRESOLVED` | `assignee_type = 'USER'` で組織所属が曖昧、または要員未紐付け | 人事発令台帳、社内 SSO アカウント（Keycloak/IdP）の所属組織コード、業務委託基本契約書を照合 | **人事労務責任者** ＋ **社内情シス責任者 (Corporate IT)** |

### 監査ログ・サインオフ記録要件
- 修復を実行する際、`t_nf09_scope_repair_queue` の更新項目として以下を必須とする：
  - `resolved_by`: 実際に修復操作を実施した担当者の `sys_user.id`（正の整数値、NULL や 0 は不可）。
  - `resolution_note`: **承認起票番号（Ticket ID）、承認者役職・氏名、特定根拠となった証拠文書名・日付**を明記（例: `[APPR-20261026-0042] 財務責任者(山田)承認済。契約番号CNT-2026-089原本に基づき法人ID:501に決定`）。

---

## 8. 修復トランザクション・CAS・ロック順序・障害保全 (Transaction, CAS, Locking & Fail-Stop)

### 8.1 構造的トランザクション境界（Transaction Boundaries）の厳格定義
「1レコードごとの単純な逐件トランザクション」では、外部キー制約違反や重複コールバックの整合性破壊を招く。したがって、以下の**3つの集約トランザクション境界**を厳格に適用する：

```mermaid
flowchart TD
    subgraph BoundaryA [境界 A: 電子インボイス集約境界 (Invoice Aggregate)]
        LockInv[1. 親インボイス行 FOR UPDATE] --> LockEvts[2. 子イベント全行 id ASC で FOR UPDATE]
        LockEvts --> LockInvQ[3. 親キュー行 FOR UPDATE]
        LockInvQ --> LockEvtQs[4. 子イベントキュー全行 id ASC で FOR UPDATE]
        LockEvtQs --> ClassifyEvts{5. 子イベント逐項分類<br/>1:一致確認 / 2:欠落回填 / 3:衝突}
        ClassifyEvts -->|3:衝突あり| FailStopA[即時中止 ROLLBACK<br/>全行PENDING維持・人工裁定へ]
        ClassifyEvts -->|1及び2のみ| UpdInv[6. 親インボイス更新 version CAS]
        UpdInv --> UpdEvts[7. 分類2の子イベントのみ親スコープ回填<br/>精密ID・親ID・原値NULL-safe比較]
        UpdEvts --> ResInvQ[8. 親キュー結案 version CAS]
        ResInvQ --> ResEvtQs[9. 子イベントキュー全結案 version CAS]
    end

    subgraph BoundaryB [境界 B: Peppol コールバック衝突グループ集約境界 (Peppol Conflict Group)]
        LockPep[1. 衝突 Peppol レコード全行を id ASC で FOR UPDATE] --> LockPepQs[2. 対象キュー全行を id ASC で FOR UPDATE]
        LockPepQs --> CheckAllOwners{3. 勝者及び全敗者の<br/>正当Ownerスコープ特定可能か?}
        CheckAllOwners -->|1行でも特定不能| FailStopB[即時中止 ROLLBACK<br/>グループ全行PENDING維持]
        CheckAllOwners -->|全行特定完了| UpdWinner[4. 正当行 1 件にOwnerスコープ回填＋VERIFIED]
        UpdWinner --> UpdLosers[5. 淘汰行全件に各々正当Ownerスコープ回填＋DUPLICATE_DEPRECATED]
        UpdLosers --> ResPepQs[6. 対象キュー全行を結案 version CAS]
    end

    subgraph BoundaryC [境界 C: 外部アカウント単一境界 (External Account Aggregate)]
        LockAcc[1. 外部アカウント行 FOR UPDATE] --> LockAccQ[2. キュー行 FOR UPDATE]
        LockAccQ --> UpdAcc[3. 外部アカウント更新 version CAS]
        UpdAcc --> ResAccQ[4. キュー結案 version CAS]
    end
```

1. **境界 A: 電子インボイス集約境界（Digital Invoice + Child Events + Queues）**:
   - **理由**: 3列複合外部キー `fk_digital_invoice_event_scope` により、親 `t_digital_invoice` と子 `t_digital_invoice_event` は `(tenant_id, legal_entity_id, digital_invoice_id)` が完全に一致していなければならない。親と子を別トランザクションで修復すると中間状態で外部キー違反が発生し、またイベントが親と異なるスコープへ誤分離されるリスクがある。
   - **境界範囲**: 対象の親インボイス、紐づく全子イベント、親のキュー行、および全子イベントのキュー行を**単一の `@Transactional` 内でアトミックに修復・結案**する。子イベントに親と矛盾する非 NULL スコープが存在する場合は、即座に全体を ROLLBACK して中断する。
2. **境界 B: Peppol コールバック衝突グループ集約境界（Peppol Identity Group Aggregate）**:
   - **理由**: 同一 `(provider, scheme_id, participant_id)` を共有する複数行は、1 件を `VERIFIED`（`callback_active_slot=1`）とし、残りを `DUPLICATE_DEPRECATED`（`callback_active_slot IS NULL`）とする処置が不可分である。1件ずつ別トランザクションで処理すると、一意制約 `uk_peppol_callback_active` 違反や中間状態でのアクティブ二重化を招く。
   - **境界範囲**: 同一識別子を持つすべての重複 Peppol レコード（勝者行および全淘汰行）に対し、それぞれ独立して正当な Owner スコープを回填した上で、勝者 1 件を `VERIFIED`、全淘汰行を `DUPLICATE_DEPRECATED` に更新し、その全キュー行を**単一の `@Transactional` 内でアトミックに修復・結案**する。1 行でも正当スコープが特定できない行がある場合は、グループ全体を結案せず ROLLBACK して `PENDING` に維持する。
3. **境界 C: 外部アカウント集約境界（External Account Aggregate）**:
   - 対象の `t_external_account_reference` 1 件と、その `t_nf09_scope_repair_queue` 1 件を単一の `@Transactional` 内で修復・結案する。

### 8.2 実スキーマに基づくエンティティ別並行制御（Concurrency Control per Actual Schema）
文書の都合で実 DB に存在しない列を追加する migration を作成することは禁止されている。実際のスキーマに基づき、エンティティ別に以下の通り並行制御を実装する：

#### (a) 実際の `version` 列を持つエンティティ（`t_digital_invoice`, `t_external_account_reference`, `t_nf09_scope_repair_queue`）
- `t_digital_invoice`（BIGINT `version`）、`t_external_account_reference`（INT `version`）、`t_nf09_scope_repair_queue`（INT `version`）は、明示的な version CAS を用いる。
```sql
-- 例: t_digital_invoice の更新
UPDATE t_digital_invoice
   SET tenant_id = :resolvedTenantId,
       legal_entity_id = :resolvedLegalEntityId,
       version = version + 1
 WHERE id = :invoiceId
   AND tenant_id IS NULL
   AND version = :expectedInvoiceVersion;
-- 更新行数 == 1 を検証。0 件の場合は即座に例外をスローしてロールバック。

-- 例: t_nf09_scope_repair_queue の結案
UPDATE t_nf09_scope_repair_queue
   SET status = 'RESOLVED',
       resolved_at = NOW(),
       resolved_by = :humanUserId,
       resolution_note = :resolutionNote,
       version = version + 1
 WHERE id = :queueId
   AND status = 'PENDING'
   AND version = :expectedQueueVersion;
-- 更新行数 == 1 を検証。0 件の場合は即座に例外をスローしてロールバック。
```

#### (b) `version` 列を持たないエンティティ（`t_peppol_participant`, `t_digital_invoice_event`）
`t_peppol_participant` および `t_digital_invoice_event` には `version` 列が存在しない。したがって、以下の二重保護（悲観的行ロック ＋ 述語・原値比較条件付き UPDATE ＋ キュー側 version CAS）で並行制御を担保する：
##### 1. Peppol 参加者レコード（`t_peppol_participant`）の並行制御
- 更新前に必ず `SELECT ... FOR UPDATE`（衝突グループ時は `ORDER BY id ASC`）を発行して行ロックを確保。
- **Type A: 通常 `SCOPE_UNRESOLVED` の更新**:
  - 原値の `status` および `deleted_flag` を完全に保持したまま、スコープ未設定行に正当値を回填する：
  ```sql
  UPDATE t_peppol_participant
     SET tenant_id = :resolvedTenantId,
         legal_entity_id = :resolvedLegalEntityId
   WHERE id = :peppolId
     AND tenant_id IS NULL
     AND legal_entity_id IS NULL
     AND status = :lockedStatus
     AND deleted_flag = :lockedDeletedFlag;
  -- 更新行数 == 1 を検証。0 件の場合は即座にロールバック。
  ```
- **Type B: `CALLBACK_IDENTITY_CONFLICT` の更新**:
  - 勝者行（正当 Owner スコープ回填 ＋ `VERIFIED` 活性化）:
    ```sql
    UPDATE t_peppol_participant
       SET tenant_id = :winnerTenantId,
           legal_entity_id = :winnerLegalEntityId,
           status = 'VERIFIED',
           verified_at = NOW()
     WHERE id = :winnerId
       AND status = 'CONFLICT_REPAIR'
       AND deleted_flag = 0
       AND tenant_id <=> :lockedWinnerTenantId
       AND legal_entity_id <=> :lockedWinnerLegalEntityId;
    ```
    -- 更新行数 == 1 を検証。
  - 淘汰（敗者）行（**各敗者自身の正当 Owner スコープ回填** ＋ `DUPLICATE_DEPRECATED`）:
    ```sql
    UPDATE t_peppol_participant
       SET tenant_id = :loserTenantId,
           legal_entity_id = :loserLegalEntityId,
           status = 'DUPLICATE_DEPRECATED'
     WHERE id = :loserId
       AND status = 'CONFLICT_REPAIR'
       AND deleted_flag = 0
       AND tenant_id <=> :lockedLoserTenantId
       AND legal_entity_id <=> :lockedLoserLegalEntityId;
    ```
    -- 各淘汰行ごとに更新行数 == 1 を検証。
    ※**【禁止】敗者行のスコープを NULL のまま結案することは厳禁**。勝者のスコープを異 Owner の敗者へコピーすることも厳禁。
##### 2. 子イベントレコード（`t_digital_invoice_event`）の逐項分類と並行制御
- **MySQL 即時制約検査の技術的現実**:
  - 3列複合外部キー `fk_digital_invoice_event_scope`（`(tenant_id, legal_entity_id, digital_invoice_id)` 参照）は、**MySQL 8 の仕様により各 SQL 文の実行時点（Statement-by-Statement）で即時に RESTRICT 検査**が行われる（遅延外部キー検査は MySQL 未対応）。
  - したがって、`@Transactional` が付与されていることのみをもって文途中の一時的外部キー不整合を許容することはできず、**外部キー制約を無効化（`SET FOREIGN_KEY_CHECKS = 0`）することも固く禁止**する。本バッチでは既存の非 NULL 衝突スコープの再帰属は取り扱わない。
- **ロック取得と 3 分類（Three-way Item-by-item Classification）**:
  - 親インボイスのロック取得後、紐づく全子イベントを `SELECT ... WHERE digital_invoice_id = :parentInvoiceId ORDER BY id ASC FOR UPDATE` により行ロックする。
  - ロック取得した子イベント全行を、親インボイスの承認済スコープ `(:parentTenantId, :parentLegalEntityId)` に対し、**逐項（1行ずつ）以下の 3 分類で厳格に判定**する：
    1. **分類 1（完全一致・検証済）**:
       - `tenant_id` および `legal_entity_id` の**双方が親と完全一致**している行。
       - **処置**: 整合性が確認されたため、再更新は行わずスキップする（更新行数カウント $N_1 = 0$）。
    2. **分類 2（欠落回填・非衝突）**:
       - スコープに欠落（`tenant_id` または `legal_entity_id` のいずれか、あるいは双方が NULL）があり、かつ非 NULL の分量が存在する場合も親の値と一致している行。
       - **処置**: 承認済親スコープにより回填する。更新時は**精密なイベント ID、親 ID、およびロック取得時の原値を用いた NULL-Safe 等価比較（`<=>` または明示的 IS NULL / = 条件）**を必須とする：
         ```sql
         UPDATE t_digital_invoice_event
            SET tenant_id = :parentTenantId,
                legal_entity_id = :parentLegalEntityId
          WHERE id = :eventId
            AND digital_invoice_id = :parentInvoiceId
            AND tenant_id <=> :lockedEventTenantId
            AND legal_entity_id <=> :lockedEventLegalEntityId;
         ```
       - **更新検証**: 分類 2 に該当する各子イベントについて、更新行数が厳密に 1 行であることを検証する。分類 2 の対象行数が $N$ 件である場合、実行される UPDATE 文の合計影響行数は厳密に $N$ 行でなければならない。
    3. **分類 3（非空分量衝突・フェイルストップ）**:
       - `event.tenant_id` または `event.legal_entity_id` の**いずれか一方でも親の値と異なる非 NULL 値を保持**している行。
       - **処置**: **直ちにフェイルストップ（Fail-Stop）し、トランザクション全体を ROLLBACK** する。親スコープによる強制上書きや勝手な再帰属は絶対に行わず、キューを `PENDING` に維持して人間による調査・人工裁定へ回す。
- **重大禁止事項（スコープ検査の網羅性）**:
  - **`tenant_id` のみを検査して `legal_entity_id` の検証を省略することは重大な欠陥であり固く禁止する**。必ず両列の完全一致および衝突有無を判定しなければならない。
- **子イベントキューの個別 version CAS 結案**:
  - 分類 2 の回填がすべて成功し、分類 3 の衝突が皆無であることを確認した後、親キューおよび全子イベントキュー行（`t_nf09_scope_repair_queue`）を個別に version CAS（`WHERE id = :queueId AND version = :expectedQueueVersion AND status = 'PENDING'`）で `RESOLVED` に更新し、各々更新行数 == 1 を検証する。
- **影響行数の厳格検証と全体ロールバック**:
  - 更新行数が期待値（正当行は 1、淘汰行は 1、分類 2 イベントは $N$、キュー行は 1）と合致しない場合、**トランザクション全体を即座に ROLLBACK** する。

### 8.3 本番修復実行プログラム（Repair Executor）の実装要件と Staging 承認ゲート
本番適用に先立ち、以下の実行プログラム要件と Staging 検証ゲートを通過しなければならない：
1. **実装要件**:
   - テスト用ヘルパーから独立した本番専用 CLI／バッチツールとして実装すること。
   - 事前ドライラン機能（`--dry-run`: トランザクションをコミットせず計画される更新差分・影響行数・キュー結案内容を標準出力へ JSON 出力）を必須具備すること。
   - バッチレートリミット（例: 1 秒あたり最大 5 トランザクション）を設け、本番 DB の I/O およびレプリケーション遅延（Replication Lag）を監視しながら進行できること。
2. **Staging 検証ゲート（本番前必須実行）**:
   - 本番相当のデータ規模・分布を持つ Staging 環境において、本ツールを用いて未解決行の全量修復演練を実施すること。
   - 実行後に Postflight SQL-06（野良 NULL ゼロ件検証）およびキュー `status = 'PENDING'` ゼロ件検証が完全に PASS することを実証し、ログおよび証跡を添えて本番運用承認申請を行うこと。

### 8.4 失敗時のフェイルストップ（Fail-Stop）と現場証拠保全
- 処理中に以下のいずれかが発生した場合、トランザクションは直ちに**完全ロールバック（ROLLBACK）**する：
  1. 業務データまたはキューの CAS 更新行数が 0 行（他トランザクションによる更新競合）。
  2. ロック待ちタイムアウト（MySQL エラー 1205）またはデッドロック（MySQL エラー 1213）。
  3. 複合外部キーや一意インデックス違反（MySQL エラー 1062 等）。
- **処置**:
  - 当該レコード／集約グループの修復を中断し、キュー行を `PENDING` に維持する。
  - エラーコード、SQLState、対象 ID、スタックトレース、および直前の `information_schema.innodb_trx` / `performance_schema.data_lock_waits` 診断ログを保全ファイルに退避する。

---

## 9. 異常時対応：ロールバック（PITR）と前向修正（Forward-Fix）の判断分岐 (Rollback vs Forward-Fix)

### 9.1 MySQL DDL の暗黙コミット（Implicit Commit）という技術的現実
MySQL 8.0（InnoDB）において、`ALTER TABLE` などの DDL は実行時に暗黙のコミットを引き起こす。したがって、**V182 マイグレーションスクリプトが途中でエラー停止した場合、途中で実行された `ALTER TABLE` は自動ロールバックされず、中途半端に変更が残存する**。

### 9.2 障害発生時の意思決定ツリー（Decision Tree）

```mermaid
flowchart TD
    Fault[マイグレーション／修復作業中の異常発生] --> PhaseCheck{障害発生フェーズは?}

    PhaseCheck -->|事前点検 Preflight 失敗| PreflightAbort[即時中止 ABORT<br/>DB変更なし・PITR不要]

    PhaseCheck -->|V182 DDL実行中に失敗| DdlFault{障害原因の特定性と許容ダウンタイム}

    DdlFault -->|原因不明 / スキーマ汚染大 / 保守窓内| DecisionPITR[【原則】PITR 全面復元<br/>直前スナップショット+Binlog再生]
    DdlFault -->|原因が100%特定可能かつ局所的<br/>（例: 単一インデックス名衝突）| ForwardFixGate{Forward-Fix 承認ゲート<br/>リードアーキテクト + DBA + 法務}

    ForwardFixGate -->|全会一致で承認| ApplyForwardFix[限定的前向修正パッチ適用 (PENDING)]
    ForwardFixGate -->|1名でも懸念あり| DecisionPITR

    PhaseCheck -->|事後データ修復中のエラー| RepairFault[当該集約グループのTxロールバック<br/>キューはPENDING維持・DB全体復元は不要]
```

### 9.3 PITR（ポイントインタイムリカバリ）実行契約 (PITR Execution Contract)
V182 DDL の中途失敗等によりロールバックが必要となった場合、以下の実行契約に従って PITR を発動する：

1. **復旧目標（Recovery Target）**:
   - V182 マイグレーション開始直前の確定 JST タイムスタンプ（秒単位）。
2. **カットオフ位置（Cut-off Position / Binlog Coordinates）**:
   - Preflight SQL 完了直後に記録したバイナリログファイル名（例: `mysql-bin.000142`）およびポジション（例: `1048576`）。
   - リカバリ処理は、V182 の最初の DDL 文が発行される直前のトランザクション終了位置をカットオフ境界点として厳格に指定する。
3. **障害現場保全（On-site Evidence Preservation）**:
   - 破損したインスタンスを直ちに停止・削除してはならない。以下の現場証拠を退避ストレージへ隔離保存する：
     - `flyway_schema_history` の完全ダンプ
     - 対象 4 テーブルおよびキューテーブルの現在の `SHOW CREATE TABLE` スキーマ定義
     - 直前の MySQL エラーログおよび直近 1 時間の Binlog ファイル
     - 上記ファイルの SHA-256 チェックサムマニフェスト
4. **切替前核検（Pre-Switch Verification）**:
   - リストアされた新 DB インスタンスにおいて、以下の 3 点を確認する：
     1. `flyway_schema_history` の最新行が V181 (`success=1`) であり、V182 の痕跡がないこと。
     2. Preflight SQL-02, 03 を再実行し、追加予定列（`tenant_id` 等）が存在しないこと。
     3. 業務テーブルのレコード件数が Preflight 時点と完全一致すること。
5. **切替後核検と人工放行（Post-Switch Verification & Human Go-Live Gate）**:
   - アプリケーションの接続先をリストア済み DB に切り替え、保守モード（読取専用）下で基本ヘルスチェックを実施。
   - **インシデントコマンダー（運用統括責任者）およびリード DBA の両名が書面／チャット記録にて「人工放行（Go-Live Approval）」を発行**した後に限り、保守モードを解除して一般トラフィックを再開する。

### 9.3.1 アプリケーション／DB 配対復元契約と人工放行 4 大前置核検 (App & DB Pairing Recovery Contract)
PITR 発動によりデータベースが V181 時点へ巻き戻された場合、アプリケーション層との整合性を保つため、以下の契約を厳格に順守する。本契約は **Staging 演練 Gate（ステージング環境での事前検証必須項目）** として位置付け、本番環境への接触や直接変更は行わない。

#### 1. V181 配対合法アプリケーション製品・設定・ダイジェスト登記台帳
データベースが V181 スキーマである環境に対し、V182 変更を前提としたアプリケーション（`t_digital_invoice.tenant_id` 列への参照や新規 CHECK 制約前提のロジック）を起動することは固く禁止する（起動時エラーまたは SQL 実行時エラーとなるため）。V181 と配対（Pairing）する正規アーティファクトおよび設定は以下のように管理する：

| 管理項目 | V181 配対復元アーティファクト仕様 | 判定ステータス |
|---|---|---|
| **アーティファクト種別** | Spring Boot 実行可能 Jar (`ses-manager-pro.jar`) | **確認済 (Confirmed)** |
| **製品バージョン** | V181 スキーマ対応リリースバージョン | **PENDING（リリース管理台帳照合待ち・杜撰な架空バージョン記載の禁止）** |
| **Git Commit SHA** | V181 マイグレーション完了時点のコミットハッシュ | **PENDING（ビルドマニフェスト照合待ち）** |
| **製品 SHA-256 ダイジェスト** | 当該リリース Jar の SHA-256 チェックサム | **PENDING（CI/CD アーティファクト検証待ち）** |
| **構成プロファイル** | `application-prod.yml` | **確認済 (Confirmed)** |
| **Flyway 制御パラメータ** | `spring.flyway.enabled: false`（強制無効化） | **必須要件 (Mandatory)** |

#### 2. 復旧・切替・ヘルスチェック期間中の自動マイグレーション完全禁止
- PITR による DB リストア後、アプリケーション起動時の環境変数または引数に以下を強制注入する：
  ```bash
  export SPRING_FLYWAY_ENABLED=false
  # または java 起動引数: -Dspring.flyway.enabled=false
  ```
- **目的**: アプリケーション起動時に Flyway が自動発動して V182 および後続のマイグレーションを勝手に再実行（再走）することを物理的に遮断する。

#### 3. V182 依存新コードによる V181 データベース運用の絶対禁止
- V182 スキーマ（`tenant_id` 列や `t_nf09_scope_repair_queue`）に依存するビルド成果物を用いて V181 DB へ接続・稼働させることを固く禁ずる。
- 必ず上記登記台帳で承認された V181 互換製品 Jar へロールバックした上で接続する。

#### 4. 人工放行（Human Go-Live Sign-Off）前の 4 大前置核検（Pre-Go-Live Gates）
リストア後の一般トラフィック再開にあたり、以下の 4 つの検証ゲートがすべて完了していなければならない：
1. **Gate 1: アプリ／DB バージョン配対整合性検証**:
   起動対象の Jar が V181 互換リリース版であり、`spring.flyway.enabled=false` が適用されていることを確認。
2. **Gate 2: Flyway 履歴の非再前進検証**:
   リストア後 DB の `flyway_schema_history` において最新行が V181 (`success=1`) であり、V182 の行が存在しないことを確認。
3. **Gate 3: 読取・書込基本互換性スモークテスト**:
   保守モード下で V181 の基本機能（ログイン、インボイス参照、Peppol 参照、契約更新）がエラーログゼロで動作することを確認。
4. **Gate 4: 障害現場証跡および復旧証拠の保全完了確認**:
   V182 障害時のエラーログ、中途スキーマダンプ、Binlog 座標記録、PITR リストア完了ログが退避ストレージに保全され、SHA-256 が検証されていること。

上記 4 Gate すべての PASS をもって、インシデントコマンダーおよび DBA リードによる正式な「人工放行（Go-Live Approval）」を発行する。

### 9.4 前向修正（Forward-Fix）の取扱境界と PENDING 表明
1. **原マイグレーションファイルと SHA-256 チェックサムの絶対維持**:
   - 本番障害時であっても、リポジトリにコミット済みの `V182__nf09_tenant_scope_hardening.sql` の内容や SHA-256 チェックサム（`BB9EBDEABF3E1CE9DAD42525551E3F594D2DC4D10F103AF8AFA7BBBA159A0DA7`）を本番環境上で直接改ざん・編集してはならない。
2. **既コミット DDL と Flyway 失敗履歴（`success = 0`）の取扱境界**:
   - MySQL において DDL が中途コミットされた場合、Flyway は当該バージョンを `success = 0` として記録する。
   - `flyway repair` を実行しても、**既に実行された `ALTER TABLE`（列追加やインデックス作成）は DB 側で元に戻らない**。単に Flyway 履歴テーブルから失敗レコードが削除または修復されるだけである。
   - MySQL 8 は `ADD COLUMN IF NOT EXISTS` 構文をサポートしていないため、修正パッチを用意せずに V182 を再実行すると、既に適用された列に対して `1060 (Duplicate column name)` エラーが発生し二次災害を引き起こす。
3. **明確な PENDING 表明（未設計・未承認の厳格保持）**:
   - **本番環境における V182 中途失敗に対する Forward-Fix スクリプト、自動修復ツール、および具体的適用手順は未設計・未検証であり、ステータスは PENDING（未承認・要検討）として保持する。未実証の修復コマンドや成功証跡を捏造・仮定してはならない**。
   - 原則は PITR による全量復元とし、Forward-Fix はリードアーキテクト、DBA、法務コンプライアンス責任者の 3 者全会一致による例外承認かつ Staging 再現検証成功時のみ検討対象とする。

---

## 10. ステージング演練（Staging Rehearsal）と本番実行（Production Execution）の差異一覧 (Differences Matrix)

リハーサル演練で実証された前提と、本番環境における固有の制約・差異を正しく理解し、過信を排除する。

| 比較項目 | 使い捨て検証演練 (Confirmed Facts) | 本番環境実行 (Suggested / Target) | 差異の性質と留意事項 |
|---|---|---|---|
| **DB 環境** | Docker コンテナ内使い捨て `mysql:8.0`（tmpfs マウント） | Multi-AZ マネージド MySQL 8 / Amazon Aurora クラスタ | ストレージ遅延、ネットワークレイテンシ、AZ 間同期負荷が存在。 |
| **データ規模** | 合成テストデータ数十〜数百件 | 数万〜数百万円規模の実ビジネスレコード（想定） | DDL の実行時間、インデックス作成時の I/O バッファ負荷が大幅に増大。 |
| **実行所要時間** | DDL・DML ともに数秒で完了 | 数分〜数十分の保守ウィンドウ（計画枠） | 長時間 DDL によるレプリケーション遅延の発生に注意。 |
| **排他・MDL** | 競合プロセス皆無（メタデータロック待ちなし） | 保守モード下でも残存コネクションによる MDL 競合リスクあり | マイグレーション前に接続プロセスの完全切断が必須。 |
| **監査主体** | 合成ダミー値（`resolved_by = 1001L`、固定メモ） | 本人確認済みの実オペレータ SSO ユーザー ID、正式承認決裁番号 | ダミー値の使用は固く禁止。実承認チケット番号を引用。 |
| **障害発生時** | コンテナ即時破棄・ローカルディスクへのログ退避 | インシデントコール招集、P1 障害宣言、PITR 発動判断 | 現場保全を行った上で、PITR 実行契約に基づく厳密な復旧を実施。 |
| **外部通信** | Provider 通信なし（モック / 無効化） | 外部 Peppol サービスプロバイダ等（本作業中は未接続維持） | 本マイグレーションおよび修復中は外部同期ジョブを完全停止。 |

---

## 11. シナリオ 5 の正確な位置づけと限定表明 (Scenario 5 Scoping & Explicit Disclaimer)

### 11.1 シナリオ 5 の実態
`V182TenantScopeHardeningRehearsalMySqlTest` の第 5 節（`scenario5_faultEvidencePreservation_verifiesErrorCapturedBeforeTeardown`）は、**「本番 V182 マイグレーション自体は正常完了（version='182', success=1）した状態から、隔離テストリソースよりテスト専用の V182.99 を意図的に故障注入（CHECK 制約違反エラー 3819）して中途失敗させた環境において、コンテナが破棄される前に実行ログ・スキーマ定義・履歴テーブル・修復キュー・SHA-256 マニフェストをディスクに確実に落盤・保全できること」を検証した証拠保全演練（Evidence Preservation Rehearsal）**である。

### 11.2 限定表明（明確な非対象事項）
1. **V182 本番スクリプト自体の途上クラッシュ非検証**:
   シナリオ 5 は、V182 自体の中途停止ではなく、V182 適用済みの状態に対して後続のテスト用スクリプト（182.99）を失敗させた演練である。V182 自体が実行途中でクラッシュした場合の中途状態をシミュレーションしたものではない。
2. **部分失敗からの自動回復・ロールバックの非検証**:
   シナリオ 5 は、障害注入によって `ALTER TABLE` が MySQL の暗黙コミットにより永続化された現場を保全・確認したものであり、**「本番 V182 が部分的に失敗した後に、自動的または安全に旧スキーマへロールバックできること」を実証したものでは決してない**。
3. **本番自動修復の完全否定**:
   MySQL のアーキテクチャ上、部分マイグレーション失敗の自動ロールバックは不可能であり、本番環境での安全担保は、本書第 2 節および第 9 節に定めた **「直前フルバックアップと Binlog による PITR 全面復元体制」** にのみ依拠する。

---

## 12. 署名・承認記録（Sign-Off Register）

本書は、技術的仕様妥当性のレビュー（Technical PLAN Review）と、本番環境への実適用を承認する運用決裁（Production Operation Approval）を明確に分離して管理する。

### 12.1 技術仕様承認（Technical PLAN Review Register）
本手順書に記載された技術仕様、SQL、トランザクション境界、および制約整合性の妥当性を審査・承認する。

| レビュー役職 | 氏名 | 承認ステータス | 承認日 | 承認コメント / 判定 |
|---|---|---|---|---|
| **独立 Reviewer** | Astra | **PLAN REVIEW PENDING** | - | 本改定ドキュメントの独立 Plan Review 待ち |
| **技術仕様担当 / 執筆者** | AI-3 (gpt-6-luna) | **SUBMITTED** | 2026-10-26 | 子イベント分類修復・Peppol独立帰属・PITRアプリDB配対契約を改定完了 |

### 12.2 本番運用決裁（Production Operation Approval Register）
本番環境での実際のマイグレーション実行、保守枠確保、PITR 発動権限、およびデータ修復承認規程を発効する実責任者のサインオフ。現時点ではすべての本番決裁は **PENDING（未承認）** である。

| 承認役職 | 氏名 | 承認ステータス | 承認日 | 承認コメント / 決裁番号 |
|---|---|---|---|---|
| **運用統括責任者 (Operations Director)** | (未定) | **PENDING** | - | 保守時間枠・トラフィック遮断計画確認中 |
| **データベース管理者 (DBA Lead)** | (未定) | **PENDING** | - | PITR 手順・Binlog 保持期間・MDL 監視確認中 |
| **財務統括責任者 (Finance Director)** | (未定) | **PENDING** | - | インボイス帰属判定基準および承認ルート確認中 |
| **法務・コンプライアンス統括責任者** | (未定) | **PENDING** | - | テナント分離性・監査証跡・契約整合性確認中 |
