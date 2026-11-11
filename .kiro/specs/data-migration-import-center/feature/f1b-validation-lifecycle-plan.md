# NF06 F1b validation lifecycle 実装計画

状態: Astra Plan Review待ち。対象は合成入力に対するvalidation runの開始・再開・chunk保存・seal・集計・publish・abortである。実source適合、業務apply、production enablementの承認ではない。

## 1. 固定基線と現状

- 計画基線: `37c4c3d8943f4aa424d513cf4b52078358df01f0`（NF06の直近source SHA）。この計画文書は同SHA上に追加するdocs-only差分とする。
- F2にはstreaming CSV parser、5 entityのcanonical row、pure/read-only validation foundationがある。これは永続validation lifecycleやDB resolver統合を意味しない。
- V184にはsource/mapping/job/validation run/validation row/identity registryのmetadata schemaとjob lease列がある。現在のproduction serviceはsource/mapping/job登録と`UPLOADED → MAPPED` bind、MAPPED専用lease、completed runのcandidate登録までである。
- `ImportIdentityCandidateService`は`COMPLETED`かつjob current pointer/published slotのあるrunとaccepted rowを読むが、validation runを作成・再開・append・seal・publishするserviceはない。test fixtureがrun/rowを直接SQLで完成状態にseedすることはservice実装やlifecycle証拠ではない。
- F1aのcandidate/lease ACL gate修正はSHA `4900a621`、そのMySQL証拠は`8b21b8d6`、javac修正は`d9641b96`、gate後のfresh DB時刻は`e318c277`、expiry跨越テスト補強は`37c4c3d8`にある。rootによる直近MySQL/fast-suite検証は本計画の範囲外であり、未確認結果をPASSと扱わない。
- F1bは`.kiro/specs/data-migration-import-center/feature/f1a-schema-draft.md`、`f1a-validation-lease-slice-plan.md`、`f1a-candidate-lease-authorization-plan.md`、`execution-contract.md`を踏襲する。異なる契約が必要と判明した場合は実装前に差分をAstraへ戻す。

## 2. 目的と明示的な境界

MAPPED jobの固定source/mapping/document snapshotをF2 parser/validatorで処理し、最大250行単位のprivacy-safe metadataを再開可能に保存する。seal後だけ再現可能なcounter/hashを確定し、単一transactionでvalidation結果を公開する。公開結果をcandidate登録が読める実データへ接続し、testの手動completed-run seedをlifecycle serviceに置き換える。

含めるもの:

1. runを伴うlease取得・renew・expired takeover・resumeのrun-aware操作。
2. validation start、bounded row chunk append、exact source EOF検証とseal、sealed metadataのrecount、atomic publish、BUILDING/SEALED runのabort。
3. 各操作での現行actor/permission、tenant、source、document/FileScope、mapping、job/run snapshotとDB-clock lease/fencingの再確認。
4. H2の状態・idempotency unit testsと実MySQLのlock/CAS/FK競合integration tests。

含めないもの: HTTP/API/UI、file upload、任意mapping transform/default/lookup、target ID承認binding、READY/apply/upsert、identity registryの承認状態変更、rollback/compensation、実legacy source、実credential/key、外部通知、NF06全体完了、production enablement。Candidateは引き続き`CANDIDATE`に留まり、publish/validationからregistry writeを呼び出さない。

## 3. 不変条件とtransaction境界

### 3.1 入力・権限・lock順

