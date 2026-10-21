# NF01 R01b 第1スライス 実行契約案

- 対象HEAD: `6d3b151c15d6a1cd20fff09445c120d4a73fd9b4`（`codex/snf-prod-nf01-lifecycle`）
- 対象: 文書証跡のtenant・ACL・版固定、および`DUAL_CONFIRMATION`の独立二者確認
- 状態: PLAN修正版。以下の「現状」は対象HEADのコード確認結果、「実装契約」は新規実装要件を示す。コード・migration変更は本書の範囲外。
- 非対象: NF09 provider送信/outbox、実Keycloak接続、production有効化。production承認と有効な二者確認は人間が行う。
- migration番号: rootが後続で割り当てる。V185は未割当として扱い、本案では予約しない。

## 1. 実装済みの事実と不足

`LifecycleTaskServiceImpl.completeTask(Long, Long, CompleteLifecycleTaskCommand)`はtransaction内でactorを`requireActor`し、`LifecycleScopeService.requireTenant`を通してtenantを確定し、`LifecycleTaskMapper.selectByIdForUpdateAndTenant`でtaskをロックする。caseとengineerをtenant付きで読み、`LifecycleScopeService.assertCanEditTask`、case状態、task状態、先行taskを検証する。成功時はtask、`LifecycleEvent`、後続task昇格を更新する。caseは通常の`selectByIdAndTenant`であり、complete経路ではcase行をロックしていない。taskは`@Version`を持ち、mapperの`FOR UPDATE`も使う。

同メソッドの`DOCUMENT_LINK`判定は必須taskで`documentId`の有無だけを検査する。任意taskではdocumentなしでも進める。documentが渡された場合は`documentMapper.selectById`で存在だけを確認し、`documentVersionId`、tenant、ACL、version所属、scan状態、hash、storage状態、hold/disposalをこの経路では検証しない。その後`LifecycleEvidenceLink`にtask/document/version ID、時刻、actor、remarksを書き、taskを完了させる。`LifecycleEvidenceLink`にはtenant、generation、revision、hash列がない。

`LifecycleTask`には`evidenceType`（`NONE`, `SELF_DECLARATION`, `DUAL_CONFIRMATION`, `DOCUMENT_LINK`, `SYSTEM_CHECK`）と楽観`version`があるが、`task_generation`もcurrent submission pointerもない。`completeTask`は`DUAL_CONFIRMATION`を特別扱いせず、現行のrole/scopeを満たした単一actorでtaskを完了できる。二段階submission/confirmation APIや永続化表は確認できない。

`LifecycleApiController.completeTask`と`MyLifecycleApiController.completeTask`はSecurityContext由来のuser IDで同じserviceを呼ぶ。service内の`requireTenant`はDBの`sys_user`を再読込してactive/status、role、tenantをSecurityContextのuser情報と比較する。一方、`LifecycleScopeService`のscope判定はrole別に`OrganizationScopeService`, `EngineerSalesService`, `EngineerAccountLinkService`を呼ぶ。現行のrole判定はpermission-group actionを直接評価しない。menu permission（`t_role_menu`）はcase単位のscope認可ではない。

文書側の実装点は`DocumentServiceImpl.assertDocumentAccessAllowed(Document)`、`DocumentVersionMapper.findByTenantAndDocumentId(tenantId, documentId)`、`FileScopeValidationService.assertDownloadAllowed(storedName, expectedDocumentVersionId, expectedHash)`である。後者のexact version/hash引数は現行実装全体で一般文書の版を常に検証する契約ではなく、`CERTIFICATION_EVIDENCE`等の専用経路に結び付いた検証もある。lifecycle facadeから呼ぶだけで一般文書のexact検証を満たすとは扱わず、汎用文書用にID/tenant/document/hash/scan/storageを一貫して検証するservice/query境界を新設または拡張する。`DocumentApiController.download`と`FileApiController`のrouteも独自にfile-scope検査を行う。文書台帳serviceの存在だけをroute横断のACL保証とみなさない。`DocumentVersion`はtenant/document/versionNo/storageKey/sha256/scanStatusを持ち、entityコメント上append-onlyだが、lifecycleから厳密な`id + tenant + document`版取得を行うqueryは現状確認できない。

## 2. 目標契約

