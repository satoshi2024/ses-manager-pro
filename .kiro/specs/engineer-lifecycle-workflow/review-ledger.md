# Review Ledger: Engineer Lifecycle Workflow (`engineer-lifecycle-workflow`)

## 1. Decision Gate & Review History

| Gate | Date | Reviewer | Result | Summary / Action Items |
|:---|:---|:---|:---|:---|
| **DG-01 (Plan & Architecture)** | 2026-08-27 | Independent Review | **PASS** | Stage A: Spec consistency (R3.1 / design §5 9-item alignment, Case ACTIVE, Task CANCELLED, execute-on-complete separation). Roadmap status updated to PASS. |
| **DG-02 (Implementation Review)** | 2026-08-27 | Independent Review | **PASS** | Stage B: All findings (LC-P0-01~04, LC-P1-01~18) verified fixed on Head `f36078a0fd9fa7a337f2182cb13b955e1a6681b2`. Targeted tests 32 passed, Fast suite 2,693 passed skip 0. PR #85 updated. |

**Pull Request**: https://github.com/satoshi2024/ses-manager-pro/pull/85 (Reviewed Head: `f36078a0fd9fa7a337f2182cb13b955e1a6681b2`)

---

## 2. Findings Log & Remediation Status

| ID | Severity | Category | Requirement / Spec Ref | Finding Summary | Remediation Strategy | Status |
|:---|:---|:---|:---|:---|:---|:---|
| **LC-P0-01** | P0 | Security / SoD | R3.3, design §4 | 例外 WAIVE が ApprovalEngine と職務分離を迂回（直接免除・偽造ID許容） | `waiveTask` は `LifecycleExceptionApprovalAdapter.applyApproved` のみから実行可能とし、申請時に `remedy_deadline` / `risk_owner` を必須化。公開 waive API は ApprovalEngine 経由へ統一。 | **CLOSED** |
| **LC-P0-02** | P0 | Security / Gate | R3.1 (1)(2)(3), design §5 | 退社自動ゲートが実行前PASSかつ unlink/portal 失効が欠落・例外握りつぶし。 | `completeCase` トランザクション内で `executeAutomaticGateActions` により Fail-Closed で unlink / アカウント無効化 / セッション失効 / 営業解除 / 組織閉鎖を実行。ポータルlookup の `try/catch` 握りつぶしを廃止。`evaluate()` は実状態（アカウント未連携、営業割当有無等）を判定してレポート。 | **CLOSED** |
| **LC-P0-03** | P0 | Governance / Gate | R3.1 (6)(8), design §5 | 必須ゲートタスクコード欠落時に PASS 扱い | RESIGNATION 案件において指定タスクコード (`RESIGN_ASSET_RETURN`, `RESIGN_EXPENSE_SETTLE`, `RESIGN_DOC_RETENTION`) の存在と完了/承認済み免除を必須化。欠落時は Fail-Closed。 | **CLOSED** |
| **LC-P0-04** | P0 | Workflow / Error Handling | R2.2, R2.3 | ROLE 担当ユーザー 0 人時に fail-open | 必須タスクの ROLE 解決で該当ロールのアクティブユーザーが 0 人の場合、`BusinessException` をスローして案件起票をロールバック。 | **CLOSED** |
| **LC-P1-01** | P1 | Data Integrity | R2.1 | 同一要員の進行中 RESIGNATION 案件の重複作成ガードなし | `createCase` 時に同一要員の `ACTIVE` / `ON_HOLD` 退社案件の存在を検証し、重複起票を拒否。 | **CLOSED** |
| **LC-P1-02** | P1 | Information Leak | R4.1 | 要員本人に内部タスク件数・進捗率が漏洩、直接 complete 試行で 403 (推測可能) | 本人向け案件進捗計算・タスク一覧から内部タスク (`is_engineer_visible = 0`) を完全に除外。非公開タスクへのアクセスは 403 ではなく 404 を返却。 | **CLOSED** |
| **LC-P1-03** | P1 | Information Leak | R4.1 | 要員本人詳細 API に `engineerSnapshotJson` が返却される | 本人向け API (`/api/my/lifecycle/**`) では `engineerSnapshotJson` を null クリアして返却。 | **CLOSED** |
| **LC-P1-04** | P1 | Compliance | R2.6 | `DOCUMENT_LINK` 証跡が必須タスクで未強制 | `DOCUMENT_LINK` の必須タスクでは `documentId` の入力を必須化し、台帳実体存在とアクセス権を検証。 | **CLOSED** |
| **LC-P1-05** | P1 | State Machine | R2.5 | `reassignTask` に案件ステータス (ACTIVE) / スコープ検査なし。COMPLETED/CANCELLED案件でも担当変更可能。 | `reassignTask` 実行時に `caseMapper.selectById` で案件が `ACTIVE` であることを確認（失敗時は400）。`scopeService.assertCanEditTask` による操作者スコープ検証を追加。 | **CLOSED** |
| **LC-P1-06** | P1 | Security / Scope | R4.2 | `createCase` が対象要員の DataScope を検証していない | 起票時に `LifecycleScopeService.assertCanAccessEngineer` による要員アクセス権限を検証。 | **CLOSED** |
| **LC-P1-07** | P1 | UI / Navigation | A1, A2 | `sidebar.html` に `lifecycle` / `myLifecycle` が未配線 | `templates/layout/sidebar.html` に管理ロール向け「ライフサイクル管理」および要員向け「マイライフサイクル」メニューを配線。 | **CLOSED** |
| **LC-P1-08** | P1 | Security / RBAC | R4.2 | V109 / H2 で `ENGINEER` に `lifecycle.*` が付与されていた | `ENGINEER` 権限グループから `lifecycle.*` を除外し、`my.*` のみに限定。 | **CLOSED** |
| **LC-P1-09** | P1 | Governance / Gate | R3.1 (9) | 退社ゲートでの稼働中契約残存チェック未実装。`ContractMapper` が注入されているだけで未使用。 | `ResignationGateChecker.evaluate` に `ACTIVE_CONTRACT` ゲート項目を追加。`contractMapper.selectCount` で `engineer_id` かつ `status='稼動中'` の件数を確認し、残存時はゲートをFAIL。 | **CLOSED** |
| **LC-P1-10** | P1 | Notifications | R4.3 | 案件完了/阻害通知の未配線、SLA 期日通知日数 | `completeCase` で `notifyCaseCompleted` を配線、SLA スケジューラで 3 日前・1 日前・当日の期日接近通知を発行。 | **CLOSED** |
| **LC-P1-11** | P1 | State Machine | R2.5 | `cancelCase` で未完了タスクが `WAIVED` に変更される | 案件中止時はタスクステータスを `WAIVED` (承認免除) ではなく `CANCELLED`（補償遷移）に変更。gate 充足とみなさない。 | **CLOSED** |
| **LC-P1-12** | P1 | Operations | B2 | 退社障害リカバリ手順・ランブックの不在 | `.kiro/specs/engineer-lifecycle-workflow/runbook.md` を作成し運用リカバリ手順を定義。 | **CLOSED** |
| **LC-P1-13** | P1 | Git Hygiene | Task M | Task M コミットに無関係な browser 証跡（会計連携・派遣コンプライアンス・受注ワークフロー evidence）が含まれた。 | `git checkout origin/main -- evidence/` で全ファイルを origin/main 版へ完全復元。 | **CLOSED** |
| **LC-P1-14** | P1 | Security / Scope | R4.2, design 表2 | 営業ロールに内部セキュリティタスクが閲覧・編集可能。本番解決データ（`assigneeRole = "営業"`）で表2が動作していなかった。 | `isTaskVisibleToUser` を本番データ（`"営業".equals(assigneeRole)` または担当者が自分自身）に対応。`assertCanEditTask` に `isTaskVisibleToUser` 検証を追加し、非公開HRタスクへの操作を 404 で完全遮断。実起票データによる Drill M-7 で検証。 | **CLOSED** |
| **LC-P1-15** | P1 | Audit / Integrity | R4.4 | 完了訂正 API の不在。`t_lifecycle_event` が通常 `BaseMapper`（UPDATE/DELETE可能）でイミュータブル性が無保証。 | `LifecycleEventMapper` に `deleteById`/`deleteBatchIds`/`deleteByMap`/`delete`/`updateById`/`update` のオーバーライドを追加し `UnsupportedOperationException` をスロー（イミュータブル保護）。完了訂正API `POST /api/lifecycle/tasks/{id}/correct` を追加。 | **CLOSED** |
| **LC-P1-16** | P1 | Workflow / DAG | R1.3 | 雇用形態フィルタ後のタスク群に対する DAG 検証がなされていなかった | `createCase` で雇用形態フィルタ適用後のタスク集合に対して DAG 整合性検証を実施。 | **CLOSED** |
| **LC-P1-17** | P1 | Process / Traceability | review-ledger.md | 独立Review指摘: 台帳と実装・設計事実の乖離。 | 各指摘の是正コード・ドキュメント変更と台帳記録を同一コミットで整合。未決定のDG-01はCANDIDATEに維持後、独立Review PASSにて承認確定。 | **CLOSED** |
| **LC-P1-18** | P1 | Security / Authorization | R4.4 | `correctCompletedTask` に認可・スコープ検査が欠落。 | `correctCompletedTask` に `lcCase` 存在・CANCELLED拒否検査、`engineer` 取得、および `scopeService.assertCanEditTask` による認可スコープ検査を追加。Drill M-6 で検証。 | **CLOSED** |

