# Tasks — カスタマーサクセス・問い合わせ/SLA・顧客ヘルス (NF-02)

## タスク一覧と進捗

- [x] **Task 0: Discovery & 現行境界インベントリ・DG-02 合意**
  - **Objective**: Customer, Contact, Contract, Portal, Notification, WorkCalendar, Document の現行境界を確認し、DG-02 の決定事項を spec に反映する。
  - **Guidance**:
    - 既存の `PortalAuthorizationService`, `DataScopeService`, `WorkCalendarDay`, `NotificationService`, `DocumentService` の接続点を確認。
    - Migration latest (V109) を確認し、本 spec の DDL を V110 に確定。
  - **Test Requirements**:
    - 既存テストスイートの健全性確認（`mvn test-compile`）。
  - **Demo**:
    - 現行境界インベントリと DG-02 決定事項が spec に記録されていることを確認。

- [x] **Task F1: データベース DDL & エンティティ整備 (V110)**
  - **Objective**: サービスデスク、SLAポリシー、計時クロック、コメント、添付リンク、状態イベント、CSAT、QBR、ヘルススナップショットのテーブルを作成する。
  - **Guidance**:
    - `V110__customer_success_service_desk.sql` を作成。
    - `V1__create_tables.sql` に consolidated baseline を同期。
    - `sql/schema-service-desk-h2.sql` を作成し、`application-test.yml` の `schema-locations` に追加。
    - エンティティクラス群 (`ServiceRequest`, `ServiceSlaPolicy`, `ServiceSlaClock`, `ServiceComment`, `ServiceAttachmentLink`, `ServiceStateEvent`, `CustomerCsat`, `CustomerQbr`, `CustomerQbrAction`, `CustomerHealthSnapshot`) と Mapper を実装。
  - **Test Requirements**:
    - `FlywayMigrationSmokeTest` または H2 コンテキスト起動テストで V110 DDL とエンティティの整合性を検証。
  - **Demo**:
    - H2 / MySQL で V110 の全テーブル・インデックス・UNIQUE 制約が正しく適用されることを確認。

- [x] **Task F2: SLA 計算エンジン & 状態機械 & スコープ解決基盤**
  - **Objective**: 営業時間・休日・タイムゾーンを考慮した SLA 計算ロジック、状態遷移エンジン、認可スコープ解決を実装する。
  - **Guidance**:
    - `ServiceSlaCalculator`: 営業時間（09:00-18:00）、土日祝日（`WorkCalendarDay`）、Pause / Resume、Reopen ラウンド別の計時。
    - `ServiceRequestService` / `ServiceImpl`: 状態遷移 CAS、初回応答日時記録、解決日時記録、再オープン処理。
    - 認可スコープ: 内部は `DataScopeService` / `CrmScopeService`、外部は `PortalAuthorizationService`。
  - **Test Requirements**:
    - 単体テスト `ServiceSlaCalculatorTest`: 営業時間内外、週末跨ぎ、Pause/Resume、Reopen round の網羅テスト。
    - 状態機械テスト `ServiceRequestServiceImplTest`: 許可/禁止遷移、CAS 競合、再オープン時の SLA 時計新ラウンド作成。
  - **Demo**:
    - SLA 計算が土日を正しくスキップし、顧客待ち中の Pause が正しく期限を延長することをテスト実行で実証。

- [x] **Task A1: 内部サービスデスク管理画面 & REST API**
  - **Objective**: 管理者・営業・マネージャー向けサービスデスク一覧・詳細画面、および内部起票・更新・コメント・状態変更 API を実装する。
  - **Guidance**:
    - `ServiceRequestApiController`: `/api/service-desk/requests/**`（一覧検索、詳細取得、起票、更新、状態変更、コメント投稿）。
    - `ServiceRequestPageController`: `/service-desk/requests`（一覧画面）、`/service-desk/requests/{id}`（詳細画面）。
    - `service-desk.js` + HTML テンプレート: 一覧テーブル、検索・フィルタ、SLA 時計カード、内部メモ/公開返信切り替え。
    - メニュー権限: `m_menu` / `t_role_menu`（管理者・営業・マネージャー）および `ActionPermissionResolver` 登録。
  - **Test Requirements**:
    - `ServiceRequestApiControllerTest`: 内部起票・更新・状態遷移・内部メモ投稿・DataScope 絞り込み検証。
  - **Demo**:
    - 内部画面から問い合わせを起票し、内部メモを投稿し、ステータス変更できることをテスト実行で実証。