新しい提出・確認の業務判断はtransactional serviceに集約する。controllerは認証principalからactorを渡し、bodyで受けたactor、tenant、role、decision、hash、storage keyを認可根拠にしない。提出者と確認者はそれぞれ現時点の`sys_user`行からtenant、status、roleを確定し、両者とも同じ対象case/task/engineerに対する現在scopeを明示評価する。確認者は提出者と別の有効ユーザーで、roleは`管理者`または`HR`に限定する。manager、disabled/deleted user、tenant不一致、scope不一致、認可依存先の不明・timeoutは拒否する。提出時scopeだけで確認を許可せず、確認時に提出者と確認者の双方を再評価する。

権限モデルは現行のrole/menu/data-scopeと`LifecycleScopeService`のrole別規則を再利用し、このsliceで新しいpermission-group action gateは追加しない。menu/APIアクセスは現行SecurityConfigとMenuPermissionFilter、case/task scopeは現行LifecycleScopeServiceの述語に従う。明示actorの評価はactorのDB行を直接渡し、submitterの組織scopeをconfirmation actorのSecurityContextから計算しない。現行`OrganizationScopeService`はSecurityUtils/request cacheに依存するため、submitterを評価するときは同じ既存規則を`tenant_id + user_id + valid date`条件のDB読取で解決する。managerは`t_user_organization`のactive primary assignmentと`m_organization_unit`階層、salesは`t_engineer_sales`の`released_at IS NULL`、engineerは`t_engineer_account_link`、task actorは`t_lifecycle_task.assignee_user_id/assignee_role/is_engineer_visible`を照合する。HR/adminの現行規則はtenant一致後にcase/task全体を許可する。新たなrole/action semanticsは持ち込まない。

## 3. 証跡文書の共通解決

狭いlifecycle evidence facade（新規service案。具体名とAPIは実装時に既存service境界を確認して決定）を提出時・確認時双方で使う。`DOCUMENT_LINK`だけでなく、文書添付を含む`DUAL_CONFIRMATION`、複数添付、将来の文書証跡を含む全evidence typeが同じfacadeを通る。evidence type別の認可迂回を認めない。

clientが選択した`documentId`と`t_document_version.id`は入力として受け取れるが、版番号`versionNo`とは区別する。client hash、storageKey、title、scan statusは根拠にしない。facadeは認証tenantで文書を特定し、現在のdocument ACL/target scopeを検証し、指定versionが同じtenant/documentに属することを検証する。最新版への暗黙fallbackはしない。versionなしの`DOCUMENT_LINK`はmandatory設定に関わらず証跡として拒否する（任意taskで証跡なし完了を認める要件があれば、証跡省略の可否とtype semanticsを別途明文化する）。

提出と確認の双方で、文書が利用可能な状態か、exact `DocumentVersion.scanStatus == CLEAN`か、`DocumentStorage.open(storageKey)`が成功しSHA-256内容が版metadataと一致するか、documentが`DISPOSED`でなくlegal hold/disposal requestで利用不可かを再評価する。Document Archiveでは`t_document_version.scan_status`を唯一のscan authorityとし、`FileSecurityMetadata.storageState/scanStatus`は別の`FileStorageServiceImpl` file familyに限定して判定へ混ぜない。新設/拡張する汎用文書検証境界でexact document version ID/hash、storage参照と実体を検査する。現在の`FileScopeValidationService` overloadは特定file typeの検査を含むため、それを一般evidenceの保証と誤認しない。DBから確定したtenant/document/version ID/versionNo/SHA-256/eligibility epochをcanonical evidenceへ格納する。提出後に最新versionが増えても保存済みevidenceは切り替わらない。確認時にACLが撤回されepochが進んでいれば確認できない。

文書名、storage path/key、別tenant/非scope文書のmetadataをAPI responseやエラーから漏らさない。提出時と確認時の文書詳細/ダウンロードもその時点のACLを再評価する。download controllerの既存認可をfacadeの代用にしない。

## 4. DUAL_CONFIRMATIONの状態・冪等性・不変証跡

`DUAL_CONFIRMATION`の最初の操作はsubmission作成で、taskは`IN_PROGRESS`のままとする。confirmationは異なるactive actorによる別API操作であり、HTTP再送、同一idempotency key再送、画面の自己申告を二人目の確認として扱わない。既存`completeTask`は`DUAL_CONFIRMATION`を拒否し、非DUALの従来動作は互換対象として維持する。通常証跡を含む非DUALの文書証跡も新facadeを経由させる。

