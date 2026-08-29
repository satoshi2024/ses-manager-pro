# 資産・アカウント・ライセンス管理（NF-09）運用・移行Runbook

本ドキュメントは、SES Manager Pro における資産・外部アカウント・有償ライセンス管理機能（NF-09）の初期移行、運用フロー、および障害時ロールバック手順を定めます。

---

## 1. 運用前提・アーキテクチャ統制

1. **秘密情報非保持（No Secrets Policy）**:
   - `t_external_account_reference` 等のテーブルやDTOに、パスワード・APIトークン・秘密鍵は絶対に保存・記録しません。
   - 保有するのは外部システム識別子（アカウント名/メールアドレス）と状態（ACTIVE / SUSPENDED / REVOKED / PENDING_CONFIRMATION / UNKNOWN）、失効要求・確認タイムスタンプのみです。
2. **資産ステータス語彙**:
   - 資産状態は `IN_STOCK` / `ASSIGNED` / `UNDER_MAINTENANCE` / `LOST` / `DISPOSED` / `RESERVED` の6値に限定します。登録・状態変更・検索・棚卸し入力で不正値を受け付けません。
3. **排他制御と期間重複代数**:
   - 貸与作成時は `m_asset` 行を `FOR UPDATE` でロックし、`[start_date, expected_return_date]` の期間重複を代数的に排除します。
   - 返却・免除は `m_asset FOR UPDATE` → `t_asset_assignment FOR UPDATE` の順で固定し、asset CASとassignment終端CASが両方成功した場合だけeventを追記します。license解放はassignment→plan、棚卸しはrun→itemの順でロックします。
4. **不変イベント台帳（Immutable Event Ledger）**:
   - 資産に対する作成・更新・貸与・返却・ステータス変更・廃棄・紛失の全履歴は `t_asset_event` に追記のみ（INSERT-only）で記録され、上書き・物理削除は行いません。専用service APIに加え、V132のMySQL UPDATE/DELETE triggerでDB側からも拒否します。
5. **ライセンス席数 CAS（Compare-And-Swap）**:
   - `m_license_plan.allocated_count` は席数上限 `seat_limit` を超えない条件付きCAS更新でアトミックに管理されます。
6. **所有法人と認可スコープ**:
   - `m_asset.owner_company_id` は会社マスタの新設IDではなく、既存 `m_organization_unit.legal_entity_id` の法人IDを保持します。
   - 管理者・HRは全件、マネージャーは管理組織の子孫組織とDataScopeの共通集合、営業は現行担当要員、要員は本人に限定します。許可対象が空の場合は全件許可へフォールバックしません。
   - 資産一覧・詳細・イベント・貸与履歴・CSV・通知・要員ポータル・外部アカウント・ライセンス・証跡文書で同じスコープを適用します。
7. **論理削除の安全条件**:
   - 未返却貸与（`status = 'ACTIVE' OR actual_return_date IS NULL`）がある資産は削除できません。廃棄は `DISPOSED` のイベントを追記してから論理削除します。
   - 外部アカウント参照は状態にかかわらず削除できません。`ACTIVE/SUSPENDED/PENDING_CONFIRMATION/UNKNOWN` は未失効blockerから除外されず、`REVOKED`・失効確認済み・`EXCEPTION_HOLD` の行も履歴として保持します。`EXCEPTION_HOLD` は承認済み例外によるgate判定であり、削除認可ではありません。
   - ライセンス割当は状態にかかわらず削除できません。`ACTIVE` または `released_date IS NULL` は未解放として扱い、解放後も `RELEASED` と日付を履歴として保持します。
8. **外部連携の正本とトランザクション境界**:
   - MDMは端末状態、IdP/SaaSはアカウント失効状態の外部正本です。DBは参照・要求・確認結果・再試行の証跡正本です。
   - 失効要求送信と失効確認は別状態・別時刻で保持します。プロバイダ呼出しはDBトランザクション外で実行し、timeout/5xx/429は成功扱いにせず `PENDING_CONFIRMATION` のまま再試行します。応答形式を分類できない場合は `UNKNOWN` として blocker を維持します。

---

## 2. 初期データ移行手順（Migration Runbook）

### 2.1 スキーママイグレーションの適用
Flyway マイグレーションにより自動適用されます。
- `V129__asset_account_license_lifecycle.sql`: 9テーブル DDL 作成
- `V130__asset_account_license_menu_permissions.sql`: メニューおよびアクション権限シード
- `V131__asset_offboarding_waiver_ledger.sql`: 承認済み `LIFECYCLE_EXCEPTION` と対象要員を結ぶ退社例外免除台帳。`approval_request_id` は一意で、既存承認の再適用を重複記録しません。
- `V132__asset_offboarding_waiver_scope_and_append_only_guards.sql`: waiverの退社case/task scope列・FK・索引、および `t_asset_event` UPDATE/DELETE拒否trigger。V131由来のNULL scope legacy行は現行gateで免除に使用しません。

