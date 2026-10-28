# SF-W3a Synthetic identity-and-scope preview persistence

状態: **実装済みsliceの履歴計画 / 2026-10-18固定Head `92d91d58172288406f6048e69ad38aa85880eb04` をAstraが限定CODE PASS、control `b6e2992b` へ統合。** ledger記録のMySQL acceptanceは26/0/0/0（UTC 12:39:06.5468658〜12:40:50.7872174、JST 21:39:06.5468658〜21:40:50.7872174）で、synthetic preview persistence/as-of再検証/ACK_UNKNOWN/CASに限る。ここは当時のplanであり、今回の修正は新runtime測定・再試験・新Reviewではない。実PII、実業務DB、実file/object、実provider、Spring production bean/controller、migration、export/delivery、外部dispatch、approval、delete/anonymize/restrict、retention処分は含めない。

証拠の読み方: `providerRead` と `crossScopeAccess` はfixture単位で動的計測する値。provider write、dispatch、dispositionは実行回数を測った値ではない。`SyntheticPreviewPort` のread-only API面とserviceの権限依存を構造assertionで検査し、出力では `structural.*Capability=ABSENT` と表す。構造ログは `verifiedCapabilitiesHavePrivateConstructorsAndServiceHasNoAuthorityDependencies` の対応する構造testcase結果と併せて読むこと。AfterEachのログ1行だけではassertion通過を証明しない。これはこのsliceの狭いport/依存境界に関する証拠であり、全システムにwriteや外送能力がないとの主張ではない。

W2b recoveryの`RECOVERED_SYNTHETIC`は合成projectionの検証結果であり、本人確認、scope、policy、case approval、dispatchの証明ではない。D2b2の実MySQL試験結果は別のAstra固定Head確認に委ねる。この文書はD2b2またはNF07全体のPASSを主張しない。

## 1. 目的と実装境界

SF-W3aは、最初の合成DSAR業務経路として次だけを扱う。

```text
REQUESTED -> IDENTITY_VERIFIED -> SCOPED -> PREVIEWED
                  |                |          |
                  +-> BLOCKED      +-> UNKNOWN/BLOCKED
                                   +-> UNKNOWN/BLOCKED
```

identityが曖昧、tenant/requester/subjectの束縛が不正、scope/providerが未知、holdがactive、policy/journal状態が不明ならfail-closedとする。previewは技術的な合成結果であり、本人請求の受理、法的期限、処分候補の承認、処分許可または外部送信許可を意味しない。合成fixture上の`CANDIDATE`も`PREVIEW_ONLY`であり、approval/actionへ進めない。

main sourceはpackage-privateな型・service境界に限定する。test-only harnessが署名鍵、identity issuer、fixture provider、生成target、一時schemaを所有する。production bean、controller、scheduler、業務schema migration、実credential/path/DB_URLの受け渡しは禁止する。test dataはharnessが生成し、fixture manifest hashで固定する。

## 2. Identity evidenceとrequest binding

1. identity proofはtest harnessが生成鍵で署名するimmutable envelopeとする。少なくともschema/domain version、audience=`SYNTHETIC`、tenant ID、request ID、requester opaque ref、subject opaque ref、identity revision、issued/expiry、payload hash、key ID、signatureをcanonical bytesへ束縛する。
2. verifierだけが署名・key・期限・audienceと全bindingを検証し、検証成功時にのみ内部`VerifiedSyntheticIdentity` capabilityを発行する。capabilityはboolean、DTO、public constructor、文字列subject IDから生成できない形にする。approvalの`approved=true`はidentity proofとして受け付けない。
3. 氏名、email、電話、社員番号、同姓同名一致をidentity証明に使わない。name-only、候補複数、未検証、失効/期限不明、unknown key、署名不正、tenant/requester/request/subject/revision差し替えはprovider read前に`BLOCKED`または`UNKNOWN`で終了する。
4. log/resultはopaque request/subject参照とclosed reason codeだけを扱い、envelope payload、signature、raw PIIを出さない。

