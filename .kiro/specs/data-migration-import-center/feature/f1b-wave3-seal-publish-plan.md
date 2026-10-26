# F1b wave3: EOF seal・recount・publish計画

状態: W3B seal設計（Astra PLAN Review依頼用）。W3A counter/capabilityコードは9ab64f13で実装済みだが、同固定SHAでの独立Review（Astra CODE PASS）および実行証拠（20261024-counter-fast-01/mysql-01）は未取得であり、W3B実装開始前の必須前置条件として保持する。本更新はW3Bの実施前置核検・最小設計を行うdocs-only差分であり、実source適合、NF-06全体完了、production enablementを意味しない。

## 1. 対象と現状

- 計画対象は合成sourceに対する`BUILDING → SEALED → COMPLETED`と、保存済みvalidation resultを`VALIDATED`として公開するlifecycleに限る。業務apply、target binding、HTTP/API/UI、実legacy source、production data、外部provider/keyは扱わない。
- source基線は`9ab64f133726b62835a4f9023263c2c6772dc62f`。この更新は本計画だけのdocs-only差分とし、source wait oracle、control/NF-08/NF-01は変更しない。
- 参照する正本は`f1b-validation-lifecycle-plan.md`、wave2の範囲は`f1b-wave2-append-plan.md`、schema列・契約は`f1a-schema-draft.md`と`V184__create_import_metadata_foundation.sql`である。wave3は既存F1b-3のseal/recount/publishを実装可能な単位（W3A, W3B, W3C, W3D）へ小分けする。
- 現行`ImportValidationAppendService.appendRemaining`は互換wrapperとして`AppendCompletion.lease()`を返す。内部`appendRemainingWithSealCapability`はsourceの全検証scan、row metadataのappend、EOF/source SHAの再確認、row-set commitment計算、および`ValidationCounterAccumulator`によるfinding codes / 5金額項目の厳密な集計を経て、private constructorの`SealCapability`を持つ内部`AppendCompletion`を返す（W3Aコード実装済み）。
- `ImportValidationLifecycleService.appendChunkInTransaction`はjob rowをfenceとしてresourceをlockし、現在のACLとDB時刻を確認してから`BUILDING`へ最大250 rowをappendする。`state <> 'BUILDING'`のrunへのappendは拒否される。しかし`BUILDING`から`SEALED`への状態遷移を行うseal CASサービス（W3B）は未実装である。
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

## 3. Wave 3A: 現状区分と前置核検証跡

### 3.1 現存W3A実装内容（SHA 9ab64f13時点）

W3Aのコードは現在worktree上に実装済みである：
1. `foundation/ValidationCounterAccumulator.java`:
   - row単位の重複finding code排除、closed code順のcanonical encoding（`SES-IMPORT-FINDING-COUNTS-V1`）、SHA-256計算、8192バイト上限検査。
   - 5固定金額項目（`expectedUnitPrice`, `unitPriceMin`, `unitPriceMax`, `sellingPrice`, `costPrice`）のknown/excluded分類、18桁以内非負整数JPY、DECIMAL(38,0) sum溢れ検査、0円とNULLの区別。
   - reason code別のcanonical encoding（`SES-IMPORT-AMOUNT-EXCLUSION-COUNTS-V1`）、SHA-256計算、8192バイト上限検査。
   - 変更不能な`ValidationCounterAccumulator.Snapshot`（防御的複製、equals/hashCode内容比較）。
2. `metadata/ImportValidationAppendService.java`:
   - `appendRemainingWithSealCapability(RunLease)`: read scanとwrite scanのcounter完全一致検査、全source rowsとpersisted rowsの一対一・全項目照合、EOFおよびsource SHA再検証。
   - 不可偽造な`SealCapability`（private constructor、同パッケージ限定、イミュータブル、Lease/Snapshot/rowSetSha256/counters束縛）。
   - source順のordered commitment（`SES-IMPORT-ROW-SET-V1`）計算。
3. 対応テスト:
   - `ValidationCounterAccumulatorTest.java`（単体試験）。
   - `ImportMetadataRealMySqlTest.java`（W3A結合試験）。

