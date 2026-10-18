# NF01 R01b 第1切片 実行契約案

- 対象固定HEAD: `e3944774e667a226848d3883e7333ddb8b2ef1f6`（`codex/snf-prod-nf01-lifecycle`）
- 対象: 文書証跡のtenant・ACL・版固定と、`DUAL_CONFIRMATION`の独立二者確認
- 状態: Astra PLANレビュー前の実装契約案。コード・migrationは本文書では変更しない。
- 非対象: NF09 provider送信/outbox、実Keycloak接続、運用承認、production有効化。

## 1. 現状と境界

`LifecycleTaskServiceImpl.completeTask(Long, Long, CompleteLifecycleTaskCommand)`は`@Transactional`でcase/taskをtenant付きで取得し、担当scopeを確認した後に完了・eventを記録する。DOCUMENT_LINKは`documentMapper.selectById(documentId)`による存在確認が中心であり、`documentVersionId`は任意入力である。`LifecycleEvidenceLink`は`taskId/documentId/documentVersionId/verifiedAt/verifiedBy/remarks`を持つが、tenant、確定hash、task generation、submitter-confirmation分離を保持しない。

関連APIは`LifecycleApiController`の`POST /api/lifecycle/tasks/{id}/complete`と、本人向け`MyLifecycleApiController`から同じserviceを呼ぶ経路である。両controllerは認証済みユーザーIDをserviceへ渡す。新しい提出・確認APIもcontrollerに業務判断を置かず、必ず同じtransactional serviceへ委譲する。PWA/ブラウザは表示と操作開始に限り、actor、tenant、role、version ID/hash、confirmation outcomeを自己申告できない。更新系は既存CSRF保護を維持する。

既存`DocumentService`のAPIは`getDocumentDetail(documentId)`, `getVersionStorageKey(documentId, versionNo)`, `download(documentId, versionNo)`等である。版実体`DocumentVersion`のフィールドは`id`, `tenantId`, `documentId`, `versionNo`, `storageKey`, `sha256`, `scanStatus`等。`DocumentVersionMapper`にはtenant + documentで版一覧を得る`findByTenantAndDocumentId(tenantId, documentId)`がある。`FileScopeValidationService.assertDownloadAllowed(storedName, expectedDocumentVersionId, expectedHash)`はexact version/hashを検査できる。`DocumentServiceImpl.assertDocumentAccessAllowed(Document)`は既存ACLの再利用点だが、controllerのdownload routeも含めた完全な認可契約を実装時に確認し、DocumentService単独のdownloadをACL保証とみなさない。

## 2. 文書証跡アクセス契約

`LifecycleEvidenceAccessService`（新規の狭いservice）を追加し、提出時および第二確認時に同一の`resolveAndAssertCurrent(actor, tenantId, documentId, documentVersionId)`を呼ぶ。入力はdocument IDと`t_document_version.id`であり、版番号`versionNo`との混同を禁止する。IDを省略したDOCUMENT_LINKは拒否し、最新版への暗黙fallbackは禁止する。

検証順序は次のとおり。

1. 呼出actorのtenantを認証主体から確定する。要求bodyのtenantやactor IDは受け入れない。
2. `Document`をtenant条件付きでロードし、存在・tenant一致・actorの現行文書ACL/データscopeを検査する。ACL判定不能や依存service不在は拒否する。
3. `DocumentVersion`を`id + tenant_id + document_id`で一意にロードする。削除済み、別tenant、別文書の版は同じnot-found/forbidden境界で拒否する。
4. 現行文書状態が利用可能であること、version `scanStatus == CLEAN`、storage参照が公開済みであることを確認する。`DISPOSED`、quarantine、未scan、reject、legal hold等の利用不可判定は拒否する。holdの扱いは文書台帳の既存規則に合わせ、少なくともhold中・処分済み・scan未完了を採用不可とする。
5. `FileScopeValidationService.assertDownloadAllowed(storageKey, version.id, version.sha256)`を呼び、現在のfile scope、保存実体、scan metadata、exact version/hashを再確認する。
6. server sideで`documentId`, `documentVersionId`, `versionNo`, `sha256`, tenant、検証時刻を確定し、client値で上書きさせない。hashは小文字hex SHA-256を形式検証する。

文書detail/download/confirmationの各要求で現在ACLを再評価し、非表示文書の名称・path・metadataを漏らさない。確認時にアクセス権が撤回済みなら確認不可とし、提出時点のアクセスを恒久的な権限grantとして扱わない。

## 3. DUAL_CONFIRMATION状態とAPI