## 3. Read-only scope capabilityとpreview provider

現在の`PrivacyProviderRegistry`はprovider ID/audienceの登録境界でありquery APIではない。W3aではこれを検索済みscopeの証明と混同せず、限定された`SyntheticPreviewPort`（test-only実装は`SyntheticPreviewProvider`）を別に定義する。

- serviceは検証済みidentityからtenant、request、opaque subject、provider ID、allowed synthetic target IDs、scope revision/hashを束縛した内部`VerifiedSyntheticPreviewScope`を作る。raw subject、name、任意SQL、任意table/path、任意URLはportに渡さない。
- 実行順序は、tenant/request binding → identity proof検証 → scope snapshot検証 → registryでprovider ID/audience確認 → fixture対象のread-only queryとする。いずれか不明なら後段へ進まず、provider query countを0とする。
- provider readごとにidentity/scope capabilityとcurrent case revisionを再検査する。複数targetのreadの間でscope/revisionが変わった場合は以降のreadを止め、既読結果もcommit前のsnapshot/hash再照合が通るまで保存しない。
- providerはscope capability内の生成targetだけを返し、結果はsynthetic target ID/version/content hash/closed status codeに限定する。source payload/raw artifact/provider responseを返さない。
- providerアクセス証跡はopaque request ID、provider ID、scope hash、synthetic target ID、result codeだけに限定する。testはscope外tenant/subject/provider/targetへのアクセス試行数が0であることを記録・assertする。serviceが渡した対象外行をproviderが返した場合もfail-closedとし、previewを保存しない。
- production provider、実業務DataScope/FileScope/AI/backup/replicaを検索する実装はこのportへ登録しない。provider registryのpresenceだけではscope authorizationを与えない。

## 4. Preview resultとケース状態

`PrivacyCaseStateMachine`の`REQUESTED → IDENTITY_VERIFIED → SCOPED → PREVIEWED`のみをこのserviceで許可する。既存FSMが`PREVIEWED → APPROVAL_PENDING`を定義していてもW3a serviceはその遷移を拒否する。`BLOCKED`と`UNKNOWN`は終端であり、このsliceから再開するedgeを持たない。binding変更時は明示的な新case revisionまたは新requestを要求する。

preview resultの許可項目は次のものだけとする。

```text
requestRef, caseRevision, scopeHash, policySnapshotHash,
syntheticTargetId, targetVersion, contentHash,
status: CANDIDATE | BLOCKED | UNKNOWN,
reasonCode, dispositionLabel, PREVIEW_ONLY
```

保存recordと読出viewは別型にする。保存recordは生成したscope内のread観測を再現する**immutable as-of history**であり、現時点の候補状態や認可を表さない。read/replay/exact retryのたびに、記録済みbindingの全項目を現在のidentity/scope/target/policy/hold/audit/journal状態へ再照合してからviewを作る。snapshotのresultが`CANDIDATE`でも、それをそのまま返すAPIや保存record直読は設けない。

`PREVIEW_STALE`、`PREVIEW_BLOCKED`、`PREVIEW_UNKNOWN`は読出responseのclosed statusであり、`PrivacyCaseState`への新しい遷移ではない。既存case/revision/historyは不変のまま残し、同じrequestを再処理して状態を上書きしない。

- 再照合が一致し必要なproofが期限内で、revocation/hold/policy/journal状態も既知の場合だけ、viewに`AS_OF_PREVIEW_ONLY`とsnapshotの`observedAt`範囲・`validatedAt`を付ける。これは各source read時点の合成previewであり、法的適格性、全storeの同時snapshot、commit時点または将来時点での有効性を意味しない。
- expiry、identity/scope/case/target/policy revision/hash drift、hold/journal headの変化を検出した場合は`PREVIEW_STALE`（reason codeで対象を特定）を返し、保存済みcandidate detailを再表示しない。active holdは`PREVIEW_BLOCKED`、状態/失効/最新値が読めない場合は`PREVIEW_UNKNOWN`とする。失効・scope外・不一致をUNKNOWNへぼかさず、具体的なclosed reasonを保持する。
- immutable historyは同一requestの正確な再送で更新/上書きしない。明示的な履歴読出も毎回完全再検証し、current bindingが変化した場合は`PREVIEW_STALE/BLOCKED/UNKNOWN`だけを返す。raw historyを開示する履歴UI/exportはW3aに含めない。

