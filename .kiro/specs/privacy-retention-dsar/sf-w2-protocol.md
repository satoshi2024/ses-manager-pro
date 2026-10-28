# SF-W2 独立Journal protocol計画

状態: **技術設計案 / Astra追加Plan再Review待ち**。前fixed head `3e05b32f`のPlan CONDITIONAL findingsを本書で補正した。これはSF-W2実装、migration採番、正式journal運用、DG-07、Privacy/Legal/production承認ではない。SF-W1の限定CODE PASSをNF07全体の完了として扱わない。

## 1. 目的と境界

SF-W2はhold・dispatch intent/authorization・command idempotency・fencingを独立Journalで直列化し、application DBへの再構築可能projectionと署名watermarkを定義する。外部providerを呼ぶworker、実DSAR/disposition、HTTP controller、production用journal接続、実credentialは後続wave/人工gateの対象である。

正本はrestore対象application DBから分離したjournal MySQL instanceとする。synthetic integration testはTestcontainersが起動する一時MySQLをapplication projection用とjournal用に別々に作り、各testで生成した識別子/credential以外から接続設定を受け取らない。`DB_URL`、環境変数、既存service、任意pathを接続元にしない。既存の`TemporarySyntheticHarness`はfilesystem containment用であり、W2のMySQL isolationを満たすものとして流用しない。

productionにおけるinstance/region、耐久複製、鍵分離、運用者、RPO/RTOは未決定であり、synthetic設計から推定しない。rootがmigrationを一元採番するため本書はversion/file番号を予約しない。test schemaはcontainer内でのみ初期化し、production migrationを作らない。

## 2. 正本とrecord構造

保存するsubjectはPIIを含まないtenant-scoped opaque referenceとする。対象IDや外部provider応答のraw body、削除対象contentはJournalへ複写せず、必要な照合値はversion/hashと限定されたresult codeにする。hashも匿名情報とはみなさずアクセス制御対象とする。

| 論理record | unique / version | 役割 |
|---|---|---|
| Partition head | PK `(tenant_id, subject_ref)`、`journal_revision`、`hold_revision`、hold状態、`last_event_hash`、lease generation | 同一subject操作の直列化点。current headはappend eventと同一txでのみ進める。 |
| Event | unique `(tenant_id, subject_ref, journal_revision)`、`previous_hash`、`event_hash`、command/action ID、kind、policy/case/target revision、hold revision、lease generation、時刻 | immutable append-only history。revisionはevent 1件ごとに1増加。event hashはcanonical event bytes、partition、revision、previous hashを含むdomain-separated SHA-256。 |
| Command key | unique `(tenant_id, subject_ref, command_id)`、`request_hash`、結果event revision/hash、ack state | 同一command再送のexact replay。key再利用でrequest hashが違えばconflict。 |
| Action | unique `(tenant_id, subject_ref, action_id)`、unique `(tenant_id, subject_ref, provider_id, provider_idempotency_key)`、policy/case hash、target version/hash、authorization event revision、attempt state | intent/authorizationと各provider attemptを同じactionへ束縛する。raw target value/provider bodyは保存しない。 |
| Lease | partition/action単位のowner、generation、expiry、fence revision | owner takeoverごとにgenerationを単調増加。stale generationは新規dispatchと状態更新を拒否する。 |
| Watermark | unique `(journal_id, tenant_id, subject_ref, checkpoint_epoch)`、signed journal revision/event hash、previous watermark hash、key ID、issuedAt、signature | 独立attestor/witnessが保持する単調latest checkpoint。journal/snapshot内のcheckpoint copyは非trusted cache。 |

全commandはexpected journal revision、expected hold revisionを含み、action commandはaction ID、lease generation、policy revision/hash、case revision/hash、target version/hashも要求する。欠落/未知version、不一致、UNKNOWN/timeoutは拒否またはQUARANTINEDへ倒す。current stateはhead/action projectionを読むが、起動・recovery時にevent chain/watermarkとの連続性を検証し、不整合を自動修復扱いしない。

`journal_revision`は一つのaccepted state-changing commandにつき一つのimmutable eventだけ進めるevent sequenceであり、intent/action subrecordの件数ではない。特にDISPATCH_AUTHORIZED eventのpayloadが完全なimmutable intentを含むため別INTENT eventを追加しない。read-only command/rejected CASはrevisionを進めない。command replayも既存revisionを返すだけでeventを増やさない。hold_revisionとlease_generationは個別意味のfenceでありjournal event sequenceの代用ではない。projectionとwatermarkはevent境界だけを参照する。