routeは既存`/api/lifecycle`と本人向け`/api/my/lifecycle`の公開面を確認したうえで決める。既存controller双方から同じserviceへ委譲するか、片方に集約するかはimplementation reviewで固定する。新API bodyに許すのは楽観version、idempotency key、業務コメントと証跡入力など必要な値に限る。actor/tenant/role/confirmation outcome/hashは入力拒否し、無視して受け付けない。confirmation actorはSecurityContextから取得する。

immutable submissionには少なくともtenant、case、engineer、task、task generation、submission revision、submitter ID/role snapshot/time、expected task version、evidence type、canonical evidence JSON/hash、文書版ID/版番号/hash/eligibility epoch群、idempotency key、server-computed request hashを保存する。confirmationも追記専用行とし、tenant/case/engineer/task/generation/submission/revision/evidence hash/confirmer ID/role snapshot/time/decision/idempotency key/request hashを記録する。拒否・失効理由は既存行の上書きではなくappend-only eventで記録する。

canonical evidence JSONは固定schema、UTF-8、固定key順、null/空値/日時/数値の正規表現を定義してserver生成する。複数添付の順序は明示sort keyまたは正規化したdocument ID順に固定する。同一意味payloadのcanonical hashをSHA-256で計算する。idempotency unique keyは`(tenant_id, task_id, task_generation, operation, actor_user_id, idempotency_key)`。`operation`は少なくともSUBMIT/CONFIRMを区別する。request hashはstableなclient document/version selectionと業務上意味あるbody値（コメント等）から計算し、serverが初回処理で割り当てるsubmission revisionやDB生成IDを含めない。confirmationのsemantic requestは`submission_id + submission_revision + canonical_evidence_sha256`に結合する。同一key/same hash retryはsubmitter/confirmerのcurrent authと全document epochを再確認してから既存結果を返す。same key/different hashは409。old generationのretryは現generationに紐づかないため409。同一key/同一結果のterminal retryであっても双方のcurrent authとdocument epochを再検証し、認可失効後やepoch mismatch後に成功結果を返さない。

## 5. generation/current selectionと永続化

一generationにつき確認対象のcurrent submissionは最大一つとする。新submissionを受理するとき同一transactionでtask/current projectionを直列化し、旧pending submissionの失効event、新revision submission、current pointer更新を一括commitする。lifecycle自身のhold/cancel/reassign等、対象taskの担当・実行可能性を変える遷移はtask generationを増やして古いsubmissionを確認不能にする。resume後は新generationで新規提出を要求する。過去submission/confirmationはimmutable historyとして残す。

文書利用可否はtask generationと独立した`t_document.evidence_eligibility_epoch`（`BIGINT`, monotonic）でfenceする。submissionは各documentのepochをsnapshotに保存する。confirmationはdocument rowを再ロックしepoch一致を要求する。不一致は409/再提出要求とし、旧submissionを別versionへ差し替えない。epochを進める条件はlegal hold開始、`t_document_link`のtarget解除/削除、文書を確認不可にする処分開始、既存versionを利用不可にするscan状態変更である。release/regrant/scan再cleanはepochを戻さず、失効submissionは新規submissionを必要とする。版追加だけではepochを進めない。固定済み旧versionが現時点でもCLEANで利用可能なら、new latest versionが増えても既存submissionを有効なまま確認できる。

現行`LifecycleTask.version`と`LifecycleCase.version`は既存の楽観versionであり、generationとは別の概念である。taskにはgeneration/current pointerがなく、review-current projectionも存在しない。新migration案はtaskのgeneration/current pointerとcurrent review projection、immutable submission、immutable confirmation、append-only eventを追加する。全lifecycle表でtenantを保ち、tenant/task/generation/revisionをDB unique/FKで拘束する。循環FKを避けるためnullable task pointerと作成順を設計する。DBがcanonical JSON/hash内容そのものを正当化するわけではないため、server canonicalizerとtransaction内検証を併用する。

FK/uniqueの実列とMySQL制約可能性をDDL作成時に決める。confirmationとsubmission間はtenant/task/generation/revision/hash一致をDB制約で担保できる形を優先する。current projectionのCAS versionとtaskのoptimistic versionの双方で更新競合を検出する。`evidence_eligibility_epoch`は既存`t_document`行に独立列として置き、document row自身を空リンク/空disposal集合にも効くstable serialization anchorとする。V1や既存migrationは編集せず新migrationとH2/test schemaを追随させる。V185は使わない。