raw field/value、file/artifact bytes、free text、provider response/message、secret、signed approval、`VerifiedApproval`、fence/dispatch proof、action receipt、実行可能なcommandは保存・出力しない。`dispositionLabel`はfixture表示用であり、実処分方式の選択や法的判断ではない。

- `ACTIVE` holdは`BLOCKED`としcandidateを出さない。
- policy、owner/purpose/trigger、retention起算、auditまたはprovider/scopeの必要情報が未提供なら`UNKNOWN`/`BLOCKED`とする。数値保持日数、法的期限、法的根拠を技術側で補わない。
- `CANDIDATE`は明示的なsynthetic-only fixture policyとfixture blockerなしが一致する場合だけ出せる。結果全体に`PREVIEW_ONLY`を付け、approval/dispatch/dispositionへ転用できない。
- journal unavailable、ACK unknown、partition mismatch、projection/recovery quarantineは`UNKNOWN`または`BLOCKED`であり、`RECOVERED_SYNTHETIC`、古いpreview、local cacheから権限を推定しない。

## 5. Persistence、CAS、idempotency

case、case revision、identity/scope/policy snapshot hash、preview item、closed status/reason、idempotency recordはtest-only schema (`src/test/resources/privacy-journal/`等)に保存する。Flyway migrationやV1変更は作らない。schema準備権限はtest harnessに限る。

1. `(tenant_id, request_id, idempotency_key)`をuniqueにし、同一key・同一request hashは保存済みの**immutable snapshot**を参照し、同一key・異なるhashはconflictとする。exact retryでproviderを再queryしたりcase revision/resultを更新したりしない。返す前に下記の完全なcurrent revalidationを行い、drift/expiry/unknownなら旧candidateを返さずclosed viewにする。新しいsnapshotには新request ID/idempotency keyを要求する。
2. case revisionはCASで単調増加し、case revisionとimmutable preview record/idempotency mappingは同じ合成test DB transactionでatomicにcommitする。このatomicityはtest DB内だけのものであり、identity/scope/provider/hold/journal等の別storeとは分散transactionでもglobal snapshotでもない。
3. provider read中はcase/projection row lockを保持しない。query前・provider readごと・final readごとにidentity/scope capability、case revisionとexpiryを再検査する。各storeから得たobserved version/hashと時刻を集め、commit前にcase CASと、独立readによる一回のbounded final revalidationを行う。
4. immutable preview recordは全source snapshot witnessと観測時刻を含む。最低限、identity envelopeのrevision/hash/key/audience/expiry状態、request/case revision、scope revision/hash、provider ID/audience、target synthetic ID/version/content hash、synthetic policy version/hash/effective/expiry/revocation state、hold/audit/blocker revision/hash、journal head revision/event hash、witness epoch/revision/checkpoint hash/key state、および各sourceの`observedAt`をopaque metadataとして束縛する。raw identity envelope、target data、signature/private keyは保存しない。異なるstoreの各readはひとつのDB snapshotを構成しないため、`observedAt`ベクトル/観測区間を記録し、global as-of atomicityを主張しない。
5. 最終独立readの後からtest DB commitまでに別storeが変化するwindowを閉じたとは主張しない。commit barrier後にdriftを検出した場合、CASで確定したimmutable as-of recordは履歴として残してよいが、応答は`PREVIEW_STALE`/`PREVIEW_BLOCKED`/`PREVIEW_UNKNOWN`とsnapshot referenceだけにする。provider readや無限retryで同requestを更新しない。post-commit response validationを行う場合も一回に限定し、全bindingが一致したときだけ`AS_OF_PREVIEW_ONLY` viewと`validatedAt`を返す。validate不能・不一致ならclosed viewを返し、candidate detailは返さない。いずれのresponseも「commit時点で全storeが一致した」または将来も有効との保証ではない。
6. DB commit ACK不明は独立read-backで同一key/revision/snapshot hashを有限照合する。一致してもimmutable recordを取得してから必ず完全なcurrent revalidationを行う。read-backまたは再検証で確証を得られない場合は`PREVIEW_UNKNOWN`とし再query/writeせず、旧previewを成功扱いにしない。
7. persistenceは合成ケースとpreview witnessだけを保持する。identity envelopeのraw field、source payload、実際のPII/削除対象、provider receiptを記録しない。

