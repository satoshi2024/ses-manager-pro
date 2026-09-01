# 受入後機能候補 — 対応表・決定台帳

- 親文書: `2026-08-27-post-acceptance-feature-backlog.md`
- 状態: 候補。各Decisionが`APPROVED`になるまでproduction変更を開始しない。

## 1. Status定義

| Status | 意味 |
|---|---|
| CANDIDATE | 候補。調査、概算、read-only spikeのみ可 |
| DISCOVERY | 現行inventory、利用者ヒアリング、KPI baselineを収集中 |
| DECISION_REQUIRED | scope、法務、security、外部契約等の判断待ち |
| APPROVED | requirements/design/tasksとowner、予算、開始条件を承認済み |
| IMPLEMENTING | 開工対話で実装中 |
| REVIEWING | 独立Review中。新機能の追加変更は禁止し、指摘修正だけ行う |
| CONDITIONAL_PASS | codeは合格だが、本番gateまたは外部環境証拠が未完 |
| PASS | Reviewとrelease gate完了 |
| DEFERRED | 理由、再開条件、再評価日を記録して延期 |
| REJECTED | 採用しない。理由と代替を記録 |

## 2. マスター台帳

| ID | feature-name候補 | 現在Status | Owner | 主要KPI | 主依存 | Decision/理由 | 再評価日 |
|---|---|---|---|---|---|---|---|
| NF-01 | `engineer-lifecycle-workflow` | PASS | Codex | 退社後access残存0件、期限超過率低減 | identity、organization、document、approval | 独立Review PASS (Stage A/B 合格)。PR #85 更新済み。要員の入社・配属・異動・休職・復職・退社ワークフロー、退社ゲート9項目、SoD例外承認確立 | 2026-08-27 |
| NF-02 | `customer-success-service-desk` | CANDIDATE | 未定 | SLA、CSAT、更新率 | customer contact、portal、renewal、notification | 未決定 | 未定 |
| NF-03 | `certification-learning-skill-gap` | APPROVED | `PROJECT_OWNER` | 資格期限、skill不足、研修成果 | engineer skill、staffing、approval、document、NF-01 lifecycle | `DG-03-SCOPE-APPROVAL-20260828-01`（2026-08-28）。Base `origin/main@76e45340`。経費締めA、PII AES-256-GCM、as-of event、AI候補のみ。詳細: `.kiro/specs/certification-learning-skill-gap/approval-decision.md` | 実装・独立Review完了後 |
| NF-04 | `mobile-pwa-self-service` | APPROVED | 管理者（プロジェクト責任者） | mobile完了率、二重登録0 | `/my/**`、attendance、expense、notification | 2026-08-28承認。Base=`origin/main@455fc92e3aa259d2a93f25c6a545ca6c6af835bc`、branch=`codex/mobile-pwa-self-service`、worktree=`C:\\work\\ses-mobile-pwa-self-service`。Chrome/Edge/Safari現行版・直前版、Android Chrome/iOS Safariを対象。install任意、pushなし。承認済みoffline/cache、idempotency、version/CAS、logout/user switch、30日保持、409差分UXをNF-04専用specへ固定する | 2026-08-28 |
| NF-05 | `integration-hub-public-api` | APPROVED | `PROJECT_OWNER` | API成功率、DLQ滞留、p95、rate境界 | identity、outbox、audit、data scope | DG-05-F1-APPROVAL-20260830-01およびDG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02（2026-08-30）。OwnerRef=PROJECT_OWNER、Base=origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd。F1/F2 PLAN/IMPLEMENTATION PASS、A1 IMPLEMENTATION PASS（fixed Head 69f857d3ac7d513b66265b02871688b28d2e7e5d、P0/P1/P2=0/0/0）。B1初回Review FAIL（fixed Head 0f1a92974ea914d16de07ccf5a586fac215283f0、P0/P1/P2=0/4/1）を`30199db8`でremediateし、再Review fixed Head 29d749bbのP1-006/P1-007を`2684ff8f`でremediateした。P1-007追加remediation後のNF05-IMPL-B1-008（初回送信前primary binding）をcode `c2cbfb99133d0df3f8d5eee285be340163747e31`で対応し、独立再Review待ち。B2/M APPROVED_SEQUENCED、A2 NOT_APPLICABLE_UNDER_CURRENT_DECISION。production enablement、実顧客credential、実provider送信、PR/mergeは禁止。詳細: .kiro/specs/integration-hub-public-api/approval-decision.md | B1再Review後にB2→Mを順次実装・独立Review |
| NF-06 | `data-migration-import-center` | CANDIDATE | 未定 | reconciliation差異0 | customer/project/contract、CSV、document | 未決定 | 未定 |
| NF-07 | `privacy-retention-dsar` | CANDIDATE | 未定 | retention未設定0、誤削除0 | document retention、audit、AI allow-list、全migration/entity/provider coverage | 承認済みscope/Privacy owner/Base branch/SHAのdecision evidence未提供。DG-07、外部専門家、社内責任者、backup/recovery、identity、recruiting、AI G10 gate未完。0/D0（inventory/no-write dry-run/spec）のみ許可し、F1-M/処分/外部provider/PRは停止。Review verdictは実装branchに記録せず、外部Review証跡でbindする | 承認証跡受領後 |
| NF-08 | `ai-management-copilot` | CANDIDATE | 未定 | 根拠link率、scope漏えい0 | AI gateway、全集計service、NF-07 | 未決定 | 未定 |
| NF-09 | `asset-account-license-lifecycle` | APPROVED | `PROJECT_OWNER` | 未返却0件、外部account残存0件、ライセンス席数超過0件 | NF-01、identity、document | `DG-09-SCOPE-APPROVAL-20260828-01`（2026-08-28）。Base `origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd`。branch `codex/asset-account-license-lifecycle`。期間重複代数排他、秘密非保存、席数CAS、不変イベント台帳、NF-01退社ゲート（`RESIGN_ASSET_RETURN`/`LIFECYCLE_EXCEPTION`）連携、自己完結型プロバイダ境界を承認。詳細: `.kiro/specs/asset-account-license-lifecycle/` | 実装・独立Review完了後 |
| NF-10 | `scheduled-management-reporting` | APPROVED | 管理者（経営管理責任者） | 作成時間、配布失敗率、scope外配布0件、snapshot不変性 | dashboard、document、notification、NF-02（ServiceDesk sectionはNF-02 PASSまで対象外） | 2026-08-28承認。管理者/マネージャーを利用者とし、月次report、Asia/Tokyo、7年保持、immutable snapshot、outbox経由のアプリ内通知＋期限付きlink、recipient preview・生成時/取得時scope検証を確定 | 実装・独立Review完了後 |

