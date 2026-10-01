# SF-W2 独立Journal protocol計画

状態: **技術設計案 / Astra追加Plan Review待ち**。これはSF-W2実装、migration採番、正式journal運用、DG-07、Privacy/Legal/production承認ではない。SF-W1の限定CODE PASSをNF07全体の完了として扱わない。

## 1. 目的と境界

SF-W2はhold・dispatch intent/authorization・command idempotency・fencingを独立Journalで直列化し、application DBへの再構築可能projectionと署名watermarkを定義する。外部providerを呼ぶworker、実DSAR/disposition、HTTP controller、production用journal接続、実credentialは後続wave/人工gateの対象である。

正本はrestore対象application DBから分離したjournal MySQL instanceとする。synthetic integration testはTestcontainersが起動する一時MySQLをapplication projection用とjournal用に別々に作り、各testで生成した識別子/credential以外から接続設定を受け取らない。`DB_URL`、環境変数、既存service、任意pathを接続元にしない。既存の`TemporarySyntheticHarness`はfilesystem containment用であり、W2のMySQL isolationを満たすものとして流用しない。

productionにおけるinstance/region、耐久複製、鍵分離、運用者、RPO/RTOは未決定であり、synthetic設計から推定しない。rootがmigrationを一元採番するため本書はversion/file番号を予約しない。test schemaはcontainer内でのみ初期化し、production migrationを作らない。

## 2. 正本とrecord構造

保存するsubjectはPIIを含まないtenant-scoped opaque referenceとする。対象IDや外部provider応答のraw body、削除対象contentはJournalへ複写せず、必要な照合値はversion/hashと限定されたresult codeにする。hashも匿名情報とはみなさずアクセス制御対象とする。

| 論理record | unique / version | 役割 |
|---|---|---|
| Partition head | PK `(tenant_id, subject_ref)`、`journal_revision`、`hold_revision`、hold状態、`last_event_hash`、lease generation | 同一subject操作の直列化点。current headはappend eventと同一txでのみ進める。 |
| Event | unique `(tenant_id, subject_ref, journal_revision)`、`previous_hash`、`event_hash`、command/action ID、kind、policy/case/target revision、hold revision、lease generation、時刻 | immutable append-only history。event hashはcanonical event bytes、partition、revision、previous hashを含むdomain-separated SHA-256。 |
| Command key | unique `(tenant_id, subject_ref, command_id)`、`request_hash`、結果event revision/hash、ack state | 同一command再送のexact replay。key再利用でrequest hashが違えばconflict。 |
| Action | unique `(tenant_id, subject_ref, action_id)` とprovider idempotency key、policy/case hash、target version/hash、authorization revision、attempt state | intent/authorizationと各provider attemptを同じactionへ束縛する。raw target value/provider bodyは保存しない。 |
| Lease | partition/action単位のowner、generation、expiry、fence revision | owner takeoverごとにgenerationを単調増加。stale generationは新規dispatchと状態更新を拒否する。 |
| Watermark | unique `(journal_id, tenant_id, subject_ref, signed_revision)`、event hash、previous watermark hash、key ID、issuedAt、signature | 独立署名authorityが確認した連続event prefixを固定する。 |

全commandはexpected journal revision、expected hold revisionを含み、action commandはaction ID、lease generation、policy revision/hash、case revision/hash、target version/hashも要求する。欠落/未知version、不一致、UNKNOWN/timeoutは拒否またはQUARANTINEDへ倒す。current stateはhead/action projectionを読むが、起動・recovery時にevent chain/watermarkとの連続性を検証し、不整合を自動修復扱いしない。

source-system identityも合成Bare keyから推定しない。W2のsubject referenceは独立namespaceであり、F1 registryのsource-system immutable namespace/親target bindingと混用しない。

## 3. Transaction、CASとlock order

全状態変更はjournal instance上の単一短transactionで、MySQLのprimary connectionから処理する。read replica/cacheはhold/dispatch判定に使用しない。InnoDB durable commit設定を起動時に検証し、ACKの結果が不明なconnection lossはsuccess/failureどちらにも推定しない。

全更新portでlock順序を固定する:

