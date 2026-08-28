# Design — 資産・アカウント・ライセンス ライフサイクル管理 (`asset-account-license-lifecycle` / NF-09)

## 1. アーキテクチャ概要

本機能は、社内情報資産（PC・モバイル・セキュリティキー等）、外部SaaS/IdPアカウント参照、および有償ライセンスのライフサイクルを安全・確実に統制するためのモジュールである。
`platform-invariants.md` に完全準拠し、期間重複貸与の排他制御、秘密非保存の保証、外部失効確認モデルの厳格化、不変イベント台帳、および `engineer-lifecycle-workflow` (NF-01) 退社ゲートとの強固な連携を実現する。

```
[UI / Controller Layer]
  ├─ AssetPageController (/asset/list, /asset/inventory, /asset/accounts)
  ├─ AssetApiController (/api/assets/**, /api/asset-assignments/**, /api/asset-inventory/**)
  ├─ ExternalAccountApiController (/api/external-accounts/**, /api/licenses/**)
  ├─ MyAssetPageController (/my/assets)
  └─ MyAssetApiController (/api/my/assets/**)

[Service Layer]
  ├─ AssetService / AssetServiceImpl
  ├─ AssetAssignmentService / AssetAssignmentServiceImpl (期間排他・CAS制御)
  ├─ AssetEventService / AssetEventServiceImpl (追記型イベント台帳)
  ├─ AssetInventoryService / AssetInventoryServiceImpl (棚卸し・差異照合)
  ├─ ExternalAccountService / ExternalAccountServiceImpl (秘密非保存・失効確認)
  ├─ LicenseService / LicenseServiceImpl (席数CAS統制)
  ├─ AssetScopeService / AssetScopeServiceImpl (組織・要員スコープ解決)
  ├─ AssetLifecycleIntegrationService (NF-01 退社ゲート連携・Blocker解決)
  └─ AssetScheduler (返却期日・棚卸し・失効未確認アラート)

[Domain & Persistence Layer]
  ├─ Entity: Asset, AssetAssignment, AssetEvent, AssetInventoryRun, AssetInventoryItem,
  │          ExternalAccountSystem, ExternalAccountReference, LicensePlan, LicenseAssignment
  └─ Mapper: MyBatis-Plus BaseMapper (LambdaQueryWrapper / 行ロック @Select)
```

---

## 2. データモデル・DDL設計

### 2.1 資産マスタ (`m_asset`)

```sql
CREATE TABLE m_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_tag VARCHAR(64) NOT NULL UNIQUE COMMENT '全社一意資産管理番号 (例: AST-PC-2026-0001)',
    serial_no VARCHAR(128) COMMENT '製造番号/シリアルNo',
    asset_name VARCHAR(128) NOT NULL COMMENT '資産名称 (例: ThinkPad T14 Gen4)',
    category VARCHAR(32) NOT NULL COMMENT '資産区分: PC, MONITOR, SMARTPHONE, SECURITY_KEY, TABLET, OTHER',
    owner_company_id BIGINT COMMENT '所有法人ID (m_company.id)',
    status VARCHAR(32) NOT NULL DEFAULT 'IN_STOCK' COMMENT 'IN_STOCK, ASSIGNED, UNDER_MAINTENANCE, LOST, DISPOSED, RESERVED',
    location VARCHAR(128) COMMENT '保管場所/拠点',
    purchase_date DATE COMMENT '取得日',
    purchase_price DECIMAL(12, 2) COMMENT '取得価格 (円)',
    warranty_expiry DATE COMMENT 'メーカー保証満了日',
    lease_expiry DATE COMMENT 'リース満了日',
    note VARCHAR(1000) COMMENT '備考',
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック用バージョン',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_asset_status (status),
    INDEX idx_asset_owner (owner_company_id),
    INDEX idx_asset_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='資産台帳';
```

### 2.2 資産貸与台帳 (`t_asset_assignment`)

```sql
CREATE TABLE t_asset_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL COMMENT '対象資産ID',
    assignee_type VARCHAR(32) NOT NULL COMMENT '貸与先区分: ENGINEER, USER',
    assignee_id BIGINT NOT NULL COMMENT '要員IDまたはユーザーID',
    start_date DATE NOT NULL COMMENT '貸与開始日',
    expected_return_date DATE COMMENT '返却予定日',
    actual_return_date DATE COMMENT '実際の返却日 (NULL=現在貸与中)',
    handover_evidence_doc_id BIGINT COMMENT '受渡し証跡文書ID (既存 DocumentLink 連携)',
    return_evidence_doc_id BIGINT COMMENT '返却証跡文書ID (既存 DocumentLink 連携)',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, RETURNED, OVERDUE, WAIVED',
    note VARCHAR(500) COMMENT '貸与メモ',
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック用バージョン',
    created_by BIGINT COMMENT '登録者ユーザーID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_assign_asset (asset_id),
    INDEX idx_assign_target (assignee_type, assignee_id),
    INDEX idx_assign_status (status),
    INDEX idx_assign_dates (start_date, actual_return_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='資産貸与履歴台帳';
```

