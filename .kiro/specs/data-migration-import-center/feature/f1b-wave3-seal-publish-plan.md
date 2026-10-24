# F1b wave3: EOF seal・recount・publish計画

状態: W3A counter契約を含むAstra PLAN再レビュー依頼用。wave2のappend/recovery、現行W3A capability code、V184の実schemaを基にしたdocs-only計画であり、追加counter実装承認、実source適合、NF-06完了、production enablementを意味しない。

## 1. 対象と現状

- 計画対象は合成sourceに対する`BUILDING → SEALED → COMPLETED`と、保存済みvalidation resultを`VALIDATED`として公開するlifecycleに限る。業務apply、target binding、HTTP/API/UI、実legacy source、production data、外部provider/keyは扱わない。
- source基線は`8f9aaad02b3b2ff6460c0a973f0e05ada11563f5`。この更新は本計画だけのdocs-only差分とし、source wait oracle、control/NF-08/NF-01は変更しない。
- 参照する正本は`f1b-validation-lifecycle-plan.md`、wave2の範囲は`f1b-wave2-append-plan.md`、schema列・契約は`f1a-schema-draft.md`と`V184__create_import_metadata_foundation.sql`である。wave3は既存F1b-3のseal/recount/publishを実装可能な単位へ分ける。
- 現行`ImportValidationAppendService.appendRemaining`は互換wrapperとして`AppendCompletion.lease()`を返す。内部`appendRemainingWithSealCapability`はsourceの全検証scan、row metadataのappend、EOF/source SHAの再確認とfinal lease renewを行い、private constructorの`SealCapability`を持つ内部`AppendCompletion`を返す。このcapabilityのcounterは現状`ValidationCounterAccumulator.Snapshot`であり、finding-code row count、amount field別known count、excluded countの全契約はまだ含まれない。
- `ImportValidationLifecycleService.appendChunkInTransaction`はjob rowをfenceとしてresourceをlockし、現在のACLとDB時刻を確認してから`BUILDING`へ最大250 rowをappendする。seal/publish serviceはまだない。
- `ImportIdentityCandidateService`の`loadSnapshot`、`requireJobSnapshot`、`requirePublishedRun`はjob `VALIDATED`、active pointerなし、job current pointer一致、run `COMPLETED`、`published_slot=1`を要求する。BUILDING/SEALEDは読取候補にならない。

## 2. V184 schema充足性

seal/publishの状態と確定値はV184の既存列に保存できるため、通常のservice transactionには新migrationを加えない。

- `t_import_validation_run.state`は`BUILDING`、`SEALED`、`COMPLETED`、`ABORTED`を許可する。`active_slot`、`published_slot`にはjob内unique indexがあり、`chk_import_run_slots`が各状態とslotの組合せを制限する。
- runには`sealed_at`、`completed_at`、`source_row_count`、`physical_row_count`、`empty_row_count`、`accepted_row_count`、`rejected_row_count`、finding summary bytes/hash、5金額項目のaccepted/rejected known count・sumとexcluded count、`amount_excluded_reason_counts`/hash、`row_set_sha256`、`summary_sha256`がある。
- `chk_import_run_completed_summary`はCOMPLETED時に全counter/hashの非NULL、`physical = source + empty`、`source = accepted + rejected`を要求する。`chk_import_run_amount_nulls`はknown countが0ならsum NULL、正数ならsum非NULLを要求する。金額sumの0円と未観測を混同しない。
- `t_import_validation_row`にはsource row number/hash、canonical fingerprint、disposition、finding codes、row commitmentがあり、`(tenant_id, validation_run_id, source_row_number)` uniqueとrun FKがある。source順の走査と重複rowの拒否に足りる。
- jobの`active_validation_run_id`と`current_validation_run_id`はrunへの複合FKを持ち、run mapping/source snapshotも複合FKで固定される。jobにはstate、version、lease owner/generation/expiryがある。transaction内CASでrun slot・job pointer・leaseを一緒に更新できる。
- 既存schemaはvalidation rowのUPDATE/DELETE禁止、SEALED以降の追記禁止、job pointerとrun stateの相互整合を単独では強制しない。wave2と同じservice-only write契約により`appendChunk`をBUILDING専用にし、seal/publish/abortがjob PRIMARY lockを取る。もしDB直接更新に対するimmutable row enforcementまで要件にするなら、trigger等のschema案を独立したschema reviewへ戻し、ここでmigration番号を決めない。
- EOF capabilityはDocumentServiceが開いた同一DocumentVersionのbyte streamから作る。現在の`DocumentServiceImpl`は登録時に新storage keyを作りhashを保存し、Import Centerはversion ID/no/hashを固定して毎回実streamのsource SHAを確認する。実storage/providerが既存versionの同じkeyを後から上書き可能ならこのimmutability前提は成立しないため、publish gate前にstorageのversion immutabilityを確認し、未保証ならstorage/versioning案を別レビューへ戻す。