1. partition headを作成時unique insertし、`SELECT ... FOR UPDATE`でlockする。transactionの先頭で `(tenant_id, subject_ref)` が一致することを確認する。
2. command keyを読み、request hashが一致する既存commandなら既存ackを返す。異なるpayloadは409相当で拒否する。
3. action/leaseを操作する場合はaction row、次にlease rowをlockする。複数subjectをまたぐtransactionは禁止し、batchはsubjectごとの小transactionに分割する。
4. expected `journal_revision` / `hold_revision` / `lease_generation`とpolicy/case/target bindingsを検証し、連番event append、head更新、command結果を同時commitする。unique制約と`UPDATE ... WHERE revision=:expected`を二重防御とする。
5. commit ACK後にのみ`JournalAck`を返す。commit成否不明時は同command IDをJournalへ照会し、同一payload再送で収束する。Journal ACK前にapplication projectionや外部I/Oを成功扱いしない。

すべてのadapterがこの順を守り、deadlock victimは副作用のない同command retryへ戻す。lock timeout、primary不在、durability設定違反、未知commit結果はfail-closedであり、local DB stateで代替判定しない。

## 4. Typed portと遷移契約

Controllerは追加しない。将来実装は`src/main/java/com/ses/privacy/journal/PrivacyJournalPort.java`をtyped boundaryとし、`JdbcPrivacyJournalAdapter`、`JournalProjectionService`、`SignedJournalWatermarkVerifier`、`HistoricalActionReceiptVerifier`を分離する。外部RPC/production接続方式はこのPlanでは選ばない。

| command | 必須比較値 | atomic Journal event / ACK |
|---|---|---|
| `appendHoldStart` | command ID/hash、expected journal/hold revision、tenant/subject、requester authority | HOLD_STARTED。hold revisionを増分し、以降の新規authorizationを拒否。既存actionは未実行/取消完了と偽らずin-flightを列挙。 |
| `appendHoldRelease` | 上記に加えてrelease authority、SoD proof、active hold ID/revision | HOLD_RELEASED。明示的解除eventだけでholdを解除する。古いapprovalやprojectionは解除根拠にならない。 |
| `acquireActionLease` | expected revision、action ID、holder、lease expiry | generationを単調増分してlease取得。takeoverでは旧generationを即時stale化する。 |
| `authorizeDispatch` | new dispatch時点のpolicy/case署名・anchor期限・revocation、tenant/subject/action/provider、target version/hash、hold revision、current lease generation、expected journal revision | INTENT_RECORDEDとDISPATCH_AUTHORIZEDを**同一tx**でappendし、action/idempotency keyを固定。commit ACK/authorization proofがない外部呼出は禁止。 |
| `lookupCommand` / `lookupAction` | tenant/subject、command/action ID | read-only照会。UNKNOWN結果の回復に使い、新しいdispatch権限を発行しない。 |
| `recordActionObservation` | 既存intent/action binding、provider receipt/result hash、attempt ID、観測時刻 | receipt/結果の事実をappend-only記録する。承認/leaseが今期限切れでも受け付け、dispatchや新actionの認可には使わない。 |
| `appendWatermark` | signer key ID、連続prefix revision/hash、previous watermark hash | event tailを再読・検証した独立attestorのみが署名済みwatermarkを追加。headとの一致をCASし、分岐/巻戻り署名を拒否。 |

新しい外向きHTTP attempt（同じprovider idempotency keyを使うretryを含む）は新しいdispatchとして扱う。直前にpolicy/case proof expiryを各envelopeとtrusted anchorの最小期限まで確認し、revocation `REVOKED`、`UNKNOWN`、照会不能、hold、target/policy/case revision drift、stale leaseは送信前に拒否する。期限後のstatus照会などread-only recoveryはauthorizationと分離する。Approval expiry後のaction result receiptは、過去にACK済みの同じintent/actionの観測事実であれば記録できる。

新dispatch直前のtarget再読と外部provider呼出の間にはcross-system atomic transactionは存在しない。providerがtarget version/ETag等のconditional preconditionを受け付ける場合は同じversion/hashをrequestへ渡して不一致を拒否させる。provider側preconditionのない不可逆target mutationはraceを閉じられないためProduction adapter allow-listへ登録しない。synthetic harnessではtarget version変更をbarrierで注入し、送信直前の拒否を確認する。