- [x] **Task A2: 顧客ポータル起票・返信・CSAT 回答画面 & API**
  - **Objective**: 顧客ポータル利用者向けの問い合わせ一覧・起票・返信・CSAT 回答機能を実装する。
  - **Guidance**:
    - コントローラ: `PortalCustomerServiceDeskApiController` (`/api/portal/customer/requests/**`), `PortalCustomerPageController`.
    - 画面: `templates/portal/customer/requests.html`, `templates/portal/customer/request-detail.html`.
    - **必須条件**: ポータル DTO には `INTERNAL` コメント、原価、内部ユーザー ID を含めない。
    - CSAT 投稿: 解決済み（`RESOLVED` / `CLOSED`）リクエストに対して 1 回限り回答可能（DB UNIQUE 制約）。
    - 添付ファイルダウンロード: 自社スコープ検証。
  - **Test Requirements**:
    - `PortalCustomerServiceDeskApiControllerTest`: 自社リクエストの CRUD、他社 (Customer B) リクエスト・添付・CSAT へのアクセス拒否 (IDOR 拒否)、CSAT 二重回答拒否 (409 Conflict)。
  - **Demo**:
    - ポータルから起票した問い合わせが内部に届き、内部からの公開返信がポータルに表示され、解決後に CSAT 評価が 1 回送信できることを確認。

- [ ] **Task B1: SLA 監視スケジューラ & 重複抑止通知**
  - **Objective**: SLA 期限直前予告および超過を検出し、担当者へ重複抑止（Dedupe）通知を発行するスケジューラを実装する。
  - **Guidance**:
    - `ServiceSlaScheduler`: 定期的に進行中の SLA 時計を走査し、初回応答・解決期限の超過および予告を判定。
    - `NotificationService` / `t_notification` への通知登録。Dedupe キー: `sla:request:{id}:round:{round}:type:{type}`。
    - 重複実行防止・マルチノード安全設計。
  - **Test Requirements**:
    - `ServiceSlaSchedulerTest`: 期限前通知、超過通知、重複実行時の二重通知防止、Reopen 後の通知分離。
  - **Demo**:
    - SLA 超過データに対してスケジューラを実行し、通知が 1 件のみ生成され、再実行で重複しないことを確認。

- [ ] **Task B2: 顧客ヘルススコア算定 & 契約更新カレンダー連携 & 定例会(QBR) & CSV エクスポート**
  - **Objective**: ルールベースの顧客ヘルススコア算出、契約更新カレンダーへのヘルスバッジ表示、QBR 管理、CSV 出力を実装する。
  - **Guidance**:
    - `CustomerHealthCalculator` / `CustomerHealthService`: 未解決 P0/P1、SLA 違反、CSAT、AR 延滞からスコアと要因説明を算出。
    - 契約更新カレンダー (`/contracts/renewal-calendar`, `RenewalCalendarServiceImpl`): 顧客ヘルスと未解決件数を DTO に追加（契約状態は自動変更しない）。
    - QBR / Action: `CustomerQbrApiController`, `CustomerQbrService`.
    - CSV エクスポート: `ServiceRequestExportService` (CSV injection 対策、DataScope 準拠)。
  - **Test Requirements**:
    - `CustomerHealthCalculatorTest`: 各ファクターの加減点、欠損値ハンドリング、更新カレンダーの非破壊性検証。
    - `CustomerQbrApiControllerTest`, `ServiceRequestExportTest`.
  - **Demo**:
    - 顧客ヘルス画面で要因内訳が表示され、契約更新カレンダーに対象顧客のヘルスバッジが表示されることを確認。

- [ ] **Task M: 全体統合検証 & 回帰テスト & 390px モバイル UI 検証 & Runbook**
  - **Objective**: 全ゲート（Fast Suite, MySQL/Flyway, Security/IDOR, 390px モバイル表示, Runbook）を完了し、成果物を固定する。
  - **Guidance**:
    - `mvn test` (Fast suite) の全件 PASS。
    - 390px ポータル画面および内部画面のレイアウト崩れなし確認。
    - 多言語リソース（`messages.properties`, `messages_en.properties`, `messages_zh_CN.properties`, `messages_ko.properties`）の整合性。
    - `review-ledger.md` の記録および base/head SHA の固定。
  - **Test Requirements**:
    - `MessageBundleConsistencyTest`, `verify-like-ci` 相当のテスト実行。
  - **Demo**:
    - サービスデスク起票からSLA計時、ポータル返信、解決、CSAT回答、ヘルス反映、契約更新カレンダー表示までの一連のE2Eフローを実証。
