# Requirements — 派遣・準委任コンプライアンス台帳

## 前提

- G2は厚生労働省の公式様式を開発baselineとする。公式URL/版/確認日/effective periodを持つprovisional mappingが
  L0と独立Reviewを通過すれば`PROVISIONAL_REVIEWED`として後続開発を開始できる。特定の社内責任者を開発時に
  固定しない。runtimeの社内責任者assignment、対象version/hashへの実actor承認event、freeze済み動的Review policyを満たす実在外部Reviewは
  `ACTIVE`化、M PASS、本番交付のgateとする。派遣元/先管理台帳は派遣終了日起算3年をbaselineとし、legal holdは延長する。
- システムはリスクと不足を提示するが、契約形態の法的適否を自動確定しない。
- R19-P1-01の実装契約は`g2-gate-decision-delta-r19-p1-01.md`を正とする。同文書は
  `PROPOSED_FOR_R10_REVIEW`であり、R10の`ACCEPTED_FOR_IMPLEMENTATION`前はdocs-onlyとする。

## R1. 就業先/責任者/契約条件

1. THE システム SHALL 就業事業所、組織単位、業務内容、就業場所、就業時間、休憩、休日、時間外、指揮命令者、派遣先/元責任者を管理する。
2. THE 派遣契約 SHALL 派遣期間、抵触日、待遇方式、苦情窓口、教育訓練、安全衛生、社会保険通知、派遣料金を持つ。
3. THE 準委任/請負 SHALL 責任分界、成果/役務、作業指示経路、再委託可否、検収方法を持つ。
4. THE 項目 SHALL 契約時snapshotを保持し、マスタ変更で過去帳票を変えない。

## R2. 台帳/帳票

1. THE システム SHALL 派遣元管理台帳、就業条件明示書、派遣先通知書、個別契約書のデータを生成できる。
2. THE 帳票 SHALL document archiveへ保存し、版/交付日/交付方法/受領確認を追跡する。
3. THE 月次就業実績 SHALL 既存勤怠/検収データから派遣先通知用に集計できるが、雇用勤怠と客先工数の差異を表示する。

## R3. リスク/期限

1. THE システム SHALL 既存の直接指揮、多重派遣、契約形態不整合に加え、抵触日、責任者欠落、明示書未交付、保険未確認、期間外稼動を検査する。
2. THE 準委任/請負 SHALL 顧客による個人への直接指示記録、勤怠承認者、作業指示経路から偽装請負リスクを警告する。
3. THE 抵触日/文書期限 SHALL 90/60/30日前通知し、後続契約/組織単位変更を考慮する。
4. THE finding SHALL acknowledged/対応中/解消/例外承認と根拠文書を持つ。

## R4. 権限/個人情報

1. HR/法務/管理者だけが個人別台帳と待遇情報を閲覧し、営業は契約に必要な限定項目だけを見る。
2. export/download SHALL 同じfield permissionとscopeを適用する。

## R5. 受入

- mappingは`DRAFT -> PROVISIONAL_REVIEWED -> ACTIVE -> SUPERSEDED`を持ち、開発baselineと本番有効版を混同しない。
- runtime責任者未指名、承認event未取得、外部専門家Review未取得では`ACTIVE`化および本番帳票交付をfail-closedにする。
- 派遣1件の必要帳票を同一snapshotから再生成し、版差分を説明できる。
- 抵触日30日前、期間外工数、責任者欠落を検知。
- 準委任のdirect command flagだけでなく指示経路/承認者不足を表示。

## R6. G2 scope、assignment、lifecycle、operation idempotency

1. THE mapping SHALL tenant scope、`COMPLIANCE_RESPONSIBLE` assignment SHALL workplace scopeとし、対象contractの
   workplaceをserver-sideで`t_contract_compliance_profile.workplace_id`からだけ解決する。
2. THE assignment SHALL `[effective_from,effective_to)`の半開区間を持ち、同一tenant/workplace/asOfで1件だけ有効とする。
   隣接は許可し、部分/open-ended/concurrent overlapはworkplace anchor lock、overlap SQL、UNIQUEで拒否する。