source-system identityも合成Bare keyから推定しない。W2のsubject referenceは独立namespaceであり、F1 registryのsource-system immutable namespace/親target bindingと混用しない。

## 3. Transaction、CASとlock order

全状態変更はjournal instance上の単一短transactionで、MySQLのprimary connectionから処理する。read replica/cacheはhold/dispatch判定に使用しない。InnoDB durable commit設定を起動時に検証し、ACKの結果が不明なconnection lossはsuccess/failureどちらにも推定しない。

全更新portでlock順序を固定する:

1. partition headを作成時unique insertし、`SELECT ... FOR UPDATE`でlockする。transactionの先頭で `(tenant_id, subject_ref)` が一致することを確認する。
2. command keyを読み、request hashが一致する既存commandなら既存ackを返す。異なるpayloadは409相当で拒否する。
3. action/leaseを操作する場合はaction row、次にlease rowをlockする。複数subjectをまたぐtransactionは禁止し、batchはsubjectごとの小transactionに分割する。
4. expected `journal_revision` / `hold_revision` / `lease_generation`とpolicy/case/target bindingsを検証し、連番event append、head更新、command結果を同時commitする。各commitはr→r+1。unique制約と`UPDATE ... WHERE revision=:expected`を二重防御とする。
5. commit ACK後にのみ`JournalAck`を返す。commit成否不明時は同command IDをJournalへ照会し、同一payload再送で収束する。Journal ACK前にapplication projectionや外部I/Oを成功扱いしない。

すべてのadapterがこの順を守り、deadlock victimは副作用のない同command retryへ戻す。lock timeout、primary不在、durability設定違反、未知commit結果はfail-closedであり、local DB stateで代替判定しない。

## 4. Typed portと遷移契約

Controllerは追加しない。将来実装は`src/main/java/com/ses/privacy/journal/PrivacyJournalPort.java`をtyped boundaryとし、`JdbcPrivacyJournalAdapter`、`JournalProjectionService`、`SignedJournalWatermarkVerifier`、`HistoricalActionReceiptVerifier`を分離する。外部RPC/production接続方式はこのPlanでは選ばない。

| command | 必須比較値 | atomic Journal event / ACK |
|---|---|---|
| `appendHoldStart` | command ID/hash、expected journal/hold revision、tenant/subject、requester authority | HOLD_STARTED。hold revisionを増分し、以降の新規authorizationを拒否。既存actionは未実行/取消完了と偽らずin-flightを列挙。 |
| `appendHoldRelease` | 上記に加えてrelease authority、SoD proof、active hold ID/revision | HOLD_RELEASED。明示的解除eventだけでholdを解除する。古いapprovalやprojectionは解除根拠にならない。 |
| `acquireActionLease` | expected revision、action ID、holder、lease expiry | generationを単調増分してlease取得。takeoverでは旧generationを即時stale化する。 |
| `authorizeDispatch` | new dispatch時点のpolicy/case署名・anchor期限・revocation、tenant/subject/action/provider、target version/hash、hold revision、current lease generation、expected journal revision | immutable intent全体をpayloadに含めた単一`DISPATCH_AUTHORIZED` eventを**1件/1 revision**で同一tx appendし、action/idempotency keyを固定。commit ACK/authorization proofがない外部呼出は禁止。 |
| `lookupCommand` / `lookupAction` | tenant/subject、command/action ID | read-only照会。UNKNOWN結果の回復に使い、新しいdispatch権限を発行しない。 |
| `recordActionObservation` | 既存intent/action binding、provider receipt/result hash、attempt ID、観測時刻 | receipt/結果の事実をappend-only記録する。承認/leaseが今期限切れでも受け付け、dispatchや新actionの認可には使わない。 |
| `appendWatermark` | signer key ID、連続prefix revision/hash、previous watermark hash | event tailを再読・検証した独立attestorのみが署名済みwatermarkを追加。headとの一致をCASし、分岐/巻戻り署名を拒否。 |

新しい外向きHTTP attempt（同じprovider idempotency keyを使うretryを含む）は新しいdispatchとして扱う。直前にpolicy/case proof expiryを各envelopeとtrusted anchorの最小期限まで確認し、revocation `REVOKED`、`UNKNOWN`、照会不能、hold、target/policy/case revision drift、stale leaseは送信前に拒否する。期限後のstatus照会などread-only recoveryはauthorizationと分離する。Approval/lease expiry後のaction result receiptは、過去にACK済みの同じintent/actionの観測事実であれば記録できる。