`recordActionObservation`は現在有効なapproval/fenceで再認可しない。receipt verifierは以前のintent、action ID/provider idempotency key、target hash、provider issuer/proof、attemptとの一致を検証する。外部receiptが無効/不完全、またはactionとの結合を確定できない場合もそのresult hashと拒否理由を監査eventに記録し、actionはUNKNOWN/QUARANTINEDのままにする。raw provider bodyは保持しない。expiry/revocation/lease generationが古いことを理由に本当に起きた外部結果を破棄してはならず、事実記録から新しい副作用を起こしてはならない。

`VerifiedDispatchFence`相当のproofはcaller booleanから作らず、journalのcommit済みauthorization eventと一致するcommand/action/revision/hashをtrusted adapterが読み直してmintする。proofはtenant/subject/case/action/target/policy/hold revision、lease generation、有効期限へ束縛し、一度きりの送信attemptへ転用できない。dispatch前にrevocation statusが`NOT_REVOKED`であることを再確認し、確認時刻とstatus source revisionをauthorization eventへ固定する。status sourceがUNKNOWN、期限切れ、照会不能ならdeny。revocation発行とjournal authorizationの間に原子的な共通revisionを提供できない実adapterはraceを排除できないため、Productionで有効化しない。

SF-W1現行`VerifiedActionReceipt.matches(..., now)`はreceipt自体のexpiryを現在時刻で要求するため、既認可actionの期限後の事実記録にはそのまま再利用しない。W2/W3では現在の送信可否用`VerifiedDispatchFence`と、journal済み過去authorization+provider署名/attemptを検証する`VerifiedHistoricalActionReceipt`相当を分離する。後者はjournalへ既発生結果を`CONFIRMED`等の事実statusまたは検証不能な`UNKNOWN`としてappendできるが、authorization proofのmint、POST/retry、別target/caseの処分を一切許さない。これはSF-W1 foundationの改変済みPASS claimではなく、次waveの明示的な拡張と独立Review条件である。

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

各partitionでwatermarkは連続した最大revisionを証明し、`journalId || tenant || subjectRef || revision || eventHash || previousWatermarkHash || issuedAt || keyId`のcanonical domain-separated bytesへ署名する。署名鍵はSF-W1 synthetic keyとは別のW2 test-only attestor鍵で生成し、production artifactへ入れない。verification trust anchorもharness専用。

event append後にattestorがprimary journalからprefixを読み、event chain、revision、以前のwatermarkを確認して署名する。署名処理はjournal row lock中の外部network I/Oにしない。watermark保存は`previousWatermarkHash`とsigned revision monotonicityをCASし、同 revision/different hashまたは後退を拒否する。append ACKと署名watermarkは別の耐久事実として記録する。dispatch workerは必要なauthorization ACKを待ち、SF-W3でwatermark/fence validityを再確認する。

restore gateでは、復元snapshotがwatermarkより古ければ署名済みwatermarkまで欠落eventを検証・replayする。snapshotのheadがwatermarkより新しい場合、そのsnapshot値だけを信頼しない。watermark後のJournal tailは再chain検証と新しい署名attestationが済むまでQUARANTINEDとする。署名鍵UNKNOWN、sequence/hash gap、遅延、改竄、eventがwatermarkを越える等のいずれも公開/dispatchを許さない。現行Journalのdurable holdはwatermark作成待ちでもlive authorization gateに即時反映する。

## 7. Synthetic MySQL harness / API制約

