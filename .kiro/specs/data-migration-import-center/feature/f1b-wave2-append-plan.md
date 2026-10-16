# F1b wave2: BUILDING row chunk append 実装計画

状態: Astra Plan PASS。実装対象はF1b-2のsynthetic/dev-only metadata appendとprefix recoveryに限定する。seal、集計確定、publish、HTTP/UI、業務apply、production enablementは含めない。

## 1. 固定基線とschema監査

- source基線: `b766e3d1012dfcf3951af6300374627cedcb17f0`。wave1のrun-aware start/renew/takeover/abortを前提にする。
- 対象migrationは既存の`V184__create_import_metadata_foundation.sql`のみ。新規migration、V184編集、共有H2 bootstrap、`TestIsolationAudit`変更は行わない。
- `t_import_validation_row`にはtenant/job/run/source row number、source SHA、canonical fingerprint、disposition、finding bytes、row commitment、run FK、`(tenant_id,validation_run_id,source_row_number)` uniqueと順序indexがある。runにはactive slot/validator fingerprint、jobにはversion/lease owner/generation/expiry/active pointerがあるためwave2の保存・fenceに十分である。
- chunk ID、cursor、operation receiptは追加しない。1 transaction内の全insertをatomicにし、既存行が全項目一致するときのみ再送をno-op成功、部分衝突/不一致は409にする。行番号uniqueと保存prefixが冪等性の永続根拠となる。
- DB自体はrow update/delete禁止やBUILDING時限定insertを強制しない。wave2専用mapperはinsert/selectのみ公開し、job/runを先にlockしたserviceからのみ呼ぶ。MySQL競合試験で実際の境界を確認する。

## 2. API境界と処理フロー

- `ImportValidationLifecycleService.RunLease`を唯一のfence capabilityとし、callerがtenant、actor、run ID、generation、hash、disposition、workerを組み立てるAPIは作らない。
- append入口は内部`ValidatedChunk`の構築元をprocessorに限定する。row metadataはparserとvalidatorからのみ作り、 callerがACCEPTEDやhashを宣言できない。HTTP/controller/APIは作らない。
- processorはexact job document/versionを既存DocumentService/FileScope経由で開き、保存済みSHA・file name・encodingを使ってsource I/OをDB write transaction外で行う。raw source row/canonical DTOはメモリ内一時利用だけで破棄する。
- sourceはF2の固定上限64 MiB、100,000 logical data record、64 columns、1 field 65,536 charsを適用する。100,000上限内でもF2の`ImportValidationSession`はsource全external key setを保持するためO(nonempty rows)のheapが残る。これは制限付き既知コストとして記録し、10,000行/heap測定は後続scale gateで行う。row payloadをlog/exception/test artifactへ保存しない。
- parserは`parseMapped`、`CanonicalRowFactory`、`ImportValidationService.validate`の順で使う。canonical conversion exceptionは既存pipeline同様`CANONICAL_CONVERSION_FAILED`のREJECTED metadataへ変換し、raw exception/valueを保存しない。amountはvalidation resultへは来るがwave2のrow tableには保存しない。
- resume時は`source_row_number`の昇順page（小さな固定page）で保存prefixを読み、parserをsource先頭から進める。各非empty source rowについてvalidatorを再実行しduplicate stateを再構築しつつ、該当prefix rowの全metadataを1行ずつ照合する。blank physical row由来のnumber gapは許容する。保存済みprefixを全て照合し終えるまで新suffixをwriteしない。途中EOF、prefix不足/過剰、差分、順序違いは409で停止する。parserがEOFに達した後でのみ未保存tailをflushする。最大row numberや空行数からEOFを推測しない。
- metadata cursor/read pageはsource再parse/validationと並行してrow-number順に比較できる内部readerとする。source read/parse時にはjob/source/document/run row lockを保持しない。prefixが長い場合も全source rowやrow DTOをList化しない。
- processorはsource全体を一度streamし、suffixを最大250 nonempty rowのbatchへ分割する。prefix確認済み以降だけchunkをflushする。最終tailが250未満でもEOF後にflushする。append成功はBUILDING row metadataのcommitだけを意味し、EOF summary/counter、SEALED、COMPLETED、VALIDATEDを設定しない。
- `SesCsvParser.ParseSummary.sourceSha256`と`ImportSourceContent.sha256Hex()`をjob/runの固定source SHAと照合する。全source SHA不一致、read/decoder/parser失敗、途中closeは残るprefixを書き進めず409相当として扱う。既に先行chunkがcommit済みの場合はその事実を保持してBUILDINGのまま停止し、続行は次回prefix再検証後のみ可能。ここではseal/count/publishを行わない。

## 3. Fingerprintとrow metadata形式

- run fingerprintは少なくともparser contract、canonical/schema contract、validator contract、resolver mode、finding serialization contractをdomain-separated length-prefixでcommitする。固定fail-closed resolverのmodeを含め、実resolverへ移行した際にはfingerprintを変える。旧policyのBUILDING runはwave1のfingerprint gateが拒否し、暗黙にresumeしない。
- wave2のresolverは固定fail-closed synthetic modeとし、未実装lookupはclosed findingとしてREJECTEDへ落とす。実DB resolverや参照entityを成功扱いするmockを足さない。PROJECT/PROPOSAL/CONTRACTの成功assertionは明示synthetic fixtureに限定し、5 entity全体の業務validation完成と表現しない。
- finding codeは明示的なallowlistに含まれるASCII tokenのみを許す。sortし重複を除き、domain tag/version、各codeのUTF-8 byte length prefixとbyte列でcanonicalizeする。自由形式message、raw value、PII、external keyを含めない。未知codeまたはcanonical bytes >4096はchunk拒否。
- row commitmentは固定domain tag/version、source row number、source row SHA、canonical fingerprint、disposition enum、finding canonical bytesを順序固定・length-prefixでSHA-256化する。parser row hashとは別目的とする。ACCEPTEDはfindingが空の時のみ、ひとつでもfindingがあればREJECTED。
- incoming chunkはsource orderを保ち、source row numberは正数かつstrictly increasing。source gapsは許す。row countは1..250。空chunkは永続writeをせず、caller側に進捗を返さない。

