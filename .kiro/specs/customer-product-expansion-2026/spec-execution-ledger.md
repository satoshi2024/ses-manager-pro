# 17spec中央実行台帳

## 1. 運用ルール

本台帳を対話管理の唯一の入口とする。通常は1specにつき主実装対話1つ、独立Review対話1つを使用し、
115個の原子taskごとに対話を作らない。原子taskは各specの `tasks.md` と `review-ledger.md` で追跡する。

2026-07-28以降は `execution-review-handbook.md` v2.0を必須基線とし、最終PASSはmerge済みHeadの独立Reviewに限る。
再ReviewはIssue RegisterのOPEN項目、修正diff、direct regressionへ限定する。既存対話の切替文面は
`conversation-baseline-v2.md`を使用する。

- `NOT READY`: decisionまたは先行spec待ち。対話を開始しない。
- `READY`: 開始条件を満たし、主実装対話を開始できる。
- `IN PROGRESS`: 主実装対話でtaskを順次実行中。
- `REVIEW`: 実装を止め、独立Review対話で確認中。
- `FIX`: Review指摘を元の実装対話で修正中。
- `PASS`: Review合格。次spec/Waveを開始可能。
- `DEFERRED`: 発注者決定により現行roadmap外。完了と同義ではない。

## 2. 中央台帳

