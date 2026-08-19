# 17spec中央実行台帳

## S10 dispatch R22 MySQL 0-skip検証未達 / T066 G2実装フェーズ1停止

R10の独立ReviewでR21 canonical payload deltaは`PASS / ACCEPTED_FOR_IMPLEMENTATION`。R22実装フェーズ1の同一Head `99fbed8294dd1a6c320b4413b832f7c7b9292da1`について、ローカル指定実行はDocker daemon起動不能により`0/0/3` skip、同一HeadのCIはDocker check成功後も`1842/1/29/0`でBUILD FAILUREとなった。V102適用失敗とforward-repairの複合index metadata row-count assertion不一致（expected 1 / actual 2。誤定義indexが2列構成のためstatisticsが2行を返すことによる。**R22-P2-02訂正: 当初「Flyway history row数assert」と記載したが、Flyway history成功件数assertは0で成立しており、失敗はindex metadataのrow-count assertion**）が残り、MySQL 0-skip証跡は未成立。R22-P1-01/P1-03は`OPEN / MYSQL_VERIFICATION_PENDING`、**P1-02/P1-05は`FIXED_BY_IMPLEMENTER / BLOCKED_BY_P1-04`、R22-P1-04は`OPEN / CI_REPRODUCED`**、P2-01は`VERIFIED_CLOSED`を維持する。S10は`IN PROGRESS / FAIL`、T066 checkbox未完了、ACTIVE化・本番generate/delivery・production authorization禁止、S12は`NOT READY`を維持する。G2 service/API/UI/security、L1〜L3、Phase A/B、実在証跡、T066 L4はR22全P1のR10 VERIFIED_CLOSED後に限り開始する。

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
| 10 | 2 | `dispatch-outsourcing-compliance-ledger` | T060〜T066 | `IN PROGRESS`（T060〜T065 PASS・T066 M実装完了。**R22全P1 VERIFIED_CLOSED（R24）**・V102基盤受領。外部専門家Review（証跡3）は条件付き確認（P1-1: FM-C-28追加・P1-2: 待遇情報提供の一次source確定が解消条件）。**M PASS条件未達=G2 gate（証跡1: COMPLIANCE_RESPONSIBLE assignment・証跡2: 実actor承認event・証跡3: 資格保有者の実在Review・証跡4: PDF目視・証跡5: T066-HISTORY可否＋P1-1版管理）は人間/外部プロセス待ち**。production authorizationなし、T066 checkbox未完了、S12 NOT READY） | order PASS、V84/V85実在。R19〜R24: V102 schema phase完了（G2 gate schema・operation ledger・forward repair・R22-P1-01〜05全VERIFIED_CLOSED、CI 1842/0/0/0 skip 0）。T066 M: 法務fixture・worker snapshot帳票項目・L4全量（1844/0/0/0・skip 41=Docker gate・CI skip 0）。外部専門家Review対応: P2-1/P2-2（交付期限rule: DEADLINE_DOCUMENT_DELIVERY=開始前日・DEADLINE_DISPATCH_NOTICE=開始後+config猶予日、CI success）実装済み、FM-C-28提案書（hash事前計算済み: 現行10a3fc78…→案(a)適用後e93d71b3…）、証跡1/2記録様式テンプレート、受入チェックリスト作成済み。`GATE-T066-HISTORY`は`TRACKED P2 / production release gate / 未実装・未受入`でS10 PASS/S12開始を阻害しない | S10 dispatch T066 M（G2 gate証跡待ち） | R22 schema phase `16f40e0f` → R23/24 fix `27e44a8e`/`6a8e2b80` → 外部Review対応 `4e1a5fe1`〜`6da34f2f` | L4全量 1844/0/0/0（skip 41=Docker gate・CI skip 0）・CI全commit success（31576512607等）・`git diff --check` PASS | **人間/外部プロセスによるG2 gate証跡取得（証跡1〜5・P1-1版管理判断・P1-2一次source確定）→ R10がM PASS判定 → S10 PASS → S12解放**。S11はT074 M PASS・release済み |
| 11 | 2 | `attendance-leave-overtime-compliance` | T067〜T074 | **`PASS`（S11完結）** | order PASS、G6確定済み。**R11 T074（M）S11最終Review: T074 PASS、S11（T067〜T074）完結**。L4独立再実行 **1702 tests / 3 failures（attendance起因0）/ 0 errors / 38 skipped（Docker）**。browser Demo（desktop/390px）を独立再実行（runId browser-m-20260810202700、SHA-256一致・consoleエラー0）。R2-P2-01（390px）CLOSED。production code変更なし。V91（方式A）・V98（休暇残数台帳、発注者割当）実在。cross-lane NOTE×4: NOTE-R3-06（dispatch V84 fresh経路）、NOTE-R4-03（`project.detail.desc`欠落、scale-300）、NOTE-R4-04（V101予約衝突、scale-300採番）、NOTE-R6-03（dispatch entity/H2不一致）は統合担当OPEN。R2-P2-02（paging）・R6-NOTE-02（scheduler通知宛先）は残件 | S11 attendance-leave-overtime-compliance 実装 | Base `5e29f39` → T067 `93c1ac6` → T068 `b327b1b` → T069 `d395797` → T070 `cc7c15c` → V91方式A `5f362fc`/`b65996f` → T071休暇 `7981e5c`/`6d99658a` → R4 fix delta `85ca62ba` → T072 `840539da` ほか → T072完了 `9be5e5c` → T073 `5c34db26` → T073完了 `11398d9` → R6 fix `62e3d31` → R6転記 `ef4ce72` → T074完了 `93d17017` | R11 T074（M）S11最終Review: L4独立再実行 **1702 tests / 3 failures（attendance起因0・全て既知cross-lane）/ 0 errors / 38 skipped（Docker gate）**、browser Demo再生成証跡一致、`git diff --check` PASS。P0=0/P1=0/P2=1（R2-P2-02）/NOTE=5。HR/法人別規程はATT-GATE-01〜06として本番release gate | **S12（staffing-capacity-planning）はS10 PASS待ちでNOT READY**。統合担当はNOTE-R3-06/R4-03/R4-04/R6-03解消後、CI相当L4×1回（fresh/legacy MySQL smoke含む）を実行。V83/V91/V98を変更・再適用せず、V82を補填しない |
| 12 | 2 | `staffing-capacity-planning` | T075〜T080 | **`PASS`（S12完結・Wave 2完了）** | S10/S11双方PASS（S10=85dfd7bf・S11=93d17017）。T075〜T080全task完了（V103実在）。R1（P1-01〜06）→fix delta（d1d7fac1）→**R2: PASS（P0=0/P1=0/P2=2/NOTE=3）**。R2残P2（P2-02 JS側・P2-08 double-click）は追加fix済み。L4: 364クラス/2029件/0/0/0、MySQL smoke fresh/legacy 2/2（V103 legacy二段upgrade・shape一致）、browser desktop/390px evidence、p95=989ms/heap=306KB実測。中央ledgerへPASS転記（2026-08-16） | S12 staffing-capacity-planning 実装 | Base `85dfd7bf` → T075 `a691f77e` → T076 `ec880114` → T077 `6e0ddfc9` → T078 `0424abd0` → T079 `6ef0108e` → T080 `22fe7d06` → ledger `5246783a` → R1 fix `f5842020`/`d1d7fac1` → R2追加fix（P2-02/P2-08） | R2: P1-01〜06全VERIFIED_CLOSED（P1-04は独立実MySQL実行2/2）。直接回帰284/0/0/0（surefire集計）。P0=0/P1=0 | **Wave 2完了。S13（external-customer-bp-portal）READINESS開始可** |
| 13 | 3 | `external-customer-bp-portal` | T081〜T087 | **PASS（CONDITIONAL PASS: P0=0/P1=0/P2=2 deferred/release gates=G-1〜G-4）** | R3最終判定（2026-08-17、独立Review）。R1のP0×1・P1×3・P2×11は全VERIFIED_CLOSED（R2修正diff検証＋R3独立再実行: 実MySQL smoke 2/2・portal 48/0/0/0・0 skip）。S13-R2-P1-01（BOM）はfix delta d408b3ecでVERIFIED_CLOSED。V104〜V104_4実在（latest=V104.4）。security chain merge済み | S13 external-customer-bp-portal 実装 | Base `009b6965` → Head `d408b3ec`（main=origin/main、全task/fix push済み） | R3: **CONDITIONAL PASS**（handbook §12） | G-1 browser Demo（主実装/本番前）・G-2 法務承認（発注者/本番前）・G-3 DNS/証明書/SMTP（主実装/本番前）・G-4 承認route設定（運用/運用前） | **S14（engineer-self-service-portal-v2）解放可**。S14はG9方針確認後にREADYへ |
| 14 | 3 | `engineer-self-service-portal-v2` | T088〜T093 | **PASS** | T088〜T093 全タスク実装・検証完了（V105〜V105.3、G9推奨既定確定、Round 4 独立再Review指摘6件 R2-P1-01/R1-P1-05/R1-P1-07/R1-P1-09/R1-P1-12/R1-P2-03 全件 VERIFIED_CLOSED。Independent Review R4.2: Base `213658df` → FixHead `32acbd02`、P0=0/P1=0/P2=0、総合判定 PASS） | S14 engineer-self-service-portal-v2 Independent Review R4.2 | Base `213658df` → FixHead `32acbd02` | Targeted 38/0/0/0, Fast 2327/0/0/0, MySQL 3/0/0/0, Browser 1/0/0/0, 0 skip, git diff --check PASS（外部ITA/他spec dirty差分はFixHeadに含まない）。OPEN issueなし。S15/次Wave開始可。CI Browser artifact hashは本番前条件 | R14 PASSでS15解放 → **S15開始可** |
| 15 | 3 | `accounting-payment-integration` | T094〜T101 / R4-T01〜R4-T08 / R5-R1 / R6-R1 | **`FIXED_PENDING_REVIEW / S16 BLOCKED`** | Stage A SpecHead Revision 6 PASS（`e0d8a96f`）。Independent Re-Review R5はBase `7219fd2a` → FixHead `fe69dd3a`、P0=1/P1=0/P2=0、総合FAIL。R4のP1×5はVERIFIED_CLOSED。R5-R1でV105.4を通常Flyway locationから除外し、Flyway前preflight runbookとV106.2-only repairへ収束した。R6では公開済みV106.2のコメント変更によるchecksum P0を検出し、`fe69dd3a`版へbyte-for-byte復元した。独立Review合格前のS16解放はしない | S15 R6 migration P0是正・Review Packet収束 | R5 review Base `7219fd2a` → prior FixHead `fe69dd3a` → R5-R1 fix `d6b8c307` → R5 Packet `951ac238` → R6 implementation `186b746c` → R6 Packet同期commit | Static 38/38、実MySQL V106.2旧公開history回帰 2/2、旧V106.1 historyからV106.2のみ1件、V105.4 history 0件。既存Gate証跡: Fast 2435/0/0/0、MySQL 57/0/0/0、Performance 1/0/0/0、Browser 1/0/0/0、Backup SUCCESS（RPO 60秒、RTO 14400秒、secret scan 0）。全skip 0、`git diff --check` PASS | `GATE-S15-FREEE-PROD`と実2 JVM 401競合はRelease Gate。R6修正後の独立再Review開始条件は最終push済みHead/origin一致＋tracked worktree clean。S16は独立Review PASSまでBLOCKED |
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
  この2026-08-09時点の履歴ではV80/V81/V83実在、S10=V84、S11=V83＋V91、S12〜S17=V99〜V104としていた。
  2026-08-11の現行正本はS10=V84/V85実在＋V102 follow-up、S11=V83/V91/V98実在、S12〜S17=V103〜V108である。
  common V99は永久欠番、V100はmigration-dev実在でcommon再利用禁止、common V101は既存用途維持。V59/V72/V82も永久欠番。
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
