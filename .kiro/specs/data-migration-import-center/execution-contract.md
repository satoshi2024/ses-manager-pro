# NF-06 実行契約（SYNTHETIC technical increment）

## 1. 適用範囲と境界

本書はTD-02の技術委任を実装可能な契約へ具体化するW0成果物である。対象adapterはCUSTOMER、ENGINEER、PROJECT、PROPOSAL、CONTRACTの5種。実legacy source、schema適合、data owner、法務判断、実移行承認を表さない。`ses-import-v1`とfixtureは合成データ専用であり、production enablementの証拠に使用しない。旧DG-06の署名者・日時・対象は変更しない。Astra Plan Review PASS後、W1の全synthetic基盤/adapter/testを実装できる。実source/schema/Owner承認はreal data applyとproduction enablementにのみ必要。

CSVはUTF-8 BOM有無またはShift_JISを利用者が明示選択し、headerとencodingを照合する。黙示推定は禁止。XLSXは拒否する。JPYはBigDecimal scale=0、UNNECESSARYとし、小数・丸め・空値の0円化を拒否する。金額なしはnullと理由で表現する。

## 2. adapter実行表

共通規則: 未定義の親・参照・子を自動生成しない。external keyによる既存行bindingは候補と証拠を提示して初回承認を要求する。新規はinsert-only。更新は別mapping version、確定済みbinding revision、読取時target version、別承認者をすべて要求する。子collectionの省略は「変更なし」、明示配列もW0ではunsupportedとして拒否する。skills/SkillTag、users、organizations、positions、history、EngineerSales/assignmentsは全adapterで対象外。adapterは初期状態だけを許し、status変更/遷移methodを呼ばない。ID参照は同一tenantかつ同一legal entityの既存active rowに限る。論理削除、曖昧候補、foreign tenant、version変化は拒否する。