`DUAL_CONFIRMATION`の初回操作は提出であり、taskを`IN_PROGRESS`のまま保持する。独立確認は別API・別actor・別DB行で行い、初回リクエストの再送、同一idempotency key再送、画面上の自己申告を第二確認として扱わない。

API案（既存`/api/lifecycle` prefixに従う。最終routeはcontroller reviewで固定する）:

- `POST /api/lifecycle/tasks/{taskId}/submissions`: bodyは`expectedTaskVersion`, `idempotencyKey`, completion comment、必要な証跡入力のみ。serverはactor/tenant/current task generationを導出し、文書証跡を解決してsubmission snapshotを作る。
- `POST /api/lifecycle/tasks/{taskId}/submissions/{submissionId}/confirm`: bodyは`expectedTaskVersion`, `expectedSubmissionRevision`, `idempotencyKey`のみ。actorはSecurityContextから取得。bodyの`confirmedBy`, `role`, `tenant`, `hash`, `decision`は無視せず入力自体を拒否する。
- GET detailは提出状態・確定証跡を、閲覧可能範囲のみに返す。別tenant/他scopeの文書名やconfirmation主体を露出しない。

`completeTask`は後方互換のため通常証跡タイプに限り従来経路を維持する。task定義が`DUAL_CONFIRMATION`なら直接completeを拒否し、必ずsubmit→confirmを通す。DOCUMENT_LINKでは新規完了経路を共通証跡resolverへ委譲する。管理UI/PWAは上記APIを呼ぶだけで、確認者を選択・代理入力しない。

## 4. 永続化案とmigration要件

既存`LifecycleEvidenceLink`は旧task記録との互換用とし、新規二段階手続きをそれだけで表現しない。新しいappend-only `t_lifecycle_task_submission`（名称案）に最低限以下を保存する。

- `id`, `tenant_id`, `case_id`, `task_id`, `task_generation`, `submission_revision`
- `submitter_user_id`, `submitter_role_snapshot`, `submitted_at`
- `expected_task_version`, `evidence_type`, canonical `evidence_snapshot`またはそのhash
- 文書の場合: `document_id`, `document_version_id`, `document_version_no`, `document_sha256`
- `idempotency_key`, `submission_status` (`PENDING_CONFIRMATION`, `CONFIRMED`, `INVALIDATED`), invalidation reason/time

一意制約は少なくとも`(tenant_id, task_id, task_generation, submission_revision)`および`(tenant_id, task_id, idempotency_key)`。同一key・同一canonical payloadは既存submissionを返す。key再利用でpayloadが異なる場合は409。

確認主体・時刻をsubmission上書きで表現せず、`t_lifecycle_task_confirmation`（または同等の追記専用confirmation event table）に`tenant_id, submission_id, confirmer_user_id, confirmer_role_snapshot, confirmed_at, decision, idempotency_key, expected_revision`を保存する。`UNIQUE(tenant_id, submission_id)`により一回の有効確認を保証する。拒否や無効化も追記イベントとして残し、訂正は既存記録を更新せず新revisionとする。

case/taskの既存versionに加えてtaskの`generation`を固定する必要がある。再割当、証跡差替、hold/cancel/resumeでgenerationを進め、古いsubmissionをINVALIDATEDにする。resumeで旧generationを復活させない。generationを安全に更新し、submission/confirmationのunique tenant scopeをDBで保証するschema変更が必要なため、新規migrationが必要。migration versionはrootが後続で割り当てること。V185は未割当につき本案では予約・採番しない。新DDLはV1や既存migrationを書き換えず、新migration + H2/test schema追随とする。

## 5. transaction、認可と線形化点

全更新でlock orderを`case → task → current submission → document → document version`に統一する。case/taskはtenant付き`FOR UPDATE` query、submissionもtenant/task/id付き`FOR UPDATE`。文書版はappend-only前提だが、文書状態/処分/holdとの競合を防ぐためdocument rowをtenant条件付きでロックする。各ロック取得後にtenant、case active、task state/version/generation、actor scopeを再検証する。

提出は単一DB transactionでcurrent ACL/版を検証し、immutable submissionと`TASK_SUBMITTED_FOR_CONFIRMATION` eventを一緒にinsertする。DUAL提出ではtask statusを変えない。確認は同一transactionでcase/task/submissionをロックし、`IN_PROGRESS`, active case, expected versions/revision, not-invalidatedを検証する。submitterとconfirmerのuser IDが異なることに加え、confirmerが現時点で有効な同一tenantの管理者またはHRで、同じcase/taskと証跡を現在閲覧・確認可能なscopeを持つことを確認する。managerは対象外。`SysUser`の現行有効状態/role/tenantに加えて、既存`LifecycleScopeService`のcase/task authorizationを使う。submission作成時のscopeのみで確認を許可しない。