| # | Wave | spec | カタログtask | 現在状態 | 開始条件/次のaction | 実装対話 | Base/Head | Review | 次へ進む条件 |
|---:|---|---|---|---|---|---|---|---|---|
| 1 | 0 | `multi-company-tenant-isolation` | T001〜T007 | T001 `COMPLETED`（発注者受領）、T002〜T007 `DEFERRED` | 独立DBを正式採用。V59は作成せず、V60以降適用後に補写しない。共有DB再承認時は当時latest+1で再計画 | — | — | T001は再Review対象外。R01は将来T002〜T007再開時だけ使用 | current-mode Gateを満たした記録を保持 |
| 2 | 0 | `organization-management-accounting` | T008〜T013 | `PASS` | R02最終merge後独立Review完了。desktop/390px実ブラウザDemoは本番前hard gateとして継続管理 | S02 organization-management-accounting 修正対話 | Base `4015785` → Head `f6f0027`（`main` / `origin/main`） | P0=0/P1=0。全量904/0/0/1、Node/JS 0 skipped、Docker MySQL smoke全5系統0 skipped。`organization-management-accounting-R21-P1-01` VERIFIED_CLOSED | PASS維持。desktop/390pxを本番リリース前に完了 |
| 3 | 0 | `enterprise-identity-security` | T014〜T020 | `PASS` | 独立Review R10（P0=0/P1=0/P2=1）および Docker 実MySQL Flyway smoke test（V1〜V66_1 全62マイグレーション適用 132/0/0/0 PASS）完了。本番前外部gate（実Entra/ClamAV/実訓練）は運用・本番リリース前検証として管理 | S03 enterprise-identity-security 再Review対応 | Review Base `fc509f49697b544cde456ecf5a1f33589a72b26b` | R07〜R10の全コード指摘および台帳記述誤記 VERIFIED_CLOSED。Docker MySQL smoke 100% SUCCESS | S04開放条件成就（S03 PASS完了） |
| 4 | 0 | `legal-document-ledger-archive` | T021〜T027 | `PASS` | R04最終独立Review完了（CONDITIONAL PASS: P0=0/P1=0/P2=11/release gates=G-1〜G-5）。V1/V67/H2/entity 4系統同期、isScoped先行＋SQL境界scope、廃棄承認管理者固定、全1104件テスト完走(1104/0/0/7 100% PASS)、git diff --check 警告0件達成 | S04 legal-document-ledger-archive | Base `9330796` → Head `c572a8f` (`main` / `origin/main`) | Tests run: 1104, Failures: 0, Errors: 0, Skipped: 7 (100% PASS)。BUILD SUCCESS実測。本番前Release Gate (G-1〜G-5) を継続管理 | R04 PASS成就。S05 (productivity-search-saved-view) 開放 |
| 5 | 0 | `productivity-search-saved-view` | T028〜T033 | `PASS` | **2026-08-01 発注者確認によりPASS（CONDITIONAL PASS: P0=0/P1=0）**。R5-P1-01 (todo.js 編集ボタンのインライン JS 直埋め破綻) を既存作法の `data-*` 属性方式へ修復し `VERIFIED_CLOSED`。M task L4全量 1135/0/0/7 BUILD SUCCESS。migration実績はV68/V69 | S05 productivity-search-saved-view 実装 | Base `ef488ff` → Head `b96d6e9`（`main` / `origin/main` merge済み） | R5-P1-01 VERIFIED_CLOSED、全量 1135/0/0/7 BUILD SUCCESS。本番前release gateとして継続管理: Docker実MySQL smoke / desktop・390px Demo / 検索p95 | **PASS成就によりWave 0完了。S06（実装済み）およびS08を正式解放** |
| 6 | 1 | `bp-company-master-procurement-compliance` | T034〜T040 | `PASS` | R06 Round 5 = **CONDITIONAL PASS（P0=0/P1=0/P2=13）**。R2〜R4の全指摘 `VERIFIED_CLOSED`。migration実績はV70/V71（V71は`information_schema`判定付きストアドプロシージャで State A/B/C 全DB環境へ冪等適用） | S06 bp-company-master-procurement-compliance 実装 | Base `ce1ccd4` → Head `4d34212`（台帳記録`ef8ddd7`まで含めて`main` / `origin/main` merge済み） | L4全量 1169/0/0/7 BUILD SUCCESS。`MigrationScriptIntegrityTest` 17/17、`SpecDispatchConsistencyTest` 8/8。本番前release gate G-1〜G-5（実MySQL fresh/legacy smoke、DELIMITER検証、desktop/390px Demo、G2外部専門家Review）を継続管理 | R06 PASS成就。S07（approval）は着手可能になった。S07は`PASS`確定済み、S09=`READY`、Wave 2=`解放`。S07正式migrationはV75〜V79（V72は永久欠番） |
| 7 | 1 | `approval-workflow-internal-control` | T041〜T047 | **`PASS`** | R4-P1-01（route decision source V79.1）、B1/T046、M/T047を`[x]`化。APPLICANT_MANAGERを含む5 sourceの境界・異常系回帰**47/0/0/0**、request→DB `route_snapshot_json`永続化→manager変更後の承認者不変を確認。V79.1実MySQL fresh/legacy、履歴、checksum/FK/CHECK/index assertion、専用回帰でpartial/repair/rollbackと、`v79_1-fk-actions-forward-repair.sql`の**DROP後・FK追加後・CHECK追加後**各partial状態からの再開可能な再実行（`FlywayV79_1RepairSmokeTest` **2/0/0/0 / BUILD SUCCESS**）、shared JDBCの複数JVM ShedLock/claim（`OperationalBoundaryMySqlIntegrationTest` **3/0/0/0**）、commit前例外時実DB rollback、Webhook loopback（**1/0/0/0**）、H2 lock warning回帰（**3/0/0/0**）を確認。**5業務desktop/390px browser Demo（10経路）を実Chrome `150.0.7871.187`で通し**、申請者単独確定不可・申請→承認→適用・二重click/retryでも業務操作1回（申請1件・APPROVE action 1件・retry後再適用なし）を証拠（`evidence/browser-m/` スクリーンショット40枚＋JSON 11ファイル）として記録。full application instance cron・外部providerは要件外としてN/A化。CI相当L4は**1471/0/0/0、BUILD SUCCESS、skip 0、script exit 0**。S07正式migrationは**V75/V76/V77/V78/V79**、R1.2/R1.3 patchは**V79.1**、S09=V80＋V81修復、S10=V84、S11=V83、S12〜S17=V85〜V90（V72/V82永久欠番、out-of-orderなし）。code baseline Head=`68fbbba`（23 commits/219 paths）、初回Review evidence commit=`2978461`（24 commits/272 paths、履歴）、現行Review evidence/result commit=`646dbda`（27 commits/274 paths）と分離。独立Review（`fa003f7`）で文書整合を確認しPASS確定 | S07 approval-workflow-internal-control 実装 | Review Base `5d228d2` → **code baseline Head** `68fbbba4dff8255b3a745ce61e73e686a78bef3e`（`68fbbba`時点で`main`/`origin/main`/`origin/HEAD`一致＝当時値）。Base→Headは**23 commits / 219 paths / +11639/-337**。**初回Review evidence commit** `2978461be1fd36334a00a97fabe37f5613e374a4`（Base→commit **24 commits / 272 paths**、履歴）と**現行Review evidence/result commit** `646dbdafb3c6b77ec0e3b7bb581392f50be53491`（Base→commit **27 commits / 274 paths**、seed修正＋browser evidence再生成）はcode baseline Headと分離して管理する（e88351d時点の基準はBase→**26 commits / 274 paths / +16873/-342**）。最終確認時worktreeは**clean**。現在の文書同期commitは`git log -1 -- <path>`で`591b1de`以降へ解決され、68fbbbaのHEAD/origin一致（当時値）と矛盾しない。Packet文書は本流`main`の履歴としてcommitされる（専用の独立Packetブランチ/リポジトリは持たない）。文書自身のcommit（例: `d13e726`は文書同期commit）は`git log -1 -- <path>`で解決するprovenanceとして記載し、code baseline Head（`68fbbba`）や現行Review evidence/result commit（`646dbda`）として自己参照しない。旧「12ファイルdirty」記述は`68fbbba`でcommit済みとなったため削除した | **PASS**。R4-REVIEW-01/02/03/04とR4-P1-01は全てVERIFIED_CLOSED、B1/T046・M/T047は`[x]`。V79.1 partial/repair/rollbackとrunbook再開可能性、複数JVM ShedLock/claim、commit前rollback、Webhook loopback、5業務desktop/390px browser Demo（10経路）、CI相当L4 **1471/0/0/0・zero-skipped**を全て確認。full application instance cron・外部providerはrequirements/design上で正式要求されないためN/A化。独立Reviewは219-path inventoryと`evidence/browser-m/`を対象に最終確認する | PASS確定済み。S09=`PASS`、S10=`READY`、S11=`READY`、Wave 2解放。S09 PASS後にS10/S11を並行dispatch可能 |
| 8 | 1 | `crm-contact-opportunity` | T048〜T053 | **`PASS`** | Round 8独立再ReviewでPASS確定。CRM-R5-P1-09（legacy NFKC backfill）・CRM-R5-P2-03（rollback順序）はVERIFIED CLOSED。T048〜T053全task完了、tasks.mdのM回帰も`[x]`化済み | S08 crm-contact-opportunity Round 7対応 | Base `94f95083f178b812caa43782a5e00d09a8d6f324` → Head `042bd0cfb8139466eb7199a7d625adfb181c8563`（`main` / `origin/main`） | Round 8: L4全量1,280/0/0/0（F0/E0/S0）、MySQL fresh/legacy/partial/repair全4経路成功、desktop/390px全role Demo（管理者・営業・マネージャー許可、HR・要員403）確認。P0=0/P1=0/P2=0、open release gate=0 | **PASS成就（S08）**。S07は`PASS`確定済み、S09=`READY`、Wave 2=`解放`。S07正式migrationはV75〜V79、V72は永久欠番のまま |
| 9 | 2 | `order-acceptance-workflow` | T054〜T059 | **`PASS`** | R12 independent diff reReviewでP0=0/P1=0/P2=1。旧OPEN 5件は全件VERIFIED_CLOSED。V80は変更せず、S10/S11を並行開放 | S09 order-acceptance-workflow Round 12 independent diff reReview | Base `23793ec` → code/evidence Head `7caa5e6` → Packet同期後merged Head（`git log -1 -- <path>`で解決、`main`=`origin/main`、worktree clean） | 直接回帰39/0/0/0、同一コードHead L4 282/1582/0/0/0、MySQL・390px証跡有効。R12-P2-01はPacket provenance記述のみ | S10/S11を正式dispatch可能。S12はS10/S11双方のPASS後、Wave 2解放済み |
| 10 | 2 | `dispatch-outsourcing-compliance-ledger` | T060〜T066 | `IN PROGRESS`（T060 PASS・T061 F1 PASS。T062 F2はR10 Round 10 FAIL→fix再提出済み・T063〜T065停止・T066 M/本番gate未達） | order PASS、G2 gate、V83実在/V82欠番 formal decision、S10=V84。R10 Round 9: fix delta `34c68f7`を独立実行40/0/0/0 skip 0で確認し、R5-P1-01..05・R8-P0-01（休憩保存: break分整数列＋t_compliance_break_detail/trigger/manifest同期）・R8-P1-01（ALWAYS全clearable列＋F1NullClearMapperTestでfull DTO NULL/CAS 0行実測）・R8-P2-01（PATCH rejectはT063へ移管）・R8-NOTE-01（asOf effective interval解決）を全VERIFIED_CLOSED。R10 Round 10: T062 F2（`de08f2e4`）を独立実行で検証し、core実装・golden 12/12は良好も**FAIL**（新規P1×2: R10-P1-01=T061 PASS済みV84に誤字4行混入・`34c68f7`へ復元済み、R10-P1-02=null-profileでMISSING系ruleが全skipのfail-open・全field未入力として検知へ修正）。fix delta再提出済み・再Review待ち。production release/apply authorizationなし | S10 dispatch T062 F2 fix（R10 Round 10） | T062 fix delta: V84をT061 PASS時の`34c68f7`バイト列へ復元＋5 ruleのnull-profile fail-closed修正＋ComplianceRuleEngineTest正本化。F2系30/0/0/0＋F1系8/0/0/0＋回帰、skip 0 | 独立実行 80/0/0/0 skip 0（F2系30＋F1系8＋MigrationScriptIntegrity 27＋ComplianceApi 1＋JsSyntax 1＋SpecDispatch S10側8/8）、`git diff --check` exit 0。残り2 failureはR10-P2-01他track起因（S12〜S14予約V99-V101 vs 実在V101、`project.detail.desc`） | T062再Review→PASSでcheckbox `[x]`→T063（A1）→（T064‖T065）→ T066 M（L4・runtime assignment/実actor承認event/外部専門家Review gate）。S12（staffing）はS10/S11双方PASS後。S11側はdirty working tree（V98・不正文字）を先に解消のこと |
| 11 | 2 | `attendance-leave-overtime-compliance` | T067〜T074 | `IN PROGRESS`（T067〜T071完了、T072〜T074未着手） | order PASS、G6確定済み。dispatchと並行可。R2-P1-01/02、R3-P2-01はVERIFIED_CLOSED。**T071（休暇/approval統合）実装済み・独立Review待ち**。V91（方式A）・V98（休暇残数台帳、発注者割当）実在、S12〜S17=V99〜V104へ繰り上げ済み。NOTE-R3-06（dispatch V84 fresh経路）はdispatch修正待ち | S11 attendance-leave-overtime-compliance 実装 | Base `5e29f39` → T067 `93c1ac6` → T068 `b327b1b` → T069 `d395797` → T070 `cc7c15c` → V91方式A `5f362fc`/`b65996f` → T071休暇 `本delta commit` | R11 Round 3独立再Review: 171/0/0/0 skip 0、MySQL smoke 2/0/0/0、fresh 2/0/0/0。T071: 休暇系4 class **20/0/0/0**、全指定回帰 **191/0/0/0 skip 0**、MySQL smoke 4/0/0/0（V83/V91/V98）、`git diff --check` PASS。P0=0/P1=0/P2=2/NOTE=1（R3-06 dispatch）。HR/法人別規程はATT-GATE-01〜06としてrelease gate | **T071独立Review→T072（freee/provider sync）着手**。V83/V91/V98を変更・再適用せず、V82を補填しない。S12〜S17はV99〜V104 |
| 12 | 2 | `staffing-capacity-planning` | T075〜T080 | `NOT READY` | dispatch/attendance PASS後S12 |  |  |  | R12 PASSでWave 2完了 |
| 13 | 3 | `external-customer-bp-portal` | T081〜T087 | `NOT READY` | Wave 2 PASS、G3/G8方針後S13 |  |  |  | R13 PASS、security chain先行merge |
| 14 | 3 | `engineer-self-service-portal-v2` | T088〜T093 | `NOT READY` | external portal security merge、G9方針後S14 |  |  |  | R14 PASS |
| 15 | 3 | `accounting-payment-integration` | T094〜T101 | `NOT READY` | portal/order/BP/archive PASS、G4/G9方針後S15 |  |  |  | R15 PASS |
| 16 | 3 | `jp-pint-digital-invoice` | T102〜T108 | `NOT READY` | accounting PASS、G5決定後S16 |  |  |  | R16 PASSでWave 3完了 |
| 17 | 4 | `ai-feedback-learning` | T109〜T115 | `NOT READY` | CRM/proposal/staffing/outcome完了、G10方針後S17 |  |  |  | R17 PASSでroadmap完了 |
| P1 | — | 勤怠系並行トラック（`.kiro/audits/2026-08-01-attendance-parallel-track-plan.md`、**履歴・superseded**） | S11 T067の先行分 + 勤怠導線是正 | **`COMPLETED`**（A1/A2/A3/B1/B2） | A1/A2/A3/B1はmerge済み。B2は`4488ba8`（calculator・test・fixture計23ファイル）でmainへmerge済み。repo-known persistent DB latestはV74、CI/TestcontainersでV83実在、S10予約V84、attendance実在V83 | S11 attendance-leave-overtime-compliance 実装 | B2 Head `4488ba8` → Packet/current merged Head `509bdb7`（main=origin/main） | **R11のReview範囲にT067とB2補助diffを含める**。B2はF1のV83 agreement接続前の第2/第3段まで完了 | T068着手前にV83の衝突を再確認し、T068でB2をV83 agreement接続へ引き継ぐ |