## 3. Wave 3A: append完了からseal capabilityを作る

### Objective

exact sourceをEOFまで処理し、保存row全体との一致を確認したappendだけが、同じ`ValidatedRow`全件走査から得たfinding/amount counterを含むseal用の非公開capabilityを発行できるようにする。

### 実装境界

- `ImportValidationAppendService.appendRemaining`の完了結果を内部`AppendCompletion`へ拡張する。これは最新`RunLease`と、同service内のprivate constructorでのみ生成できる`SealCapability`を保持する。controller、test、callerが`RunLease`からcapabilityを組み立てるAPIは設けない。
- capabilityは少なくともtenant/job/run、source/document/mapping snapshot、validator fingerprint、current lease generation/worker、source SHA、ordered row-set SHA、parser counters、canonical summary dataを束縛する。byte arraysは生成時と取得時にcopyし、public mutable fieldを持たせない。
- capabilityを生成するのは、最終write scanが`SesCsvParser.ParseSummary`を返し、元byte全体の`sourceSha256`がjob/run/document snapshotと一致し、全chunk insert/retryが成功し、prefix cursorを`requireExhausted()`まで消費した後だけとする。parser/read/decoder例外、SHA差分、chunk failure、prefix不一致ではcapabilityを返さず、runはBUILDINGのままにする。すでにcommitしたrow prefixは保持して再開に使う。
- row-set commitmentはsource orderで計算する。各entryは`source_row_number`と`row_commit_sha256`をdomain tag/version付きlength-prefix形式で連結する。`row_commit_sha256`は既存のrow number/hash、canonical fingerprint、disposition、finding bytesをcommitする。hash format/versionを定数として固定し、既存wave2のrow commitmentとV184の`row_set_sha256`定義に合わせる。
- parser callbackの各`RowMetadata`は既存prefixと`PrefixCursor.compare`で6項目すべて（row number、source hash、canonical fingerprint、disposition、finding bytes、row commitment）を比較する。新suffixは`appendChunkInTransaction`の全insert countまたはsame-metadata retryが確認された後だけproofに含める。parser EOFの時点でprefix cursorの既存rowが全て消費済みであり、追加済みsuffixもすべてcommit済みであることを成立条件にする。
- 保存row数はparserの`sourceRows`と同じであること、`sourceRows = accepted + rejected`、`physicalRows = sourceRows + emptySkipped`をcapability生成時に確認する。row numberはparserのphysical record numberをそのまま比較し、blank rowによるgapを連番へ詰めない。単なる`MAX(source_row_number)`やchunk size 0をEOF証拠にしない。
- `header`を含まないbyte-empty fileはparserどおり拒否し、capabilityを発行しない。正しいheaderだけのsourceはsourceRows=0でEOF/hashを確認する。header後の空recordはphysicalRows/emptySkippedへ計上し、sourceRows=0のvalid empty resultとして扱える。空rowがある場合も0-row batchだけではsealせず、EOF capabilityが必須である。

### W3A counter契約と変更対象

