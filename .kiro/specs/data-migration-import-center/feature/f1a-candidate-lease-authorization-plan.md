# NF06 candidate・lease現在権限gate修正計画

状態: Astraによる計画レビュー待ち。実装・MySQL実行・production承認は未完了。本書はcandidate registry登録と第一slice validation leaseの権限線形化だけを対象とし、NF06全体や業務applyの完了を意味しない。

## 目的と修正範囲

`ImportIdentityCandidateService.registerCandidate`と`ImportValidationLeaseService`の成功・冪等returnを、同じトランザクション内の現在権限gateで撤権と直列化する。初回の`requireCurrentAuthorization()`は入力検証と早期拒否に使えるが、通常SELECTだけではcommit前の権限を固定しない。成功可能な全分岐では、resource lock待ちの後、最後の成功returnまたはwriteより前に`requireCurrentAuthorizationForWrite(expectedContext)`を呼び、そのactor・tenant・legal entity・明示ALLOW/DENY判定の戻り値だけで処理を続ける。gateの行ロックをtransaction commitまで保持する。

candidateは現行順序`source → document → document version → IMPORT_JOB link → job → mapping → validation run → validation row → registry`を変えない。registryのlocking readとsnapshot/collision検証後、既存candidateを返す分岐と新規insert分岐の双方でwrite gateを取得する。gateを通過した戻り値をtenant/actorのwrite contextとして使い、既存candidateのidempotent returnもgate後に限る。競合insert/duplicate retryは現行の独立transaction契約を保ち、retryごとに現在権限を再確認する。source reader、Document stream、crypto/key providerなどのI/Oは引き続きtransaction/resource lock外とし、暗号化済み値と検証済みsource rowを渡してから短いwrite transactionを開始する。

leaseは現行順序`source → document → document version → IMPORT_JOB link → job → mapping`を維持する。resource snapshot検証後かつACQUIRE/TAKEOVERの同operation・同worker retryによる成功grant returnより前にwrite gateを取得する。冪等returnとCAS更新の双方が同一gateを通り、grant returnまたはlease CASからcommitまで権限行ロックを保持する。現行のgate前にある通常再確認は早期拒否用途に限り、線形化の根拠にはしない。認可変更が先にcommitした場合は403かつlease状態不変、gateが先の場合は権限変更がmetadata transaction後にcommitする。状態/snapshot/CAS競合は既存409契約を維持する。lease transaction中にsource stream等の外部I/Oを追加しない。

leaseの各成功候補は、最後に待つ可能性がある認可gateの取得後にMySQL primaryの`CURRENT_TIMESTAMP(6)`を新たに読み、その値で期限を再判定する。これはACQUIRE/TAKEOVERの同operation retry grant return、通常ACQUIRE/TAKEOVER CAS、RENEWの期限検査とCASすべてに適用する。gate前の`dbNow`をgate wait後に流用しない。既存UPDATEの`lease_expires_at <= CURRENT_TIMESTAMP(6)` / `> CURRENT_TIMESTAMP(6)`等のDB条件も維持し、application clockへ置換しない。

`beginAcquire`、`beginTakeover`、candidateの事前snapshot/read/encryptだけでは書込み許可を確定しない。最終gate後の失敗は既存transaction rollbackに任せ、SQL timeout/deadlock/lock wait timeoutを403や期待拒否として数えない。bindMappingの`job == null`確認前に`job.sourceId()`を参照する防御不足は別の小修正候補として切り分け、P1線形化やその合格証拠へ混ぜない。

## READ_COMMITTEDとFK parent lockの根拠

