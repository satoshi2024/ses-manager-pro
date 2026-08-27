# Design — カスタマーサクセス・問い合わせ/SLA・顧客ヘルス (NF-02)

## 1. 全体アーキテクチャと方針

- **親機能**: `NF-02 customer-success-service-desk`
- **原則**: `platform-invariants.md` に従い、既存の `Customer`, `Contract`, `CustomerContact`, `WorkCalendarDay`, `DocumentService`, `NotificationService`, `PortalAuthorizationService` を正本として再利用し、重複する顧客マスタや認証機構は作成しない。
- **Migration採番**: 着手時点の latest (V109) + 1 = **V110**。

---

## 2. データベース設計 (DDL: V110)

### 2.1 テーブル一覧

1. **`m_service_sla_policy` (SLAポリシーマスタ)**
   - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
   - `name` VARCHAR(100) NOT NULL COMMENT 'ポリシー名'
   - `priority` VARCHAR(20) NOT NULL COMMENT '優先度 (P0, P1, P2, P3)'
   - `response_time_hours` INT NOT NULL COMMENT '初回応答目標時間(時間)'
   - `resolve_time_hours` INT NOT NULL COMMENT '解決目標時間(時間)'
   - `business_hours_start` TIME NOT NULL DEFAULT '09:00:00' COMMENT '始業時刻'
   - `business_hours_end` TIME NOT NULL DEFAULT '18:00:00' COMMENT '終業時刻'
   - `include_holidays` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '休日を含むか(0:除外, 1:含む)'
   - `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE'
   - `version` INT NOT NULL DEFAULT 0
   - `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
   - `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
   - UNIQUE KEY `uk_sla_policy_priority` (`priority`, `status`)

2. **`t_service_request` (問い合わせ・課題トランザクション)**
   - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
   - `request_no` VARCHAR(64) NOT NULL COMMENT 'リクエスト番号 REQ-YYYYMM-XXXX'
   - `customer_id` BIGINT NOT NULL COMMENT '顧客ID'
   - `contact_id` BIGINT NULL COMMENT '顧客担当者ID'
   - `contract_id` BIGINT NULL COMMENT '契約ID'
   - `project_id` BIGINT NULL COMMENT '案件ID'
   - `engineer_id` BIGINT NULL COMMENT '要員ID'
   - `category` VARCHAR(50) NOT NULL COMMENT 'CONTRACT, BILLING, ATTENDANCE, QUALITY, SYSTEM, OTHER'
   - `priority` VARCHAR(20) NOT NULL COMMENT 'P0, P1, P2, P3'
   - `channel` VARCHAR(30) NOT NULL COMMENT 'PORTAL, EMAIL, PHONE, MEETING, INTERNAL'
   - `subject` VARCHAR(255) NOT NULL COMMENT '件名'
   - `description` TEXT NOT NULL COMMENT '詳細内容'
   - `owner_user_id` BIGINT NULL COMMENT '社内主担当者 sys_user.id'
   - `status` VARCHAR(30) NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED, IN_PROGRESS, WAITING_CUSTOMER, RESOLVED, CLOSED'
   - `first_response_at` DATETIME NULL COMMENT '初回応答日時'
   - `resolved_at` DATETIME NULL COMMENT '解決日時'
   - `closed_at` DATETIME NULL COMMENT '終了日時'
   - `reopened_at` DATETIME NULL COMMENT '最新再オープン日時'
   - `reopen_count` INT NOT NULL DEFAULT 0 COMMENT '再オープン回数'
   - `version` INT NOT NULL DEFAULT 0
   - `created_by` BIGINT NULL COMMENT '作成者 (内部sys_user.id or NULL)'
   - `portal_user_id` BIGINT NULL COMMENT '起票元ポータルユーザーID'
   - `updated_by` BIGINT NULL
   - `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
   - `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
   - UNIQUE KEY `uk_service_request_no` (`request_no`),
   - INDEX `idx_sr_customer_status` (`customer_id`, `status`),
   - INDEX `idx_sr_owner_status` (`owner_user_id`, `status`),
   - INDEX `idx_sr_priority_status` (`priority`, `status`)

3. **`t_service_sla_clock` (SLA計時・履歴テーブル)**
   - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
   - `service_request_id` BIGINT NOT NULL COMMENT 'サービスリクエストID'
   - `round_no` INT NOT NULL DEFAULT 1 COMMENT 'ラウンド番号(再オープン時に新ラウンド作成)'
   - `policy_id` BIGINT NOT NULL COMMENT '適用SLAポリシーID'
   - `response_deadline` DATETIME NOT NULL COMMENT '初回応答期限'
   - `resolve_deadline` DATETIME NOT NULL COMMENT '解決目標期限'
   - `first_responded_at` DATETIME NULL COMMENT '実初回応答日時'
   - `response_breached` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '初回応答超過フラグ'
   - `resolved_at` DATETIME NULL COMMENT '実解決日時'
   - `resolve_breached` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '解決超過フラグ'
   - `total_pause_minutes` INT NOT NULL DEFAULT 0 COMMENT '累計停止時間(分)'
   - `last_paused_at` DATETIME NULL COMMENT '最終停止開始日時'
   - `status` VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING, PAUSED, COMPLETED'
   - `version` INT NOT NULL DEFAULT 0
   - `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
   - `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
   - UNIQUE KEY `uk_sla_clock_req_round` (`service_request_id`, `round_no`)

4. **`t_service_comment` (スレッドコメント・内部メモ)**
   - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
   - `service_request_id` BIGINT NOT NULL COMMENT 'サービスリクエストID'
   - `author_type` VARCHAR(20) NOT NULL COMMENT 'INTERNAL_USER, PORTAL_USER, SYSTEM'
   - `author_id` BIGINT NOT NULL COMMENT 'sys_user.id or portal_user.id'
   - `author_name` VARCHAR(100) NOT NULL COMMENT '投稿者表示名'
   - `visibility` VARCHAR(20) NOT NULL DEFAULT 'PORTAL_VISIBLE' COMMENT 'PORTAL_VISIBLE, INTERNAL'
   - `comment_text` TEXT NOT NULL COMMENT 'コメント本文'
   - `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
   - `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
   - INDEX `idx_comment_request_vis` (`service_request_id`, `visibility`, `created_at`)

5. **`t_service_attachment_link` (添付ファイルリンク)**
   - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
   - `service_request_id` BIGINT NOT NULL COMMENT 'サービスリクエストID'
   - `comment_id` BIGINT NULL COMMENT '紐づくコメントID (任意)'
   - `document_id` BIGINT NOT NULL COMMENT 't_document.id'
   - `visibility` VARCHAR(20) NOT NULL DEFAULT 'PORTAL_VISIBLE' COMMENT 'PORTAL_VISIBLE, INTERNAL'
   - `file_name` VARCHAR(255) NOT NULL COMMENT 'ファイル名'
   - `file_size` BIGINT NOT NULL COMMENT 'ファイルサイズ'
   - `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
   - INDEX `idx_att_request_vis` (`service_request_id`, `visibility`)