- 主な変更対象は`foundation/ValidationCounterAccumulator.java`、`metadata/ImportValidationAppendService.java`、対応するunit testsである。`ImportValidationPipeline.validateInternal`は現在、各実`ValidatedRow`について`counters.record(result)`を一度呼んでから同じrowをconsumerへ渡している。この一箇所の集計をprefix再検証とsuffix appendの両方に使い、別のsuffix-only集計を作らない。parser/pipelineの呼出順は変えずに済む場合、`ImportValidationPipeline.java`は編集しない。
- `appendRemainingWithSealCapability`のread-only scanとwrite scanは、どちらもsource全体を先頭から再parse/revalidateする。proofに採用するcounterは全sourceを再走査したwrite scanの`RunSummary.counters`であり、既存prefixの各`ValidatedRow`と未保存suffixの各`ValidatedRow`をそれぞれ一度だけ加算する。DBのprefix件数やinsert件数をamount/finding counterとして足し直さない。read scanとwrite scanのcounter snapshotはvalue-wise一致を確認し、一致しなければcapabilityを発行しない。
- finding summaryは各`ValidatedRow.findings()`内のcodeをrow単位でdistinct化した後にcountする。同じrowに同じcodeと異なるmessageを持つfindingが複数あってもそのcodeは一件、別source rowなら同codeを各row一件ずつ加算する。`finding_summary_bytes`はclosed codeごとの件数をcode順にdomain/version/count付きcanonical bytesへする。message、row number、PII、raw valueはsummaryへ入れない。row単位のpersisted `finding_codes`と同じくunknown codeを許さない。件数0の空summaryもversion付きcanonical nonempty bytesで表し、V184 `VARBINARY(8192)`を超える場合はproofを拒否する。
- 金額field集合は既存`ImportValidationService.amountFields`に固定された5項目のみ: `expectedUnitPrice`, `unitPriceMin`, `unitPriceMax`, `sellingPrice`, `costPrice`。entityに適用されないfieldもproof snapshotではゼロcounterとして明示し、source schema外のfieldを受け入れない。各fieldについてrowのdisposition別(`ACCEPTED`/`REJECTED`)にknown countと円sum、field単位のexcluded count、`field:reasonCode`単位のexcluded reason countを持つ。既知額とexcludedは`ValidatedRow.knownAmounts`/`excludedAmountReasons`から、同じ各rowに対してどちらか一方だけ計上する。
- countはrow-field単位で、複数amount fieldを持つrowはそれぞれ別に数える。`amount_excluded_count`はそのfieldでamountが未知/invalidになったrow-field数であり、reason countのfield別合計と一致する。overall excluded countは5 fieldの合計。reason codeは`ImportAmountParser`の閉じたreason集合に限定し、free textをcanonical summaryへ入れない。
- count=0なら当該dispositionのsumはNULL、known count>0ならsumを必ず保持する。値`0`円はknown countを1以上、sumを`BigDecimal.ZERO`として表し、未観測/全件excludedのNULLと混同しない。accepted/rejectedにamountがない場合もcount 0を明示する。既存overall `validatedAcceptedAmount`等は互換のaggregate projectionとして残すが、複数schema fieldを足した値を売上・粗利など業務金額として解釈しない。
- counter更新はcount overflowを`Math.addExact`相当で拒否し、sumはscale=0かつ符号・precisionがV184 `DECIMAL(38,0)`に収まることをsnapshot/proof生成前に検査する。sumはJPY円単位のJava `BigDecimal`で加算し、precision 39以上、非整数scale、負値、count overflow、closed schema外field/reason、known/excludedの重複または欠落はfail-closedでcapabilityなしとする。`amount_excluded_reason_counts`もfield/reason順のversion付きcanonical nonempty bytesとし、V184 `VARBINARY(8192)`上限を超えれば拒否する。parser上限内でも将来のschema/入力制約変更に対する明示guardを残す。
- W3Aの金額は、source byte streamから生成された実`ValidatedRow`に含まれるtyped fieldのvalidation結果だけから集計する。`t_import_validation_row`にはraw amountがなく、DB保存rowからraw額を再構成/再計算するとは主張しない。DB prefixはrow metadata 6項目との照合とrow-set commitmentに使い、金額/finding counterは再parseしたsourceの同じrow pipelineから得る。
- `ValidationCounterAccumulator.Snapshot`は5-field全件とnested reason mapsを含むdeep immutable valueとし、constructor入力とaccessor境界でnested mapまでcopyし、BigDecimal/String/Long以外のmutable valueを保持しない。counter snapshotを保持する`SealCapability`は既存どおりprivate constructorとし、append serviceがEOF、source SHA、prefix exhaustion、全suffix commit、lease rebind後だけ生成する。callerは`RunLease`からcounter/capabilityを作れず、公開互換`appendRemaining(RunLease)`は従来どおりleaseだけを返す。新しいcounter APIをcontroller/public serviceへ露出しない。
- 予定変更ファイル: `src/main/java/com/ses/importcenter/foundation/ValidationCounterAccumulator.java`でrow finding dedup/per-field amount accumulatorsとimmutable snapshot、`src/main/java/com/ses/importcenter/metadata/ImportValidationAppendService.java`で両scanのcounter一致・proof bindingと5-field/schema checks、必要なら`ImportValidationPipeline.java`でrow counting順の保持。testは新規`src/test/java/com/ses/importcenter/foundation/ValidationCounterAccumulatorTest.java`、既存`ImportValidationServiceTest.java`と`SesCsvParserTest.java`、必要最小限の既存metadata testを使う。`ImportValidationLifecycleService`、migration、H2 bootstrap、UI/APIはW3Aで変更しない。