新dispatch直前のtarget再読と外部provider呼出の間にはcross-system atomic transactionは存在しない。providerがtarget version/ETag等のconditional preconditionを受け付ける場合は同じversion/hashをrequestへ渡して不一致を拒否させる。provider側preconditionのない不可逆target mutationはraceを閉じられないためProduction adapter allow-listへ登録しない。synthetic harnessではtarget version変更をbarrierで注入し、送信直前の拒否を確認する。

`recordActionObservation`は現在有効なapproval/fenceで再認可しない。receipt verifierは以前のintent、action ID/provider idempotency key、target hash、provider issuer/proof、attemptとの一致に加え、署名者のauthority/scopeと署名時点の信頼状態を検証する。approval/leaseが期限切れでもreceiptは審査する。providerが自己申告する時刻はtrusted time evidenceとしない。receiptを確定する時間証拠はexact receipt digestと署名を暗号学的に束縛し、独立timestamp authorityのtoken、またはleak/compromise前にdurably attestedされたreceipt digestのいずれかとする。dispatch attempt/intent/authorizationの存在時刻はHTTPを試みた事実しか証明せず、その後に作成/改竄されたreceiptの署名時刻証拠にしない。外部receiptがinvalid、不完全、またはactionとの結合を確定できない場合はresult observation hashと固定reason codeだけを監査eventに記録し、raw bodyを捨て、actionはUNKNOWN/QUARANTINEDのままにする。

historical signatureはexact receipt digestに束縛された`trustedReceiptTime`、key-history、署名者authority/scopeで評価する。確定条件は少なくとも`key.validFrom <= trustedReceiptTime < key.validUntil`、既知の通常revocationがある場合は`trustedReceiptTime < revocationEffectiveFrom`、compromiseがある場合は`trustedReceiptTime < compromiseEffectiveFrom`であり、これらを同じprovider/key/action/scopeへ結び付ける。通常rotationはrotation前にこの条件を満たす正確なreceiptを遡及無効化しないが、provider signed timestampだけでは成立しない。receipt到着がrotation後でも、exact digestの独立timestampまたはleak前attestationがあれば検証できる。古いintent/attemptの存在のみではreceipt digestの存在/署名時刻を証明しない。revocation/compromise effective pointまたは鍵履歴の信頼時刻がUNKNOWNならhistorical receiptだけでCONFIRMEDにしない。

既知compromise後の署名に付いたbackdated provider timestampは拒否する。compromise開始時点・範囲がUNKNOWN、exact digest time evidenceがない、署名者scope/署名/key statusがUNKNOWNなら、observation hashと閉じたreason code (`RECEIPT_TIME_UNTRUSTED`、`RECEIPT_SIGNER_SCOPE_INVALID`、`RECEIPT_KEY_HISTORY_UNKNOWN`、`RECEIPT_BINDING_MISMATCH`、`RECEIPT_QUERY_UNKNOWN`)だけ記録してUNKNOWNを維持し、履歴receipt単独で`CONFIRMED`にしない。自由記述のprovider/error文字列やraw bodyをreasonへ埋め込まない。後日compromiseが判明したらtrust-change eventを追記し、影響を受ける過去の`CONFIRMED` claimを再評価してUNKNOWN/QUARANTINEDへ移す。eventやcheckpointを削除/書換えして成功判定を維持しない。

providerが提供するaction ID/idempotency keyを使ったauthoritative read-only queryは、古いreceiptの署名時刻を証明する用途と分ける。queryが現在のprovider outcomeを確定した場合は「現在観測した事実」として同actionへappendできるが、元receiptを遡及的にtrustedとは扱わない。provider identity/TLS/trust scope、action/target binding、query freshness/response signatureを検証し、そのquery自体は新しいdispatchを認可しない。queryが未対応、結果不確定、binding不一致ならUNKNOWNを維持する。expiry/revocation/lease generationが古いことを理由に本当に起きた外部結果を破棄してはならず、事実記録から新しい副作用を起こしてはならない。

`VerifiedDispatchFence`相当のproofはcaller booleanから作らず、journalのcommit済みauthorization eventと一致するcommand/action/event revision/hashをtrusted adapterが読み直してmintする。proofはtenant/subject/case/action/target/policy/hold revision、lease generation、有効期限へ束縛し、一度きりの送信attemptへ転用できない。dispatch前にrevocation statusが`NOT_REVOKED`であることを再確認し、確認時刻とstatus source revisionをauthorization payloadへ固定する。status sourceがUNKNOWN、期限切れ、照会不能ならdeny。revocation発行とjournal authorizationの間に原子的な共通revisionを提供できない実adapterはraceを排除できないため、Productionで有効化しない。

