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
| 5 | 0 | `productivity-search-saved-view` | T028〜T033 | **FIX** | R05 Round 4 = FAIL（P0=0/P1=1）。スコープ縮小2件は spec改訂により正当にCLOSED。残1件 (R4-P1-01) は API バインド & sparse update 修正および MockMvc テスト 4 本追加により修復完了。全量テスト 1135/0/0/7 BUILD SUCCESS | S05 productivity-search-saved-view 実装 | Base `e331f41` → Head `f7e5711`（`main`） | R4-P1-01 修正完結、全量 1135/0/0/7 BUILD SUCCESS。未実施: Docker実MySQL smoke / desktop・390px Demo / 検索p95 / Node・JS syntax / F1 Demo完走 | R4-P1-01 の VERIFIED_CLOSED ＋ M task L4 → PASSでWave 0完了、S06/S08解放（発注者判断でS06の条件付き先行着手は可。migrationはV70から採番） |
| 6 | 1 | `bp-company-master-procurement-compliance` | T034〜T040 | `NOT READY` | Wave 0 PASS、G2決定後S06。CRMと並行可 |  |  |  | R06 PASS |
| 7 | 1 | `approval-workflow-internal-control` | T041〜T047 | `NOT READY` | BP/CRM PASS、G7方針記録後S07 |  |  |  | R07 PASSでWave 1完了 |
| 8 | 1 | `crm-contact-opportunity` | T048〜T053 | `NOT READY` | Wave 0 PASS後S08。BPと並行可、V70→V71順merge |  |  |  | R08 PASS |
| 9 | 2 | `order-acceptance-workflow` | T054〜T059 | `NOT READY` | approval PASS後S09 |  |  |  | R09 PASS |
| 10 | 2 | `dispatch-outsourcing-compliance-ledger` | T060〜T066 | `NOT READY` | order PASS、G2確定後S10。attendanceと並行可 |  |  |  | R10 PASS |
| 11 | 2 | `attendance-leave-overtime-compliance` | T067〜T074 | `NOT READY` | order PASS、G6確定後S11。dispatchと並行可 |  |  |  | R11 PASS |
| 12 | 2 | `staffing-capacity-planning` | T075〜T080 | `NOT READY` | dispatch/attendance PASS後S12 |  |  |  | R12 PASSでWave 2完了 |
| 13 | 3 | `external-customer-bp-portal` | T081〜T087 | `NOT READY` | Wave 2 PASS、G3/G8方針後S13 |  |  |  | R13 PASS、security chain先行merge |
| 14 | 3 | `engineer-self-service-portal-v2` | T088〜T093 | `NOT READY` | external portal security merge、G9方針後S14 |  |  |  | R14 PASS |
| 15 | 3 | `accounting-payment-integration` | T094〜T101 | `NOT READY` | portal/order/BP/archive PASS、G4/G9方針後S15 |  |  |  | R15 PASS |
| 16 | 3 | `jp-pint-digital-invoice` | T102〜T108 | `NOT READY` | accounting PASS、G5決定後S16 |  |  |  | R16 PASSでWave 3完了 |
| 17 | 4 | `ai-feedback-learning` | T109〜T115 | `NOT READY` | CRM/proposal/staffing/outcome完了、G10方針後S17 |  |  |  | R17 PASSでroadmap完了 |

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
