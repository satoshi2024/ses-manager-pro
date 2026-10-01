# Production Readiness 修復Headの独立Review記録

各判定は対象diffに限定する。feature全体、未実施suite、production approvalへ拡張しない。

## C0 Browser fixture

- 実装: Luna / `0d78cc13400355363cb95af8e1a97d24a10a5504`
- Reviewer: Astra / `astra_review_baseline`、read-only、試験再実行なし。
- 判定: **CODE PASS**、新規P0/P1/P2 = 0/0/0。
- 原seed `101246425020` の修正前14/0/1/0と、修正後同seed14/0/0/0、
  別seed `987654321` 14/0/0/0を、runごとの9 Surefire XMLとlogで独立照合した。
- 本testがinsert成功したengineer/user組だけを記録し、AfterEachで精密削除する。
  既存linkを削除せず、unique例外の抑制、制約緩和、順序固定、skipを追加しない。
- 限界: 全fixtureの完全隔離、JVM強制終了後の復元を証明していない。
  既存linkのtenant変更を復元しない分岐は既存動作であり、本差分の阻害findingではない。
- control統合commit: `778e372d585d7e5a6a98a3899667ca18dfe97b3d`。
  統合後全suite/remote main CIは未実行であり、main CI失敗をPASSへ更新しない。

## R03 training

- 実装Head: `5facee224d4e59dad15981d29b3accb8b89c9661`
- Astra独立照合: TrainingPlanServiceTest XML/text 16/0/0/0。限定修復のCODE PASS、検証P2が1件残る。
- complete/cancel/enrollは共通のtenant-bound plan lockを使用し、非取消件数>0かつ全件COMPLETEDでのみ終端化する。
  event keyはenrollment ID/versionを含み、更新とeventは同一transaction。
- P2: Mockito/ReentrantLockの同一enrollment再送試験では、異なるenrollmentを持つ同planの
  MySQL実並行を証明していない。complete×2、complete/cancel、enrollと最終complete、rollback、
  空集合と両取消順序の追加回帰待ち。全NF03/production承認ではない。未統合。

## R09 provider境界

- 実装Head: `bbeca7c7462ae298160849dc44503a243888dc1c`
- Astra: CONDITIONAL PASS、新規P0/P1なし。prod/mock混在拒否とfail-closedの4 profile試験を照合。
- 残るP2: 実FailClosed client→ExternalAccountService→offboardingの保存状態/確認event/retry/clearanceを
  integrationで示すこと。既存testのinstanceofによる早期returnをPASS証明に使わない。
- 実providerなしの安全拒否と実provider導入完了を混同しない。
- 回帰追加Head `02105ce6a92d61736d44ce727f028059618a64c4` は対象限定5/0/0/0。
  root照合で非transaction testのfixture後始末不足、固定engineer/actorと実scopeの不一致を検出したため、
  isolated tenant/owner、精密cleanup、確認正本列の不在証拠を補強した。
- 最終Head `da850b97b93a870a0e3888e95fd5557cf829e97a` のAstra再Reviewは **CODE PASS**、P0/P1/P2=0/0/0。
  profile 4/0/0/0＋同JVM integration反復2/0/0/0をraw XML/textで照合し、以前の接続回帰P2を閉じた。
  prod/mixed profileは単一fail-closed bean、独立tenant/法人/HR/Engineerと精密cleanup、
  実service/mapper経由のPENDING→UNKNOWN、確認時間/人/actor/source=NULL、clearance拒否を確認した。
- domain-event tableが存在しないため、架空のevent countを記録せず確認正本列をassertした。
  full prod Spring起動、NF01 completeCase、全cleanup障害窓、実Keycloak/session/token失効の証拠ではない。
- control統合Head: `036b3eed83cad61d385cce1973b7db5838d0619f`。最終全suiteは未実行。

## R01a / R03 最終限定再審査

Reviewer `astra_review_baseline`（gpt-6-astra）が固定Headとraw証拠をread-onlyで確認した。
新規試験は実行していない。いずれも **CODE PASS / 新規P0/P1/P2=0/0/0**。