candidateとleaseの`TransactionTemplate`は`READ_COMMITTED`である。したがって、tenant範囲locking queryのpredicate/gap lockがphantomを防ぐとは仮定しない。V63の実FK定義を根拠に、現行gateが取得する親行のrecord lockと子insert時のFK検査lockを、実MySQLで個別に実証する。V63は` t_user_permission_group.user_id → sys_user.id`、同membershipの`group_id → m_permission_group.id`、および`t_permission_group_action.group_id → m_permission_group.id`を定義する。tenant列はこれらFKに含まれないため、FKの存在だけでtenant認可しない。gateはロック後のmembership/group/action戻り行についてtenant ID、actor ID、group ID、enabled/deleted stateを照合し、現在tenantに属するactive membership経由のexact `import.metadata.manage` ALLOWと`*`/`import.*`/exact DENYだけから判定する。

gate-held試験はtest-only `@SpyBean` wrapperを第一候補とする。`requireCurrentAuthorizationForWrite`の呼出しで実メソッドを`callRealMethod()`し、権限判定とFOR UPDATEが成功してtransactionへ戻る直前に限定threadのlatchで停止する。認可結果をstub/mockせず、停止中も実serviceのtransaction/connection/lockを保持する。許可後のservice writeをreleaseして実commitさせ、待機中permission edit/insertがその後commitしたことを確認する。spyが実メソッドまたは対象beanを正しく包めない環境なら、この方法をPASS扱いへ緩めず、同等のtest-only同期方法を計画レビューへ戻す。

## 実MySQL競合試験

既存の`ImportIdentityCandidateMySqlTest`と`ImportValidationLeaseMySqlTest`はauthorizationおよび/またはDocumentServiceをmockし、独自DriverManager schemaを作る。これらは純粋なservice/transaction回帰には残すが、実ACL/Document/FileScope/tenant統合証明には使わない。専用`@Tag("mysql")` Spring integration fixtureを用い、実`ImportMetadataAuthorizationService`、candidate/lease service、DocumentService、FileScope、tenant resolver、real `LoginUser` principal、実database user/group/membership/actionを通す。source document/versionはtest内で合成CLEAN行として手動seedし、tenant/legal entity、retention/hold、IMPORT_JOB typed link、合成storage bytesを整合させる。このfixtureはDocument upload/scan処理そのものを証明しない。cryptoは実`ImportIdentityCryptoService`とtest-only合成keyを使い、source readerも実経路を通す。既存mock fixtureは流用しない。

すべてのbarrier、`Future.get`、lock-wait pollingに短い上限を置き、finallyでbarrier release、rollback/connection close、future回収を行う。future中の例外は伝播させる。実gate内barrierでは、実`requireCurrentAuthorizationForWrite`の結果を返す直前、同じservice thread・transaction connection上で`CONNECTION_ID()`と`@@transaction_isolation`を記録し、`READ-COMMITTED`およびactive transactionをassertする。外側test transactionのisolation表示で代用しない。`data_lock_waits`は指定されたrequester→gate/blocker connection edgeだけを対象に照合し、table/index/lock type/mode/statusおよびキーまたは特定parent idを厳密に確認する。任意のSQL例外やtimeoutは拒否成功ではない。