- JUnit `@Tag("mysql")`専用classでapplication projection用とjournal用の隔離MySQL 8 Testcontainerを起動する。container ID、random schema/database ID、生成credential、worktree、manifest hashをtest内assertする。system property/env/config fileからDB URLやpathをoverrideできるconstructorを作らない。
- production `prod`または`prod,test` effective profile、container identity不一致、Docker未利用、network/volume unexpected、credentials外部参照は起動fail-fast。synthetic Testcontainer classのskipは禁止し、DockerなしをPASS/skip扱いにしない。
- Spring controller/REST endpointは作らない。typed command port、journal adapter、projection adapter、test-only schema initializer、attestor portだけ。test-only DDLはcontainer bootstrap resource内に限り、Flyway versionは持たない。
- 一時DBにはgenerated opaque subject/action/policy/case、架空target hashだけ投入する。作成前後にcontainer identity/fixture hashを取り、終了後はharnessが所有するcontainer/databaseだけを破棄する。他のcontainer/service/processは探索停止/再利用しない。
- test networkはTestcontainers内のjournal/projection/provider stubへ限定し、外部provider invocationは0。provider stub call counterはtest adapter内だけで観測する。
- 実装時の予定配置は`src/test/java/com/ses/privacy/journal/TemporaryJournalMySqlHarness.java`、`PrivacyJournalLinearizationMySqlTest`、`PrivacyJournalRecoveryMySqlTest`と、Testcontainersだけが読む`src/test/resources/privacy-journal/schema-test.sql`。schema SQLはtest initializer専用でmigration/resource discoveryへ加えない。

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
| P7 expired action observation | 期限内にACK済みintent/authorizationとprovider attemptがあり、期限後にvalid/invalid receiptを報告 | valid receiptは過去authorization/attemptへのhistorical proofで期限後もsame actionの事実statusへ追加。invalid receiptは拒否証跡とUNKNOWNを保持。どちらも現行dispatch proofをmintせず、新authorization/actionを生成しない。 |
| P8 revision drift | policy/case hash/revision、target version/hash、hold revisionを一つずつ変更 | 新しいdispatch authorizationを拒否。old signed snapshotを使ったproof/fence mintなし、dispatch call=0。 |
| P9 commit response lost | append transaction commit後にclient応答を切断、同じcommandをquery/retry | lookupで既存revision/hashを返し、同eventを二重appendしない。未commitならACK/dispatchなし。 |
| P10 projection failure | Journal hold ACK後にprojection DB停止、retry/replay二重実行 | Journal holdは有効、認可入口は拒否。projectionはrevision CASで回復し、二重eventなし。 |
| P11 signed old snapshot | snapshot Rより新しいHOLD/INTENT/receiptと連続signed watermarkを用意して二度restore | signature/hash chain検証後に水位までreplay、二度目no-op、hold保持。再公開前にhead一致を証明。 |
| P12 gap/tamper/unwatermarked tail | watermark破損/未知key、sequence gap、hash改竄、snapshotがwatermarkより新しい/後続tail未署名 | restore publication/dispatch拒否、QUARANTINED継続。修正値/水位を実装が推定しない。 |
| P13 journal unavailable/unknown ACK | DB停止、timeout、deadlock、durability config不適合を注入 | hold/authorization ACKなし。projectionだけで認可せず、外部呼出=0。timeoutはcommand lookup後にexact retry。 |

## 9. wave分割とReview gate

| subwave | 追加予定 | exit condition |
|---|---|---|
| W2a journal schema/adapter | typed port、logical test schema、partition head/event/command/action/lease CAS、hash chain | lock order、durability、unique constraints、P1–P5/P9/P13を専用MySQL integrationでzero skip検証 |
| W2b projection/attestation | revision CAS projector、test-only signed watermark attestor/verifier、replay/quarantine API | P10–P12、snapshot repeatability、UNKNOWN hard stop。watermark/restore failureでpublic stateなし |
| W3 separate | dispatch worker/provider adapter、expiry/revocation revalidation、receipt/UNKNOWN recovery | P6–P8および送信前/送信後/crash/retry、late result factual ingestion。W2 plan/code review後に別scope |

各subwaveはfixed HEAD、schema/test hashes、container identity、0 skip、外部provider call=0をAstraへ提出して限定Reviewを受ける。Review PASSはそのsynthetic protocol incrementに限る。正式owner、policy/legal retention、実provider、production journal、migration番、releaseは人間gateでありこの計画では承認しない。

## 10. 参照契約

- `.kiro/specs/privacy-retention-dsar/synthetic-foundation.md` §§SF-03a/SF-04a（線形化、intent先行、復元）
- `C:\work\ses-snf-status-audit\.kiro\roadmap\2026-10-01-snf-production-nf07-foundation-contract.md` §§SF-03a/SF-04a（control履歴、read-only）
- `C:\work\ses-snf-status-audit\.kiro\roadmap\2026-10-01-snf-production-plan-review-r3.md` NF07（取消receipt、expiry/revocation差分、hold/dispatch gate）
- Astra SF-W1 limited CODE PASS: `6ec4ca2837502b9defc33a7a4fe938b5e9d3ecd9`。SF-W2は独立追加Plan Review待ち。