- R01a `ced643043de8bec2cf00588b93bf2eedd5c80513`: V183のtenant/月序号行を同一transactionで
  原子的に更新し、並行番号重複・9999以降・容量上限を修復。DBで再取得したactive actorの
  role/tenantを冪等応答前に検査する。H2 26/0/0/0、MySQL 3/0/0/0の成功logと新規回帰を照合。
  既存2件のP2を閉鎖。control統合は`9a456611`、`ced8aa59`。
- R03 `893849284bf57803b8f9480adbc33a4a6b862f8a`: Docker欠如によるskipを除去。
  complete/cancel両順序は実競合、双方connectionと対象plan PRIMARY RECORDを結ぶlock probe、
  requester connection IDとservice操作の同transactionを確認。MySQL 4/0/0/0。
  最終XML SHA-256 `A79A84CBF7F836F0658E0AE3E65001AD05B2E528A882BB743D6986CD08E41093`一致。
  並行証拠P2と後続補正を閉鎖。control統合は`8f19cf43`〜`626aa967`の5commit。

これらは全NF01/NF03の受入・production承認ではない。NF01のDUAL二者確認、document ACL/version、
durable撤権trigger、NF03 retention/Privacy gateと全体受入は未完。統合後full suiteは未実行。

## NF07 SF-W1 限定再審査

- 固定実装Head `6ec4ca2837502b9defc33a7a4fe938b5e9d3ecd9`、Astra **CODE PASS**。
  新規P0/P1/P2なし。元のP1（認可後取消の迂回）とP2（authority期限の持越し）は閉鎖した。
- DISPATCH_AUTHORIZEDからCANCELLEDへの直接遷移を削除し、receipt無し・通常結果receipt・
  別action receiptでも拒否する。認可後のin-flightを取消要求だけで消去しない。
- VerifiedApproval期限はpolicy/case envelopeと両authority validUntilの最小値。
  envelopeより早いanchor失効時点から状態進行を拒否する回帰を追加した。
- raw XMLの9+5+4=**18/0/0/0**と文書内SHA-256一致をReviewerが確認した。
  control統合commitは`756d2a5e`、`985eedf7`。
- SF-W2 journal/CAS、durable intent、dispatch直前の失効再検証、実DB並行は未実装。
  worker/取消確認/復旧は後続波。DG07、Privacy/Legal、実処置、production承認を付与しない。

## NF01 既存fixture追従

`e3944774e667a226848d3883e7333ddb8b2ef1f6`は明示tenant/active DB actorを既存2 classへ設定した。
対象5/0/0/0、Astra CODE PASS。製品側境界とdocument/cancel断言を維持し、control `e90daaf1`へ統合した。
全fastの再実行は別gateとして残る。

## NF10 session-bound再認証

- 固定Head `20cce0f72734ac1dad9a9f07278475eb4a7fe048`、Astra **限定CODE PASS**。
  共有deliveryの再認証timestampで別sessionも許可されたP1は閉鎖。新規P0/P1/P2なし。
- grantをsession ID、Authentication instance、tenant/user/delivery、scopeHash、authorities、
  10分期限へ束縛し、rotation/logout/別sessionで継承しない。downloadで現行DB active userと
  recipient previewを再検証する。grant発行はtransaction afterCommitに限定する。
- 監査timestampが残ってもgrant発行失敗時の認可根拠にしない。
  integrationは自身のuser/run/deliveryだけを作成・精密削除し、seed adminを変更しない。
- 最新単一runのraw XMLは**46/0/0/0**。10 report hashとsource patch hashの一致をReviewerが確認した。
  `docs/review/NF10-reauth-session-boundary-evidence.md`に履歴と最終runを区分した。
- control統合: `4766936e`、`cc6510d5`。
- integrationのrecipient previewはmock。実DBのrole/組織再計算は静的照合であり、
  commit故障注入・実browser・session replicationの動的証拠は未取得。
  全NF10受入、staging、production releaseの承認を付与しない。