6. **`t_service_state_event` (状態変更監査ログ)**
   - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
   - `service_request_id` BIGINT NOT NULL
   - `round_no` INT NOT NULL DEFAULT 1
   - `from_status` VARCHAR(30) NULL
   - `to_status` VARCHAR(30) NOT NULL
   - `reason` VARCHAR(255) NULL
   - `actor_type` VARCHAR(20) NOT NULL COMMENT 'INTERNAL_USER, PORTAL_USER, SYSTEM'
   - `actor_id` BIGINT NOT NULL
   - `actor_name` VARCHAR(100) NOT NULL
   - `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP

7. **`t_customer_csat` (顧客満足度調査回答)**
   - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
   - `service_request_id` BIGINT NOT NULL COMMENT '対象リクエストID (UNIQUE)'
   - `customer_id` BIGINT NOT NULL COMMENT '顧客ID'
   - `portal_user_id` BIGINT NOT NULL COMMENT '回答ポータルユーザーID'
   - `score` INT NOT NULL COMMENT '評価スコア (1:大変不満 〜 5:大変満足)'
   - `feedback_comment` TEXT NULL COMMENT 'フィードバックコメント'
   - `answered_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
   - UNIQUE KEY `uk_csat_request` (`service_request_id`),
   - INDEX `idx_csat_customer` (`customer_id`, `answered_at`)