### 3.2 必須前置条件（W3A review / runtime 前置の未充足一覧）

固定SHA `9ab64f133726b62835a4f9023263c2c6772dc62f` において、以下の前置条件が未充足である：
- **ランタイム検証証拠の未生成**: `verification-evidence/run-nf06-counter-fast-01-1024.ps1` および `run-nf06-counter-mysql-01-1024.ps1` は準備されているが、証拠ディレクトリ `verification-evidence/nf06-fixed/20261024-counter-fast-01` および `20261024-counter-mysql-01` は未実行であり、ログ・結果アーティファクトが存在しない。
- **Astra独立Reviewの未受審**: コミット `8f9aaad0` 以降（`3f4616a9`, `2f317977`, `1c7b5546`, `9ab64f13`）のW3A実装に対するAstra CODE PASS記録が存在しない。
- **前置ゲート遵守原則**: これらの前置証拠をスキップまたは事後追認してW3B実装を進めることは禁止される。W3Bは本設計文書のAstra PLAN PASS後、かつW3Aのランタイム証拠・CODE PASS取得を待って実装を開始する。

## 4. Wave 3B: BUILDINGからSEALEDへのCAS詳細設計

### 4.1 目的と責務境界

exact sourceのEOF走査とrow appendを完了し発行された`SealCapability`を消費し、job PRIMARY lock下で保存済み行セット（`t_import_validation_row`）と証明を完全照合した上で、単一トランザクション内で確定集計値・ハッシュ群を書き込み、runを`BUILDING → SEALED`へ原子的にCASする。

- **含めるもの**:
  - `SealCapability`の消費と偽造防止検証。
  - 既定ロック順序に従う全リソースロックと現行権限・状態の再検証。
  - ACLゲート通過後のDB現在時刻（`CURRENT_TIMESTAMP(6)`）によるリース期限厳格判定。
  - 保存済みDB行セットの昇順ストリーミング走査と`row_set_sha256`の再照合。
  - 正準`summary_sha256`の算出。
  - runの確定集計列書き込みと`BUILDING → SEALED`の単一トランザクションCAS。
  - トランザクション失敗時の零部分書き込み（全原子ロールバック）。
  - コミット応答喪失（ACK loss）後の冪等再試行回復。
  - `append`, `renew`, `takeover`, `abort`との競合直列化検証。
- **明確な除外対象**:
  - recount, publish, apply, UI, 実業務インポート, 本番有効化（production enablement）。

### 4.2 実装対象ファイル一覧

| 区分 | ファイルパス | 変更/新設 | 責務概要 |
|---|---|---|---|
| 本番コード | `src/main/java/com/ses/importcenter/metadata/ImportValidationLifecycleService.java` | 変更 | `seal(SealCapability capability)` メソッドおよび `sealInTransaction` の追加。リソースロック・権限再検証・行セット照合・CAS・ACK loss冪等回復を実装。 |
| 本番コード | `src/main/java/com/ses/importcenter/metadata/ImportValidationAppendService.java` | 軽微変更 | `SealCapability` のpackage-privateアクセサ確認（必要に応じて追加）。SEALED後の追記拒否ガードの再確認。 |
| テスト（H2） | `src/test/java/com/ses/importcenter/metadata/ImportValidationSealServiceTest.java` | **新規作成** | Fast feedback用単体・状態遷移テスト。CAS条件、row-set不一致、件数不一致、金額制約、サマリーハッシュ算出、ACK loss冪等性を網羅。 |
| テスト（MySQL） | `src/test/java/com/ses/importcenter/metadata/ImportValidationSealRealMySqlTest.java` | **新規作成** | 実MySQL競合・分離性統合テスト（`@Tag("mysql")`）。ロック待機順序、デッドロック回避、renew/takeover/abort/appendとの競合CAS、期限切れ境界を検証。 |

> [!IMPORTANT]
> 既存の `ImportMetadataRealMySqlTest.java` は既に約4000行に達しており可読性・保守性リスクが高いため、W3Bの検証コードを同ファイルへ追加して肥大化させることを禁止し、独立した専用テストクラス `ImportValidationSealRealMySqlTest.java` および `ImportValidationSealServiceTest.java` に分離する。