| Adapter / 順序 | 必須canonical field（現行DTO/entityのrequiredを含む） | 初期状態 / 更新 | facadeとmaster参照 | 子・副作用 | 補償境界 |
|---|---|---|---|---|---|
| CUSTOMER 1 | `externalKey`, `companyName`（CustomerSaveDto `@NotBlank`）。任意: companyNameKana, contactPerson/email/phone, address, commercialFlow, trustLevel, deliveryPreference, remarks | 新規は状態列なし。更新は承認binding+versionを必須 | `CustomerImportFacade`を新設し、入力検証、tenant/legalEntity bind、`CustomerService.save`を包む。更新は`updateWithOptimisticLock`。現行generic saveはcreate validation境界が不足するためadapterから直接呼ばない。master参照なし | 子なし。tenantとcurrent legalEntityはpayloadから受けず実行contextから付与 | 全adapterでhard delete/自動void/取消/restore禁止。compensation operation未定義はmanual remediation |
| ENGINEER 2 | `externalKey`, `fullName` (`@NotBlank`); `employmentType`は許容値「正社員/契約社員/BP」。status/expectedUnitPrice/birthDate/experienceYears等は任意だがenum・非負・型検証 | 初期statusは`Bench`固定（V1 t_engineer.status DB default）。明示statusは`Bench`のみ許可し、自動稼働・提案状態は禁止。更新は`updateWithStatusGuard`とversion必須。status変更はW0拒否 | `EngineerImportFacade`から`EngineerService.save` / `updateWithStatusGuard`。tenant/legalEntityはcontext。参照IDのorganization/costCenterは既存scope内だけ | 会計履歴をsave/updateが記録。organization変更によるscope invalidationを維持。skills、resume/photo、login user link、sales assignmentは更新しない | hard delete/自動rollbackなし。補償operation未定義はmanual remediation |
| PROJECT 3 | `externalKey`, `projectName` (`@NotBlank`), `customerExternalKey`解決必須 (`@NotNull customerId`); price range/date rangeがあれば整合検証 | 初期statusは現行`t_project.status` DB default `募集中`。明示statusは`募集中`のみ受理。更新は既存statusを維持しversion照合、status変更は別業務操作のため拒否。DB enumは`募集中/選考中/充足/クローズ`。`Project.status` Entity Patternは異なる値集合を定義しており、既存不整合としてF2の整合修正対象とする。 | `ProjectImportFacade.createWithoutSkills(dto)`は`dto.skills=null`のまま`saveProjectWithSkills`へ渡す（serviceがnullならreplaceを呼ばない）。`updateWithoutSkills(dto)`もskills=nullのtyped DTOで`updateProjectWithSkills`へ渡す（同じnull guard）。skills field自体を含むpayloadはW0で拒否。Customerは先行行のid-mapで解決し同一tenant/legalEntity | project skills/positionsはscope外。serviceはskills配列を渡すとreplaceするため暗黙置換禁止。関連contract/open proposalがあるcustomer移替えは既存service規則で拒否 | 新規/更新を問わずhard delete禁止。自動void/取消なし。補償不可・version競合はmanual remediation |
| PROPOSAL 4 | `externalKey`, engineer key, project key (`@NotNull`); project/customer/engineer整合。position/assignment payloadは対象外として拒否 | 初期状態は`書類選考中`のみ。成約/見送り/面接段階の初期値禁止。status更新/遷移methodは全て拒否 | `ProposalImportFacade`からimport専用typed domain entry pointを呼ぶ（generic `ProposalService.save`を直接呼ばない）。Engineer/Projectは前段で作成済みまたは既存承認binding | 現行`ProposalService.save`はEngineerを「提案中」へ変更するため、Import用typed create facadeでは状態変更副作用を抑止する明示domain entry pointを追加する。提案日時/実行actor記録、重複検証、監査は保持。position/assignment payloadは拒否する。`changeStatus`は呼ばず、Contract draft/history/通知を伴う成約処理を起動しない | Proposal/Engineer status side effectを持たないtyped operationの原子境界で記録を作る。hard delete/自動rollbackなし。補償operation未定義はmanual remediation |
| CONTRACT 5 | `externalKey`, customer/engineer/project keys、startDate、sellingPrice、costPrice（ContractSaveDto required）、日付範囲と三者整合。contractNoはdomain採番に委任し外部keyを代入しない | 新規状態は`準備中`固定（serviceが上書き）。稼働中/終了/解約を初期作成しない。更新は承認binding+version、既存status保持。status遷移methodは全て拒否 | `ContractImportFacade`→`saveWithBusinessRules` / version付き`updateWithBusinessRules`。customer/project/engineerを同一tenant/legal entityで照合 | compliance finding、contract number、engineer status、sales scope invalidation、staffing allocation、compliance audit等の既存side effectを維持。Proposalからの派生は一切なし。position/quotation/order/sales repは明示既存参照以外null/default | 全adapterでhard delete/自動取消/自動restore禁止。参照有無を問わずrollbackは明示domain compensationまたはmanual remediation。 |

F2で各facadeのrequest DTOとdomain validationを確定する。現行serviceが副作用を実行する場合、その副作用はrow transactionに参加するかafter-commit idempotent effectとしてoutbox等で確定し、再試行時に二重発火しないこと。5 adapterのいずれかでこの条件を満たせなければ当該adapterは停止する。

## 3. Cross-job identity・原子性・fencing

### 3.1 登録identity

source systemはtenant所有の登録済み識別子（`source_system_id`, status ACTIVE、version）とする。登録は管理操作、重複case-insensitive code、無効/未登録sourceを拒否する。registry unique keyは`(tenant_id, source_system_id, entity_type, external_key_normalized)`。NFCを`ses-key-v1`として固定し、大小文字を保持する。前後空白はtrimせず拒否し、空・制御文字・byte上限超過も拒否する。DB比較はbinary/case-sensitiveとする。legacy source側の別正規化は別adapter versionで定義し、衝突は拒否する。外部keyの生値は監査/logへ出さず、必要に応じ暗号化またはhash/display maskを用いる。

