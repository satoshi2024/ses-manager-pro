# NF06 F1a 永続metadata・identity基盤の実行決定

状態: TECHNICAL_DECISION / ASTRA_REVIEW_PENDING。実source、実移行、production承認ではない。
2026-10-02再fetchのorigin/mainは`1443fb68943849a3260d7ebe005259f3eb609d60`。
F2 foundation `1e8aa651`とexecution-contractの既存Plan PASSを前提とする。
本追加決定を独立Reviewした後、Lunaへ実装を委任する。新DDL番号は実装開始直前に総括が予約する。
V183はNF01使用済み。V184以降の番号を本書だけで予約しない。

## 切出しと完成境界

F1aはsource system、mapping、job、validation run/row、cross-job identity candidateを永続化する。
業務apply、既存targetへの承認binding、READY/APPLYING、compensation、upload/HTTP/UIは次波。
F1aで未実装の操作は拒否し、synthetic試験の成功をF1全体・NF06全体の完成へ読み替えない。

validation metadata writerのleaseを今回実装する。後続apply workerと同じjob行fenceを利用するが、
現在はMAPPED状態のvalidationのみ許す。apply用の業務row/result/outbox原子性は今回の合格範囲外。

## 登録と不変性

- source codeはASCII `[A-Za-z][A-Za-z0-9_-]{0,63}`、lowercaseを保存しtenant内unique。
  source system IDとcodeは作成後変更不可。ACTIVE/DISABLEDとversionのみCAS変更できる。
- JobはUPLOADEDで五元identity `(tenant, source_system_id, entity_type, schema_version, source_sha256)`を占有する。
  source systemの現在ACTIVE/version、同一tenant/legal entity、CLEANな正確なsource document version/hashを確認する。
  file内容や利用者指定名をmetadataへ複製しない。upload自体はA1の別transactionである。
- UPLOADEDではmapping未束縛を許す。初回bindはjob lock＋expected version CASでmapping ID/version/hashを設定しMAPPEDへ遷移。
  一度束縛した内容は変更不可。同じmappingのretryだけ同じ結果を返し、別mappingは409。
  同じsource identityに別jobを作ってmapping競合を回避しない。
- Mappingはtenant/source/entity/schemaのnamespaceでversion unique。canonical mapping bytesとSHA-256を一致確認し、
  作成後のUPDATEは禁止。新versionを追加する。変換式は既存allow-listのみ、任意script/expressionは禁止。
- Validation runと各rowはappend-only。runはjob/source/document/mappingの正確なversion/hash、
  actor、schema version、validation hash、不変counterを束縛する。再validationは新runを作る。
  rowはrun＋source row numberでunique、row hash/canonical fingerprint/finding codeのみ保持しPII payloadを持たない。
- READYへの昇格・approval発行は実装しない。旧runへの承認を新runへ継承する経路も提供しない。

## External identityと暗号境界

NFC・大小文字保持・前後空白/制御文字拒否・UTF-8最大256byteはF2と共通にする。
正規化したbyte列を比較の正本とするが、PIIを含み得るので平文のVARBINARY/VARCHARには保存しない。

- Lookup列はHMAC-SHA-256のBINARY(32)。用途分離したlookup keyとtenant/source/entityのlength-prefix encodingを使用する。
  `(tenant, source_system_id, entity_type, lookup_digest)`をuniqueとする。
- 正確なbyte列は別用途のAES-256-GCM鍵で暗号化し、random nonce・key version・format versionを保存する。
  AADへformat/tenant/source/entity/digestを束縛する。既存digestの再利用時は復号byte列も一定時間比較し、
  一致しなければcollision/corruptionとして停止する。誤った同一視や自動bindingは禁止。
- Lookup key versionはsource namespaceに固定し、暗黙rotationしない。rotation/reindexは後続の別検証手順とする。
  暗号key欠落/不明version/復号失敗はfail-closed。固定production keyやdev key fallbackを作らない。
- synthetic test keyはtest resource/processだけに置き、実credentialを読まない。ログ・例外・DTO toStringに
  source key、plaintext/ciphertext、credentialを出さない。復号した値を通常一覧APIへ公開しない。
- RegistryはCANDIDATEのみ。target ID/version、parent identityとtarget parentの承認bindingはF1bで設計・実装する。
  candidateをAPPLIED/APPROVEDと見なさず、foreign tenant/source/entityの検索再利用を拒否する。