SF-W1現行`VerifiedActionReceipt.matches(..., now)`はreceipt自体のexpiryを現在時刻で要求するため、既認可actionの期限後の事実記録にはそのまま再利用しない。W2/W3では現在の送信可否用`VerifiedDispatchFence`と、journal済み過去authorization+provider署名/attempt/key-history/trusted-time evidenceを検証する`VerifiedHistoricalActionReceipt`相当を分離する。通常rotation、compromise/backdated forgery、historical time unknown、authoritative query、wrong action/target bindingを別々に検証する。後者は信頼が確認できる場合だけjournalへ既発生結果を`CONFIRMED`等の事実statusとしてappendし、信頼を確定できなければobservation hash/fixed reasonと`UNKNOWN`を保持する。authorization proofのmint、POST/retry、別target/caseの処分は一切許さない。これはSF-W1 foundationの改変済みPASS claimではなく、次waveの明示的な拡張と独立Review条件である。

`UNKNOWN` recoveryは同じactionを現在のheadから照会するread-only経路とし、古いcase snapshot/proofを再利用しない。再送が必要なら再度authorizeし、現在も承認/anchor/revocation/hold/target/fenceが有効な場合だけ同じaction/idempotency keyで許す。過去にdispatch済みか不明な場合、provider lookupが未対応またはUNKNOWNなら自動POSTせずUNKNOWNを維持する。取消権威receiptなしにCANCELLEDへ遷移しない。

## 5. Projection順序と障害回復

application DBはJournalの再構築可能projectionであり、authorizationの正本ではない。

1. application commandはまずJournalへappendする。hold startはACKで有効となり、application DB projection更新が失敗/遅延しても認可gateはJournalを照会してholdを拒否する。
2. projection writerはevent revision順に適用し、`last_applied_journal_revision`のCASで二重適用を防ぐ。遅延/再送は同じrevision/hashならno-op、同revision異hash・gap・local revision > JournalはQUARANTINED。
3. Journal ACKの応答が失われたらcommand IDで照会する。確認前にprojectionを先行commitせず、二重command IDで新しいhold/actionを作らない。
4. application DBが過去snapshotへ戻った時は、独立Journalのsigned watermarkとevent chainを検証し、snapshot後のhold/intent/result eventをrevision順に再投影する。二度目の再投影はno-opである。
5. Journal停止、checkpoint欠落/破損、event gap、signature/chain不一致、unknown revision、projectionとheadの不整合時はread/write publicationとdispatchをQUARANTINEDにし、手動の権限付きreconciliationまで解除しない。

projection成功はhold開始のACKを代替せず、local projectionの成功をdispatch権限としない。rollbackはeventを消去せず、hold解除などの補償commandを新revisionとしてappendする。不可逆な外部successをDB rollbackで消去しない。

## 6. 署名watermarkと旧snapshot

各partitionでwatermarkは連続した最大event revisionを証明し、`journalId || tenant || subjectRef || checkpointEpoch || eventRevision || eventHash || previousWatermarkHash || issuedAt || keyId`のcanonical domain-separated bytesへ署名する。署名鍵はSF-W1 synthetic keyとは別のW2 test-only attestor鍵で生成し、production artifactへ入れない。verification trust anchorもharness専用。

event append後にattestorがprimary journalからprefixを読み、event chain、revision、以前のwatermarkを確認して署名する。署名処理はjournal row lock中の外部network I/Oにしない。署名済checkpointと`latest checkpoint epoch/revision/hash`を、application DB snapshot/Journal DBから独立したattestor witnessへ単調appendする。attestor署名鍵とwitness最新pointerが共同のtrust anchorであり、journal/snapshotにコピーされた署名だけではlatest性を証明しない。attestorはevent prefix全体を再検証し、signed revisionがwitness latestより大きい場合だけ次checkpointを発行する。同revision/same hashは同一checkpointを返し、同revision/different hashまたは後退を拒否する。watermark保存は`previousWatermarkHash`、checkpoint epoch、signed event-revision monotonicityをCASする。append ACKと署名watermarkは別の耐久事実として記録する。dispatch workerは必要なauthorization ACKを待ち、SF-W3でwatermark/fence validityを再確認する。