## 4. Append transactionと失敗意味

- source parsing中にwrite lockを保持しない。各batchのcommit transactionは`READ_COMMITTED / REQUIRES_NEW`を使う。
- lock順はwave1/F1b planと同じ`source system → document → document version → IMPORT_JOB link ID順 → job → mapping → run → row number順`。必要ないtableだけをskipする。
- exact prefix readが終わり、chunkを構築した後、write transaction内でresource/snapshotを再lockし、job PRIMARY rowを最後までfenceとして保持する。row rangeをascendingでlock/readする。最後に待つACL row lockの後で現actor/tenant-bound exact permissionを解決し、最後にDB primaryの`CURRENT_TIMESTAMP(6)`を読みlease expiryを判断する。
- ACL/snapshot checksはwave1と同じくsource ACTIVE/version、document exact tenant/legal entity/version/CLEAN/hash/hold/retention/FileScope、正確なIMPORT_JOB link、mapping canonical SHA、job state=`VALIDATING`/version/active pointer、run state=`BUILDING`/active slot/current fingerprint、run/job input equality、lease owner/generation/expiryを含む。どれかの変化は403/409としzero writeにする。
- 同じchunk再送時も同じauth gate、resource revalidation、lease/fence検証を毎回実施する。期限切れownerは既存一致rowを読めても成功扱いしない。
- 既存同run/row_numberがあればsource hash/fingerprint/disposition/finding bytes/row commitmentの全一致のみ再送一致として認める。一項目差異、chunk内重複/逆順、row key uniqueness競合は409。all-same existing retryはno-op。partial existing rowsは全rowが再検証済みprefixとの一致であり、新rowがその連続suffixである場合だけsuffix appendを許可する。それ以外の混在/穴は拒否する。
- 新規rowは最大250件を1 transactionでbatch insert。どのrowでもinsert count/constraint/failureが不正なら全batch rollback。外部へSQL deadlock、lock wait timeout、connection failureを403/409へmapしない。

## 5. 試験受入

### H2 / service tests

1. 0/1/249/250/251境界、249+EOF tail、blank row gap、250ごとのchunk順序。
2. 同一chunk再送は全一致でno-op、各field（number/hash/fingerprint/disposition/finding/commit）を独立に変えたretryは409/無変更。未知finding code、oversize finding bytes、row number非昇順/重複、251行は拒否。
3. prefixをpage境界(1,249,250,251)で照合、duplicate-key stateをrow単位で復元、prefix確定前にはsuffix insertがないこと、prefix不足/余分/変更/順序違い/EOF不足を拒否。blank line由来のrow number gapだけは成功。
4. batch中insert failureを注入し全行rollback、先に成功済みchunkは保持しBUILDINGのままで次回replay可能。非BUILDING、stale generation、expired lease、cross-tenant、snapshot/mapping/policy driftはzero write。
5. conversion failureは固定codeだけを保存、message/raw/canonical PII/external key/amountはSQL row/DTO/logに出ないこと。
6. source SHA mismatch、decoder/parser/read exceptionはappend停止し、既commit prefixを編集/削除しない。empty sourceと0 row batchはcompletion扱いしない。

### Real MySQL / Testcontainers

- 新test classの場合`@Tag("mysql")`とshard inventoryへ1回だけ登録する。既存のreal MySQL testへ追加する場合はinventory重複を作らない。
- 独立service/requester/blocker connection IDと`DATABASE()`、`@@transaction_isolation=READ-COMMITTED`、InnoDB lock wait edge（requester/blocker、index/key、type/mode/status）を実測する。
- append writer先行ではtakeover/abortがjob fenceで待機し、chunk commit後に正しい期限/generation判断をする。takeover先行では旧grant appendが待機後409かつrow count/hash不変。
- ACL mutation/permission revocation/source/document/link mutationの各先行順はappendを拒否しzero write、append先行順はmutationが待機してcommit後にのみ進む。DENYとSQL deadlock/timeout分類を分ける。
- 同chunk concurrent retryは一意なrow件数と全metadata一致を確認し、競合で重複しない。conflicting metadataは一方を拒否。中間batch failure/unique conflictはそのchunk全row rollback。
- queryで保存metadataが完全一致し、PII/raw/amount列や別の業務表にwriteがないことを確認する。SQL deadlock/lock timeoutはinfra failureとして保持する。

## 6. 明示的対象外と未完了

- このwaveはrun `BUILDING`のrow append/recoveryのみ。EOF sealing、run counters/finding summary、amount summary、row-set/summary hash、publish、candidate read/registry、HTTP/UI、apply/upsert、rollback/compensationは後続wave。
- 5 entityの業務validation完全性、実source適合、resolverの実DB semantics、NF-07/DG-07、production enablementを主張しない。現段階はsynthetic/dev-onlyであり実PII/credential/providerを使わない。
- `source_row_count`/`physical_row_count`/`empty_row_count`や`accepted/rejected` run counterはEOFとsealで確定するためwave2では設定しない。