### 4.3 SealCapability の消費・不可偽造性契約

- `SealCapability` は `ImportValidationAppendService` の private constructor でのみ生成可能であり、外部 caller、controller、test から直接インスタンス化できない。
- `ImportValidationLifecycleService.seal(SealCapability capability)` は以下の不変条件をエントリ時点で検証する：
  - `capability != null` かつ `capability.lease() != null`。
  - `instanceId` が自サービスインスタンスと完全一致（別プロセス・別インスタンスのトークンを拒否）。
  - `tenantId`, `jobId`, `runId`, `generation`, `workerId`, `jobVersion` が内包する `RunLease` と完全一致。
  - `counters` が非NULLであり、内部不変条件（`physical = source + empty`, `source = accepted + rejected`, 5金額項目完備）を満たしていること。

### 4.4 ロック順序・認可・リソース・時刻検証

sealトランザクション（`REQUIRES_NEW / READ_COMMITTED`）内では、以下の厳格な順序で排他ロック（`FOR UPDATE`）を取得する：
1. `t_import_source_system`: `tenant_id`, `id` でロック。`status = 'ACTIVE'`, `version_no` 一致を確認。
2. `t_document`: `tenant_id`, `id` でロック。`deleted_flag = 0`, `legal_hold_flag = 0`, `legal_entity_id` 一致, `retention_until >= CURRENT_DATE` を確認。
3. `t_document_version`: `tenant_id`, `document_id`, `id` でロック。`deleted_flag = 0`, `scan_status = 'CLEAN'`, `version_no` 一致, `sha256` 一致を確認。
4. `t_document_link`: `target_type = 'IMPORT_JOB'`, `target_id = jobId`, `deleted_flag = 0` を `ORDER BY id FOR UPDATE` でロック。リンクが単一であることを確認し、`authorizeImportJobDownload` を呼び出し。
5. `t_import_job` (PRIMARY): `tenant_id`, `id` でロック。競合操作（append/renew/takeover/abort/seal）のフェンスとしてコミットまで保持。
6. `t_import_mapping`: `tenant_id`, `id` でロック。`entity_type`, `schema_version`, `mapping_version`, `mapping_sha256`, `canonical_bytes` 一致を確認。
7. `t_import_validation_run`: `tenant_id`, `job_id`, `id` でロック。`state = 'BUILDING'`, `active_slot = 1`, `published_slot IS NULL`, `validation_fingerprint` 一致を確認。
8. `t_import_validation_row`: 後述の行セット検証にて `source_row_number ASC` でページング走査。
9. **最終認可ゲート**: 全リソースロック待機後に `authorization.requireCurrentAuthorizationForWrite` を実行し、現在のアクターおよびテナント認可（拒否優先）を再判定。
10. **DB現在時刻の取得**: 認可ゲート待機完了直後に MySQL primary の `SELECT CURRENT_TIMESTAMP(6)` を発行し、`lease_expires_at > dbNow` を判定。待機中に期限切れを迎えた場合は fail-closed（409 conflict）とする。

### 4.5 DB row-set 走査と整合性照合

job PRIMARY lock 下で、`t_import_validation_row` の永続行を走査し、`SealCapability` の証明と一対一で照合する：
1. **件数照合**:
   `SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?`
   取得件数が `capability.counters().sourceRows()` と厳密に一致すること。
2. **順序付き row-set SHA 再計算**:
   - `source_row_number ASC` で固定サイズ（250件）ページングストリーミングを実施し、全行DTOをヒープへ一括蓄積しない。
   - `MessageDigest` をドメイン `"SES-IMPORT-ROW-SET-V1"`、バージョン `new byte[]{0, 0, 0, 1}` で初期化。
   - 各行について、`source_row_number`（Long.BYTES, 8バイト）、コミットメントバイト長（Integer.BYTES, 4バイト）、`row_commit_sha256`（32バイト）を順次digestへ投入。
   - 算出された `HexFormat.of().formatHex(digest.digest())` が `capability.rowSetSha256()` と完全一致すること。
   - 走査中に1行でもコミットメントの欠落・順序不正・差分があれば即座に 409 conflict で中断・ロールバック。