restore gateではまず独立witnessへlatest checkpoint epoch/revision/hashを照会し、そのcheckpoint signature/key statusをverifyする。正しく署名された古いwatermarkと完全な古いprefixを持つsnapshotでも、witness latestよりheadが古ければpublic/write/dispatchを拒否する。復元snapshotがlatest watermarkより古ければlatestまで欠落eventを検証・replayし、head/hashを一致させる。snapshotのheadがlatestより新しい場合、そのsnapshot値だけを信頼しない。latest watermark後のJournal tailは再chain検証と新しい署名attestationが済むまでQUARANTINEDとする。witness不可用/UNKNOWN、署名鍵UNKNOWN/compromised、sequence/hash gap、遅延、改竄、eventがlatest watermarkを越える等のいずれも公開/dispatchを許さない。現行Journalのdurable holdはwatermark作成待ちでもlive authorization gateに即時反映する。

## 7. Synthetic MySQL harness / API制約

- JUnit `@Tag("mysql")`専用classでapplication projection用とjournal用の隔離MySQL 8 Testcontainerを起動する。container ID、random schema/database ID、生成credential、worktree、manifest hashをtest内assertする。system property/env/config fileからDB URLやpathをoverrideできるconstructorを作らない。
- production `prod`または`prod,test` effective profile、container identity不一致、Docker未利用、network/volume unexpected、credentials外部参照は起動fail-fast。synthetic Testcontainer classのskipは禁止し、DockerなしをPASS/skip扱いにしない。
- Spring controller/REST endpointは作らない。typed command port、journal adapter、projection adapter、test-only schema initializer、attestor portだけ。test-only DDLはcontainer bootstrap resource内に限り、Flyway versionは持たない。
- 一時DBにはgenerated opaque subject/action/policy/case、架空target hashだけ投入する。作成前後にcontainer identity/fixture hashを取り、終了後はharnessが所有するcontainer/databaseだけを破棄する。他のcontainer/service/processは探索停止/再利用しない。
- test networkはTestcontainers内のjournal/projection/provider stubへ限定し、外部provider invocationは0。provider stub call counterはtest adapter内だけで観測する。
- 実装時の予定配置は`src/test/java/com/ses/privacy/journal/TemporaryJournalMySqlHarness.java`、`PrivacyJournalLinearizationMySqlTest`、`PrivacyJournalRecoveryMySqlTest`と、Testcontainersだけが読む`src/test/resources/privacy-journal/schema-test.sql`。schema SQLはtest initializer専用でmigration/resource discoveryへ加えない。
- 接続capability生成経路はtest-only `PrivacyJournalTestConfiguration`がSpring `ApplicationContext.getEnvironment()`から得る実Environmentでprofile guardを通した後、`TemporaryJournalMySqlHarness`がTestcontainersの実container objectから開始する。同harnessは実container ID、JDBC host/port、random database ID、container生成credentialを互いに照合し、そのobjectからDataSourceを構築してtest source set内だけのpackage-private `JournalTestConnectionCapability`を作る。capabilityは任意Environment/URL/DataSourceを受け取る公開constructorを持たない。test-only configurationがcapabilityを検証/消費し、同packageのpackage-private JDBC adapter constructorへcontainer由来DataSourceを渡す。main adapterはpublic raw-DataSource constructor、任意DataSource/URL setter、Spring property bindingを持たない。mainは`PrivacyJournalPort`だけを公開し、production用journal bean/secret/key/provider fallbackを持たない。test source set外からDataSourceを差し替えるpublic capability経路は存在しない。
- capability生成とport invocationの両方で`ApplicationContext`の実`Environment`を照合する。active/default profileのunion/effective profilesのいずれかに`prod`があれば拒否し、synthetic adapterはactive profileに`test`が明示される場合のみ許す。`prod,test`、activeなし/default prod、test不在、caller指定`isTest=true`だけは全拒否する。guardはcontextから取得したSpring Environmentを直接参照し、clientからenvironment/audienceを受け取らない。W2にproduction adapter beanを追加せず、main `JdbcPrivacyJournalAdapter`はpackage-private constructorによりtest package以外へraw `DataSource`を公開しない。productionではport beanなしをfail-closedとし、後続production adapter/bean追加には別Plan/人間gateを必要とする。
- attestor test private keyと独立latest-witnessは`src/test/java/com/ses/privacy/journal`のharnessが生成する。attestor/witness用MySQLはapplication DBとJournal DBとは別の3つ目のTestcontainerとして起動し、journal/snapshot restore対象外にする。attestorだけがappend可能なcheckpoint logとmonotonic latest pointerを保持し、projection/Journal userにはUPDATE/DELETE権限を与えない。旧application+journal snapshotを復元してもwitnessのlatest pointerが前進したままであることを検証する。real witnessのWORM/KMS/運用者権限分離はproduction人工gateであり、このMySQL構成を本番nonrollback保証とは呼ばない。main artifactにprivate key/test signerを含めない。test run終了時にharness所有3 containerのみ破棄する。
- historical receiptのtrusted-time test authorityはprovider鍵/attestor鍵と独立したtest-only Ed25519 keyを生成し、exact receipt digest + exact signature bytes + trusted timestampへdomain-separated署名する。main source/artifactに鍵/fixture signerは置かない。production TSAまたは同等の事前digest attestor、retention、compromise通知源の選定は人間/Operations/Security gateに残す。

