# NF06 F1a 実metadata・ACL検証slice plan

状態: 実装前のAstra review待ち。これは次の最小実metadata sliceの計画であり、F1a/F1/NF06全体の完了やproduction有効化を意味しない。今回のコード範囲は別途の`disableSource` not-found fail-closed修正だけとする。

## 目的と境界

既存のF1a metadata経路が、実際の認可resolver・`ImportMetadataService`・`DocumentServiceImpl`・`FileScopeValidationService`・DB上のCLEAN document/version/typed linkを通して、exact sourceを許可時だけ開くことを確認する。document storageのみtest providerを使い、service/mapper/ACL/transactionはmockで置き換えない。source bytesとkeyは合成fixtureに限定する。新migration、HTTP/UI、業務apply、real source/provider/credential、production設定変更は含めない。

既存の`ImportIdentityCandidateMySqlTest`はmetadata serviceとdocument serviceをmockしているため、このsliceの認可・storage・transaction証拠として流用しない。既存のV184 schemaとapplication-test DB bootstrapを使い、必要なseedだけをtest内で作成・cleanupする。

## 最小の成功経路

1. 実mapperで既存の合成CLEAN document/version fixtureをDBへseedし、合成CSV bytesをtest-only storage providerへ登録する。tenant/legal entity、retention、hold、FileScopeのdocument種別・scope情報と、source grantも実DBへseedする。この成功経路はupload、scan、DocumentServiceによるdocument作成・登録、`IMPORT_SOURCE`種別の登録検証を証明しない。
2. `SecurityContextHolder`に実`LoginUser` principalを設定し、DB active user、tenant、`LegalEntityContextService`、permission group/actionから`ImportMetadataAuthorizationService`がexact `import.metadata.manage` ALLOWを解決することを確認する。管理者ロールだけでは認可されない。
3. 実`ImportMetadataService`でsource作成、mapping登録、CLEAN documentに対するjob登録、`IMPORT_JOB` typed link作成、mapping bindを行い、`openSource(jobId)`が同一document/version/hashのbytesを返すことを確認する。streamを閉じ、テスト終了時にDB/storage stateを消す。
4. success側でもstorage open count、DB上のversion/hash、CLEAN状態、legal hold/retention、typed link、source status/version、mapping ID/version/hash、job identityをassertする。`openSource`後にDocumentServiceのDOWNLOAD監査行がdocument ID・version ID・認証user ID・action=`DOWNLOAD`へ一致することを確認する。job拒否auditが現在存在するとは仮定せず、拒否auditは後続sliceの対象とする。source row/PII/credentialはログへ出さない。

## ACL拒否マトリクス

全拒否ケースは実principalと実resolverを通し、`BusinessException`の403/404/409相当を確認する。対象tenant外の存在有無を漏らさないIDは404で統一する。source streamを開く操作では、拒否前storage open countが0であることを必須にする。

| ケース | 期待 |
|---|---|
| exact `import.metadata.manage` ALLOW | 成功経路のみ許可 |
| `*`のみ、`import.*`のみ、exact ALLOWなし | 403、storage open 0 |
| exact DENY併存、`*` DENY、`import.*` DENY | DENY優先で403、storage open 0 |
| non-admin、inactive/deleted user、unknown principal、未認証 | 403、storage open 0 |
| 別tenant source/job/document/version、legal entity不一致 | 404または403、storage open 0、相手tenant行に変更なし |
| source DISABLED、source version drift | 409、storage open 0 |
| document/version/hash drift | 現行operationのfail-closed契約に従い404/409/403、storage open 0 |
| scanがCLEANでない、deleted document/version | DocumentService/FileScopeの現行拒否契約に従い403/404/409、storage open 0 |
| hold中またはretention不整合、FileScope拒否 | 403/409、storage open 0 |
| `IMPORT_JOB` typed link欠落・別job・別tenant・generic linkのみ | 403/409、storage open 0 |

`authorizeImportSourceRegistration`と`authorizeImportJobDownload`の前提を個別に確認し、generic `DocumentService`経路や別featureの権限をimport許可の代用にしない。拒否はstorage provider呼出しより前で起きることをcounterで示す。

## MySQL transaction・競合証拠

新設する専用`@Tag("mysql")` testを既存の正確に一つのmysql shardへ登録する。複数connection/thread、barrier、bounded `Future.get`、最外`finally`でのrollback/close/shutdown/awaitを使い、拒否を成功競合数に数えない。test provider以外のMySQL containerは使わない。

- `t_document_link` insertを故意に失敗させ、job insertもrollbackされることを確認する。commit前に別connectionからjob/linkが不可視であること、commit後に両方可視であることを確認する。
- 同一tenant/source/entity/schema/source hashの同時job登録を両投入順で実行し、勝者は1 jobと1 exact linkを作り、同一snapshotのretryだけ同じjobへ収束する。異なるdocument/version/hashのretryは409で拒否し、二つ目のjob/linkを残さない。
- mapping作成はimmutable versionの追加操作であり、異なるcanonical bytesは別mapping versionとして作成可能である。競合する作成ではversion番号を一意に採番する。job mapping bindは`UPLOADED` jobのversion CASで一つのmappingだけを確定し、同じmappingへのretryだけ収束する。別mapping bindは409で拒否し、jobは先に勝ったmappingを維持する。これはmapping作成競合とbind競合を混同しない。
- sourceのversion変更とdisableをsource row lockに対して両順序で競合させ、登録側は現source状態を再確認して拒否する。異なるsource/mapping snapshotは既存operationの契約に沿って409、404または403となり、二つ目のjob/linkを残さない。
- lock待ち中にactorのactive/deleted状態またはexact permission grantを別connectionから撤回する。待機後に許可が残っていない場合、操作が改めて認可を解決して403となり、job/link/storage openが0であることを確認する。leaseや`ImportIdentityCandidateService`の既存mock/barrierはこの試験の代用にしない。
- stale source/job/mapping/document snapshotは409、拒否時のjob/link/mapping状態は変更なしとする。実測するconnection ID、対象PRIMARY lock、commit境界、storage open数、DB hash/CLEAN/hold/retention/typed-linkをtest assertionまたは安全なtest evidenceに残す。credential、URI、source row内容は出力しない。

## 実装分割・合格条件

最初の実装commitは専用MySQL test harnessと成功経路（実metadata・document・FileScope・exact ACL・test storage provider）までに限定し、Astra reviewを待つ。許可後の後続小commitで拒否matrix、rollback/visibility、同一identity競合、snapshot drift、lock待ち中のgrant失効を加える。各段階でshard inventoryは一度だけ登録し、tagged testにskipを追加しない。

H2は`disableSource`のmissing/foreign tenant fail-closedや小さな純validationにのみ使い、MySQL transaction/lock/visibilityの証拠にしない。rootがserial実行するMySQL gateがzero skipで通るまでintegration結果は未検証と扱う。実sourceの適合、業務apply、承認、production enablementは後続の独立decision gateとする。
