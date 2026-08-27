# Requirements — 要員ライフサイクルワークフロー (入社・配属・異動・休職・復職・退社)

## 前提・背景

SES事業において、要員の入社、案件配属、部署/拠点異動、休職、復職、および退社（離職・契約終了）は、アカウント発行/失効、端末貸与/返却、担当営業/組織の割当/解除、法定文書締結/保存、教育研修、各種精算など多岐にわたる部門横断タスクを伴う。
従来の手作業チェックリスト運用では、退社後のアカウントやセッションの残存、端末未返却、担当引継ぎ漏れ、未精算の放置などの内部統制・セキュリティリスクが存在していた。
本機能（`engineer-lifecycle-workflow` / NF-01）は、ライフサイクルイベントを追跡可能な「案件（Case）」と「タスク（Task）」として体系化し、担当自動解決、期限管理、証跡保存、厳格な退社ゲート制御、および例外承認ワークフローを提供する。

---

## R1. ライフサイクルテンプレート (Templates)

1. THE 管理者/HR SHALL ライフサイクル種別（`入社 (JOIN)`, `配属 (ASSIGNMENT)`, `異動 (TRANSFER)`, `休職 (LEAVE)`, `復職 (REINSTATEMENT)`, `退社 (RESIGNATION)`）ごとに、版番号（`version_no`）と有効期間（`valid_from`, `valid_to`）を持つテンプレートを作成・改定できる。
2. THE テンプレート SHALL 複数のタスク定義を持ち、各タスク定義はタスクコード、タスク名、説明、基準日からの相対期限日数（`relative_due_days`）、担当解決ルール（`assignee_rule`）、必須区分（`is_mandatory`）、完了阻害区分（`is_blocking`）、証跡種別（`evidence_type`）、本人公開区分（`is_engineer_visible`）、および対象雇用形態（`target_employment_types`）を保持する。
3. THE テンプレート SHALL タスク間の先行/後続依存関係（DAG: 有向非巡回グラフ）を定義できる。
4. WHEN 進行中の案件（Active Case）が存在する状態でテンプレートが改定された場合、THE システム SHALL 既存案件を暗黙更新せず、改定後の新版は改定以降に起票される後続案件へ適用する（版の不変性・snapshot維持）。

---

## R2. 案件起票とタスク実行 (Case Generation & Execution)

1. WHEN ライフサイクル案件を起票する場合、THE システム SHALL 要員スナップショット（氏名、雇用形態、所属組織、主担当営業、ログインアカウント等）および起票時点の有効テンプレート版から、案件（Case）およびタスク（Task）インスタンスを原子的一括（単一DBトランザクション）で生成する。
2. THE タスク担当者（Assignee） SHALL テンプレート定義の解決ルール（`SPECIFIC_USER: 指定ユーザー`, `ROLE: ロール`, `ORGANIZATION_MANAGER: 所属組織責任者`, `PRIMARY_SALES: 主担当営業`, `ENGINEER_SELF: 要員本人`, `APPLICANT: 申請起票者`）に基づき、起票時点の事実から一意に解決されスナップショットとして固定される。
3. WHEN 担当者解決不能（該当者不在）またはタスク依存関係の循環（Cyclic Dependency）が検出された場合、THE システム SHALL 案件起票を直ちに拒否（Fail-Closed）し、中途半端な部分タスク行を残さず全件ロールバックする。
4. THE 案件状態 SHALL `DRAFT (下書き)` → `ACTIVE (進行中)` → `COMPLETED (完了)` を基本とし、例外時に `ON_HOLD (保留)` または `CANCELLED (取消)` を持つ。
5. THE タスク状態 SHALL `PENDING (先行タスク待ち)` → `IN_PROGRESS (着手可能/進行中)` → `COMPLETED (完了)` を基本とし、例外時に `ON_HOLD (保留)` または `WAIVED (例外免除)` を持つ。
6. THE タスク完了 SHALL 実行者（`completed_by`）、完了日時（`completed_at`）、コメント（`completion_comment`）、証跡種別、および証跡データ（文書台帳リンクまたはメタデータJSON）を永続化する。完了済みタスクの原地直接編集は禁止し、訂正は新イベント（`t_lifecycle_event`）として追記記録する。