### 5.1 exact retryとread contract

exact retryはidempotency keyによってimmutable recordを特定するだけであり、現在の表示許可を与えない。replay/view作成時に毎回次を再確認する: requester/request/case revision binding、identity signature/key/audience/expiry/revocation、scope revision/hash、provider registration/audience、各target version/content hash、policy version/hash/effective/expiry/revocation state、holdとblocker最新revision、journal head/event hashおよびwitness latest/key/freshness。いずれかの現在値を独立readできない場合も一致扱いにしない。

| 再確認結果 | exact retry/read response | 再query/write |
|---|---|---|
| 全binding一致、expiry内、state既知 | `AS_OF_PREVIEW_ONLY` + 元snapshotの`observedAt`と再確認`validatedAt`。candidate detailも合成previewとして表示し、権限には変換しない | なし |
| identity/scope/target/policy/case revision/hash drift、proof expiry/revoked | `PREVIEW_STALE` + closed reasonとsnapshot ref。旧candidate detailを現在表示として返さない | なし。新request/keyが必要 |
| hold activeまたは明示blocker | `PREVIEW_BLOCKED` + blocker reason | なし |
| policy/hold/audit/journal/witness状態不明、read timeout/ACK不明 | `PREVIEW_UNKNOWN` + closed reason | なし。状態を人または別承認sliceで解決して新request |

成功後のexact retryについても各分類（identity expiry/revocation、scope変更、policy変更/expiry、hold開始/解除revision、journal/witness進行、target version/hash変更、case revision変更、source read unavailable）を個別MySQL testで実証する。drift検出後、同じidempotency keyは古いsnapshotをrefresh/overwriteしない。保持されたas-of recordを現在candidateへ昇格する経路はない。

## 6. Authorityと失敗時の拒否

このsliceのservice constructor/dependency graphには`VerifiedApproval`、`VerifiedDispatchFence`、signer、journal append/`DISPATCH_AUTHORIZED` command、provider write/execute client、production DataSourceを含めない。legal policy/case approvalを生成・検証したふりをしない。testで有効なsynthetic approval envelopeを別に作れても、W3a serviceは受け取らず、`PREVIEWED → APPROVAL_PENDING`以降を拒否する。

| 条件 | 結果 | provider read / preview persistence |
|---|---|---|
| identity proof missing/ambiguous/invalid/expired | `BLOCKED` or `UNKNOWN` | 0 / 0 |
| tenant/requester/subject/revision不一致 | `BLOCKED` | 0 / 0 |
| scope/provider未登録またはscope不一致 | `UNKNOWN` or `BLOCKED` | 不一致確認前は0 / 0 |
| hold active | `BLOCKED` | 0 / BLOCKED reasonのみ |
| policy/audit/journal状態不明 | `UNKNOWN` | 新しいtarget readなし / UNKNOWN reasonのみ |
| query中のdrift | `PREVIEW_STALE`/`PREVIEW_BLOCKED`/`PREVIEW_UNKNOWN` | immutable candidate recordを新規保存しない |
| final independent read後〜test DB commit前の別store drift | immutable as-of historyは保存可。responseは必ずclosed status + snapshot ref | 同じrequestの再queryなし。candidate detail/authorizationは返さない |
| post-commit validationでdrift/unavailable | `PREVIEW_STALE`/`PREVIEW_BLOCKED`/`PREVIEW_UNKNOWN` | 保存historyは変更しない。再試行はboundedで追加recovery/requeryなし |
| successful preview後のexact retryでexpiry/scope/policy/hold/journal/target drift | 上記closed status。古いcandidate detailをcurrent viewとして出さない | 0 provider query、0 write、0 revision increment |
| journal recoveryが`RECOVERED_SYNTHETIC` | そのstatusは診断情報のみ | identity/scope/approval/dispatch権限を与えない |