registry recordはbinding revision、target tenant/entity/id、target version、状態、承認者、承認時刻を持つ。initial bind候補は自動確定しない。別tenant targetは禁止。UPDATE適用直前にtarget versionとrevisionを再照合する。

### 3.2 row transactionとresult

1 source rowのtarget変更、cross-job registry binding/revision、job id-map、business row result、監査イベント/outboxを**同一DB row transaction**でcommitする。mapper直DMLでdomain facadeを迂回しない。transaction参加不能な外部/cache通知は同一transactionのoutboxに書き、commit後に冪等配信する。
2 duplicate registry uniqueでは既存bindingを読取り、同じnormalized identity + 同じtarget version/action/result fingerprintのみREUSEDと判定。別target、別mappingによる異なる意図、revision不一致は409/CONFLICTで停止し、勝手にSKIPしない。
3 apply attemptは別append-only eventとして`APPLIED/UPDATED/REUSED/DEDUPED/REJECTED/FAILED`とfencing tokenを持つ。REUSED/DEDUPEDはattempt件数だけ増やし、business row result/action/counters/amounts/result hashを既存値のまま保つ。
4 leaseは単調増加generation（fencing token）を持つ。takeoverはjob/lease rowをlockし、expected generation/state/expiryを条件にatomic CASでgenerationを更新して排他取得する。各row transactionも同じjob/lease rowを`SELECT ... FOR UPDATE`または同等のDB lock/CASで取得し、generation/owner/stateを検証してlockをcommitまで保持する。takeoverとrow transactionは同じlock順で直列化し、generation変化を挟んだ旧workerのtarget write/commitを拒否する。前後2回の通常SELECTだけではfencingと認めない。
5 checkpointはcommit済みrow resultから再計算可能なprojectionとする。row result/registryが正本。checkpoint遅延・破損時にprefixを再走査し、勝手にbusiness resultをSKIPPED化しない。

source job登録uniqueは`(tenant_id, source_system_id, entity_type, schema_version, source_sha256)`。mapping hashはimmutable job propertyであり、同じunique identityが既にあればmapping hash一致時だけ再開/再利用し、不一致は409で拒否する。mapping違いはsource hashを変えて再uploadしても既存registry bindingとの競合を再評価し、手動承認なしに適用しない。DB制約/lockとCASが唯一の並行制御。

## 4. No-domain-writeとreconciliation

validateは業務table、sequence、domain service writeを行わない。許可されるmetadata writeを次に限定する: `t_import_job`（state/source hash/actor/time）、`t_import_mapping`（immutable mapping/version/hash）、`t_import_validation_run`（run metadata/hash/counters）、`t_import_validation_row`（source row number/hash、canonical fingerprint、finding codeだけ。PII payload禁止）。validate内で変更不可: CUSTOMER/ENGINEER/PROJECT/PROPOSAL/CONTRACT各業務表、domain history/audit、id registry/binding、id-map、checkpoint、document linkage。source documentの登録はupload transactionでありvalidate操作と分離する。no-write検証はこのallowlistとの差分をtable別に評価する。

counter phaseを分離する。sourceはheader以外の非空データ行。validateで確定した`validated_accepted_total`は以後不変。`apply_pending`は`validated_accepted_total`から終端bucketを引く動的projection。`source = validated_accepted_total + rejected`、`physical_rows = source + empty_skipped`。終端では`validated_accepted_total = applied + updated + business_skipped + apply_failed`。非terminal errorはapply_pendingに留め、apply_failedは再試行を終えterminal化したrowだけを数える。REUSED/DEDUPEDは別attempt projectionでbusiness_skippedへ算入しない。skipはmappingで明示された業務上のno-opのみ。

