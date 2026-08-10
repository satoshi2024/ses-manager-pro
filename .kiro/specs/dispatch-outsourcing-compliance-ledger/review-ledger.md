# dispatch-outsourcing-compliance-ledger review ledger

## 現行判定

`R10 Round 16: T060 PASS / T061 F1 PASS / T062 F2 PASS / T063 A1 PASS / T064 B1 PASS（P0=0/P1=0/P2=0）。R15-P1-01〜04（download再mask・party snapshot化・mapping scope・営業動線）をVERIFIED_CLOSED。T065解放可、T066 M（帳票全項目化＋G2 gate）未達、production authorizationなし`。

**R10 Round 15: T060〜T063 PASS維持。T064 B1 FAIL（R15-P1-01〜04）→ fix再提出済み・再Review待ち。T065停止、T066 M/本番gate未達、production authorizationなし**。

**R10 Round 14: T060〜T063 PASS確定。T064 B1実装提出済み（R10 Round 15確認待ち）。T065解放可、T066 M/本番gate未達、production authorizationなし**。

**R10 Round 13: T060 PASS / T061 F1 PASS / T062 F2 PASS / T063 A1 PASS（P0=0/P1=0/P2=0）。R12-P1-01（SaveDto 2列削除・UI保存400解消）・R12-P2-01（contact顧客一致）をVERIFIED_CLOSED。T064〜T065解放可、T066 M/本番gate未達、production authorizationなし**。

**R10 Round 12: T060/T061/T062 PASS維持。T063 A1 FAIL（R12-P1-01: SaveDto残留retention/legalHoldによるUI保存400、P2-01: contact顧客一致未検証）→ fix再提出済み・再Review待ち。T064〜T065停止、T066 M/本番gate未達、production authorizationなし**。

**R10 Round 11: T060 PASS / T061 F1 PASS / T062 F2 PASS（P0=0/P1=0）。R10-P1-01（V84誤字・34c68f7バイト復元）・R10-P1-02（null-profile fail-open修正）をVERIFIED_CLOSED。T063 A1実装提出済み（R10 Round 12確認待ち）、T064〜T065解放可、T066 M/本番gate未達、production authorizationなし**。

**R10 Round 10: T060 PASS / T061 F1 PASS / T062 F2 FAIL（R10-P1-01: PASS済みV84誤字4行・復元済み、R10-P1-02: null-profile fail-open・修正済み）→ fix delta再提出済み・再Review待ち**。T062のcore実装・既存4 rule golden 12/12はclean独立実行で確認済み。T063〜T065はF2 PASS後に再開、T066 Mは未着手。production release/apply authorizationなし。

**R10 Round 10: T060 PASS / T061 F1 PASS / T062 F2 FAIL（R10-P1-01: PASS済みV84誤字4行・復元済み、R10-P1-02: null-profile fail-open・修正済み）→ fix delta再提出済み・再Review待ち**。T062のcore実装・既存4 rule golden 12/12はclean独立実行で確認済み。T063〜T065はF2 PASS後に再開、T066 Mは未着手。production release/apply authorizationなし。

**R10 Round 5 packet: T060 PASS / R4-P1-01 VERIFIED_CLOSED / T061 F1 FAIL（R5 P1×5、docs fix plan提出済み）**。R10 Round 4はT060をPASS（R1-P1-01 VERIFIED_CLOSED、R1-P1-02 VERIFIED_CLOSED_BY_DECISION_CHANGE）と判定し、R4-P1-01もVERIFIED_CLOSEDとした。Round 5はT061のDDL/entity/H2/MySQL/direct regressionをread-only独立確認し、mapping 1対1不足、snapshot履歴欠落、legacy/partial未検証、明示NULL更新漏れ、PII ownership未分離の5 P1をOPENとした。T061 checkboxは未完了へ戻し、field-mapping §4、design §5.5/§6.2、tasks test matrixを先に改訂する。production release/apply authorizationは付与しない。S11 attendanceの別track差分は混入させない。

**R10 Round 5 docs-only 再Review（Head `3891c0e`）**: NOT ACCEPTED / FAIL継続。P0=0、新規Issue=0。R5-P1-01（旧mapping/gate判断残存）、P1-02（content hashとidempotency混同・worker current未定義）、P1-04（mutable NULL clearとappend-only訂正の競合）がOPEN。R5-P1-03（fresh/legacy/partial/repair/forward-repair計画）とR5-P1-05（T061/T063/T064 ownership分離）の計画は受理。T060 PASS維持、T061 FAIL、T062〜T066停止。本deltaは上記3根本原因の文書再修正（rework 3追補）であり、V1/V84/code fixは開始しない。同じIssue IDで再提出する。

**R10 Round 6 docs-only 再Review（Head `53ec7ef`）**: FAIL継続（P1はcode fix待ち）だが、R5-P1-01/02/04の**文書修正は受理**されT061 code fix着手が許可された。P0=0、P1=5（OPEN、docs受理済み）、P2=2、NOTE=1。新規P2: R6-P2-01（tasks.md test matrix不整合）、R6-P2-02（PROFILE_TYPED未使用）。NOTE: R6-NOTE-01（planned introduction table境界・recipient列名の一意化）。T060 PASS維持、T061 FAIL、T062〜T066停止。P1はcode fix＋F1 direct regression証跡が揃うまでOPEN維持、production release/apply authorizationなし。R6 P2/NOTEは本ledger追補と同一deltaで解消済み。

R4-P1-01のunblock fixとして、reserved <= latestを検出するguard、CI/TestcontainersのFlyway履歴read-only証跡、V83実在/V82欠番の正式decision、V84〜V90の予約資料同期、legacy fixtureを追加した。`SpecDispatchConsistencyTest`は9/0/0/0 PASSへ復帰し、R10独立確認でR4-P1-01がVERIFIED_CLOSEDとなった。T061の旧実装はレビューでFAILとなったため、schema decision matrixとdirect regression matrixを確定してからV1/V84/H2/entityを再同期する。

## T060 証跡

| task | requirements | 変更file | test / Demo | base / head | risk / rollback |
|---|---|---|---|---|---|
| T060 | R1.1〜R1.4, R2.1〜R2.2, R3.1〜R3.4, R4.1〜R4.2, R5 | `.kiro/specs/dispatch-outsourcing-compliance-ledger/field-mapping.md`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md`（T060 checkboxを未完了へ戻した）。production code/DDL/migration/SecurityConfigは変更しない | L0/direct regression **PASS**: form mapping 96行、SRC-E ⑱=1行、SRC-L ④=1行、根拠なし2026-10 mapping行=0行、全mapping行11列、version/effective period、T060 3文書の`git diff --check` exit 0。R10 Round 2はP1-01をVERIFIED_CLOSED、P1-02をOPEN / APPROVAL_REQUIREDと判定。社内承認Demoは証拠未取得のため未完了 | R10固定範囲 Base `f8adbc028ae0e260ed8123d0405901febee16f5a` → original Head `8fdadb4af51d224d7659d377196b6774d46dea1f` → Packet Head `be2fb190dcdf6d13286694ebe3a6a31cb477fb09`。R1 fix Head `0909acb867577217b91de1bc64edd581f4da403c`、R10 Round 2確認Head `cddbc325c0793fdb41ccb73a3f976de271b34093` | T061/V82へ進めない。productionでは未指名/未確認/資格・根拠不足をfail-closed。rollbackはR1 fix commitをrevertし、production変更は存在しないためDB rollback不要 |
| T060 / R4 unblock delta | R1.1〜R5、platform-invariantsのMigration順序、parallel-execution-planの着手時latest再確認 | `src/test/java/com/ses/migration/SpecDispatchConsistencyTest.java`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md`, `.kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md`。migration/DDL/production code/tasks checkboxは変更しない | `mvn -B -Dtest=SpecDispatchConsistencyTest test`: **8 tests, failures 1, errors 0, skipped 0**。failureは期待された安全側検出で、`S10 dispatch-outsourcing-compliance-ledger の予約V82が実在最新V83以下`を報告。`git diff --check`は対象差分で確認する。ローカル既定DB read-only: `flyway_schema_history` latest successful V74、V82/V83なし。非ローカル環境は未確認 | R4固定範囲 Base `1fd0f7492ab46388c961e2e721ccdedd416929c4` → Decision fix `a1f5e8e8c5b8b559520109a43c61e59f56ab8243` → Packet Head `87a901375ec94dcb7093fdd2e863ed1b8b109a56`。guard fix commit `066a61f9584ab4d9bfe9c3dea9ed3d4ec1b8379c` → ledger sync commits `8b772adcb801c013d347ca097ac8100c544d0ae4`, `bfc4ca3f1c0fdb8d7b0fac4507527f2090f4dc4f`, `23d6845e2284793404e0910948f92e2da48d2b96`, `79f63db0bc8fae23698b90517c4c35a621eb59b7` | R4-P1-01がOPENの間はdeploy freeze、T061/V82/production変更を開始しない。中央ledgerはREADY/R10 PASSからIN PROGRESS/R4-P1-01 OPENへ同期した。rollbackはこのテストガードとledger追記のrevertのみで、DB変更はない。S11 attendance変更はstageしない |
| T060 / R10 P2・environment packet | R1.1〜R5、R4-P1-01 environment evidence gate | `.kiro/specs/dispatch-outsourcing-compliance-ledger/field-mapping.md`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/environment-evidence-packet.md`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md`。T061/V82/productionは変更しない | P2最小修正: field-mappingの古い「R10再Review待ち」をT060 PASS/R4-P1-01環境証跡待ちへ更新。`git diff --check` exit 0。local-default read-only JDBC: V82/V83 target rows=0、latest successful V74 / success=true / installed_on=`2026-08-02 00:35:29` / checksum=`559443363`。CI/Testcontainers、staging、production、other legacyはowner証跡未提出 | P2 fix Base `4dadfb30258f5d21246fdbe48783addb7bf79171` → P2 fix `9cac72a9c56f109fce359447df9a799f8639e295`。environment packet commit `6e6896a7792cf81609da7800525ca59f47ac8353` | packetがINCOMPLETEの間は正式migration decisionを作成せず、採番変更、V82作成、T061/DDL/production変更を開始しない。S11 dirty変更はstageしない |
| T061 / F1 DDL | R1.1〜R1.4、R1.2/R1.3派遣・準委任固有項目、R2.2、R3.1/R3.4、R4.1〜R4.2、R5 snapshot/fail-closed | `src/main/resources/db/migration/V1__create_tables.sql`, `V84__dispatch_outsourcing_compliance_ledger.sql`, `src/main/java/com/ses/entity/{Workplace,ContractComplianceProfile,ComplianceFinding,DocumentDelivery}.java`, 対応mapper 4件、H2専用schema/engineer-schema/application-test、direct regression | prior direct testsはMySQL fresh 1/0/0/0、H2 1/0/0/0、SpecDispatch 9/0/0/0、Contract 48/0/0/0、Compliance API 1/0/0/0、skip 0だが、R10 Round 5はT061を**FAIL**とした。mapping→typed schema/history coverage、snapshot append-only、V83 legacy/partial/repair、値→NULL、PII ownershipの5 P1をclosure条件に追加 | Base `856ab1faf09f07abcd7a5b34453a5037173ce553` → implementation Head `e7f7f19434e0e45d54888d6b468e9d8704c6056f` → docs fix/ledger sync Head（本commit）。S11 attendance dirty変更はstageしない | 現行実装はreview-readyではない。production release/apply authorizationなし。R10がRound 5 fix planを受理するまでcode fixを開始せず、P1 VERIFIED_CLOSEDまでT061 checkboxを戻さずT062/A1/B1/B2を開始しない |

