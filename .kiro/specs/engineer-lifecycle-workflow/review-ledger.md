# Review Ledger: Engineer Lifecycle Workflow (`engineer-lifecycle-workflow`)

## 1. Decision Gate & Review History

| Gate | Date | Reviewer | Result | Summary / Action Items |
|:---|:---|:---|:---|:---|
| **DG-01 (Plan & Architecture)** | 2026-08-27 | Independent Review | **FAIL -> REMEDIATION IN PROGRESS** | Stage A: Spec consistency, review ledger creation, scope reconciliation (IdP/contracts/billing). Stage B: P0 SoD/Gate fixes. |
| **DG-02 (Implementation Review)** | 2026-08-27 | Independent Review | **FAIL -> REMEDIATION IN PROGRESS** | LC-P0-01 (Approval bypass), LC-P0-02 (Resignation gate unlink & portal), LC-P0-03 (Missing gate task fail-closed), LC-P0-04 (Role resolver fail-closed). P1 UI & scope isolation. |

---

## 2. Findings Log & Remediation Status

| ID | Severity | Category | Requirement / Spec Ref | Finding Summary | Remediation Strategy | Status |
|:---|:---|:---|:---|:---|:---|:---|
| **LC-P0-01** | P0 | Security / SoD | R3.3, design §4 | 例外 WAIVE が ApprovalEngine と職務分離を迂回（直接免除・偽造ID許容） | `waiveTask` は `LifecycleExceptionApprovalAdapter.applyApproved` のみから実行可能とし、申請時に `remedy_deadline` / `risk_owner` を必須化。公開 waive API は ApprovalEngine 経由へ統一。 | **FIXED** |
| **LC-P0-02** | P0 | Security / Gate | R3.1 (1)(2)(3) | 退社自動ゲートが実行前PASSかつ unlink/portal 失効が欠落・例外握りつぶし | `ResignationGateChecker.evaluate` は実状態を検証。`completeCase` トランザクション内で `unlinkByEngineerId` と正しい `portalUserId` でのセッション失効を実行し、例外時はロールバック（Fail-Closed）。 | **FIXED** |
| **LC-P0-03** | P0 | Governance / Gate | R3.1 (6)(8), design §5 | 必須ゲートタスクコード欠落時に PASS 扱い | RESIGNATION 案件において指定タスクコード (`RESIGN_ASSET_RETURN`, `RESIGN_EXPENSE_SETTLE`, `RESIGN_DOC_RETENTION`) の存在と完了/承認済み免除を必須化。欠落時は Fail-Closed。 | **FIXED** |
| **LC-P0-04** | P0 | Workflow / Error Handling | R2.2, R2.3 | ROLE 担当ユーザー 0 人時に fail-open | 必須タスクの ROLE 解決で該当ロールのアクティブユーザーが 0 人の場合、`BusinessException` をスローして案件起票をロールバック。 | **FIXED** |
| **LC-P1-01** | P1 | Data Integrity | R2.1 | 同一要員の進行中 RESIGNATION 案件の重複作成ガードなし | `createCase` 時に同一要員の `ACTIVE` / `ON_HOLD` 退社案件の存在を検証し、重複起票を拒否。 | **FIXED** |
| **LC-P1-02** | P1 | Information Leak | R4.1 | 要員本人に内部タスク件数・進捗率が漏洩、直接 complete 試行で 403 (推測可能) | 本人向け案件進捗計算・タスク一覧から内部タスク (`is_engineer_visible = 0`) を完全に除外。非公開タスクへのアクセスは 403 ではなく 404 を返却。 | **FIXED** |
| **LC-P1-03** | P1 | Information Leak | R4.1 | 要員本人詳細 API に `engineerSnapshotJson` が返却される | 本人向け API (`/api/my/lifecycle/**`) では `engineerSnapshotJson` を null クリアして返却。 | **FIXED** |
| **LC-P1-04** | P1 | Compliance | R2.6 | `DOCUMENT_LINK` 証跡が必須タスクで未強制 | `DOCUMENT_LINK` の必須タスクでは `documentId` の入力を必須化し、台帳実体存在とアクセス権を検証。 | **FIXED** |
| **LC-P1-05** | P1 | State Machine | R2.5 | `reassignTask` に案件ステータス (ACTIVE) / スコープ検査なし | `reassignTask` 実行時に案件が `ACTIVE` であることおよび実行ユーザーのスコープを検証。 | **FIXED** |
| **LC-P1-06** | P1 | Security / Scope | R4.2 | `createCase` が対象要員の DataScope を検証していない | 起票時に `LifecycleScopeService` / `DataScopeService` による要員アクセス権限を検証。 | **FIXED** |
| **LC-P1-07** | P1 | UI / Navigation | A1, A2 | `sidebar.html` に `lifecycle` / `myLifecycle` が未配線 | `templates/layout/sidebar.html` に管理ロール向け「ライフサイクル管理」および要員向け「マイライフサイクル」メニューを配線。 | **FIXED** |
| **LC-P1-08** | P1 | Security / RBAC | R4.2 | V109 / H2 で `ENGINEER` に `lifecycle.*` が付与されていた | `ENGINEER` 権限グループから `lifecycle.*` を除外し、`my.*` のみに限定。 | **FIXED** |
| **LC-P1-09** | P1 | Governance / Gate | R3.1 (7) | 退社ゲートでの未完了契約・未請求/未収金の確認 | 退社ゲート検証項目に稼働中契約 (`t_contract`) の終了確認を追加。 | **FIXED** |
| **LC-P1-10** | P1 | Notifications | R4.3 | 案件完了/阻害通知の未配線、SLA 期日通知日数 | `completeCase` で `notifyCaseCompleted` を配線、SLA スケジューラで 3 日前・1 日前・当日の期日接近通知を発行。 | **FIXED** |
| **LC-P1-11** | P1 | State Machine | R2.5 | `cancelCase` で未完了タスクが `WAIVED` に変更される | 案件中止時はタスクステータスを `WAIVED` (承認免除) ではなく明確に区別し、gate 充足とみなさない。 | **FIXED** |
| **LC-P1-12** | P1 | Operations | B2 | 退社障害リカバリ手順・ランブックの不在 | `.kiro/specs/engineer-lifecycle-workflow/runbook.md` を作成し運用リカバリ手順を定義。 | **FIXED** |
| **LC-P1-13** | P1 | Git Hygiene | Task M | Task M コミットに無関係な browser 証跡が含まれた | 不要な証跡差分を `origin/main` からリバート。 | **FIXED** |
| **LC-P1-14** | P1 | Security / Scope | R4.2 | 営業ロールに内部セキュリティタスクが閲覧可能 | 営業ロール向けに内部タスクの閲覧境界を制御。 | **FIXED** |
| **LC-P1-15** | P1 | Audit / Integrity | R4.4 | 完了訂正 API の不在、イベント台帳のイミュータブル性 | 完了後の訂正イベント記録およびイベントテーブルの追記専用保護。 | **FIXED** |
| **LC-P1-16** | P1 | Workflow / DAG | R1.3 | 雇用形態フィルタ後のタスク群に対する DAG 検証 | `createCase` で雇用形態フィルタ適用後のタスク集合に対して DAG 整合性を検証。 | **FIXED** |

---

## 3. Spec & Design Document Synchronizations

1. **`requirements.md`**:
   - R3.3: 例外免除（`LIFECYCLE_EXCEPTION`）において是正期日（`remedy_deadline`）およびリスク所有者（`risk_owner`）の入力を必須化。即時免除の曖昧記述を削除。
   - R3.1: 退社ゲート 8 項目に稼働中契約終了確認およびポータル/セッションの Fail-Closed 処理を明記。
2. **`design.md`**:
   - §4: 例外承認フロー（ApprovalEngine 統一アダプタ経由のみ、公開直接免除 API 撤廃）。
   - §5: 退社ゲート評価（実状態検証 + 自動実行アクション + 完了後再検証）。
   - §6: 状態遷移（DRAFT / ACTIVE / ON_HOLD / COMPLETED / CANCELLED）。タスク状態（PENDING / IN_PROGRESS / COMPLETED / WAIVED）。案件中止時のタスク補償処理。
3. **`tasks.md`**:
   - P0/P1 修正タスクの反映とデモ・テスト要件の整合。