---

## 3. P2 Follow-up Items (Non-blocking)

1. `DOCUMENT_LINK`: `documentId` 実体存在確認のみ実装（詳細文書ACL検証は今後拡張可能）。
2. `remedyDeadline`: 非空必須チェック実装済み（将来日付パースの厳密検証は今後追加可能）。
3. `notifyCaseBlocked`: 阻害タスク発生時通知の追加配線。
4. H2 `schema-lifecycle-workflow-h2.sql`: テーブル名 `t_lifecycle_template`（本番 `m_`）の完全同期。

---

## 4. Spec & Design Document Synchronizations

1. **`requirements.md`**:
   - R2.4: `DRAFT` 状態を削除（`createCase` は即 `ACTIVE`）。
   - R2.5: タスク `CANCELLED` 状態を追加（案件取消時の補償遷移）。
   - R3.1: 退社ゲート 9 項目（自動実行 #1〜#5、前提条件 #6〜#9: 稼働中契約終了確認含む）を明記。
   - R3.3: 例外免除において `remedy_deadline` / `risk_owner` を必須化。
2. **`design.md`**:
   - §4: 例外承認フロー（ApprovalEngine 統一アダプタ経由のみ）。
   - §5: 退社ゲート表を 9 項目に整合。前提条件検査（#6〜#9）と完了時自動実行アクション（#1〜#5）の責務分離を明記。
   - 表2（§6）: 営業ロールは `is_engineer_visible=0` の内部タスクのうち営業関係（`assigneeRole="営業"`）のみ可視、HR機密はマスク・編集遮断（`LifecycleScopeService` 実装済み）。
   - 表3（§6）: Case `DRAFT` 行削除（即 `ACTIVE`）。Task `CANCELLED` 行追加。訂正は `TASK_CORRECTION` イベント追記と明記。
3. **`tasks.md`**: P0/P1 修正タスクの反映とデモ・テスト要件の整合。