## R10 Issue Register（履歴スナップショット）

| issue ID | Review status | Implementer status | violated / location | fix evidence | verification / next action |
|---|---|---|---|---|---|
| dispatch-outsourcing-compliance-ledger-R1-P1-01 | **VERIFIED_CLOSED** | **FIXED_BY_IMPLEMENTER** | T060 Objective/L0全項目網羅、R2.1／field-mapping.md SRC-E section・SRC-L section・2026-10行 | SRC-E「社会保険の加入手続きが完了していない場合の理由（⑱）」とSRC-L「60歳以上か否かの別（④）」を独立mapping行へ追加。4公式PDFに確認できない2026-10通知行を削除し、一次source特定gateへ戻した | R10 Round 2がmapping 96行、全mapping行11列、SRC-E⑱=1、SRC-L④=1、根拠なし2026-10行=0、`git diff --check` exit 0を確認。新規P0/P1なし |
| dispatch-outsourcing-compliance-ledger-R1-P1-02 | **VERIFIED_CLOSED_BY_DECISION_CHANGE** | **FIXED_BY_DECISION_CHANGE / REVIEWED** | T060 Demo、tasks.md、review-ledger.md／社内責任者の実actor・承認日時・mapping version/hash・source版・status証跡 | R10 Round 4のDecision fix `a1f5e8e8c5b8b559520109a43c61e59f56ab8243` が、自然人の事前固定ではなくG2-DEV-GATE、role lifecycle、runtime assignment、本番fail-closedの分離を確定。対象Packet Head `87a901375ec94dcb7093fdd2e863ed1b8b109a56`、mapping blob `32fdb05b00509aab8002a68ba9fa728db8fab36c`をR10が確認した | R10 Round 4がVERIFIED_CLOSED_BY_DECISION_CHANGE。runtime role assignment、実actorによる承認event、資格/根拠確認はM / 本番設定gateとして残し、開発T061をこのP1で停止しない。ただしR4-P1-01が別途OPENのためT061/V82は開始不可 |
| dispatch-outsourcing-compliance-ledger-R4-P1-01 | **OPEN** | **FIXED_BY_IMPLEMENTER / ENVIRONMENT_EVIDENCE_REQUIRED** | `parallel-execution-plan.md:63,70-74`、customer-product-expansion README:75、dispatch `tasks.md:9` のV82→V83順序、out-of-order禁止、着手時latest再確認。既存の`SpecDispatchConsistencyTest`は予約番号と同値の実在だけを検査し、reserved `<=` latestを検出できなかった | `SpecDispatchConsistencyTest.予約Migration番号が実在スクリプトと衝突しないこと()`へ`reserved <= latest`の検出を追加。現repoはV83が実在しV82が未実在であるため、テストがS10の予約V82を実在最新V83以下として報告する。ローカル既定DBはread-only確認済みでlatest successful V74、V82/V83履歴なし。staging/production等の非ローカル環境の適用状態は証明できず、推測・捏造しない | deploy freezeを継続し、環境ごとのread-only `flyway_schema_history`（V82/V83のversion、success、installed_on、checksum）を取得する。全環境でV83未適用ならV82を先にmerge/applyしてからV83へ進む順序を固定する。1環境でもV83適用済みなら、予約表・README・parallel plan・全派工資料を同一decisionで次の未使用番号（実在latestに応じたV84以降）へ繰り上げ、legacy fixtureを追加し、guardがPASSすることを確認する。証跡・decision・direct regression PASSが揃うまでVERIFIED_CLOSEDにしない |

**旧判定（R10 Round 4後。Round 5で上書き）**: R4-P1-01は`VERIFIED_CLOSED`、R4-P2-01はprovenance表記を訂正済み。T061/V84は開始可としていたが、Round 5のT061/F1 FAILと5 P1 OPENにより、T061 checkbox未完了・後続task停止へ戻した。production release/apply authorizationなし。

## R10 Round 3 判定

- 判定: `FAIL: open blockers=dispatch-outsourcing-compliance-ledger-R1-P1-02`
- P1-01: `VERIFIED_CLOSED`維持。新規P0/P1なし。
- P1-02: `OPEN / APPROVAL_REQUIRED`維持。対象mapping blob `80fe732df1553f5d9a21b6776d8288419f29d9cc` と一致する実actor、権限、承認status、承認日時、公式source版を含む証拠が未提出。
- T060/F1: `[x]`、T061/F1: `[ ]`、T061/V84: 開始可（production authorizationなし）。
- c34ba6f以降のS10 fix delta・承認eventなし。S11 attendanceの追加commit/dirty変更は本specのReview対象外。
- 次回Reviewは承認証拠提出後のみ。証拠なしの再Review依頼はしない。

## R10 Round 4 判定とR4-P1-01 unblock

- R10 Round 4のT060判定はPASS。R1-P1-01は`VERIFIED_CLOSED`、R1-P1-02は`VERIFIED_CLOSED_BY_DECISION_CHANGE`。mapping 96行、全mapping行11列、SRC-E⑱=1、SRC-L④=1、根拠なし2026-10行=0、SpecDispatchConsistencyTest 8/8、`git diff --check` exit 0をR10が確認した。
- 新規R4-P1-01はOPEN。PacketのBase/HeadにV83が実在しV82が不存在で、V82→V83の予約順序と矛盾する。現在のmainでもV83 scriptが存在し、V82は未作成であるため、T061/V82作成を開始しない。
- direct regressionは、guard追加後に現状態を8 tests / 1 failure / 0 error / 0 skippedで検出した。これは誤検知を隠さずdeployを止めるための期待されたfailであり、R4-P1-01の環境証跡・順序決定が未完了であることを示す。
- ローカル既定DB（2026-08-09 read-only確認）の`flyway_schema_history`は成功済み最新V74、V82/V83なし。staging/production等の環境情報は未取得であり、全環境のV83未適用証明にはならない。環境ownerはread-only証跡を提出するまでdeployを凍結する。
- このdeltaはT060文書、DDL、migration、SecurityConfig、production codeを変更しない。S11 attendanceの変更は除外した。

## R10追加指示: P2最小修正とenvironment evidence packet

- P2は完了。`field-mapping.md`の状態を`PROVISIONAL_REVIEWED / T060 COMPLETE（R10 T060 PASS、R4-P1-01は環境証跡待ち）`へ最小修正した。T060 PASS、R4-P1-01 OPEN、中央ledgerのIN PROGRESS状態とは矛盾しない。
- `environment-evidence-packet.md`を作成し、local-defaultのread-only結果と、CI/Testcontainers・staging・production・other legacyの未提出状態を秘密情報なしで記録した。全environment証跡packetは未完了である。
- local-default結果はV82/V83 target rows=0、成功済み最新V74、`success=true`、`installed_on=2026-08-02 00:35:29`、`checksum=559443363`。executor/owner roleは`主実装AI（local read-only verifier; environment owner approval not claimed）`と明記した。repo内に非localのenvironment owner、接続先、credentialは存在しない。
- environment inventoryはrepoで確定可能なlocal-default、CI/Testcontainers、およびR10要求のstaging、production、other legacy/deploymentを区分として固定した。非localの正式environment名とowner ID/roleは未提出であり、未確認environmentを不存在やV83未適用とは扱わない。
- 環境ownerへ要求するpacket形式は、environment名、capture時刻、V82/V83のversion・success・installed_on・checksum、latest successful migration、owner/実行役割である。秘密情報は提出しない。
- 全environment証跡が揃うまで、V82先行または採番繰上げの正式decisionを推測で作成しない。予約表・全派工資料・legacy fixture同期および`SpecDispatchConsistencyTest` PASSも、そのdecision後に行う。

## R10 progress acknowledgement

