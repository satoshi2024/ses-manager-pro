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

`LifecycleApiController.completeTask`と`MyLifecycleApiController.completeTask`はSecurityContext由来のuser IDで同じserviceを呼ぶ。service内の`requireTenant`はDBの`sys_user`を再読込してactive/status、role、tenantをSecurityContextのuser情報と比較する。一方、`LifecycleScopeService`のscope判定はrole別に`OrganizationScopeService`, `EngineerSalesService`, `EngineerAccountLinkService`を呼ぶ。現行のrole判定はpermission-group actionを直接評価しないため、実装時に新しいaction gateが必要かをpermission設計に照らして確定する。menu permission（`t_role_menu`）はcase単位のscope認可ではない。

文書側の実装点は`DocumentServiceImpl.assertDocumentAccessAllowed(Document)`、`DocumentVersionMapper.findByTenantAndDocumentId(tenantId, documentId)`、`FileScopeValidationService.assertDownloadAllowed(storedName, expectedDocumentVersionId, expectedHash)`である。後者のexact version/hash引数は現行実装全体で一般文書の版を常に検証する契約ではなく、`CERTIFICATION_EVIDENCE`等の専用経路に結び付いた検証もある。lifecycle facadeから呼ぶだけで一般文書のexact検証を満たすとは扱わず、汎用文書用にID/tenant/document/hash/scan/storageを一貫して検証するservice/query境界を新設または拡張する。`DocumentApiController.download`と`FileApiController`のrouteも独自にfile-scope検査を行う。文書台帳serviceの存在だけをroute横断のACL保証とみなさない。`DocumentVersion`はtenant/document/versionNo/storageKey/sha256/scanStatusを持ち、entityコメント上append-onlyだが、lifecycleから厳密な`id + tenant + document`版取得を行うqueryは現状確認できない。

## 2. 目標契約

新しい提出・確認の業務判断はtransactional serviceに集約する。controllerは認証principalからactorを渡し、bodyで受けたactor、tenant、role、decision、hash、storage keyを認可根拠にしない。提出者と確認者はそれぞれ現時点の`sys_user`行からtenant、status、roleを確定し、両者とも同じ対象case/task/engineerに対する現在scopeを明示評価する。確認者は提出者と別の有効ユーザーで、roleは`管理者`または`HR`に限定する。manager、disabled/deleted user、tenant不一致、scope不一致、認可依存先の不明・timeoutは拒否する。提出時scopeだけで確認を許可せず、確認時に提出者と確認者の双方を再評価する。

既存`LifecycleScopeService.assertCanEditTask`は提出者に対する既存業務認可の基準として再利用候補だが、現行実装は組織scope等のcache可能なserviceを読む。confirmationの最終判定をcacheのみで行わない。実装時は認可に必要なDB行を直列化したあと、現行active/role/tenant/scope/actionをDB上の最新値で評価する専用の読み方を定義する。実際に必要な行はコード上のrole/scope構成に合わせて特定し、存在を確認していないpermission-actionやmembership表をlifecycle scopeの現行根拠として記述しない。

## 3. 証跡文書の共通解決

狭いlifecycle evidence facade（新規service案。具体名とAPIは実装時に既存service境界を確認して決定）を提出時・確認時双方で使う。`DOCUMENT_LINK`だけでなく、文書添付を含む`DUAL_CONFIRMATION`、複数添付、将来の文書証跡を含む全evidence typeが同じfacadeを通る。evidence type別の認可迂回を認めない。

clientが選択した`documentId`と`t_document_version.id`は入力として受け取れるが、版番号`versionNo`とは区別する。client hash、storageKey、title、scan statusは根拠にしない。facadeは認証tenantで文書を特定し、現在のdocument ACL/target scopeを検証し、指定versionが同じtenant/documentに属することを検証する。最新版への暗黙fallbackはしない。versionなしの`DOCUMENT_LINK`はmandatory設定に関わらず証跡として拒否する（任意taskで証跡なし完了を認める要件があれば、証跡省略の可否とtype semanticsを別途明文化する）。

