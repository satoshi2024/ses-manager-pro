# data-migration-import-center Requirements

## 1. 状態と承認境界

| 項目 | 値 |
|---|---|
| Feature | data-migration-import-center / NF-06 |
| Status | TECHNICAL_SCOPE_SELECTED / PLAN_REVIEW_PENDING（合成W0設計。実source移行は未承認） |
| Approved entity/schema | <APPROVED_SCOPE>（未入力） |
| Owner | <OWNER>（未入力） |
| Base | 1443fb68943849a3260d7ebe005259f3eb609d60（今回の開発基線。実source承認ではない） |
| DG-06 | 未決定 |

旧Discovery記録は当時の状態を保持する。今回の技術委任による合成W0はexecution-contract.mdを正本とする。DG-06は未決定で、W0は文書と合成fixture設計のみ。業務DDL/apply/upsert/rollback/UI更新は追加しない。

## 2. 用語

- source row: header後の非空データ行。完全空行は別counter `empty_skipped` とする。
- canonical DTO: parserの出力を現行domainの型・単位・正規化規則へ変換した不変値。DB entityそのものではない。
- mapping version: entity/schema version、列mapping、encoding、date/amount format、transform、lookup、upsert policyを一体で版管理する値。
- candidate resolution: source keyが既存候補へ一致した際、候補IDと根拠を表示し、承認を要求すること。
- accepted: validateでエラーなしとなったrow。apply済みを意味しない。
- applied: domain service呼出が成功し、作成または更新の業務結果が確定したrow。

## 3. 要件

### R0. Discoveryと開始ゲート

1. システムは、source schema、対象entity、schema version、natural key、重複時policy、既存行更新の承認、rollback/compensation方針、Owner、baseをjob開始条件として確認しなければならない。
2. DG-06が未承認でも合成W1の実装/検証を妨げない。real source jobは未承認schema/mappingをMAPPED/VALIDATED/READYへ進めず、production data applyを拒否する。
3. 旧schema fixtureは、機密除去済みDDL、型/制約、代表正常/異常行を含む承認済みartifactでなければならない。推測したfixtureを本番の証拠として扱ってはならない。
4. entityごとの自然キーと依存順は、mapping versionの一部として記録し、変更時は別versionと再previewを要求しなければならない。

### R1. Upload・mapping・preview

1. 管理者は、許可されたentity/schema versionとsource fileをjobに登録できなければならない。
2. upload時に、file size、content type、拡張子、magic/scan、encoding、BOM、header、quote、改行を検証し、source SHA-256を保存しなければならない。
3. CSVはUTF-8 BOM/no BOMまたはShift_JISを利用者が明示選択し、quoted comma/newline/escaped quoteを復元する。黙示encoding推定/fallbackは禁止し、選択値と検証結果を記録する。
4. XLSXは初期scope外とし、magic/拡張子検証で明示的に拒否する。
5. mappingは、required/default/transform/lookup/date/amount formatと自然キーの各列を保存し、未定義列・重複列・必須列欠損をpreview errorにする。
6. previewは入力rowをDBへ書き込まず、canonical候補、候補ID、警告、error code/message、source row number、source row hashを返さなければならない。

### R2. Validate（DB no-domain-write）

1. validateはsource原本を再現可能なparserで読み、parser→canonical DTO→read-only validationの順で評価し、domain data tableへwriteしてはならない。
2. validate中に、domain serviceのsave/update/replace/resolveOrCreate、mapper insert/update/delete、sequence採番、監査対象の業務変更を実行してはならない。
3. field validationにはrequired、長さ、enum/allowlist、日付range、JPY BigDecimalのscale/precision/非負条件、巨大cell、formula文字列を含める。
4. cross-row validationにはsource natural key重複、参照順、customer-project-contract整合、engineer/project/position存在、proposal status、期間、金額整合を含める。
5. read-only lookupは、論理削除行を現行行と混同せず、候補ID、論理削除、複数候補、未解決を別error codeに分類しなければならない。
6. formula injectionと通常の負数を区別し、入力値を勝手にformulaとして評価してはならない。error exportではセル先頭の危険文字を無害化し、元のrow number/hashとerrorを保持する。
7. validate完了後もsource hash、mapping version/hash、base snapshot、accepted_pending/rejected件数、金額sumを保存する。metadata write allowlistとdomain table no-write境界はexecution-contract.mdで制限し、変更されたsource/mappingでREADYへ進めない。

### R3. Apply・chunk・restart・idempotency

1. applyはREADYかつ、source SHA-256、mapping version/hash、schema version、承認者、base snapshotがvalidate結果と一致するjobだけを受け付ける。
2. jobはUPLOADED→MAPPED→VALIDATED→READY→APPLYING→COMPLETED/FAILED/ROLLED_BACKの公開状態と、rollback時のROLLBACK_REQUESTED→ROLLING_BACK→ROLLED_BACK/ROLLBACK_FAILEDの内部状態を持ち、許可遷移、terminal性、retry/reopen条件、CAS条件を設計表どおりに検証しなければならない。二重apply、同一source hashで異なるmappingのapply、COMPLETED/ROLLED_BACKからの直接再applyを拒否しなければならない。
3. chunkは有限の計画・進捗単位。target、cross-job registry binding、id-map、business row result、audit/outboxをrow transactionで原子的に確定する。checkpointはrow resultから再構築可能なprojection。
4. 再開はfencing generation、source row hash、registry revision、target version、result fingerprintを検証する。REUSED/DEDUPED attemptを記録してもbusiness result/counter/hashを変更しない。
5. create/update/status change/child replacementを含む全業務操作はcanonical DTOからdomain serviceへ渡す。mapper直insert/update/deleteでvalidation、監査、履歴、関連同期を迂回してはならない。
6. apply中の業務エラーはrow単位でcode/message/correlation idを残し、chunkの再試行可否とjobの最終状態を決定論的に記録しなければならない。