## 2.1 第十八次Review対応の最新実績（2026-07-29）

最新ReviewのBaseは`origin/main=fb91943`（PR #42 merge済み）。P1-1〜P1-4のキャッシュ競合、manager組織scope漏れ、混在組織請求書、NULL業務通知を`90f50c0`で修正し、manager/notification/invoiceの実行級回帰を追加した。定向・全量は883/0/0/1（唯一skipはCJKフォントなし）、Node/JS syntaxは実行済み、Flyway fresh・legacy V60・repair・V62 closed fixture・migration integrity・ConcurrentUpdateは0 skippedで実行済み、`git diff --check` exit 0。`90f50c0`は未mergeのため、S02はFIX/REVIEW、S03はNOT READYとする。desktop/390px Demoは本番前硬门禁として未実施のまま保持する。

## 2.2 S02/S03実装差分の独立バグ検査（2026-07-30）

merge前branchのS02実装とS03 F1〜B2に対する追加検査で、P0×1・P1×4・P2×3・P3×1を検出し修正した。
最重要はaction permission層の後方互換破り（営業/HR/要員が業務APIで403）と、V64がrole-managerへ
全権限wildcardをseedしていた点である。詳細と修正内容は
`enterprise-identity-security/review-ledger.md`の「追加修正（2026-07-30 …）」を正とする。

