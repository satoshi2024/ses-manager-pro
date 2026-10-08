# NF06 F1a validation lease slice plan

状態: 実装前のAstra review待ち。対象はmetadata writerのlease取得・更新・takeoverとそのMySQL競合証拠に限る。HTTP/UI、業務apply、実source適合、production enablementを含めない。

## 現状と既存境界

V184に6 metadata表とjobの`lease_owner`、`lease_generation`、`lease_expires_at`がある。既存`ImportMetadataService`はsource/mapping/job登録とUPLOADED→MAPPED bind、source/document確認を行い、`ImportMetadataAuthorizationService`は現在principal・active admin・tenant-boundな`import.metadata.manage`の明示ALLOWを解決する。`ImportIdentityCandidateService`は完成済みvalidation runとaccepted rowを読んでcandidate registryへ独立登録し、source/document/version/job/mapping/run/row/registryの境界を再確認する。parser/parser validationは純処理で、job lease、validation run開始、row append、seal/publish serviceはまだない。candidate登録からleaseを流用・迂回しない。

## 第一sliceの操作契約

HTTP endpointは作らず、専用の`ImportValidationLeaseService`内部操作を追加する。

- `acquire(jobId)`: jobが`MAPPED`、owner/expiryがNULLの場合だけ取得し、DBの現在値を基準にgenerationを1増やす。
- `renew(jobId, workerId, generation)`: owner/generationが一致し、DB現在時刻で未失効の場合だけ期限を30秒後へ延ばす。generationは変えない。
- `takeOver(jobId, expectedGeneration, newWorkerId)`: ownerがあり、期限がDB現在時刻以下で、generationが期待値と一致する場合だけ新ownerへ交代しgenerationを1増やす。未失効leaseの横取りは拒否する。

期限はMySQL primaryの`CURRENT_TIMESTAMP(6)`をlock取得後に読み、SQL条件にもexpiry・owner・generation・job versionを含めてCASする。worker IDはserver側で発行し、caller申告のactor/tenant/ownerを権威にしない。heartbeat目安は10秒、leaseは30秒。期限切れownerはtakeover前でもrenew/commit不可。全操作は現在のactor・active admin・明示permissionを再解決し、source ACTIVE/version、job source version、mapping ID/version/hash、source encoding/hash、exact document/version/hash、tenant/legal entity、CLEAN状態とIMPORT_JOB typed linkを一致確認する。file stream、crypto provider、HTTP等のI/OはDB lock中に行わない。

lock順はsource systemを先頭、jobを後段とし、document/version行をlockする場合はAstra確認のうえschema draftとcandidate実装に合わせて`source system → document → document version → job`に統一する。jobのsource IDを得る事前readは非lockingでよいが、transaction内でsource versionを再照合してからjobを`FOR UPDATE`する。既存decisionの`source → job`順とschema draftのdocument/version挿入位置に差があるため、Astra reviewで固定してから実装する。

## 次段階への依存と今回の外側

leaseのみのsliceはjob列を更新し、run/row/registryを変更しない。続くvalidation開始transactionが同じlock順とfencing世代で新しい`BUILDING` run、active slot/pointer、job状態を原子的に作る。その後、parserはDB lock外でexact sourceを先頭から読む。最大250件のrow metadataを一transactionで追記し、全row retryはsource row number/hash/fingerprint/disposition/finding codeが全一致する場合だけ冪等に許す。EOFでsource hashとrow集合を照合して`SEALED`へCASし、以後row変更を拒否する。集計はsealed rowとexact sourceからlock外で再計算し、最終短transactionでactor/source/document/mapping/lease/run snapshotとexpiryを再確認してrunを`COMPLETED`、counter/hash、job current pointer、`VALIDATED`を原子的にpublishする。これらrun/chunk/seal/publish操作は第一sliceには実装しない。

## 第一sliceの検証

H2では初回取得、同owner renewでgeneration不変・期限更新、未失効横取り拒否、期限切れ時のみtakeover、旧generation/期限切れowner拒否、MAPPED以外のstate拒否、source version/status不一致、document/version/hash/link不一致、actor/permission失効時のwriteゼロを確認する。MySQL `@Tag("mysql")`では独立connection/threadで初回取得競合の勝者1件、renewとtakeoverの競合、takeover前後の旧worker commit拒否、DB時刻ベースの期限境界、lock取得とsource disableの直列化を実測する。既存6表schemaを利用し、新migrationやschema重複作成は行わない。Astraが本計画とlock順をPASSした後にlease serviceとこれらのtestだけを実装する。

## migration証跡

V184のtable `COMMENT`文言だけを日本語化したため、SQL checksumが変わる。未適用の作業branchであることを維持し、統合後はfresh installとV182 B0 upgradeの両方でFlyway validate/migrateを再実行する。V1〜V183は変更しない。
