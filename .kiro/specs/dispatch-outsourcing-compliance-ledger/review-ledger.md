# dispatch-outsourcing-compliance-ledger review ledger

## 現行判定

`R10 Round 4 packet: T060 PASS / R4-P1-01 VERIFIED_CLOSED / T061 V84 START ALLOWED`。R10 Round 4はT060をPASS（R1-P1-01 VERIFIED_CLOSED、R1-P1-02 VERIFIED_CLOSED_BY_DECISION_CHANGE）と判定し、R4-P1-01もVERIFIED_CLOSEDとした。CI/TestcontainersのV83実在・V82不在、正式decisionによるS10=V84繰上げ、予約資料・fixture同期、direct regressionを独立確認済みである。T061/V84の開発は開始可だが、production release/apply authorizationは付与しない。S11 attendanceの別track差分は混入させない。

R4-P1-01のunblock fixとして、reserved <= latestを検出するguard、CI/TestcontainersのFlyway履歴read-only証跡、V83実在/V82欠番の正式decision、V84〜V90の予約資料同期、legacy fixtureを追加した。`SpecDispatchConsistencyTest`は9/0/0/0 PASSへ復帰し、R10独立確認でR4-P1-01がVERIFIED_CLOSEDとなった。T061開始時にmerge済み`db/migration`のlatestを再確認し、V84でDDLを実装する。

## T060 証跡