### 2.3 資産イベント台帳 (`t_asset_event`) — 追記専用

```sql
CREATE TABLE t_asset_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL COMMENT '資産ID',
    event_type VARCHAR(64) NOT NULL COMMENT 'CREATED, ASSIGNED, RETURNED, TRANSFERRED, REPAIRED, REPORTED_LOST, REMOTE_WIPED, DISPOSED, INVENTORIED',
    event_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'イベント発生日時',
    actor_user_id BIGINT COMMENT '操作者ユーザーID',
    assignee_type VARCHAR(32) COMMENT '貸与先区分',
    assignee_id BIGINT COMMENT '貸与先ID',
    from_status VARCHAR(32) COMMENT '変更前ステータス',
    to_status VARCHAR(32) COMMENT '変更後ステータス',
    evidence_doc_id BIGINT COMMENT '関連証跡文書ID',
    event_summary VARCHAR(255) NOT NULL COMMENT 'イベント要約',
    details_json TEXT COMMENT '追加メタデータJSON (PII/Secret非含有)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_event_asset (asset_id, event_time),
    INDEX idx_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='資産不変イベント台帳';
```

### 2.4 棚卸し台帳 (`t_asset_inventory_run`, `t_asset_inventory_item`)

```sql
CREATE TABLE t_asset_inventory_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inventory_code VARCHAR(64) NOT NULL UNIQUE COMMENT '棚卸しコード (例: INV-2026-H1)',
    title VARCHAR(128) NOT NULL COMMENT '棚卸し名称',
    target_date DATE NOT NULL COMMENT '基準日',
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS' COMMENT 'DRAFT, IN_PROGRESS, COMPLETED, CANCELLED',
    total_assets INT NOT NULL DEFAULT 0,
    matched_count INT NOT NULL DEFAULT 0,
    discrepancy_count INT NOT NULL DEFAULT 0,
    missing_count INT NOT NULL DEFAULT 0,
    conducted_by BIGINT COMMENT '実施責任者ユーザーID',
    completed_at DATETIME COMMENT '完了日時',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='棚卸し実施台帳';

CREATE TABLE t_asset_inventory_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inventory_run_id BIGINT NOT NULL COMMENT '棚卸し実施ID',
    asset_id BIGINT NOT NULL COMMENT '対象資産ID',
    expected_status VARCHAR(32) NOT NULL COMMENT '台帳上ステータス',
    expected_location VARCHAR(128) COMMENT '台帳上保管場所/貸与先',
    observed_status VARCHAR(32) COMMENT '実地確認ステータス',
    observed_location VARCHAR(128) COMMENT '実地確認場所',
    discrepancy_type VARCHAR(32) NOT NULL DEFAULT 'UNCHECKED' COMMENT 'UNCHECKED, MATCH, DISCREPANCY, MISSING, UNREGISTERED',
    discrepancy_reason VARCHAR(500) COMMENT '差異理由',
    resolution_action VARCHAR(500) COMMENT '是正措置',
    checked_by BIGINT COMMENT '確認者ユーザーID',
    checked_at DATETIME COMMENT '確認日時',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_inv_item (inventory_run_id, asset_id),
    INDEX idx_inv_item_status (discrepancy_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='棚卸し明細台帳';
```

### 2.5 外部アカウント参照 (`m_external_account_system`, `t_external_account_reference`)