### Test requirements

- capabilityはEOF + source SHA一致 + 全prefix/suffix一致後のみ得られる。途中EOF/read/decoder failure、source SHA mismatch、欠落/追加/変更row、余分な既存row、prefix metadata差分では得られない。
- 0/1/249/250/251 nonempty rows、複数chunk、header-only、空行のみ、blank physical row gapを扱い、ordered row-set SHAを同じ入力で再計算して一致させる。
- prefix再検証scanとwrite scanでcounterが一致し、全sourceのprefix+suffix各`ValidatedRow`を一度だけ集計する。prefixだけ/suffixだけを足す誤実装、chunk retryによる二重計上、空recordをsource rowとして加算する誤りを検出する。
- 同一row内duplicate finding codeは1件、別rowの同一codeは2件としてfinding summaryが再現する。message違いはcounter/hashを変えず、unknown codeはrow/proof生成前に拒否する。
- 5 amount fieldすべてでaccepted/rejected known countとsum、excluded per field、field+reason countを検証する。field非適用のzero、known 0円(`count=1,sum=0`)、known count 0(`sum=NULL`)、全件excluded、同じfieldの複数reasonを区別する。
- count overflowとDECIMAL(38,0)超過/不正scaleをproof前に拒否し、V184のamount NULL/count制約を満たす値だけをcapabilityへ含める。nested mapの変更不能性、入力mapの後続変更分離、private capability constructor/既存`appendRemaining`の戻り値互換も確認する。
- capabilityのtenant/job/run/generation/input fingerprint不一致、別runへの付替え、同じappend responseのretryを拒否または同一結果へ収束させる。callerが空chunkやsource SHAを渡してsealを呼ぶpublic pathはない。

## 4. Wave 3B: BUILDINGからSEALEDへのCAS

### Transaction契約