`UNKNOWN`/`BLOCKED`からcandidateへ暗黙の再試行edgeを作らない。人が変更したscope/identity/policyを新revisionへ反映した後も、本sliceはpreviewまでに留まり、承認操作は別slice/別gateである。

## 7. Testcontainers acceptance matrix

rootがMySQLを直列実行する。すべてのcaseはharness所有の一時MySQL/schemaと生成fixtureで実行し、DB/container ID、fixture manifest hash、opaque request ref、provider read/write/dispatch countを記録する。secret、private key、credential、subject原文、raw target dataは出力しない。Docker unavailable時はskipせず失敗として扱う。

| Case | 実fixture / 競合 | 受入条件 |
|---|---|---|
| identity proof | valid ephemeral signatureとtenant/requester/subject/revisionを1字段ずつ差し替え、期限/audience/key/signatureも変更 | 完全bindingのvalid proofだけ`IDENTITY_VERIFIED`へ進む。bool/name-only、曖昧identityはreject、provider query=0 |
| scope and provider | allowlisted synthetic targetに対するread-only provider。別tenant/subject/provider/targetを用意 | service経由のreadは同一scopeのみ。cross-scope access=0、未登録provider call=0 |
| preview output | candidate、active hold、unknown policy/audit/provider/journal fixture | closed result schema、raw data/artifactなし、`PREVIEW_ONLY`常在。holdはBLOCKED、unknownはUNKNOWN、policy fixtureなしにcandidateなし |
| CAS/idempotency | exact replay、same key/different payload、同revision競合2 worker | exact retryはimmutable snapshotを再検証してからview化。異payload conflict、CAS成功は一つ、case/revision/history atomic |
| successful-preview exact retry drift | 初回成功後にidentity expiry/revocation、scope、policy/期限、hold、journal/witness、target hash、case revisionを各々変更・停止 | 各再送は適切な`PREVIEW_STALE/BLOCKED/UNKNOWN`。古いcandidate current viewなし、provider query=0、write/revision increment=0 |
| pre-commit drift | provider query後とfinal read前のbarrierでidentity/scope/target/policy/hold/journalを変更 | 古いsnapshotはcommitしない。closed reason、旧preview上書きなし、same request requeryなし |
| final-read-to-commit race | 全independent final read完了後、test DB commit barrier前に別storeのscope/target/policy/hold/journalを変更 | test DBにimmutable as-of recordが保存されても、post-commit bounded revalidationはclosed viewだけを返しcandidate detailを返さない。recordは上書きしない。global atomicityをclaimしない |
| state/authority boundary | `PREVIEWED → APPROVAL_PENDING`、有効synthetic approval、`RECOVERED_SYNTHETIC`を提示 | service遷移拒否。approval/fence/action receiptを生成しない。journal dispatch append、external call、disposition write各0 |
| hold/recovery failures | hold ACTIVE、journal unavailable/ACK unknown/quarantine | BLOCKED/UNKNOWN。local stateやrecovery statusでhold解除/identity/policy/dispatchを推定しない |

当時の成功条件はこのsynthetic preview persistence sliceの対象caseがすべてpassし、0 skip、read/cross-scopeの動的計測、およびprovider write/dispatch/disposition能力がport/依存構造にないことをassertionで確認することに限る。固定文字列のゼロ値をruntime計測値として扱わない。D2b2 deadline、一般JDBC/socket hard bound、NF07 full feature、retention policyの法的正しさ、production readinessを証明しない。

