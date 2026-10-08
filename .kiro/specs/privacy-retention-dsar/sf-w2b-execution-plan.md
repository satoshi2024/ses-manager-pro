# SF-W2b 投影・attestation 技術実行計画

状態: **実装前の技術実行案 / Astra追加Review待ち**。対象は合成journalからapplication DBへの再構築可能投影、独立attestor署名checkpoint、latest witness照合とquarantine契約に限る。実PII、実credential、実provider、外部呼出、production bean/接続、Flyway migration、実処分、policy/Legal承認を追加しない。W2aの`HOLD_RELEASED` fail-closedと合成`DISPATCH_AUTHORIZED` ACKの非送信許可性を維持する。

## 1. 現行コード差分監査

W2a固定sourceには`PrivacyJournalPort`とpackage-private `JdbcPrivacyJournalAdapter`があり、journal event/head/hash chain、command replay、lease fenceとMySQL内projection整合性を検証する。W2aの`sf_journal_*`はjournal自身のevent由来状態であり、application DB側の独立projectionではない。`VerifiedActionReceipt`は既存case/action receiptの境界であり、attestor checkpointとは別概念である。`SyntheticEnvironmentGuard`は合成authority audienceとactive prodを拒否するが、W2b接続capabilityを追加する場合はtest-only factoryが実container所有権も検証しなければならない。

W2aのtest-only `JournalTestConnectionCapability` はactive `test`、prod/default-prod拒否、harness所有container由来の接続だけを許す既存パターンとなる。W2bはこの考え方を複製せず、3つの独立した一時MySQL（journal、app projection、witness）を1つのtest-only harnessが所有する形に拡張する。任意JDBC URL、DataSource、環境変数、filesystem pathから公開capabilityを作れないことを接続試行前に保証する。

本計画ではW2a既存sourceを変更しない。実装時に変更が不可避と判明したら差分を止めてReview対象へ戻す。現状の独立projection、attestor署名鍵/witness、freshness/recovery verifier、W2b P10–P12/P14回帰は未実装である。

## 2. 型・ファイル配置

main sourceは`src/main/java/com/ses/privacy/foundation/`内のpackage-private型に限定する。Spring `@Component`/`@Bean`、controller、scheduler、public constructorによるraw connection受け渡しは追加しない。

| 型 / ファイル案 | 役割・不変条件 |
|---|---|
| `PrivacyProjectionPort` | typed `apply(JournalEvent)`、`head(partition)`、`snapshot(partition)`境界。適用結果はAPPLIED/NO_OP/QUARANTINEDを明示し、boolean成功を権限に転用しない。 |
| `JdbcPrivacyProjectionAdapter` | app DB test schemaだけを更新。`last_applied_revision`とevent hashをCASし、同revision/same hashはno-op、same revision/different hash、gap、projection revisionがjournal headを超えるケースはQUARANTINED。 |
| `JournalProjectionService` | journal event/head検証→app projection適用の順を制御。journal DB transaction完了後に別app DB transactionを開始し、両DB間transactionを作らない。 |
| `WatermarkCanonicalizer` | domain/version prefix付きcanonical UTF-8 bytesを一意に生成し、null/長さ/順序/Instant精度を固定。domainはW2b専用。 |
| `SyntheticWatermarkAttestor` | test-only private keyで連続journal prefixを署名。attestorはjournal tail/event hashを再読してheadとの一致を確認してから署名する。 |
| `LatestWitnessPort` / `JdbcLatestWitnessAdapter` | 第3独立DBにcheckpoint epoch/revision/hash/key ID/signature digestをCAS保存し、latest値を取得。witness値がjournal/app snapshot内にだけ存在する構成は禁止。 |
| `SignedWatermarkVerifier` | public trust anchor, key history, signature, canonical payload, latest witness一致、checkpoint freshnessを検証。古いが正しい署名だけではrestoreを通さない。 |
| `ProjectionRecoveryService` | 起動時・restore時はREAD_ONLY_QUARANTINEDで開始し、witness latestから信頼可能範囲とjournal tailを検査後、冪等replayと一致検証を行う。write/publication有効化を返すAPIはこの合成層にも置かない。 |
| test-only `TemporaryW2bHarness`, `SyntheticAttestorKeyFixture`, schema resources | Testcontainersでjournal/app/witness各MySQLを個別起動し、test内Ed25519 keyを生成し、private keyはtest source set外へ保存しない。環境credential/URLは読まない。 |