- R10の進捗判定を受領し、`dispatch-outsourcing-compliance-ledger-R4-P1-01` は **OPEN / ENVIRONMENT_EVIDENCE_REQUIRED** のまま維持する。
- 非local environmentの証跡は未完了であり、deploy freeze、T061/V82/DDL/production変更停止、正式migration decision未作成を継続する。
- local-defaultのexecutor/owner role追記とinventory scopeの明文化は完了したが、CI/Testcontainers・staging・production・other legacyのowner証跡は未提出である。
- 全environmentの同一schema証跡、正式decision、予約表/全派工資料/legacy fixture同期、`SpecDispatchConsistencyTest` PASSが揃うまで、正式独立Reviewは開始しない。

## R10 Round 5 T061/F1 判定と単一fix plan（2026-08-09）

R10は固定範囲 Base `856ab1faf09f07abcd7a5b34453a5037173ce553` → implementation Head `e7f7f19434e0e45d54888d6b468e9d8704c6056f` → ledger/current Head `8e21d28d5e64bbfda00a84e9c1079be4d408aa89`をread-only確認した。T060 PASS、R4-P1-01 VERIFIED_CLOSED、R4-P2-01 VERIFIED_CLOSEDを維持し、T061/F1をFAIL、T062/A1/B1/B2を開始不可とした。Round 5はRound 4以降の収束規則に従い、code fixより先にfield-mapping §4、design §5.5/§6.2、tasks test matrixを改訂する。

| issue ID | status / violated | 根本原因と最小fix plan | direct regression / closure条件 |
|---|---|---|---|
| dispatch-outsourcing-compliance-ledger-R5-P1-01 | **OPEN / R1.1-R1.3, R2.2, T060 mapping 1対1** | 96 stable row IDをcanonical schema manifestへ一意解決した。旧候補名、旧単一制限日、保存形状の一括構造化データ選択、GATE-T060-ROLEによるfield semantics判断はofficial rowから除去し、typed column/history/T066 semantics gateへ分離した | F1-MAP-01、2種制限日、SRC-E⑱、SRC-L④、料金、source/client苦情、worker-specific/反復historyの96行coverage。stale token scan 0 |
| dispatch-outsourcing-compliance-ledger-R5-P1-02 | **OPEN / R1.4, R5, design §5.4/§5.5** | content hashとoperation idempotencyを分離。contract snapshotはversionのみ一意、同じoperation retryは1行、新operationでA(v1,hA)→B(v2,hB)→A(v3,hA)を許可。worker stateにcurrent pointer/version/FK/CASを具体化し、direct mutation拒否と承認済みretention purge境界を定義した | F1-SNAPSHOT-01/02。retry 1行、A/B/A 3version、CAS 1勝、2 worker独立current、orphan 0、direct UPDATE/DELETE拒否 |
| dispatch-outsourcing-compliance-ledger-R5-P1-03 | **OPEN / platform-invariants DDL DoD、T061 migration acceptance** | MySQL smokeのfreshだけでなく、exact V83 legacy、partial schema、failed history/repair、post-apply forward repairを別fixture/test IDで固定する | F1-MYSQL-FRESH-01、F1-MYSQL-LEGACY-01、F1-MYSQL-PARTIAL-SCHEMA-01、F1-MYSQL-FAILED-HISTORY-REPAIR-01、F1-MYSQL-POST-APPLY-ROLLBACK-01をskip 0 |
| dispatch-outsourcing-compliance-ledger-R5-P1-04 | **OPEN / design §5.1明示NULL、tasks T061** | mutable currentのnullable列だけをFieldStrategy.ALWAYS＋full DTOで値→NULLにする。historyはclear inventoryから除外し、CORRECTED/CANCELLED、supersedes_event_id、correction_reason等を持つ新event INSERTで訂正する。旧行は不変 | F1-NULL-01、F1-HISTORY-CORRECTION-01。current NULL、field省略拒否、CAS rollback、旧history不変、新event、asOf最新解決 |
| dispatch-outsourcing-compliance-ledger-R5-P1-05 | **OPEN / R4.1-R4.2、T061 PacketのPII scope claim** | T061はportal/AI直接公開0のconsumer scan、T063はdetail/list/count、T064はCSV/Excel/PDF/downloadのfield allow-list/maskを担当する。Demo ownershipを分離済み | F1-PII-OWNERSHIP-01をT061、detail/list/countをT063、CSV/Excel/PDF/downloadをT064で各々証明 |
**R5 docs-only response status**: 上記5件は本同期では`OPEN`のまま。今回の変更は決定・test matrix・task ownershipの具体化だけで、V1/V84、production code、SecurityConfig、DDL、T061 checkbox以外の実装は変更しない。R10がfix planを受理するまでcode fixを開始せず、R10 VERIFIED_CLOSED前にT062/A1/B1/B2へ進まない。

### R5 docs-only rework 3（再提出内容、P1はOPEN維持）

- **R5-P1-01**: 公式mapping 96行をstable IDとcanonical resolution codeへ固定し、各official rowのDB column cellを専用typed columnまたは指定historyへ置換した。旧候補名、旧単一制限日、保存形状の一括構造化データ選択、GATE-T060-ROLEによるfield semantics判断はactive mappingから除去した。料金・2種制限日の法的意味と条件付き表示だけをGATE-T066-FIELD-SEMANTICSへ残した。
- **R5-P1-02**: snapshot_versionだけをcontract単位の一意キーとし、snapshot_hashはcontent indexに限定した。retryはoperation_idとexpected current versionで冪等化し、同じoperationは同じresultを返す。新operationのA(v1,hA)→B(v2,hB)→A(v3,hA)を保持する。worker snapshot/stateの具体的なtable、current pointer、FK、CAS、orphan rollback、direct UPDATE/DELETE拒否、承認済みretention purgeを定義した。
- **R5-P1-04**: mutable currentのclear inventoryとappend-only history correction protocolを分離した。currentだけをFieldStrategy.ALWAYS＋full DTOで値→NULLにし、historyはevent_type、supersedes_event_id、correction_reason、actor、effective interval、asOf keyを持つ新eventで訂正/取消する。F1-NULL-01とF1-HISTORY-CORRECTION-01を分離した。
- **R5-P1-03 / R5-P1-05**: 既提出のlegacy/partial/repair matrixとT061/T063/T064のPII ownership分離は維持する。Issue statusはR10確認前のため5件すべてOPEN。
- **再Review指摘（R10 Round 5 P1×3 OPEN）への追補**: `t_contract.work_location`・`t_contract.job_description`・`official_typed_field`等の旧候補列を§3の全mapping行から除去し、§3.1〜3.4の列ヘッダを「DB column候補」から「DB column（F1 canonical resolution）」へ変更、§3冒頭に「§3.5 manifestと§4を正本とする」注記を追加した。派遣料金の画面位置を「契約金額」から「compliance profileの派遣料金欄（売上/粗利列とは分離）」へ訂正し、独立要否の混同を解消した。旧単一`limitation_date`の文言を§3.5/§5.1へ統一し、`GATE-T060-RETENTION`は`GATE-T066-RETENTION`へ移管（FM-L-30行・§4 RETENTION_METADATA行・§6 gate表・本ledgerのM/本番gate節を同期）。
- 今回はspec docs、tasks、review-ledgerだけを変更し、V1/V84、production code、SecurityConfig、test source、T061 checkboxは変更しない。R10が3件のfix planを受理するまでT061 code fix、T062/A1/B1/B2、production変更を開始しない。

## R10 Round 6 判定とR6 P2/NOTE同期（2026-08-09）

R10 Round 6はHead `53ec7ef`（docs 4文書のみ）をread-only確認し、**R5-P1-01/02/04の文書修正を受理、T061 code fix着手を許可**した。P0=0、P1=5（OPEN、docs受理済み）、P2=2、NOTE=1。T060 PASS維持、T061 FAIL継続、T062〜T066停止継続。P1 statusはcode fix＋direct regression証跡（F1-MAP-01、F1-SNAPSHOT-01/02、F1-NULL-01、F1-HISTORY-CORRECTION-01、F1-MYSQL-FRESH/LEGACY/PARTIAL-SCHEMA/FAILED-HISTORY-REPAIR/POST-APPLY-ROLLBACK-01、F1-PII-OWNERSHIP-01、skip 0）が揃うまでOPEN維持、T061 checkboxは未完了のまま。production release/apply authorizationなし。

- **R6-P2-01**: tasks.mdのF1テスト要件がdesign §6.2と不一致（`F1-NULL-02`参照・`F1-PII-OWNERSHIP-01`未記載）だったため、design §6.2のtest ID列へ同期した（F1-NULL-01へ訂正しF1-PII-OWNERSHIP-01を追加）。ledger内のF1-NULL-02表記も正本へ合わせた。
- **R6-P2-02**: §3.5 resolution tableの`PROFILE_TYPED`が96 stable IDのいずれにも未使用かつ§4 canonical tableに不在だったため削除し、§3.5と§4のresolution code表を一致させた。
- **R6-NOTE-01**: `PLANNED_INTRODUCTION_TERMS`/`PLANNED_INTRODUCTION_HISTORY`のtable境界を確定した（terms=予定労働条件sub-field列、history=紹介時期・採否・非採用理由の反復行でtermsを参照）。`recipient_scope`表記をdesign §1の`recipient_contact_id`へ統一し、§3.3/§3.5/§4の全cellを同期した。V84列名はcode fix時にF1-MAP-01で照合する。
- 本deltaはdocs 3文書（field-mapping/tasks/review-ledger）のみ。V1/V84/code/test未変更、T061 checkbox未変更。

## R10 Round 7 判定とR7-NOTE-01対応（2026-08-09）

R10 Round 7はHead `10fb5d3`（docs 3文書のみ）を再確認し、**R6-P2-01/02・R6-NOTE-01をVERIFIED_CLOSED**とした。新規P0/P1なし。新規NOTE 1件（R7-NOTE-01）: §3.1 FM-C-27行（紹介予定派遣の予定労働条件）のsnapshot・asOf規則列が「append-only history」表記のままで、`t_planned_introduction_terms`（current条件のsub-field列）と不整合。§3.5/§4を正本とする閲覧表のためF1-MAP-01を誤導しないが、code fixと同一差分での1行訂正が指示された。

