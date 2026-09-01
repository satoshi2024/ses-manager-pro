# Operations & Runbook: Customer Success & Service Desk (NF-02)

## 1. 概要 (Overview)

SES Manager Pro のカスタマーサクセス・サービスデスク機能 (NF-02) は、顧客企業からの問い合わせ・要望・インシデントを一元管理し、SLA 監視、CSAT (顧客満足度調査)、定例会 (QBR) 管理、および多角的な指標に基づく顧客ヘルススコア算定と契約更新判断支援を提供する統合運用基盤です。

### 構成要素
1. **サービスデスク (Service Desk)**: 社内向け (`/service-desk/requests`) および顧客ポータル向け (`/portal/customer/service-desk/requests`) の起票・返信・ステータス管理。
2. **SLA 計算・監視エンジン (SLA Engine & Scheduler)**: 営業時間 (09:00〜18:00)・土日祝日スキップ・顧客回答待ち Pause・再オープンラウンド管理・超過時 Dedupe 通知。
3. **CSAT (顧客満足度調査)**: 解決済みリクエストに対する 1 回限りのポータル評価フォーム (1〜5点 + コメント)。
4. **定例会・QBR 管理 (Customer QBR)**: 議事録・決定事項・アクションアイテム・期日管理。
5. **顧客ヘルススコア (Customer Health Score)**: 未解決P0/P1、直近30日SLA超過、直近90日CSAT平均、売掛金延滞、直近60日QBRに基づく 100 点満点減点モデルと要因内訳。
6. **契約更新カレンダー連携 (Renewal Calendar Integration)**: 契約更新カレンダー (`/contract/renewal-calendar`) へのヘルス状態バッジ表示（契約ステータスは非破壊・参照のみ）。

---

## 2. 権限 & アクセスマトリクス (Role & Permission Matrix)

| 機能 / エンドポイント | 管理者 | 営業 | マネージャー | HR / 要員 | 顧客ポータルユーザー | 備考 |
|---|---|---|---|---|---|---|
| **内部サービスデスク** (`/service-desk/requests`) | ◯ | ◯ (DataScope) | ◯ | ✕ (403) | ✕ (403) | 営業は DataScope (担当顧客) 制御適用 |
| **顧客ポータル** (`/portal/customer/service-desk/requests`) | ✕ (内部ログイン) | ✕ | ✕ | ✕ | ◯ (自社スコープのみ) | IDOR 違反時は 404 Not Found を返却 |
| **内部メモ (Internal Note)** | ◯ | ◯ | ◯ | ✕ | ✕ (API/DTO レベルで完全除外) | 構造的漏洩防止 |
| **SLA ポリシー参照** (`/api/service-desk/requests/policies`) | ◯ | ◯ | ◯ | ✕ | ✕ | 優先度別目標時間設定 |
| **顧客ヘルス閲覧** (`/customer-success/health`) | ◯ | ◯ (DataScope) | ◯ | ✕ | ✕ | 4要素要因内訳表示 |
| **顧客ヘルススナップショット生成** (`/api/customer-success/health/snapshots`, `/api/service-desk/health/snapshots`) | ◯ | ✕ (403) | ✕ (403) | ✕ (403) | ✕ (403) | 管理者および厳格なSYSTEMスケジューラコンテキスト専用。2つのURLは同一action権限 |
| **QBR 定例会管理** (`/api/customer-success/qbrs`) | ◯ | ◯ (DataScope) | ◯ | ✕ | ✕ | |
| **契約更新カレンダー** (`/contract/renewal-calendar`) | ◯ | ◯ | ◯ | ✕ | ✕ | 契約更新判定は営業・管理者の手動 |

---

## 3. 運用手順 (Operational Workflows)

### 3.1 サービスリクエストの受付・対応フロー
1. **起票**:
   - 顧客ポータルまたは社内管理画面から起票（優先度 P0〜P3 を指定）。
   - 起票と同時に Round 1 の `t_service_sla_clock` が生成され、初回応答期限・解決目標期限が営業日・営業時間ベースで自動設定される。