- runはtenant/job/source ID・version/entity/schema/encoding/source SHA/document ID・exact version ID/no/hash/mapping ID・version/hash/validation fingerprintを固定する。job snapshotやrun input fieldsは開始後に更新しない。
- source原本のread/reparse、Document stream、F2 parser、canonicalizer、read-only validator、recountはDB transaction/row lock外で行う。再確認のためのmetadata queryは各commit前transaction内で行う。
- DB変更を伴うoperationは既定順 `source system → document → document version → IMPORT_JOB typed link（ID昇順）→ job → mapping → validation run → validation row（row number昇順）` を守る。必要ないresourceだけを飛ばす。multi-row read/writeもascending row numberで処理する。
- 各write transactionは現在のprincipal、active admin、tenant-bound exact permission（deny precedence含む）を解決し、実DB上のscope/tenant/legal entity、source ACTIVE/version、document CLEAN・hold/retention・FileScope・正確なIMPORT_JOB link、mapping fingerprintをlock後に再確認する。ACL gateは最後に待つlockより後、write/CASより前に実行し、DB時刻はgate待機後に再読する。403/409とSQL deadlock/timeoutを区別する。
- job PRIMARY row lockはchunk commit、resume/takeover、seal/abort/publishの全処理でfencing直列化点とし、commitまで保持する。MySQLで実service connection/threadのwait edge、READ COMMITTED、requester/blocker、lock key/mode/statusを検証する。H2はlock証拠に使わない。
- `lease_owner`と`lease_expires_at`は一緒に設定/解除し、generationは単調増加する。generation/owner/expiry/job version/active run ID/stateがCASに含まれる。expiryはMySQL primary `CURRENT_TIMESTAMP(6)`を最後の認可gate後に判定する。

### 3.2 Runとjob状態

| 操作 | 前状態 | 成功時 | 必須条件 |
|---|---|---|---|
| start validation | job `MAPPED`または再validationの`VALIDATED`、active runなし | run `BUILDING`、job `VALIDATING`、active pointer/slot設定 | 新generation leaseとrun作成、旧published slot/current pointer解除を同一txで実施。run_no採番も同一job lock下 |
| resume/takeover | job `VALIDATING`、同一active runが`BUILDING`または`SEALED` | 状態維持、generationと`last_resumed_lease_generation`更新 | 未期限の別workerを奪わない。期限切れ後のみgenerationを増加。run input/prefixを再照合 |
| renew | job `VALIDATING`、同一run/owner/generation | expiryを`GREATEST(現expiry, DB現在時刻+30秒)`で非減少更新し、versionを厳密に+1 | generation/run/pointer/input一致かつ未期限。commit応答喪失retryは現expiryが旧grant以上、versionが旧grantより大きい場合に既存grantを返す（expiry同値を許容）。通常CASはexact version/expiry。旧job-only MAPPED grant APIは再利用しない |
| append chunk | run `BUILDING` | 最大250行分を追加または全一致retry | 同一run/current fence、source row number/hash/fingerprint/disposition/finding code完全一致だけ冪等。partial chunkは全rollback |
| seal | run `BUILDING` | run `SEALED` | source全byte SHA/EOF、physical/nonempty/empty計数、行番号順の全row metadata集合が完全一致。後続append禁止 |
| publish | run `SEALED` | run `COMPLETED`、job `VALIDATED`、current pointer/published slot設定 | sealed rows/sourceから確定集計・hash再計算済み、同一transactionでrun/job/pointers/counters/hash/lease解放 |
| abort | run `BUILDING`または`SEALED` | run `ABORTED`、job `MAPPED`、active pointer/slot/lease解除 | active fenceを持つworker。COMPLETEDはabort不可。rowsは監査保持し編集/削除しない |

BUILDING/SEALEDは非公開の途中状態であり、candidate読取や「validation完了」表示対象ではない。current resultは`COMPLETED` runとjob pointer、`published_slot=1`が相互一致する一件だけとする。再validation開始時は旧COMPLETED runを履歴として保持するが、既存schema契約に従い旧published slotとcurrent pointerを同じ開始transactionで解除し、新runのpublishが成功するまでcurrent resultを持たない。開始が失敗すれば全体rollbackし旧結果を保つ。`FAILED`というrun stateはV184契約にないため追加しない。回復可能なworker failureはBUILDING prefix/SEALED stateを保ち、明示abortはABORTEDへ進める。

開始・lease grant後のrun作成を別transactionで行って孤立leaseを作らない。実装はrun startと初回generation獲得を一つのtransactional commandとして扱う。現行`ImportValidationLeaseService`のMAPPED専用APIは互換維持し、run-aware operationは別method/contextにする。resumeは同一active runだけに結び、新run作成へすり替えない。commit応答喪失のretryはserver-issued opaque operation contextと完全一致したjob/run/generation/inputのみ冪等とし、利用者指定worker/generationを受け付けない。

