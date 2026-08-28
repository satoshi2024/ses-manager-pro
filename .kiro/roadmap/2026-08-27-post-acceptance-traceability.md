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
| NF-05 | `integration-hub-public-api` | CANDIDATE | 未定 | API成功率、DLQ滞留 | identity、outbox、audit、data scope | 未決定 | 未定 |
| NF-06 | `data-migration-import-center` | CANDIDATE | 未定 | reconciliation差異0 | customer/project/contract、CSV、document | 未決定 | 未定 |
| NF-07 | `privacy-retention-dsar` | CANDIDATE | 未定 | retention未設定0、誤削除0 | document retention、audit、AI allow-list、全migration/entity/provider coverage | 承認済みscope/Privacy owner/Base branch/SHAのdecision evidence未提供。DG-07、外部専門家、社内責任者、backup/recovery、identity、recruiting、AI G10 gate未完。0/D0（inventory/no-write dry-run/spec）のみ許可し、F1-M/処分/外部provider/PRは停止。Review verdictは実装branchに記録せず、外部Review証跡でbindする | 承認証跡受領後 |
| NF-08 | `ai-management-copilot` | CANDIDATE | 未定 | 根拠link率、scope漏えい0 | AI gateway、全集計service、NF-07 | 未決定 | 未定 |
| NF-09 | `asset-account-license-lifecycle` | CANDIDATE | 未定 | 未返却、active account残存 | NF-01、identity、document | 未決定 | 未定 |
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

- portal起票対象契約と利用者。
- SLAの営業時間、休日calendar、停止時間、priority matrix。
- internal noteと顧客公開commentの分類・誤公開防止方式。
- health scoreの要因、重み、表示対象、更新判断への使い方。

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

- API利用者、契約SLA、公開resource/command。
- OAuth provider、client secret保管/rotation、IP制限。
- version廃止期間、rate limit、課金/利用量制限。
- webhook署名、retry上限、dead-letter retention。

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

- 資産種別、所有法人、棚卸し頻度。
- 外部MDM/IdPとの連携範囲と正本。
- 紛失時のincident/法務/セキュリティ連絡。

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