2. **受付・初回応答 (`RECEIVED` → `IN_PROGRESS`)**:
   - 担当者をアサインし、顧客向け返信を送信。実初回応答日時が記録され、初回応答 SLA が確定。
3. **顧客確認待ち (`IN_PROGRESS` → `WAITING_CUSTOMER`)**:
   - 顧客からの追加情報待ちの場合、ステータスを `WAITING_CUSTOMER` に変更。SLA 計時が一時停止 (Pause) される。
4. **対応再開 (`WAITING_CUSTOMER` → `IN_PROGRESS`)**:
   - 顧客から返信があった場合、計時が再開され、停止していた時間が解決目標期限に自動延長加算される。
5. **解決 (`IN_PROGRESS` → `RESOLVED`)**:
   - 対応完了時にステータスを `RESOLVED` に変更。解決 SLA が確定し、ポータル側に CSAT 評価フォームが表示される。
6. **再オープン (`RESOLVED` → `IN_PROGRESS`)**:
   - 顧客から追加の問い合わせがあった場合、再オープン回数がインクリメントされ、Round 2 の SLA クロックが新設される（過去の Round 1 記録は保持）。
7. **終了 (`RESOLVED` → `CLOSED`)**:
   - 対応完了を確認後、クローズ。

### 3.2 顧客ヘルススコア算定 (100点満点 減点モデル)
- **基本配点 (初期値 100点)**:
  1. **未解決 P0 リクエスト**: 1件につき -30点、**未解決 P1**: 1件につき -15点
  2. **直近 30 日 SLA 超過件数**: 1件につき -10点
  3. **直近 90 日 CSAT**: 平均 3.0 未満で -15点、3.0以上4.0未満で -5点、4.0以上は0点（未評価・データ欠損時は減点なし）
  4. **売掛金 (AR) 延滞**: 延滞発生中で -25点
  5. **直近 60 日 QBR 未開催**: 既存QBRがある場合は -10点（QBR未登録は欠損入力）
  - スコア範囲: 0点〜100点 (0点未満にはならない)
- **状態判定**:
  - `HEALTHY` (健全): 80点以上
  - `WARNING` (注意): 50〜79点
  - `CRITICAL` (危険): 50点未満
- **スナップショットの非破壊リビジョン管理**:
  - 同一月のスナップショット再実行時、データ変更がなければ冪等にスキップ。
  - データ変更がある場合は上書き更新（UPDATE）を行わず、非空の訂正理由を付けた新しい版（`version_no` + 1）を INSERT します。最新版は最大versionで解決し、DB triggerでもUPDATE/DELETEを拒否します。
  - `targetMonth` は現在tenant月のみ受け付けます。過去月をas-of算定できない実装で受理しません。
  - SecurityContextなしの直接呼出しは拒否し、管理者または actor/source が固定されたSYSTEM schedulerだけを許可します。

---

## 4. バッチ & スケジューラ運用 (Schedulers & Background Tasks)

### 4.1 `ServiceSlaScheduler`
- **実行間隔**: 設定値 `service.sla-monitoring.cron`（既定5分毎）
- **実行内容**:
  1. 進行中リクエストの SLA クロックを走査。
  2. 期限30分前以内のwarning、期限到達後の初回breach、以後30分間隔の継続breachを通知台帳へdedupe記録。
  3. owner → 契約担当営業 → 顧客の有効契約担当営業 → 有効管理者の順に受信者を解決し、受信者不在/配信失敗を永続化して再試行。
- **分散ロック**: ShedLock (`name = "serviceSlaMonitoring"`, `lockAtLeastFor = "PT1M"`, `lockAtMostFor = "PT10M"`)。各clock更新はversion/status CAS。

