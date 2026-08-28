# 資産・アカウント・ライセンス管理（NF-09）運用・移行Runbook

本ドキュメントは、SES Manager Pro における資産・外部アカウント・有償ライセンス管理機能（NF-09）の初期移行、運用フロー、および障害時ロールバック手順を定めます。

---

## 1. 運用前提・アーキテクチャ統制

1. **秘密情報非保持（No Secrets Policy）**:
   - `t_external_account_reference` 等のテーブルやDTOに、パスワード・APIトークン・秘密鍵は絶対に保存・記録しません。
   - 保有するのは外部システム識別子（アカウント名/メールアドレス）と状態（ACTIVE / SUSPENDED / REVOKED）、失効確認タイムスタンプのみです。
2. **排他制御と期間重複代数**:
   - 貸与作成時は `m_asset` 行を `FOR UPDATE` でロックし、`[start_date, expected_return_date]` の期間重複を代数的に排除します。
3. **不変イベント台帳（Immutable Event Ledger）**:
   - 資産に対する作成・更新・貸与・返却・ステータス変更・廃棄・紛失の全履歴は `t_asset_event` に追記のみ（INSERT-only）で記録され、上書き・物理削除は行いません。
4. **ライセンス席数 CAS（Compare-And-Swap）**:
   - `m_license_plan.allocated_count` は席数上限 `seat_limit` を超えない条件付きCAS更新でアトミックに管理されます。

---

## 2. 初期データ移行手順（Migration Runbook）

### 2.1 スキーママイグレーションの適用
Flyway マイグレーションにより自動適用されます。
- `V129__asset_account_license_lifecycle.sql`: 9テーブル DDL 作成
- `V130__asset_account_license_menu_permissions.sql`: メニューおよびアクション権限シード

### 2.2 既存資産・アカウントの初期登録
1. 管理者アカウントで `/asset/list` にログイン。
2. 「資産新規登録」より社内PC・ディスプレイ・モバイル端末等の管理タグ（例: `AST-PC-2026-0001`）とシリアル番号、保管場所を登録。
3. `/asset/accounts` より、現在利用中の外部SaaSシステム（Google Workspace, Microsoft 365, GitHub, Slack 等）およびライセンスプランを登録。
4. 要員・社員との貸与紐付けを実施。

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
1. 要員の退社手続き開始時、`AssetOffboardingService.checkOffboardingClearance(engineerId)` が自動実行される。
2. 未返却端末、未失効アカウント、未解放ライセンスが存在する場合、退社ゲートがブロックされる。
3. 返却完了後、または例外承認（`approveOffboardingWaiver`）登録後にクリアランスがパスする。
4. 退社確定時に `triggerOffboardingRevocations` が実行され、アカウントの失効要求およびライセンスの自動解放が行われる。

### 3.4 紛失インシデント緊急初動フロー
1. 要員マイポータル（`/my/assets`）または管理者画面より「紛失報告」を実行。
2. 資産ステータスが `LOST` に遷移し、不変イベント台帳に記録。
3. `AssetAlertService` が管理者・HR・セキュリティ担当者へ緊急通知を一斉配信。
4. 外部アカウントの即時無効化（`/asset/accounts` から手動失効または連携失効）を実施。

---

## 4. 障害対応 & ロールバック手順（Rollback Runbook）

### 4.1 アプリケーション障害時の切り戻し
- Git ブランチ `codex/asset-account-license-lifecycle` のマージ前に戻す場合:
  ```bash
  git revert <merge-commit-hash>
  ```
- スキーマの切り戻しが必要な場合（緊急時）:
  ```sql
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

---

## 5. 監視・アラート項目

| アラート種別 | 判定条件 | 通知先 | 対応アクション |
|---|---|---|---|
| `ASSET_OVERDUE` | 返却予定日 < 当日 かつ status=ACTIVE | 管理者 / HR | 貸与先要員・担当営業へ督促 |
| `ASSET_LEASE_EXPIRING` | リース満了日 <= 当日+30日 | 管理者 / 総務 | リース延長または返却・買替手続き |
| `ASSET_LOST_INCIDENT` | 紛失報告API実行時 | 全管理者 / HR | SaaSアカウント即時停止、端末位置特定 |