金額集計は金額が存在する行だけに同じ分類を適用し、`source_amount_known = validated_accepted_amount + rejected_amount`、`apply_pending_amount = validated_accepted_amount - applied_amount - updated_amount - business_skipped_amount - apply_failed_amount`、終端で`validated_accepted_amount = applied_amount + updated_amount + business_skipped_amount + apply_failed_amount`を満たす。各bucketの件数とJPY合計、excluded理由を別保持する。stage別にsource/validated_accepted_total/apply_pending/rejected/applied/updated/business_skipped/apply_failed/empty_skipped/amount_excludedのcountとamountを混ぜない。validated_accepted_amountは不変、apply_pending_amountは未終端acceptedの動的sum。amountの空値はunknown/excludedで0としない。固定scale=0/UNNECESSARY。

result hashはaccepted rowの不変business disposition（row hash、registry identity hash、target id/version、INSERT/UPDATE/明示業務skip、適用後version、確定amount、business error code）をsource row順でcanonical encodeしhash化する。attempt log、時刻、worker、REUSED/DEDUPEDは含めない。よって中断なしとcrash/restart後で同一business outcomeならcounter/amount/result hashが一致する。validate hashは別名前空間でvalidated_accepted_total/rejectedを含み、apply終端hashと混同しない。

## 5. Synthetic contractとW1切出し

`src/test/resources/import-center/ses-import-v1/`はgeneratorで作った非実在値のみを収録する。schema manifestはfield、type、required、normalization、reference、state policy、amount rule、versionを定義する。5つの各positive/negative fixtureに加え、同じsource-system/external keyの2 job、同key異target、tenant越境、論理削除参照、relation mismatch、version conflict、side-effect boundary、compensation拒否を表すscenario manifestを置く。CSVは明示UTF-8 BOM版とShift_JIS版を分け、quoted newline、escaped quote、formula-like文字列、負数の識別fixtureも含む。金額例は合成の整数円のみ。real legacy fixtureを含めない。

受入matrix（各5 adapterについてpositive create、mandatory欠損、許可外state、wrong tenant、missing/deleted/ambiguous reference、duplicate external key/cross-job、relation mismatch、update approval/version、side-effect exactly-once、compensation/manual caseを検証）に加え、以下を必須とする。

| 障害条件 | 合格条件 |
|---|---|
| row commit前crash | target/registry/id-map/result/audit全てrollback、再試行で1 business result |
| row commit後checkpoint前crash | registry/resultを再読しattempt=REUSED、business counters/amount/hash不変 |
| checkpoint/counter projection破損/遅延 | 正本row resultからprojection再構築、二重writeなし |
| lease期限後に旧worker継続 | 同じlease rowのlock/CASがtakeoverとrow txnを直列化。generation mismatchで旧workerのwrite/commit拒否 |
| 同じkeyの並行job | DB unique/lockで一つのbindingだけ確定、敗者はcandidate/conflict |
| 同hash・別mapping | 409、既存job/resultを変更しない |
| contract/proposal副作用失敗 | row transaction内side effect rollback、またはoutboxの冪等再送。部分成功を隠さない |
| completed/result hash | 中断なしと再開後でbusiness count/JPY amount/result hashがbyte同一 |

W1はAstra Plan Review PASS後に順に、(1) F2 contract/schema+synthetic fixtures/read-only validators、(2) F1 metadata schema/registry/unique/fence、(3) A1 upload/preview、(4) B1 5 facade apply/crash-recovery、(5) B2 compensation/reconciliation、(6) M MySQL concurrency+10k+browserで分割する。各wave完了後にReviewする。synthetic dataで全機能を実装/検証できる。実sourceのschema・mapping・binding・data owner approvalはproduction data applyの別gateであり、旧DG-06記録の改変/代行はしない。migration番号は総括agentが一括割当。W0では文書のみで、DDL/business implementationを含まない。