- 追加migration: **V66**（action permissionのbaseline付与と拒否指定）。V63〜V65は変更していない。
- 採番影響: 後続spec#4〜#17の予約をV67〜V80へ繰り上げ、README予約表と全design/tasksを同一差分で更新済み。
- test: 全量 1027/0/0/6。skip 6件は全てDocker必須のTestcontainers。
- **release gate（未達）**: proxyがDocker Hub blob CDNを遮断するため`mysql:8.0`を取得できず、V66は実MySQLで
  未実行。Docker利用可能なCIでFlyway smoke 5件を実行するまでS03をPASSへ進めない。

## 2.3 Wave 0完了認定とCRM（S08）採番確定（2026-08-01）

S08 `crm-contact-opportunity` の着手前確認で挙がった2件のblockerを、発注者確認のうえ本節で解消する。

1. **Wave 0完了**: S05 `productivity-search-saved-view` は Round 5 の R5-P1-01（`todo.js`のインラインJS直埋め）
   修正が完結し、L4全量 1135/0/0/7 BUILD SUCCESS、Head `b96d6e9` は `origin/main` にmerge済みである。
   これをもって **S05 = PASS（CONDITIONAL PASS）＝ Wave 0完了** と認定する。Docker実MySQL smoke、
   desktop/390px Demo、検索p95 は S02/S04/S06 と同じく **本番リリース前のrelease gate** として継続管理し、
   後続specの開始条件には含めない。S06も Round 5 CONDITIONAL PASS（P0=0/P1=0、L4全量 1169/0/0/7）で
   `origin/main` にmerge済みのため、**S08はWave 0 PASS後の正規の開始**であり、条件付き先行着手ではない。