### 3.3 Chunk、再開、seal、publish

- parserはsourceを先頭からstream再読し、全sourceをString/List/JSONへ蓄積しない。chunk上限は250非empty source rows。`source_row_number`はblank physical rowsによるgapを許し、source順序は保持する。
- row保存は`source_row_sha256`、canonical fingerprint、`ACCEPTED/REJECTED`、closed-set finding codes、row commitmentのみとする。raw row、PII field、external key、amount値や自由形式のerror messageは保存しない。
- retry/takeoverはexact sourceを先頭から再parseする。保存済みprefixのrow number/hash/fingerprint/disposition/finding bytes/commitmentを全て照合し、duplicate identity stateをprefixから再構築してから未保存suffixを処理する。同じrow keyでも一項目でも異なれば409相当で停止し、上書きしない。既commit chunkは再挿入しない。
- EOFで元byte source SHA、非empty row順/hash集合と保存済みrows、physical/source/empty countsを照合してBUILDING→SEALEDをCASする。blank row由来の番号gapを不正としない。途中EOF、extra/missing/changed row、parser/fingerprint drift、読取不可、source/document/permission driftはseal/publish拒否。
- 集計はSEALEDの不変row disposition/finding codeと同じexact source再parseからlock外で再計算する。accepted/rejected/empty/physical/finding、全F2 JPY known/excluded fieldsのcount/sum/reasonをV184のclosed schema fieldsに適合させ、NULL/0の意味を区別する。`source = validated_accepted_total + rejected`（V184の`accepted_row_count`が固定基数）、`physical = source + empty_skipped`等のexecution-contract式とsummary hashesを検証する。caller供給counter/hashを信頼しない。
- 最終publish transactionはACL/source/document/mapping再検証、active pointer/current generation/owner/expiry/state CAS、run summary/counter/hash保存、`run.state=COMPLETED`、`active_slot=NULL`、`published_slot=1`、job current pointer/state=`VALIDATED`、lease解放を原子的に行う。失敗時にpartial published resultを残さない。
- abortは途中metadataを消さず、run/rowを履歴として保持しactive slot/pointerとleaseを解放する。source/document/link、old run、auditは削除しない。再validationは新run_noを作る。

## 4. F2・NF-07依存とゲート

F2 pure parser/canonical validatorをrow単位で呼び出し、同じ schema/validator policy fingerprintをrunへ固定する。F2 foundationにないresolver semantics、mapping expression、domain writeはF1bへ混ぜない。候補照合が未実装ならそのfindingをclosed codeでreject/候補待ちとし、validationからregistry writeや自動bindingをしない。F2のparser/fingerprint contractが変わる場合は既存BUILDING runをresumeせず409/abortし、新mapping/schema/validator versionで別runを始める。

NF-07 `privacy-retention-dsar`はDISCOVERY/DEV-0のみで、F1〜MおよびDG-07は未承認と記録されている（`.kiro/roadmap/2026-09-07-snf01-10-remediation-audit.md`）。F1bはsynthetic/dev scopeに限り、既存DocumentServiceのCLEAN/FileScope/hold/retention判定を必須にする。PII inventory、retention/disposition、DSAR、法的承認を代行または実装完了と扱わない。NF-07/DG-07/Privacy Ownerなどの正式ゲートが閉じるまでreal source、個人情報の本番validation/production enablementは不可。NF-07のsource/head競合中はこのplan/実装sliceを独立worktreeで保ち、shared filesを共同編集しない。

## 5. 実装waveと試験受入matrix

Plan PASS後だけ実装を開始し、既存V184 schemaのshapeを先に監査する。schemaに足りない必須情報/constraintがあればmigrationを即興で追加せず、差分を別Astra schema reviewへ返す。V184以降のmigration番号はrootが割り当てる。H2 shared schema/config/`TestIsolationAudit`は変更しない。

