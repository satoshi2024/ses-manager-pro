# SF-W3a Synthetic identity-and-scope preview persistence

状態: **PLAN案 / Astra独立Plan Review待ち。未実装。** このsliceは、生成データだけを使う要求のidentity binding、scope限定read-only preview、およびpreview結果の合成test storeへの永続化を計画する。実PII、実業務DB、実file/object、実provider、Spring production bean/controller、migration、export/delivery、外部dispatch、approval、delete/anonymize/restrict、retention処分は含めない。

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

raw field/value、file/artifact bytes、free text、provider response/message、secret、signed approval、`VerifiedApproval`、fence/dispatch proof、action receipt、実行可能なcommandは保存・出力しない。`dispositionLabel`はfixture表示用であり、実処分方式の選択や法的判断ではない。

- `ACTIVE` holdは`BLOCKED`としcandidateを出さない。
- policy、owner/purpose/trigger、retention起算、auditまたはprovider/scopeの必要情報が未提供なら`UNKNOWN`/`BLOCKED`とする。数値保持日数、法的期限、法的根拠を技術側で補わない。
- `CANDIDATE`は明示的なsynthetic-only fixture policyとfixture blockerなしが一致する場合だけ出せる。結果全体に`PREVIEW_ONLY`を付け、approval/dispatch/dispositionへ転用できない。
- journal unavailable、ACK unknown、partition mismatch、projection/recovery quarantineは`UNKNOWN`または`BLOCKED`であり、`RECOVERED_SYNTHETIC`、古いpreview、local cacheから権限を推定しない。

## 5. Persistence、CAS、idempotency

case、case revision、identity/scope/policy snapshot hash、preview item、closed status/reason、idempotency recordはtest-only schema (`src/test/resources/privacy-journal/`等)に保存する。Flyway migrationやV1変更は作らない。schema準備権限はtest harnessに限る。

1. `(tenant_id, request_id, idempotency_key)`をuniqueにし、同一key・同一request hashは同じrevision/resultを返し、同一key・異なるhashはconflictとする。case revisionはCASで単調増加し、case revisionと対応preview resultを同じ短いDB transactionでcommitする。
2. provider read中はcase/projection row lockを保持しない。query開始前にverified identity/scope capabilityを検査し、commit直前に独立readでcase revision、identity revision、scope hash、target version/content hash、policy snapshot hashとjournal/hold状態を再確認する。
3. いずれかの値がquery中に変わった場合、古いpreviewを保存/上書きせず`SCOPE_OR_TARGET_CHANGED`等のclosed codeで終了し、新しいrevisionまたはrequestで再previewを要求する。test barrierでread中およびcommit直前のdriftを実MySQL上で注入する。
4. DB commit ACK不明は独立read-backで同一key/revision/hashを有限照合する。一致が確証できない場合は`UNKNOWN`とし再度query/writeせず、old previewを成功扱いにしない。
5. persistenceは合成ケースとpreviewだけを保持する。identity envelopeのraw field、source payload、実際のPII/削除対象、provider receiptを記録しない。

## 6. Authorityと失敗時の拒否

このsliceのservice constructor/dependency graphには`VerifiedApproval`、`VerifiedDispatchFence`、signer、journal append/`DISPATCH_AUTHORIZED` command、provider write/execute client、production DataSourceを含めない。legal policy/case approvalを生成・検証したふりをしない。testで有効なsynthetic approval envelopeを別に作れても、W3a serviceは受け取らず、`PREVIEWED → APPROVAL_PENDING`以降を拒否する。

| 条件 | 結果 | provider read / preview persistence |
|---|---|---|
| identity proof missing/ambiguous/invalid/expired | `BLOCKED` or `UNKNOWN` | 0 / 0 |
| tenant/requester/subject/revision不一致 | `BLOCKED` | 0 / 0 |
| scope/provider未登録またはscope不一致 | `UNKNOWN` or `BLOCKED` | 不一致確認前は0 / 0 |
| hold active | `BLOCKED` | 0 / BLOCKED reasonのみ |
| policy/audit/journal状態不明 | `UNKNOWN` | 新しいtarget readなし / UNKNOWN reasonのみ |
| query中またはcommit前にbinding/hash drift | `UNKNOWN` / 再preview要求 | 古いresultは0保存 |
| journal recoveryが`RECOVERED_SYNTHETIC` | そのstatusは診断情報のみ | identity/scope/approval/dispatch権限を与えない |

`UNKNOWN`/`BLOCKED`からcandidateへ暗黙の再試行edgeを作らない。人が変更したscope/identity/policyを新revisionへ反映した後も、本sliceはpreviewまでに留まり、承認操作は別slice/別gateである。

## 7. Testcontainers acceptance matrix

rootがMySQLを直列実行する。すべてのcaseはharness所有の一時MySQL/schemaと生成fixtureで実行し、DB/container ID、fixture manifest hash、opaque request ref、provider read/write/dispatch countを記録する。secret、private key、credential、subject原文、raw target dataは出力しない。Docker unavailable時はskipせず失敗として扱う。

| Case | 実fixture / 競合 | 受入条件 |
|---|---|---|
| identity proof | valid ephemeral signatureとtenant/requester/subject/revisionを1字段ずつ差し替え、期限/audience/key/signatureも変更 | 完全bindingのvalid proofだけ`IDENTITY_VERIFIED`へ進む。bool/name-only、曖昧identityはreject、provider query=0 |
| scope and provider | allowlisted synthetic targetに対するread-only provider。別tenant/subject/provider/targetを用意 | service経由のreadは同一scopeのみ。cross-scope access=0、未登録provider call=0 |
| preview output | candidate、active hold、unknown policy/audit/provider/journal fixture | closed result schema、raw data/artifactなし、`PREVIEW_ONLY`常在。holdはBLOCKED、unknownはUNKNOWN、policy fixtureなしにcandidateなし |
| CAS/idempotency | exact replay、same key/different payload、同revision競合2 worker | exact retryは同じ保存結果、異payload conflict、CAS成功は一つ、case/revision/result atomic |
| read-to-commit drift | provider query後barrierでidentity/scope/target/policy/journal revisionを変更 | 古いsnapshot resultをcommitしない。reason closed、旧preview上書きなし、再queryには新revisionを要求 |
| state/authority boundary | `PREVIEWED → APPROVAL_PENDING`、有効synthetic approval、`RECOVERED_SYNTHETIC`を提示 | service遷移拒否。approval/fence/action receiptを生成しない。journal dispatch append、external call、disposition write各0 |
| hold/recovery failures | hold ACTIVE、journal unavailable/ACK unknown/quarantine | BLOCKED/UNKNOWN。local stateやrecovery statusでhold解除/identity/policy/dispatchを推定しない |

成功条件はこのsynthetic preview persistence sliceの対象caseがすべてpassし、0 skip、provider write/dispatch/disposition=0であることに限る。D2b2 deadline、一般JDBC/socket hard bound、NF07 full feature、retention policyの法的正しさ、production readinessを証明しない。

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