## 8. Files、順序、Review gate

計画後の実装候補（このPLAN commitには含めない）:

- main package-private: `SyntheticIdentityEnvelope`/verifier/verified capability、`VerifiedSyntheticPreviewScope`、narrow `SyntheticPreviewPort`、closed `PreviewResult`、`SyntheticPrivacyRequestService`。
- 実装時の配置候補: `src/main/java/com/ses/privacy/foundation/SyntheticIdentityEnvelope.java`、`SyntheticIdentityEvidenceVerifier.java`、`VerifiedSyntheticIdentity.java`、`VerifiedSyntheticPreviewScope.java`、`SyntheticPreviewPort.java`、`PreviewResult.java`、`SyntheticPrivacyRequestService.java`。mainにはtest private key/signerを置かない。
- test-only: `src/test/java/com/ses/privacy/foundation/SyntheticIdentityFixtureSigner.java`、`SyntheticPreviewFixtureProvider.java`、`SyntheticPrivacyRequestServiceMySqlTest.java`、drift barrier、temporary MySQL schema。
- schemaは`src/test/resources/privacy-journal/`以下だけ。`TemporaryW2bHarness`を利用する場合も既存W2a/W2b sourceを改変せず、harness所有container/capabilityを使う。
- W3a planは`synthetic-foundation.md`のSF-W3/SF-W4方向性とrequirements PR-R2/PR-R3の前半を明確に限定する。承認後の実装sliceであって、Full Feature gateやF1/A2の着手承認ではない。

順序: (1) この計画の独立Astra PLAN review、(2) 指摘修正と固定plan SHA、(3) 別commitでsynthetic implementation、(4) rootが実MySQL acceptanceを直列実行、(5) implementation delta review。PLAN PASS前にコードを開始しない。F1 catalog/policy DDL、実identity、real provider/search/export、approval UI、hold release authority、dispatch worker、retention/disposition実行、production bean/APIは後続人手gateの後に別途計画する。

## 9. 人工承認が必要なpolicy値と、先行設計可能な技術契約

以下のproduction値は人が実在する外部承認記録とowner付きで決定するまで`UNKNOWN/BLOCKED`のままとし、本sliceへ設定値として持ち込まない。

- Privacy Owner、approved base/scope、purpose/legal basis、data-elementごとのowner/collection trigger。
- retention duration、起算trigger、期限計算/タイムゾーン、policy version/effective range、各data class/国・regionの差異。
- legal/audit/statutory holdの種類、開始/解除authority、SoD、緊急経路、解除証拠。
- 本人確認証拠の正式標準、人手resolution責任者、request deadline/response window、case owner、拒否理由、appeal/reopen、delivery。
- provider/recipient/region/DPA、AI G10 scope、backup/replica/search対象範囲、third-party redactionの正式規則。
- audit/legal document/recruiting/identity evidenceの保存規則、disposition method（delete/logical delete/anonymize/restrict）、二者approval、feature flag/runbook/monitoring/emergency stop、restore/RPO/RTO。

以下は正式な法的数値を決めずに先行設計・合成検証できる。

- opaque IDs、canonical/signed envelope schema、audience/domain separation、expiry/revocation verification、typed verified capabilities。
- request/case revision、CAS、idempotency key/hash、snapshot hash、read-without-lock再検証、closed reason codes。
- `REQUESTED/IDENTITY_VERIFIED/SCOPED/PREVIEWED/BLOCKED/UNKNOWN`の技術状態とdefault deny、target/provider scope binding。
- `Instant` timestamp/as-of接点、generated synthetic fixtures、test-only schemas/providers、drift/parallelism/fault acceptance matrix。
- `PREVIEW_ONLY` output allow-list、zero provider/write/dispatch countとprovenance/evidence format。

synthetic fixtureに使う仮ポリシー値は明示的にtest-only audienceへ束縛し、production configuration、既定保持期間、法的判断、allow-listへ転用できないことを試験する。