## 主体とpermission

F1aにHTTP/UI入口はない。metadata serviceは呼出ごとに現在security principal、current DB active/deleted/role、
tenantと権威legal entityを確認する。管理者以外、未知principal、tenant不一致、失効/無効ユーザーを拒否する。
利用者入力のtenant/actor/approved booleanを権威にしない。共通SecurityConfigや他featureの認可を変更しない。

既存AuthorizationServiceは管理者を無条件許可するため、それだけでは「明示import権限」を証明しない。
専用resolverは既存tenant-bound permission group/action正本を読み、active groupへの現在割当てと
exact `import.metadata.manage` の明示ALLOWを要求する。`*` / `import.*` / exactのDENYを優先し、
legacy admin bypassやglobal `*` ALLOWだけでは許可しない。production seedで権限を自動付与しない。
resolverの結果は当該操作限りのimmutable internal contextとし、public constructor/booleanで偽造可能なAPIにしない。
source/jobの各write transactionでもactor/source状態とversionを再確認する。

## Lease・transaction

- 時刻の権威は同一MySQL primaryのCURRENT_TIMESTAMP(6)。期限は30秒、heartbeatは10秒目安。
  同owner・同generation・未失効のrenewだけ期限を延長し、generationは変えない。
  初回取得、失効後再取得、takeoverはgenerationを単調増加させる。期限切れownerのcommitはtakeover前でも拒否する。
- Lock順はsource system→job→mapping/validation/registry対象keyの昇順。全取得/renew/takeover/metadata commitで統一する。
  DB transaction途中にprovider/file I/Oや待機を行わない。source document read/crypto準備は必要な再検証と分離する。
- metadata writerはjob行FOR UPDATEをcommitまで保持し、state/owner/generation/expiry/source versionを確認する。
  必要ならcommit直前もDB時刻を再取得し、失効を拒否する。2回の通常SELECTだけでfencingを証明しない。
- validation run、全row metadata、counter/hashとjobのrun参照を同一transactionで確定する。
  100,000行全体を単一長期transactionにしない。詳細chunk/staging状態と最終publishの原子性は実装前Reviewで固定する。
  中間rowを完成runの結果として公開してはならない。
- commit前失敗は全rollback。commit応答喪失は不変run/job identityで照合し、別結果を作らない。

## DDLと実装範囲

候補表: `t_import_source_system`, `t_import_mapping`, `t_import_job`, `t_import_validation_run`,
`t_import_validation_row`, `t_import_identity_registry`。tenant複合FKまたは同等以上のDB制約を付け、
source→job/mapping、job→run、run→rowの跨tenant参照をDBでも拒否する。詳細column/null/check/index表を
実装担当が最初のDDL draftとして提出し、Astra確認前にschemaを完成扱いしない。

許可範囲は`com.ses.importcenter/**`、対応test、専用schema fixture、追加migration、feature spec、
mysql shard inventoryと必要なH2 init参照のみ。既存migration/V1/V182/V183、他domain service、
共通security設定、provider/credential、feature有効化は変更禁止。必要な共通変更はrootへ具体化して戻す。
Document/FileScopeの閲覧を迂回しない読取adapterはimport配下に置く。IMPORT_JOBリンク登録と
FileReferenceProviderの完成はF1全体に必要であり、本metadata切出しだけで完成とはしない。

## Gate・handoff

1. H2: state遷移拒否、actor/tenant/group deny、source disable/version drift、mapping不変/競合、run immutable、
   暗号wrong AAD/key/missing key、NFC/case/byte境界。業務table/sequence/document writeが0であること。
2. MySQL: fresh＋B0 upgrade、unique競合、同identity異mapping、namespace隔離、実job lockの両順序、
   expired owner/takeover/renew競合、途中rollback、snapshot不一致、partial validationが未公開であること。
3. 最終source SHA、全raw log/XML/hash、test countsの単一run集計、schema hash、実container識別子を記録する。
   profile内zero skipped、shard inventory、全branch diff check。H2をMySQL証拠の代わりにしない。
4. 固定HeadでAstra独立Review。P0/P1未解消、必須証拠不足は統合不可。F1b/apply/UI/実移行gateは残す。