### 4.2 `CustomerHealthScheduler`
- **実行間隔**: 毎日未明 02:00 (`0 0 2 * * *`)
- **実行内容**:
  1. 全顧客の当月ヘルススコアを計算し、固定actor/sourceを持つcontrolled `SYSTEM` schedulerとしてスナップショットを作成。
- **分散ロック**: ShedLock (`name = "customerHealthSnapshotDaily"`, `lockAtLeastFor = "PT1M"`, `lockAtMostFor = "PT30M"`)。

---

## 5. トラブルシューティング (Troubleshooting)

| 現象 | 想定原因 | 対処方法 |
|---|---|---|
| ポータルからリクエストが見えない / 404 エラー | ログインユーザーの顧客組織IDとリクエストの `customer_id` が不一致 | ポータルユーザーの所属組織設定 (`m_portal_organization`) を確認 |
| SLA の期限が営業時間外に設定されている | カレンダーマスタ (`m_work_calendar_day`) の休日設定またはポリシーの営業時間が不正 | 所属法人・組織のカレンダー設定を確認 |
| CSAT を複数回回答できない | 1リクエストにつき1回のみ回答可能な仕様（一意性制約） | 正常な動作です。再評価が必要な場合はリクエストを再オープンしてください |
| CSV エクスポートで文字化けする | Excel のエンコーディング自動判別エラー | 本システムの CSV は UTF-8 with BOM で出力されているため、最新の Excel またはテキストエディタで直接開いてください |

---

## 6. 未マージ開発DBにおける旧V110のReset / Repair手順 (Unmerged Dev DB Reset & Repair Guide)

旧 NF02 feature ブランチ (`origin/codex/customer-success-service-desk`) の `V110__customer_success_service_desk.sql` を手元で適用してしまった開発環境データベースは、本番・メイン統合用のマイグレーション `V147__customer_success_service_desk.sql` と Flyway バージョン番号およびチェックサムが衝突します。

Flyway の既存チェックサムを直接書き換えることは禁止されています。以下のいずれかの手順でデータベースを正常化してください。

### 手順 A: 開発用DBを全リセットする場合（推奨）
```bash
# 1. 既存の開発DBをドロップして再作成
mysql -u root -p -e "DROP DATABASE IF EXISTS ses_manager_db; CREATE DATABASE ses_manager_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 2. アプリケーションを起動（V1〜V147が自動適用されます）
.\apache-maven-3.9.6\bin\mvn spring-boot:run
```

### 手順 B: 開発DBのデータを残して旧V110のみロールバック・修復する場合

> 開発用の退避可能なDBだけで実行し、事前にbackupを取得してください。旧V110の正確な履歴行だけを削除し、他のFlyway履歴や既存業務テーブルは変更しません。
```sql
-- 1. 旧V110で作成されたテーブルを削除
DROP TABLE IF EXISTS t_customer_csat;
DROP TABLE IF EXISTS t_customer_health_snapshot;
DROP TABLE IF EXISTS t_customer_qbr_action;
DROP TABLE IF EXISTS t_customer_qbr;
DROP TABLE IF EXISTS t_service_state_event;
DROP TABLE IF EXISTS t_service_comment;
DROP TABLE IF EXISTS t_service_attachment_link;
DROP TABLE IF EXISTS t_service_sla_escalation;
DROP TABLE IF EXISTS t_service_sla_clock;
DROP TABLE IF EXISTS t_service_request;
DROP TABLE IF EXISTS m_service_sla_policy;

-- 2. 旧V110で挿入されたメニュー/ロールデータを削除
DELETE FROM t_role_menu WHERE menu_id IN (SELECT id FROM m_menu WHERE menu_key = 'service-desk');
DELETE FROM m_menu WHERE menu_key = 'service-desk';

-- 3. Flyway 履歴テーブルから旧 V110 レコードを削除
DELETE FROM flyway_schema_history WHERE version = '110' AND script = 'V110__customer_success_service_desk.sql';
```

その後、`mvn spring-boot:run` を実行すると、正規の `V147__customer_success_service_desk.sql` が適用されます。
