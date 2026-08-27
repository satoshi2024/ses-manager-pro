# Implementation & Review Ledger — customer-success-service-desk (NF-02)

## 1. 概要・メタデータ

- **Feature ID**: NF-02
- **Feature Name**: `customer-success-service-desk`
- **Worktree Path**: `C:\work\ses-customer-success-service-desk`
- **Branch**: `codex/customer-success-service-desk`
- **Base Branch**: `origin/main`
- **Base Commit**: `bd2bfca6aecab365f4fbbf4916ddb4f393614d27`
- **Status**: `DISCOVERY` (Spec整備完了・Approval待ち)
- **Latest Migration Version**: V110
- **Decision Gate**: DG-02

---

## 2. Decision Gate (DG-02) 合意事項

1. **ポータル起票対象と利用者**:
   - 顧客組織に所属するポータル認証ユーザー (`PortalLoginUser`) が自社に紐づく問い合わせを起票・閲覧・返信。
2. **SLAの営業時間・休日・停止・Priority**:
   - 標準営業時間 09:00〜18:00、土日祝日 (`WorkCalendarDay`) を除外した厳密な計時。
   - `WAITING_CUSTOMER` 状態中は SLA 時計を一時停止（Pause）。再開時に延長。
   - 優先度 P0 (緊急: 応答1h/解決4h), P1 (高: 応答2h/解決8h), P2 (中: 応答4h/解決24h), P3 (低: 応答8h/解決48h)。
3. **Internal Note と顧客公開コメントの分離**:
   - `t_service_comment.visibility` (`INTERNAL` / `PORTAL_VISIBLE`)。
   - ポータル API および DTO から `INTERNAL` を完全に除外（サーバー側クエリおよびマッピングで保証）。
4. **Health Score の要因と更新判断**:
   - 100点満点減点モデル（未解決P0/P1、SLA違反、CSAT、AR延滞、定期接触）。
   - 説明可能な要因内訳と欠損入力を表示。契約更新ステータスを自動更新しない。

---

## 3. 現行境界インベントリ (Domain Inventory)

| ドメイン | 既存正本テーブル / Service | 本機能での接続・再利用 | 責務境界（禁止事項） |
|---|---|---|---|
| Customer & Contact | `m_customer`, `t_customer_contact`, `CustomerService`, `CustomerContactService` | リクエスト起票時の顧客・担当者参照 | 新規顧客マスタを作らない |
| Contract & Renewal | `t_contract`, `RenewalCalendarService`, `RenewalCalendarServiceImpl` | 契約更新カレンダーにヘルススコア・未解決P0/P1を表示 | 契約更新判定を自動上書きしない |
| External Portal | `m_portal_organization`, `t_portal_user`, `PortalAuthorizationService` | 顧客ポータルからの起票・返信・CSAT回答・自社スコープ検証 | 内部 `sys_user` 権限と混同しない |
| Business Calendar | `m_work_calendar`, `m_work_calendar_day` | SLA 営業時間・休日スキップ計算 | 24時間単純加算にしない |
| Document & File | `t_document`, `DocumentService`, `DocumentStorage`, `FileReferenceProvider`, `FileScopeValidationService` | リクエスト添付ファイル保存・ダウンロード | 独自ストレージパスや未保護ダウンロードを作らない |
| Notification | `t_notification`, `NotificationService`, `NotificationLinks` | SLA 超過・期限前アラート・ポータル起票通知（Dedupe付き） | 重複通知を発行しない |

---

## 4. Task 完了記録 (Task Execution & Verification Table)

| Task ID | 内容 | 対象ファイル | 定向テスト / 回帰テスト | Demo / 検証結果 | Commit SHA |
|---|---|---|---|---|---|
| Task 0 | Discovery & 現行境界インベントリ・DG-02 | `requirements.md`, `design.md`, `tasks.md`, `review-ledger.md` | `mvn test-compile` | Spec & Inventory 整備確認 (PASS) | 22d35cc3 |
| Task F1 | DDL & Entity 整備 (V110) | `V110__...sql`, `schema-service-desk-h2.sql`, Entity, Mapper | `ServiceDeskEntityMapperTest` | 10テーブル初期化・CRUD・初期ポリシー投入確認 (PASS) | 837c73b7 |
| Task F2 | SLA 計算エンジン & 状態機械 & スコープ | `ServiceSlaCalculator`, `ServiceRequestService`, DTO | `ServiceSlaCalculatorTest`, `ServiceRequestServiceImplTest` | 休日スキップ/Pause/Reopen/二重CSAT防止実証 (PASS) | in-progress |
| Task A1 | 内部サービスデスク画面 & API | `ServiceRequestApiController`, `service-desk.js`, HTML | `ServiceRequestApiControllerTest` | 内部起票・内部メモ実証 | Pending |
| Task A2 | ポータル起票・返信・CSAT 画面 & API | `PortalCustomerServiceDeskApiController`, Portal HTML | `PortalCustomerServiceDeskApiControllerTest` | IDOR 拒否・CSAT 1回実証 | Pending |
| Task B1 | SLA スケジューラ & Dedupe 通知 | `ServiceSlaScheduler`, `NotificationService` | `ServiceSlaSchedulerTest` | 重複抑止実証 | Pending |
| Task B2 | ヘルススコア & 更新カレンダー連携 & QBR & CSV | `CustomerHealthService`, `RenewalCalendarServiceImpl`, QBR, CSV | `CustomerHealthServiceTest`, `RenewalCalendarTest` | ヘルス要因・カレンダー連携実証 | Pending |
| Task M | 統合検証 & 390px モバイル & Runbook | Message bundles, Runbook | `mvn test`, `MessageBundleConsistencyTest` | 全ゲート合格 & モバイル実証 | Pending |

---

## 5. リスク・ロールバック・運用 Runbook

- **Migration ロールバック方針**:
  - V110 で追加されるテーブル (`m_service_sla_policy`, `t_service_request`, `t_service_sla_clock`, `t_service_comment`, `t_service_attachment_link`, `t_service_state_event`, `t_customer_csat`, `t_customer_qbr`, `t_customer_qbr_action`, `t_customer_health_snapshot`) は新規独立テーブルであるため、ロールバック時は当該テーブル群を DROP することで既存機能に影響を与えずに復元可能。
- **データ分離リスク**:
  - ポータルへの `INTERNAL` コメント露出を防止するため、単体テストおよび API テストで DTO allow-list とクエリ条件を二重に検証。
