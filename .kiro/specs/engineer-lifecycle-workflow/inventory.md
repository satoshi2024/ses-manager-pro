# 現行経路インベントリ台帳 — 要員ライフサイクルワークフロー (NF-01 / DG-01)

## 1. 概要と目的

本インベントリ台帳は、新機能 `engineer-lifecycle-workflow` (NF-01) の設計および実装に先立ち、システム内の要員、ユーザー、認証セッション、組織所属、営業担当、文書台帳、精算、資産候補等の既存エンティティおよびサービス経路を網羅的に調査・確定したものである。

---

## 2. 既存ドメインエンティティ・サービス経路一覧

| ドメイン領域 | 正本テーブル | 主要エンティティ / サービス | 現行の操作・更新経路 | ライフサイクルワークフローでの連携方式 |
|---|---|---|---|---|
| **要員マスタ** | `t_engineer` | `Engineer.java`, `EngineerService`, `EngineerStatusService` | CRUD, ステータス変更 (`稼動中`, `退場予定`, `Bench`, `提案中`), 組織・原価部門紐付け | 案件起票時のスナップショット取得、入社/異動/退社時のステータス更新・組織更新 |
| **システムユーザー** | `sys_user` | `SysUser.java`, `SysUserService`, `CustomUserDetailsService` | ユーザー作成・更新, パスワード変更, `status` (1: 有効, 0: 無効), アカウントロック | 入社時のアカウント発行確認、退社時の `status=0` 無効化およびロック確認 |
| **要員アカウント連携** | `t_engineer_account_link` | `EngineerAccountLink.java`, `EngineerAccountLinkService` | `link(engineerId, sysUserId, linkedBy)`, `unlinkByEngineerId(engineerId)` | 入社時のポータル連携、退社時の連携解除またはアカウント無効化検証 |
| **Webセッション** | `t_user_session` | `PersistentSession.java`, `PersistentSessionService` | `register`, `validateAndTouch`, `revokeAllForUser(userId, reason)` | 退社ゲートでの内部Webセッション全件強制無効化 |
| **ポータルセッション** | `t_portal_session` | `PortalSession.java`, `PortalSessionService` | `issue`, `resolve`, `revokeAllForUser(portalUserId, reason)` | 退社ゲートでの外部/要員ポータルセッション全件強制無効化 |
| **組織所属** | `t_user_organization`, `t_organization_unit` | `UserOrganization.java`, `OrganizationService`, `OrganizationScopeService` | `assignUser`, `transferUser`, `closeAssignmentsForUser(userId, releaseDate)` | 異動時の所属付替、退社時の有効所属全件自動閉鎖 |
| **要員担当営業** | `t_engineer_sales` | `EngineerSales.java`, `EngineerSalesService` | `assign`, `setPrimary`, `release(engineerId, assignmentId)` (履歴管理: `released_at`) | 配属/異動時の主担当営業割当、退社時の営業担当解除・引継ぎ |
| **法定文書・電子契約** | `t_document`, `t_document_version`, `t_document_link` | `Document.java`, `DocumentService`, `DocumentStorage` | `registerGenerated`, `registerReceived`, `addVersion`, `link(docId, targetType, targetId)` | タスク完了時の証跡リンク (`targetType='LIFECYCLE_CASE'`), 入退社書類の保管検証 |
| **ファイルスキャン** | (Document metadata) | `FileScanner.java`, `FileScopeValidationService` | `scan_status` (`CLEAN`, `QUARANTINE`, `INFECTED`) | 証跡文書アップロード時のクリーン状態検証 (fail-closed) |
| **経費・支払精算** | `t_expense_request` | `ExpenseRequest.java`, `ExpenseRequestService` | 申請・承認・会計連携・支払完了 (`DRAFT`, `REQUESTED`, `APPROVED`, `ACCOUNTING_SYNCED`, `PAID`) | 退社ゲートでの未精算経費（`REQUESTED`/`APPROVED`）残存チェック |
| **統一承認エンジン** | `t_approval_request`, `t_approval_action`, `m_approval_route` | `ApprovalEngineService`, `ApprovalTargetAdapter` | `request`, `approve`, `reject`, `returnForRevision`, `resubmit` | 完了阻害タスクの例外免除申請 (`RequestType = LIFECYCLE_EXCEPTION`) |
| **通知・Outbox** | `t_notification`, `t_notification_outbox` | `NotificationService`, `NotificationOutboxService` | `create` (dedupe_key), Webhook/メール非同期配信 | 期日リマインダー、期限超過エスカレーション、ブロッカー通知 |
| **貸与資産 (候補)** | (チェックリスト / 外部管理) | `m_lifecycle_template_task` 内の証跡/確認タスク | PC返却、携帯返却、入館証返却、SaaSアカウント削除確認 | NF-09実装前は証跡リンク・二者確認タスクとして追跡し、退社ゲートで完了を検証 |

---

## 3. DG-01 決定事項と解決表

| 決定論点 | 決定内容 | 根拠・理由 |
|---|---|---|
| **1. ライフサイクル対象者** | **社員（正社員、契約社員）および BP/フリーランス** を対象とする。 | SES事業では社員の入退社だけでなく、BP要員の入場・退場・現場交代も同様にアカウント・端末・法定文書・現場引継ぎの統制が必要であるため。テンプレート側の `target_employment_types` により形態別のタスク差分を吸収する。 |
| **2. 退社時の強制ブロック対象** | **内部ユーザー (`sys_user.status=0`), Webセッション (`revokeAllForUser`), ポータル連携, 組織所属 (`closeAssignmentsForUser`), 担当営業 (`EngineerSales`), 貸与資産返却, 未精算経費** を必須ゲートとする。 | 退社後の不正アクセス、情報漏洩、未返却資産の損失、組織・営業母集団のゴースト残存を構造的に防止するため。 |
| **3. タスク完了の証跡種別** | `NONE (証跡不要)`, `SELF_DECLARATION (自己申告)`, `DUAL_CONFIRMATION (二者確認)`, `DOCUMENT_LINK (文書台帳リンク)`, `SYSTEM_CHECK (システム自動検証)` の5区分とする。 | 操作の重要度・リスクに応じて柔軟かつ厳格な証跡を要求し、内部統制（J-SOX）に対応するため。 |
| **4. 既存ApprovalEngineとの境界** | **通常タスク完了は直接実行（CAS保護）、完了阻害タスクの例外免除（WAIVE）のみApprovalEngineを利用** する。 | 日常のチェックリスト完了に都度重厚な稟議を通すと運用が破綻する一方、セキュリティや未返却の免除は厳格な多段階承認とリスクオーナーが必要なため。 |

---

## 4. Migration & 並行禁止境界

- **直前最新Migration**: `V108_3__digital_invoice_send_unique.sql`
- **本機能Migration**: `V109__engineer_lifecycle_workflow.sql`
- **H2用スキーマ**: `src/test/resources/sql/schema-lifecycle-workflow-h2.sql`
- **V1同期**: `V1__create_tables.sql` にベースDDLを追加
- **並行禁止範囲**: `sys_user`, `t_engineer`, `t_document`, `t_approval_request` の既存カラム変更・削除の禁止