## 3. 要件→既存資産→追加境界

| ID | 再利用する正本 | 新しく所有するもの | 所有してはいけないもの |
|---|---|---|---|
| NF-01 | `Engineer`、`SysUser`、組織履歴、承認、文書、通知 | lifecycle case/template/task/evidence link | password、IdP account実体、給与計算 |
| NF-02 | Customer/Contact、Contract、portal、renewal、notification | service request/comment/SLA/CSAT/health factor | 新Customer master、新portal認証、法的自動判定 |
| NF-03 | EngineerSkill/Career、staffing demand、approval、document | certificate/course/learning plan/gap snapshot | 新要員master、AI自動評価確定 |
| NF-04 | `/my/**` API、attendance/expense/change request | PWA shell、draft/queue、request idempotency | 勤怠計算、独自認証、PII offline cache |
| NF-05 | identity、permission、data scope、outbox、audit | API client/scope/version/delivery/inbound event | internal entity公開、第二outbox |
| NF-06 | 個別CSV service、各domain service、audit | import job/mapping/row error/checkpoint/reconciliation | domain validation複製、直接mapper bypass |
| NF-07 | document retention、audit、AI allow-list | PII catalog/policy/hold/request/disposition job | 法的結論、audit/法定原本の無条件削除 |
| NF-08 | Dashboard/forecast/accounting等のservice、AI gateway | semantic catalog/query run/answer citation | 任意SQL、scope bypass、自動業務更新 |
| NF-09 | Engineer/Organization、identity参照、document | asset/account reference/assignment/inventory | password/token、MDMそのもの |
| NF-10 | 各集計service、DocumentService、notification/outbox | report template/run/snapshot/delivery | 集計式の複製、session依存scheduler |

## 4. Decision Gate

