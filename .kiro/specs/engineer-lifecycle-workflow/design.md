# Design — 要員ライフサイクルワークフロー (入社・配属・異動・休職・復職・退社)

> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 本書はplatform-invariantsの決定事項を再掲せず、本spec固有のデータ構造、状態機械、および決定表を規定する。

---

## 1. DDL・データモデル (採番: V109)

本機能の正式Migrationは **V109__engineer_lifecycle_workflow.sql** とする（直前最新は `V108_3__digital_invoice_send_unique.sql`）。

### 1.1 テーブル定義

```sql
-- 1. ライフサイクルテンプレート
CREATE TABLE m_lifecycle_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_type VARCHAR(30) NOT NULL COMMENT 'JOIN, ASSIGNMENT, TRANSFER, LEAVE, REINSTATEMENT, RESIGNATION',
    name VARCHAR(100) NOT NULL COMMENT 'テンプレート名',
    description TEXT NULL COMMENT '説明',
    version_no INT NOT NULL DEFAULT 1 COMMENT '版番号 (改定ごとに+1)',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, INACTIVE',
    valid_from DATE NOT NULL COMMENT '有効開始日 (inclusive)',
    valid_to DATE NULL COMMENT '有効終了日 (inclusive, NULL=無期限)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_lifecycle_tpl_lookup (template_type, status, valid_from, valid_to),
    UNIQUE KEY uk_lifecycle_tpl_type_ver (template_type, version_no, deleted_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ライフサイクルテンプレート';

-- 2. テンプレートタスク定義
CREATE TABLE m_lifecycle_template_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id BIGINT NOT NULL COMMENT 'm_lifecycle_template.id',
    task_code VARCHAR(50) NOT NULL COMMENT 'タスクコード (例: JOIN_DOC_SUBMIT, ACC_REVOKE)',
    task_name VARCHAR(100) NOT NULL COMMENT 'タスク名',
    description TEXT NULL COMMENT 'タスク説明・手順',
    relative_due_days INT NOT NULL DEFAULT 0 COMMENT '基準日からの相対日数 (-7=7日前, 0=当日, 3=3日後)',
    assignee_rule VARCHAR(30) NOT NULL COMMENT 'SPECIFIC_USER, ROLE, ORGANIZATION_MANAGER, PRIMARY_SALES, ENGINEER_SELF, APPLICANT',
    assignee_rule_value VARCHAR(100) NULL COMMENT 'ROLE名や特定UserID等の補助値',
    is_mandatory TINYINT NOT NULL DEFAULT 1 COMMENT '1: 必須, 0: 任意',
    is_blocking TINYINT NOT NULL DEFAULT 1 COMMENT '1: 案件完了を阻害, 0: 非阻害',
    evidence_type VARCHAR(30) NOT NULL DEFAULT 'NONE' COMMENT 'NONE, SELF_DECLARATION, DUAL_CONFIRMATION, DOCUMENT_LINK, SYSTEM_CHECK',
    is_engineer_visible TINYINT NOT NULL DEFAULT 1 COMMENT '1: 本人公開, 0: 内部限定',
    target_employment_types VARCHAR(100) NULL COMMENT '適用雇用形態カンマ区切り (例: 正社員,契約社員,BP / NULL=全形態)',
    sort_order INT NOT NULL DEFAULT 0,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_tpl_task_template (template_id, sort_order),
    UNIQUE KEY uk_tpl_task_code (template_id, task_code, deleted_flag),
    CONSTRAINT fk_tpl_task_template FOREIGN KEY (template_id) REFERENCES m_lifecycle_template(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='テンプレートタスク定義';

-- 3. テンプレートタスク依存関係 (DAG)
CREATE TABLE m_lifecycle_template_task_dep (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id BIGINT NOT NULL,
    predecessor_task_code VARCHAR(50) NOT NULL COMMENT '先行タスクコード',
    successor_task_code VARCHAR(50) NOT NULL COMMENT '後続タスクコード',
    UNIQUE KEY uk_tpl_task_dep (template_id, predecessor_task_code, successor_task_code),
    CONSTRAINT fk_tpl_dep_template FOREIGN KEY (template_id) REFERENCES m_lifecycle_template(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='テンプレートタスク依存関係';

-- 4. ライフサイクル案件インスタンス
CREATE TABLE t_lifecycle_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_no VARCHAR(50) NOT NULL COMMENT '案件番号 (例: LC-202608-0001)',
    lifecycle_type VARCHAR(30) NOT NULL COMMENT 'JOIN, ASSIGNMENT, TRANSFER, LEAVE, REINSTATEMENT, RESIGNATION',
    engineer_id BIGINT NOT NULL COMMENT '対象要員ID',
    template_id BIGINT NOT NULL COMMENT '適用テンプレートID',
    template_version INT NOT NULL COMMENT '適用テンプレート版番号スナップショット',
    anchor_date DATE NOT NULL COMMENT '基準日 (入社日、異動日、退社日等)',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, ACTIVE, ON_HOLD, COMPLETED, CANCELLED',
    title VARCHAR(150) NOT NULL COMMENT '案件タイトル',
    remarks TEXT NULL COMMENT '特記事項',
    applicant_user_id BIGINT NOT NULL COMMENT '起票者ユーザーID',
    engineer_snapshot_json LONGTEXT NOT NULL COMMENT '起票時点の要員・組織・営業スナップショット',
    completed_at DATETIME NULL COMMENT '案件完了日時',
    completed_by BIGINT NULL COMMENT '完了確定ユーザーID',
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_lifecycle_case_no (case_no),
    INDEX idx_lifecycle_case_eng (engineer_id, lifecycle_type, status),
    INDEX idx_lifecycle_case_status (status, anchor_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ライフサイクル案件';

-- 5. ライフサイクルタスクインスタンス
CREATE TABLE t_lifecycle_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL COMMENT 't_lifecycle_case.id',
    task_code VARCHAR(50) NOT NULL COMMENT 'タスクコード',
    task_name VARCHAR(100) NOT NULL COMMENT 'タスク名',
    description TEXT NULL COMMENT '説明・手順',
    due_date DATE NOT NULL COMMENT '算出された期日 (anchor_date + relative_due_days)',
    assignee_user_id BIGINT NULL COMMENT '解決された担当ユーザーID (特定時)',
    assignee_role VARCHAR(30) NULL COMMENT '担当ロール (ROLE解決時)',
    assignee_name_snapshot VARCHAR(100) NULL COMMENT '担当者名スナップショット',
    is_mandatory TINYINT NOT NULL DEFAULT 1 COMMENT '1: 必須, 0: 任意',
    is_blocking TINYINT NOT NULL DEFAULT 1 COMMENT '1: 案件完了を阻害, 0: 非阻害',
    evidence_type VARCHAR(30) NOT NULL DEFAULT 'NONE' COMMENT 'NONE, SELF_DECLARATION, DUAL_CONFIRMATION, DOCUMENT_LINK, SYSTEM_CHECK',
    is_engineer_visible TINYINT NOT NULL DEFAULT 1 COMMENT '1: 本人公開, 0: 内部限定',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, IN_PROGRESS, ON_HOLD, COMPLETED, WAIVED',
    completed_at DATETIME NULL COMMENT '完了日時',
    completed_by BIGINT NULL COMMENT '完了実行者ID',
    completion_comment TEXT NULL COMMENT '完了時コメント',
    evidence_data_json TEXT NULL COMMENT '証跡メタデータJSON',
    approval_request_id BIGINT NULL COMMENT '例外免除時の承認申請ID (ApprovalEngine連携)',
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_lifecycle_task_case (case_id, status),
    INDEX idx_lifecycle_task_assignee (assignee_user_id, status, due_date),
    INDEX idx_lifecycle_task_due (due_date, status),
    CONSTRAINT fk_lifecycle_task_case FOREIGN KEY (case_id) REFERENCES t_lifecycle_case(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ライフサイクルタスク';

-- 6. タスク依存関係インスタンス
CREATE TABLE t_lifecycle_task_dep (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    predecessor_task_id BIGINT NOT NULL,
    successor_task_id BIGINT NOT NULL,
    UNIQUE KEY uk_task_dep (case_id, predecessor_task_id, successor_task_id),
    INDEX idx_task_dep_succ (successor_task_id),
    CONSTRAINT fk_task_dep_case FOREIGN KEY (case_id) REFERENCES t_lifecycle_case(id) ON DELETE CASCADE,
    CONSTRAINT fk_task_dep_pred FOREIGN KEY (predecessor_task_id) REFERENCES t_lifecycle_task(id) ON DELETE CASCADE,
    CONSTRAINT fk_task_dep_succ FOREIGN KEY (successor_task_id) REFERENCES t_lifecycle_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='タスク依存関係インスタンス';

-- 7. 証跡文書リンク
CREATE TABLE t_lifecycle_evidence_link (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL COMMENT 't_lifecycle_task.id',
    document_id BIGINT NOT NULL COMMENT 't_document.id (法定文書台帳リンク)',
    document_version_id BIGINT NULL COMMENT '文書版ID',
    verified_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verified_by BIGINT NOT NULL,
    remarks VARCHAR(255) NULL,
    UNIQUE KEY uk_evidence_doc (task_id, document_id),
    CONSTRAINT fk_evidence_task FOREIGN KEY (task_id) REFERENCES t_lifecycle_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='タスク証跡文書リンク';

-- 8. ライフサイクル追記イベント台帳 (監査ログ)
CREATE TABLE t_lifecycle_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    task_id BIGINT NULL,
    event_type VARCHAR(50) NOT NULL COMMENT 'CASE_CREATED, CASE_ACTIVATED, TASK_STARTED, TASK_COMPLETED, TASK_WAIVED, EXCEPTION_APPLIED, RESIGNATION_GATE_CHECKED, CASE_COMPLETED, CASE_CANCELLED',
    actor_user_id BIGINT NOT NULL,
    actor_role_snapshot VARCHAR(30) NOT NULL,
    before_state VARCHAR(30) NULL,
    after_state VARCHAR(30) NULL,
    details_json LONGTEXT NULL,
    occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_lifecycle_event_case (case_id, occurred_at),
    INDEX idx_lifecycle_event_task (task_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ライフサイクルイベント台帳';
```