8. **`t_customer_qbr` (定例会・QBR記録)**
   - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
   - `customer_id` BIGINT NOT NULL COMMENT '顧客ID'
   - `title` VARCHAR(255) NOT NULL COMMENT '会議タイトル'
   - `meeting_date` DATE NOT NULL COMMENT '開催日'
   - `attendees` TEXT NULL COMMENT '参加者'
   - `agenda` TEXT NULL COMMENT '議題'
   - `discussion` TEXT NULL COMMENT '討議内容'
   - `decisions` TEXT NULL COMMENT '決定事項'
   - `next_meeting_date` DATE NULL COMMENT '次回予定日'
   - `created_by` BIGINT NULL
   - `updated_by` BIGINT NULL
   - `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
   - `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
   - INDEX `idx_qbr_customer_date` (`customer_id`, `meeting_date`)

9. **`t_customer_qbr_action` (QBRアクションアイテム)**
   - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
   - `qbr_id` BIGINT NOT NULL COMMENT 'QBR ID'
   - `title` VARCHAR(255) NOT NULL COMMENT 'タスク件名'
   - `description` TEXT NULL COMMENT 'タスク詳細'
   - `owner_user_id` BIGINT NULL COMMENT '担当者 sys_user.id'
   - `due_date` DATE NOT NULL COMMENT '期日'
   - `status` VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN, IN_PROGRESS, COMPLETED, CANCELLED'
   - `completed_at` DATETIME NULL
   - `version` INT NOT NULL DEFAULT 0
   - `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
   - `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
   - INDEX `idx_qbr_action_owner` (`owner_user_id`, `status`),
   - INDEX `idx_qbr_action_due` (`due_date`, `status`)