```sql
CREATE TABLE m_external_account_system (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    system_code VARCHAR(64) NOT NULL UNIQUE COMMENT 'システムコード (例: GOOGLE_WORKSPACE, GITHUB, SLACK, AWS_IAM)',
    system_name VARCHAR(128) NOT NULL COMMENT 'システム名称',
    system_type VARCHAR(32) NOT NULL COMMENT 'IDP, SAAS_MAIL, SAAS_COLLAB, CLOUD_INFRA, MDM',
    auth_type VARCHAR(32) NOT NULL DEFAULT 'SAML_OIDC' COMMENT 'SAML_OIDC, OAUTH2, DIRECT_PROVISION',
    is_active INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部アカウントシステムマスタ';

CREATE TABLE t_external_account_reference (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    system_id BIGINT NOT NULL COMMENT '外部システムID (m_external_account_system.id)',
    account_identifier VARCHAR(255) NOT NULL COMMENT '外部識別子 (メールアドレス、アカウントID等 - PII扱い)',
    assignee_type VARCHAR(32) NOT NULL COMMENT 'ENGINEER, USER',
    assignee_id BIGINT NOT NULL COMMENT '要員IDまたはユーザーID',
    permission_level VARCHAR(64) COMMENT '権限区分: ADMIN, DEVELOPER, MEMBER, READONLY',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, SUSPENDED, REVOKED, EXCEPTION_HOLD',
    provisioned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '発行/割当日時',
    revoke_requested_at DATETIME COMMENT '失効要求送信日時',
    revoke_confirmed_at DATETIME COMMENT '失効完了確認日時 (NULL=失効未確認)',
    revoke_confirmed_by BIGINT COMMENT '失効確認者ユーザーID',
    external_sync_status VARCHAR(32) DEFAULT 'NONE' COMMENT 'NONE, SYNC_PENDING, SYNC_SUCCESS, SYNC_FAILED, TIMEOUT',
    sync_error_message VARCHAR(500) COMMENT '外部連携エラー要約 (秘密値非含有)',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_ext_acc_target (assignee_type, assignee_id),
    INDEX idx_ext_acc_system (system_id, status),
    INDEX idx_ext_acc_status (status, revoke_confirmed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部アカウント参照台帳 (秘密非保存)';
```

### 2.6 ライセンス管理 (`m_license_plan`, `t_license_assignment`)

```sql
CREATE TABLE m_license_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_code VARCHAR(64) NOT NULL UNIQUE COMMENT 'プランコード (例: LIC-M365-E5, LIC-GITHUB-ENT)',
    plan_name VARCHAR(128) NOT NULL COMMENT 'プラン名',
    system_id BIGINT COMMENT '外部システムID (m_external_account_system.id)',
    seat_limit INT NOT NULL COMMENT '購入ライセンス席数上限',
    allocated_count INT NOT NULL DEFAULT 0 COMMENT '現在割当数 (CAS保護)',
    cost_per_seat DECIMAL(12, 2) COMMENT '1席あたり月額単価 (円)',
    cost_center_id BIGINT COMMENT '費用負担組織/Cost Center ID',
    expiry_date DATE COMMENT 'ライセンス契約満了日',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, EXPIRED, TERMINATED',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='有償ライセンスプランマスタ';

CREATE TABLE t_license_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL COMMENT 'ライセンスプランID',
    assignee_type VARCHAR(32) NOT NULL COMMENT 'ENGINEER, USER',
    assignee_id BIGINT NOT NULL COMMENT '要員IDまたはユーザーID',
    account_reference_id BIGINT COMMENT '関連外部アカウント参照ID',
    assigned_date DATE NOT NULL COMMENT '割当開始日',
    released_date DATE COMMENT '割当解除日 (NULL=現在割当中)',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, RELEASED',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_lic_assign_plan (plan_id, status),
    INDEX idx_lic_assign_target (assignee_type, assignee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ライセンス割当台帳';
```

---

## 3. Platform Invariants 準拠の3つの決定表

### 表1: 時間・asOf・履歴不変性 (`platform-invariants.md` §8)

| 対象 | current (現在値) | history (履歴) | snapshot (時点確定) | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| **資産 (`m_asset`)** | `status`, `location`, `owner_company_id` | `t_asset_event` に全状態変更を追記 | 棚卸し実行時 (`t_asset_inventory_item.expected_*`) | `t_asset_event` を timestamp で遡及 | `owner_company_id`=NULL は自社全社共通資産 |
| **貸与 (`t_asset_assignment`)** | `actual_return_date IS NULL` のアクティブ行 | 過去の返却済み行 (`actual_return_date` 入力済) | 貸与時・返却時受渡し証跡 (`handover/return_evidence_doc_id`) | `start_date <= :d AND (actual_return_date IS NULL OR actual_return_date >= :d)` | `actual_return_date`=NULL は未返却（現在貸与中） |
| **外部アカウント (`t_external_account_reference`)** | `status = 'ACTIVE'` 行 | 状態変更イベント (`t_asset_event`) | 失効確認スナップショット (`revoke_confirmed_at`, `revoke_confirmed_by`) | `provisioned_at <= :t AND (revoke_confirmed_at IS NULL OR revoke_confirmed_at >= :t)` | `revoke_confirmed_at`=NULL は失効未確認（有効または失効手続中） |
| **ライセンス割当 (`t_license_assignment`)** | `status = 'ACTIVE'` 行 | 解除済み行 (`released_date` 入力済) | 月次管理会計締め時点の `allocated_count` | `assigned_date <= :d AND (released_date IS NULL OR released_date >= :d)` | `released_date`=NULL は現在割当継続中 |