### DG-01 NF-01
- lifecycle対象者: 社員（正社員、契約社員）および BP/フリーランスを対象とする（テンプレートの target_employment_types でタスク差分を吸収）。
- 退社時の強制block対象: 内部ユーザー (sys_user.status=0)、Webセッション (revokeAllForUser)、ポータル連携解除、組織所属閉鎖 (closeAssignmentsForUser)、担当営業解除 (EngineerSales)、貸与資産返却、未精算経費。
- Task完了の証跡: NONE, SELF_DECLARATION, DUAL_CONFIRMATION, DOCUMENT_LINK, SYSTEM_CHECK の5区分。
- 既存承認エンジンへ載せる操作と、単純Task完了の境界: 通常タスク完了は直接実行（CAS保護）、完了阻害タスクの例外免除（WAIVED）のみ ApprovalEngine（RequestType = LIFECYCLE_EXCEPTION）を利用。

### DG-02 NF-02

- **portal起票対象契約と利用者**: 顧客ポータルユーザー（`service-desk.create`/`service-desk.view`権限）。自社契約・案件・要員・担当者に厳格制限。
- **SLAの営業時間、休日calendar、停止時間、priority matrix**: 09:00-18:00、自組織/法人単位のカレンダー厳格分離（法人間カレンダー和集合の禁止）、`WAITING_CUSTOMER`でSLA停止、再開時延長。エスカレーション順序: ①リクエストOwner → ②契約担当営業 → ③顧客主担当営業 → ④アクティブ管理者全員（硬直ID 1フォールバック完全撤廃）。
- **internal noteと顧客公開commentの分類・誤公開防止方式**: `t_service_comment.visibility`（`INTERNAL` / `PORTAL_VISIBLE`）とDTOレベルの完全除外。
- **health scoreの要因、重み、表示対象、更新判断への使い方**: 100点満点減点モデル（未解決P0 -30点/件、未解決P1 -15点/件、SLA違反30d -10点/件、直近90日CSAT平均3.0未満 -15点・3.0以上4.0未満 -5点、AR延滞 -25点、60日QBRなし -10点）。更新カレンダー連携（参照のみ・自動判断なし）。スナップショットは管理者・固定actor/sourceのSYSTEM scheduler専用、同一月冪等・データ変更時はappend-only新版（version_noインクリメント）。as-of算定を持たない過去targetMonthは拒否する。
- **Flyway & Integration**: rebase後の統合migrationはV147。詳細: `.kiro/specs/customer-success-service-desk/`

### DG-03 NF-03

- 対象資格、期限、証憑、研修費承認。
- skill gapの正本となる案件需要期間とskill taxonomy。
- 本人評価・上長評価・AI候補の表示/利用境界。

#### 開発段階 Owner ポリシー（DecisionId `DG-03-DEV-20260828`、2026-08-28）

| 項目 | 値 |
|---|---|
| OwnerRef | `PROJECT_OWNER` |
| OwnerDisplayName | `プロジェクト責任者` |
| OwnerType | `ROLE` |
| ApprovalMode | `ROLE_BASED_DEV` |

- 個人の実名を repository、`.kiro`、commit、test fixture へ記録しない。
- Gate は**責任主体を一意に識別できる OwnerRef**を要求する（開発段階は `PROJECT_OWNER`）。
- 承認証跡は DecisionId、決定日、OwnerRef、対象 scope、Base SHA、承認 commit で追跡する。
- 本番移行時の実ユーザー対応は repository 外の組織管理・監査システムで解決する。
- **本ポリシーの採用だけでは NF-03 を `APPROVED` にしない。** approved scope、Base SHA、DG-03 実値（6項目＋経費締め A or B）が承認された時点で、Status を `APPROVED` に更新し Owner=`PROJECT_OWNER` とする。

#### 開発開始承認（DecisionId `DG-03-SCOPE-APPROVAL-20260828-01`、2026-08-28）

| 項目 | 値 |
|---|---|
| OwnerRef | `PROJECT_OWNER` |
| Base branch | `origin/main` |
| Base commit | `76e45340a23cfee964fac778b7b4d856fa2c9e7b` |
| 旧 merge-base | `455fc92e`（承認 Base としては不使用） |

**Approved scope（要約）:** 資格 master/取得/期限/証憑、course/plan/enrollment、既存 ExpenseRequest 研修費、90/60/30 通知、as-of skill gap、rule-based gap＋AI 候補、本人/manager/HR workflow。**Out of scope:** 外部 LMS 連携、AI 自動評価/配置/採否・昇格・給与・不利益判断。