## 8. lock order・failure・acceptance matrix

全並行試験は同じpartitionを別JDBC connection/threadで操作し、barrier/latchで競合開始点を固定する。sleepと直列呼出から線形化を推論しない。matrixの`dispatch call`はsynthetic provider adapterへの外向き副作用試行数を示す。

| Case | Setup / fault injection | 必須結果 |
|---|---|---|
| P1 hold vs dispatch: hold wins | head rev r/hold h、別connectionで同一expected revのholdとauthorizeをbarrier開始 | holdだけがr+1でACK。authorizeはconflict/hold blocker、intent/authorize eventなし、dispatch call=0。 |
| P2 hold vs dispatch: authorize wins | authorize commitを先にbarrier確定してからholdを送る | authorize r+1 ACK、その後hold r+2 ACK。後続新規authorization拒否。既存actionはin-flight/UNKNOWNのまま列挙され、cancel/unsent/successを推測しない。 |
| P3 CAS + command replay | 2 connectionが同じexpected revisionで違うcommandを送信、同一commandのresponse-loss retryを注入 | 1遷移だけcommit。exact same ID/hash retryは同じack revision/hash、同ID異payload拒否、event/hold count増分は一回。 |
| P4 action idempotency | 同action ID/provider keyの二worker、same key/different request hash | 一つのintent/actionに制約。conflicting body拒否。結果不明時にPOSTを重複発行しない。 |
| P5 stale lease | generation n workerをpauseしtakeoverでn+1、nが新dispatch/updateを試行 | stale nの新規dispatch/head update拒否。既に起きた可能性のあるprovider result receiptはaction-bound factとして追加可能。 |
| P6 new dispatch expiry/revocation | approval/anchor expiry、revoked、revocation UNKNOWN、revocation source timeoutを個別注入 | 各新規送信/retry拒否、journal blocker記録、dispatch call=0。read-only lookupはactionにだけ束縛。 |
| P7a normal key rotation late receipt | 期限内にACK済みintent/authorization/provider attempt、exact receipt digestへ結合したtrusted-time tokenまたはrotation前attested digest、当時validなprovider signing keyがあり、通常rotation後にlate receipt到着 | 旧keyの当時scope/署名/validityとexact digest時刻を検証し、same actionの事実statusをappend。現在のapproval/lease expiryで拒まない。新dispatch権限は0。 |
| P7b compromised key/backdate | key compromise開始点を記録後、そのkeyで開始点以前とself-claimedする署名receiptを送る。独立time proof境界の前/同時刻/後も注入 | exact digestの`trustedReceiptTime`がkey validity内かつ厳密にcompromise effectiveFromより前の場合のみhistorical signatureを信頼。receipt内自己申告時刻、dispatch attempt時刻、同時刻/後のproofではhash/fixed reasonだけ、UNKNOWN維持。 |
| P7c historical time unknown | key scope/historyはあるがtimestamp trust evidenceが欠落、またはcompromise interval不明 | observation hash + 固定reason、UNKNOWN。raw bodyは保存せず、既存成功statusを新規に確定しない。 |
| P7d authoritative query | expired/untrusted old receiptの同一actionをproviderの権威あるread-only queryで照会 | queryのcurrent outcomeだけを新たな事実として同actionへ記録。query証明は過去receiptの署名/時刻を遡及的に信頼化しない。query unavailable/unknownではUNKNOWN。新規dispatch=0。 |
| P7e wrong historical binding | valid signature/time evidenceを持つreceiptのaction ID、provider key、target version/hash、tenant/subjectの一つを差替 | いずれもhistorical proof不一致でhash/fixed reasonだけ記録、既存actionはUNKNOWN。新規target/actionへ結合しない。 |
| P7f post-confirmation trust change | exact receipt digestをCONFIRMED後、同keyのcompromise/unknown effective pointまたはscope invalidation eventを追加 | trust-change eventをappendし、範囲内/不明の既存claimをUNKNOWN/QUARANTINEDへ再評価。旧event/signature/checkpointを削除/改竄せず、再処分=0。 |
| P8 revision drift | policy/case hash/revision、target version/hash、hold revisionを一つずつ変更 | 新しいdispatch authorizationを拒否。old signed snapshotを使ったproof/fence mintなし、dispatch call=0。 |
| P9 commit response lost | append transaction commit後にclient応答を切断、同じcommandをquery/retry | lookupで既存revision/hashを返し、同eventを二重appendしない。未commitならACK/dispatchなし。 |
| P10 projection failure | Journal hold ACK後にprojection DB停止、retry/replay二重実行 | Journal holdは有効、認可入口は拒否。projectionはrevision CASで回復し、二重eventなし。 |
| P11 signed old snapshot | snapshot Rより新しいHOLD/intent/receiptとlatest signed watermarkを用意して二度restore | witnessのlatest checkpointまでsignature/hash chain検証しreplay、二度目no-op、hold保持。再公開前にheadとwitness latestが一致。 |
| P12a valid but old watermark rollback | valid signatureと完全prefixを持つold snapshot/watermarkをlatest witnessより前のrevisionでrestore | signature自体がvalidでもwitness latestとのfreshness不一致でpublic/write/dispatchすべて拒否、QUARANTINED。 |
| P12b gap/tamper/witness failure | watermark未知key/compromised、witness unavailable/rollback、sequence gap、hash改竄、latest後tail未署名 | restore publication/dispatch拒否、QUARANTINED継続。修正値/水位を実装が推定しない。 |
| P14 authorize tx crash boundary | DISPATCH_AUTHORIZED compound eventの各書込段階後にconnection abort/deadlockを注入 | commitしないケースはevent=0、intent=0、head/action projection不変、ACK=0、provider call=0。commitして応答だけ失った場合だけ同command queryで単一event/intentを回収。 |
| P15 test capability/profile bypass | 任意DataSource/DB_URL、default prod、active prod、mixed prod+test、caller `isTest=true`、test permitなしをそれぞれ構成 | synthetic adapter/fixture鍵/test schemaを取得できず、DB/attestor connection試行=0。active test + harnessが生成したcontainer capabilityだけ許可。main adapterにpublic raw-DataSource constructorがない。 |
| P13 journal unavailable/unknown ACK | DB停止、timeout、deadlock、durability config不適合を注入 | hold/authorization ACKなし。projectionだけで認可せず、外部呼出=0。timeoutはcommand lookup後にexact retry。 |