1. **撤権先行・candidate registry wait:** 実`registerCandidate`の冪等既存candidate経路を使い、別connectionで同じregistry PRIMARY/unique rowを保持してserviceが正確に待つことを確認する。待機中に別connectionでactor inactive化または実grant撤回をcommitし、blocker解放後にserviceが403になることを確認する。既存registry ciphertext/digest/provenance、validation row/run、job/source/mapping/version/linkは変更されず、candidateの新規書込みもないことを照合する。必要なlock waitは事前確認し、source readerのsynthetic read自体はservice transaction外に置く。
2. **撤権先行・lease job wait:** `beginAcquire`等のserver発行operation/grantを作成した後、別connectionでjob PRIMARYを保持し、実acquire/renew/takeoverを正確に待たせる。待機中にactor inactive化またはexact grant撤回をcommitし、blocker解放後に403、lease owner/generation/expiry/job version不変を確認する。必要ならtakeover/renew用の期限・同operation retry状態を個別に作る。DB timeout/deadlockは失敗扱い。
3. **gate先行・実service transaction保持:** candidate登録とlease更新で各々、上記spy barrierにより実write gate取得後、実service transactionをcommit直前まで保持する。candidate側では既存ALLOW groupへの新しい`import.*` DENY action insertを別connectionから行い、gateが保持する該当`m_permission_group`親PRIMARY X lockに対するFK child insert waitを確認する。lease側ではあらかじめDENY actionを持つ別groupを作ってmembershipを付けずにおき、actorへの`t_user_permission_group` insertを別connectionから行い、gateが保持する`sys_user`親PRIMARY X lockに対するFK child insert waitを確認する（許可候補のtenant一致を検証する）。両ケースで正確なrequester→service connection edge、child requestとparent blockerの表/index/type/mode/status、対象parent keyを確認する。release後は実candidate registry insertまたはlease CASが先にcommitし、待機insertがcommit、その後の次回service callが新denyにより403となることをassertする。これで成功線形化点を実service transactionと結び、直接helperだけの試験で代用しない。
4. **rollback/成功証拠:** revoke先行ケースはdomain row/version/pointer/registry/lease/storage状態がゼロ変更であることを確認する。gate先行ケースは許可されたcandidate insertまたはlease CASが一度commitし、後続permission mutationが次requestに反映されることを確認する。test providerのstream open count、CLEAN/document hash、tenant/legal entity/link、candidate digest/provenance、lease owner/generation/expiry/versionも照合する。

5. **lease expiry境界:** ACQUIRE/TAKEOVERの同operation冪等grant、ACQUIRE/TAKEOVER CAS、RENEWをそれぞれ含める。テストはDB expiryをgate waitより短い残時間へ設定し、grant/action親行を別connectionで保持して実gate queryをwaitさせる。許可状態を維持したまま期限を越えてblockerを解放し、fresh DB時刻によって409となること、403ではないこと、既存期限切れgrantを返さないこと、RENEW/CASで期限を延長しないこと、owner/generation/expiry/job versionが不変であることをassertする。期限を越えても成功する旧`dbNow`再利用は失敗とする。すべての時間待ちは有界にし、lock timeout/deadlockは期待409に置き換えない。
6. **source/document/actorの双方向変更順:** 各resourceで「mutation先行」と「service gate/resource lock先行」を最低一つずつ実証する。mutation先行側は独立connectionがsource status/version、document hold/deletedまたはversion scan/deleted、actor status/deletedのいずれかを変更してcommitし、待機中の実candidate/lease serviceが現行契約の409（source/snapshot/state drift）または403（actor/FileScope拒否）となり、candidate/lease/job/link/registryのwriteとversion変化がないことを確認する。service先行側は実serviceのresource row lockとwrite gateを取得後にspy barrierで止め、同じ行への独立mutationがそのservice connectionをblockerとして実waitすることを確認する。barrier release後service writeがcommitし、その後mutationがcommitする。次回service callが更新後のsource driftなら409、actor失効またはFileScope拒否なら403となること、二回目のwriteがないことを照合する。scopeは合成tenant内のsource、document/version、actorだけで、legal entity組織全体の同時変更線形化は主張しない。

## まだ証明しない範囲

このsliceはcandidate登録と第一sliceのMAPPED job leaseだけを扱う。VALIDATING run resume/takeover、chunk append、seal/publish、旧workerのpublish fencing、業務domain apply、HTTP/UI、real provider/key、production enablement、cross-organization legal entityの同時変更線形化は証明しない。新規permission group rowそのものに対するtenant predicate gap lockも主張しない。現行gateがtenant内全group/action行をロックする広い範囲はP2性能・競合リスクであり、実測/範囲縮小を別項目として残す。V184や他migration/schemaは変更しない。

実装後に必要なtest commitと実MySQL serial runが完了するまで、本計画だけを根拠にNF06認可競合をPASSにしない。H2や既存mock-based MySQL testはREAD_COMMITTED phantom/lock順/Document ACLの証拠ではない。