提出と確認の双方で、文書が利用可能な状態か、`DocumentVersion.scanStatus == CLEAN`か、file metadataが公開状態か、documentが`DISPOSED`でなくlegal hold/disposal状態が採用を禁じるかを再評価する。`DocumentServiceImpl`の`legalHoldFlag`/status、`DocumentVersion.scanStatus`、`FileSecurityMetadata.storageState/scanStatus`、`DocumentLink`とtyped target、`DocumentDisposalRequest`の実状態に基づき拒否条件を定義する。新設/拡張する汎用文書検証境界でexact document version ID/hash、storage metadataおよび実体を検査する。現在の`FileScopeValidationService` overloadは特定file typeの検査を含むため、それを一般evidenceの保証と誤認しない。DBから確定したtenant/document/version ID/versionNo/SHA-256をcanonical evidenceへ格納する。提出後に最新versionが増えても保存済みevidenceは切り替わらない。確認時にACLが撤回されていれば確認できない。

文書名、storage path/key、別tenant/非scope文書のmetadataをAPI responseやエラーから漏らさない。提出時と確認時の文書詳細/ダウンロードもその時点のACLを再評価する。download controllerの既存認可をfacadeの代用にしない。

## 4. DUAL_CONFIRMATIONの状態・冪等性・不変証跡

`DUAL_CONFIRMATION`の最初の操作はsubmission作成で、taskは`IN_PROGRESS`のままとする。confirmationは異なるactive actorによる別API操作であり、HTTP再送、同一idempotency key再送、画面の自己申告を二人目の確認として扱わない。既存`completeTask`は`DUAL_CONFIRMATION`を拒否し、非DUALの従来動作は互換対象として維持する。通常証跡を含む非DUALの文書証跡も新facadeを経由させる。

routeは既存`/api/lifecycle`と本人向け`/api/my/lifecycle`の公開面を確認したうえで決める。既存controller双方から同じserviceへ委譲するか、片方に集約するかはimplementation reviewで固定する。新API bodyに許すのは楽観version、idempotency key、業務コメントと証跡入力など必要な値に限る。actor/tenant/role/confirmation outcome/hashは入力拒否し、無視して受け付けない。confirmation actorはSecurityContextから取得する。

immutable submissionには少なくともtenant、case、engineer、task、task generation、submission revision、submitter ID/role snapshot/time、expected task version、evidence type、canonical evidence JSON/hash、文書版ID/版番号/hash群、idempotency key、server-computed request hashを保存する。confirmationも追記専用行とし、tenant/case/engineer/task/generation/submission/revision/evidence hash/confirmer ID/role snapshot/time/decision/idempotency key/request hashを記録する。拒否・失効理由は既存行の上書きではなくappend-only eventで記録する。

canonical evidence JSONは固定schema、UTF-8、固定key順、null/空値/日時/数値の正規表現を定義してserver生成する。複数添付の順序は明示sort keyまたは正規化したdocument ID順に固定する。同一意味payloadのcanonical hashをSHA-256で計算し、request hashにはevidence snapshotとコメント等の業務上意味ある入力を含める。同一keyかつ同一request hashのretryは現行認可を通した後に既存結果を返す。同一keyでhashが異なる場合は409。terminal retryもcurrent authorizationとsubmission hash/generationを再検証し、認可失効後に成功結果を返さない。

## 5. generation/current selectionと永続化

一generationにつき確認対象のcurrent submissionは最大一つとする。新submissionを受理するとき同一transactionでtask/current projectionを直列化し、旧pending submissionの失効event、新revision submission、current pointer更新を一括commitする。再割当、証跡差替、hold、cancelなど確認内容を無効にする遷移はgenerationを増やして古いsubmissionを確認不能にする。resume後は新generationで新規提出を要求する。過去submission/confirmationはimmutable historyとして残す。

現行`LifecycleTask.version`と`LifecycleCase.version`は既存の楽観versionであり、generationとは別の概念である。taskにはgeneration/current pointerがなく、review-current projectionも存在しない。新migration案はtaskのgeneration/current pointerとcurrent review projection、immutable submission、immutable confirmation、append-only eventを追加する。全lifecycle表でtenantを保ち、tenant/task/generation/revisionをDB unique/FKで拘束する。循環FKを避けるためnullable task pointerと作成順を設計する。DBがcanonical JSON/hash内容そのものを正当化するわけではないため、server canonicalizerとtransaction内検証を併用する。