Application projectionはtenant/subject partition、applied revision、event hash、projected hold/action stateの最小opaque値だけを保持する。削除原文やprovider responseは保存しない。app projection schemaは`src/test/resources/privacy-journal/`等の合成test-only resourceとし、versioned migrationは作らない。W2a schema resourceを変更せず別ファイルに置く。

## 3. Canonical bytes・鍵・checkpoint

Checkpoint envelopeはschema versionとdomain separationを必須とし、以下のfieldをlength-prefixで固定順に符号化する。

`domain || schemaVersion || journalId || tenantId || subjectRef || epoch || eventRevision || eventHash || previousCheckpointHash || issuedAtMicros || keyId`

署名はEd25519とし、hash表記は小文字hex、日時はUTC epoch microseconds、文字列はUTF-8 byte長prefix、大小文字/末尾空白は保持する。canonicalizerのencode/decode往復、一field差し替え、順序/区切り曖昧化、別domain署名再利用をpure testで拒否する。PII/raw payloadはfieldに含めない。

各test harnessがattestor private keyを新規生成し、対応public keyだけをverifier trust anchorへ登録する。private key fixtureは`src/test/java`に限定し、生成鍵にTEST/SYNTHETIC audienceを持たせる。main sourceにprivate key、key loader、環境変数key fallbackを置かない。unknown key、signature不正、compromise/revocation state unknown、witness unavailableはfail-closedとする。

Witness `latest`はpartitionごとに単調epoch、revision、event hash、checkpoint hashをCAS更新する。attestor/witness updateは一つの複合test operationだがDBを跨いだatomicityは主張しない。witness更新後にapp projectionが失敗しても、recoveryはlatest checkpointまで再投影して収束する。attestor生成中のcrash/ACK unknownはcommand/checkpoint IDで照会し、値を推定しない。witness巻戻り、同epoch異hash、revision後退は拒否する。

## 4. CAS・ロック順・recovery

journal本体を変更するcommandはW2aの固定lock orderを維持し、W2b projectorはjournalをread-onlyで検証してから別connection/transactionでapp projectionを更新する。DB間の同時lock保持を避ける。各projection partition transactionは次の順序に固定する。

1. app projection headをpartition PKで`SELECT ... FOR UPDATE`し、同一transaction内でevent identity/expected predecessorを確認する。
2. `incomingRevision == currentRevision + 1`かつprevious event hash一致ならinsert/updateと`WHERE revision=:expected` CASを同時実行する。
3. `incomingRevision == currentRevision && incomingHash == storedHash`ならNO_OP。hashが異なる場合はQUARANTINED。
4. gap (`incomingRevision > currentRevision+1`)、backward (`incomingRevision < currentRevision`で未照合)、app revision > journal head、snapshot/head hash不一致はQUARANTINED。自動でrevision/hashを補正しない。
5. row transaction commit後にのみ適用済み結果を返す。commit ACK不明ではsnapshotを再読し、same revision/hashなら収束、その他はQUARANTINED。

Restoreはapp projectionをREAD_ONLY_QUARANTINEDとして起動し、witnessから最新checkpointを取得する。witnessが不在/読取不能、signature/key unknown、checkpoint epoch/revision/hashが後退・分岐、journal prefixが欠落/改竄、witness latestより先に未attested tailがある場合はpublication/write-enableを拒否する。watermark検証だけでjournal headが完全とはみなさず、witness checkpoint以降のtailも連続hash chainとして全件検証してからreplayする。

回復順は (a) witness latest read、(b) journal chainとcheckpoint対応prefix検証、(c) projection snapshotとの比較、(d) 必要eventをrevision順に適用、(e) headと全event hash再照合、(f) 同一手順を二回実行して二回目が全件NO_OPであること、で固定する。recovery中の新event/holdはhead再読で検知し、watermark/replay対象を更新して再検査する。local projection値はhold/dispatchの認可根拠に使わない。W2bにはdispatch worker/認可APIが存在しない。

## 5. 失敗・quarantine契約

| 失敗 | 結果 |
|---|---|
| projection same revision/same hash | NO_OP、状態不変 |
| same revision/different hash、gap、backward、projection ahead | QUARANTINED、公開/書込/dispatch不可 |
| witness unavailable、old/replayed witness、epoch/revision rollback | QUARANTINED、ローカル署名cacheへfallback不可 |
| unknown/compromised key、署名不正、canonical bytes不一致 | QUARANTINED、信頼推定不可 |
| journal ACK unknown / attestor timeout | command/checkpoint IDで独立照会。確認までprojectionの成功/失敗を推定しない |
| projection commit ACK unknown | app snapshot再読。完全一致ならno-op収束、それ以外quarantine |
| recovery中の新hold/event | revision再読・再照合までquarantine維持 |