## 9. wave分割とReview gate

| subwave | 追加予定 | exit condition |
|---|---|---|
| W2a journal schema/adapter | typed port、logical test schema、partition head/event/command/action/lease CAS、hash chain | lock order、durability、unique constraints、P1–P5/P9/P13を専用MySQL integrationでzero skip検証 |
| W2b projection/attestation | revision CAS projector、test-only signed watermark attestor/latest-witness/verifier、replay/quarantine API、test-only connection/key capability | P10–P12/P14、snapshot repeatability、old watermark rollback rejection、UNKNOWN hard stop。freshness/witness failureでpublic stateなし |
| W3 separate | dispatch worker/provider adapter、expiry/revocation revalidation、current/historical receipt verifier分離、UNKNOWN recovery | P6–P8、P7a–P7f、送信前/送信後/crash/retry、late result factual ingestion、key compromise trust-change。W2 plan/code review後に別scope |

各subwaveはfixed HEAD、schema/test hashes、container identity、0 skip、外部provider call=0をAstraへ提出して限定Reviewを受ける。Review PASSはそのsynthetic protocol incrementに限る。正式owner、policy/legal retention、実provider、production journal、migration番、releaseは人間gateでありこの計画では承認しない。

## 10. 参照契約

- `.kiro/specs/privacy-retention-dsar/synthetic-foundation.md` §§SF-03a/SF-04a（線形化、intent先行、復元）
- `C:\work\ses-snf-status-audit\.kiro\roadmap\2026-10-01-snf-production-nf07-foundation-contract.md` §§SF-03a/SF-04a（control履歴、read-only）
- `C:\work\ses-snf-status-audit\.kiro\roadmap\2026-10-01-snf-production-plan-review-r3.md` NF07（取消receipt、expiry/revocation差分、hold/dispatch gate）
- Astra SF-W1 limited CODE PASS: `6ec4ca2837502b9defc33a7a4fe938b5e9d3ecd9`。SF-W2は独立追加Plan Review待ち。