- **R7-NOTE-01**: FM-C-27行のsnapshot・asOf規則列を「契約・交付時点のtyped snapshot/asOfを保存し、current masterの変更で過去帳票を変えない」へ訂正した。§3.2 FM-E-22行（保険/賃金/就業場所/喫煙措置）と同じ契約に統一。
- 本deltaはdocs 1文書（field-mapping）+ledger追記のみ。V1/V84/code/test未変更、T061 checkbox未変更。S11 attendance dirty変更はstageしない。
- 次のdelta: **T061 code fix**（V1/V84/H2/engineer-schema/entity/mapper/MySQL smoke再同期＋F1-MAP-01/SNAPSHOT-01/02/NULL-01/HISTORY-CORRECTION-01/MYSQL-FRESH/LEGACY/PARTIAL-SCHEMA/FAILED-HISTORY-REPAIR/POST-APPLY-ROLLBACK-01/PII-OWNERSHIP-01、skip 0、Demo A/B/A・worker独立・NULL clear・history訂正）。P1×5はcode fix証跡が揃うまでOPEN維持。

## T061 F1 code fix delta（2026-08-09）

R10 Round 7の「次はT061 code fix deltaを提出すること」に従い、R5契約（field-mapping §3.5/§4、design §5.5/§5.6、tasks.md T061）をV1/V84/H2/entity/mapper/MySQL smokeへ同一差分で再同期した。

**変更file**:
- `db/migration/V1__create_tables.sql`: dispatch sectionをR5 shapeへ置換（旧`limitation_date`/`worker_limitation_date`/`snapshot_json`等を除去し、typed列・2種制限日・current pointerを追加）。drop listへ新table 13件を追加。
- `db/migration/V84__dispatch_outsourcing_compliance_ledger.sql`: 全面再同期。snapshot UNIQUE(contract_id,snapshot_version)・hash非一意索引、worker snapshot/state（FK/CAS）、operation table（operation_id一意）、10 history table（event correction protocol）、finding/delivery拡張、conditional ALTER/FK（partial path）、append-only拒否trigger 24件（DROP IF EXISTS＋CREATEの冪等パターン）。
- entity 18件＋mapper 18件: ContractComplianceProfile/DocumentDelivery更新、ContractComplianceSnapshot/WorkerSnapshot/WorkerState/ComplianceSnapshotOperation/ComplianceWorkCalendar/ComplianceComplaintHistory/EmploymentStabilityHistory/TrainingHistory/CareerConsultingHistory/PlannedIntroductionTerms/PlannedIntroductionHistory/DirectHireDisputeHistory/NotificationDifferenceHistory/LedgerWorkSnapshot新規。
- `sql/schema-dispatch-compliance-h2.sql`: R5 shapeへ再生成（drop list付き、H2 dialect、document/contact/sys_user/engineer FKとtriggerはmapper境界で担保）。
- `sql/engineer-schema-h2.sql`: dispatch sectionをR5 shapeへ同期（S11 attendance変更は保持）。
- test: DispatchComplianceSchemaH2Test再書、F1SnapshotWriteProtocolTest/F1NullAndHistoryCorrectionTest/F1MapManifestTest/F1PiiOwnershipScanTest新規、FlywayDispatchComplianceSchemaSmokeTest再書、FlywayV84LegacySchemaSmokeTest/FlywayV84PartialSchemaSmokeTest/FlywayV84FailedHistoryRepairSmokeTest/FlywayV84PostApplyRollbackSmokeTest新規。

**実行test（L1〜L3定向・直接回帰、skip 0）**: `mvn -B -Dtest=<上記17クラス> test` → **127 tests / failures 0 / errors 0 / skipped 0 / BUILD SUCCESS**。内訳: F1-MAP-01 1、F1-SNAPSHOT-01/02 2、F1-NULL-01＋HISTORY-CORRECTION-01 2、PII-OWNERSHIP-01 1、MYSQL-FRESH 1、MYSQL-LEGACY 1、MYSQL-PARTIAL-SCHEMA 1、MYSQL-FAILED-HISTORY-REPAIR 1、MYSQL-POST-APPLY-ROLLBACK 1、SpecDispatchConsistencyTest 9、MigrationScriptIntegrityTest 27、ContractServiceImplTest 48、ComplianceApiControllerTest 1、MobileResponsiveLayoutTest 25、JsSyntaxCheckTest 1、MessageBundleConsistencyTest 4。`git diff --check` exit 0。

**Demo証跡（SQL/H2・MySQL実測）**: profile snapshot A(v1,hA)→B(v2,hB)→A(v3,hA)を3 version保持、current pointerはv3、v1不変。同じoperation_idのretryはUNIQUE拒否で1行。2 workerのcurrent pointerは独立しCAS競合1勝、失敗txはsnapshot/pointerを全rollbackしorphan 0。current列の値→NULLが保存され旧値残存なし、snapshotは不変。history訂正はCORRECTED/CANCELLED新event（supersedes_event_id/correction_reason付き）で旧行不変、asOfは最新有効event。snapshot/history tableはDB triggerで直接UPDATE/DELETE拒否（MySQL実測）。

**Rollback**: 本deltaはV84 rewriteを含むが、V84は未release（production/staging未適用、R10確認済み）のため、commit revertのみでDB rollback不要。S11 attendance dirty変更（V91等）はstageしていない。

**境界**: SecurityConfig/UI/controller/service/i18n/sidebarは未変更。T062以降のtaskへは進めていない。production release/apply authorizationなし。T061 checkboxはR10 VERIFIED_CLOSEDまで未完了のまま。

## R10 Round 8 判定とR8 fix delta（2026-08-09）

R10 Round 8はHead `b9b91f9`をread-only＋独立実行（H2/docs系43/0/0/0、MySQL 5形状5/0/0/0、skip 0）で確認した。**R5-P1-02/03/05はVERIFIED_CLOSED**（snapshot protocol・worker state・operation idempotency・immutability trigger・5形状migration・PII scan成立）。R5-P1-01/04はOPEN維持。新規: **R8-P0-01**（FM-C-12/E-12/L-18の休憩保存先欠落。F1MapManifestTestが欠落を正本化、法定必須項目）、**R8-P1-01**（FieldStrategy.ALWAYSがclearable列に未適用。F1-NULL-01はraw SQLのみ）、P2=1/NOTE=1。T060 PASS、T061 FAIL継続、T062〜T066停止、production authorizationなし。

**本deltaのfix**:
- **R8-P0-01**: WORK_TIME_TYPED契約どおり、`t_contract_compliance_profile`/`t_contract_compliance_snapshot`へ`break_start_minute`/`break_end_minute`（分整数）を追加し、複数休憩はappend-onlyの`t_compliance_break_detail`（break_no/start_offset_minute/end_offset_minute、event correction protocol、immutability trigger×2）へ反復detailとして保存。V1/V84/H2/engineer-schema/entity（ContractComplianceProfile/ContractComplianceSnapshot/ComplianceBreakDetail＋mapper）を同一差分で同期し、F1MapManifestTestのWORK_TIME_TYPED manifestへ反映、MySQL fresh smokeへ列/table/trigger assertを追加。
- **R8-P1-01**: `ContractComplianceProfile`の全clearable nullable業務列へ`@TableField(updateStrategy = FieldStrategy.ALWAYS)`を付与（design §5.5 explicit NULL・field-mapping §4.3の既定解）。新規`F1NullClearMapperTest`（@SpringBootTest/H2/test profile）でfull DTOの`updateById`による値→NULL保存と、楽観ロックCAS失敗（expected version不一致）の0行更新をMyBatis-Plus経路で検証。
- **R8-P2-01**: design §6.2 F1-NULL-01行へ「省略PATCH rejectはT063 API導入時にvalidationで担保、T061はraw SQL＋mapper full DTO testで値→NULLとCAS 0行を担保」を明記。
- **R8-NOTE-01**: F1-HISTORY-CORRECTION-01のasOf解決assertをeffective interval（effective_from/to）＋「asOf日に有効な後続eventにsupersedeされていない」NOT EXISTS条件へ強化（8/10→evt-1、8/20→evt-2、8/31→evt-2、10/1→0件を検証）。

**実行test（L1〜L3定向・直接回帰、skip 0）**: H2/F1系（DispatchComplianceSchemaH2Test、F1MapManifestTest、F1SnapshotWriteProtocolTest、F1NullAndHistoryCorrectionTest、F1NullClearMapperTest、F1PiiOwnershipScanTest）8/0/0/0、MySQL 5形状（FRESH/LEGACY/PARTIAL-SCHEMA/FAILED-HISTORY-REPAIR/POST-APPLY-ROLLBACK）5/0/0/0、SpecDispatchConsistencyTest 9/0/0/0、MigrationScriptIntegrityTest 27/0/0/0。`git diff --check` exit 0。

**Rollback**: V84は未releaseのためcommit revertのみでDB rollback不要。本deltaのV1/engineer-schema変更はS10側の自前commitとして記録（R10 provenance注意事項に対応）。

## T062 F2 ComplianceRule分割/拡張 delta（2026-08-09）

R10 Round 9のT061 F1 PASS（R5-P1-01..05・R8全件VERIFIED_CLOSED）を受け、F2を実装した。S11 trackのdirty working tree（V98・LeaveServiceImpl compile error・不正文字）によりmain worktreeではtest実行不能だったため、**isolated worktree（`s10-dispatch-f2`、base `3a0e48d`）で実装・検証**し、push後にmainへ同期した（R10 Round 9の「S10側はdirty stateと分離して作業」指示に準拠）。