---

## 2. アーキテクチャとサービスクラス設計

### 2.1 サービス構成

- `LifecycleTemplateService` / `LifecycleTemplateServiceImpl`: テンプレートCRUD、バージョン管理、DAG循環検証。
- `LifecycleCaseService` / `LifecycleCaseServiceImpl`: 案件CRUD、要員スナップショット固定、タスク一括生成、案件状態遷移（activate, hold, complete, cancel）。
- `LifecycleTaskService` / `LifecycleTaskServiceImpl`: タスク進行（start, complete, waive）、依存充足評価、証跡検証、完了時イベント発行。
- `LifecycleAssigneeResolver`: 担当者解決（`SPECIFIC_USER`, `ROLE`, `ORGANIZATION_MANAGER`, `PRIMARY_SALES`, `ENGINEER_SELF`, `APPLICANT`）。
- `ResignationGateChecker`: 退社ゲートの各システム検証（アカウント無効化、セッション強制失効、組織所属閉鎖、担当営業解除、未精算チェック等）の実行と判定。
- `LifecycleExceptionApprovalAdapter`: 既存統一承認エンジン（`ApprovalEngineService`）とのアダプタ実装（`RequestType = "LIFECYCLE_EXCEPTION"`）。
- `LifecycleScopeService`: 管理側および要員本人側のデータアクセススコープ解決。
- `LifecycleNotificationService`: 期日リマインダー、超過エスカレーション、ブロッカー発生、完了通知（重複抑止キー付き）。

