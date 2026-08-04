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
| 6 | 1 | `bp-company-master-procurement-compliance` | T034〜T040 | `PASS` | R06 Round 5 = **CONDITIONAL PASS（P0=0/P1=0/P2=13）**。R2〜R4の全指摘 `VERIFIED_CLOSED`。migration実績はV70/V71（V71は`information_schema`判定付きストアドプロシージャで State A/B/C 全DB環境へ冪等適用） | S06 bp-company-master-procurement-compliance 実装 | Base `ce1ccd4` → Head `4d34212`（台帳記録`ef8ddd7`まで含めて`main` / `origin/main` merge済み） | L4全量 1169/0/0/7 BUILD SUCCESS。`MigrationScriptIntegrityTest` 17/17、`SpecDispatchConsistencyTest` 8/8。本番前release gate G-1〜G-5（実MySQL fresh/legacy smoke、DELIMITER検証、desktop/390px Demo、G2外部専門家Review）を継続管理 | R06 PASS成就。S07（approval）は着手可能になった（現行の独立ReviewはNOT REVIEWABLEであり、S09/Wave 2は未解放）。S07正式migrationはV75〜V79（V72は永久欠番） |
| 7 | 1 | `approval-workflow-internal-control` | T041〜T047 | **`IN PROGRESS`** | T041〜T046の実装記録は存在するが、B1/T046・M/T047は未完了。R1.2/R1.3のroute decision sourceをV79.1で実装し、追加source別境界・異常系回帰37/0/0/0を確認したが、R4-P1-01は実MySQL gate未確認のため**OPEN / P1**を維持する。S07正式migrationは**V75/V76/V77/V78/V79**、R1.2/R1.3 patchは**V79.1**、S09以降はV80〜V88（V72永久欠番、out-of-orderなし） | S07 approval-workflow-internal-control 実装 | Review Base `5d228d2` → current Head `74329e9`（`main` / `origin/main` / `origin/HEAD`一致）。Base→Headは**18 commits / 211 files / +9684/-330**。current worktreeはR1.3追加回帰test 3 filesが未commitでdirty、commit/pushは行っていない。manifestは211 committed pathsへ同期済み | **NOT REVIEWABLE**。R4-REVIEW-01/03/04、R4-P1-01、B1/M、実MySQL/Browser/zero-skippedは未達。R4-REVIEW-02のみVERIFIED_CLOSED。R1.3対象37/0/0/0、static35/0/0/0、20クラスdirect153/0/0/0、L4相当1454/0/0/12（Maven BUILD SUCCESS、scriptはskip検出exit 1） | current HeadのPacketを独立Reviewし、R4-P1-01とB1/Mの実環境DoDが完了するまでS09/Wave 2を解放しない |
| 8 | 1 | `crm-contact-opportunity` | T048〜T053 | **`PASS`** | Round 8独立再ReviewでPASS確定。CRM-R5-P1-09（legacy NFKC backfill）・CRM-R5-P2-03（rollback順序）はVERIFIED CLOSED。T048〜T053全task完了、tasks.mdのM回帰も`[x]`化済み | S08 crm-contact-opportunity Round 7対応 | Base `94f95083f178b812caa43782a5e00d09a8d6f324` → Head `042bd0cfb8139466eb7199a7d625adfb181c8563`（`main` / `origin/main`） | Round 8: L4全量1,280/0/0/0（F0/E0/S0）、MySQL fresh/legacy/partial/repair全4経路成功、desktop/390px全role Demo（管理者・営業・マネージャー許可、HR・要員403）確認。P0=0/P1=0/P2=0、open release gate=0 | **PASS成就（S08）。S07 approval-workflow-internal-controlの正式Review PASSは未成就で、S09/Wave 2は未解放**（S07正式migrationはV75〜V79、V72は永久欠番のまま） |
| 9 | 2 | `order-acceptance-workflow` | T054〜T059 | `NOT READY` | approval PASS後S09 |  |  |  | R09 PASS |
| 10 | 2 | `dispatch-outsourcing-compliance-ledger` | T060〜T066 | `NOT READY` | order PASS、G2確定後S10。attendanceと並行可 |  |  |  | R10 PASS |
| 11 | 2 | `attendance-leave-overtime-compliance` | T067〜T074 | `NOT READY` | order PASS、G6確定後S11。dispatchと並行可 |  |  |  | R11 PASS |
| 12 | 2 | `staffing-capacity-planning` | T075〜T080 | `NOT READY` | dispatch/attendance PASS後S12 |  |  |  | R12 PASSでWave 2完了 |
| 13 | 3 | `external-customer-bp-portal` | T081〜T087 | `NOT READY` | Wave 2 PASS、G3/G8方針後S13 |  |  |  | R13 PASS、security chain先行merge |
| 14 | 3 | `engineer-self-service-portal-v2` | T088〜T093 | `NOT READY` | external portal security merge、G9方針後S14 |  |  |  | R14 PASS |
| 15 | 3 | `accounting-payment-integration` | T094〜T101 | `NOT READY` | portal/order/BP/archive PASS、G4/G9方針後S15 |  |  |  | R15 PASS |
| 16 | 3 | `jp-pint-digital-invoice` | T102〜T108 | `NOT READY` | accounting PASS、G5決定後S16 |  |  |  | R16 PASSでWave 3完了 |
| 17 | 4 | `ai-feedback-learning` | T109〜T115 | `NOT READY` | CRM/proposal/staffing/outcome完了、G10方針後S17 |  |  |  | R17 PASSでroadmap完了 |
| P1 | — | 勤怠系並行トラック（`.kiro/audits/2026-08-01-attendance-parallel-track-plan.md`） | S11 T067の先行分 + 勤怠導線是正 | **`IN PROGRESS`** | A1/A2/A3/B1完了（PR #51〜#54 merge済み）。migrationを1本も作成しておらず最新はV74のまま。B2「OvertimeComplianceCalculator + 境界fixture」は本行の登録をもって着手可 | 各トラック個別対話 | Head `c0ad9ee` | **R11の範囲に含める**（S11のF2前半を先行実装するため独立Reviewを別途行わない） | S11着手時にB1の棚卸しをF1へ、B2のcalculatorをF2へ引き継ぐ。B2は閾値解決の第2/第3段のみ実装し、第1段(`m_overtime_agreement`)はF1のDDL(V78)後に接続する |

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