---

## R3. 退社ゲート (Resignation Gate) と 例外承認 (Exception Approval)

1. THE 退社案件（`RESIGNATION`） SHALL 以下の必須統制項目をゲート検証タスクまたはシステム検証として包含する:
   - (1) 内部ユーザーアカウント無効化（`sys_user.status = 0` / ロック）
   - (2) 有効Webセッションの即時失効（`PersistentSessionService` および `PortalSessionService` による全セッション強制revocation）
   - (3) 要員ポータル連携の解除または無効化（`t_engineer_account_link` 解除）
   - (4) 担当営業の割当解除または主担当引継ぎ（`EngineerSalesService`）
   - (5) 組織所属の終了（`OrganizationService.closeAssignmentsForUser` による有効所属の閉鎖）
   - (6) 貸与資産の返却確認（PC、スマートフォン、入館証、セキュリティキー等の返却証跡）
   - (7) 未精算経費・未完了請求の確認
   - (8) 法定文書・電子契約原本の保管確認
2. WHEN 案件内に未完了の完了阻害タスク（`is_blocking = 1` かつ status ≠ `COMPLETED` / `WAIVED`）が存在する場合、THE システム SHALL 案件の `COMPLETED` 遷移を確実に拒否（Block）する。
3. WHEN 業務上不可避な理由で阻害タスクを免除・保留して案件を完了させる場合、THE システム SHALL 申請者単独での完了を禁止し、既存の統一承認エンジン（`ApprovalEngineService` / `RequestType = LIFECYCLE_EXCEPTION`）へ例外申請を要求する。例外申請には免除理由、是正期限（`remedy_deadline`）、およびリスク所有者（`risk_owner_user_id`）を必須とし、承認完了後にのみ該当タスクを `WAIVED` へ遷移させて案件完了を可能とする。

---

## R4. 認可スコープ・本人ポータル・通知・監査

1. THE 要員本人 SHALL `/my/lifecycle` および `/api/my/lifecycle/**` 画面において、自分を対象とする案件のうち `is_engineer_visible = 1`（本人公開可）のタスクのみを閲覧・自己完了操作でき、内部統制・セキュリティ・人事評価に関わる内部タスク（`is_engineer_visible = 0`）を閲覧・推測できない。
2. THE 管理側利用者（管理者、HR、マネージャー、営業） SHALL 自身の認可スコープ（管理者は全件、HRは全件、マネージャーは管轄組織配下、営業は担当要員）に該当する案件・タスクのみを閲覧・更新できる。スコープ外のID指定に対しては存在を推測させない拒否（404 または一貫したエラー）を返す。
3. THE システム SHALL タスク着手可能化、期限接近（3日前/前日）、期限超過（当日/超過継続）、阻害発生、および案件完了を、重複抑止キー（Deduplication Key）を用いて対象者および上位者へ通知する。
4. THE システム SHALL 案件およびタスクの全ライフサイクル状態遷移、担当変更、例外申請/承認、およびシステム自動実行結果を、改ざん不能な追記専用イベント台帳（`t_lifecycle_event`）へ記録する。

---

## R5. 非機能要件・受入基準

1. **認可母集団の一貫性**: 一覧、詳細、集計、エクスポート、通知、およびスケジューラバッチにおいて同一のスコープ判定ロジック（`LifecycleScopeService`）を適用する。
2. **並行実行保護と冪等性**: タスク完了および案件状態遷移は版番号楽観ロック（CAS）により保護し、二重クリックや並行リクエストによる二重完了・状態不整合を防止する。
3. **完全性とロールバック**: 循環依存または担当解決不能なテンプレートからの起票試行は即座に失敗し、DBを元の状態に保つ。
4. **テスト完全性**: Fast test（H2）、MySQL実コンテナ（Flyway smoke）、および390pxモバイルビューポートを含む全テストゲートでスキップ0件を達成する。