---

## 3. 担当解決ルール (Assignee Resolution)

| ルールコード | 解決元・解決アルゴリズム | 解決不能時の扱い |
|---|---|---|
| `SPECIFIC_USER` | `assignee_rule_value` の User ID | ユーザー不在/無効時は起票エラー |
| `ROLE` | `assignee_rule_value` の Role 名（例: HR, 管理者, 営業） | 当該Roleユーザー不在時は起票エラー |
| `ORGANIZATION_MANAGER` | 要員の所属組織（`t_engineer.organization_id` または `t_user_organization`）の `manager_user_id` | 所属なしまたはManager不在時は起票エラー |
| `PRIMARY_SALES` | 要員の主担当営業（`t_engineer_sales.primary_flag=1` かつ `released_at IS NULL`） | 主担当未設定時は起票エラー |
| `ENGINEER_SELF` | 要員に紐づくログインアカウント（`t_engineer_account_link.sys_user_id`） | アカウント未連携時は起票エラー（BP等でアカウント不要な場合はテンプレート側で別ルール指定） |
| `APPLICANT` | 案件を起票したユーザー（ログイン中ユーザー） | 起票者自身 |

> **Fail-Closed 原則**: いずれかの必須タスクで担当者が解決できない場合、またはDAGに循環依存が存在する場合、案件起票トランザクションは直ちに失敗（400エラー）し、DBを元の状態にロールバックする。