| Wave | 変更と受入条件 |
|---|---|
| F1b-1 run-aware command/state | start atomically creates lease+BUILDING run; renew/takeover/resume exact run; version/CAS/opaque op retry; abort; current-pointer lifecycle; rejected transition/tenant/actor/snapshot drift rollback |
| F1b-2 append/recovery | max-250 append; exact-row idempotency; conflicting retry 409; takeover prefix scan; stale generation commit blocked; row payload privacy; partial chunk rollback |
| F1b-3 seal/recount/publish | actual exact source EOF/hash and ordered row-set check; blank row gaps; SEALED immutability; counter/finding/amount rebuild; summary/result hash; atomic current pointer and VALIDATED publish; publish retry response-loss idempotency |
| F1b-4 integration/review | dedicated real MySQL `@Tag("mysql")` fixture and shard inventory once; READ COMMITTED, actual connection IDs, lock waits, FK/snapshot and ACL races; root serial execution with zero skips; fixed-head Astra review |

必須合成test matrix:

1. 初回start、再validation start、単一active run制約、旧completed run保持/current解除、abort、invalid state transition、cross-tenant run/pointer拒否。
2. 0/1/249/250/251 row、empty row gaps、duplicate/missing/reordered row、oversize source/finding bytes、同一chunk再送、異hash/metadata retry拒否。
3. BUILDING chunk commit前/後のcrash、response loss、同worker retry、expired lease takeover、resume時の完全一致prefix、prefix差分/short source/extra source拒否。
4. seal成功後のappend拒否、SEALED recount repeat、source SHA/parser/schema/validator/mapping/document/version/hold/FileScope/actor/permission drift、expiry中のrevokeと失効順序。
5. publish crash前後、同operation再送、旧generationによるpublish拒否、run/job/pointer/counter/hashのall-or-nothing、ABORTED/BUILDINGがcandidateから見えないこと。
6. counter式、physical/source/empty、accepted/rejected/finding、JPY known count/sum/excluded reason、NULL vs zero、same input+policyで同一hash。

必須MySQL gateは独立connection/threadで、(a) chunk writer先行/permission mutation後行、(b) permission/source/document mutation先行/service拒否、(c) takeover対stale chunk commitの両順序、(d) concurrent startの一件成功、(e)同chunk append/retryとrow unique、(f) seal対append、(g) publish対takeover/abortの実lock edge/CAS、(h) deadlock/lock timeoutが403/409へ誤分類されないことを確認する。assertionsは exact connection ID、DATABASE、PRIMARY/index/key、lock type/mode/status、commit後state/version/pointer/count/hashを含む。Fast H2はservice/state/unit checksに限定し、MySQL証拠を代替しない。

10,000行performance/heap計測、browser/UI、full rollback/compensationはM/B2/A1の後続gateに残る。F1b passをそれらの完了と読み替えない。

## 6. Astra review依頼と未決点

Review対象はこの文書とbase SHA `37c4c3d8943f4aa424d513cf4b52078358df01f0`の差分だけ。確認依頼:

1. startがlease generationとBUILDING runを同じtransactionで取得すること、run-aware resume/takeoverがjob-only APIから分離されていること。
2. `VALIDATED → VALIDATING`再検証時にcurrent pointerを開始時に外す既存V184契約、および失敗/abort後の表示意味。
3. F2 source reparse・canonical fingerprint・finding/amount recountをPII/raw row永続化なしに同一算定する十分なAPI境界。
4. SEALED中のlease renew/expiry・abort権限、DB時刻の最終判定、stale workerのjob lock fenceをMySQLで示す試験行列。
5. V184 fields/constraintsでresume-prefix、summary/counter、closed-code dataを十分拘束できるか。不足時はschema-only follow-up reviewを要求する。
6. NF-07 DEV-only/production-blocked状態とDocument hold/retention/FileScopeの具体的統合点。

Astra Plan PASSとrootの固定実装範囲指示を得るまでproduction source/code/schema/migrationは変更しない。本計画単体はsynthetic implementation approvalでもproduction approvalでもない。
