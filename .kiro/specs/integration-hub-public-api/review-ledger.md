# NF-05 Review Ledger（scope expansion承認・F2 PASS・A1独立再Review待ち）

## Approval gate

| 項目 | 現在値 | 判定 |
|---|---|---|
| NF-05 status | APPROVED | OWNER_APPROVED |
| DG-05 | DG-05-F1-APPROVAL-20260830-01（2026-08-30） | APPROVED |
| approved resources/commands | GET-only 11 paths、inventory allow-list、command/exportなし | APPROVED |
| Owner | PROJECT_OWNER（OwnerType=ROLE） | APPROVED |
| Base branch | origin/main | APPROVED |
| Base commit | b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd | APPROVED |
| threat model | 承認済み11項目 | APPROVED |
| auth method | HMAC-SHA256 signed service account、OAuth fallbackなし | APPROVED |
| contract SLA | 月間99.9%、p95 500ms、保守7日前、重大障害60分以内、v1廃止予告180日 | APPROVED |
| public field inventory | inventory allow-listのみ、internal entity serialize禁止 | APPROVED |

## Scope expansion gate

| Wave | Decision status | Review/実装状態 |
|---|---|---|
| F1 | APPROVED | PLAN PASS / IMPLEMENTATION PASS。fixed reviewed Head 7e50bf1360ea8d7271acc0667593635451300268 |
| F2 | IMPLEMENTATION_PASS | fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`、P0/P1/P2=0/0/0 |
| A1 | REMEDIATED_REVIEW_PENDING | 初回FAILを`874fface`系列で修正したが、再Review fixed Head `cddd4850`でP1=1/P2=2が残り、NF05-IMPL-A1-005〜007を追加remediate中 |
| A2 | NOT_APPLICABLE_UNDER_CURRENT_DECISION | approved command=0件。command/exportはdefault deny、全体完了をblockしない |
| B1 | APPROVED_SEQUENCED | A1 Review後。development/test mock/stub/loopbackのみ |
| B2 | APPROVED_SEQUENCED | B1 Review後。production受信enablementなし |
| M | APPROVED_SEQUENCED | B2 Review後。最終security/recovery/performance/scan/runbook Review |

scope expansionの正本値はDecisionId=DG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02、Decision date=2026-08-30、
OwnerRef=PROJECT_OWNER、OwnerType=ROLE、Base=origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd、
Implementation branch=codex/integration-hub-public-apiである。production enablement、実顧客credential、
実providerへの外部送信、force push、main変更、PR、merge、auto-mergeは禁止する。

## Scope expansion Plan delta remediation

固定Head 1547871caed049ba14d1e5e4a25ad50fa19771fcの独立Plan deltaはPLAN FAIL（P0=0、P1=4、P2=2）
だった。F1 PASSとOwner Gateは再オープンせず、F2は再Reviewの
PLAN PASSまで開始しない。今回のdocs-only remediationで次をSPEC_ADDRESSEDへ移し、
同じR-NF05へ新しいremote Headを渡して独立再Reviewを受ける。

| Finding ID | Severity | Finding | 対応証跡 | Status |
|---|---|---|---|---|
| NF05-PLAN-EXP-001 | P1 | F2 chainの順序、stateless、既存chain排他、default denyが未固定 | design 1.1、inventory 3.1、requirements IH-R1-12、tasks F2 | SPEC_ADDRESSED |
| NF05-PLAN-EXP-002 | P1 | HMAC canonical requestのbyte単位仕様が不足 | design 3.1、inventory 3.2、requirements IH-R1-13、tasks F2 | SPEC_ADDRESSED |
| NF05-PLAN-EXP-003 | P1 | production enablement禁止のdefault-off/fail-closed起動契約が不足 | design 8.1、requirements IH-R1-14/IH-R5-4、tasks F2 | SPEC_ADDRESSED |
| NF05-PLAN-EXP-004 | P1 | mock/stub/loopback限定のdestination、redirect、proxy、DNS検証が不足 | design 8.2、inventory 3.1/3.2、requirements IH-R1-15/IH-R5-4、tasks F2 | CLOSED_BY_REVIEW |
| NF05-PLAN-EXP-005 | P2 | A2=N/Aとcommand implementation taskが不整合 | requirements IH-R2-6、tasks A2、inventory 9 | CLOSED_BY_REVIEW |
| NF05-PLAN-EXP-006 | P2 | requirements/planの旧Plan delta trace | README、plan、requirements、completion-matrix、中央traceability | CLOSED_BY_REVIEW |

SPEC_ADDRESSEDは独立ReviewのPLAN PASSを意味しない。production source、migration、test、
public endpoint、external transportはこのremediationで変更していない。再Reviewで各契約、
evidence path、F2開始条件を照合する。

## Scope expansion Plan delta re-review remediation

固定Head 9cca2deec9ab1bd5417aaba98f859ed14210da13の再ReviewはPLAN FAIL
（P0=0、P1=3、P2=0）だった。F1 PASS、P1-EXP-004、P2-EXP-005/006は維持し、
下記の残存P1だけを0R-P6として補正する。

| Finding ID | Severity | Finding | 対応証跡 | Status |
|---|---|---|---|---|
| NF05-PLAN-EXP-007 | P1 | security chainの順序、専用監査、CSRF/CORS、anonymous、401/403 error boundaryが未閉鎖 | design 1.1、inventory 3.1、requirements IH-R1-12、tasks F2 | SPEC_ADDRESSED |
| NF05-PLAN-EXP-008 | P1 | canonicalTargetのraw取得、path/query結合、空値、上限、Content-Encoding、signature長が未確定 | design 3.1、requirements IH-R1-13、tasks F2 | SPEC_ADDRESSED |
| NF05-PLAN-EXP-009 | P1 | disabled時deny-only chain、bean生成条件、missing/default、MOCK/STUB/LOOPBACK enumが未閉鎖 | design 8.1、inventory 3.1/3.2、requirements IH-R1-14/IH-R1-15/IH-R5-4、tasks F2 | SPEC_ADDRESSED |

ExternalApiAuditBoundaryは既存ApiAuditFilterの代替ではなく、external GETを含む全decisionの
専用監査境界である。nonce commit前のsource IP確定、canonicalTarget golden vector、disabled
deny-only chainとbean不存在を仕様・受入テストへ固定した。SPEC_ADDRESSEDは独立Plan Reviewの
PASSを意味せず、remediation commit e18f0d589b63223bf864bb33c6910b56a59d940eを同じremote branchへ
pushしたうえで、新しいremote Headを同じR-NF05へ再提出する。

## F2 initial implementation evidence（独立Implementation Review FAILの基点）

R-NF05のscope expansion Plan deltaは固定remote Head `ca27f45532bbf96d29da7b9ba87ca52b9cf96d8a`で
PLAN PASS（P0=0、P1=0、P2=0）となったため、同じ専用worktreeでF2を開始した。実装は
`src/main/java/com/ses/config/integrationhub/`へ限定し、F1 serviceを利用する専用principal、
`@Order(0)`/`/external-api/v1/**` chain、stateless/deny-only、HMAC canonical byte検証、
trusted proxy/source IP/CIDR、nonce atomic commit、scope/data scope/command default deny、
quota、correlation、専用audit、stable error boundaryとfilter自動二重登録防止を追加した。
承認済みA1 controller、B1/B2 provider transport、production enablement、実顧客credential、
実provider送信は変更していない。F2 testsはcanonical golden vector、proxy/CIDR、route、properties、
認証/nonce/session/browser、scope/data/quota、disabled MockMvc chainを含む。実装commitは
`aadcfa98`であり、独立Implementation Review PASS受領前にA1へ進まず、最終remote Headは
外部handoffで固定する。

## F2 implementation remediation evidence

独立Implementation Reviewの固定Head `220ac86f531d6e656aeac0ef19225e9596b9385b` はFAIL
（P0=0、P1=4、P2=2）だった。`e47025b5`で次の6件をremediateし、同じR-NF05へ再提出する。

| Finding ID | Severity | Remediation | Verification | Status |
|---|---|---|---|---|
| NF05-IMPL-F2-001 | P1 | connector valveを唯一のraw request-target供給元とし、手動attributeなしのenabled E2Eを追加 | valve unit、F2 chain、connector E2E（環境起因でHTTP assertion前停止） | CLOSED_BY_REVIEW |
| NF05-IMPL-F2-002 | P1 | typed client/route data scopeのintersectionをtenant/legal entity bindしたimmutable effective scopeへ保存 | scope unit、authorization negative、chain integration | CLOSED_BY_REVIEW |
| NF05-IMPL-F2-003 | P1 | 専用V130 audit entity/mapper/service/boundaryを追加し、全decision一request一record、失敗時500を固定 | H2 audit、boundary failure、chain audit count | CLOSED_BY_REVIEW |
| NF05-IMPL-F2-004 | P1 | DNSなしstrict IPv4/IPv6/mapped IPv6 parserへ置換 | source IP strict literal tests | CLOSED_BY_REVIEW |
| NF05-IMPL-F2-005 | P2 | 有限route/method/status/outcome/tier labelとcardinality testを追加 | Micrometer scrape label test | CLOSED_BY_REVIEW |
| NF05-IMPL-F2-006 | P2 | namespace root exact matcherとcatalog/filter/audit/correlation同一境界を追加 | root integration test | CLOSED_BY_REVIEW |

対象F2 suiteは29 tests、failure/error/skipなし。enabled connector E2EはWindowsのloopback接続確立エラーでHTTP到達前に停止したため、
この実行制約を隠さず記録する。F2をPASS扱いせず、独立再Reviewの判定を待つ。

## F2 additional implementation remediation evidence

独立再Reviewの固定Head `f57df6d2cd962c4695d41b9a1980cc4b621cb408` はFAIL（P0=0、P1=1、P2=1）だった。`a16cdcba`で次の2件をremediateした。

| Finding ID | Severity | Remediation | Verification | Status |
|---|---|---|---|---|
| NF05-IMPL-F2-007 | P1 | tenant/legal entityをauthoritative singleton predicateとしてclient/route/ intersection/effective scopeへbind | data scope 4 tests、authorization 7 tests、chain integration 3 tests | CLOSED_BY_REVIEW |
| NF05-IMPL-F2-008 | P2 | mapped IPv6 source/CIDRを4-byte IPv4へcollapseし、mapped prefix 96〜128を0〜32へ変換 | source IP 5 tests（mapped/IPv4双方向を含む） | CLOSED_BY_REVIEW |

追加focused suiteは19 tests、failure/error/skipなし。fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`でF2 IMPLEMENTATION PASS（P0/P1/P2=0/0/0）を受領した。

## A1 Implementation evidence（独立再Review待ち）

| 対象 | 実装/証跡 | 状態 |
|---|---|---|
| read API | `ExternalApiReadController`、`ExternalApiReadService`、`ExternalApiReadMapper`。GET-only 11 paths、list/detail/count | 実装済み・再Review待ち |
| invoice scope | invoiceIds × customerIdsをlist/detail/countへ同一predicate。複数contract時はpublicContractIdをnull | `874fface`、mapper 2 tests、service test | 再Review待ち |
| cursor population | 初回as-ofのmembership/allow-list DTOをV131 materialized snapshotへ保存し、snapshot IDをcursorへbind | `ExternalApiReadSnapshotMapper`、snapshot integration test | 再Review待ち |
| external contract | 4 external DTO、allow-list列、internal entity/ID/secret/PII/金額情報の非公開、11 GET-only path | `ExternalApiDtoContractTest` | 再Review待ち |
| identity/cursor | HMAC opaque public ID、AES-GCM cursor。client/tenant/legal entity/route/scope/snapshot/as-of/expiry binding、canonical Base64URL | `ExternalApiCursorCodecTest` 3 tests | 再Review待ち |
| scope/non-enumeration | F2 immutable effective scopeを唯一のpopulation入力とし、list/detail/count同一母集団、scope外detailは404 | 実装済み | 再Review待ち |
| tests | remediation focused suite 16 tests、failure/error/skipなし。Windows connector E2Eはcrypto fixture修正後もloopback接続制約でHTTP assertion未到達 | E2E制約を隠さず記録 | 再Review待ち |

A1 implementation commit `466bd9aa44e8699f58cfe0ac033c9c444a7de71e`の初回Review FAIL（P0=0/P1=2/P2=2）を、remediation commit
`874fface3bfe90dd27b766ddf9aeff4e00eae591`で対応し、entity serialization negativeの強化を`9ed77cf3056d1bd3f913e461115f4ca732639519`で追加した。同じR-NF05へ独立A1再Reviewとしてhandoffし、PASS受領まではB1を開始しない。

### A1 Implementation Review remediation findings

| ID | Severity | Finding | Fix evidence | Status |
|---|---|---|---|---|
| NF05-IMPL-A1-001 | P1 | invoice customer scope未適用、複数contractを単一publicContractIdとして表現 | `ExternalApiReadMapper`共通customer predicate、`contractCount`、mapper/service tests | REMEDIATED_REVIEW_PENDING |
| NF05-IMPL-A1-002 | P1 | cursorのasOfがページ間のvisible membership/public valueを固定しない | V131 read snapshot header/item、snapshot-only next page、insert/update/delete/reparent integration test | REMEDIATED_REVIEW_PENDING |
| NF05-IMPL-A1-003 | P2 | noncanonical Base64URLのunused bitsを受理 | decode後paddingなしcanonical再encode完全一致、tamper test | REMEDIATED_REVIEW_PENDING |
| NF05-IMPL-A1-004 | P2 | 4 DTO/11 path/entity negative/non-enumeration/E2E crypto fixtureの証跡不足 | DTO/path/entity contract tests、明示test key付きconnector E2E | REMEDIATED_REVIEW_PENDING |
| NF05-IMPL-A1-005 | P1 | snapshot purgeが公開request依存かつ非bounded | expiry index順最大32 header、FK cascade、独立scheduler、rollback/retry/read非DELETE tests | REMEDIATED_REVIEW_PENDING |
| NF05-IMPL-A1-006 | P2 | cursor page間でfractional asOf精度が変化 | 初回からUTC epoch secondsへ正規化、fractional clock pagination test | REMEDIATED_REVIEW_PENDING |
| NF05-IMPL-A1-007 | P2 | connector E2E fixtureのDATETIME timezone変換で認証時刻が未来化 | UTC `LocalDateTime` fixture、Linux実connector再実行対象。Windows loopback失敗は環境制約として記録 | REMEDIATED_REVIEW_PENDING |

今回の再Review FAIL（fixed Head `cddd4850`、P0=0、P1=1、P2=2）の指摘を、上記3件として記録した。
P1-005/006/007は実装・test remediation済みの独立再Review待ちであり、F2 PASS、F1 PASS、Owner Gate、Plan PASSは再オープンしない。

## Findings

| ID | Severity | Finding | Evidence | Disposition |
|---|---|---|---|---|
| NF05-DISC-BLOCK-001 | BLOCKER | approval ledgerがCANDIDATEで、DG-05と開始条件が未承認 | approval-decision.md、中央traceability | OWNER_GATE_RESOLVED |
| NF05-DISC-002 | P1 | NotificationOutboxDispatcherがclaim→外部notify→resultをREQUIRES_NEW transaction内で実行 | NotificationOutboxDispatcher.java:44-70 | SPEC_ADDRESSED。実装は未着手 |
| NF05-DISC-003 | P1 | provider token versionと暗号key versionが分離された公開credential envelopeなし | IntegrationConnection、FreeeIntegrationServiceImpl、MFA crypto services | F1 scope APPROVED。実装未着手 |
| NF05-DISC-004 | P1 | ApiAuditFilterは公開API境界を監査しない | ApiAuditFilter.java:103-117 | CLOSED_BY_REVIEW。F2 ExternalApiAuditBoundaryを追加し、既存filterと分離 |
| NF05-DISC-005 | P1 | 公開client quotaのmulti-node rate limiterなし。既存portal limiterはJVMローカル | PortalRateLimiterImpl.java:15-45 | CLOSED_BY_REVIEW。F1 ApiUsageBucketServiceをchainから適用 |
| NF05-DISC-006 | P1 | InvoiceDetailDtoがInvoice entityを継承する既存前例 | dto/.../InvoiceDetailDto | public DTOでは禁止 |
| NF05-DISC-007 | P1 | 既存/api/webhooks/**はpermitAllかつCSRF ignore | SecurityConfig.java:130-145, 312-315 | 公開inboundと分離 |
| NF05-DISC-008 | P2 | correlation IDのglobal edge filter/MDC propagationを確認できない | ad hoc provider/service経路のみ | CLOSED_BY_REVIEW。F2外部chain専用correlation filterを追加、worker propagationはB1/B2 |
| NF05-DISC-009 | P1 | OpenAPI、HTTP status/error、version互換規則がなく外部契約をreviewできない | openapi-candidate.yaml | A1 remediation後も独立再Review待ち。`874fface`で契約テストを補強 |
| NF05-DISC-010 | P2 | metrics cardinalityと禁止labelの具体設計がない | design.md / requirements.md | SPEC_ADDRESSED。F2/M APPROVED_SEQUENCED、実装未着手 |
| NF05-DISC-011 | P2 | payload retention、legal hold、purgeの契約がない | design.md / requirements.md | SPEC_ADDRESSED。F1 PASS、B1/B2/M APPROVED_SEQUENCED |
| NF05-DISC-012 | P2 | Review Headをcompletion traceへ固定する方式が曖昧 | completion-matrix.md | SPEC_ADDRESSED。最終Headは外部handoffで固定 |
| NF05-PLAN-001 | P1 | rate/quota保存キーが承認値と一致しない | design.md 2.1、requirements IH-R1-8、inventory 7 | SPEC_ADDRESSED。R-NF05 PLAN PASSでクローズ |
| NF05-PLAN-002 | P1 | nonce replay ledgerのatomic unique、TTL、purge契約が不足 | design.md 2.1、requirements IH-R1-9/IH-R3-3 | SPEC_ADDRESSED。R-NF05 PLAN PASSでクローズ |
| NF05-PLAN-003 | P1 | 第二outbox禁止とt_api_deliveryのreuse/分離方針が未確定 | design.md 2/2.1、requirements IH-R3-5/6、inventory 5 | SPEC_ADDRESSED。R-NF05 PLAN PASSでクローズ |
| NF05-PLAN-004 | P1 | retention/legal holdの保存モデル、競合、restore後purgeが不足 | design.md 2.1/8、requirements IH-R6-3、tasks F1 | SPEC_ADDRESSED。R-NF05 PLAN PASSでクローズ |
| NF05-PLAN-005 | P1 | burst 20のcapacity、refill、atomic predicate、clock rollbackが未固定 | design.md 2.1、requirements IH-R1-8、tasks F1 | SPEC_ADDRESSED。R-NF05 PLAN PASSでクローズ |
| NF05-PLAN-006 | P1 | idempotency/delivery/inboundのcanonical enumとterminal retention mappingが不一致 | design.md 2.1/5.3、requirements IH-R6-3、tasks F1 | SPEC_ADDRESSED。R-NF05 PLAN PASSでクローズ |

## F1 Implementation Review findings

初回の独立Implementation Reviewは固定Head `b420911b63177763544edd1e02d663bf528d9dc1` に対し、
FAIL（P0=0、P1=7、P2=2）だった。以下はapproved F1 scope内のremediationであり、再Review前にPASSへ昇格させない。

| ID | Severity | Finding | 対応 | Status |
|---|---|---|---|---|
| NF05-IMPL-001 | P1 | secret/PII/raw body非永続化とgeneric CRUD迂回がservice境界で保証されない | typed ExternalDtoSnapshotの用途別構造allow-list、payload/canonicalPayloadのfield-specific object検証、changedFieldNamesのbounded array、safe response/inbound/outbound検証、F1 serviceからIService/ServiceImpl継承を除去 | CLOSED_BY_REVIEW |
| NF05-IMPL-002 | P1 | inbound DuplicateKey hash conflictがCONFLICTへ永続化されずRECEIVEDに残る | provider event rowをFOR UPDATEで再読し、RECEIVED/PROCESSINGをversion CASでCONFLICTへ遷移。unit testと実MySQL duplicate race追加 | CLOSED_BY_REVIEW |
| NF05-IMPL-003 | P1 | active holdを含むpurge batchでcheckpointがstarveする | active holdを候補から除外し、hold acquire/release時に対象class cursorをreset。checkpoint→target→holdの共通lock順序とkeyset末尾resetを実装 | CLOSED_BY_REVIEW |
| NF05-IMPL-004 | P1 | purge deleteがactive leaseとrow versionを直前に再確認しない |対象row lock後にversion、retention、terminal、lease token/expiryのstrict NULL組合せをdelete predicateへ含め、H2/MySQL test追加 | CLOSED_BY_REVIEW |
| NF05-IMPL-005 | P1 | idempotency conflictが永続化されずCONFLICT状態へ到達しない | mismatch時に固定409 code、terminal/90日retentionをCAS保存してから例外を返す | CLOSED_BY_REVIEW |
| NF05-IMPL-006 | P1 | delivery result CASがgeneration/provider idempotency keyを要求しない | row version、lease token、payload hash、provider idempotency key、generation由来のCAS契約へ修正し、`d476614e`でSQL predicateにもdelivery_generationを追加。H2/MySQL test更新 | CLOSED_BY_REVIEW |
| NF05-IMPL-007 | P1 | F1のmulti-node/境界/遷移/hold-purge証跡が不足 | 実service/mapperを使うMySQL multi-connection usage unique初期化、delivery CAS、hold/purge race、malformed lease、inbound duplicateの5件とH2 inbound/purge境界を追加。M証跡は未完了 | CLOSED_BY_REVIEW |
| NF05-IMPL-008 | P2 | credential OVERLAPのNULL期限がfail-open | overlap_until IS NOT NULL AND overlap_until > server_nowへ修正 | CLOSED_BY_REVIEW |
| NF05-IMPL-009 | P2 | raw pathをroute templateとしてusage bucketへ保存可能 | OpenAPI candidateの11 fixed route template exact setへ制限し、raw resource path test追加 | CLOSED_BY_REVIEW |

## F1 Implementation Review follow-up findings

follow-up独立Implementation Reviewは固定Head `dff90b3961b647035436abd378a352b1fa000dd1` に対して
FAIL（P0=0、P1=4、P2=0）だった。下記はapproved F1 scope内で`5a2a023178433882bc1c5dcf92e19b5ecfa19db6`
へ反映したremediationであり、再Review受領までIMPLEMENTATION PASSへ昇格させない。

| ID | Severity | Finding | 対応 | Status |
|---|---|---|---|---|
| NF05-IMPL-FU-001 | P1 | ExternalDtoSnapshotの許可fieldへraw body/PIIを文字列または未制約nested valueとして埋め込める | `96d6801c`でpublic ID、date/date-time、status/resultCode、signature/processing status、error codeをfield固有pattern/enumで検証。changedFieldNames/skillTagCode、nested深度もboundedにし、許可field内のraw JSON/provider body scalarを拒否するnegative testを追加 | CLOSED_BY_REVIEW |
| NF05-IMPL-FU-002 | P1 | delivery purgeのlease token/expiry片側NULLがfail-open | candidate queryとdelete CASを「両方NULL」または「両方non-NULLかつexpiry<=now」に限定し、期限欠落rowを実MySQLで検証 | CLOSED_BY_REVIEW |
| NF05-IMPL-FU-003 | P1 | holdとpurgeのcheckpoint/target/hold lock順序が逆でdeadlockし得る | hold acquire/releaseをcheckpoint→target→holdへ統一し、checkpoint初期化もupsert-firstへ変更。実MySQL hold/purge raceを追加 | CLOSED_BY_REVIEW |
| NF05-IMPL-FU-004 | P1 | MySQL複数connectionのproduction service/mapper競合証跡が不足 | `IntegrationHubF1MySqlConcurrencyTest`をSpring経由の5テストへ拡張し、usage unique初期化、delivery CAS、hold/purge、malformed lease、inbound duplicateを実証 | CLOSED_BY_REVIEW |

## F1 implementation trace

| Task | Evidence | Status | Review boundary |
|---|---|---|---|
| F1 persistence foundation | `a7654b44`、V129 MySQL migration、H2 schema/init、entity/mapper/service/crypto、purge/rollback証跡 | IMPLEMENTATION_PASS | `a184c1f4`、`d476614e`、`5a2a0231`、`96d6801c`でIMPL findingsをremediate。固定Head `0b52e3de7908d57c2dbac8b9ce1b0972c1be83c3`の独立Implementation ReviewはP0/P1/P2=0でPASS。F2/A1/A2/B1/B2/M、public endpoint、外部送信、production enablementは未着手 |

## Evidence status

- T0: read-only inventory、dedicated worktree、通常checkout非変更を確認。Discovery Review Headは6e0f5067。
- Task 0R: atomic outbox、candidate OpenAPI、metrics cardinality、payload retention、review traceをdocs-onlyでSPEC_ADDRESSED。
  remediation commitは48037c923224f684968dbaf3410cdb37307ed100。
- Task 0R-D: count/asOf/status-code/correlation headerのdelta指摘をdocs-onlyでSPEC_ADDRESSED。実装とPlan Gateは未完了。
  delta remediation commitは11ee82c15a5cdf8f961b2a2d0518a52d81f4de71。
- Owner approval: approval-decision.mdと中央traceabilityへDecisionId、OwnerRef、Base、scope、auth、SLA、
  field inventory、threat modelを正本化した。
- R-NF05 Plan Review: fixed Head 257ffe60773d5c612c8b6ffcfeaf65ef30c2c5ecでPLAN FAIL（P0=0、P1=4）。
  Owner Gateは再オープンせず、NF05-PLAN-001〜004のspec remediation後に同Reviewへ再提出した。
- R-NF05 remediation: b0151e7d8acc54da124c4464db1df263e4b3f716でNF05-PLAN-001〜004をSPEC_ADDRESSEDへ更新した。
  再ReviewのPLAN PASSまではF1を開始しない。
- R-NF05 delta Plan Review: fixed Head 678eac3f09b7ed54419655fcf326e0b15c6d7d62でPLAN FAIL（P0=0、P1=2）。
  NF05-PLAN-005/006をspec remediation後、同Reviewへ再提出する。Owner GateとNF05-PLAN-001〜004は再オープンしない。
- R-NF05 residual remediation: a3b63d70f53bc799d1abcb6e26e34ad163aa9843でNF05-PLAN-005/006をSPEC_ADDRESSEDへ更新した。
  再ReviewのPLAN PASSまではF1を開始しない。
- R-NF05 state mapping cleanup: fdea4bb18db3d3ae6542dc0c534425783dd28a24で旧aliasを除去し、canonical enum/terminal
  retention mappingをdesign/tasksへ同期した。
- R-NF05 Plan Review: 1db3b2fc2657831b7c6c1e59217301302b7caa80でPLAN PASS（P0=0、P1=0、P2=2）。P2は非blocking。
- Scope expansion Plan delta Review: fixed Head 1547871caed049ba14d1e5e4a25ad50fa19771fcでPLAN FAIL
  （P0=0、P1=4、P2=2）。NF05-PLAN-EXP-001〜006をSPEC_ADDRESSEDへ補正し、同じR-NF05へ再提出する。
- Scope expansion Plan delta re-Review: fixed Head 9cca2deec9ab1bd5417aaba98f859ed14210da13でPLAN FAIL
  （P0=0、P1=3、P2=0）。NF05-PLAN-EXP-007〜009を0R-P6でSPEC_ADDRESSEDへ補正し、同じR-NF05へ再提出する。
- F1: Approved scopeのpersistence基盤を`a7654b44`で実装完了。F1 targeted suiteは23 tests PASS、MySQL Flyway smokeもPASS。
- F1 Implementation Review: `b420911b63177763544edd1e02d663bf528d9dc1`でFAIL（P0=0、P1=7、P2=2）。
  `a184c1f4`および`d476614e`でapproved F1 scope内のP1/P2 remediationを実装し、H2 F1対象31 testsとMySQL multi-connection
 concurrency 3 testsをPASSした。follow-upの固定Head `dff90b3961b647035436abd378a352b1fa000dd1`はFAIL（P0=0、P1=4、P2=0）だったが、
  `5a2a0231`で4件をremediateし、H2 F1対象31 testsとMySQL `IntegrationHubF1MySqlConcurrencyTest` 5 testsをPASSした。最新の再Review固定Head
  `f4e3bf7f0c0a8c85d0ca22294471546313e5df1f`はFAIL（P0=0、P1=1、P2=0）で、FU-002〜004はクローズ、FU-001のnested scalar bypassのみ残った。
  `96d6801c`でこれをremediateし、H2 F1対象31 testsを再PASSした。固定Head `0b52e3de7908d57c2dbac8b9ce1b0972c1be83c3`の独立Implementation ReviewはPASS（P0=0、P1=0、P2=0）となった。F2、A1、A2、B1、B2、M: 未着手。
- N/A扱いのテストはない。必須テストは各Taskのpreconditionとして保持する。
- Plan Review完了時点では外部送信、migration、production Java、UI変更は行っていなかった。F1実装は
  Implementation PASS済みで、scope expansion Plan delta PASS後にF2以降を順次開始する。development/testの
  mock/stub/loopback以外の外部送信、production enablement、実顧客credentialは引き続き禁止する。

## Current scope expansion evidence

- DecisionId DG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02 はOwnerRef PROJECT_OWNER、OwnerType ROLE、
  Base origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd、scope expansion approval reviewed Head
  7e50bf1360ea8d7271acc0667593635451300268（承認時点の履歴値）を正本化している。
- F1はPLAN PASS / IMPLEMENTATION PASSを維持する。scope expansion Plan deltaはca27f455でPLAN PASS、
  F2はfixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`でIMPLEMENTATION_PASS、A1初回Review FAIL（fixed Head `111f4baa37096a1419cc8aaddcb2fe8c71e0e229`、P0=0/P1=2/P2=2）は`874fface`系列でremediateしたが、再Review fixed Head `cddd4850`でP1=1/P2=2が残り追加remediation中のREMEDIATED_REVIEW_PENDING、B1/B2/MはAPPROVED_SEQUENCED、A2はNOT_APPLICABLE_UNDER_CURRENT_DECISIONである。
- A1 remediationを既存R-NF05へ独立再Reviewとして依頼する。F1 gate、Owner Gate、0R/0R-D、F2 PASSの状態は再オープンしない。A1再Review完了までB1は開始しない。
- P1-EXP-004、P2-EXP-005/006はクローズ状態を維持する。production endpoint enablement、実顧客credential、実provider送信、PR、mergeは引き続き禁止する。

## F1 gate evidence

1. F1開始時にorigin/mainをfetchし、migration最大値、H2 schema/init経路、backup/rollback前提を再確認する（完了）。
2. 実装はapproved implementation scopeへ限定し、public endpoint、外部送信、A1/A2/B1/B2、production enablementを行わない。
3. 実装commit `a7654b44`、remediation commit `a184c1f4`、generation correction `d476614e`、follow-up remediation
   `5a2a0231`、typed snapshot correction `96d6801c`を許可されたbranchへpush済み。F1対象suiteはfailure/error/skipなし、全fast suiteのF1対象外failure/errorは
   全体PASSへ昇格させない。