QUARANTINEDは固定reason codeとopaque evidence digestを記録し、自由形式例外/raw SQL/provider応答を記録しない。W2bは人手のreconciliation経路を実装しない。誤ったsnapshot値を正本へ昇格させない。

## 6. Acceptance matrix / Testcontainers境界

P10–P12、P14を専用W2b MySQL classで実証する。各caseはtest-owned一時3 containerを使い、container ID、random DB名、schema hash、key public fingerprint、network/provider call countを記録する。private key/password/JDBC secretは証拠へ出さない。Docker必須でskip-on-unavailableを使わない。Maven/MySQLの実行はrootが割当・直列化する。

| ID | 試験入力 | 必須結果 |
|---|---|---|
| P10 projection revision CAS | 正常r→r+1、same r/hash再送、same r/different hash、gap、backward、app revision > journal head、commit応答喪失 | 初回のみAPPLIED、exact replay NO_OP、残りQUARANTINED。重複/穴埋めeventなし。 |
| P11 signed checkpoint | canonical envelope正常、tampered signature/field/domain、unknown key、key compromise unknown、epoch/revision CAS競合、witness ACK loss | 正常署名のみ検証。競合/不明はfail-closed、private keyはtest harness外へ出ない。 |
| P12 restore/latest witness | 古いsnapshot + valid旧watermark + 完全な旧prefix、witness最新前/後tail、journal gap/tamper、witness unavailable/rollback/unknown key | valid old watermarkでもlatest witnessとの差でpublication/write-enable拒否。fresh prefixだけreplay可。 |
| P12b replay repeatability | 一度目replay、同じsnapshotから再度replay、復元中に新hold/event注入 | 二度目全件NO_OP。新eventが見えたらquarantine維持し再照合。 |
| P14 projection/checkpoint transaction boundaries | 各app row書込段階、checkpoint/witness前後、commit前後でfault/response loss | partial transactionはrollbackまたはquarantine。witness進行後のprojection遅れは復旧可能。ACK不明を成功扱いしない。 |
| P15 connection capability | arbitrary DataSource/DB_URL/env credential、prod、prod+test、default-prod、test permitなし、test後のprofile変更 | DB接続試行・attestor鍵生成・schema作成=0で拒否。 |

Fault injectionはtest-only JDBC wrapper/trigger/barrierに閉じる。providerは存在せず、external provider call countは常に0であることをassertする。

## 7. 実装batchとreview固定点

1. **Batch A — 契約/fixture schema**: test-only 3-DB harness, app/witness schema resources, typed records, canonicalizer, synthetic Ed25519 fixture。boundary/pure testsのみ。
2. **Batch B — projection CAS**: `PrivacyProjectionPort`, JDBC projection adapter, quarantine result types。P10/P14 projection fault subset。
3. **Batch C — attestor/witness**: checkpoint signer/verifier, independent latest witness CAS, profile/capability refusal。P11/P15。
4. **Batch D — restore/replay**: recovery service, latest checkpoint reconciliation, repeat replay and old snapshot tests。P12/P12b。
5. **Batch E — evidence packet**: fixed HEAD/source/schema manifest hashes, test output and 0 skip evidence, provider call count=0, explicit未実装範囲。実装後にW2b limited code reviewを依頼する。

Batchごとに小さくcommitし、W2a source・中央traceability・migration・production wiringは変更しない。技術Plan reviewがPASSするまではAstra reviewに提出する本計画以外のW2b実装を開始しない。各batchで想定外の既存source変更が必要なら停止し、差分と必要理由をrootへ返す。

## 8. 参照・残存境界

- `.kiro/specs/privacy-retention-dsar/synthetic-foundation.md` §§SF-03a/SF-04a
- `.kiro/specs/privacy-retention-dsar/sf-w2-protocol.md` §§5–9、W2a実装境界追記
- `.kiro/specs/privacy-retention-dsar/sf-w2a-jdbc-review-packet.md` 固定source・test boundaryと未実装範囲
- `.kiro/roadmap/2026-10-01-snf-production-nf07-foundation-contract.md` §§SF-03a/SF-04a
- `.kiro/roadmap/2026-10-01-snf-production-plan-review-r3.md` NF07追加Plan事項
- `.kiro/roadmap/2026-10-02-snf-production-plan-review-r4.md` checkpoint freshness/old watermark条件

W2b synthetic technical reviewはNF07全機能、NF07 policy/legal/DSAR、worker、実provider、DG-07、正式Privacy Owner、production approvalを満たさない。W2aのhold解除authority/SoDおよびdispatch proof不足も解消しない。