## 6. transaction、lock順、認可撤回の線形化

以下を実装時の依存行と直列化契約とする。これは現在の全writerが満たすという説明ではない。すべて同一DB transactionで取得し、同種の複数actor・複数documentはID昇順にlockする。

| 判定predicate | 実テーブル/行と読取 | 空集合も保護する安定lock anchor | 変更writerと必要な順序 |
|---|---|---|---|
| active actor tenant/role | `sys_user`の`id + tenant_id + status=1 + deleted_flag=0`をsubmitter/confirmerそれぞれ読む。submitterはrequest bodyやSecurityContextの別actorを代用しない | 同じ`sys_user`行をID昇順で`FOR UPDATE` | `SysUserServiceImpl.updateUser`は既に`selectByIdForUpdate`。`updateUserStatus`/`deleteUser`はuser row lockを追加し、他のstatus/tenant/role更新経路も同じuser rowから開始 |
| case/task accessと状態 | `t_lifecycle_case(id,tenant_id,deleted_flag)`、`t_lifecycle_task(id,case_id,deleted_flag)`、`t_engineer(id,tenant_id,deleted_flag)`。task assignment/visibility列も同じtask行から評価 | tenant付きcase row → task rowの順で`FOR UPDATE`; task lockでgeneration/current pointerも保護 | `LifecycleTaskServiceImpl.startTask/completeTask/waiveTask/reassignTask`とcase hold/cancel/complete transitionをcase→taskへ統一。現completeはtask→nonlocking caseなので改修対象。task generation更新もtask lock中に行う |
| manager scope predicate | `t_user_organization(tenant_id,user_id,valid_from,valid_to,primary_flag,deleted_flag)`、target engineerの`t_engineer.organization_id`、必要な`m_organization_unit` ancestors/validityを、明示actor idと業務日で読む | actor `sys_user`行がmembershipの空集合を保護; target `t_engineer`と評価対象org hierarchy rowsをID昇順にlock | `OrganizationServiceImpl.assignUser/transferUser`はuser→membershipの既存前例。`updateUserOrganization/releaseAssignment/closeAssignmentsForUser`とorg `updateOrganization/merge`は同anchor/順序へ合わせる。範囲はLifecycleScopeServiceのmanager判定に必要なmembership/tree変更に限る |
| sales scope predicate | `t_engineer_sales(engineer_id,sales_user_id,released_at,deleted_flag)`で同tenantのengineerに紐づく`released_at IS NULL`行を読む。role/active userは上記sys_user行 | target `t_engineer` rowをassignment集合のanchorとして先にlock。これで現任行ゼロでもassignとの競合を直列化 | `EngineerSalesServiceImpl.assign/setPrimary/release/releaseAllByEngineerId`は現在anchor lockなし。user row(s)→engineer row→sales rowsへ変更し、確認側と揃える |
| engineer本人scope predicate | `t_engineer_account_link(tenant_id,engineer_id,sys_user_id)`を両端tenant付きで読む | user rowとtarget `t_engineer` rowをID/固定順でlockし、空linkの挿入を保護 | `EngineerAccountLinkServiceImpl.link/unlinkByEngineerId`は現在engineer/user rowの共通lockなし。上記user→engineer順に合わせる |
| document ACL link predicate | `t_document_link(tenant_id,document_id,target_type,target_id,deleted_flag)`をdocument単位で全件読む。`DocumentServiceImpl.assertDocumentAccessAllowed`の既存role/DataScope semanticsをexplicit actor引数で再評価する。既存special document type restrictions（例: `PRIVATE_NOTE`の既存`one-on-one.confidential`判定）も維持し、新action gateは追加しない | `t_document(id,tenant_id)` rowを先にlock。これが0件linkと0件disposal requestも保護する | 確認した直接insert入口は`DocumentServiceImpl.linkWithinRegistration`, `DocumentMetadataCommitService.link`, `ExpenseRequestServiceImpl`, `AssetAssignmentServiceImpl`, `AssetLostIncidentServiceImpl`, `ServiceRequestAttachmentCommitService`。追加はgrantのみでepoch不変。現行direct unlinkは確認できない。将来のunlinkはdocument row lock→link更新→epoch++を行う単一serviceに限定し、direct mapper delete/soft-deleteを許さない |
| typed target parent/scope | `t_document_link.target_type/target_id`から解決する親行をこの1箇所で扱う: `ENGINEER→t_engineer`, `CUSTOMER→t_customer`, `CONTRACT→t_contract`, `ACCEPTANCE→t_acceptance`とその契約target, `PROJECT→t_project`, `PROPOSAL→t_proposal`, `SALES_ORDER→t_sales_order`とその`customer_id`, asset linksは`t_asset_assignment`/`t_asset_lost_incident`。他type/未対応親はfail closed | `t_document`→全`DocumentLink`行→target parent rowsの順。複数targetは`(target_type,target_id)`の固定順。一つのresolverだけがこのpolymorphic mappingを保持する | target親を変更する業務writerは自身の親rowをlockしてから変更し、document lockを後取りしない。通常のengineer/org/sales scope writerは上のscope lockで評価を直列化する。必要な親行が存在しない/tenant不一致は拒否 |
| exact version/content | `t_document_version(id,tenant_id,document_id,version_no,sha256,scan_status,storage_key,deleted_flag)`をexact IDで読み、parent documentと一致させる。最新版queryは使わない | `t_document`→exact version rowの順。content identity（id/versionNo/hash/key）は不変 | `DocumentServiceImpl.addVersion`はstorage/scan後に`DocumentMetadataCommitService.commitVersion`でrowをinsertする。document row lock中にversionNo確保とinsert。版追加はepochを進めず旧CLEAN版を有効に保つ |
| eligibility epoch/hold/disposal | `t_document.evidence_eligibility_epoch`, `legal_hold_flag`, `status`, `t_document_disposal_request(document_id,status in PENDING/APPROVED)`を読む。request状態は全件predicateで検査 | `t_document` row lockをparent anchorにし、その後version/link/disposal child rowsを固定順で読む。childなしでもparent lockによりinsertを直列化 | hold開始、disposal PENDING request作成/autoretention request作成、実際のDISPOSED遷移、target unlinkはdocument lock中にepoch++。release/reject/regrantは戻さない。writerはdocument→child request/link順 |
| scan eligibility | Document Archiveの権威は`t_document_version.scan_status`。`DocumentServiceImpl`はscan成功後に`CLEAN`版をinsertし、DocumentStorage版には現行`FileSecurityMetadata` rowが作られない。`FileSecurityMetadata`は別`FileStorageServiceImpl` file familyの権威であり、このdocument archive evidence判定に混ぜない | document→version row | content identity (`id/version_no/storage_key/sha256`)はappend-only。唯一の状態例外としてscan availabilityだけをone-way `CLEAN→REJECTED/UNAVAILABLE` CAS (`id+tenant+document+expected scan_status`)で更新し、同transactionでdocument epoch++。rescanで旧版をCLEANへ戻さず、新scan結果は新versionとして作る。現行更新writerなし。storage open/hash失敗も提出/確認をfail closed |