---

### 表2: 主体 × 操作 × 可視母集団 (`platform-invariants.md` §8)

| 主体 | list / detail / count | export / download (CSV/PDF) | notification | scheduler / async |
|---|---|---|---|---|
| **管理者 (`ROLE_管理者`)** | 全社・全部署の全資産・貸与・外部アカウント・ライセンス・棚卸し | 全件エクスポート可能 | 全社アラート（期日超過、棚卸し差異、未失効） | 全件対象で期日・未失効・棚卸しバッチ実行 |
| **マネージャー (`ROLE_マネージャー`)** | 自組織＋配下組織に所属する要員の貸与資産・アカウントのみ | 自組織配下のみエクスポート可能 | 自組織配下の期日超過・未返却アラート | 実行しない (システムprincipalが実行) |
| **HR (`ROLE_HR`)** | 全要員の貸与資産・外部アカウント（入退社関連） | 全要員貸与リストエクスポート可能 | 退社ゲートblocker通知 | 実行しない |
| **営業 (`ROLE_営業`)** | 担当要員の貸与資産（PC貸与状況等） | 担当要員分のみエクスポート可能 | 担当要員の返却期日接近通知 | 実行しない |
| **要員本人 (`ROLE_要員`)** | 自分自身に現在貸与されている資産・アカウント参照のみ (`/my/assets`) | エクスポート不可 | 本人の返却期日接近・超過通知のみ (宛先指定) | 実行しない |
| **システムPrincipal (`scheduler`)** | 全件スキャン（権限バイパス） | システム監査ログ・通知Outbox生成 | 全通知キューを生成 | 定期バッチ実行 |

---

### 表3: 状態機械 と 競合保護 (`platform-invariants.md` §8)

| エンティティ / 状態 | 許可遷移 | 遷移の防重・競合保護手段 | Competing Writer 発生時の挙動 | Rollback / 補償 |
|---|---|---|---|---|
| **資産 (`m_asset`)** | `IN_STOCK` ↔ `ASSIGNED`<br>`IN_STOCK` ↔ `UNDER_MAINTENANCE`<br>`IN_STOCK/LOST` → `DISPOSED`<br>Any → `LOST` | `version` CAS 楽観ロック + 状態条件付き UPDATE (`UPDATE m_asset SET status=:to, version=version+1 WHERE id=:id AND version=:v AND status=:from`) | 一方が 409 Conflict（楽観ロック例外）で失敗 | トランザクション全体ロールバック |
| **貸与 (`t_asset_assignment`)** | `ACTIVE` → `RETURNED`<br>`ACTIVE` → `OVERDUE`<br>`ACTIVE/OVERDUE` → `WAIVED` | **同一資産期間排他判定**: トランザクション内で `SELECT ... FOR UPDATE` により対象資産を行ロックし、重複貸与区間が存在しないことを確認後に INSERT/UPDATE | 後発の貸与申請が `BusinessException("該当年月日に既に有効な貸与が存在します")` で拒否 | 貸与作成・更新全体がロールバック |
| **外部アカウント (`t_external_account_reference`)** | `ACTIVE` → `SUSPENDED`<br>`ACTIVE/SUSPENDED` → `REVOKED`<br>Any → `EXCEPTION_HOLD` | 状態CAS + 失効確認分離 (`UPDATE ... SET status='REVOKED', revoke_confirmed_at=:now WHERE id=:id AND status IN ('ACTIVE','SUSPENDED')`) | 同時更新時は1件のみ成功、他方は409 | 外部API要求失敗時は `status` を変更せず `SYNC_FAILED` 記録 |
| **ライセンス席数 (`m_license_plan`)** | 割当増加 / 割当解除 | 席数CAS (`UPDATE m_license_plan SET allocated_count=allocated_count+1, version=version+1 WHERE id=:id AND allocated_count < seat_limit AND version=:v`) | 席数上限到達時は CAS 失敗し `BusinessException("ライセンス席数が上限に達しています")` | 割当処理全体がロールバック |

---

## 4. 期間重複貸与排除 (Overlap Prevention Architecture)

### 4.1 重複判定代数
同一の `asset_id` に対して、新規貸与期間 `[req_start, req_end]`（`req_end` は未定の場合 NULL）と既存貸与期間 `[exist_start, exist_end]`（`actual_return_date` または `expected_return_date`）の重なり判定式:
```
overlap := (exist_start <= req_end OR req_end IS NULL)
       AND (exist_actual_return IS NULL OR exist_actual_return >= req_start)
```