## 11. W2a実装境界で判明したproof未完了事項（追加Plan review待ち）

この節は元のSF-W2 Plan PASSを置き換えず、W2aコード上の境界を明確にする追記案である。ここに書くproof/verifierはW2aでは実装しない。

### 11.1 hold解除proof草案

SF-W1の`VerifiedApproval`はpolicy/case、target、action/provider、revision、expiryを束縛するが、hold識別子、hold解除authority scope、解除理由、解除主体の権限、hold requesterとのSoDを持たない。`VerifiedDispatchFence`もW1ではjournal eventからの発行経路がない。したがってどちらもhold解除の資格証明として再利用しない。W2aの`HOLD_RELEASED` commandは権限モデルが未実装のためfail-closedとし、解除eventをappendしない。

後続Planでは専用のprocess内`VerifiedHoldRelease`を導入する案とする。これはverifierだけが生成し、最低限次のcanonical bindingを含める。

`tenant || subjectRef || holdId || expectedHoldRevision || action=HOLD_RELEASE || reasonHash || commandId || expectedJournalHead`

issuerとapproverは、trust anchorで検証された署名付きauthority claimにより、上記の正確なtenant/subject/hold/action scopeについて解除権限を持つことを証明する。actor名やrole文字列、caller booleanからproofを作らない。要求者・issuer・approverの必要な分離条件を検証し、同一主体またはscope外authorityは拒否する。trust anchor不明、署名不正、失効状態UNKNOWN、期限切れ、理由hash不一致はいずれも拒否する。

Journalはproof bindingとcommand payload hashを照合した後、partition headとhold revisionをlock/CASし、expected headが一致する場合だけ一つの`HOLD_RELEASED` eventをappendする。exact同一command ID/hashの再送は元ACKを返し、同ID異payload、古いhead/hold revision、異なるhold IDは競合として拒否する。解除event前後のtransaction失敗ではhead/event/command projectionを一切進めない。実装着手前にこのReleaseProofとauthority SoD契約を別Plan Reviewへ提出する。

### 11.2 W2a dispatch ACKの非代替性

W2aで`DISPATCH_AUTHORIZED`というsynthetic event名を使う場合も、そのACKは同一partitionのhead CAS、hold blocker、lease fenceに関する合成線形化記録に限る。署名済policy/case approval、expiry/revocation freshness、dispatch fenceを検証した証明ではなく、外部送信を許可しない。W2aにはworker/provider接続もproduction beanも存在しない。後続workerはこのevent/ACKだけで外部副作用を起こしてはならず、別のreview済みdispatch proof/verifier契約が必要である。

W2aのReview packetは`HOLD_RELEASED proof未実装`と`実policy/expiry/revocation dispatch proof未実装`を明示し、このprotocol全体またはNF07全体の完了へ読み替えない。

### 11.3 W2aの実装時に固定するDB境界

lease expiry/takeover判定にはcommandから渡された時刻を使わず、adapterへ注入されたtrusted `Clock`の現在値を使う。event時刻は同じclockから記録し、MySQL `TIMESTAMP(6)`とhash再計算が一致するようInstant/lease expiryをmicrosecondへ正規化する。typed commandからevent時刻を指定するfieldは持たせない。

append前にprimary接続上で`innodb_flush_log_at_trx_commit=1`、`sync_binlog=1`、binary log有効、read-only無効、`InnoDB`既定engine、全journal tableのInnoDB engineを確認する。不適合や照会不能ならjournal ACKを返さずfail-closedにする。合成Testcontainerはこの必要条件を満たす設定で起動し、test内で不適合設定を注入して拒否を検証する。これは実運用HA/WORM/独立witnessの証明ではない。

tenant/subject/command/action/worker等のopaque identityは大小文字と末尾空白を区別するbinary NO PAD collationを使用する。command replay keyは`(tenant, subject, commandId)`であり、別partitionの同じcommand IDを衝突扱いしない。headに格納するheld/lease generation/owner/expiryはevent chainから再構築して照合し、head fieldの直接改変も拒否する。

実装sourceはW1の`VerifiedApproval`等と同じ`com.ses.privacy.foundation` packageに置くため、synthetic JDBC adapter constructorはpackage-private、接続capabilityとMySQL initializer/test keyはtest source setだけに置く。この配置は既存NF featureや共通DB接続へ公開beanを追加するものではない。