## NF06 F2 初回審査

- 固定Head `8913518863bee02124ae715fde5dc7d8a33f3ce3`、Astra **限定CODE FAIL**。
  P0/P1/P2=0/1/2。controlへ未統合、修復と再審査待ち。
- P1: `ImportAmountParser`がprecision検査より先に`setScale(0)`を行うため、
  短い巨大指数入力で多量の計算/メモリを消費し得る。変換前の数値文法/桁数制限が必要。
- P2: Contract startDateの空値/明示NULLが必須検査を通過する。
- P2: Proposalのpositionとprojectを個別解決するだけで所属関係を照合していない。
  関係不一致または検証不能は拒否する必要がある。
- raw XML 6+9=15/0/0/0とhash一致は確認したが、上記3条件をカバーしていなかった。
  対象test成功を安全境界全体のPASSとしない。
- resolver実adapter、job/mapping identity、apply/UI/DDL、transaction/recoveryは後続波。

### NF06 F2 再審査

- 最終Head `1e8aa651e6ec4901e1e498aca1a484a7b88fe4c8`、Astra **foundation限定CODE PASS**。
  上記P1×1/P2×2は閉鎖、新規の確認済みfindingなし。
- 金額をBigDecimal構築前にASCII十進文法/64文字/有効整数18桁へ制限し、指数を拒否。
  Contract startDate必須値と、同tenant/legalEntityのposition→project親key照合を追加した。
- raw XML **11+6=17/0/0/0**と2 hashを照合。control `a79e2171`、`5b2e5db3`へ統合した。
- 現ValidationContext/resolverは単一synthetic namespaceに限定する。実registry接続前には
  immutable sourceSystemか出自を持つ親target IDをbindingし、裸externalKeyの跨source比較を禁止する。
- 18/19桁、64/65文字、負零の境界は静的照合のみ。次波で回帰へ追加する。
  完全なF2 Demo、実resolver/apply、NF06全機能・production受入は未完了。

## NF07 SF-W2a 初回静的審査（2026-10-02）

- Reviewer: astra_review_baseline / gpt-6-astra。作業tree adapter hash
  `2A3D30D5F2050ED37B25863D3B1D2030AD1A33D158DF017FEA8EA1BB0ABE360D`、port hash
  `FC9FD31041E5ACD965B0B36FDFE0D5947314F1679467D8CF525DD870094ABF96`。
  **CODE FAIL、P1×2/P2×2**。未統合。単一journalのsynthetic incrementのみ対象。
- P1: command projectionのrevision/event hashをevent正本へ照合せずreplay ACKへ返している。
  正本にないACKや誤bindingを拒否し、command欠落/改竄の回帰を追加する。
- P1: action projection欠落時に同actionの別commandを再認可できる。command/action/leaseを
  eventから再構成して正確に照合し、欠落/余分/異なるcase/hash/世代を拒否する。
- P2: eventsとheadの独立autocommit読取は合法的な並行appendを破損と誤認する。
  同partitionの一貫したtransaction/lockで検証し、head単独でも未検証projectionを公開しない。
- P2: writerの全table UPDATE権限は過剰。headのみUPDATE、履歴tableはSELECT/INSERTとし、
  writerで履歴UPDATE/DELETEが失敗することを確認する。破損注入には別admin接続を使用する。
- P1並行testの開始barrierだけではlock待機を直接証明しておらず、P5も逐次試験である。
  connection/partition/PRIMARY lockを結ぶ直接証拠と必要な双方順序を追加する。
- trusted clock、microsecond正規化、binary identity、durability/profileチェックの方向は妥当。
  HOLD_RELEASEDは未実装として拒否し、synthetic dispatch ACKを実送信権限として使わない。
- MySQL初回runはBoundary testのInstant import不足でtestCompile FAIL。testsは未実行であり、
  既存pure 3件成功と合算しない。原因修復・上記finding対応後に再実行する。