### 2.2 既存資産・アカウントの初期登録
1. 管理者アカウントで `/asset/list` にログイン。
2. 「資産新規登録」より社内PC・ディスプレイ・モバイル端末等の管理タグ（例: `AST-PC-2026-0001`）とシリアル番号、保管場所を登録。
3. `/asset/accounts` より、現在利用中の外部SaaSシステム（Google Workspace, Microsoft 365, GitHub, Slack 等）およびライセンスプランを登録。
4. 要員・社員との貸与紐付けを実施。

### 2.3 移行後の照合
初回移行後とリリース前に、バックアップ取得済みの読み取り専用接続で次の照合を行います。件数が想定と異なる場合は登録・削除を止め、差異を棚卸し明細として解消してから再実行します。

```sql
-- 有効資産の状態別件数
SELECT status, COUNT(*) AS asset_count
FROM m_asset
WHERE deleted_flag = 0
GROUP BY status
ORDER BY status;

-- 未返却一覧（削除・退社完了判定に使用）
SELECT a.id, a.asset_tag, a.asset_name, a.status,
       aa.assignee_type, aa.assignee_id, aa.start_date,
       aa.expected_return_date, aa.actual_return_date
FROM m_asset a
JOIN t_asset_assignment aa ON aa.asset_id = a.id
WHERE a.deleted_flag = 0
  AND aa.deleted_flag = 0
  AND (aa.status = 'ACTIVE' OR aa.actual_return_date IS NULL)
ORDER BY aa.expected_return_date, a.asset_tag;

-- 失効未確認アカウント
SELECT id, system_id, assignee_type, assignee_id, status,
       revoke_requested_at, revoke_confirmed_at, external_sync_status
FROM t_external_account_reference
WHERE deleted_flag = 0
  AND status IN ('ACTIVE', 'SUSPENDED', 'PENDING_CONFIRMATION', 'UNKNOWN')
  AND revoke_confirmed_at IS NULL;

-- 未解放ライセンス
SELECT id, plan_id, assignee_type, assignee_id,
       assigned_date, released_date, status
FROM t_license_assignment
WHERE deleted_flag = 0
  AND (status = 'ACTIVE' OR released_date IS NULL);
```

---

## 3. 日常運用手順

### 3.1 資産貸与・返却フロー
- **貸与時**: `/asset/list` でステータスが `IN_STOCK` の資産を選択し、「貸与」ボタンから貸与先要員/ユーザー、貸与開始日、返却予定日を入力。
- **返却時**: `/asset/list` でステータスが `ASSIGNED` の資産の「返却」ボタンから実返却日と端末状態メモを入力。資産ステータスは自動的に `IN_STOCK` に復帰。

### 3.2 定期棚卸し実施フロー
1. `/asset/inventory` で「新規棚卸し計画開始」をクリック。基準日を指定して開始。
2. 理論在庫（全有効資産）のスナップショット明細が展開される。
3. 担当者は現物確認を行い、「確認入力」から実地ステータス・保管場所・差異区分（MATCH / DISCREPANCY / MISSING）を記録。
4. 全明細の確認完了後、「棚卸し完了・確定」をクリックして集計を固定。

### 3.3 NF-01 退社ワークフロー連携
1. 要員の退社手続き開始時、`AssetOffboardingService.checkOffboardingClearance(engineerId, lifecycleCaseId, lifecycleTaskId)` が自動実行される。case/task scopeが欠落した場合はblockerを返し、waiverを適用しない。
2. 未返却端末、未失効アカウント、未解放ライセンスが存在する場合、退社ゲートがブロックされる。
3. 返却完了後、または承認済み `LIFECYCLE_EXCEPTION` のrequest type・status・targetを検証して、対象要員・退社case・`RESIGN_ASSET_RETURN` taskをscopeとして `t_asset_offboarding_waiver` に永続化した後にクリアランスがパスする。`approved_by` はApproval Actionの実操作ユーザーを記録し、申請者IDで代用しない。`RESIGN_ASSET_RETURN` が `COMPLETED` でも3 blockerの実照合は省略しない。
4. 退社確定時に `triggerOffboardingRevocations` が実行され、アカウントの失効要求およびライセンスの自動解放が行われる。

### 3.4 紛失インシデント緊急初動フロー
1. 要員マイポータル（`/my/assets`）または管理者画面より「紛失報告」を実行。
2. 資産ステータスが `LOST` に遷移し、不変イベント台帳に記録。
3. `AssetAlertService` が管理者・HR・セキュリティ担当者へ緊急通知を一斉配信。
4. 外部アカウントの即時無効化（`/asset/accounts` から手動失効または連携失効）を実施。