| DG | 確定値 |
|---|---|
| DG-03-1 PII | AES-256-GCM または token reference。`certification.pii.view` で full 参照。retention `CERTIFICATION_PII`。NF-07 まで自動削除なし |
| DG-03-2 証憑 | `CERTIFICATION_EVIDENCE`＋`CERTIFICATION_RECORD` typed resolver。admin bypass/empty-link/OR-union 禁止 |
| DG-03-3 taxonomy | `m_skill_tag` canonical。alias は HR/admin 承認。unknown 自動登録禁止 |
| DG-03-4 as-of | append-only event、feature 有効化日から。backfill なし。`historical_data_unavailable`。immutable snapshot |
| DG-03-5 経費 | **選択肢 A** — `ExpenseRequestServiceImpl` 共有締め。0 円は `ZERO_COST_CONFIRMED` のみ |
| DG-03-6 AI | rule-based primary。AI は候補/説明のみ。HR_FINAL のみ公式 skill 反映 |

通知: Asia/Tokyo、90/60/30、退職除外、休職は本人停止/HR 確認、account 未 link は HR 通知、DB dedupe。異議は HR workflow。

詳細: `.kiro/specs/certification-learning-skill-gap/approval-decision.md`、`.kiro/specs/certification-learning-skill-gap/owner-policy.md`

### DG-04 NF-04

- 承認日: 2026-08-28。Owner: 管理者（プロジェクト責任者）。
- 対応browser/OS: Chrome、Edge、Safariの現行版および直前版。AndroidはChrome、iOSはSafariを対象とする。
- PWA installは任意。初版ではpush通知を実装せず、既存のアプリ内通知/badgeを使用する。
- Service Worker cacheは静的shell/assetsのallow-listだけを許可する。API、portal、document、payroll、bank、PDF、attachment、その他PII responseはnetwork-onlyかつno-storeとする。inventoryで検出したno-store不足routeも修正対象とする。
- Offlineはtimesheet/attendanceの最小draftおよびdaily save/delete queue、expensesのdraft create/update、change-requestのallowlist payloadによるdraft createだけを対象とする。receipt、attachment、submit、resubmit、leave、profile、survey、1on1、lifecycle、submit/approve/reject/close/cancel/withdrawはonline-onlyとする。
- Draft/queueの最大保持期間は30日。送信成功時、logout時、user switch時は即時削除する。session expiry時はqueue送信を停止し、再認証後に同一user contextを検証して再開する。
- QueueはclientRequestId、canonical payload hash、baseVersion、user scope、screen、month、createdAtを保持する。同一ID・同一hashはreplay、同一ID・異なるhashおよびstale baseVersionは409とする。409ではserver/client差分を表示し、last-write-winsで上書きしない。
- user Aのdraft/queueをuser Bへ表示・送信しない。端末紛失・shared deviceではuser-scoped storageを信頼せず、logout/user switchでclearし、user context不一致時はflushをfail-closedする。
- 承認済みBase: `origin/main@455fc92e3aa259d2a93f25c6a545ca6c6af835bc`。実装開始時は必ずfetchして最新`origin/main`を再確認し、実際のBase SHAをspec/review-ledgerへ記録する。

### DG-05 NF-05

- DecisionId=DG-05-F1-APPROVAL-20260830-01、Decision date=2026-08-30、OwnerRef=PROJECT_OWNER、OwnerType=ROLE。
- Approved Baseはorigin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd、実装branchはcodex/integration-hub-public-api。
- F1のclient/credential/scope/idempotency/usage bucket/webhook persistence contractと最小crypto/config abstractionを承認する。
- HMAC-SHA256 signed service account、AES-256-GCM envelope、±5分、nonce replay拒否、rotation overlap 24時間、revoke即時、90日expiryを固定する。
- client CIDR default deny、trusted proxy限定、60 req/min、burst 20、日次50,000、SLA月間99.9%/p95 500ms、v1廃止予告180日を固定する。
- GET-only 11 pathsとinventory allow-listを承認する。F1 Decision時点ではcommand/export、public endpoint、外部送信、A1/A2/B1/B2、production enablementを保留した。scope expansion DecisionでF2、A1、B1、B2、Mの開発を承認し、A2はN/Aとする。
- webhookはHMAC-SHA256、timestamp±5分、最大8回の指数backoff+jitter、4xx no-retry、DLQ/manual replayを固定する。
- retentionはsucceeded 30日、failed/DLQ 90日、audit metadata 1年、legal hold中purge停止。脅威モデル11項目を受入対象とする。
- Plan ReviewのPLAN PASSをF1開始条件とし、force push、main変更、PR、merge、auto-mergeは禁止する。