### 4.6 summary_sha256 正準形式定義

runサマリー全体の不可逆・決定論的な正準ハッシュ `summary_sha256` は、以下の仕様に従い算出する：
- ドメイン: `"SES-IMPORT-VALIDATION-SUMMARY-V1"`（ASCII bytes）
- フォーマットバージョン: `1`（32-bit int, 4バイト）
- 項目（順序固定・length-prefix連結）:
  1. `tenant_id`（UTF-8 bytes, 4バイト長プレフィックス）
  2. `job_id`（64-bit long, 8バイト）
  3. `run_id`（64-bit long, 8バイト）
  4. `source_sha256`（32バイトバイナリ）
  5. `validator_fingerprint`（32バイトバイナリ）
  6. `row_set_sha256`（32バイトバイナリ）
  7. `source_row_count`（64-bit long）
  8. `physical_row_count`（64-bit long）
  9. `empty_row_count`（64-bit long）
  10. `accepted_row_count`（64-bit long）
  11. `rejected_row_count`（64-bit long）
  12. `finding_summary_sha256`（32バイトバイナリ）
  13. 5金額項目（名前辞書順 `costPrice`, `expectedUnitPrice`, `sellingPrice`, `unitPriceMax`, `unitPriceMin`）:
      - フィールド名（ASCII bytes, 4バイト長プレフィックス）
      - `accepted_known_count`（64-bit long）
      - `accepted_sum_yen`（count > 0 の場合: 円単位整数文字列のUTF-8 bytes, 4バイト長プレフィックス。count == 0 の場合: 長さ 0）
      - `rejected_known_count`（64-bit long）
      - `rejected_sum_yen`（count > 0 の場合: 円単位整数文字列のUTF-8 bytes, 4バイト長プレフィックス。count == 0 の場合: 長さ 0）
      - `excluded_count`（64-bit long）
  14. `amount_excluded_reason_sha256`（32バイトバイナリ）
- 上記ストリームの SHA-256 ダイジェスト（32バイト）を `summary_sha256` とする。

### 4.7 単一トランザクション CAS と原子性

照合完了後、同一トランザクション内で以下のCAS更新を原子的に実行する：
```sql
UPDATE t_import_validation_run SET
    state = 'SEALED',
    sealed_at = CURRENT_TIMESTAMP(6),
    source_row_count = ?,
    physical_row_count = ?,
    empty_row_count = ?,
    accepted_row_count = ?,
    rejected_row_count = ?,
    finding_summary_bytes = ?,
    finding_summary_sha256 = ?,
    expected_unit_price_accepted_known_count = ?,
    expected_unit_price_accepted_sum_yen = ?,
    expected_unit_price_rejected_known_count = ?,
    expected_unit_price_rejected_sum_yen = ?,
    expected_unit_price_excluded_count = ?,
    unit_price_min_accepted_known_count = ?,
    unit_price_min_accepted_sum_yen = ?,
    unit_price_min_rejected_known_count = ?,
    unit_price_min_rejected_sum_yen = ?,
    unit_price_min_excluded_count = ?,
    unit_price_max_accepted_known_count = ?,
    unit_price_max_accepted_sum_yen = ?,
    unit_price_max_rejected_known_count = ?,
    unit_price_max_rejected_sum_yen = ?,
    unit_price_max_excluded_count = ?,
    selling_price_accepted_known_count = ?,
    selling_price_accepted_sum_yen = ?,
    selling_price_rejected_known_count = ?,
    selling_price_rejected_sum_yen = ?,
    selling_price_excluded_count = ?,
    cost_price_accepted_known_count = ?,
    cost_price_accepted_sum_yen = ?,
    cost_price_rejected_known_count = ?,
    cost_price_rejected_sum_yen = ?,
    cost_price_excluded_count = ?,
    amount_excluded_reason_counts = ?,
    amount_excluded_reason_sha256 = ?,
    row_set_sha256 = ?,
    summary_sha256 = ?
WHERE tenant_id = ? AND job_id = ? AND id = ?
  AND state = 'BUILDING'
  AND active_slot = 1
  AND published_slot IS NULL
  AND last_resumed_lease_generation = ?
```
- **job行の取り扱い**:
  `t_import_job` の `state` は `VALIDATING` のまま維持し、`active_validation_run_id`（= runId）、`lease_owner`, `lease_generation`, `lease_expires_at` をそのまま保持する。`current_validation_run_id` は `NULL` のままとする（SEALEDは未公開）。