### R4. Rollback・compensation

1. apply前に、entityごとのrollback可否、対象範囲、承認要否、後続参照時のcompensation、禁止操作を表示し、承認なしに変更してはならない。
2. 後続参照があるrowをhard deleteでrollbackしてはならない。既存行・契約・履歴・document link・work record・invoice等はdomain serviceの補償操作または管理者承認へ送る。
3. rollbackは業務audit、row result、id-map、compensation action、失敗理由を保存し、部分rollbackをROLLBACK_FAILEDとして明示する。
4. source file/documentはrollback対象の業務rowと別に扱い、原本・error export・監査証跡を先に削除してはならない。

### R5. Reconciliation・evidence

1. validate/applyのphase別counterを保存する。validateはsource=accepted_pending+rejected（empty_skippedは別加算）、apply終端はaccepted=applied+updated+business_skipped+apply_failed。REUSED/DEDUPEDはbusiness skippedではない。
2. 金額列はphaseごとにJPY件数・合計・excluded理由を集計する。validate時点でacceptedをapply終端bucketへ入れない。終端式はexecution-contract.mdに従う。
3. 空/不正金額は0円と混同せず、amount_excluded件数と理由を分離する。負数は業務上許可される場合とformula文字列を別に扱う。金額のcurrency=JPY、scale、rounding modeはvalidate開始前にmapping承認者が確定し、既定はscale=0・UNNECESSARYで小数をrejectする。別modeはDG-06承認理由とmapping versionへ保存し、apply時に変更しない。
4. 完了時に、source hash、mapping version、mapping hash、schema version、executor、approvedBy、started/finished time、base snapshot、result hashを保存する。
5. result hashはrow resultの安定した並びと正規化された結果項目から算出し、同一入力・同一mapping・同一結果の再検証に使えるようにする。

### R6. Security・file・audit・scope

1. upload/apply/rollback/exportはCSRF、role、dynamic menu、DataScope、OrganizationScopeを適用し、UI非表示だけで認可を代替してはならない。
2. source原本はDocumentService.registerReceivedとDocumentService.linkを使い、quarantine/scan/hash/access log/FileReferenceProviderの境界を再利用する。IMPORT_JOB linkはFileScopeValidationServiceへ登録し、tenant、job、対象entityのscope不一致や未登録targetTypeはfail-closedで403にする。
3. jobの原本・error export・previewには、raw PII、storageKey、secretをアプリケーションログへ出さない。download/preview/rollbackを監査対象に含める。
4. async/schedulerでSecurityContextやrequest scopeを参照せず、executor、tenant、correlation idを明示的に渡す。ThreadLocalはfinallyでclearする。
5. DataScopeのvisible populationはlist/detail/count/options/autocomplete/preview/error export/apply/retry/rollback/batchの全経路で一致させる。

### R7. Engineer CSV互換

1. 既存Engineer CSVのheader、UTF-8 BOM出力、RFC4180のquoted field、行番号付きpartial-successを維持する。
2. 既存 /api/engineers/import-csv の利用者が、Import Centerの導入だけでupsert、status guard、error semanticsを受けないようにする。
3. Import CenterでEngineerを対象にする場合は、legacy-engineer mappingと新規mappingを区別し、既存CSVのgolden fixtureを通過しなければならない。

### R8. Scale・failure・recovery

1. 10,000行をstream/chunkで処理し、全sourceを一括String/list/JSONへ蓄積しない。
2. UTF-8 BOM、Shift_JIS、quoted newline、formula injection、巨大cell、source duplicate、missing referenceをfixture/testする。
3. validate no-write、double apply、same source identity/different mapping hash拒否、mid-chunk crash、restart、rollback compensation、reconciliationを自動testする。
4. failure注入で、chunk境界前後のcommit、checkpoint、id-map、row result、auditを再起動後に再構成できることを確認する。
5. 実MySQLのunique/FK/locking/dialectはmysql tagのTestcontainers gateで確認し、H2だけをMySQL互換の証拠にしない。

## 4. W0 technical increment

adapter別field/state/facade/dependency/side effect/compensation、cross-job identity、row transaction/fencing、counter/hash、synthetic fixture matrixは [execution-contract.md](execution-contract.md) を唯一の実行契約として参照する。migration versionは総括agentが割り当てる。

## 5. 完了判定

実sourceのDG-06未承認でも、技術委任に基づくW0 synthetic契約・schema/golden fixture設計は完了可能。このW0 batchでは業務コード/DDLを変更しない。合成W1はAstra Plan PASS後に可能。実source適合とproduction data applyはOwner/DG-06等の別gateを要する。