#### DG-05 scope expansion

- DecisionId=DG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02、Decision date=2026-08-30、OwnerRef=PROJECT_OWNER、OwnerType=ROLE。
- Base=origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd、Implementation branch=codex/integration-hub-public-api、scope expansion approval reviewed Head=7e50bf1360ea8d7271acc0667593635451300268（承認時点の履歴値）。
- F1はPLAN PASS / IMPLEMENTATION PASS（P0/P1/P2=0）を維持し再オープンしない。scope expansion Plan deltaはca27f455でPASS、F2=fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`でIMPLEMENTATION_PASS、A1はfixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`でIMPLEMENTATION_PASS、B1は初回Review FAIL（fixed Head `0f1a92974ea914d16de07ccf5a586fac215283f0`、P0=0/P1=4/P2=1）を`30199db8`、再Review P1-006/P1-007を`2684ff8f`でremediateした。P1-007追加remediation後のNF05-IMPL-B1-008（初回送信前primary binding）を`c2cbfb99133d0df3f8d5eee285be340163747e31`で対応し、focused/H2/MySQL証跡を追加して同じR-NF05へ独立再Review待ち、B2/M=APPROVED_SEQUENCED。
- A2=NOT_APPLICABLE_UNDER_CURRENT_DECISION（approved command=0件）であり、command/exportはdefault deny、全体完了をblockしない。
- scope expansion Plan delta PASS後はF2→A1→B1→B2→Mを各waveの独立Review後に順次実装する。development/testのmock/stub providerとloopback test serverのみを許可する。
- production enablement、実顧客credential、実providerへの外部送信、force push、main変更、PR、merge、auto-mergeは禁止する。
- scope expansion Plan deltaの固定Head 1547871caed049ba14d1e5e4a25ad50fa19771fcはPLAN FAIL
  （P0=0、P1=4、P2=2）。dedicated chain、HMAC canonical bytes、production fail-closed、
  mock/loopback destination、A2 N/A、旧traceをdocs-onlyで補正後、同じR-NF05へ再Reviewする。
