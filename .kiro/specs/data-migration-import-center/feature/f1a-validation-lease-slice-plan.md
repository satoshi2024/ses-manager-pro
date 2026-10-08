# NF06 F1a validation lease slice plan

状態: 実装前のAstra review待ち。対象はmetadata writerのlease取得・更新・takeoverとそのMySQL競合証拠に限る。HTTP/UI、業務apply、実source適合、production enablementを含めない。

## 現状と既存境界

V184に6 metadata表とjobの`lease_owner`、`lease_generation`、`lease_expires_at`がある。既存`ImportMetadataService`はsource/mapping/job登録とUPLOADED→MAPPED bind、source/document確認を行い、`ImportMetadataAuthorizationService`は現在principal・active admin・tenant-boundな`import.metadata.manage`の明示ALLOWを解決する。`ImportIdentityCandidateService`は完成済みvalidation runとaccepted rowを読んでcandidate registryへ独立登録し、source/document/version/job/mapping/run/row/registryの境界を再確認する。parser/parser validationは純処理で、job lease、validation run開始、row append、seal/publish serviceはまだない。candidate登録からleaseを流用・迂回しない。

## 第一sliceの操作契約

HTTP endpointは作らず、専用の`ImportValidationLeaseService`内部操作を追加する。

- 第一sliceの`acquire(jobId)`、`renew(grant)`、`takeOver(jobId, expectedGeneration)`はすべてjobが`MAPPED`で、`active_validation_run_id`と`current_validation_run_id`がNULLの場合に限る。`acquire`はowner/expiryがNULLのときgenerationを1増やす。`renew`は有効なgrantのowner/generationが一致し、未失効の場合だけexpiryを30秒後へ延ばし、generationを変えない。`takeOver`は期限切れかつgeneration一致の場合だけserver発行の新workerへ交代し、generationを1増やす。未失効leaseの横取りと他state/pointerの操作を拒否する。

期限はMySQL primaryの`CURRENT_TIMESTAMP(6)`をlock取得後に読み、最終CAS直前にも再取得する。CAS条件にはexpiry・owner・generation・job versionと入力snapshotを含める。lease worker ID、operation identity、grantはserverが生成する。serviceからだけ生成できる非公開constructorの不透明grantへtenant/actor/legal entity/job/source snapshot/worker/operation identity/generationを束縛し、request DTOからworker IDやgrantを復元できるAPIを作らない。heartbeat目安は10秒、leaseは30秒。期限切れownerはtakeover前でもrenew/commit不可。全操作で現在のactor・active admin・明示permissionを再解決し、source ACTIVE/version、job source version、mapping ID/version/hash、source encoding/hash、exact document/version/hash、tenant/legal entity、CLEAN状態とIMPORT_JOB typed linkを一致確認する。file stream、crypto provider、HTTP等のI/OはDB lock中に行わない。

全操作のlock順を`source system → document → document version → IMPORT_JOB typed link（id昇順）→ job → mapping → validation run → validation row（row number昇順）→ registry（digest昇順）`に固定する。対象外のresourceは飛ばすが、順序を入れ替えない。jobのsource IDを得る事前readは非lockingでよい。transactionではsourceを`FOR UPDATE`してACTIVE/versionを確認し、document/version/typed linkを同順に確認してからjobを`FOR UPDATE`する。これを第一sliceと後続candidate/validation lifecycle共通の順序とし、実装時の再判断事項にしない。

同一acquire/takeoverのcommit応答が失われた場合、呼出元は同じserver生成operation identityを持つ不透明な試行contextでのみ再試行する。operation identityはworker IDと要求時のexpected generationへ結び、利用者が指定できない。serviceは現在認可を取得してlock順を進み、jobを`FOR UPDATE`した後に再認可し、grantのactor/tenant/legal entityと一致することを確かめる。最終CAS直前にも認可とDB時刻を再取得する。owner・generation・snapshotが同じ試行に一致しleaseが未失効なら、既commitの同じgrantを返す。leaseを再mintせずgenerationを増やさない。identityが異なる試行、期限切れ、snapshot drift、別owner/generationなら409相当で拒否する。期限後に再取得する場合は新operation identityのtakeoverとする。renew retryは同じworker/generationでexpiryだけを延長するheartbeat再送として扱い、ownerやgenerationは変えない。

この初回sliceは`MAPPED`専用で、run pointerは常にNULLである。後続の`VALIDATING` resume/takeoverは独立APIとし、job lease generation/owner/expiry更新とrunの`last_resumed_lease_generation`更新を同一transactionでCASする。`VALIDATING`中のrenewもrun pointer・run generationを同transactionで照合するrun-aware APIに限定し、第一sliceのjob-only takeover/renewを再利用しない。run付き実workerのstale commit拒否は、将来のchunk/seal/publish同一job row lockに対するMySQL実証が必要であり、今回の単体fence確認だけで完成扱いにしない。

## 次段階への依存と今回の外側

leaseのみのsliceはjob列を更新し、run/row/registryを変更しない。続くvalidation開始transactionが同じlock順とfencing世代で新しい`BUILDING` run、active slot/pointer、job状態を原子的に作る。その後、parserはDB lock外でexact sourceを先頭から読む。最大250件のrow metadataを一transactionで追記し、全row retryはsource row number/hash/fingerprint/disposition/finding codeが全一致する場合だけ冪等に許す。EOFでsource hashとrow集合を照合して`SEALED`へCASし、以後row変更を拒否する。集計はsealed rowとexact sourceからlock外で再計算し、最終短transactionでactor/source/document/mapping/lease/run snapshotとexpiryを再確認してrunを`COMPLETED`、counter/hash、job current pointer、`VALIDATED`を原子的にpublishする。これらrun/chunk/seal/publish操作は第一sliceには実装しない。

## 第一sliceの検証

H2では初回取得、同owner renewでgeneration不変・期限更新、未失効横取り拒否、期限切れ時のみtakeover、旧generation/期限切れowner拒否、MAPPED以外またはrun pointer設定済みのjob拒否、応答喪失後の同一operation retryが同generation/grantを返すこと、異なるoperation retryの拒否、source version/status不一致、document/version/hash/link不一致、actor/permission失効時のwriteゼロを確認する。MySQL `@Tag("mysql")`では独立connection/threadで初回取得競合の勝者1件、renewとtakeoverの競合、takeover前後の旧grant拒否、DB時刻ベースの期限境界、lock取得とsource disableの直列化を実測する。第一sliceはjob lease CAS/fenceだけを確認し、run付きchunk/publishでの旧worker commit拒否は後続MySQL gateに残す。既存6表schemaを利用し、新migrationやschema重複作成は行わない。Astraが本計画をPASSした後にlease serviceとこれらのtestだけを実装する。

## migration証跡

V184のtable `COMMENT`文言だけを日本語化したため、SQL checksumが変わる。未適用の作業branchであることを維持し、統合後はfresh installとV182 B0 upgradeの両方でFlyway validate/migrateを再実行する。V1〜V183は変更しない。