---

## 4. 既存ApprovalEngineとの境界と単純タスク完了の分離

| 操作種別 | 実行経路 | 承認エンジン（ApprovalEngine）の要否 | 理由 |
|---|---|---|---|
| **通常タスクの自己申告・完了** | `LifecycleTaskService.completeTask` | **不要** (直接完了 + CAS) | チェックリスト項目や文書提出は担当者が証跡と共に完了させる。都度重厚な稟議ルートを通すと運用が破綻するため。 |
| **二者確認タスクの完了** | `LifecycleTaskService.completeTask` (verifier検証) | **不要** (直接完了 + 完了者記録) | 完了者と被確認者の職務分離をサービス層で検証し、イベント台帳に記録。 |
| **完了阻害タスクの例外免除 (WAIVE)** | `ApprovalEngineService.request` (`LIFECYCLE_EXCEPTION`) | **必須** (ApprovalEngine経由) | 退社時の未返却端末免除や未完了セキュリティタスクのスキップは内部統制上の重要リスク。是正期日、免除理由、リスクオーナーを記録し、HR/管理者等の多段階承認を強制する。 |
| **案件自体の完了確定** | `LifecycleCaseService.completeCase` | **不要** (ゲート全項目PASSの検証) | 全タスクが `COMPLETED` または承認済み `WAIVED` であることを検証し、退社ゲートのシステム要件を満たした時点で確定。 |

---

## 5. 退社ゲート (Resignation Gate) の決定表

退社案件（`RESIGNATION`）の完了確定（`POST /api/lifecycle/cases/{id}/complete`）に際し、システムは以下の9項目を厳格に評価・実行する:
- **前提条件検査（#6〜#9）**: 阻害タスクの完了/承認済み免除、経費精算、文書保管、稼働中契約の終了。これらが1つでも未充足の場合は `evaluate()` で FAIL となり案件完了をブロックする。
- **案件完了時自動実行（#1〜#5）**: `completeCase` トランザクション内で `executeAutomaticGateActions` により Fail-Closed（1つでも失敗時は全ロールバック）で自動実行される。`evaluate()` では事前確認として対象の有無を検出し、自動実行予定としてレポートする。