- **更新件数検証**:
  run更新件数が厳密に 1 でない場合は即座に例外をスローしトランザクションを完全ロールバックする。
- **零部分書き込み保証**:
  トランザクションがロールバックされた場合、runは `BUILDING` に留まり、サマリー列や `sealed_at` が一部だけ更新されることはない。

### 4.8 応答喪失（ACK loss）再試行と冪等回復

sealトランザクションのコミット後にネットワーク切断等でクライアントへの応答が喪失した場合の再試行セマンティクス：
1. `lockResources` で取得した run が既に `state = 'SEALED'` の場合：
   - 同一runId、同一 `last_resumed_lease_generation`、同一 `job.active_validation_run_id` を確認。
   - 保存済みの `row_set_sha256` および `summary_sha256` が渡された `SealCapability` から算出した値とバイト単位で完全一致するか照合。
   - 保存済みの各カウンター・サマリーハッシュが完全一致する場合に限り、再更新を行わず（`sealed_at` やバージョンを再更新しない）、読み取った既存の sealed 状態を成功として返却する。
2. 異なる `SealCapability`、ハッシュ差分、世代不一致、または非SEALED状態への遷移（ABORTED等）が検出された場合は 409 conflict を返却する。

### 4.9 競合直列化契約

| 競合シナリオ | 先行操作 | 後続操作 | 挙動と結果 |
|---|---|---|---|
| append 対 seal | append が job lock を先取 | seal が待機 | append がコミットするとDB行セット・ハッシュが変化。seal再検証時にDB行数またはrow-set hash不一致となり seal は 409 conflict で拒否される（または全行一致再送 append の場合は seal 成功）。 |
| append 対 seal | seal が job lock を先取 | append が待機 | seal がコミットし run は `SEALED` となる。後続 append は `state <> 'BUILDING'` ガードにより 409 conflict で拒否され、永続行・集計値は不変。 |
| renew 対 seal | renew が job lock を先取 | seal が待機 | renew が `version_no` をインクリメント。sealのjobバージョン照合が不一致となり 409 conflict で拒否される。 |
| renew 対 seal | seal が job lock を先取 | renew が待機 | seal は job version を変更しないため、seal コミット後、未期限であれば後続 renew は正常に完了可能。 |
| takeover 対 seal | takeover が job lock を先取 | seal が待機 | takeover が `lease_generation` をインクリメント。sealの世代照合またはrunの `last_resumed_lease_generation` 照合が不一致となり seal は 409 conflict で拒否される。 |
| takeover 対 seal | seal が job lock を先取 | takeover が待機 | seal 実行時点で未期限であれば takeover は「未期限worker奪取禁止」により 409 conflict。 |
| abort 対 seal | abort が job lock を先取 | seal が待機 | abort が run を `ABORTED`、active pointer を解除。seal は `state <> 'BUILDING'` により 409 conflict で拒否される。 |
| abort 対 seal | seal が job lock を先取 | abort が待機 | seal が run を `SEALED` に進める。現行 `abortInTransaction` は `state IN ('BUILDING', 'SEALED')` を許容しているため、同世代・有効期限内の現workerによる明示的 abort は成功し `ABORTED` へ遷移可能。 |
| seal 待機中 expiry 跨越 | - | seal が待機 | リソースロックや認可ゲートで待機中にリースの `lease_expires_at <= dbNow` となった場合、認可ゲート直後のDB時刻検査で即座に検知され、CASを実行せず 409 conflict で拒否。 |