- source再読込とparser処理はDB transaction外で完了してから、capability付きseal transactionを開始する。長いfile IO、validation、recountをresource lock中に行わない。
- transactionは既定順`source system → document → document version → IMPORT_JOB link ID順 → job PRIMARY → mapping → run → validation row（source_row_number昇順）`でlockする。job PRIMARY lockがappend、takeover、renew、abort、seal間のfenceとなり、commitまで保持する。
- current actor/tenant-bound exact allow（deny precedence）、active user、legal entity、source `ACTIVE`/version、DocumentService/FileScope、CLEAN/hash、hold/retention、正確なIMPORT_JOB link、mapping bytes/SHA、job/run input snapshotをlock取得後に再検証する。last waited row/resource lockとACL gateが完了した後、MySQL primaryの`CURRENT_TIMESTAMP(6)`を読み、lease expiryを判定する。
- final CASはjob `VALIDATING`、`active_validation_run_id=runId`、`current_validation_run_id IS NULL`、lease owner/generation/version/expiryの完全一致、`lease_expires_at > dbNow`を要求する。runは`BUILDING`、`active_slot=1`、`published_slot IS NULL`、`last_resumed_lease_generation=lease.generation`、input snapshot/fingerprint一致を要求する。
- capabilityのrow count/hashをjob lock下でordered DB row-set commitmentと照合し、scan中に別chunkがcommitした場合やDB rowが変更された場合はsealを拒否する。DB row traversalはpageでstreamし、row DTO一覧を全件memoryへ積まない。
- 一つのtransactionでrunの確定counter、finding/amount summary bytes/hash、`row_set_sha256`、`summary_sha256`、`sealed_at=CURRENT_TIMESTAMP(6)`、`state='SEALED'`をCASする。jobは`VALIDATING`のままactive pointer/leaseを保持し、current pointerはNULLのまま。transaction rollbackならrunはBUILDINGであり、summaryの部分保存はない。
- 既存`ImportIdentityCandidateService`はCOMPLETED/pointer/published slotのみ読むためSEALEDは未公開のままである。`ImportValidationLifecycleService.appendChunkInTransaction`にSEALEDを拒否するstate guardを置き、SEALED rowsはservice経由で更新/削除しない。

### Test requirements

- same capability retry後のSEALEDは同一summary/hashを返し、二重更新やtimestamp/versionの再変更をしない。別capabilityまたは別input/generationは409。
- seal commit直前のtakeover/renew/abortとの両順序をMySQLで検証する。job lockを先取した操作だけがCASを進める。expiryをlock/gate待機中に跨いだ場合はfresh DB timeで拒否する。
- seal中のsource/document/link/mapping/ACL変更はwait edgeを計測し、lock勝者と結果を確認する。failed transactionではSEALED/COMPLETED、current pointer、published slotを作らない。
- SEALED後のappendは拒否され、stored row count/row-set hash/summaryの値は変わらない。ABORTED/BUILDING/SEALEDはいずれもcandidate readerから見えない。

## 5. Wave 3C: sealed recountとpublish capability

### Objective

`SEALED`の永続summaryをexact sourceとrow metadataから再現でき、restart/takeover後もpublish前に同じ証拠を確認できるようにする。

### Recount契約

- `recountSealed(RunLease)`はcurrent active workerのleaseに束縛し、SEALED run snapshotを読んでexact DocumentService sourceを再openする。source全byte hashとEOFを再確認し、F2 parser/canonical validatorで全nonempty rowを再計算する。DB lockはsource read/parse中に保持しない。
- exact sourceを再parseして得た各`ValidatedRow`をSEALEDのordered DB row metadataと一対一比較し、prefix不足/余分/不一致、source hash差分、validator fingerprint差分があればpublish capabilityを発行しない。row/physical/empty counts、accepted/rejected、finding counts、amount fields/reason countsはsource validation streamから再計算する。DB rowにはraw amountがないため、amountをDB rowから復元したとは扱わない。row-set SHAおよびsummary SHAも再構成し、SEALED列とbyte/value単位で一致させる。
- finding summaryはclosed finding-codeごとのcountをcode順にdomain/version/length-prefixでcanonicalizeする。row/summaryへfinding message、raw row、PII、external keyを保存しない。
- 金額は各schema fieldについてaccepted/rejected known countと円sum、全sourceに対するexcluded count、`field:closedReason` countを再計算する。count=0のsumはNULL、known rowがあり算定sumが0円なら0を保持する。DECIMAL(38,0)範囲外、unknown reason、schemaにないfieldはpublish拒否。
- `summary_sha256`はdomain/version、固定job/run input hash、source SHA、validator fingerprint、row-set SHA、counter、finding summary hash、amount summary hashes/sumsを順序固定・length-prefixでcommitする。検証結果から再計算し、caller供給値を使わない。
- recount完了時だけ、`ImportValidationAppendService`のprivate constructorで`PublishCapability`を作る。tokenはrun/job/snapshot/current generation/workerとseal済みrow-set/summary hashを束縛する。takeoverはgenerationを増やすため旧workerのtokenを使えず、新workerがrecountをやり直す。

### Test requirements