| # | チェック項目コード | チェック項目名 | 実行種別 | 判定対象・検証方式 | 未充足時の動作 | 例外免除（WAIVE）の可否 |
|---|---|---|---|---|---|---|
| 1 | `USER_DEACTIVATION` | 内部アカウント無効化 | 自動実行 | 案件完了時に `sys_user.status = 0` に自動更新（アカウント未連携時は対象外PASS） | 完了時自動実行（失敗時Txロールバック） | 自動実行のため免除不要 |
| 2 | `SESSION_REVOCATION` | Webセッション失効 | 自動実行 | `PersistentSessionService` および `PortalSessionService` による全セッション即時破棄 | 完了時自動実行（Fail-Closed） | 自動実行のため免除不要 |
| 3 | `PORTAL_UNLINK` | ポータル連携解除 | 自動実行 | `EngineerAccountLinkService.unlinkByEngineerId` による連携解除 | 完了時自動実行（Fail-Closed） | 自動実行のため免除不要 |
| 4 | `SALES_RELEASE` | 担当営業割当の解除 | 自動実行 | `EngineerSales` の全アクティブ割当の `released_at` に基準日を設定 | 完了時自動実行（Fail-Closed） | 自動実行のため免除不要 |
| 5 | `ORG_ASSIGNMENT_CLOSE` | 組織所属の終了 | 自動実行 | `OrganizationService.closeAssignmentsForUser` による組織配属終了 | 完了時自動実行（Fail-Closed） | 自動実行のため免除不要 |
| 6 | `ASSET_RETURN` | 貸与資産の返却 | 手動阻害タスク | タスク `RESIGN_ASSET_RETURN` の完了状態 (`COMPLETED`/`WAIVED`) | 案件完了をブロック | 可（弁償手続き中等で例外承認必要） |
| 7 | `UNSETTLED_EXPENSE` | 未精算経費・請求確認 | 手動阻害タスク/DB | `t_expense_request` の未精算件数が0件、または `RESIGN_EXPENSE_SETTLE` タスクが承認済み `WAIVED` | 案件完了をブロック | 可（別途精算合意で例外承認必要） |
| 8 | `DOCUMENT_RETENTION` | 法定文書保存確認 | 手動阻害タスク | タスク `RESIGN_DOC_RETENTION` の完了状態 (`COMPLETED`/`WAIVED`) | 案件完了をブロック | 可（紙面保管等の例外承認必要） |
| 9 | `ACTIVE_CONTRACT` | 稼働中契約の終了確認 | システムDB検証 | `t_contract` で対象要員の `status = '稼動中'` の件数が0件 | 案件完了をブロック | 不可（契約終了または解約が必要） |

---

## 6. プラットフォーム不変条件 3大決定表

### 表1: 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| テンプレート定義 | `m_lifecycle_template.status='ACTIVE'` かつ `valid_from <= today <= valid_to` | `m_lifecycle_template.version_no` | `t_lifecycle_case.template_version` | **案件起票時点**で解決しsnapshotへ固定 | `valid_to IS NULL`＝無期限有効 |
| 案件基準日 (anchor_date) | `t_lifecycle_case.anchor_date` | — | 案件作成時に固定 | 案件起票時入力（入社日/退社日等） | 必須（NULL不可） |
| タスク期日 (due_date) | `t_lifecycle_task.due_date` | — | `anchor_date + relative_due_days` で起票時算出 | 起票時算出 | 必須（NULL不可） |
| 担当者解決 | `t_lifecycle_task.assignee_user_id` | — | `assignee_name_snapshot` に記録 | **案件起票時点の事実** | 担当未定（ROLE未アサイン時） |
| 例外是正期限 | `t_lifecycle_task.approval_request_id` 経由 | 承認履歴 | `payload_json.remedy_deadline` | 例外申請時点 | **必須**（将来日付） |

---

### 表2: 主体 × 操作 × 可見母集団

| 主体 | 一覧/詳細/集計 (list/detail/count) | エクスポート/ダウンロード | 通知 (notification) | スケジューラ/バッチ (scheduler) |
|---|---|---|---|---|
| **管理者** | 全件（全テンプレート、全案件、内部セキュリティタスク含む全タスク） | 全件 | 設定不足・未完了エスカレーション・例外承認依頼 | 期限監視バッチ、退社ゲート一括検査 |
| **HR** | 全件（全テンプレート、全案件、内部タスク含む全タスク） | 全件 | 期限接近・超過・承認依頼・完了通知 | — |
| **マネージャー** | 管轄組織所属要員の案件 ∩ DataScope（内部タスク含む） | 同左 | 管轄要員のタスク期限・承認依頼 | — |
| **営業** | 担当要員の案件 ∩ DataScope（内部タスクのうち営業関係のみ、HR機密はマスク） | 同左 | 担当要員の配属/退社タスク | — |
| **要員本人** | **自分対象の案件** かつ `is_engineer_visible = 1` のタスクのみ | 自分の証跡文書のみ | 自分宛のタスク期限・完了案内 | — |
| **Portal User** | 不可視 | — | — | — |
| **Scheduler Principal** | 全件 | — | 宛先は**対象担当者本人のみ** (重複抑止キー適用) | SLA監視、未着手/超過通知 |

