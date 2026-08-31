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
  │          ExternalAccountSystem, ExternalAccountReference, LicensePlan, LicenseAssignment,
  │          AssetOffboardingWaiver, AssetLostIncident
  └─ Mapper: MyBatis-Plus BaseMapper (LambdaQueryWrapper / 行ロック @Select)
```

---

## 1.4 資産ステータス語彙と遷移入口

資産ステータスの許可値は `IN_STOCK`、`ASSIGNED`、`UNDER_MAINTENANCE`、`LOST`、`DISPOSED`、`RESERVED` の6値に固定する。登録時と `AssetService.changeStatus` の両方で値をtrim・大文字化し、許可集合にない値は `BusinessException(400)` として保存前に拒否する。通常の新規登録は `IN_STOCK` 固定であり、`ASSIGNED`/`LOST`/`DISPOSED` をpayloadで指定して初期化する経路を持たない。画面の検索・一覧バッジ・棚卸し入力、およびV1/V129のDDLコメントはこの6値を同じ語彙で表示する。

状態遷移は表3の許可集合をサービスで強制する。`IN_STOCK ↔ ASSIGNED` の貸与・返却遷移は貸与レコードと資産CASを同一トランザクションで扱う `AssetAssignmentService` 専用、`LOST` は紛失インシデント起票付きの `AssetService.reportLost` 専用、`DISPOSED` は廃棄イベント付きの `AssetService.disposeAsset` 専用とする。`UNDER_MAINTENANCE/RESERVED → IN_STOCK` は `AssetService.restoreToStock` 専用とする。`AssetService.changeStatus` は `ASSIGNED`/`IN_STOCK`/`LOST`/`DISPOSED` を遷移先に受け付けず、専用処理の副作用を迂回する経路を提供しない。`DISPOSED` から `IN_STOCK`/`RESERVED`、`LOST` から `UNDER_MAINTENANCE`/`RESERVED`、`UNDER_MAINTENANCE` から `ASSIGNED`/`DISPOSED` は拒否する。返却処理はロック取得後に `start_date <= actual_return_date <= 今日` を検証し、未来日または開始日前の返却で資産を `IN_STOCK` に戻さない。

`reportLost` は資産状態CAS、`t_asset_event.REPORTED_LOST`、`t_asset_lost_incident` 初回行、`ASSET_LOST_INCIDENT` 通知outbox登録を同一transactionで実行する。既に `LOST` の資産に対する再送は既存インシデントを返し、台帳・通知を増殖させない。インシデント対応の更新は行ロックとversionで直列化し、関連文書は `t_document_link` へINSERT-onlyで追加する。

## 1.5 論理削除の安全条件設計（AS-R1.5 対応）

MyBatis-Plus の `logic-delete-field: deletedFlag` によりすべての `removeById()` 呼び出しは論理削除（`deleted_flag = 1`）となり、以降の SELECT から自動的に除外される。これにより**退社ゲート・期間排他・ライセンス席数集計が実行時に誤った判断を行う可能性**がある。これを防ぐため以下の設計制約を適用する。

### 1.5.1 論理削除前バリデーション（Fail-Closed）

| 操作対象 | 禁止条件 | 拒否メソッド | 例外 |
|---|---|---|---|
| `m_asset` | `t_asset_assignment.status IN ('ACTIVE','OVERDUE') AND actual_return_date IS NULL` のレコードが存在する | `AssetService.softDeleteAsset(id)` | `BusinessException("未返却貸与が存在する...")` |
| `t_external_account_reference` | **既存参照行は状態にかかわらず論理削除禁止**。未失効状態では特に退社gateから除外されるため削除不可 | `ExternalAccountService.softDeleteAccount(id)` | 常に `BusinessException`（終端履歴も保持。`EXCEPTION_HOLD` は削除認可を意味しない） |
| `t_license_assignment` | **既存割当行は状態にかかわらず論理削除禁止**。`ACTIVE` または `released_date IS NULL` は未解放として削除不可 | `LicenseService.softDeleteAssignment(id)` | 常に `BusinessException`（`RELEASED` 履歴も保持） |

論理削除操作は上記バリデーションを通過した場合のみ実行する。実装上、貸与・外部account・license割当の既存行は終端状態を含め常に拒否し、これらを物理/論理削除するAPIを提供しない。`AssetEventService`、`AssetAssignmentService`、`ExternalAccountService` は汎用 `IService`/`ServiceImpl` を継承せず、専用の追記・状態遷移APIだけを公開する。さらに `t_asset_event` はV132のMySQL UPDATE/DELETE triggerでDB側からも保護する。`m_asset` だけは`DISPOSED`かつ未返却貸与ゼロの場合に限り管理者の台帳整理を許可する。判定は`@Transactional`かつ行lock内で行い、同時削除によるTOCTOUを防ぐ。

### 1.5.2 廃棄 vs 論理削除

- **資産廃棄**: `AssetService.disposeAsset(id, ...)` を使用。`t_asset_event` に `DISPOSED` イベントを追記し、`deleted_flag` は `0` のままとする（台帳上の証跡を維持する）。`changeStatus` からの直接廃棄は拒否する。
- **台帳整理（論理削除）**: 管理者が廃棄後の不要な資産マスタを台帳から通常一覧上見えなくする目的でのみ使用可能。`DISPOSED` 状態かつ ACTIVE 貸与ゼロの場合のみ許可する。これは `m_asset` に限る。

### 1.5.3 終端状態の保持

`t_asset_assignment` の `RETURNED`、`t_external_account_reference` の `REVOKED`、`t_license_assignment` の `released_date IS NOT NULL` に達した終端行は論理削除せず台帳上に残留させる。これら3台帳には終端履歴を削除するAPIを設けず、`t_asset_event` および `t_document_link` からの参照可能性を維持する。`m_asset` の `DISPOSED` 行だけは、上記の終端履歴を残したまま管理者の台帳整理として論理削除できる。

---

## 1.6 認可スコープ契約（P1-01 / P1-02 / P1-05 是正）

`owner_company_id` は存在しない `m_company` の行IDではなく、既存 `m_organization_unit.legal_entity_id` と同じ法人スコープ値である。資産テーブルに新しい法人マスタや法人認可表は追加しない。NULLは全社共有を表すが、スコープユーザーの許可集合が空の場合にNULL資産を全件公開する意味ではない。

| actor | 許可資産の導出 | fail-closed 条件 |
|---|---|---|
| 管理者 / HR | 全資産 | なし（メニュー権限は別ゲート） |
| 営業 | 現任 `t_engineer_sales.sales_user_id = actor` の要員に対する現在貸与。既存DataScopeの担当要員母集団を使い、owner法人で追加制限しない（担当要員が別法人所属でも可） | actorがDBで解決できない、担当要員0名、現在貸与0件 |
| マネージャー | 管轄組織の要員に対する現在貸与を既存OrganizationScope/DataScopeの母集団から導出し、owner法人は `NULL`（共有）または管轄組織の `legal_entity_id` と積集合 | 所属/管理組織が解決できない、許可要員0名、非NULL法人が不一致 |
| 要員 | 自身の貸与記録にリンクされた証跡文書、ポータルは自身の現在貸与のみ | ログイン要員リンクなし、他要員の資産、管理台帳API |

一覧・count・detail・event・assignment history・CSV・通知・外部アカウント/ライセンスの担当者参照はこの同一母集団を使う。営業・マネージャーへ未貸与資産は公開しない。`owner_company_id IS NULL` の共有資産も、営業・マネージャーに許可された要員への現在貸与がある場合だけ公開する。`DocumentLink(ASSET_ASSIGNMENT)` と `DocumentLink(ASSET_LOST_INCIDENT)` は、実在する `t_document` → 対象assignment/incident → asset を検証し、共通の `AssetScopeService` が許可する場合だけdetail/downloadを許可する。返却・移管で新しいassignmentが作られた場合、旧assignmentの文書権限は旧assigneeにのみ再評価され、新assigneeへ暗黙継承しない。紛失インシデントの文書リンク追加は、管理者/HR以外ではactorの対象資産scopeと既存文書リンクのscopeをともに満たす場合だけ許可し、未リンク文書または別scope文書はfail-closedで拒否する。

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
    owner_company_id BIGINT COMMENT '所有法人のlegal_entity_id（m_organization_unit.legal_entity_id）。NULLは全社共有。m_companyは作らない',
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

### 2.5 紛失インシデント台帳 (`t_asset_lost_incident`)

V133で資産ごとに1行の紛失インシデント台帳を追加する。`reported_at`、リモートワイプ要求/実施/確認状態と各日時、警察届出番号、保険申請状態/日時を保持し、認証秘密や復旧コードは保持しない。`t_document_link` の `target_type = 'ASSET_LOST_INCIDENT'` で関連証跡を追記する。LOST遷移時は `AssetLostIncidentService.createInitial` が初回台帳と通知outboxを同一transactionで登録し、再送は既存行を再利用する。

```sql
CREATE TABLE t_asset_lost_incident (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL UNIQUE,
    reported_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reported_by BIGINT,
    incident_details VARCHAR(2000),
    remote_wipe_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED',
    remote_wipe_requested_at DATETIME,
    remote_wipe_executed_at DATETIME,
    remote_wipe_confirmed_at DATETIME,
    police_report_number VARCHAR(128),
    insurance_claim_status VARCHAR(32) NOT NULL DEFAULT 'NOT_APPLIED',
    insurance_claimed_at DATETIME,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_asset_lost_incident_asset FOREIGN KEY (asset_id) REFERENCES m_asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='紛失資産インシデント対応台帳（秘密非保存）';
```

APIは `GET/PUT /api/assets/{assetId}/lost-incident` とし、GET/PUTの両方をメソッド単位で管理者/HR/マネージャーに限定し、営業/要員からは403とする。サービス内でも資産scopeを検証して対応情報を参照・更新する。`DocumentServiceImpl.assertDocumentAccessAllowed` は `ASSET_ASSIGNMENT` と `ASSET_LOST_INCIDENT` の両target typeを同じscope判定へ渡し、detail/downloadで共通利用する。`AssetLostIncidentService.linkDocuments` は対象資産とactorのscopeに加えて既存文書リンクのscopeを検証し、管理者/HR以外の未リンク文書・cross-scope文書追加を拒否する。actor IDが渡された場合はSecurityContextのロールを信頼せず、`SysUserMapper` で永続化された実ロールを解決する。要員の自己紛失報告は `AssetService.reportLost` の専用経路だけを使用し、controllerから個別通知を呼び出さない。

### 2.6 外部アカウント参照 (`m_external_account_system`, `t_external_account_reference`)

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
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, SUSPENDED, REVOKED, PENDING_CONFIRMATION, UNKNOWN, EXCEPTION_HOLD',
    provisioned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '発行/割当日時',
    idempotency_key VARCHAR(128) COMMENT '失効要求冪等性キー',
    retry_count INT NOT NULL DEFAULT 0 COMMENT 'ポーリング/リトライ回数',
    next_retry_at DATETIME COMMENT '次回ポーリング予定日時',
    last_error_message VARCHAR(500) COMMENT '直近エラー要約 (秘密値非含有)',
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

### 2.7 ライセンス管理 (`m_license_plan`, `t_license_assignment`)

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

### 2.8 退社例外免除台帳 (`t_asset_offboarding_waiver`)

`LIFECYCLE_EXCEPTION` の承認適用結果はプロセスメモリに保持せず、V131〜V132の追記台帳へ保存する。`approval_request_id` は一意であり、同じ承認の再適用は同じ台帳行を再利用する。新規台帳行は対象要員、`lifecycle_case_id`、`lifecycle_task_id`（`RESIGN_ASSET_RETURN`）の組を必須とし、case/taskの外部キーで削除を制限する。台帳の有効判定は、参照先 `t_approval_request` が `request_type = 'LIFECYCLE_EXCEPTION'` かつ `status = 'approved'`（大文字小文字を区別しない）で、対象要員および指定された退社案件・タスクと一致することを必須とする。`approved_by` は承認アクションの実操作ユーザーであり、申請者IDを代用しない。

```sql
CREATE TABLE t_asset_offboarding_waiver (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    engineer_id BIGINT NOT NULL COMMENT '対象要員ID',
    lifecycle_case_id BIGINT NOT NULL COMMENT '対象退社案件ID',
    lifecycle_task_id BIGINT NOT NULL COMMENT 'RESIGN_ASSET_RETURNタスクID',
    approval_request_id BIGINT NOT NULL COMMENT '承認済みLIFECYCLE_EXCEPTION申請ID',
    reason VARCHAR(1000) NOT NULL COMMENT '免除理由',
    approved_by BIGINT COMMENT '承認適用操作者ID',
    approved_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '免除適用日時',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_asset_offboarding_waiver_request (approval_request_id),
    INDEX idx_asset_offboarding_waiver_engineer (engineer_id, approved_at),
    INDEX idx_asset_offboarding_waiver_case_task (lifecycle_case_id, lifecycle_task_id),
    CONSTRAINT fk_asset_offboarding_waiver_case FOREIGN KEY (lifecycle_case_id) REFERENCES t_lifecycle_case(id) ON DELETE RESTRICT,
    CONSTRAINT fk_asset_offboarding_waiver_task FOREIGN KEY (lifecycle_task_id) REFERENCES t_lifecycle_task(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退社blocker例外免除追記台帳';
```

V131からの既存行は移行互換のためscope列がNULLのlegacy行として残り得るが、現行の退社gateはcase/taskの完全一致がないlegacy行を免除として採用しない。新規適用はV132でscope列、FK、索引を追加する。

### 2.8.1 追記専用のDB保護

`t_asset_event` はアプリケーションの専用service境界に加え、V132で `BEFORE UPDATE` と `BEFORE DELETE` triggerを作成する。訂正は既存行の更新・削除ではなく後続イベントの追記で表現する。

---

## 3. Platform Invariants 準拠の3つの決定表

### 表1: 時間・asOf・履歴不変性 (`platform-invariants.md` §8)

| 対象 | current (現在値) | history (履歴) | snapshot (時点確定) | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| **資産 (`m_asset`)** | `status`, `location`, `owner_company_id` | `t_asset_event` に全状態変更を追記 | 棚卸し実行時 (`t_asset_inventory_item.expected_*`) | `t_asset_event` を timestamp で遡及 | `owner_company_id`=NULL は自社全社共通資産 |
| **貸与 (`t_asset_assignment`)** | `actual_return_date IS NULL` のアクティブ行 | 過去の返却済み行 (`actual_return_date` 入力済) | 貸与時・返却時受渡し証跡 (`handover/return_evidence_doc_id`) | 貸与重複判定は `start_date <= :d AND (actual_return_date IS NULL OR actual_return_date > :d)`。asOf履歴参照では返却日を含む | `actual_return_date`=NULL は未返却（現在貸与中）。次の貸与に対して返却日は排他的境界 |
| **外部アカウント (`t_external_account_reference`)** | `ACTIVE` / `SUSPENDED` / `PENDING_CONFIRMATION` / `UNKNOWN` の未確認行 | 状態変更イベント (`t_asset_event`) | 失効確認スナップショット (`revoke_confirmed_at`, `revoke_confirmed_by`) | `provisioned_at <= :t AND (revoke_confirmed_at IS NULL OR revoke_confirmed_at >= :t)` | `revoke_confirmed_at`=NULL は失効未確認（有効・停止・確認待ち・状態不明） |
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
| **資産 (`m_asset`)** | `IN_STOCK ↔ ASSIGNED`（貸与サービス専用）<br>`IN_STOCK ↔ UNDER_MAINTENANCE`<br>`IN_STOCK ↔ RESERVED`<br>`IN_STOCK/LOST/RESERVED` → `DISPOSED`<br>Any → `LOST` | `version` CAS 楽観ロック + 状態条件付き UPDATE (`UPDATE m_asset SET status=:to, version=version+1 WHERE id=:id AND version=:v AND status=:from`)。`ASSIGNED↔IN_STOCK` は `AssetAssignmentService` が貸与行CASと同時更新 | 一方が 409 Conflict（楽観ロック例外）で失敗 | トランザクション全体ロールバック |
| **貸与 (`t_asset_assignment`)** | `ACTIVE` → `RETURNED`<br>`ACTIVE` → `OVERDUE`<br>`ACTIVE/OVERDUE` → `WAIVED` | **同一資産期間排他判定**: トランザクション内で `SELECT ... FOR UPDATE` により対象資産を行ロックし、重複貸与区間が存在しないことを確認後に INSERT/UPDATE。返却時は `start_date <= actual_return_date <= 今日` をロック内で検証 | 後発の貸与申請が `BusinessException("該当年月日に既に有効な貸与が存在します")` で拒否 | 貸与作成・更新全体がロールバック |
| **外部アカウント (`t_external_account_reference`)** | `ACTIVE` → `SUSPENDED`<br>`ACTIVE` → `PENDING_CONFIRMATION`（失効要求送信）<br>`PENDING_CONFIRMATION` → `UNKNOWN`（応答形式を分類不能）<br>`SUSPENDED/PENDING_CONFIRMATION/UNKNOWN` → `REVOKED`（外部確認済み）<br>Any → `EXCEPTION_HOLD`（承認済み例外） | 状態CAS + 失効確認分離 (`UPDATE ... SET status='REVOKED', revoke_confirmed_at=:now WHERE id=:id AND status IN ('ACTIVE','SUSPENDED','PENDING_CONFIRMATION','UNKNOWN') AND revoke_confirmed_at IS NULL`)。要求の冪等性キー、retry/backoff、`external_sync_status` を同一参照行へ保存 | 同時更新時は1件のみ成功、他方は409。timeout/5xx/429 は `PENDING_CONFIRMATION` を維持し、応答形式を分類できない場合だけ `UNKNOWN` として blocker を維持 | 外部API要求失敗時は `REVOKED`/confirmed に倒さず、`TIMEOUT`/`SYNC_FAILED` と retry/backoff を記録。管理者の確認または外部確認成功時だけ `REVOKED` へ補償遷移 |
| **ライセンス席数 (`m_license_plan`)** | 割当増加 / 割当解除 | 席数CAS (`UPDATE m_license_plan SET allocated_count=allocated_count+1, version=version+1 WHERE id=:id AND allocated_count < seat_limit AND version=:v`) | 席数上限到達時は CAS 失敗し `BusinessException("ライセンス席数が上限に達しています")` | 割当処理全体がロールバック |

---

### 3.1 更新系の固定lock orderとCAS契約

| 操作 | 固定順序 | 成功条件 | 競合時 |
|---|---|---|---|
| 返却 / 免除 | `m_asset FOR UPDATE` → `t_asset_assignment FOR UPDATE` → asset `ASSIGNED→IN_STOCK` CAS → assignment `ACTIVE/OVERDUE→RETURNED/WAIVED` CAS → event INSERT | 両CASが各1行更新。どちらかが失敗した場合はevent・証跡を記録しない | 409、トランザクション全体rollback。返却/免除で終端eventを二重記録しない |
| ライセンス解放 | `t_license_assignment FOR UPDATE` → `m_license_plan FOR UPDATE` → assignment `ACTIVE→RELEASED` CAS → plan `allocated_count - 1` CAS | assignment CASとplan CASが各1行更新。割当行を終端化してから席数を減算する | 409、トランザクション全体rollback。二重解放で席数を二重減算しない |
| 棚卸し明細更新 / 確定 | `t_asset_inventory_run FOR UPDATE` → `t_asset_inventory_item FOR UPDATE` | runが`COMPLETED`でない状態で明細を更新し、同じrun lock内で集計・`COMPLETED`遷移する | 完了後更新と二重確定を拒否し、集計と保存明細を同一transactionで一致させる |
| 外部失効要求 | `idempotency_key` unique制約 → atomic claim (`idempotency_key IS NULL` のみ) → provider呼出し | 同一keyは既存要求を返してproviderへ再送しない。別key、別accountへのkey衝突は409 | 先着claim以外はproviderを呼ばず、更新0件ならDB状態を再読して同一keyを返す |

返却と免除の入口で行う非lock読取は対象資産IDを得るためのヒント取得に限る。状態判定、CAS、履歴追記は上表のlock内で実施する。外部providerの呼出し結果は要求記録と確認結果を分離し、timeout/通信障害/5xx/429では`PENDING_CONFIRMATION`を維持する。poll jobはアカウントごとに確認例外を捕捉し、retry/backoffを永続化して後続アカウントへ継続する。

## 4. 期間重複貸与排除 (Overlap Prevention Architecture)

### 4.1 重複判定代数
同一の `asset_id` に対して、新規貸与期間 `[req_start, req_end]`（`req_end` は未定の場合 NULL）と既存貸与期間 `[exist_start, exist_end]`（`actual_return_date` または `expected_return_date`）の重なり判定式。実返却済み行では `actual_return_date` を次の貸与に対する排他的境界とし、返却日当日の再貸与を許可する:
```
overlap := (exist_start <= req_end OR req_end IS NULL)
       AND (exist_actual_return IS NULL OR exist_actual_return > req_start)
```

### 4.2 トランザクション内排他シーケンス
```
1. BEGIN TRANSACTION
2. SELECT * FROM m_asset WHERE id = :assetId FOR UPDATE;
3. IF asset.status != 'IN_STOCK' THEN THROW BusinessException("資産が保管中ではありません");
4. SELECT COUNT(*) FROM t_asset_assignment
   WHERE asset_id = :assetId
      AND deleted_flag = 0
     AND (actual_return_date IS NULL OR actual_return_date > :startDate)
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
   ▼ (成功: 200 OK)                 ▼ (timeout / 通信障害 / 5xx / 429)
[status: REVOKED]              [status: PENDING_CONFIRMATION]
[revoke_confirmed_at: now]     [external_sync_status: TIMEOUT / SYNC_FAILED]
[revoke_confirmed_by: actor]   [retry_count / next_retry_at を保存]
                                    │
                                    └───────────────┐
                                                    ▼ (応答形式を判別不能な場合だけ)
                                               [status: UNKNOWN]
                                                    │
                                                    ▼
                                               [管理者/ポーリングによる確認]
                                                    │
                                                    ▼
                                               [status: REVOKED]
                                               [revoke_confirmed_at: now]
                                               [revoke_confirmed_by: adminUserId]
```
> **重要**: 外部API呼出しがタイムアウトした場合、システムは決して `REVOKED` や `confirmed` に倒さず、`TIMEOUT` / `SYNC_FAILED` としてアラート一覧に掲出し、退社ゲート blocker を維持する（Fail-Closed 原則）。

応答形式を分類できない場合だけ`UNKNOWN`へ遷移する。`UNKNOWN`はtimeoutの別名ではなく、どちらも`revoke_confirmed_at IS NULL`の間は退社gate blockerである。

---

## 6. NF-01 退社ゲート連携インターフェース契約

### 6.1 `AssetOffboardingService`
`engineer-lifecycle-workflow` の退社ケース完了時、以下のメソッドを通じて 3大 blocker（未返却端末、未失効アカウント、未解放ライセンス）判定を行う:

`ResignationGateChecker` は `RESIGN_ASSET_RETURN` タスクの状態だけを信頼せず、毎回このサービスの3件のcountを照合する。タスクが`COMPLETED`でも3件のcountがすべて0でなければBlockとし、タスクが`WAIVED`の場合も、承認済み`LIFECYCLE_EXCEPTION`の対象一致を検証して`t_asset_offboarding_waiver`へ永続化された台帳行がなければBlockとする。承認申請IDだけのプロセスメモリ登録、任意ID、再起動後に消える免除状態は認めない。

blockerの検索条件はrequirementsと同じOR契約を使い、状態と日付の不整合行もfail-closedで検出する。

```sql
-- 未返却貸与資産
WHERE assignee_type = 'ENGINEER' AND assignee_id = :engineerId
  AND deleted_flag = 0
  AND (status = 'ACTIVE' OR actual_return_date IS NULL)

-- 未解放ライセンス
WHERE assignee_type = 'ENGINEER' AND assignee_id = :engineerId
  AND deleted_flag = 0
  AND (status = 'ACTIVE' OR released_date IS NULL)
```

```java
public interface AssetOffboardingService {
    /**
     * 要員の未返却資産、未失効外部アカウント、および未解放有償ライセンスの検証
     * @param engineerId 対象要員ID
     * @return クリアランス判定結果 (未返却数、未失効数、未解放数、詳細リスト、免除フラグ)
     */
    OffboardingClearanceResultDto checkOffboardingClearance(Long engineerId, Long lifecycleCaseId, Long lifecycleTaskId);

    /**
     * 退社確定時の一括無効化トリガー（アカウント失効要求・ライセンス解放）
     */
    void triggerOffboardingRevocations(Long engineerId, Long actorUserId);

    /**
     * 例外承認適用によるBlocker解除記録 (ApprovalEngine / RequestType = LIFECYCLE_EXCEPTION)
     */
    void approveOffboardingWaiver(Long engineerId, Long lifecycleCaseId, Long lifecycleTaskId,
                                  String reason, Long approvalRequestId, Long actorUserId);
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