3. THE mapping approval SHALL asOf時点の有効assignmentへ実際に指名されたuser本人だけが実行でき、管理者bypassを許さない。
4. THE production generate/delivery SHALL target workplaceの現在assignmentとapprovalを毎回再評価し、workplace Aのapproval、
   旧assignment actorのapprovalをworkplace Bまたは交代後の新規deliveryへ流用しない。
5. THE approval/external review/status history SHALL append-only event reducerで管理し、REJECT/REVOKE、再APPROVE、同時刻順序、
   concurrent insertを決定的に解決する。state-changing operationは共通operation ledgerでtenant+operation type+
   idempotency key、request hash、PROCESSING/SUCCEEDED/FAILED、immutable result reference/allow-list result summary、failure retryability、
   永久保持を管理し、commit後response喪失の再送で同じ成功結果を返す。PROCESSING中の同一key再送は、lease有効中は
   `409 IDEMPOTENCY_IN_PROGRESS`、元operation完了後の再送は同じ成功結果を`200`で返す。同key異payloadは
   `IDEMPOTENCY_KEY_REUSED`、retryable=0は`IDEMPOTENCY_RETRY_NOT_ALLOWED`として、同時再送、rollback後再送を決定的に拒否/再開する。
6. THE lifecycle SHALL `DRAFT -> PROVISIONAL_REVIEWED -> ACTIVE -> SUPERSEDED`とし、DRAFT以外のmapping/source/policy編集、
   SUPERSEDEDの再ACTIVE化を禁止する。ACTIVE current rowはexpected version CASで遷移する。
7. THE mapping version SHALL platform既定どおりinclusive effective periodを持つ。current ACTIVEの`effective_to=NULL`と、
   `effective_from`がasOfより後のfuture DRAFT/PROVISIONAL 1件だけは法改定scheduleとして共存できる。future候補同士の重複、
   2件目のfuture候補、effective date前のACTIVE化は禁止する。deployment timezoneが欠落・空・不正ならJVM defaultへfallbackせず
   `GATE_TIMEZONE_UNAVAILABLE`でfail-closedとし、expired/gap periodのgenerate/deliveryも拒否する。PROVISIONALの明示SUPERSEDEDは
   gate hashなしでreason付きeventを保存する。

## R7. 動的external reviewer policy

1. THE 管理者 SHALL tenant単位でreviewer type code/display/description/credential label/required/enabled/sortと、
   mapping versionごとのrequirement group、許容type、minimum distinct reviewer数を設定できる。
2. THE evaluator SHALL group間AND、group内type OR、groupごとのminimum distinct reviewer数で評価し、同一reviewer identityを
   重複countしない。空・破損policy、空group、minimum 1未満、hash/group/type不一致はfail-closedとする。
3. THE policy SHALL mapping versionへfreezeし、type masterのrename/disable後もsnapshot/hash/eventを変更しない。
   disabled typeの新規利用、参照済みtype code変更、物理削除を拒否する。
4. THE reviewer identity SHALL reviewer type、資格/登録識別子、所属組織、reviewer名のcanonical hashで識別し、
   credential原文、storage path/key、内部metadataを不要に公開しない。
5. THE system SHALL reviewer typeをJava enum/static Set、DB CHECK、固定select、`m_system_config` JSON、業務seedで固定しない。
6. THE credential snapshot SHALL 専用AES-256-GCM envelope、random IV、key version、current/old key rotation、prod key必須validation、
   tamper/wrong-key fail-closedを持つ。AADはINSERT前に確定したserver UUIDv4 `operation_id`を使い、AUTO_INCREMENT event IDの
   後付けUPDATEを行わない。credential未入力時はencrypted/key-version/cipher-format/maskedの4項目を全NULL、入力時は全非NULLとし、
   key versionは許容文字列、key設定はpaddingなしbase64url decoded 32 bytesを要求する。MFA/Freee/BP用鍵を流用せず、平文credentialをDB/API/logへ出さない。

## R8. ACTIVE、delivery、preview

1. THE ACTIVE request SHALL approval event IDだけを参照起点とし、tenant/workplace/assignment/actor/mapping version/hash/
   policy hash/event validityをDBから再解決する。少なくとも1 workplaceの実actor approvalはtenant ACTIVE化に必要だが、
   他workplaceのdelivery authorizationを与えない。