確認処理はこの順にcase→task/current projection→submitter/confirmer sys_user→明示scope membership/target rows→document rows→exact version rows→disposal/link child rowsをlockする。認可とevidence stateの最終判定は必要な全rowを取り終えた後に行い、その後はlock waitを生じる別readをせず、immutable event/submission/confirmation、current projection CAS、task CASをcommitする。document-only writerはdocument row以下のchildだけをlockし、case/taskへ戻らない。user/scope writerもcase/taskを取得しない。

対象writerは上表に記載したuser status/role、lifecycle state、manager/sales/engineer membership、document link/hold/disposal/version/scanの各入口に限る。全アプリの文書添付consumerやNF09/outboxを一律に移行する計画ではない。なお`DocumentRetentionJob.processExpiredDocuments`は現在candidateを読みPENDING requestを直接insertするため、document row lockとepoch++を追加する。`DocumentServiceImpl.requestDisposal`も同じ規約へ変更する。`approveDisposal/rejectDisposal/executeDisposal`はrequestを先に読む既存順を変え、非lockでdocument IDを解決後にdocument row、request rowの順で再読込/更新する。epochはPENDING request作成時、およびlegacy request経由を含む実際のDISPOSED遷移時に進め、approve/reject/releaseで戻さない。hold開始は既存`Document.version` CASにepoch++を同時加算し、hold解除ではepochを戻さない。storage deletionは`executeDisposal`のafterCommitのままとし、confirmed transactionが先にdocument lockを得た場合はその間storage削除が始まらない。

