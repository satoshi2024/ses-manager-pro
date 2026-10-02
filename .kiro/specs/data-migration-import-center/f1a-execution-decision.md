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
  作成後のUPDATEは禁止。新versionを追加する。F1aのmappingはF2で実装済みのheader→canonical path直接対応のみ。未実装default/transform/lookup式、任意script/expressionは禁止。
- Validation runの入力と各rowは不変。runはjob/source/document/mappingの正確なversion/hash、
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
- validationはBUILDING→SEALED→COMPLETEDの内部状態を持つ。入力は開始時に固定し、各最大250行のchunkを
  job fence付き短transactionでappendする。同run/row番号のretryはhash/全metadata一致だけ許し、不一致は拒否。
  source全量終了後、同job lockで入力件数・prefix連続性を確認してSEALEDへCASし、以後のrow追加/変更を拒否する。
  SEALEDの不変rowから外側のread-only処理でhash/counterを再計算し、最終短transactionでjob fence、run/version、
  source/mapping一致と計算結果を照合してCOMPLETED、counter/hash、job.current_validation_run_id、VALIDATEDを原子的にpublishする。
  BUILDING/SEALEDは進捗としてのみ表示でき、完成したvalidation結果として公開しない。
  crash後は同source/mappingの同runへ新generationで再取得し、確定prefixまたはSEALEDから再開する。
  再validation開始時は旧COMPLETED runを保持したままjobをMAPPEDへCASし、current pointerを解除する。旧runを再編集しない。
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
## 第1回Astra Reviewと条件の解消方針

固定`17d8ebb3f75395139ca3d76d5e6af8c84fcf157e`はPLAN CONDITIONAL（P2×2）。
以下を総括の技術決定として追加する。DDL/実装のPASSはまだ付与しない。

1. Candidate登録は独立したmetadata操作でありvalidateの一部ではない。validateからregistry書込みを呼ばない。
   execution-contract §4のmetadata write allowlistを維持する。競合candidateは完全な復号identityまで照合してから同一と判定する。
2. 金額・raw rowをvalidation metadataへ追加保存しない。SEALEDの集計は、現在のFileScope/ACLとCLEANな固定document versionを
   再確認し、権威あるreaderでexact sourceをEOFまで再parseする。source SHA、parser/schema/mapping version、各source rowの
   row hash/canonical fingerprintを保存済み正本と照合する。DB参照の再validationは行わず、保存済み各rowの
   ACCEPTED/REJECTED dispositionとfinding codeを使い、F2の同じ純amount parserで各fieldのamount/excluded reasonを再計算する。
   dispositionも個人情報を複製しない最小result metadataとして保存する。集計値・EOFをcaller申告から受けない。
3. physical/source/empty数は上記reader/parserが計測する。sourceRowNumberには空行由来のgapがあるため1..N連続を要求しない。
   非空source recordの順序・番号・hash集合が保存済みrow集合と完全一致することをSEALの必要条件にする。
4. crash再開ではexact sourceを先頭から再parseし、確定prefixのrow hash/fingerprint/dispositionを照合してから続行する。
   job内duplicate集合をprefixのnormalized identityから再構築し、境界以降の重複keyを拒否する。
   sourceの一致不能、prefixに不足/余分/異なるrow、暗号鍵不明、document読取不可なら再開しない。
5. chunk、seal、publishは各々current actor/permission/source/document状態を再確認する。権限失効やsource disableを
   凍結versionだけで許可しない。document変化は既存readonly facadeのrevisionを再検証し、未確認はfail-closedとする。
6. DB時刻はjob lock取得後と最後の書込み段階に取り直す。expiry、generation、owner、current run一致を条件付きwriteへ含める。
7. DDL draftはtenant/source/entity/mapping整合、単一active run、current pointer、immutable run inputを制約表に具体化する。
   次のschema Review前に、上記のsource reader・暗号adapter・permission resolverの呼出し位置/transaction表も提出する。

## 2026-10-02 schema draft Review と共有境界の追加決定

feature draft `f773fed7aa3a812c5301f14fbe3f1b2a5e837a7f` はAstra PLAN CONDITIONAL（P1×1、P2×4）。
認可前のstorage open、active run/resume fenceの不足、source version/encoding/result項目の不足、
mapping行サイズと参照整合、job/link初期登録循環を修正してから再Reviewする。まだDDL/実装開始を許可しない。
Astraの「既存document/versionにtenant複合uniqueがない」という説明は誤りとして撤回された。
V1の751/781行およびV102の77〜80行に存在する。残る論点はversionが指定documentに属することの保証である。

総括は利用者から委任された技術判断として、次の最小共有変更を設計範囲に追加する。
これはproduction、Privacy/Legal、実データ移行の承認ではない。

1. FileScopeの専用IMPORT_JOB認可、汎用download経路からの迂回拒否、必要なDocumentService/Link読取・登録境界と
   対応試験を許可する。他featureの認可緩和、共通SecurityConfig変更、管理者の無条件許可は含まない。
   exact認可はstorage openより前に完了する。source/version/hash/tenant/legal entity/現在主体を再照合する。
2. 既存DocumentServiceによる安全なアクセス監査ログは許可する。業務表の読取専用契約とこの安全監査副作用を区別し、
   allowlist試験でも別分類にする。ログを抑止したりstorageへ直接アクセスして規約を回避しない。
3. source system登録とidentity candidate登録は独立metadata操作とする。validationからregistryへ書き込まない。
4. job/link初期化は受控登録transactionとして具体化する。既存Document認可と現在の明示import権限で
   CLEANな正確な既存document versionを確認し、source/documentの必要なlockと状態照合後、job作成と
   DocumentService.linkによるIMPORT_JOB link登録を同じtransactionで確定する。途中失敗は全rollback。
   transaction内のstorage I/Oは禁止。upload/scanはA1の別操作であり、fixtureも登録境界を迂回しない。
   既存link実装のtransaction参加・副作用・lock順をコードで検証し、成立しなければ設計を戻す。
5. 必要最小限の参照整合indexは新V184内で設計できる。既存uniqueの重複追加、過去migrationの編集は禁止。
   document/version/jobのtenant照合順序とcollation互換、単一active run、復旧generationを再Reviewする。

V184は総括台帳でNF06 F1aへ専有予約済み。V183はNF01、V185以降は未割当である。