### 4.10 状態・トランザクション遷移表

| 項目 | 前状態 | 遷移条件 | 成功後状態 | ロールバック/拒否時 |
|---|---|---|---|---|
| `t_import_job.state` | `VALIDATING` | `active_validation_run_id = runId`, `current_validation_run_id IS NULL`, `lease_expires_at > dbNow` | `VALIDATING`（維持） | `VALIDATING`（維持） |
| `t_import_job.active_validation_run_id` | `runId` | 一致 | `runId`（維持） | `runId`（維持） |
| `t_import_job.current_validation_run_id` | `NULL` | 一致 | `NULL`（維持） | `NULL`（維持） |
| `t_import_job.lease_generation` | `gen` | `capability.generation() = gen` | `gen`（維持） | `gen`（維持） |
| `t_import_job.version_no` | `v` | `capability.jobVersion() = v` | `v`（維持） | `v`（維持） |
| `t_import_validation_run.state` | `BUILDING` | `active_slot = 1`, `published_slot IS NULL`, `last_resumed_lease_generation = gen`, 全行セット照合一致 | `SEALED` | `BUILDING`（維持） |
| `t_import_validation_run.sealed_at` | `NULL` | CAS成功 | `CURRENT_TIMESTAMP(6)` | `NULL`（維持） |
| `t_import_validation_run.active_slot` | `1` | CAS成功 | `1`（維持） | `1`（維持） |
| `t_import_validation_run.published_slot` | `NULL` | CAS成功 | `NULL`（維持） | `NULL`（維持） |
| `t_import_validation_run` サマリー列・ハッシュ群 | `NULL` | 全制約（null/0, カウンター等式, 8192バイト）適合 | 確定値・正準ハッシュが保存 | `NULL`（維持、零部分書き込み） |
| `t_import_validation_row` | N行 | 変更なし（読取・照合のみ） | N行（不変） | N行（不変） |

### 4.11 負向テストマトリクス