権限撤回/eligibility changeが該当anchorを先にlockしてcommitすればconfirmationはwaitし、最新DB predicateまたはepoch mismatchで拒否する。confirmationが先に全行をlockしてcommitすれば撤回writerはwaitしてから後続commitする。link/requestの空集合でも同じparent row lockがこの順序を保証する。MySQL 2-session acceptanceで両順序を確認する。別store/IdPはtransactional guaranteeに含めない。二つのconfirm競合では一方だけがconfirmation/current/task terminal更新とterminal eventをcommitし、他方は現行認可・epochを再確認した同一結果または409になる。

## 7. acceptance

H2単体テストは業務分岐用に使い、unique制約、row lock、CAS、transaction rollback、線形化の証明は実MySQL `@Tag("mysql")` + Testcontainersで行う。barrier/Futureはtimeout付きとし、workerはfinallyで停止・回収する。

- 文書: missing version拒否、別document/tenant版拒否、CLEAN以外・非公開storage・disposed/hold/disposal不許可状態・ACL不明拒否、exact hash mismatch拒否、最新version追加後も提出済版固定、提出後のACL撤回で確認拒否、API metadata/path漏えいなし。
- 二者確認: same user、disabled/deleted、tenant移動/不一致、manager、wrong current scopeを拒否し、別active HR/adminで現行scopeを持つ場合のみ成功。現行role/menu/scope guardを維持し、new permission-group action gateを足さない。body偽装actor/role/hash/decisionは入力拒否。
- generation/current: generationあたりpending currentが一件、競合する提出はCAS/uniqueで直列化、新提出時の旧invalidation eventと新current pointerが同一transaction、task担当・実行可能性を変えるreassign/hold/cancel後とresume後に旧submissionは確認不可。document hold/unlink/disposal/scan invalidationはtask generationではなくdocument epochで失効し、document version追加だけでは固定済み旧CLEAN版を失効させない。
- 冪等性/atomicity: unique `(tenant,task,generation,operation,actor,key)`のsame-key/same-hash retryはcurrent authorization/epoch通過後に同じ結果、same-key/different-hashは409、old-generation retryは409、terminal retryはsubmitter/confirmer双方のcurrent authとdocument epoch mismatch時に成功を返さない。挿入/更新の各障害点でsubmission/confirmation/task/projection/eventが部分commitしない。
- 線形化: scope/user/role撤回対confirm、legal hold、target unlink、PENDING disposal insert、scan `CLEAN→unavailable`対confirmはepoch/parent lock先行側に応じ確定する。任意のnew version追加はepochを進めず、固定済み旧CLEAN版を失効させない。release/regrant/scan再cleanはepochを戻さず旧submissionは要再提出。二confirm競合でconfirmation一件、task terminal一回、event一件。
- 互換性: 非DUALの既存task completion、SELF_DECLARATION等通常証跡、event/read path、本人向け/管理向け両APIの認可に回帰なし。MySQL migrationとH2 schemaが一致する。

## 8. 後続NF01→NF09 durable revoke request（本slice外）

後続batchでACTIVE RESIGNATIONからHR/adminが明示開始するtenant-scoped durable request/outboxを設計する。短いDB transactionでcase/engineer/account generationとstable idempotency key、actorを保存し、commit後workerがproviderを呼ぶ。provider I/Oを`LifecycleCaseServiceImpl.completeCase`のtransaction/lock中に実行しない。NF09現行`requestRevokeWithIdempotency`は`NOT_SUPPORTED`でclaim後providerへ同期送信し、claim後・送信前crash時に同key再送では再送しないため、durable dispatcherとsend-stateが別途必要。送信後ACK前crashはprovider idempotency/status-query契約が無ければUNKNOWNのままblockする。本sliceの文書ACL/DUAL完了をNF09統合や退社完了の証明として扱わない。

PLAN受理後に限りimplementation sliceへ進む。production承認、実環境有効化、独立した実在HR/admin利用者による二者確認は人間の責務であり、AI/実装者は代行しない。本書はPLANレビュー結果であり、実装PASS、migration適用、production承認を主張しない。