> **本人への内部Task非公開境界**: `/api/my/lifecycle/**` では、`t_lifecycle_task.is_engineer_visible = 0` のタスクをSQL条件 `AND t.is_engineer_visible = 1` で完全に除外し、内部セキュリティタスクの存在自体を推測できないようにする。

---

### 表3: 状態機械 と 競合

| 対象 | 状態 | 許可遷移 | 防重・並行制御手段 | competing writer | ロールバック / 補償 |
|---|---|---|---|---|---|
| **Case** | `ACTIVE` | → `ON_HOLD`, → `COMPLETED`, → `CANCELLED` | 状態CAS + `version` + 未完了阻害タスク0件チェック | 二重完了click | `ACTIVE` へ戻す |
| **Case** | `ON_HOLD` | → `ACTIVE`, → `CANCELLED` | 状態CAS + `version` | — | — |
| **Case** | `COMPLETED` | 終端 (直接再open不可) | 状態CAS + `version` | — | 訂正が必要な場合は新案件を起票 |
| **Case** | `CANCELLED` | 終端 | 状態CAS + `version` | — | — |
| **Task** | `PENDING` | → `IN_PROGRESS`, → `ON_HOLD`, → `CANCELLED` | 先行タスク全COMPLETED検知時のCAS | 複数先行タスクの同時完了 | — |
| **Task** | `IN_PROGRESS` | → `COMPLETED`, → `ON_HOLD`, → `WAIVED`, → `CANCELLED` | 状態CAS + `version` | 二重完了click, 本人と代理の同時完了 | `IN_PROGRESS` へ戻す |
| **Task** | `ON_HOLD` | → `IN_PROGRESS`, → `CANCELLED` | 状態CAS + `version` | — | — |
| **Task** | `COMPLETED` | 終端 (原地更新禁止) | 状態CAS + `version` | 同時更新 | 訂正は `t_lifecycle_event` 追記（`TASK_CORRECTION` イベント） |
| **Task** | `WAIVED` | 終端 (例外承認完了後のみ) | `ApprovalEngine` 最終承認CAS | — | 承認取消は却下で表現 |
| **Task** | `CANCELLED` | 終端 (案件CANCELLED時の補償遷移のみ) | cancelCase の同一トランザクション内で一括更新 | — | — |

> **createCase は即 `ACTIVE`**: 案件起票（`createCase`）は DAG 検証・担当者解決・タスク一括生成がすべて成功した場合のみコミットされ、`DRAFT` を経由せず直接 `ACTIVE` で作成される。案件の事前保存（下書き）は現時点では未サポート。

---

## 7. Migration、共有ファイル、並行禁止範囲、ロールバック方針

### 7.1 Migration
- 正式版番号: `V109__engineer_lifecycle_workflow.sql`
- H2用スキーマ: `src/test/resources/sql/schema-lifecycle-workflow-h2.sql` を新設し、`application-test.yml` の `spring.sql.init.schema-locations` へ登録する。
- `V1__create_tables.sql` に新テーブルを追加し、増分migrationとの重複ADD COLUMNが発生しないようにする。

### 7.2 共有ファイル
- `SysUser.java`, `Engineer.java`, `SecurityConfig.java`
- `m_menu` (メニューキー `lifecycle`, `myLifecycle` の追加)
- `templates/layout/sidebar.html` (ライフサイクルメニュー項目の追加)
- `messages*.properties` (4言語バンドル同期)

### 7.3 並行禁止範囲
- `SysUser`, `Engineer`, `t_document`, `t_approval_request` のスキーマ変更を別機能と同時に行わない。
- ライフサイクル案件起票中は対象要員のマスター更新ロックを取得し、同時変更による不整合を防止する。

### 7.4 ロールバック方針
- 案件起票失敗時: 単一DBトランザクションにより全件自動ロールバック。
- 案件取消時: `status = CANCELLED` に更新し、未完了タスクをすべて `CANCELLED` に更新（物理削除しない）。
- 障害復旧: `t_lifecycle_event` の時系列ログから直近の確定状態を再現可能。