| task | requirements | 変更file | test / Demo | base / head | risk / rollback |
|---|---|---|---|---|---|
| T060 | R1.1〜R1.4, R2.1〜R2.2, R3.1〜R3.4, R4.1〜R4.2, R5 | `.kiro/specs/dispatch-outsourcing-compliance-ledger/field-mapping.md`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md`（T060 checkboxを未完了へ戻した）。production code/DDL/migration/SecurityConfigは変更しない | L0/direct regression **PASS**: form mapping 96行、SRC-E ⑱=1行、SRC-L ④=1行、根拠なし2026-10 mapping行=0行、全mapping行11列、version/effective period、T060 3文書の`git diff --check` exit 0。R10 Round 2はP1-01をVERIFIED_CLOSED、P1-02をOPEN / APPROVAL_REQUIREDと判定。社内承認Demoは証拠未取得のため未完了 | R10固定範囲 Base `f8adbc028ae0e260ed8123d0405901febee16f5a` → original Head `8fdadb4af51d224d7659d377196b6774d46dea1f` → Packet Head `be2fb190dcdf6d13286694ebe3a6a31cb477fb09`。R1 fix Head `0909acb867577217b91de1bc64edd581f4da403c`、R10 Round 2確認Head `cddbc325c0793fdb41ccb73a3f976de271b34093` | T061/V82へ進めない。productionでは未指名/未確認/資格・根拠不足をfail-closed。rollbackはR1 fix commitをrevertし、production変更は存在しないためDB rollback不要 |
| T060 / R4 unblock delta | R1.1〜R5、platform-invariantsのMigration順序、parallel-execution-planの着手時latest再確認 | `src/test/java/com/ses/migration/SpecDispatchConsistencyTest.java`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md`, `.kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md`。migration/DDL/production code/tasks checkboxは変更しない | `mvn -B -Dtest=SpecDispatchConsistencyTest test`: **8 tests, failures 1, errors 0, skipped 0**。failureは期待された安全側検出で、`S10 dispatch-outsourcing-compliance-ledger の予約V82が実在最新V83以下`を報告。`git diff --check`は対象差分で確認する。ローカル既定DB read-only: `flyway_schema_history` latest successful V74、V82/V83なし。非ローカル環境は未確認 | R4固定範囲 Base `1fd0f7492ab46388c961e2e721ccdedd416929c4` → Decision fix `a1f5e8e8c5b8b559520109a43c61e59f56ab8243` → Packet Head `87a901375ec94dcb7093fdd2e863ed1b8b109a56`。guard fix commit `066a61f9584ab4d9bfe9c3dea9ed3d4ec1b8379c` → ledger sync commits `8b772adcb801c013d347ca097ac8100c544d0ae4`, `bfc4ca3f1c0fdb8d7b0fac4507527f2090f4dc4f`, `23d6845e2284793404e0910948f92e2da48d2b96`, `79f63db0bc8fae23698b90517c4c35a621eb59b7` | R4-P1-01がOPENの間はdeploy freeze、T061/V82/production変更を開始しない。中央ledgerはREADY/R10 PASSからIN PROGRESS/R4-P1-01 OPENへ同期した。rollbackはこのテストガードとledger追記のrevertのみで、DB変更はない。S11 attendance変更はstageしない |
| T060 / R10 P2・environment packet | R1.1〜R5、R4-P1-01 environment evidence gate | `.kiro/specs/dispatch-outsourcing-compliance-ledger/field-mapping.md`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/environment-evidence-packet.md`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md`。T061/V82/productionは変更しない | P2最小修正: field-mappingの古い「R10再Review待ち」をT060 PASS/R4-P1-01環境証跡待ちへ更新。`git diff --check` exit 0。local-default read-only JDBC: V82/V83 target rows=0、latest successful V74 / success=true / installed_on=`2026-08-02 00:35:29` / checksum=`559443363`。CI/Testcontainers、staging、production、other legacyはowner証跡未提出 | P2 fix Base `4dadfb30258f5d21246fdbe48783addb7bf79171` → P2 fix `9cac72a9c56f109fce359447df9a799f8639e295`。environment packet commit `6e6896a7792cf81609da7800525ca59f47ac8353` | packetがINCOMPLETEの間は正式migration decisionを作成せず、採番変更、V82作成、T061/DDL/production変更を開始しない。S11 dirty変更はstageしない |
| T061 / F1 DDL | R1.1〜R1.4、R1.2/R1.3派遣・準委任固有項目、R2.2、R3.1/R3.4、R4.1〜R4.2、R5 snapshot/fail-closed | `src/main/resources/db/migration/V1__create_tables.sql`, `V84__dispatch_outsourcing_compliance_ledger.sql`, `src/main/java/com/ses/entity/{Workplace,ContractComplianceProfile,ComplianceFinding,DocumentDelivery}.java`, 対応mapper 4件、H2専用schema/engineer-schema/application-test、direct regression | L1〜L3 **PASS**: `FlywayDispatchComplianceSchemaSmokeTest` 1/0/0/0（MySQL V84 fresh）、`DispatchComplianceSchemaH2Test` 1/0/0/0、`SpecDispatchConsistencyTest` 9/0/0/0、`ContractServiceImplTest` 48/0/0/0、`ComplianceApiControllerTest` 1/0/0/0、`git diff --check` exit 0。snapshot不変、limitation_date NULL＝未算定を許容、2種抵触日、worker/workplace snapshot、finding unique、期間/FKを確認 | Base `856ab1faf09f07abcd7a5b34453a5037173ce553` → implementation commit（本commit）→ ledger sync Head（後続docs commit）。S11 attendance dirty変更はstageしない | T061開発成果はreview-ready。production release/apply authorizationなし。UI/export/PDFのfield mask実動線はA1/B1で同じprofile permission境界を検証する |

## R10 Issue Register（履歴スナップショット）