### 3.5 失効要求・確認・タイムアウト対応
- `revoke_requested_at` は外部へ要求を送信した時刻、`revoke_confirmed_at` は外部状態を確認できた時刻です。要求送信だけで `REVOKED` と判定しません。
- `idempotency_key` はDBで一意です。同一account・同一keyの再送は既存状態を返しproviderへ再送せず、claimは `idempotency_key IS NULL` のatomic updateで先着1件に限定します。別keyまたは別accountとの衝突は409で拒否します。
- `FAILED_OR_TIMEOUT`、ネットワークエラー、プロバイダ停止時は `status=PENDING_CONFIRMATION`、`external_sync_status=TIMEOUT` または `SYNC_FAILED` とし、`next_retry_at` に従ってポーリングします。
- confirmation APIのtimeout/5xx等の例外はアカウント単位でretry count/backoffを永続化し、同じpoll job内の後続アカウントを継続処理します。
- 確認成功時だけ `REVOKED` と `revoke_confirmed_at` を記録します。失効確認が取れない退社caseはブロックを維持します。

### 3.6 棚卸し差異の扱い
- `MATCH` は台帳と現物が一致した場合だけ登録します。
- `DISCREPANCY` / `MISSING` / `UNREGISTERED` は差異理由と是正措置を必須とし、棚卸し完了後の明細は変更できません。
- 差異が解消するまで資産の廃棄・再貸与・退社完了処理を実行せず、棚卸しrunと不変イベント台帳を照合します。
- 明細更新と確定は同一run行を先にlockします。`COMPLETED`後の更新、二重確定、確定処理と明細更新の交差は拒否し、保存明細と集計値を同一transactionで確定します。

---

## 4. 障害対応 & ロールバック手順（Rollback Runbook）

### 4.1 アプリケーション障害時の切り戻し
- Git ブランチ `codex/asset-account-license-lifecycle` のマージ前に戻す場合:
  ```bash
  git revert <merge-commit-hash>
  ```
- 本番スキーマは通常、DROPで切り戻しません。まず書き込みを停止し、DBバックアップとFlyway履歴を保全したうえで、承認済みの前方互換Flyway修正またはバックアップ時点への復旧を選択します。復旧後は下記の件数照合、未返却一覧、失効未確認一覧、ライセンス未解放一覧を再実行します。
- 復旧手順（DBA承認・バックアップ検証済みの場合）:
  1. 対象DBのフルバックアップと `flyway_schema_history` を保存。
  2. アプリをメンテナンスモードにし、外部プロバイダの失効要求ジョブを停止。
  3. 承認済みバックアップを別名DBへリストアし、件数・イベント・未返却一覧を照合。
  4. 検証済みの場合だけ本番DBへ切替え、Flyway checksum と smoke test を確認。
- 下記のDDL削除は、データ消失を伴うため、本番の通常ロールバックには使用せず、バックアップ取得済みの隔離検証DBでのみ使用します。
  ```sql
  DROP TABLE IF EXISTS t_asset_offboarding_waiver;
  DROP TABLE IF EXISTS t_license_assignment;
  DROP TABLE IF EXISTS m_license_plan;
  DROP TABLE IF EXISTS t_external_account_reference;
  DROP TABLE IF EXISTS m_external_account_system;
  DROP TABLE IF EXISTS t_asset_inventory_item;
  DROP TABLE IF EXISTS t_asset_inventory_run;
  DROP TABLE IF EXISTS t_asset_event;
  DROP TABLE IF EXISTS t_asset_assignment;
  DROP TABLE IF EXISTS m_asset;
  DELETE FROM t_role_menu WHERE menu_id IN (SELECT id FROM m_menu WHERE menu_key IN ('asset-management', 'my-assets'));
  DELETE FROM m_menu WHERE menu_key IN ('asset-management', 'my-assets');
  ```
- `t_asset_event` と貸与・アカウント・ライセンス履歴は監査対象のため、ロールバックで上書き・物理削除しません。誤登録は訂正イベントまたは後続状態で補正します。

---

## 5. 監視・アラート項目

| アラート種別 | 判定条件 | 通知先 | 対応アクション |
|---|---|---|---|
| `ASSET_OVERDUE` | 返却予定日 < 当日 かつ status=ACTIVE | 管理者 / HR | 貸与先要員・担当営業へ督促 |
| `ASSET_LEASE_EXPIRING` | リース満了日 <= 当日+30日 | 管理者 / 総務 | リース延長または返却・買替手続き |
| `ASSET_LOST_INCIDENT` | 紛失報告API実行時 | 全管理者 / HR | SaaSアカウント即時停止、端末位置特定 |
| `ASSET_REVOKE_PENDING` | `PENDING_CONFIRMATION` が再試行期限超過 | 管理者 / HR / セキュリティ | プロバイダ状態を手動確認し、確認結果を記録 |
| `ASSET_INVENTORY_DISCREPANCY` | `DISCREPANCY` / `MISSING` が未解消 | 管理者 / 総務 | 差異理由・是正措置・証跡を登録 |

## 6. Review提出物チェックリスト

- 資産状態別件数と未返却一覧のreconciliation結果。
- 失効未確認アカウント・未解放ライセンス一覧。
- 全Java secret scan結果（秘密値・パスワード・token・recovery codeの列/DTO/ログなし）。
- 外部要求とconfirmed resultの分離、timeout非成功のテスト結果。
- バックアップ復旧・前方互換移行・隔離DB限定DROPのロールバック手順。
