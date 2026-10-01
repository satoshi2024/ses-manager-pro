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