2. **Migration採番**: `db/migration` の実適用済み最新は **V71**（`V70__bp_company_master_and_compliance.sql`、
   `V71__bp_company_fix_and_procurement.sql`）。**S08 CRMは `V73` で確定**する。
   採番の正本は `README.md` §3 の予約表であり、`design.md`（予約V73）、`tasks.md`、`spec-start-conversations.md`、
   `copyable-conversations/S08__…start.txt` の全てが既にV73で一致している（`SpecDispatchConsistencyTest` が固定）。
   旧番号（BP V69 → CRM V70）が残っていた `parallel-execution-plan.md` / `dispatch-guide.md` /
   `spec-review-conversations.md` / `copyable-conversations/R06〜R17` / `task-start-conversations.md` は
   本更新で予約表（V72〜V82）へ揃えた（§2.4でV75〜V84へ再繰り上げ）。

   - **V72の扱い**: 予約はS07 approvalだが、着手順はCRM（V73）が先である。Flywayは `out-of-order` を
     有効化していないため、V73適用済みDBへ後からV72を足すと `FlywayValidateException` になる。
     したがって **V72はV59と同じ永久欠番** とする。CRM側がV72へ繰り下げて欠番を埋めることは禁止する。
     approvalの繰り上げ先は §2.4 のとおり **V75** で確定した（本節作成時点の想定はV74だったが、
     CRMがV74を権限seedに使用したため1つ後ろへずれた）。

