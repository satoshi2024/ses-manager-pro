# dispatch-outsourcing-compliance-ledger review ledger

## 現行判定

`R10 Round 4 packet: T060 PASS / R4-P1-01 OPEN`。R10 Round 4はDecision fix `a1f5e8e8c5b8b559520109a43c61e59f56ab8243` とPacket Head `87a901375ec94dcb7093fdd2e863ed1b8b109a56` に対してT060をPASS（R1-P1-01 VERIFIED_CLOSED、R1-P1-02 VERIFIED_CLOSED_BY_DECISION_CHANGE）と判定した。一方、Base/PacketにV83が実在しV82が不存在であるため、`dispatch-outsourcing-compliance-ledger-R4-P1-01` をOPEN / ENVIRONMENT_EVIDENCE_REQUIREDとして記録する。本fixではmigration/productionを変更せず、T061/V82の開始を停止する。S11 attendanceのcommit/dirty変更はReview対象外であり、混入させない。

R4-P1-01のunblockとして、予約migration番号が実在最新番号以下になる状態を検出するdirect regressionを追加した。現repoのV83実在・V82予約に対してテストは期待どおり失敗し、安全側にdeployを停止する。ローカル既定DBのread-only確認では`flyway_schema_history`の成功済み最新はV74、V82/V83の履歴行はない。ただしstaging/productionその他の環境状態をrepoから証明できないため、「全環境でV83未適用」とは断定しない。全環境の証跡と順序決定が揃うまでdeploy freezeを継続する。

## T060 証跡