- SEALED summaryを元row/sourceからrecountした結果が一致し、same run repeatで同じhashを得る。
- source byte差分、EOFなし、mapping/parser/fingerprint drift、ordered row metadata各列の差分、counter/finding/amount byte差分、NULLと0の取り違えはpublish capabilityを発行しない。
- restart後のSEALED takeoverでは同じrunを継続する。old generation capabilityは拒否し、新generation recountの一致だけをpublishへ渡す。

## 6. Wave 3D: SEALEDからCOMPLETEDへのatomic publish

### Transaction契約

- publishはrecount済み`PublishCapability`を受け、source IOをしない短transactionで行う。resource lock、job/run pointer、mapping snapshot、actor/ACL/FileScopeを再確認し、capabilityのsummary/row-set hashとSEALED保存値を照合する。
- lock順と最後の判定順は3Bと同じ。最後のresource/row lockとACL gateの後で`CURRENT_TIMESTAMP(6)`をfreshに取得し、job lease期限を判定する。expiryは処理開始時やcapability生成時の時刻を使わない。
- CAS前提はjob `VALIDATING`、active pointerが対象run、current pointer NULL、current owner/generation/version/expiryがleaseと一致し未期限、run `SEALED`/active slot 1/published slot NULL、`last_resumed_lease_generation`とinput/hash一致、全COMPLETED summary列がNULL/0規約を含めて整合すること。
- 同一transaction内でrunを`COMPLETED`、`active_slot=NULL`、`published_slot=1`、`completed_at=CURRENT_TIMESTAMP(6)`へCASし、jobを`VALIDATED`、`active_validation_run_id=NULL`、`current_validation_run_id=runId`、lease owner/expiry NULL、version+1へCASする。FKとunique slot、`chk_import_run_completed_summary`を満たす順に更新し、commit前のどちらか片方の状態は外部readerに見せない。
- 再validation開始時に旧current pointer/`published_slot`が解除済みという既存F1b-1契約を維持する。新runのpublish commitまでcurrent validation resultはない。publish failure/rollback時も新runのcurrent pointer、published slot、VALIDATED stateを一部だけ残さない。
- publish成功後のread pathは`ImportIdentityCandidateService`の現行条件（job VALIDATED/current pointer一致/active pointerなし、run COMPLETED/`published_slot=1`、source/document/mapping snapshot一致）を維持する。read pathはBUILDING/SEALED/ABORTEDやpointer不一致を公開しない。validation/publishからidentity registry writeを呼ばない。

### Race、ack loss、retry semantics

- append対seal: appendがjob lockを先に取ればappend commit後にseal capability/hash照合が成立した場合だけseal。sealが先ならstate SEALEDとなり後続appendは拒否。
- renew対seal/publish: renewが先にversion/expiryを変えたら古いexact lease CASは拒否する。current ownerは新しいleaseでcapabilityを再束縛できるのは同一run/input/generationのままの場合だけとし、必要に応じEOF verification/recountを再実行する。expiryを跨いだtokenは使わない。
- takeover対stale worker: takeoverがgenerationを増やした後は旧generationのappend/seal/publishを拒否する。active unexpired workerはtakeoverできない。新workerはsame BUILDING prefixまたはSEALED runだけを継続し、新runを暗黙作成しない。
- abort対seal/publish: job row lockを先に得た操作を直列化点とする。abort成功後はABORTED/pointers releasedとなりseal/publishは不可。seal成功後のabortは現generation/未期限の明示操作に限る。COMPLETEDはabort不可。
- commit ACK loss: seal retryは同run・same proof hash・same current generationが既にSEALEDならread-onlyで同じsealed stateを返す。異なるproofは拒否。process restart後はSEALED takeover + recountで新capabilityを作る。publish retryで既にjob pointer/run state/hashが完全一致するCOMPLETED resultがあれば現在権限のread-only確認結果を返し、row、counter、timestamp、versionを再writeしない。pointer/hash不一致や別generationによる未完了publishは409。
- renew/takeover/abort/publishの勝敗をSQL error/timeoutで判定しない。期待するdomain拒否だけを409/403にし、deadlock/lock wait timeout/connection failureはinfra failureとして保持する。

