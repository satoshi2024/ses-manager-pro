# 運用手順書 (Runbook) : ライフサイクルワークフロー & 退社統制ゲート

## 1. 概要とアーキテクチャ概要

本手順書は、SES Manager Pro における要員ライフサイクルワークフロー（入社・配属・異動・休職・復職・退社）および退社統制ゲート（Resignation Gate）の運用、障害発生時の切り分け、およびリカバリ手順を定義する。

---

## 2. 退社統制ゲート（9項目）の運用基準

退社案件（`RESIGNATION`）の完了確定（`POST /api/lifecycle/cases/{id}/complete`）は、以下の9項目がすべて充足されている場合にのみ実行可能となる。

| No | 項目コード | 項目名 | 自動/手動 | 判定基準・アクション |
|:---|:---|:---|:---|:---|
| 1 | `USER_DEACTIVATION` | ログインアカウント無効化 | 自動実行 | 案件完了時に `sys_user.status = 0` に自動更新。アカウント未連携の場合は対象なし（自動PASS）。 |
| 2 | `SESSION_REVOCATION` | 全セッション強制失効 | 自動実行 | `PersistentSessionService` および `PortalSessionService` による全セッション即時破棄（Fail-Closed）。 |
| 3 | `PORTAL_UNLINK` | 要員ポータル連携解除 | 自動実行 | `EngineerAccountLinkService.unlinkByEngineerId` による連携解除。ポータル連携なしの場合は対象なし（自動PASS）。 |
| 4 | `SALES_RELEASE` | 担当営業割当の解除 | 自動実行 | `EngineerSales` の全アクティブ割当の `released_at` に基準日を設定。割当なしの場合は対象なし（自動PASS）。 |
| 5 | `ORG_ASSIGNMENT_CLOSE` | 組織所属の終了 | 自動実行 | `OrganizationService.closeAssignmentsForUser` による組織配属終了。 |
| 6 | `ASSET_RETURN` | 貸与資産の返却 | 手動タスク | `RESIGN_ASSET_RETURN` タスクが `COMPLETED` または承認済み `WAIVED`。タスク未定義の場合はゲートFAIL。 |
| 7 | `UNSETTLED_EXPENSE` | 立替経費・精算確認 | 手動タスク | `t_expense_request` で対象要員の未精算件数を確認。未精算0件 → 自動PASS。未精算あり → `RESIGN_EXPENSE_SETTLE` タスクが承認済み `WAIVED` でなければFAIL。 |
| 8 | `DOCUMENT_RETENTION` | 退職関連文書の保管確認 | 手動タスク | `RESIGN_DOC_RETENTION` タスクが `COMPLETED` または承認済み `WAIVED`。タスク未定義の場合はゲートFAIL。 |
| 9 | `ACTIVE_CONTRACT` | 稼働中契約の終了確認 | 手動確認 | `t_contract` で対象要員の `status='稼動中'` の件数を確認。残存している場合はゲートFAIL（契約を終了または解約してから退社を完了）。 |

---

## 3. 例外免除フロー（ApprovalEngine 連携）

業務上のやむを得ない理由（例: PCの郵送返却待ち、役員特例承認など）により阻害タスクを一時的に免除する場合：

1. **申請手順**:
   - 画面または API より例外申請（`RequestType = "LIFECYCLE_EXCEPTION"`）を起票。
   - **必須入力項目**:
     - `reason`: 免除理由（詳細）
     - `remedy_deadline`: 是正完了期限（将来日付）
     - `risk_owner`: リスク所有者・承認責任者（氏名/役職）
2. **承認権限**:
   - HR責任者または役員ロールによる多段承認。
3. **自動反映**:
   - 承認完了時、`LifecycleExceptionApprovalAdapter.applyApproved` が自動呼出され、該当タスクが `WAIVED` に遷移し `approval_request_id` が記録される。
   - ※ 直接 API による WAIVE はシステム的に遮断（SoD 担保）。

---

## 4. 障害時の切り分けとリカバリ手順

### 4.1 案件完了時の 400 Bad Request（ゲート未充足）
- **原因**: 必須タスクの未完了、未精算経費の存在、または稼働中契約の残存。
- **対応**:
  1. `/lifecycle/{id}` の退社ゲートチェックリストを確認。
  2. 警告が出ている手動タスク（資産返却・経費精算・書類保管）の進捗を確認し、完了報告または例外承認申請を実施。

### 4.2 案件完了時の 409 Conflict（楽観ロック競合）
- **原因**: 他の管理者が同時にタスク進捗や案件状態を更新した。
- **対応**:
  1. 画面を再読み込み（F5）し、最新状態を取得。
  2. ゲート項目を再確認した上で、再度「完了確定」ボタンを押下。

### 4.3 自動クリーンアップ処理の例外発生（Fail-Closed）
- **動作**: セッション失効や組織閉鎖で予期せぬエラーが発生した場合、トランザクション全体がロールバックされ、案件は `ACTIVE` のまま維持される（不整合防止）。
- **対応**:
  1. 監査ログ（`/audit-log`）およびシステムエラーログを確認。
  2. 関連するユーザーアカウントやポータルユーザーの整合性を修復後、再度完了確定を実行。