本節の更新はドキュメントのみで、コード・SQL・各specの`tasks.md`のチェックボックス状態は変更していない。
S08は `origin/main` の最新をBaseに再取得し、T048から開始する。

## 2.4 CRM（S08）Round 2 対応と採番の再繰り上げ（2026-08-01）

R08 Round 2 は **CONDITIONAL PASS**（P0=0 / P1=1 / P2=2 / NOTE=3）。Round 1のP0×2・P1×3は
Review側の実測で全て `VERIFIED_CLOSED`。新規P1 **CRM-R2-P1-01**（`/api/crm/*` がaction key解決表に無く、
V73が登録したmenuに `MenuPermissionFilter` がヒットした時点で**管理者を含む全roleが403**）を
本更新で修正した。

修正は2段構えである。片方だけでは閉じない。

1. `ActionPermissionResolver.RESOURCE_NAMES` へ `crm` を登録する。未登録rootでは `resolve()` が
   null を返し、`/api/**` も page も**管理者bypassより前**の `deny()` に落ちる。
2. **V74** で `crm.*` を `t_permission_group_action` へseedする。V66_1が非管理者groupから
   全局 `*` を削除して「既知resource wildcardの列挙」へ置換したため、rootを登録しただけでは
   group割当済みの営業/マネージャーが拒否される。

2の調査中に、**同じ理由でS05とS06にも付与漏れ**があることが判明した。V68/V69の
`search` / `task` / `saved-view` / `batch-operation` とV70の `bp-company` は `RESOURCE_NAMES` に
登録済みだが権限seedが無く、group割当済みの営業/HR/マネージャーは**出荷済み機能で403**になる
（V67の `document.*` だけが正しくseedしていた）。同じ1行機構で塞げるため、発注者確認のうえ
**V74で併せて補完**した。付与先は各specのmenu付与に一致させている。

再発防止として `MigrationScriptIntegrityTest` に静的検査を2件追加した（Docker不要）。

- `migrationが登録するメニューのapi_prefixがaction_keyへ解決できること`
- `メニューを持つresourceには権限seedがあること`

いずれも修正前の状態で実際に失敗することを確認済みである。

### 採番

**CRMがV74を使用したため、S07 approvalを V75、#9〜#17を V76〜V84 へ繰り上げた。**
`README.md` §3の予約表、各specの `design.md` / `tasks.md`、`spec-start-conversations.md`、
`spec-review-conversations.md`、`task-start-conversations.md`、`copyable-conversations/S07〜S17`・
`R07〜R17`、`parallel-execution-plan.md`、`dispatch-guide.md` を同一差分で更新した
（`SpecDispatchConsistencyTest` 8/8で固定）。**V59とV72は永久欠番**であり、埋めない。

### 残課題

- **CRM-R2-P2-01（範囲外・backlog）**: `main` が本spec以前からREDである
  （`WorkRecordServiceImplTest` ×2、`BpPaymentWritePathTest` ×1）。T053(M)は「`mvn test`全量」を
  要求するため、work-record / BP発注側で解消しない限りS08は構造的にPASSできない。
  `4d34212`〜`e8b7da6` のbisectが必要。
- **NOTE-R2-02**: `.github/workflows/ci.yml` のno-skip gateは `Flyway.*SmokeTest` 等を除外している。
  **CIが緑であることをもって実MySQL smokeが実行された証拠にしない**。当該runのsurefire XMLで
  `skipped="0"` を直接確認すること。
- **NOTE-R2-01**: 閉区間同士のprimary重なりは生成列UNIQUEの対象外。T049/A1のservice CASで塞ぐ。