### Test requirements

- publish worker 2件の同時実行ではcurrent pointer/run slotを持つCOMPLETED resultが1件だけになる。二重commit、二重version increment、二つのpublished slotを作らない。
- writer/takeover/abort/renew/publishの各投入順でMySQL実lock wait edge（actual requester/blocker `CONNECTION_ID()`、`DATABASE()`、index/key、record lock mode/status）を確認し、結果state/version/generation/expiry/pointers/counter/hashesを比較する。
- publish直前にexpiryを跨ぐ、permission revoke、source disable、document hold/retention/link change、mapping bytes/hash差分を競合させ、last-lock/last-gate後のfresh DB timeと再認可が拒否することを確認する。
- transaction更新途中のfailure/connection ACK lossを分け、failureでは両側rollback、commit済みACK lossではretryがstored COMPLETED valueを返して再writeしない。
- `ImportIdentityCandidateService`でBUILDING/SEALED/ABORTED/pointer driftが見えず、publish後のCOMPLETED/current resultだけを読めることを確認する。

## 7. 小分けの実装・review gate

1. **W3A capability + EOF/reconciliation** — `ImportValidationAppendService`のappend completionにprivate-ctor tokenを追加。全parser/source/row proofとcounterを作る。H2境界testを先に通し、V184 row schemaは変更しない。
2. **W3B seal transaction** — `ImportValidationLifecycleService`へseal CASを追加。resource/ACL/time lock order、row-set recheck、summary persistence、ACK-loss retryを実装してH2 state testsとMySQL job-row lock testsを行う。
3. **W3C SEALED recount + publish capability** — SEALED source/row proofとsummary再構成を実装。takeover recovery、old generation token、empty/blank/amount/finding testsを行う。
4. **W3D atomic publish + candidate visibility** — run/job current pointer/slot/leaseを同tx CASし、candidate readerの既存published-only条件を回帰確認する。parallel publish、renew/takeover/abort、ACK-lossをMySQLで試験する。
5. **Review freeze** — 各waveの対象SHA、H2/MySQL evidence、zero skip、schema audit、未完了境界をAstra CODE reviewへ渡す。合格はNF-06全体、A1/B1/B2/M、real source、production enablementの完了を意味しない。

## 8. Wave 3総合受入条件

- source EOFとsource SHAが同じexact DocumentVersion bytesから証明され、ordered parser rowsと全persisted row metadataが一対一・全列一致する。
- `source_row_count = accepted_row_count + rejected_row_count`、`physical_row_count = source_row_count + empty_row_count`。0-row/blank-only resultはheader/parser/source EOF proofが成立した場合だけ有効。
- finding/amount summaryはclosed canonical encodingとhashで再現可能。NULL/0、currency=JPY、DECIMAL(38,0)、excluded reasonの件数式を満たす。
- BUILDING→SEALEDのproof/summary/stateと、SEALED→COMPLETED/current pointer/published slot/job VALIDATED/lease releaseはそれぞれ完全atomic。途中状態は読者に見えない。
- current actor/permission/resource snapshot/fence/generation/version/expiryを毎write gateで確認し、DB時刻をlast lock/ACL待機後に取得する。
- takeoverは同一runの新generationだけを許し、stale worker、expiry済みworker、abort済みrunはseal/publish不可。renewとpublish/abortはCASで一つの勝者に直列化する。
- readerはCOMPLETED + current pointer + published slotの全一致だけを見る。candidate registerからのregistry writeは独立操作のまま。
- V184列と制約で保存/atomic CASが可能なことを実装前にMySQL fresh DBで再確認する。上記契約に不足する列やDB-enforced invariantが見つかれば、変更前に別のschema-only提案をroot/Astraへ戻し、migration番号を予約しない。

## 9. 明示的対象外

実source schema適合、DG-06、PII production processing、file upload/UI/API、real resolver、target binding、domain apply/upsert、B1/B2/M、business rollback/compensation、NF-07/DG-07、provider/credential、production deploymentは本計画に含めない。wave3がPASSしてもsynthetic validation lifecycleのseal/publish gateのみを閉じる。