2. THE new delivery SHALL mapping version ID/version/hash、review policy hash、gate evaluated at、gate snapshot hashを保存し、
   mapping/policy/review evidenceの変更後に旧idempotency/archiveを新規結果として再利用しない。legacy rowはNULLのまま表示する。
3. THE historical delivery SHALL current mapping/review/assignmentを再評価せず、交付時に保存したimmutable FULL/MASK/LIMITED
   document version、既存profile/worker snapshot ID/hash、resolved workplace ID、render_input_hashからrole別にdownloadできる。
   新しいworkplace/config snapshot tableは作らず、PDF renditionをcontentの唯一の正本とし、current master/configを再renderに使わない。
   document ACL、tenant/data/organization/file scope、scan=CLEAN、access auditは維持する。
4. THE preview SHALL formal generateと別APIとし、archive/delivery/notification/delivery IDを作らず、watermarkと
   非本番content-dispositionを付ける。

## R9. UI/API/security

1. THE `/compliance-gate` page SHALL Mapping、reviewer type、review requirement、workplace assignment、internal approval、
   external review、ACTIVE、event historyを提供し、`compliance-gate.*` action permissionとservice actor条件をserverで強制する。
2. THE page SHALL `canManagePolicy/canManageAssignments/canRecordExternalReview/canActivate/canApprove`をserver計算して返し、
   JavaScript role判定をauthorizationに使わない。全POST/PUTはCSRF対象とする。
3. THE API SHALL allow-list DTOだけを返し、evidence pickerをdocument/version/title/original filename/SHA-256/scan/created atへ限定する。
   clientのdocument/version IDはtenant/file/workplace/org/DataScope、exact hash、CLEANをserverで再検証する。

## R10. migration、history、受入証跡

1. THE G2 follow-up SHALL R10 acceptance後にV102を使用し、V84/V85/V101を変更しない。common V99は永久欠番、
   migration-dev V100はcommonで再利用せず、S12〜S17はV103〜V108とする。
2. THE migration verification SHALL fresh/legacy/partial/failed-history-repair/post-apply rollback、V1/V102/H2/entity/mapper、
   operation ledger、source `BEFORE INSERT/UPDATE/DELETE` freeze trigger、append-only DB拒否、common/dev location採番を検証する。
3. `GATE-T066-HISTORY` SHALL `TRACKED P2 / production release gate`として別specへ分離し、S10 PASS/S12開始を阻害しない。
   未実装を受入済みとせず、対象fieldを必要とするproduction帳票はwrite/asOf/correction/permission/golden完了まで禁止する。
4. T066/S10 PASS SHALL G2 mechanism、実在assignment actor approval、freeze済みpolicyを満たす実在external review、
   実在CLEAN evidence、Phase A/Phase B browser証跡、R10最終Reviewを必要とする。

## Acceptance trace matrix

全caseのfixture/actor/tenant/workplace/asOf/HTTP/DB/rollback/cacheは
`g2-gate-decision-delta-r19-p1-01.md` §13を正とする。

| requirement | direct regression ID | level |
|---|---|---|
| R6.1〜R6.4 assignment/scope | `G2-ASG-01..13`, `G2-DEL-02..04` | L2〜L3 |
| R6.5 event reducer/operation idempotency | `G2-EVT-01..11`, `G2-IDP-01..13`, `G2-MIG-07` | L2〜L3 |
| R6.6 lifecycle/effective period/ACTIVE | `G2-ACT-01..06`, `G2-LIFE-01..09` | L2〜L3 |
| R7.1〜R7.3 dynamic policy/freeze | `G2-POL-01..16` | L0〜L2 |
| R7.4/R9.3 PII/evidence/credential crypto | `G2-EVT-12..14`, `G2-SEC-09..10`, `G2-SEC-12..18` | L1〜L2 |
| R8.1〜R8.4 delivery/preview/immutable rendition | `G2-DEL-01..15` | L1〜L2 |
| R9.1〜R9.3 role/CSRF/DTO/i18n | `G2-SEC-01..11` | L0〜L2 |
| R10.1〜R10.2 migration/source freeze | `G2-MIG-01..12` | L0〜L2 |
| R10.3 history gate | `G2-HISTORY-01` inventory + production catalog L0 | L0 |
| R10.4 browser/real evidence | `G2-BROWSER-01` Phase A/B | L3 Demo |