**変更file**:
- `service/compliance/`: `ComplianceRule`（interface）、`ComplianceRuleContext`（読み取り専用context: maxLayer/profile/deliveries/workRecordDailies/contractChain/organizationUnit）、`AbstractComplianceRule`（severity/message/enabled共通化。message keyは既存camelCase `compliance.finding.tierExceeded`等と一致）、既存4 rule（TierExceeded/DirectCommand/DoubleDispatch/SettlementMismatch: ロジックを移管し挙動・enabled key・message・出力順をgolden fixtureどおり維持）、新rule 6件（MissingLimitationDate: 2種抵触日NULL検出＋chain算定dueDate添付、MissingResponsible: 指揮命令者/派遣先/派遣元責任者、MissingInsurance: 保険3種fingerprint別、MissingDocumentDelivery: 明示書/通知書、MissingInstructionRoute: 準委任/請負、WorkOutsidePeriod: 客先工数が契約期間外）、`LimitationDateCalculator`（design §5.2 期間代数: 連続更新通算/クーリングconfig値リセット/組織単位変更別chain/同日開始/未来開始先読み/並行契約通算。上限月数・クーリング日数はconfig key＋既定値で、GATE-T060-COOLING/T066の値をコードへ直書きしない）、`ComplianceFindingStore`（(contract_id, code, condition_fingerprint)でupsert同期。再検出でRESOLVED→OPEN、ack済みは保持、非検出でOPEN/ACK/IN_PROGRESS→RESOLVED、EXCEPTION_APPROVEDは保持）、`ComplianceRuleEngine`（全rule実行＋upsert。runActiveContracts/runForContract。契約クエリはshared test schema対応のため必要列のみselect）。
- `LaborComplianceServiceImpl`: 既存4 ruleへ委譲する形へ分解（check/findCurrentRisksの出力はgolden fixtureどおり不変。LaborComplianceServiceImplTest 12件PASS）。
- `controller/api/ComplianceApiController`: `POST /api/compliance/rules/run` 追加（既存compliance menu権限内。実行はread-only＋finding upsert）。
- `dto/compliance/ComplianceFinding`: conditionFingerprint/dueDate追加（既存4引数コンストラクタは互換維持）。`RuleRunResultDto`新規。
- messages 4 bundle: 新rule 9 message key追加（ja/en/ko/zh、MessageBundleConsistencyTest 4件PASS）。

**実行test（L2〜L3定向・直接回帰、skip 0）**: 統合batch **164 tests / failures 0 / errors 0 / skipped 0 / BUILD SUCCESS**。内訳: LimitationDateCalculatorTest 8（連続更新/クーリング/組織単位変更/同日開始/未来開始/並行契約/算定不能）、ComplianceRuleEngineTest 7（新rule code別境界）、ComplianceFindingStoreTest 1（再実行重複0・ack保持・解消RESOLVED・再検出OPEN）、ComplianceRuleRunApiTest 1（Demo: run 2回でopened=0・補完でresolved=2）、LaborComplianceServiceImplTest 12（既存4 rule golden fixture維持）、MessageBundleConsistencyTest 4、ComplianceApiControllerTest 1、MonthlyClosingServiceImplTest 12、SpecDispatchConsistencyTest 9、MigrationScriptIntegrityTest 27、ContractServiceImplTest 48、MobileResponsiveLayoutTest 25、JsSyntaxCheckTest 1、F1系（MAP/SNAPSHOT/NULL/HISTORY/PII/H2）9。`git diff --check` exit 0。

**Demo証跡**: 欠落profileの派遣契約で抵触日2＋責任者3＋保険3＋明示書2＝10 findingがOPEN、rule再実行でopened=0（重複0）、2種抵触日を補完して再実行で該当2件がRESOLVED（欠落解消）、未補完分はOPEN維持。契約chain（連続更新通算・クーリングリセット・組織単位変更別chain・同日開始・未来開始先読み・並行契約通算）をLimitationDateCalculatorTestで実測。

**境界**: DDL/migration変更なし（T061 shapeをそのまま利用）。SecurityConfig/UI/他機能未変更。T062 checkboxはR10確認まで未完了のまま。production release/apply authorizationなし。S11 dirty working treeはS11側で解消が必要（本deltaはisolated worktreeで分離済み）。

## R10 Round 10 fix delta（2026-08-10）

R10 Round 10はT062 F2（`de08f2e4`）を独立実行で検証し、**FAIL**判定・新規P1×2を提示した。本deltaはその2 blockerの最小修正である。

| issue ID | violated | 根本原因と最小fix | 証跡 |
|---|---|---|---|
| R10-P1-01 | platform-invariants §4.1（適用済みmigration編集禁止）、F1-MYSQL-POST-APPLY-ROLLBACK-01、T061 PASS凍結、commit message虚偽 | `de08f2e4`でT061 PASS済みV84の4コメント行（`労働`→`労動`、L262/L267/L306/L545）が混入しchecksumが変動。**T061 PASS時点`34c68f7`のバイト列へ完全復元** | `git diff 34c68f78 -- V84`空（バイト一致）、`労動` count=0、`git diff --check` exit 0 |
| R10-P1-02 | design §5.1（未入力＝`MISSING_*` finding対象）、R5、R3.1 | 5 rule全てが`profile == null → List.of()`でskipし、最もリスクの高い「一切未設定」状態が検知不能（fail-open）。**null profileを「全field未入力」として評価**し、fingerprint fallback（workplace=contract.customerId、org=unknown）は既存実装を維持。`MissingDocumentDeliveryRule`はprofile非依存のためnull check自体を除去 | ComplianceRuleEngineTest: null-profileでMISSING 7 code全件＋保険3・明示書2の件数、準委任null-profileで指示経路を正本化（旧skip正本化testは置換）。L2〜L3定向・直接回帰 **80/0/0/0 skip 0**（下記） |

**変更file**: `src/main/resources/db/migration/V84__dispatch_outsourcing_compliance_ledger.sql`（34c68f7へ復元・4行）、`service/compliance/{MissingLimitationDateRule,MissingResponsibleRule,MissingInsuranceRule,MissingDocumentDeliveryRule,MissingInstructionRouteRule}.java`（null-profile fail-closed）、`ComplianceRuleEngineTest.java`（skip正本化testをfail-closed正本化へ置換＋準委任test追加）、`tasks.md`（F2 status追記）。

**実行test（L2〜L3定向・直接回帰、skip 0）**: F2系（ComplianceRuleEngineTest 8・ComplianceFindingStoreTest 1・ComplianceRuleRunApiTest 1・LimitationDateCalculatorTest 8・LaborComplianceServiceImplTest 12 golden維持）30/0/0/0、F1系（DispatchComplianceSchemaH2Test・F1MapManifestTest・F1SnapshotWriteProtocolTest・F1NullAndHistoryCorrectionTest・F1NullClearMapperTest・F1PiiOwnershipScanTest）8/0/0/0、MigrationScriptIntegrityTest 27/0/0/0、ComplianceApiControllerTest 1、JsSyntaxCheckTest 1、SpecDispatchConsistencyTestはS10側8/8 PASS（残り1 failureはR10-P2-01のS12〜S14予約V99-V101 vs 実在V101で他track起因）、MessageBundleConsistencyTestは`project.detail.desc`（scale-300 `0e29c555`）のみ他track起因。`git diff --check` exit 0。

**P2（他track・本deltaのfix対象外）**: R10-P2-01 — 現mainでSpecDispatchConsistencyTest（S12/S13/S14予約が実在V101以下）とMessageBundleConsistencyTest（`project.detail.desc`未定義）が他track起因で失敗。dispatchのV84予約・本deltaの変更とは無関係。予約表再同期（S12〜S17を実在latest+1へ）とscale-300側のkey追加は統合/他track担当。

**Rollback**: V84はT061 PASS時のバイト列へ戻しただけ（未release）で、commit revertでDB rollback不要。ruleのnull-profile挙動はこのdeltaのcommit revertで旧挙動へ戻る。

**境界**: DDL/migrationはV84復元のみ（新規schema変更なし）。SecurityConfig/UI/controller/i18n/sidebar/他機能未変更。T062 checkboxはR10再Review PASSまで未完了のまま。production release/apply authorizationなし。再開条件: R10がR10-P1-01/02のCLOSEを確認 → T062 checkbox `[x]` → T063/A1（→T064/B1 ‖ T065/B2並行）→ T066 M。

## R10 Round 11 判定（2026-08-10）: T062 F2 PASS

R10はHead `85ca62ba` → `39f0384c`（10ファイル/+62/-39）をread-only＋独立実行（F2系30/0/0/0・F1系9/0/0/0・MigrationScriptIntegrity 27/0/0/0・skip 0）で確認した。失敗2件は前回Head `85ca62ba`から同一の**他track起因**（R10-P2-01: S12/S13/S14予約V99-V101 vs 実在V101、`project.detail.desc`）で、本deltaの変更とは無関係。dispatch関連は全PASS。

| issue ID | 前回 | 今回 | 検証 |
|---|---|---|---|
| R10-P1-01 | OPEN | **VERIFIED_CLOSED** | `git diff 34c68f7..39f0384c -- V84` 空（バイト完全復元）、`労動` 0件。T061 PASS時点checksumへ復帰。F1-MYSQL-POST-APPLY契約・platform-invariants §4.1違反解消 |
| R10-P1-02 | OPEN | **VERIFIED_CLOSED** | 5 rule全てnull profileを「全field未入力」として評価（design §5.1）。fingerprint fallback維持。MissingDocumentDeliveryRuleはprofile非依存のためnull check除去。ComplianceRuleEngineTestをfail-closed正本化（null profileでMISSING検知＋準委任test追加、8/8 PASS） |
| R10-P2-01 | P2 | 継続（dispatch非関与） | SpecDispatchConsistencyTest 1 failure（S12〜S14予約 vs 実在V101）、MessageBundleConsistencyTest 1 failure（`project.detail.desc`）。本delta前から同一失敗。統合担当/他trackで解消 |