### 4.2 トランザクション内排他シーケンス
```
1. BEGIN TRANSACTION
2. SELECT * FROM m_asset WHERE id = :assetId FOR UPDATE;
3. IF asset.status != 'IN_STOCK' THEN THROW BusinessException("資産が保管中ではありません");
4. SELECT COUNT(*) FROM t_asset_assignment 
   WHERE asset_id = :assetId 
     AND deleted_flag = 0
     AND (actual_return_date IS NULL OR actual_return_date >= :startDate)
     AND (:endDate IS NULL OR start_date <= :endDate);
5. IF count > 0 THEN THROW BusinessException("指定期間に重複する貸与が存在します");
6. INSERT INTO t_asset_assignment (...) VALUES (...);
7. UPDATE m_asset SET status = 'ASSIGNED', version = version + 1 WHERE id = :assetId AND version = :v;
8. INSERT INTO t_asset_event (asset_id, event_type, ...) VALUES (...);
9. COMMIT;
```

---

## 5. 秘密非保存と外部失効（Revoke）モデル

### 5.1 秘密非保存の保証 (No Secrets In Code/DB/Log)
- パスワード、APIキー、OAuth Client Secret、リカバリーコード等を格納するカラムを設計しない。
- DTO、Form、JSON レスポンスに `password`, `secret`, `token`, `key` 等の機密プロパティを含めない。
- Unit Test として `AssetSecretFieldScanTest` を実装し、Entity/DTO/DBスキーマに秘密情報フィールドが存在しないことをリフレクションと正規表現で常時検証する。

### 5.2 外部失効（Revoke）状態遷移とタイムアウト耐性
```
[外部失効要求開始]
        │
        ▼
[Outbox / Adapter 呼出し]
        │
   ┌────┴───────────────────────────┐
   ▼ (成功: 200 OK)                 ▼ (タイムアウト / 5xx / 429)
[status: REVOKED]              [status: ACTIVE のまま]
[revoke_confirmed_at: now]     [external_sync_status: TIMEOUT / SYNC_FAILED]
[revoke_confirmed_by: actor]   [sync_error_message: "Gateway Timeout"]
                                    │
                                    ▼ (手動確認または定期リトライ)
                               [管理者による失効完了手動確認]
                                    │
                                    ▼
                               [status: REVOKED]
                               [revoke_confirmed_at: now]
                               [revoke_confirmed_by: adminUserId]
```
> **重要**: 外部API呼出しがタイムアウトした場合、システムは決して `REVOKED` や `confirmed` に倒さず、`TIMEOUT` / `SYNC_FAILED` としてアラート一覧に掲出し、退社ゲート blocker を維持する（Fail-Closed 原則）。

---

## 6. NF-01 退社ゲート連携インターフェース契約

### 6.1 `AssetOffboardingService`
`engineer-lifecycle-workflow` の退社ケース完了時、以下のメソッドを通じて 3大 blocker（未返却端末、未失効アカウント、未解放ライセンス）判定を行う:

```java
public interface AssetOffboardingService {
    /**
     * 要員の未返却資産、未失効外部アカウント、および未解放有償ライセンスの検証
     * @param engineerId 対象要員ID
     * @return クリアランス判定結果 (未返却数、未失効数、未解放数、詳細リスト、免除フラグ)
     */
    OffboardingClearanceResultDto checkOffboardingClearance(Long engineerId);

    /**
     * 退社確定時の一括無効化トリガー（アカウント失効要求・ライセンス解放）
     */
    void triggerOffboardingRevocations(Long engineerId, Long actorUserId);

    /**
     * 例外承認適用によるBlocker解除記録 (ApprovalEngine / RequestType = LIFECYCLE_EXCEPTION)
     */
    void approveOffboardingWaiver(Long engineerId, String reason, Long approvalRequestId, Long actorUserId);
}
```

---

## 7. 画面・API設計

### 7.1 管理画面
1. `/asset/list`: 資産一覧・検索・新規登録・編集・貸与・返却・ステータス変更モーダル。
2. `/asset/inventory`: 棚卸し一覧・新規棚卸し計画・実地照合・差異理由入力・完了確定。
3. `/asset/accounts`: 外部アカウント参照一覧・失効確認・ライセンス席数ダッシュボード。

### 7.2 要員ポータル画面
- `/my/assets`: 要員本人が自身に貸与されている資産（PC、モニタ等）の管理タグ、資産名、返却期日、および利用アカウント参照を確認できる画面（390px モバイル完全対応）。