- scope expansion Plan delta remediationは8d25215b9b651e99433becf50d13498da3699d2aへpush済み。
- scope expansion Plan delta re-Reviewの固定Head 9cca2deec9ab1bd5417aaba98f859ed14210da13はPLAN FAILだったが、remediation後の固定Head ca27f45532bbf96d29da7b9ba87ca52b9cf96d8aでPLAN PASS（P0=0、P1=0、P2=0）を受領した。
- scope expansion Plan delta residual remediationはe18f0d589b63223bf864bb33c6910b56a59d940eへpush済み。Plan PASS後、F2を実装し、FAIL remediation `e47025b5`と追加remediation `a16cdcba`を経て独立Review PASSを受領した。A1初回FAIL（fixed Head `111f4baa37096a1419cc8aaddcb2fe8c71e0e229`、P0=0/P1=2/P2=2）はremediateし、fixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`で独立Implementation Review PASSを受領した。B1初回Review FAIL（fixed Head `0f1a92974ea914d16de07ccf5a586fac215283f0`、P0=0/P1=4/P2=1）を`30199db8`でremediateした。再Review fixed Head `29d749bb6db1aad9ca98a9dd253b30d375dbba5c`のP1-006/P1-007を`2684ff8f1303b6d0cc6550882601405d3d78f3b2`でremediateし、P1-007残存へprimary/secondary binding、current DB membership、soft-delete/reparent/contract付替え検証を`5c94367c499bb019ca459659b43580817419a2f1` → `0618d983e397de4526b265f96565991110b11299`で追加した。さらにNF05-IMPL-B1-008を`c2cbfb99133d0df3f8d5eee285be340163747e31`でremediateし、docs trace commit後に同じR-NF05へ独立再Reviewとしてhandoffする。

### DG-05 NF-05 current implementation checkpoint（2026-08-31）

| Wave | 状態 | Fixed Head / evidence |
|---|---|---|
| F1 | IMPLEMENTATION_PASS | existing approved F1 Review PASSを維持、再オープンなし |
| F2 | IMPLEMENTATION_PASS | fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`、P0/P1/P2=0/0/0 |
| A1 | IMPLEMENTATION_PASS | fixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`、P0/P1/P2=0/0/0 |
| B1 | IMPLEMENTATION_PASS | fixed Head `f897d748cb93ade26c41d6ba4cb1a88efb29a29d`、P0/P1/P2=0/0/0 |
| B2 | IMPLEMENTATION_REVIEW_PENDING | initial `122c7c3b`、remediation `cc468e4f` → `251461f1` → `e564f400`。provider/resource/admin/content-type/quota/stable-error境界を補正。Linux connector再Review待ち |
| A2 | NOT_APPLICABLE_UNDER_CURRENT_DECISION | approved command=0件、command/exportはdefault deny |
| M | APPROVED_SEQUENCED | B2独立Review後。security、負荷、障害訓練、rotation、scan、runbook、最終Head固定が未完 |

production enablement、実顧客credential、実provider送信、main変更、force push、PR、merge、auto-mergeは禁止する。B2の独立Implementation Reviewは
既存R-NF05へdocs trace commit後の固定remote Headをhandoffし、PASS前にB2を公開可能と扱わない。

### DG-06 NF-06

- 最初に対応するentityと旧システムschema。
- 自然キー、重複処理、upsert/insert-only、既存行更新の承認。
- job単位rollback保証と、後続参照発生後の補償方式。

### DG-07 NF-07

- PII owner、処理目的、保存期間、法務/HR/税務責任者。
- legal holdの開始/解除権限と二者承認。
- 削除、匿名化、参照制限、exportの対象別方式。
- 本人確認手段とrequest期限。
- policy version、起算trigger、対象別result evidence、処分方式のallow-list。
- 同姓同名/複数候補のhuman resolution、第三者redaction、scope/delivery/期限/reopen。
- legal-document-ledger-archiveの未分類3文書、storage削除失敗時のresult evidence。
- database-backup-recoveryのPROD-001〜008、DB+binary同時点restore、restore後tombstone再適用。
- production feature flag、法務owner、runbook、monitoring、emergency stop。

NF-07の承認証跡は現在提供されていない。`<APPROVED_SCOPE>`、`<OWNER>`、`<BASE_BRANCH>`、`<BASE_COMMIT>`を推測で置換せず、技術比較base `origin/main@f131f51c50dbfb68ffc8e71878da52947560c80e` と開始時merge-base `0333b0a4afadef42639bad27e1ae443758f9804f`を未承認のReview境界として記録する。中央台帳のStatusは`CANDIDATE`のまま変更しない。

### DG-08 NF-08

- 本番AI provider、data processing agreement、越境、training opt-out。
- semantic query catalogのownerと更新Review。
- 利用可能role、質問/回答retention、cost上限。
- 回答不能時のUXとhuman escalation。

### DG-09 NF-09

#### 開発開始承認（DecisionId `DG-09-SCOPE-APPROVAL-20260828-01`、2026-08-28）

| 項目 | 値 |
|---|---|
| OwnerRef | `PROJECT_OWNER` |
| Base branch | `origin/main` |
| Base commit | `b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd` |
| Branch | `codex/asset-account-license-lifecycle` |

**Approved scope（要約）:**
- 資産台帳管理（PC/モニター/端末等、シリアル、保管場所、取得日/価格、リース満了日、不変イベント台帳 `t_asset_event`、ステータスCAS）
- 貸与・返却管理（期間重複代数排他 `[start_date, expected_return_date]`、行ロック `FOR UPDATE`、返却時 `IN_STOCK` 復帰、証跡文書リンク）
- 外部アカウント参照管理（SaaS/IdP識別子と状態、パスワード/トークン秘密非保存原則、失効要求 `revoke_requested_at` と失効確認 `revoke_confirmed_at` の分離）
- 有償ライセンス席数管理（プラン別 `seat_limit`、CAS 条件付きインクリメント `allocated_count < seat_limit`、割当・解放）
- 定期実地棚卸し・差異照合（理論在庫スナップショット展開、実地ステータス/場所入力、MATCH/DISCREPANCY/MISSING集計、完了確定固定）
- 期限監視・アラート通知（返却期限超過、リース満了30日前、紛失インシデント緊急初動通知、日次スケジューラ）
- 要員マイポータル（`/my/assets`、有効貸与/アカウント/ライセンス確認、紛失・盗難自己報告）
- NF-01 退社ゲート連携（`RESIGN_ASSET_RETURN` 未返却/未失効ブロック、`LIFECYCLE_EXCEPTION` による特例免除 WAIVE 連携、退社確定時一括失効トリガー）
- 外部プロバイダ連携境界（NF-09配下自己完結型 `ExternalAccountProviderClient` / Mock、タイムアウト/未確認を成功扱いにしない統制。NF-05開工時にNF-05実アダプターへ委譲）

**Out of scope:**
- パスワード・クレデンシャル・シークレット・回復コードの保存（厳格禁止）
- MDM サーバー本体・IdP プロトコルサーバー本体の実装（外部SaaS参照およびNF-05委譲前提）
- 外部APIタイムアウト時の自動成功みなす処理（厳格禁止）

| DG | 確定値 |
|---|---|
| DG-09-1 資産識別・所属 | 管理タグ `asset_tag` 一意、シリアル番号 `serial_no`、所有法人 `owner_company_id`、状態6区分（`IN_STOCK`, `ASSIGNED`, `UNDER_MAINTENANCE`, `LOST`, `DISPOSED`, `RESERVED`） |
| DG-09-2 秘密非保持・外部参照 | `t_external_account_reference` にシークレット列を一切持たない。外部アカウント識別子と状態（`ACTIVE`, `SUSPENDED`, `REVOKED`）、失効要求/確認日時のみ保持 |
| DG-09-3 ライセンス席数CAS | `UPDATE m_license_plan SET allocated_count = allocated_count + 1 WHERE allocated_count < seat_limit AND version = :v` で席数上限を原子保護 |
| DG-09-4 NF-01 Link Contract | 退社タスク `RESIGN_ASSET_RETURN` に対し、未返却端末・未失効アカウント・未解放ライセンス残存時は退社ブロック。`LIFECYCLE_EXCEPTION` 承認時のみ WAIVED バイパス |
| DG-09-5 外部連携所有境界 | NF-09配下の `ExternalAccountProviderClient` / Mock を利用。失効要求と確証ステータス確認を厳格分離し、タイムアウトは未完了（非成功）として保持。NF-05開工時に実アダプターへ透過委譲 |

詳細: `.kiro/specs/asset-account-license-lifecycle/`

### DG-10 NF-10

- **利用者とscope**: 利用者は管理者とマネージャー。管理者は全社、マネージャーは許可された組織scopeだけを対象とする。scheduleの有効化は管理者のみが行う。
- **対象section**: 売上、粗利、売上予測、稼働率、Bench、管理会計、Cash Flow、AR aging、BP支払予定、契約終了・更新見込み。NF-02がPASSするまでServiceDesk/SLAは対象外とする。
- **月次と時刻**: 月次管理レポート、timezoneは`Asia/Tokyo`。速報は未締めデータとして`dataAsOf`とfreshnessを表示する。確定版は月次締め完了後のみ生成する。
- **snapshot**: snapshot/documentは7年間保持し、snapshotはimmutableとする。template変更・現在DB値・現在権限変更で過去runを変化させない。明示的な再生成は上書きせず新versionを作る。通常のgeneration retryは同一runの同一snapshotを再利用し、重複snapshotを生成しない。
- **失敗**: sectionが1つでも失敗したrunは`PARTIAL`/`FAILED`として配布を停止する。失敗とretryを監査可能にする。
- **配布と認可**: 配布はnotification outbox経由のアプリ内通知＋期限付きlinkのみとし、メール添付は使用しない。recipient previewを生成前に必須とする。generation時とdownload時の両方でrecipient scopeを検証し、権限喪失・組織異動・link期限切れではdownloadを拒否する。download時は再認証を要求する。
- **正本とscheduler**: PDF/XLSX/CSVは同じimmutable snapshotから生成する。既存正本service/DTOを使用し、report独自SQL・集計式・丸めを作らない。schedulerは明示system principalを使用し、HTTP sessionに依存しない。
- 承認証跡: Owner=`管理者（経営管理責任者）`、Base branch=`origin/main`、承認済みBase policy=`再開時にfetchした最新origin/main`。承認時に確認された`origin/main`は`455fc92e3aa259d2a93f25c6a545ca6c6af835bc`。

## 5. 実装/Review証跡テンプレート

### 実装台帳行

| Task | Requirements | Base | Head | 変更file | Tests | Demo | 未検証 | Rollback | Review ready |
|---|---|---|---|---|---|---|---|---|---|
| `<Txxx>` | `<R...>` | `<sha>` | `<sha>` | `<paths>` | `<count/result>` | `<evidence>` | `<items>` | `<method>` | YES/NO |

### Review finding行

| Finding ID | Severity | Requirement | Evidence `file:line` | Reproduction | Impact | Minimum fix | Regression | Status | Fix commit |
|---|---|---|---|---|---|---|---|---|---|
| `<feature>-R1-P1-01` | P1 | `<R...>` | `<path:line>` | `<steps>` | `<impact>` | `<scope>` | `<test>` | OPEN | — |

### Severity

- **P0**: 情報漏えい、認証/認可突破、不可逆データ破壊、金額重大誤り、起動/移行不能。
- **P1**: 中核受入条件未達、状態競合、二重登録、scope不一致、回復不能な外部連携障害。
- **P2**: 限定条件の不具合、運用/UX/性能の改善が必要だがrelease判断可能。

## 6. Release Gateチェック

- [ ] requirements/design/tasksが承認済み。
- [ ] Base/Headが固定され、対象diffが分離可能。
- [ ] 実装が通常checkoutと分離した専用Codex worktree、`codex/<feature-name>` branchで行われている。
- [ ] 完了Taskのcommitがremote feature branchへpush済みで、local/remote Headが一致する。
- [ ] Reviewが通常checkout/実装worktreeと別の専用worktreeで行われている。
- [ ] Reviewがapproved scope/Decision Gate/requirements/design/tasks/ledgerを先に照合し、`PLAN PASS`を記録している。
- [ ] migration latest+1、V1/H2/MySQL同期が確認済み。
- [ ] fast gate skip 0。
- [ ] mysql gate skip 0。
- [ ] performance gate skip 0（性能影響がある場合）。
- [ ] desktop/390px Demo。
- [ ] role/data scope/CSRF/audit/競合/冪等の否定系。
- [ ] backup/restore/rollbackまたは補償手順。
- [ ] 監視、alert、runbook、feature flag。
- [ ] Plan Review PASS後にImplementation Reviewを行い、双方の独立ReviewがPASS。
- [ ] Review PASS後にPRが自動作成/更新され、PR URL/numberとreview済みHeadが台帳に記録されている。
- [ ] Review前またはFAIL/CONDITIONAL PASS時にready PRを作成していない。
- [ ] PRの自動merge/branch削除を行っていない。
- [ ] 外部契約/法務/セキュリティgate完了。

## NF-05 B2 remediation handoff

独立B2 Implementation Reviewの固定Head `0514e00a1cd27fdedba8d15b5bc87d2fd02d706c` はP0=0、P1=4、P2=1でFAILだった。
`cc468e4f`でapproved provider/subscriptionの受信前検証、resource primary/secondary opaque bindingと現行membership再評価、
LoginUser/admin permission、opaque admin reference、strict Content-Typeを実装修正した。B2 focused/H2 15 testsとMySQL 8
Flyway V136 smoke 2 testsはPASS、Windows connectorはloopback接続エラーで未検証とする。

同じR-NF05へdocs trace後の固定remote Headを独立再Implementation Reviewとしてhandoffする。B2 PASS受領までM開始、
production receive enablement、実credential、実provider送信、PR/mergeは禁止し、F1/F2/A1/B1のPASSは再オープンしない。

## NF-05 B2 quota/error follow-up

R-NF05の固定Head `7f16cc1d9aecf3ebd688d69f981f0610567d4d1` で、inbound routeがquota allow-listにないP1と、unknown providerの
test/spec status-code不一致P2が示された。`251461f1`でroute catalogの`QUOTA_ROUTE_TEMPLATES`をquotaの単一正本化し、
`/external-api/v1/webhooks/{provider}`を追加した。未承認providerは403/`FORBIDDEN_SCOPE`、形式不正は400/`REQUEST_INVALID`へ同期した。

Windows connectorはloopback接続確立前に5件errorだったためPASSに算入しない。Linux connector 5/5を同じR-NF05へ独立再Reviewとしてhandoffし、
B2はその判定までIMPLEMENTATION_REVIEW_PENDING、M・production enablement・実provider送信・PR/mergeは禁止とする。

## NF-05 B2 stable-error boundary follow-up

R-NF05 fixed Head `7757bfa49a4ece9aceddcedde2e835bc7466afe1` のP1（controller由来stable errorがwrapper経由で500化）を、
`e564f400`でremediateした。`ExternalApiResponseBoundaryFilter`は厳密なsecurity exception causeだけをunwrapし、その他は500へ収束する。
Linux実Tomcatで202/200/409/403/400、専用audit、拒否時ledger非作成を同じR-NF05へ独立再Reviewとしてhandoffする。