FK/uniqueの実列とMySQL制約可能性をDDL作成時に決める。confirmationとsubmission間はtenant/task/generation/revision/hash一致をDB制約で担保できる形を優先する。current projectionのCAS versionとtaskのoptimistic versionの双方で更新競合を検出する。V1や既存migrationは編集せず新migrationとH2/test schemaを追随させる。V185は使わない。

## 6. transaction、lock順、認可撤回の線形化

以下は実装契約上の全体lock orderであり、現行コードが既に満たすとの主張ではない。複数actorや複数添付の同種行はID昇順に取る。

1. `t_lifecycle_case` tenant-scoped row。
2. `t_lifecycle_task` tenant-scoped rowとcurrent review projection。
3. `sys_user`のsubmitter/confirmer rows（ID昇順、tenant条件）。ユーザーrole/status/tenant変更入口もuser rowから始める。
4. 実際にlifecycle判定が参照する現行scope行。コード上は`OrganizationScopeService`の組織関係、`EngineerSalesService`の担当関係、`EngineerAccountLinkService`の本人紐付け、case/engineer/task行が該当する。lifecycleがpermission-group/action gateを採用する場合は、そのmembership/group/action行もここに明示追加する。
5. `t_document`、対象` t_document_version`、`t_document_link`、typed target親行、`t_file_security_metadata`、`t_document_disposal_request`。必要行を識別した後で安定した順にロックし、最終ACL/state/hash/scan検証を行う。
6. immutable submission/confirmation/invalidation event insert、review-current CAS、task status/version CAS、lifecycle event insert。

重要なのは認可・文書の最終判定後に競合可能なlock waitを残さないこと。必要な行を同一順序でロックしてから、actor双方とevidenceをDB最新値で最終authorizeし、直ちにimmutable記録/current projection/task/eventを書いてcommitする。

実装時に互換化するwriter入口はコードで確認した次のものを含む。

- user: `SysUserServiceImpl.updateUser`, `updateUserStatus`, `deleteUser`。現状`updateUser`は`selectByIdForUpdate`を使うがstatus更新は`updateById`、退職・組織閉鎖・session revokeも含め経路単位でlockを確認する。
- scope: `PermissionGroupManagementServiceImpl.replaceAssignments`; `OrganizationServiceImpl.assignUser`, `updateUserOrganization`, `transferUser`, `releaseAssignment`と組織統合・退職時閉鎖経路; `EngineerSalesServiceImpl.assign`, `setPrimary`, `release`; engineer account linkの全更新service。既存`UserOrganizationMapper.selectByUserForUpdate`はuser→membership順の前例だが、全writerの統一保証ではない。
- document: `DocumentServiceImpl.addVersion`と`DocumentMetadataCommitService.commitVersion`; `placeLegalHold`; `requestDisposal`; `approveDisposal`; `rejectDisposal`; `executeDisposal`; `confirm`。加えて`DocumentLinkMapper`経由のlink追加・解除入口を調査する。
- scan/storage: `FileStorageServiceImpl.store`経由のscan metadata更新、`rescan`, `load`, `delete`; `FileSecurityMetadataMapper.updateScanResult`。scan writerはdocument versionの`scanStatus`と`FileSecurityMetadata`のどちらを正とするか現行データモデルを確認し、一つのauthority/CAS契約を定める。
- lifecycle: `LifecycleTaskServiceImpl.startTask`, `completeTask`, `waiveTask`, `reassignTask`, `promoteEligibleSuccessors`と`LifecycleCaseServiceImpl`のstatus遷移。case→task順を全更新経路に適用し、単なるtask lock後にcaseを読む現行順序を必要に応じ改める。

`DocumentServiceImpl.addVersion`は`NOT_SUPPORTED`でstorage/scanを行い、その後`DocumentMetadataCommitService.commitVersion`が`REQUIRES_NEW`でmetadataをcommitする。document row lockをfile I/Oの間保持しない。最終metadata transactionでdocument rowをtenant/version CASし、version insert/link/evidence-visible stateを一括確定する方式を設計する。`executeDisposal`はDBでstatusを変更しstorage deleteをafterCommit callbackで行う。確認側はDISPOSEDを拒否できるが、storage delete失敗とmetadata状態の整合は既存disposal仕様に従って別途確認する。