**task別判定**: T060 PASS維持 / T061 F1 PASS維持（V84復元でchecksum整合）/ **T062 F2 PASS**（P0=0/P1=0。core実装・golden 12/12・新rule/engine/store/calculator全test合格）/ T063〜T065解放可 / T066 M未着手。

**残課題（本spec外・統合担当）**: 予約表V99-V101衝突の再同期、`project.detail.desc` key追加。解消後に現mainの全量CI再実行を推奨。

**境界**: 本deltaはV84復元・rule修正・test正本化・ledger同期のみ。SecurityConfig/UI/controller/i18n/sidebar/他機能未変更。production release/apply authorizationなし。T062 checkboxを`[x]`化し、T063（A1）から着手可。

## T063 A1 契約compliance profile/UI delta（2026-08-10、R10 Round 12確認待ち）

R10 Round 11のT062 F2 PASSを受け、T063 A1を実装した。S11 trackが同一worktreeをdirtyにしているため、**isolated worktree（`ses-manager-pro-s10-t063`、base `42b80b30`=origin/main）で実装・検証**し、push後にmainへ同期する。

**変更file**:
- `controller/api/ContractComplianceProfileApiController.java`（新規）: `GET/PUT /api/contracts/{id}/compliance-profile`。契約メニュー（4管理ロール）の権限配下。CSRFは既存ajaxSetup経由。
- `service/ContractComplianceProfileService.java`＋`service/impl/ContractComplianceProfileServiceImpl.java`（新規）:
  - role別field mask（design §5.3）: 管理者/HR=P0_FULL、マネージャー=P1_MASK（待遇・保険・苦情詳細・雇用安定措置・抵触日例外・retention metadataをmask）、営業=P2_LIMITED（業務遂行に必要な限定fieldのみ、書き込み不可）。
  - maskはexport/PDF（T064）と同一allow-listを共有する前提で、SENSITIVE_FIELDS/P2_ALLOWED_FIELDSを定数化。
  - 保存はfull DTO必須（key欠落は400、R8-P2-01の省略PATCH reject）。楽観ロック（version CAS、不一致409）。format validation（分0〜1439、日付順序、flag 0/1、workplace/contact/user存在、workplaceの契約顧客一致）。
  - masked role（マネージャー）: sensitive fieldの変更は403 reject（省略=現値維持、異なる値=reject）。BeanUtilsコピー後にsensitive現値をrestoreし、画面maskによる誤消去を防ぐ。
  - findingsは`MonthlyClosingServiceImpl.canViewCompliance()`と同じ方式（管理者=常に可、他roleはcompliance menu権限をMenuCacheServiceで再チェック、fail-closed）でcompliance menu権限がある場合のみ返す（design §5.3）。
  - DataScope: `dataScopeService.assertAllowedContract`（既存契約APIと同じ境界）。
- `dto/compliance/ContractComplianceProfileSaveDto.java`・`ContractComplianceProfileDetailDto.java`（新規）。
- `controller/page/ContractPageController.java`: `GET /contract/detail/{id}` 追加（view名のみ）。
- `templates/contract/detail.html`（新規）: 契約詳細画面（JS駆動）。契約形態別section切替（派遣固有: 抵触日・保険・待遇・苦情・派遣人員・派遣期間・安全衛生 / 準委任・請負固有: 指示経路・再委託・検収）、sensitive fieldは`cpp-sensitive`クラスでmasked role時に編集不可＋「—」表示、findingsカード（compliance権限時のみサーバが返す）、mobile対応（common layout・col-md分割・mobile-date-range相当）。
- `static/js/modules/contract-compliance.js`（新規）: 取得・section切替・mask適用・full DTO構築（masked roleはsensitive keyを省略）・保存・findings描画。
- `static/js/modules/contract.js`: 契約一覧の操作列へ詳細リンク（`/contract/detail/{id}`）追加。
- messages 4 bundle: T063キー約150件追加（ja/en/ko/zh、MessageBundleConsistencyTest PASS）。

**実行test（L1〜L3定向・直接回帰、skip 0）**: ContractComplianceProfileApiTest 15（role別mask matrix: 管理者/HR full・マネージャーP1_MASK・営業P2_LIMITED・findings権限・full DTO欠落400・営業PUT 403・マネージャーsensitive変更403・sensitive省略=現値維持・version 409・期間逆転400・workplace不存在400・契約404）、ContractComplianceDetailPageTest 2、MobileResponsiveLayoutTest 26（`/contract/detail/1`追加）、F2系30（Engine 8・golden 12・Store 1・RunApi 1・Calculator 8）、F1系8、MigrationScriptIntegrity 27、ComplianceApi 1、JsSyntax 1。計108件中失敗2件は既知のR10-P2-01他track起因（S12〜S14予約V99-V101 vs 実在V101、`project.detail.desc`）。`git diff --check` exit 0。

**Demo証跡（L1〜L3実測）**: 派遣/準委任のsection切替はdetail.htmlの`data-section="dispatch"/"quasi"`とJSの`applyContractType`で実装し、page testでマークアップ確認。maskはAPIレスポンスをrole別に実測（管理者=dispatch_fee_amount 10000表示、マネージャー=doesNotExist、営業=限定fieldのみ）。ブラウザでの営業/マネージャーログイン画面DemoはR10 ReviewのDemo確認項目として提示（本環境はbrowser Demo不可のため）。CSV/Excel/PDF/downloadのmaskはT064（B1）のDemo範囲。

**境界**: DDL/migration変更なし（V84 shapeをそのまま利用）。SecurityConfig/他機能未変更。T063 checkboxはR10確認まで`[x]`維持（実装提出済み）。production release/apply authorizationなし。

## R10 Round 12 fix delta（2026-08-10）: R12-P1-01/P2-01

R10 Round 12はT063 A1（`6d5e21f5`）を独立実行（121件中失敗2件は既知の他track起因、T063関連全PASS）で確認し、**FAIL**判定・新規P1×1/P2×1を提示した。本deltaはその最小修正である。

| issue ID | violated | 根本原因と最小fix | 証跡 |
|---|---|---|---|
| R12-P1-01 | tasks.md A1 Demo（保存動線）、design §5.5、serviceコメント | `ContractComplianceProfileSaveDto`に`retentionDueDate`/`legalHoldFlag`が残り、`editableFields()`（DTO全field由来）がfull-DTO必須キーへ含めた。UI（data-key 76件）は両キーを送らないため**全ロールで保存400**。加えてマネージャーがmask済みretentionを盲書き換え可能だった（SENSITIVE_FIELDS guard対象外）。**SaveDtoから2フィールドを除去**（editableFields・missingチェック・BeanUtils copy対象から自動除外され、UI保存が成立。GETの管理者/HR表示はentity経由で維持） | `ContractComplianceProfileApiTest`へ**UI実送payload回帰test**追加: テンプレートの`data-key`属性を正規表現抽出し、そのkeyセットだけでPUT→200（retention/legalHoldが含まれないこともassert）。17/17 PASS |
| R12-P2-01 | commit messageの顧客一致主張と実装の不一致 | contact（commandPersonContactId/clientResponsibleContactId）は存在チェックのみで、他顧客のcontact参照が通った。**contact.customer_idと契約customer_idの一致チェックを追加**（両方非null時のみ。workplaceと同じ規則） | 新test: 他顧客の担当者を指定したPUT→400。i18n key `contract.compliance.contactCustomerMismatch`×4バンドル追加 |

**変更file**: `ContractComplianceProfileSaveDto.java`（retention/legalHold除去）、`ContractComplianceProfileServiceImpl.java`（requireContactOfCustomer追加）、`ContractComplianceProfileApiTest.java`（EDITABLE_KEYS同期＋UI payload回帰test＋contact顧客一致test、15→17件）、messages 4 bundle（contactCustomerMismatch）。

**実行test（L1〜L3定向・直接回帰、skip 0）**: ContractComplianceProfileApiTest **17/0/0/0**、ContractComplianceDetailPageTest 2、MobileResponsiveLayoutTest 26、F2系30（golden 12/12）、F1系8、MigrationScriptIntegrity 27、ComplianceApi 1、JsSyntax 1。計112件中失敗1件は既知のR10-P2-01他track起因（`project.detail.desc`）のみ。`git diff --check` exit 0。

**NOTE（R12・blockしない）**: ① 営業書き込み403・マネージャーmasked writeは決定表にwrite列が無い中のfail-closed実装判断（design §5.3へ明文化推奨）② `guardSensitiveUnchanged`の`String.valueOf`比較（BigDecimal表記差でfalse-403余地、UIがsensitiveを送らないため実害低）③ findingsカードはcanViewCompliance＋DataScope ✓。

**Rollback**: DDL/migration/SecurityConfig変更なし。commit revertでDB rollback不要。

**境界**: T063 checkboxはR10再Review PASSまで未完了へ戻し、再開条件: R10がR12-P1-01/P2-01のCLOSEを確認 → T064（B1）→ T065（B2）→ T066 M。production release/apply authorizationなし。

## R10 Round 13 判定（2026-08-10）: T063 A1 PASS

R10はHead `6d5e21f5` → `a6695026`（9ファイル/+112/-12）をread-only＋独立実行（123件中失敗2件は既知のR10-P2-01他track起因で本delta前から同一、dispatch関連全PASS）で確認した。