権限変化との線形化契約: 認可評価の成功をconfirmation transaction内の判定点とし、その後の確認書込とtask更新を同一transactionでcommitする。user/role/permission変更が同一DB内にある場合はその行/versionを同じtransactionで読み、必要なら共有lock規約に入れる。別store/IdP上の認可をDB transactionで原子的に固定できない場合は全store atomicを主張しない。権限問い合わせのtimeout/不明は拒否。外部IdPの強いrevocation barrierが必要となる本番要件は別依存として記録する。

確認成功時、同じtransaction内でconfirmation row + `TASK_COMPLETED` event + task `COMPLETED`, `completedBy=confirmer`, `completedAt`, comment/evidence snapshotを保存する。楽観version CAS/affected rows=1を必須とする。途中失敗は全件rollback。eventには実在actor IDとrole snapshotを記録し、SYSTEMや初回submitterをconfirmerとして代入しない。二回のconfirm競合では一方だけ成功し、他方はidempotent同一結果または409で、二重terminal eventを生成しない。

hold/cancel/reassign/evidence amendmentも同じcase→task lock orderを用い、revisionを無効化する。確認とholdが競合した場合、DB lockを先に取得した側が線形化順を決め、確認がcommit済みなら後続holdは履歴を消さず別状態遷移として記録、hold/cancelが先ならconfirmは拒否される。DocumentのACL/hold/disposalが別transactionで競合する場合、document row lockとその変更経路の同一lock規約が必要。role/ACLが別storeなら完全同時性は約束せずfail closedで扱う。

## 6. acceptance / 実テスト判定

H2 unit testは分岐を速く検証するだけとし、CAS、unique制約、row lock、並行処理の証明には実MySQL `@Tag("mysql")` + Testcontainersが必須。barrier/Futureはtimeout付き、test workerはfinallyで終了・回収する。

- DOCUMENT_LINK: missing version拒否、version IDが別document/tenantに属する場合拒否、CLEAN以外/処分/hold/quarantine/不明ACL拒否、hash mismatch拒否、latest version追加後も既提出版が変化しない、提出後の現行ACL撤回で確認拒否、detail/downloadのscope漏えいなし。
- DUAL: same-user second step拒否、manager/disabled/wrong-tenant/non-scope actor拒否、正しい別HR/adminのみ成功、client bodyによるactor/role/hash偽装拒否。
- invalidation: reassignment/evidence change/hold/cancelがpending revisionを失効、resume後も古いconfirm不可。提出のsame-key/same-payload再送は同じsubmission、same-key/different-payloadは409。
- concurrency: 2 worker same revisionを同時confirmし、DB上confirmation 1行・task COMPLETE・terminal event 1件、もう一方は確定的duplicate/409。confirm対hold/cancel/reassign競合は定義済みlock順に従う。例外挿入点でsubmission/confirmation/task/eventが部分commitしない。
- compatibility: non-DUALの既存task completion、既存SELF_DECLARATION/通常証跡と旧event/read pathが回帰しない。migration適用後のMySQLとtest schemaを検査する。

PLAN受理後に限り実装sliceへ進み、fresh compile/test reportとmigration reviewを別途提出する。本案自体はテスト実施やCODE PASSを主張しない。

## 7. 後続NF01→NF09 durable revoke request（本slice外）

後続batchで、ACTIVE RESIGNATIONからHR/adminが明示的に開始するtenant-scoped durable request/outboxを設計する。短いDB transactionでcase/engineer/account generationとstable idempotency key、actorを保存し、commit後workerがproviderを呼ぶ。provider I/Oを`LifecycleCaseServiceImpl.completeCase`のtransaction/lock中に実行しない。NF09現行`requestRevokeWithIdempotency`は`NOT_SUPPORTED`でclaim後providerへ同期送信し、claim後・送信前crash時に同key再送では再送しないため、durable dispatcherとsend-stateが別途必要。送信後ACK前crashはprovider idempotency/status-query契約が無ければUNKNOWNのままblockする。実Keycloak/provider、全割当入口とoffboarding fence、実環境での独立確認は後続の外部依存である。本sliceの文書ACL/DUAL完了をNF09統合や退社完了の証明として扱わない。

独立した実際の有効なHR/admin利用者が二者確認を行うことは運用上必須であり、AI/実装者が人間の確認・AstraのPLAN判断・production承認を代行することはない。