## 2.5 CRM（S08）Round 8独立再ReviewによるPASS確定とS07解放（2026-08-02）

S08 `crm-contact-opportunity`のRound 8独立再Reviewが完了した。CRM-R5-P1-09（legacy NFKC backfill）・
CRM-R5-P2-03（rollback順序）はいずれもVERIFIED CLOSED。Base `94f9508` → Head `042bd0c`（`origin/main`と一致）で
L4全量 1,280/0/0/0（F0/E0/S0）、MySQL fresh/legacy/partial/repair全4経路成功、desktop/390px全role browser Demo
（管理者・営業・マネージャー許可、HR・要員403）を確認した。P0=0/P1=0/P2=0、open release gate=0。

これにより§2.4「残課題」記載のCRM-R2-P2-01（`main`既存RED: `WorkRecordServiceImplTest`/`BpPaymentWritePathTest`）は
本Round時点でL4全量がF0/E0/S0であることから解消済みと確認できる。

- `crm-contact-opportunity`のtasks.md（T048〜T053、M回帰含む）は全項目`[x]`。review-ledger.mdにRound 8 PASSを追記済み。
- 上表の§2番号8行を`IN PROGRESS`→`PASS`、Reviewを`8a6531a`→`042bd0c`Roundへ更新した。
- 番号7行（`approval-workflow-internal-control`）を`NOT READY`→`READY`へ更新した。採番はV75で確定のまま（V72は永久欠番）。
- 追加のコード修正・再Reviewは不要。Wave 2はS07完了とその独立Review PASSまで引き続き未開始。

## 2.6 S09 Round 12 PASS current Head correction（2026-08-09）

上記のS09に関する過去記録は履歴として保持する。現行の正本は本節とS09の
`order-acceptance-workflow/review-ledger.md` §1/§2.7であり、`1497305`、`e0bd72b`、`67de0d4`を現行Headとして扱わない。

- **code/evidence Head**: `7caa5e6a25b21a21a7d7d02961ace7245b33fb47`。実装・Browser証跡を含む。
- **Packet/current merged Head**: Packet同期commit（`git log -1 -- <path>`で解決）。Packet同期後の
  `git rev-parse HEAD origin/main`は同一SHAで、`main`=`origin/main`=`origin/HEAD`である。
  V80, V81, V83実在、S10=V84、S11=V83（方式A追補V91実在）、S12=V99、S13=V100、S14=V101、S15=V102、S16=V103、S17=V104とする。V59/V72/V82は永久欠番。
  過去版の予約記載（V82〜V89）は履歴として保持するが、現行正本は上記のV83実在・V82欠番decisionによりS10=V84、S11=V83＋追補V91、S12〜S17=V99〜V104とする。
- **current state**: `PASS`（R12 independent diff reReview完了、P0=0/P1=0/P2=1）。
  R12-P2-01はPacket provenance記述のみで、実装・認可・データへの影響はない。
- **L4 evidence**: Maven/Surefire最終report群で282 classes / 1582 tests / failures 0 / errors 0 /
  skipped 0を確認し、Docker MySQL smokeを含む。外側監視のtimeoutでwrapper最終trailerは未取得だが、child完了後の全report集計とzero-skippedを確認した。
  Browser evidenceはS09 review-ledger §2.7および`order-acceptance-workflow/evidence/`へ固定する。
- **direct regression**: 39 tests / failures 0 / errors 0 / skipped 0 / BUILD SUCCESS。同一コードHeadのL4は方針どおり再実行せず、282 XMLを検算した。
- **worktree**: clean、`.tmp-ui-scale-r3` gitlinkなし。nested repository内部はS09 main-tree変更対象外である。
- **next action**: S10/S11を並行dispatch可能。S12はS10/S11双方のPASS後。Wave 2を解放する。

## 3. 1specの状態遷移

```text
NOT READY → READY → IN PROGRESS → REVIEW ─┬→ PASS
                                           └→ FIX → REVIEW

発注者による延期: NOT READY/IN PROGRESS → DEFERRED
再開時: DEFERRED → decision・採番・依存再確認 → READY
```

## 4. 対話命名例

- 実装: `S02 organization-management-accounting 実装`
- Review: `R02 organization-management-accounting Review`
- 修正は新規対話を作らず、同じS02へR02の指摘だけを返す。
- 再Reviewも新規対話を作らず、同じR02へ修正commit/diffだけを渡す。