| issue ID | 前回 | 今回 | 検証 |
|---|---|---|---|
| R12-P1-01 | OPEN | **VERIFIED_CLOSED** | SaveDtoからretentionDueDate/legalHoldFlag削除→editableFields自動除外・missingチェック対象外・copy対象外。**17件目testがテンプレート実data-key抽出payloadでPUT→200を回帰保証**（`doesNotContain`もassert）。マネージャーのretention盲書き換えも遮断。GETの管理者/HR表示はentity経由で維持 |
| R12-P2-01 | P2 | **VERIFIED_CLOSED** | requireContactOfCustomer（存在＋customer_id一致、両方非null時）。他顧客contact指定PUT→400をtestで検証。i18n `contactCustomerMismatch`×4 |
| R10-P2-01 | P2 | 継続（dispatch非関与） | `project.detail.desc`・予約V99-V101衝突。統合担当追跡 |

**task別判定**: T060/T061/T062 PASS維持 / **T063 A1 PASS**（P0=0/P1=0/P2=0。role mask・full DTO・CAS・validation・UI保存動線まで検証済み）/ T064〜T065解放可 / T066 M未着手（G2 gate）。

**独立証跡**: API 17/0/0/0、ページ2、Mobile 26、F2系30、F1系9、Integrity 27 — 全PASS skip 0。保存動線はUI実送payload（data-key 76件＋version）でPUT 200を自動回帰化。

**NOTE継続（非block）**: 営業write 403のdesign明文化推奨、guardのBigDecimal表記差、engine N+1（T066性能検証）。

**境界**: DDL/migration/SecurityConfig変更なし。production release/apply authorizationなし。T063 checkboxを`[x]`化し、T064（B1）から着手可。

## T064 B1 法定帳票/交付/archive delta（2026-08-10、R10 Round 15確認待ち）

R10 Round 14のT064着手許可を受け、B1を実装した。S11 trackが同一worktreeをdirtyにしているため、**isolated worktree（`ses-manager-pro-s10-t063`、base `a26fa0d1`=origin/main）で実装・検証**し、push後にmainへ同期する。

**変更file**:
- `service/compliance/ComplianceSnapshotWriter.java`（新規）: profile→snapshotの作成・再利用。内容hash（profile業務fieldの決定的SHA-256）を冪等キーとし、最新snapshotのhashが一致すれば再利用。新規時はUNIQUE(contract_id,snapshot_version)＋operation row（operation_id一意）＋profile current pointerのversion CASで競合制御（design §5.4・field-mapping §4.1/§4.2、F1プロトコル準拠）。失敗は同一txで全rollback（orphan 0）。
- `service/compliance/ComplianceDocumentGenerator.java`（新規・@Component）: 4帳票種別（就業条件明示書/派遣先通知書/派遣元管理台帳/個別契約書）の内容モデルをsnapshot typed列から構築（MAPPING-2026-07 baseline、current masterを再読しない）。sensitive行（待遇・保険・苦情・雇用安定・抵触日例外・worker PII）はmaskLevel!=FULLで「—」へ置換（R4.2）。PDFはopenpdf＋`PdfFontUtils.resolveCjkFont()`（日本語フォント埋め込み・A4）。
- `service/compliance/ComplianceFieldMask.java`（新規）: T063/T064共有のSENSITIVE_FIELDS/P2_ALLOWED_FIELDS定数を集約（T063 serviceは参照へ切替、挙動不変）。
- `service/compliance/ComplianceAccessControl.java`（新規）: compliance menu権限再チェック共通化。**design §5.3の「HR/法務=全件・全field」に合わせHRを常に可へ**（T063のcanViewComplianceもこの共通化へ切替）。
- `service/ComplianceDocumentService.java`＋`service/impl/ComplianceDocumentServiceImpl.java`（新規）:
  - list: 交付記録一覧（compliance権限ロールのみ、営業は403）
  - generate: profile→snapshot→PDF→`DocumentService.registerGenerated`（document archive、businessKey=`COMPLIANCE:{contractId}:{docType}`、discriminator=`v{templateVersion}:{snapshotHash}`でarchive側も冪等）→`t_document_delivery`記録。冪等キー`(contract_id, document_type, template_version, snapshot_hash)`で既存交付行があれば再生成しない（design §5.4）。
  - confirm: `confirmed_at`+note記録（CAS）。NULL=受領未確認（未交付ではない、design §5.1）
  - download: 生成PDF配信。scanStatus CLEAN以外は403（fail-closed）＋DocumentAccessLog DOWNLOAD記録
  - template versionは`m_system_config`（`compliance.template.<TYPE>.version`、既定1、SystemConfigServiceImplのSCHEMASへ4 key登録＝管理者が/system-configから変更可。GATE-T060の「判断値はconfigへ置く」方針）
  - 営業は生成・確認・ダウンロード403（fail-closed）。受領者contactは存在＋契約顧客一致チェック
- `controller/api/ComplianceDocumentApiController.java`（新規）: `GET /api/contracts/{id}/compliance-documents`、`POST .../generate`、`POST .../{deliveryId}/confirm`、`GET .../{deliveryId}/download`（契約メニュー権限配下・CSRF・audit）。
- `templates/contract/detail.html`＋`static/js/modules/contract-compliance.js`: 法定帳票・交付カード（生成フォーム・交付記録一覧・受領確認・ダウンロード）。営業（LIMITED）にはカード非表示。
- messages 4 bundle: doc.*/cpp.document.*/error key約60件追加。

**実行test（L2〜L3定向・直接回帰、skip 0）**: ComplianceDocumentApiTest 7（生成→snapshot+archive+delivery・同一内容再生成で2件目なし・profile変更→新snapshot→新交付・templateVersion切替（config）で版が進む・受領確認・PDFダウンロード%PDF・不正種別/方法400・profile未作成400・営業403）、ComplianceDocumentGeneratorTest 5（golden content model・MASKでsensitive「—」・worker PII mask・4帳票構成・PDF生成）、T063系（API 17・page 2・Mobile 26）、F2系30、F1系8、Integrity 27、ComplianceApi 1、JsSyntax 1。**計124件全PASS（失敗0・skip 0）**。`git diff --check` exit 0。

**Demo証跡（L2〜L3実測）**: 派遣元管理台帳等を生成→交付記録作成。同一snapshot（同一内容）の再生成でdelivery件数・snapshot件数とも増えない（冪等）。profile変更→snapshot v2→新hash→新交付記録（版差分が説明できる）。template version切替（m_system_config）で版が進む。PDFはscanStatus CLEANのみダウンロード可。ブラウザ画面DemoはR10 ReviewのDemo確認項目として提示。

**境界**: DDL/migration変更なし（V84 shape・既存document archiveを利用。retention categoryはGATE-T066-RETENTION）。SecurityConfig/他機能未変更（canViewComplianceのHR常可化のみdesign §5.3準拠の共通化）。T064 checkboxはR10確認まで未完了。production release/apply authorizationなし。

## R10 Round 15 fix delta（2026-08-10）: R15-P1-01〜04

R10 Round 15はT064 B1（`84101461`）を独立実行（135件中失敗2件は既知のR10-P2-01他track起因、B1系12/12含め全PASS）で確認し、**FAIL**判定・新規P1×4を提示した。本deltaはその修正である。

| issue ID | violated | 根本原因と最小fix | 証跡 |
|---|---|---|---|
| R15-P1-01 | R4.2・design §5.3 | 生成時role固定mask＋role非依存冪等キーで、マネージャーがFULL PDFを取得可／mask済PDFが正本化。**generateは常にFULLでarchive正本化し、download時にviewer roleで再mask（snapshotから再レンダリング）**。scanStatus CLEANの正本登録をdownloadの前提gateに維持 | 新test: 管理者generate→管理者download（FULL）とマネージャーdownload（MASK）のバイト列が異なる・営業download（LIMITED）もFULLと異なる。全て%PDF。8/8 PASS |
| R15-P1-02 | R2.1・FM-C-01 | party_*未投入＋label/value不一致。**SnapshotWriterが`company.name/address/representative`（m_system_config、SCHEMASへ`company.representative`追加）をsnapshot化**。generatorは`doc.party.name/address/representative`ラベルへ整合（派遣元=party_*、派遣先=workplace_*） | API test: 生成後に`t_contract_compliance_snapshot.party_name/party_address`がconfig値で投入されることを実測。GeneratorTestで4帳票の当事者行をgolden assert |
| R15-P1-03 | R2.1・T060 mapping・B1 golden | 4帳票のmapping項目の相当数が未出力。**generatorを拡充**: 福利厚生・雇用安定措置・協定対象flag・派遣人員・抵触日2種・抵触日例外・休日カレンダー・責任者（通知書/台帳）・時間外（台帳）・保存満了（台帳）等をsnapshot typed列から出力。**履歴table・worker snapshot由来項目（苦情処理状況・キャリア・教育訓練・紹介予定・紛争防止・差異通知・性別/年齢・無期/60歳）は、それらの行を作成する実装が存在しないためT066（M）で全項目化する旨をdesign.md §3.1へ明記**（範囲固定） | GeneratorTest 5/5（新section含むgolden） |
| R15-P1-04 | design §5.3・R4.1 | 営業の帳票API全403が「同左」と未文書化逸脱。**営業に一覧＋masked（LIMITED）downloadを許可**（generate/confirmはwriteとして403維持）。逸脱はdesign.md §3.1へ明記 | 新test: 営業が一覧200・masked download 200・generate 403 |

**変更file**: `ComplianceDocumentServiceImpl.java`（download再mask・営業許可・snapshot検索）、`ComplianceDocumentGenerator.java`（4帳票のsection拡充・party行）、`ComplianceSnapshotWriter.java`（party config投入）、`SystemConfigServiceImpl.java`（`company.representative`＋template version 4 keyをSCHEMASへ）、messages 4 bundle（doc.party.*/doc.section.benefits・retention等）、design.md §3.1（逸脱・範囲）、test 2件拡充（API 7→8、Generator 5）。