現行`placeLegalHold`はoptimistic `Document.version` CASを使う。disposal request/approval/executionは現コードで同一全体lock順の`FOR UPDATE`を確認できず、`requestDisposal`もhold check後にrequest insertする。`FileSecurityMetadataMapper.updateScanResult`はID + deleted flag updateでCAS/version predicateがない。これらは受入れ前にtenant/state/version predicateまたはrow lockを追加し、scope撤回・hold/disposal/scan writer先行ならconfirmationはwait後に拒否、confirmation先行ならwriterはconfirmation後にcommitする線形化を作る。

文書writerはdocument lockを得た後にcase/taskへ戻らない。認可/scope writerもcase/task lock後に戻って順序を逆転させない。role/user変更はuser→scope行、lifecycle confirmationはcase→task→actor rows→scope→document rowsの順に合わせる必要がある。全関連writerが同規約になっていない間はACL/hold/disposal/scanとの線形化済みと宣言しない。別store/IdPをこのDB transactionの原子性に含めず、参照失敗は拒否する。cache invalidationはDB撤回の線形化に代えない。

権限撤回が該当DB rowを先にlock/commitすれば、confirmationはwait後に最新権限を読み拒否する。confirmationが先に全関連rowをlockしcommitすれば、撤回はwaitして後続commitする。MySQLの2 session barrier acceptanceで両順序を確認する。二つのconfirm競合では一方だけがconfirmation/current/task terminal更新とterminal eventをcommitし、他方は同一認可済みidempotent結果または409になる。

## 7. acceptance

H2単体テストは業務分岐用に使い、unique制約、row lock、CAS、transaction rollback、線形化の証明は実MySQL `@Tag("mysql")` + Testcontainersで行う。barrier/Futureはtimeout付きとし、workerはfinallyで停止・回収する。

- 文書: missing version拒否、別document/tenant版拒否、CLEAN以外・非公開storage・disposed/hold/disposal不許可状態・ACL不明拒否、exact hash mismatch拒否、最新version追加後も提出済版固定、提出後のACL撤回で確認拒否、API metadata/path漏えいなし。
- 二者確認: same user、disabled/deleted、tenant移動/不一致、manager、wrong scope、permission/actionでdenyされるactorを拒否し、別active HR/adminで正しい現行scopeを持つ場合のみ成功。body偽装actor/role/hash/decisionは入力拒否。
- generation/current: generationあたりpending currentが一件、競合する提出はCAS/uniqueで直列化、新提出時の旧invalidation eventと新current pointerが同一transaction、reassign/evidence change/hold/cancel後とresume後に旧submissionは確認不可。
- 冪等性/atomicity: same-key/same-payloadは同じsubmission結果、same-key/different-hashは409、terminal retryは現行認可失効後に成功を返さない。挿入/更新の各障害点でsubmission/confirmation/task/projection/eventが部分commitしない。
- 線形化: scope/user/role/membership撤回対confirm、legal hold/disposal/new version/scan update対confirm、各々両ロック順で先行側に応じた確定結果。二confirm競合でconfirmation一件、task terminal一回、event一件。
- 互換性: 非DUALの既存task completion、SELF_DECLARATION等通常証跡、event/read path、本人向け/管理向け両APIの認可に回帰なし。MySQL migrationとH2 schemaが一致する。

## 8. 後続NF01→NF09 durable revoke request（本slice外）

後続batchでACTIVE RESIGNATIONからHR/adminが明示開始するtenant-scoped durable request/outboxを設計する。短いDB transactionでcase/engineer/account generationとstable idempotency key、actorを保存し、commit後workerがproviderを呼ぶ。provider I/Oを`LifecycleCaseServiceImpl.completeCase`のtransaction/lock中に実行しない。NF09現行`requestRevokeWithIdempotency`は`NOT_SUPPORTED`でclaim後providerへ同期送信し、claim後・送信前crash時に同key再送では再送しないため、durable dispatcherとsend-stateが別途必要。送信後ACK前crashはprovider idempotency/status-query契約が無ければUNKNOWNのままblockする。本sliceの文書ACL/DUAL完了をNF09統合や退社完了の証明として扱わない。

PLAN受理後に限りimplementation sliceへ進む。production承認、実環境有効化、独立した実在HR/admin利用者による二者確認は人間の責務であり、AI/実装者は代行しない。本書はPLANレビュー結果であり、実装PASS、migration適用、production承認を主張しない。