10. **`t_customer_health_snapshot` (顧客ヘルススナップショット)**
    - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
    - `customer_id` BIGINT NOT NULL COMMENT '顧客ID'
    - `snapshot_date` DATE NOT NULL COMMENT 'スナップショット日付'
    - `health_status` VARCHAR(20) NOT NULL COMMENT 'HEALTHY, WARNING, CRITICAL'
    - `total_score` INT NOT NULL COMMENT '総合スコア 0-100'
    - `open_critical_issues_count` INT NOT NULL DEFAULT 0 COMMENT '未解決P0/P1件数'
    - `sla_breach_count_30d` INT NOT NULL DEFAULT 0 COMMENT '直近30日SLA違反件数'
    - `avg_csat_score` DECIMAL(3,2) NULL COMMENT '平均CSAT'
    - `ar_overdue_flag` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '売掛金延滞有無'
    - `missing_inputs_json` TEXT NULL COMMENT '欠損入力項目JSON'
    - `factors_explanation` TEXT NULL COMMENT 'スコア算出根拠説明'
    - `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    - UNIQUE KEY `uk_health_customer_date` (`customer_id`, `snapshot_date`)

---

## 3. SLA計算エンジン (`ServiceSlaCalculator`)

### 3.1 営業時間と休日ロジック
- 標準営業時間: デフォルト 09:00 〜 18:00 (1日9時間稼働枠)。
- 休日判定: `WorkCalendarDay` または 土日 (Saturday/Sunday) および祝日。
- 期限算出ロジック:
  1. 開始日時 $T_{start}$ が営業時間外の場合は、直近の直後の営業日の始業時刻（例: 09:00）に補正。
  2. 残り目標時間 $H_{remain}$ を営業日ごとの稼働可能時間（分単位）で消費しながら加算。
  3. 終業時刻（18:00）を超過する場合は翌営業日の始業時刻（09:00）へ繰り越し。
- 一時停止・再開:
  - `WAITING_CUSTOMER` 遷移時に `last_paused_at = now`, `status = 'PAUSED'` を設定。
  - 対応再開（`IN_PROGRESS`等）時に、停止期間（$\Delta t = now - last\_paused\_at$）を計算し、`total_pause_minutes` に加算。
  - `response_deadline` および `resolve_deadline` を $\Delta t$ の営業日換算分だけ後ろ倒しに再計算・更新。

---

## 4. 顧客ヘルススコア算出ロジック (`CustomerHealthCalculator`)

100点満点の減点/加点モデル（説明可能）：
- 基礎点: 100点
- 減点要因:
  - 未解決 P0 リクエスト 1件につき: -30点
  - 未解決 P1 リクエスト 1件につき: -15点
  - 直近30日間の SLA 違反（初回応答 or 解決） 1件につき: -10点
  - 売掛金（AR）延滞発生中: -25点
  - 直近CSAT平均が 3.0 未満: -15点 (3.0〜3.9: -5点, 4.0以上: 0点, 回答なし: 欠損マークで減点なし)
  - 過去60日以内に定例会(QBR)または接触なし: -10点
- スコア範囲: $\max(0, \min(100, \text{Score}))$
- ステータス区分:
  - 80〜100点: `HEALTHY` (緑)
  - 50〜79点: `WARNING` (黄)
  - 0〜49点: `CRITICAL` (赤)
- **非破壊原則**: 顧客ヘルススコアは契約更新カレンダーの表示バッジとしてのみ利用され、契約テーブル（`t_contract.renewal_decision`）を自動変更しない。

---

## 5. 3つの決定表 (`platform-invariants.md` §8 準拠)

### 表1: 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| サービスリクエスト状態 | `t_service_request.status` | `t_service_state_event` | — | 現在値 | — |
| SLA計時 | `t_service_sla_clock` (最新round) | `t_service_sla_clock` (過去round) | `t_service_sla_clock` | 当該roundの確定値 | 未設定/計時前 |
| SLA初回応答/解決超過 | `response_breached`, `resolve_breached` | round別レコード | — | 当該roundの確定値 | 超過なし (0) |
| CSAT回答 | `t_customer_csat.score` | — | 回答時snapshot | 現在値 | **未回答** (回答可能) |
| 顧客ヘルス | 最新日の`t_customer_health_snapshot` | 日別`t_customer_health_snapshot` | `t_customer_health_snapshot` | 指定日スナップショット | 未算定 |
| QBRアクション | `t_customer_qbr_action.status` | 監査ログ | — | 現在値 | 期日未設定/担当未設定 |

### 表2: 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 内部 管理者 | 全顧客の全リクエスト・内部メモ・QBR・ヘルス | 全件CSV | 全通知 | SLA監視・ヘルス日次集計 |
| 内部 マネージャー | 組織scope ∩ DataScope の顧客リクエスト・QBR・ヘルス | 同左 | 自組織担当のSLAアラート | — |
| 内部 営業 | 担当顧客 (DataScope) のリクエスト・内部メモ・QBR・ヘルス | 同左 | 自担当のSLAアラート・起票通知 | — |
| 内部 HR / 要員 | 閲覧不可 (403) | 閲覧不可 | — | — |
| ポータル利用者 (Customer) | **自社 (`customerId`) かつ `visibility='PORTAL_VISIBLE'` のみ**。内部メモ・原価・内部担当者IDは不可視 | 自社リクエスト添付のみ | 自社リクエストの返信通知 | — |
| ポータル利用者 (BP) | 閲覧不可 (403/404) | 閲覧不可 | — | — |
| scheduler principal | 全件 | — | 担当営業/マネージャー宛にdedupe通知 | SLA期限超過検出、日次ヘルス集計 |

### 表3: 状態機械 と 競合

| 状態 | 許可遷移 | 遷移の防重手段 | competing writer | rollback |
|---|---|---|---|---|
| `RECEIVED` | → `IN_PROGRESS`, → `WAITING_CUSTOMER`, → `CLOSED` | 状態条件付きUPDATE / `version` CAS | 同時アサイン・同時着手 | 409 Conflict |
| `IN_PROGRESS` | → `WAITING_CUSTOMER`, → `RESOLVED`, → `CLOSED` | 状態CAS＋SLA時計更新 | 同時解決・同時保留 | 409 Conflict |
| `WAITING_CUSTOMER` | → `IN_PROGRESS`, → `RESOLVED`, → `CLOSED` | 状態CAS＋SLA時計再開 | ポータル返信と内部操作の同時実行 | 先着優先 |
| `RESOLVED` | → `CLOSED`, → `REOPENED` | 状態CAS | 顧客の再オープンと社内のクローズ | 先着優先 |
| `CLOSED` | → `REOPENED` | 状態CAS | 再オープン | 先着優先 |
| `REOPENED` | → `IN_PROGRESS` | 新規SLA Clock作成 (新round) | 二重再オープン防止 (`UNIQUE(request_id, round_no)`) | ロールバック |
| CSAT回答 | 1回のみ回答可能 | `UNIQUE(service_request_id)` | 二重回答・多重送信 | 409 Conflict |

---

## 6. API・画面設計

### 6.1 内部管理・営業向け API & 画面
- **URL**:
  - `/service-desk/requests` (問い合わせ一覧画面)
  - `/service-desk/requests/{id}` (問い合わせ詳細・タイムライン画面)
  - `/service-desk/qbr` (定例会・QBR一覧/登録画面)
  - `/service-desk/health` (顧客ヘルス分析画面)
- **API**:
  - `GET /api/service-desk/requests` (検索・一覧・ページネーション・DataScope適用)
  - `GET /api/service-desk/requests/{id}` (詳細取得・内部メモ含む・SLA状態含む)
  - `POST /api/service-desk/requests` (新規起票)
  - `PUT /api/service-desk/requests/{id}` (属性・担当者更新)
  - `POST /api/service-desk/requests/{id}/status` (ステータス変更: 着手/保留/解決/終了/再オープン)
  - `POST /api/service-desk/requests/{id}/comments` (コメント・内部メモ投稿)
  - `GET /api/service-desk/requests/{id}/attachments/{attId}/download` (添付ダウンロード)
  - `GET /api/service-desk/qbr` / `POST /api/service-desk/qbr` (QBR CRUD)
  - `POST /api/service-desk/qbr/{id}/actions` (QBR Action登録・ステータス更新)
  - `GET /api/service-desk/health` (顧客ヘルス一覧・要因分析)
  - `POST /api/service-desk/health/recalculate` (ヘルス手動再計算)
  - `GET /api/service-desk/requests/export` (CSVエクスポート)

### 6.2 顧客ポータル向け API & 画面
- **URL**:
  - `/portal/customer/requests` (自社問い合わせ一覧画面)
  - `/portal/customer/requests/{id}` (詳細・返信・CSAT回答画面)
- **API**:
  - `GET /api/portal/customer/requests` (自社リクエスト一覧)
  - `GET /api/portal/customer/requests/{id}` (詳細取得・`PORTAL_VISIBLE` コメントのみ返却・内部メモ除外DTO)
  - `POST /api/portal/customer/requests` (ポータルからの新規問い合わせ起票)
  - `POST /api/portal/customer/requests/{id}/comments` (ポータルからの返信コメント投稿)
  - `POST /api/portal/customer/requests/{id}/csat` (CSAT回答投稿・1回限り・DB UNIQUE保護)
  - `GET /api/portal/customer/requests/{id}/attachments/{attId}/download` (自社添付ダウンロード・IDOR拒否)

---

## 7. テスト・検証計画

1. **SLA 計算・休祝日・タイムゾーン・Pause・Reopen テスト**:
   - 営業時間外（深夜・早朝）起票時の翌営業日開始補正。
   - 金曜夕方起票時の土日を跨ぐ期限計算。
   - `WAITING_CUSTOMER` による Pause と `IN_PROGRESS` 再開時の営業日換算期限繰り延べ。
   - Reopen 時の新 round_no 作成と過去 round_no の SLA 実績保持。
2. **セキュリティ・スコープ・IDOR 拒否テスト**:
   - 顧客Aのポータルセッションから顧客Bのリクエスト、添付、コメント、CSAT APIへのアクセス拒否 (404/403)。
   - ポータルAPIレスポンスDTOに `INTERNAL` コメント、原価、内部ユーザーIDが含まれないことの検証。
   - 内部営業ユーザーにおける DataScope 制限（担当外顧客の秘匿）。
3. **競合・冪等性・重複防止テスト**:
   - 同一リクエストに対する CSAT 二重投稿の拒否 (UNIQUE制約)。
   - 同一リクエストに対する並行ステータス更新の CAS 保護。
   - SLA スケジューラの二重実行および Dedupe 通知の検証。
4. **契約更新カレンダー連携 & 顧客ヘルス算定テスト**:
   - 未解決 P0/P1、SLA 違反、CSAT、AR 延滞に応じたヘルススコアの透明な算出と欠損入力マーク。
   - 更新カレンダー上でのヘルスバッジ表示と、契約ステータスが自動更新されないことの検証。