| テスト分類 | テストケースID | 試験内容と注入障害 | 期待される判定と保護動作 |
|---|---|---|---|
| 不正トークン | NEG-SEAL-01 | 他テナントの `SealCapability` を提示 | 403 Forbidden（認可・テナント境界拒否） |
| 不正トークン | NEG-SEAL-02 | 別job / 別run の `SealCapability` を提示 | 409 Conflict（job/run 不一致） |
| 不正トークン | NEG-SEAL-03 | 旧世代（stale generation）の `SealCapability` を提示 | 409 Conflict（世代不一致） |
| 不正トークン | NEG-SEAL-04 | 期限切れの `SealCapability` を提示 | 409 Conflict（lease expired） |
| 行セット差分 | NEG-SEAL-05 | DB側に余分な未検証行（suffix row）が存在 | 409 Conflict（行数不一致、CAS未実行） |
| 行セット差分 | NEG-SEAL-06 | DB側の行が1行欠落（行番号抜け） | 409 Conflict（行数不一致・row-set hash不一致） |
| 行セット差分 | NEG-SEAL-07 | DB側の行コミットメント（`row_commit_sha256`）が改ざん | 409 Conflict（row-set hash不一致、全ロールバック） |
| 行セット差分 | NEG-SEAL-08 | 行番号順序が逆転しているデータが存在 | 409 Conflict（昇順不変条件違反） |
| サマリー契約 | NEG-SEAL-09 | `physical != source + empty` の不整合カウンター | 409 Conflict（カウンター整合性契約違反） |
| サマリー契約 | NEG-SEAL-10 | `source != accepted + rejected` の不整合カウンター | 409 Conflict（カウンター整合性契約違反） |
| サマリー契約 | NEG-SEAL-11 | known count = 0 なのに sum != NULL | 409 Conflict（V184 amount nulls 制約拒否） |
| サマリー契約 | NEG-SEAL-12 | known count > 0 なのに sum == NULL | 409 Conflict（V184 amount nulls 制約拒否） |
| サマリー契約 | NEG-SEAL-13 | sum が DECIMAL(38,0) 上限超過（39桁以上） | 409 Conflict（金額精度溢れ拒否） |
| サマリー契約 | NEG-SEAL-14 | finding summary bytes が 8192 バイト超過 | 409 Conflict（VARBINARY上限超過拒否） |
| サマリー契約 | NEG-SEAL-15 | amount reason summary bytes が 8192 バイト超過 | 409 Conflict（VARBINARY上限超過拒否） |
| 競合直列化 | NEG-SEAL-16 | seal 中に並行 append が先にコミット | 後続 seal は 409 Conflict（行セット変更検知） |
| 競合直列化 | NEG-SEAL-17 | seal 完了後に遅延 append が試行 | 409 Conflict（`state <> 'BUILDING'` により append 拒否） |
| 競合直列化 | NEG-SEAL-18 | seal 中に並行 renew が先にコミット | 後続 seal は 409 Conflict（job version 不一致） |
| 競合直列化 | NEG-SEAL-19 | seal 中に並行 takeover が先にコミット | 後続 seal は 409 Conflict（generation 不一致） |
| 競合直列化 | NEG-SEAL-20 | seal 中に並行 abort が先にコミット | 後続 seal は 409 Conflict（run ABORTED 検知） |
| 時刻・認可境界 | NEG-SEAL-21 | ロック待機中にリース有効期限を跨ぐ | 409 Conflict（fresh DB現在時刻判定で拒否） |
| 時刻・認可境界 | NEG-SEAL-22 | ロック待機中にアクター権限が revoke される | 403 Forbidden（認可ゲートで拒否、CAS未実行） |
| 外部リソース | NEG-SEAL-23 | ロック待機中に source system が INACTIVE 化 | 409 Conflict（source status 不一致） |
| 外部リソース | NEG-SEAL-24 | ロック待機中に document に legal_hold が設定 | 409 Conflict（document hold 検知） |
| 冪等回復 | NEG-SEAL-25 | 同一 SealCapability による二重 seal 呼出し（ACK loss） | 成功（同一サマリー・ハッシュ返却、二重更新なし） |
| 冪等回復 | NEG-SEAL-26 | SEALED 完了済みの run に対し別ハッシュの SealCapability で再試行 | 409 Conflict（ハッシュ差分拒否） |
| 原子性検証 | NEG-SEAL-27 | CAS 途中で SQL エラー・障害注入 | 全ロールバック（run は BUILDING のまま、サマリー空） |

### 4.12 テスト実装方針（独立クラス分離）

1. **`ImportValidationSealServiceTest.java`（Fast Suite / H2 / Mock）**:
   - Spring context または直接モック/H2 datasource を利用し、短時間で実行可能な単体テスト集。
   - NEG-SEAL-01 〜 NEG-SEAL-15, NEG-SEAL-25 〜 NEG-SEAL-27 を中心に、バリデーションロジック、正準ハッシュ生成、ゼロ部分書き込みを検証。
2. **`ImportValidationSealRealMySqlTest.java`（Testcontainers MySQL / `@Tag("mysql")`）**:
   - 既存の肥大化した `ImportMetadataRealMySqlTest` には追加せず、完全に独立したテストクラスとして新設。
   - NEG-SEAL-16 〜 NEG-SEAL-24 を中心に、実MySQL上でのInnoDB行ロック順序、デッドロック不在、トランザクション分離（READ COMMITTED）、wait edge、および `CURRENT_TIMESTAMP(6)` 境界を実環境で検証。

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

1. **W3A capability + EOF/reconciliation** — `ImportValidationAppendService`のappend completionにprivate-ctor tokenを追加。全parser/source/row proofとcounterを作る。H2境界test・MySQL結合testを通過し、V184 row schemaは変更しない。（コード実装完了、検証証拠生成とAstra CODE PASS待ち）。
2. **W3B seal transaction** — `ImportValidationLifecycleService`へseal CASを追加。resource/ACL/time lock order、row-set recheck、summary persistence、ACK-loss retryを実装して独立したH2 state tests（`ImportValidationSealServiceTest`）とMySQL lock tests（`ImportValidationSealRealMySqlTest`）を行う。
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