| task | requirements | 変更file | test / Demo | base / head | risk / rollback |
|---|---|---|---|---|---|
| T060 | R1.1〜R1.4, R2.1〜R2.2, R3.1〜R3.4, R4.1〜R4.2, R5 | `.kiro/specs/dispatch-outsourcing-compliance-ledger/field-mapping.md`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/tasks.md`（T060 checkboxを未完了へ戻した）。production code/DDL/migration/SecurityConfigは変更しない | L0/direct regression **PASS**: form mapping 96行、SRC-E ⑱=1行、SRC-L ④=1行、根拠なし2026-10 mapping行=0行、全mapping行11列、version/effective period、T060 3文書の`git diff --check` exit 0。R10 Round 2はP1-01をVERIFIED_CLOSED、P1-02をOPEN / APPROVAL_REQUIREDと判定。社内承認Demoは証拠未取得のため未完了 | R10固定範囲 Base `f8adbc028ae0e260ed8123d0405901febee16f5a` → original Head `8fdadb4af51d224d7659d377196b6774d46dea1f` → Packet Head `be2fb190dcdf6d13286694ebe3a6a31cb477fb09`。R1 fix Head `0909acb867577217b91de1bc64edd581f4da403c`、R10 Round 2確認Head `cddbc325c0793fdb41ccb73a3f976de271b34093` | T061/V82へ進めない。productionでは未指名/未確認/資格・根拠不足をfail-closed。rollbackはR1 fix commitをrevertし、production変更は存在しないためDB rollback不要 |
| T060 / R4 unblock delta | R1.1〜R5、platform-invariantsのMigration順序、parallel-execution-planの着手時latest再確認 | `src/test/java/com/ses/migration/SpecDispatchConsistencyTest.java`, `.kiro/specs/dispatch-outsourcing-compliance-ledger/review-ledger.md`。migration/DDL/production code/tasks checkboxは変更しない | `mvn -B -Dtest=SpecDispatchConsistencyTest test`: **8 tests, failures 1, errors 0, skipped 0**。failureは期待された安全側検出で、`S10 dispatch-outsourcing-compliance-ledger の予約V82が実在最新V83以下`を報告。`git diff --check`は対象差分で確認する。ローカル既定DB read-only: `flyway_schema_history` latest successful V74、V82/V83なし。非ローカル環境は未確認 | R4固定範囲 Base `1fd0f7492ab46388c961e2e721ccdedd416929c4` → Decision fix `a1f5e8e8c5b8b559520109a43c61e59f56ab8243` → Packet Head `87a901375ec94dcb7093fdd2e863ed1b8b109a56`。guard fix commit `066a61f0e21c1f7d620f825ce5e721d53eb3c770` → ledger sync commits `8b772adcb801c013d347ca097ac8100c544d0ae4`, `bfc4ca3f1c0fdb8d7b0fac4507527f2090f4dc4f`, `23d6845e2284793404e0910948f92e2da48d2b96` | R4-P1-01がOPENの間はdeploy freeze、T061/V82/production変更を開始しない。rollbackはこのテストガードとledger追記のrevertのみで、DB変更はない。S11 attendance変更はstageしない |

## R10 Issue Register

| issue ID | Review status | Implementer status | violated / location | fix evidence | verification / next action |
|---|---|---|---|---|---|
| dispatch-outsourcing-compliance-ledger-R1-P1-01 | **VERIFIED_CLOSED** | **FIXED_BY_IMPLEMENTER** | T060 Objective/L0全項目網羅、R2.1／field-mapping.md SRC-E section・SRC-L section・2026-10行 | SRC-E「社会保険の加入手続きが完了していない場合の理由（⑱）」とSRC-L「60歳以上か否かの別（④）」を独立mapping行へ追加。4公式PDFに確認できない2026-10通知行を削除し、一次source特定gateへ戻した | R10 Round 2がmapping 96行、全mapping行11列、SRC-E⑱=1、SRC-L④=1、根拠なし2026-10行=0、`git diff --check` exit 0を確認。新規P0/P1なし |
| dispatch-outsourcing-compliance-ledger-R1-P1-02 | **VERIFIED_CLOSED_BY_DECISION_CHANGE** | **FIXED_BY_DECISION_CHANGE / REVIEWED** | T060 Demo、tasks.md、review-ledger.md／社内責任者の実actor・承認日時・mapping version/hash・source版・status証跡 | R10 Round 4のDecision fix `a1f5e8e8c5b8b559520109a43c61e59f56ab8243` が、自然人の事前固定ではなくG2-DEV-GATE、role lifecycle、runtime assignment、本番fail-closedの分離を確定。対象Packet Head `87a901375ec94dcb7093fdd2e863ed1b8b109a56`、mapping blob `32fdb05b00509aab8002a68ba9fa728db8fab36c`をR10が確認した | R10 Round 4がVERIFIED_CLOSED_BY_DECISION_CHANGE。runtime role assignment、実actorによる承認event、資格/根拠確認はM / 本番設定gateとして残し、開発T061をこのP1で停止しない。ただしR4-P1-01が別途OPENのためT061/V82は開始不可 |
| dispatch-outsourcing-compliance-ledger-R4-P1-01 | **OPEN** | **FIXED_BY_IMPLEMENTER / ENVIRONMENT_EVIDENCE_REQUIRED** | `parallel-execution-plan.md:63,70-74`、customer-product-expansion README:75、dispatch `tasks.md:9` のV82→V83順序、out-of-order禁止、着手時latest再確認。既存の`SpecDispatchConsistencyTest`は予約番号と同値の実在だけを検査し、reserved `<=` latestを検出できなかった | `SpecDispatchConsistencyTest.予約Migration番号が実在スクリプトと衝突しないこと()`へ`reserved <= latest`の検出を追加。現repoはV83が実在しV82が未実在であるため、テストがS10の予約V82を実在最新V83以下として報告する。ローカル既定DBはread-only確認済みでlatest successful V74、V82/V83履歴なし。staging/production等の非ローカル環境の適用状態は証明できず、推測・捏造しない | deploy freezeを継続し、環境ごとのread-only `flyway_schema_history`（V82/V83のversion、success、installed_on、checksum）を取得する。全環境でV83未適用ならV82を先にmerge/applyしてからV83へ進む順序を固定する。1環境でもV83適用済みなら、予約表・README・parallel plan・全派工資料を同一decisionで次の未使用番号（実在latestに応じたV84以降）へ繰り上げ、legacy fixtureを追加し、guardがPASSすることを確認する。証跡・decision・direct regression PASSが揃うまでVERIFIED_CLOSEDにしない |

## R10 Round 3 判定

- 判定: `FAIL: open blockers=dispatch-outsourcing-compliance-ledger-R1-P1-02`
- P1-01: `VERIFIED_CLOSED`維持。新規P0/P1なし。
- P1-02: `OPEN / APPROVAL_REQUIRED`維持。対象mapping blob `80fe732df1553f5d9a21b6776d8288419f29d9cc` と一致する実actor、権限、承認status、承認日時、公式source版を含む証拠が未提出。
- T060/F1: `[ ]`、T061/V82: 未開始。
- c34ba6f以降のS10 fix delta・承認eventなし。S11 attendanceの追加commit/dirty変更は本specのReview対象外。
- 次回Reviewは承認証拠提出後のみ。証拠なしの再Review依頼はしない。

## R10 Round 4 判定とR4-P1-01 unblock

- R10 Round 4のT060判定はPASS。R1-P1-01は`VERIFIED_CLOSED`、R1-P1-02は`VERIFIED_CLOSED_BY_DECISION_CHANGE`。mapping 96行、全mapping行11列、SRC-E⑱=1、SRC-L④=1、根拠なし2026-10行=0、SpecDispatchConsistencyTest 8/8、`git diff --check` exit 0をR10が確認した。
- 新規R4-P1-01はOPEN。PacketのBase/HeadにV83が実在しV82が不存在で、V82→V83の予約順序と矛盾する。現在のmainでもV83 scriptが存在し、V82は未作成であるため、T061/V82作成を開始しない。
- direct regressionは、guard追加後に現状態を8 tests / 1 failure / 0 error / 0 skippedで検出した。これは誤検知を隠さずdeployを止めるための期待されたfailであり、R4-P1-01の環境証跡・順序決定が未完了であることを示す。
- ローカル既定DB（2026-08-09 read-only確認）の`flyway_schema_history`は成功済み最新V74、V82/V83なし。staging/production等の環境情報は未取得であり、全環境のV83未適用証明にはならない。環境ownerはread-only証跡を提出するまでdeployを凍結する。
- このdeltaはT060文書、DDL、migration、SecurityConfig、production codeを変更しない。S11 attendanceの変更は除外した。

## M / 本番gateと再開条件

- `COMPLIANCE_RESPONSIBLE` のruntime assignment、資格/根拠の確認、法定責任者の事業所/契約assignmentは、M / 本番設定gateとして実装・設定する。承認eventには実際のactor user ID、表示名snapshot、role、日時、mapping version/hash、根拠資料を保存する。
- 2026-10-01施行分の待遇差説明を求める権利の正確な文言・対象範囲は `GATE-T060-2026-10` としてB1/T066で確認する。`MAPPING-2026-07`へ遡及しない方針は確定済み。
- 個別契約書・就業条件明示書・派遣先通知書のarchive retention categoryは `GATE-T060-RETENTION` としてT061/B1で具体化する。派遣元管理台帳の派遣終了日から3年間保存だけを公式記載のbaselineとする。
- 抵触日算定のクーリング期間値と組織単位変更の同一性基準は `GATE-T060-COOLING` としてT062/T065で具体化する。
- 外部社労士/弁護士の照合は `GATE-T060-EXTERNAL` としてT066 M / 本番解放前のgateである。

T060からT061へ進む条件は、R10 Round 4で確認済みのT060判定に加え、R4-P1-01について全環境のV82/V83適用状態証跡、V82→V83または繰上げの単一decision、予約表・全派工資料・legacy fixtureの整合、`SpecDispatchConsistencyTest` direct regression PASSがR10により確認されること。R4-P1-01がOPENの間はT061/V82/production変更を開始しない。T061開始時にはmerge済み `db/migration` のlatestを再確認する。R10のRound 4 review後も、tasks.mdのcheckboxはレビュー判定と実装headの同期確認前に変更しない。