| issue ID | Review status | Implementer status | violated / location | fix evidence | verification / next action |
|---|---|---|---|---|---|
| dispatch-outsourcing-compliance-ledger-R1-P1-01 | **VERIFIED_CLOSED** | **FIXED_BY_IMPLEMENTER** | T060 Objective/L0全項目網羅、R2.1／field-mapping.md SRC-E section・SRC-L section・2026-10行 | SRC-E「社会保険の加入手続きが完了していない場合の理由（⑱）」とSRC-L「60歳以上か否かの別（④）」を独立mapping行へ追加。4公式PDFに確認できない2026-10通知行を削除し、一次source特定gateへ戻した | R10 Round 2がmapping 96行、全mapping行11列、SRC-E⑱=1、SRC-L④=1、根拠なし2026-10行=0、`git diff --check` exit 0を確認。新規P0/P1なし |
| dispatch-outsourcing-compliance-ledger-R1-P1-02 | **VERIFIED_CLOSED_BY_DECISION_CHANGE** | **FIXED_BY_DECISION_CHANGE / REVIEWED** | T060 Demo、tasks.md、review-ledger.md／社内責任者の実actor・承認日時・mapping version/hash・source版・status証跡 | R10 Round 4のDecision fix `a1f5e8e8c5b8b559520109a43c61e59f56ab8243` が、自然人の事前固定ではなくG2-DEV-GATE、role lifecycle、runtime assignment、本番fail-closedの分離を確定。対象Packet Head `87a901375ec94dcb7093fdd2e863ed1b8b109a56`、mapping blob `32fdb05b00509aab8002a68ba9fa728db8fab36c`をR10が確認した | R10 Round 4がVERIFIED_CLOSED_BY_DECISION_CHANGE。runtime role assignment、実actorによる承認event、資格/根拠確認はM / 本番設定gateとして残し、開発T061をこのP1で停止しない。ただしR4-P1-01が別途OPENのためT061/V82は開始不可 |
| dispatch-outsourcing-compliance-ledger-R4-P1-01 | **OPEN** | **FIXED_BY_IMPLEMENTER / ENVIRONMENT_EVIDENCE_REQUIRED** | `parallel-execution-plan.md:63,70-74`、customer-product-expansion README:75、dispatch `tasks.md:9` のV82→V83順序、out-of-order禁止、着手時latest再確認。既存の`SpecDispatchConsistencyTest`は予約番号と同値の実在だけを検査し、reserved `<=` latestを検出できなかった | `SpecDispatchConsistencyTest.予約Migration番号が実在スクリプトと衝突しないこと()`へ`reserved <= latest`の検出を追加。現repoはV83が実在しV82が未実在であるため、テストがS10の予約V82を実在最新V83以下として報告する。ローカル既定DBはread-only確認済みでlatest successful V74、V82/V83履歴なし。staging/production等の非ローカル環境の適用状態は証明できず、推測・捏造しない | deploy freezeを継続し、環境ごとのread-only `flyway_schema_history`（V82/V83のversion、success、installed_on、checksum）を取得する。全環境でV83未適用ならV82を先にmerge/applyしてからV83へ進む順序を固定する。1環境でもV83適用済みなら、予約表・README・parallel plan・全派工資料を同一decisionで次の未使用番号（実在latestに応じたV84以降）へ繰り上げ、legacy fixtureを追加し、guardがPASSすることを確認する。証跡・decision・direct regression PASSが揃うまでVERIFIED_CLOSEDにしない |

**現行Issue Register override（R10 Round 4後）**: R4-P1-01は`VERIFIED_CLOSED`、R4-P2-01は本ledger同期でprovenance表記を訂正済みのためR10再確認対象。T061/V84は開始可、production release/apply authorizationなし。

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

## M / 本番gateと再開条件

- `COMPLIANCE_RESPONSIBLE` のruntime assignment、資格/根拠の確認、法定責任者の事業所/契約assignmentは、M / 本番設定gateとして実装・設定する。承認eventには実際のactor user ID、表示名snapshot、role、日時、mapping version/hash、根拠資料を保存する。
- 2026-10-01施行分の待遇差説明を求める権利の正確な文言・対象範囲は `GATE-T060-2026-10` としてB1/T066で確認する。`MAPPING-2026-07`へ遡及しない方針は確定済み。
- 個別契約書・就業条件明示書・派遣先通知書のarchive retention categoryは `GATE-T060-RETENTION` としてT061/B1で具体化する。派遣元管理台帳の派遣終了日から3年間保存だけを公式記載のbaselineとする。
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

## R10 final Review synchronization（2026-08-09）

- R4-P1-01: `VERIFIED_CLOSED`。T060 PASS維持。T061/V84の開発開始を許可し、production release/apply authorizationは付与しない。
- R4-P2-01: provenance表記をBase/R10 reviewed Head/current mainの三層で訂正した。`current Head`はR10 reviewed Headを指し、S11後続commitをS10へ混入させない。
- 次 action: T061開始前にmerge済み`db/migration`のlatestを再確認し、V84予約と衝突しないことを確認する。