**実行test（L2〜L3定向・直接回帰、skip 0）**: **計125件全PASS（失敗0・skip 0）**: T064系13（API 8・Generator 5）、T063系45、F2系30、F1系8、Integrity 27、ComplianceApi 1、JsSyntax 1。`git diff --check` exit 0。MessageBundleConsistencyTestは既知の他track失敗（`project.detail.desc`）のみ。

**Rollback**: DDL/migration/SecurityConfig変更なし。commit revertでDB rollback不要。

**境界**: T064 checkboxはR10再Review PASSまで未完了維持。再開条件: R10がR15-P1-01〜04のCLOSEを確認 → T065（B2）→ T066 M。production release/apply authorizationなし。

## R10 Round 16 判定（2026-08-10）: T064 B1 PASS

R10はHead `84101461` → `ca47e7f1`（12ファイル/+272/-38）をread-only＋独立実行（136件中失敗2件は既知のR10-P2-01他track起因で本delta前から同一、dispatch関連全PASS）で確認した。

| issue ID | 前回 | 今回 | 検証 |
|---|---|---|---|
| R15-P1-01 | OPEN | **VERIFIED_CLOSED** | generateは常にFULLで正本化、downloadはsnapshotからviewer roleで再レンダリング（管理者/HR=FULL・マネージャー=MASK・営業=LIMITED）。cross-role testでFULL/MASK/LIMITEDのPDF byte差を実測（API 8件目）。scan CLEAN gate・access log維持。design §3.1に再レンダリング版≠正本の旨明記 |
| R15-P1-02 | OPEN | **VERIFIED_CLOSED** | SnapshotWriterがcompany系config（SCHEMAS登録済み）をparty_*へsnapshot化（FM-C-01）。generatorの当事者行をdoc.party.name/address/representativeへ整合、派遣期間はdoc.periodで別出力 |
| R15-P1-03 | OPEN | **VERIFIED_CLOSED（scope決定として受理）** | typed列由来項目を各帳票へ追加。履歴table・worker snapshot由来項目はT066（M）で履歴連携と共に全項目化（design §3.1明記）。T066の受入で網羅を再検証 |
| R15-P1-04 | OPEN | **VERIFIED_CLOSED** | 営業は一覧＋masked（LIMITED）download可、generate/confirmはwriteとして403（design §3.1明記）。API testで実測 |
| R10-P2-01 | P2 | 継続（統合担当追跡） | 同一2 failureのみ |

**task別判定**: T060〜T063 PASS維持 / **T064 B1 PASS**（P0=0/P1=0/P2=0。冪等・版管理・正本FULL・viewer再mask・scan gate・受領確認・営業動線まで検証済み）/ T065解放可 / T066 M未着手（**T064から移管された履歴/worker由来の帳票全項目化**を含む）。

**NOTE（非block・T066で検証）**: ① download再レンダリングのengineer名は現在マスタ由来（正本PDFはsnapshot固定でR1.4担保）② operation_idがserver派生（§4.1と構造差異・観測挙動はF1-SNAPSHOT-01合致）③ profileHashのBigDecimal表記感度 ④ 帳票PDFの実ブラウザ目視はM/本番gate。

**境界**: DDL/migration/SecurityConfig変更なし。production release/apply authorizationなし。T064 checkboxを`[x]`化し、T065（B2）から着手可。

## M / 本番gateと再開条件

- `COMPLIANCE_RESPONSIBLE` のruntime assignment、資格/根拠の確認、法定責任者の事業所/契約assignmentは、M / 本番設定gateとして実装・設定する。承認eventには実際のactor user ID、表示名snapshot、role、日時、mapping version/hash、根拠資料を保存する。
- 2026-10-01施行分の待遇差説明を求める権利の正確な文言・対象範囲は `GATE-T060-2026-10` としてB1/T066で確認する。`MAPPING-2026-07`へ遡及しない方針は確定済み。
- 個別契約書・就業条件明示書・派遣先通知書のarchive retention categoryは `GATE-T066-RETENTION` としてT066/B1で具体化する。派遣元管理台帳の派遣終了日から3年間保存だけを公式記載のbaselineとする。
- 抵触日算定のクーリング期間値と組織単位変更の同一性基準は `GATE-T060-COOLING` としてT062/T065で具体化する。
- 外部社労士/弁護士の照合は `GATE-T060-EXTERNAL` としてT066 M / 本番解放前のgateである。

T060からT061へ進む条件は、R10 Round 4で確認済みのT060判定に加え、R4-P1-01について全環境のV82/V83適用状態証跡、V82→V83または繰上げの単一decision、予約表・全派工資料・legacy fixtureの整合、`SpecDispatchConsistencyTest` direct regression PASSがR10により確認されること。R4-P1-01がOPENの間はT061/V82/production変更を開始しない。T061開始時にはmerge済み `db/migration` のlatestを再確認する。R10のRound 4 review後も、tasks.mdのcheckboxはレビュー判定と実装headの同期確認前に変更しない。

## R4-P1-01 implementer fix delta（2026-08-09）

- **Status**: `R10 VERIFIED_CLOSED`。T061/V84の開発開始可、production release/apply authorizationは付与しない。
- **Environment evidence**: local-defaultはV82/V83 rowなし、latest V74、success=true、installed_on=`2026-08-02 00:35:29`、checksum=`559443363`。CI/TestcontainersはCI run `31305828153`の`FlywayEnvironmentEvidenceTest`でV82 row absent、V83 success=true、installed_on=`2026-08-09 09:27:47.0Z`、checksum=`2106900723`、versioned latest=V83をread-only assert（test 1/0/0/0）。GitHub Environment APIは`total_count=0`、repo workflowに永続staging/production deployment targetなし。外部環境を推測・接続していない。
- **Formal decision**: `migration-order-decision-r4-p1-01.md`を作成し、V83実在を根拠にS10=V84、S11=V83、S12=V85、S13=V86、S14=V87、S15=V88、S16=V89、S17=V90、V82欠番を確定。production release authorizationは含めない。
- **Synchronized artifacts**: customer-product-expansion README、parallel plan、central ledger、S10〜S17 design/tasks、start/review conversations、copyable conversations、`s10-r4-p1-01-v83-realized.properties`を同期。`SpecDispatchConsistencyTest`は**9/0/0/0**（skip 0、BUILD SUCCESS）へ復帰。
- **Direct tests**: `mvn -B -Dtest=FlywayEnvironmentEvidenceTest test` **1/0/0/0 BUILD SUCCESS**（local Docker MySQL）；`mvn -B -Dtest=SpecDispatchConsistencyTest test` **9/0/0/0 BUILD SUCCESS**；`git diff --check` exit 0。repeatable migrationのversion=NULLをlatest判定から除外する回帰も含む。
- **Changed files boundary**: migration/DDL/SecurityConfig/production code/tasks checkboxは変更なし。R4証跡・decision・docs・test fixture/direct regressionだけを変更し、S11の別track差分は混入していない。rollbackは本deltaのdocs/test commit revertのみでDB rollback不要。
- **Review result**: R10がenvironment packet、formal decision、V84〜V90の全資料同期、fixture、9件direct regression、実在SHA/Base/Headを独立確認し、R4-P1-01を`VERIFIED_CLOSED`とした。
- **Provenance**: Base `df7f6b1f5e27b64876133d26debd95422d29379a` → **R10 reviewed Head `b75af1a1eff16e6c5723a2a2310a31ec324e7f80`**。同期内容commit `08eb09802d07c6e272473495ac22f5057cd4bbba`、provenance predecessor `23e48e0689deabeab49f8888c3aac1bc8c11a97f`。R10 reviewed Head後のcurrent main `7f60738a0dd1b3a9314cc3b115dae1173673358d`はS11中央ledger 1 fileのみでS10 Review対象外。CI evidence run `31306415759`は全体1629/0/0/0、`SpecDispatchConsistencyTest` 9/0/0/0、`FlywayEnvironmentEvidenceTest` 1/0/0/0。

## T061 review packet synchronization（2026-08-09）

- T061 F1は実装完了。Base `856ab1faf09f07abcd7a5b34453a5037173ce553` → implementation Head `e7f7f19434e0e45d54888d6b468e9d8704c6056f`。この後のledger同期commitを含むcurrent HeadをR10 packet送付時に固定する。
- Changed boundary: V1/V84、4 entity/mapper、H2専用schema、engineer-schema/application-test、migration guard、H2/MySQL smoke、tasks/design/ledgerのみ。SecurityConfig、UI、B1/B2、S11 attendanceは変更していない。
- Test packet: MySQL V84 fresh `FlywayDispatchComplianceSchemaSmokeTest` 1/0/0/0、H2 `DispatchComplianceSchemaH2Test` 1/0/0/0、`SpecDispatchConsistencyTest` 9/0/0/0、`ContractServiceImplTest` 48/0/0/0、`ComplianceApiControllerTest` 1/0/0/0、`git diff --check` exit 0。
- Demo evidence: profileのsnapshot_json/workplace_snapshot_json/worker_snapshot_json不変、limitation_date NULL＝未算定、事業所期間逆転拒否、finding `(contract_id, code, condition_fingerprint)`重複拒否、V84の契約/文書/contact FKを確認。UI/export/PDFの実maskはT063/T064のgateとして残す。

## R10 final Review synchronization（2026-08-09）

- R4-P1-01: `VERIFIED_CLOSED`。T060 PASS維持。T061/V84の開発開始を許可し、production release/apply authorizationは付与しない。
- R4-P2-01: provenance表記をBase/R10 reviewed Head/current mainの三層で訂正した。`current Head`はR10 reviewed Headを指し、S11後続commitをS10へ混入させない。
- 次 action: T061開始前にmerge済み`db/migration`のlatestを再確認し、V84予約と衝突しないことを確認する。
