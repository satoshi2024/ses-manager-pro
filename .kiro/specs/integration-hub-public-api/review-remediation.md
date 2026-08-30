# NF-05 Review Remediation（Task 0R）

## 判定の扱い

この文書はReview指摘のうち、実装AIがdocs/architectureで解消可能な範囲を追跡する。
SPEC_ADDRESSEDは仕様上の不足を補ったことを示すだけで、実装PASS、security PASS、公開許可を意味しない。
scope expansion DecisionはF2/A1/B1/B2/Mの開発を承認しているが、Plan ReviewのPLAN PASSと各実装Reviewは
別ゲートである。production enablement、実顧客credential、実provider送信は常に禁止する。
R-NF05は固定Head 257ffe60773d5c612c8b6ffcfeaf65ef30c2c5ecに対してPLAN FAIL（P0=0、P1=4）だった。
最初のremediation後の固定Head 678eac3f09b7ed54419655fcf326e0b15c6d7d62でもPLAN FAIL（P0=0、P1=2）となった。
Owner Gateは再オープンせず、残るburst/state mappingの2件をSPEC_ADDRESSEDへ補正した結果、
固定Head 1db3b2fc2657831b7c6c1e59217301302b7caa80でR-NF05 PLAN PASS（P0=0、P1=0、P2=2）を受領した。

## Finding対応表

| Finding | 種別 | 対応 | 状態 | 残る条件 |
|---|---|---|---|---|
| DG-05 / scope / Owner / Base / auth / SLA / field | P1 | approval-decision.md、review-ledger、中央traceabilityへ承認値を正本化 | OWNER_APPROVED | Plan PASS、F1実装、独立Implementation Review |
| remote Headなし | P1 | Discovery/0R/0R-D seriesをorigin/codex/integration-hub-public-apiへpushし、local/remote一致を確認 | SPEC_ADDRESSED | Task単位pushと最終Head固定 |
| outbox insert after-commit | P1 | 業務stateとoutbox rowを同一DB transactionでatomic commitする設計へ修正。claim/HTTP/CASを分離 | SPEC_ADDRESSED | F1/B1実装とcrash/stale/replay test |
| 外部契約なし | P1 | Owner承認済みの非公開openapi-candidate.yamlを保持。read-only allow-list、status/error/cursor/securityを固定 | SPEC_ADDRESSED | A1は別scope、public endpointは未実装 |
| 実装・検証証拠なし | P1 | F1 approved persistence基盤を`a7654b44`で実装し、F1 targeted testとMySQL migration smokeの証跡を追加 | CLOSED_BY_REVIEW | F1独立Implementation Review PASS。F2〜Mの実装・検証は別gate |
| metrics cardinality不足 | P2 | finite label set、禁止label、scrape/cardinality test、safe trace/audit方針を追加 | SPEC_ADDRESSED | F2/Mの実装・scrape証拠 |
| payload retention不足 | P2 | digest/hash/allow-list snapshot、承認retention、legal hold、purge/restore testを追加 | SPEC_ADDRESSED | F1/B1/B2/M実装 |
| handoff commit系列不足 | P2 | Review Head 6e0f5067を基点として記録し、remediation commitと最終Headをcommit series＋外部handoffで追跡 | SPEC_ADDRESSED | remediation push後の最終Head通知 |
| R-NF05 rate/quota保存キー不一致 | P1 | t_api_usage_bucketをclient×scope×tenant×route templateの論理キーへ固定。IP/raw pathを除外し、minute/day/burstのDB unique・条件付きincrementを定義 | SPEC_ADDRESSED | R-NF05再ReviewでPLAN PASS |
| R-NF05 nonce replay ledger不足 | P1 | t_api_nonce_replay、client+nonce hash atomic unique、rotation跨ぎ再利用拒否、TTL/purge、raw nonce非永続化を定義 | SPEC_ADDRESSED | R-NF05再ReviewでPLAN PASS |
| R-NF05 第二outbox/t_api_delivery方針不明 | P1 | 第二の汎用outboxを禁止し、既存notification outbox/Accounting IntegrationJobをreuse・二重書込みせず、t_api_deliveryをNF-05専用ledgerとして分離 | SPEC_ADDRESSED | R-NF05再ReviewでPLAN PASS |
| R-NF05 retention/legal hold契約不足 | P1 | retention class/expiry、t_api_retention_hold、lock/CAS競合、active lease、部分失敗、restore epoch後全件再評価を保存モデルへ固定 | SPEC_ADDRESSED | R-NF05再ReviewでPLAN PASS |
| R-NF05 burst algorithm不足 | P1 | capacity 20、初期token 20、3秒ごとに1 token refill、minute/dayと同一transactionのatomic predicate、clock rollback、Retry-Afterを固定 | SPEC_ADDRESSED | R-NF05再ReviewでPLAN PASS |
| R-NF05 canonical state/terminal mapping不足 | P1 | idempotency/delivery/inboundのcanonical enum、遷移、非terminal/terminal、30/90日classと起算点、alias/逆遷移拒否を固定 | SPEC_ADDRESSED | R-NF05再ReviewでPLAN PASS |

## Scope expansion Plan delta remediation

固定Head 1547871caed049ba14d1e5e4a25ad50fa19771fcのscope expansion Plan deltaはPLAN FAIL
（P0=0、P1=4、P2=2）である。F1 PASSとOwner Gateは維持し、F2は再ReviewのPLAN PASSまで開始しない。
以下は実装ではなく、P1/P2を仕様・architectureとして分離して補正した証跡である。

| Finding | 対応 | 状態 | 再Review条件 |
|---|---|---|---|
| P1 chain order/stateless/exclusivity/default deny | dedicated chainのorder、排他matcher、stateless、filter順序、anyRequest deny、非共有principalをdesign/inventory/requirements/tasksへ追加 | SPEC_ADDRESSED | 独立Plan delta Review |
| P1 HMAC canonical bytes | raw body bytes、UTF-8 byte length、RFC3986 path/query、duplicate sort、固定LF framing、厳密base64urlを追加 | SPEC_ADDRESSED | 署名vectorとbyte契約の照合 |
| P1 production enablement guard | default-off、MOCK default、missing/unknown/conflict/real URL/credentialのstartup fail-closedを追加 | SPEC_ADDRESSED | 起動fail-closed契約の照合 |
| P1 mock/loopback destination | literal loopback/port、peer/DNS、redirect/proxy、rebind/multi-address拒否を追加 | CLOSED_BY_REVIEW | R-NF05 delta re-Reviewでクローズ |
| P2 A2 inconsistency | A2をNOT_APPLICABLE_UNDER_CURRENT_DECISIONへ統一し、command implementation objectiveを除去 | CLOSED_BY_REVIEW | R-NF05 delta re-Reviewでクローズ |
| P2 old trace | README/plan/requirements/ledger/matrix/中央traceabilityを現HeadのFAILへ同期 | CLOSED_BY_REVIEW | R-NF05 delta re-Reviewでクローズ |

この表のSPEC_ADDRESSEDはPLAN PASSではない。production source、migration、test、endpoint、外部送信の
変更はなく、remediation後の新しいremote Headを同じR-NF05へhandoffする。

## F2 Implementation Review remediation

固定Head `220ac86f531d6e656aeac0ef19225e9596b9385b` の独立Implementation ReviewはFAIL
（P0=0、P1=4、P2=2）だった。F2 approved scope内で解消可能な6件を実装commit
`e47025b5`へ反映した。独立再Reviewが完了するまで、いずれもPASSまたは公開許可へ昇格させない。

| Finding ID | Severity | Finding | 対応証跡 | Status |
|---|---|---|---|---|
| NF05-IMPL-F2-001 | P1 | 実運用のraw request-target供給経路がなく、enabled chainが手動attributeなしで通らない | `ExternalApiRawRequestTargetValve`、`ExternalApiTomcatConfiguration`、`ExternalApiRawRequestTargetValveTest`、manual attributeなしの`ExternalApiEnabledConnectorE2ETest` | CLOSED_BY_REVIEW |
| NF05-IMPL-F2-002 | P1 | client data scopeとroute data scopeのintersection・tenant/legal entity bindingが認可contextへ未接続 | `ExternalApiDataScope`、`ExternalApiEffectiveScope`、`ExternalApiAuthorizationFilter`、scope negative/integration tests | CLOSED_BY_REVIEW |
| NF05-IMPL-F2-003 | P1 | 専用auditがcorrelation、credential識別子、pre/post principal、全decision、一request一recordを満たさない | `ExternalApiAuditTrail`、`ExternalApiAuditBoundary`、`ExternalApiAuditService`、V130、H2/audit boundary tests。永続化失敗は500 fail-closed | CLOSED_BY_REVIEW |
| NF05-IMPL-F2-004 | P1 | CIDR判定がDNS/曖昧IP表記を許す | `ExternalApiCidrMatcher` strict literal parser、IPv4/IPv6/mapped/hostname/short/integer/leading-zero/zone tests | CLOSED_BY_REVIEW |
| NF05-IMPL-F2-005 | P2 | finite metrics labelとscrape cardinality契約が未実装 | `ExternalApiMetricsRecorder`、finite-label scrape test | CLOSED_BY_REVIEW |
| NF05-IMPL-F2-006 | P2 | `/external-api/v1` namespace rootが専用filter境界を迂回する | `ExternalApiRouteCatalog` exact root、`ExternalApiSecurityConfig` exact matcher、root chain/audit/correlation integration test | CLOSED_BY_REVIEW |

F2 remediation対象13クラスの再実行結果は29 tests、failure 0、error 0、skip 0である。enabled connector E2Eは実装上のmanual
attribute注入なしで追加したが、Windows実行環境の`Unable to establish loopback connection`によりTomcat起動前に停止し、HTTP assertionへ到達していない。
この環境制約をPASS根拠にせず、独立Reviewへ明示する。A1/B1/B2/M、production enablement、実顧客credential、実provider送信、PR/mergeは未実施である。

## F2 Implementation Review追加remediation

固定remote Head `f57df6d2cd962c4695d41b9a1980cc4b621cb408` の独立再ReviewはFAIL（P0=0、P1=1、P2=1）だった。`a16cdcba`で次の2件を修正し、
同じR-NF05へ再提出する。

| Finding ID | Severity | Finding | 対応証跡 | Status |
|---|---|---|---|---|
| NF05-IMPL-F2-007 | P1 | tenant/legal entity矛盾がresource ID一致だけで許可される | `ExternalApiDataScope.requireAuthoritativeBinding`、`ExternalApiDataScope.intersect`の空dimension保持、`ExternalApiEffectiveScope`のsingleton注入/空predicate拒否、authorization/data-scope tests | CLOSED_BY_REVIEW |
| NF05-IMPL-F2-008 | P2 | IPv4-mapped IPv6 source/CIDRがfamily長不一致で誤拒否される | `ExternalApiCidrMatcher`の`::ffff:0:0/96` collapseとprefix変換、mapped/IPv4双方向CIDR tests | CLOSED_BY_REVIEW |

authoritative scopeはtenant/legal entityを常にprincipal singletonへ拘束し、JSON dimension省略時にもeffective populationへ追加する。明示dimensionの不一致、
client/routeでの空intersection、resource dimensionの不在はfail-closedとする。CIDRはmapped IPv6をIPv4 predicateへ正規化して比較する。追加focused suiteは19 tests、
failure/error/skipなしでPASSした。fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`でF2 IMPLEMENTATION PASS（P0/P1/P2=0/0/0）を受領した。

## A1 Implementation Review handoff

F2 PASS後、A1を `466bd9aa44e8699f58cfe0ac033c9c444a7de71e`で実装した。対象はGET-only 11 paths、external DTO allow-list、
opaque public ID、暗号化cursor、effective scope-bound list/detail/countである。初回独立Reviewはfixed Head `111f4baa37096a1419cc8aaddcb2fe8c71e0e229`でFAIL
（P0=0、P1=2、P2=2）だった。`874fface3bfe90dd27b766ddf9aeff4e00eae591`でinvoice customer scope、snapshot-bound cursor、
canonical Base64URL、4 DTO/11 path/entity/E2E証跡をremediateした。remediation focused suiteは16 tests、failure/error/skipなしでPASSした。

| 対象 | 状態 | Review境界 |
|---|---|---|
| F2 | IMPLEMENTATION_PASS | fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`、P0/P1/P2=0/0/0 |
| A1 | REMEDIATED_REVIEW_PENDING | `874fface3bfe90dd27b766ddf9aeff4e00eae591`を既存R-NF05へ再handoff。独立再Review完了までB1を開始しない |

Windows browser profileのconnector E2Eはcrypto fixture修正後もloopback接続確立失敗でHTTP assertion前に停止したため、この環境制約をA1 PASS根拠にはしない。

## Scope expansion Plan delta re-review remediation

固定Head 9cca2deec9ab1bd5417aaba98f859ed14210da13の独立Plan delta再ReviewはPLAN FAIL
（P0=0、P1=3、P2=0）だった。P1-EXP-004、P2-EXP-005/006はSPEC_ADDRESSEDを維持し、
次の3件を0R-P6として補正する。F1 PASS、Owner Gate、production禁止境界は再オープンしない。

| Finding | 対応 | 状態 | 再Review条件 |
|---|---|---|---|
| P1 security chain order/audit/error boundary | trusted proxy/source IP/CIDRをnonce commit前に確定、HMACとnonce insertを分離。ExternalApiAuditBoundaryでGETを含む全decisionを記録し、CSRF/CORS/anonymous/401/403/correlationを専用chainへ固定 | SPEC_ADDRESSED | 独立Plan delta再Review |
| P1 canonicalTarget byte uniqueness | OpenAPI wire header、credentialVersion/keyId、raw request-target取得元、origin-form split、path/queryの?・&・=、値なし/空値、percent normalization、sort/rebuild、Content-Encoding、target/header/body上限、signature 32-byte、golden vectorを固定 | SPEC_ADDRESSED | canonical byte照合 |
| P1 disabled fail-closed bean/config | profileへfalse/MOCKを明示しmissingは拒否。disabledでもdeny-only chainを生成し、controller/worker/scheduler/transportを生成せずfall-throughを禁止。mode enumをMOCK/STUB/LOOPBACKへ統一 | SPEC_ADDRESSED | startup/bean/route境界照合 |

この0R-P6はdocs/architectureのみであり、production source、migration、test、public endpoint、
external transportを変更しない。新しいremote Headを同じR-NF05へ渡し、PLAN PASS受領までF2を開始しない。

## F1 Implementation Review remediation

初回Implementation Reviewは固定Head `b420911b63177763544edd1e02d663bf528d9dc1` に対してFAIL（P0=0、
P1=7、P2=2）だった。以下は実装・テストへ反映し、後続の独立Implementation Reviewでクローズされた。

| Finding | 対応 | 状態 | 証跡 / 残る条件 |
|---|---|---|---|
| P1-001 snapshot保存境界・generic CRUD迂回 | typed ExternalDtoSnapshotの構造allow-list、field固有型、用途別service API。IService/ServiceImpl継承を除去 | CLOSED_BY_REVIEW | `a184c1f4`、`5a2a0231`、`96d6801c`、H2 31件。独立Implementation Review PASS、M scanは別gate |
| P1-002 inbound DuplicateKey conflict | provider event FOR UPDATE後にCONFLICTをversion CAS保存 | CLOSED_BY_REVIEW | InboundEventServiceTest 2件、`5a2a0231`の実MySQL inbound race。独立Implementation Review PASS |
| P1-003 purge starvation | active hold除外、hold acquire/release reset、checkpoint→target→holdの共通lock順序、keyset末尾reset | CLOSED_BY_REVIEW | IntegrationHubF1RetentionH2Testのhold/lease-cursor境界、`5a2a0231`のMySQL hold/purge race。独立Implementation Review PASS |
| P1-004 purge lease/version CAS | lock後のdelete predicateへversion、terminal、expiry、lease token/expiryのstrict NULL組合せを含める | CLOSED_BY_REVIEW | H2 + `5a2a0231`のIntegrationHubF1MySqlConcurrencyTest。独立Implementation Review PASS |
| P1-005 idempotency CONFLICT | mismatchを固定409/90日retentionへ永続化後に拒否 | CLOSED_BY_REVIEW | ApiIdempotencyServiceTest、mapper CAS。独立Implementation Review PASS |
| P1-006 delivery result CAS | provider key、payload hash、lease、row version、generation由来キーをCAS要求し、`d476614e`でSQL predicateにもdelivery_generationを追加 | CLOSED_BY_REVIEW | H2 + MySQL CAS test。独立Implementation Review PASS。B1外部provider実装は未着手 |
| P1-007 F1 evidence不足 | H2 31 tests、実service/mapperを使うMySQL multi-connection 5 tests、shard inventoryを追加 | CLOSED_BY_REVIEW | `5a2a0231`、F1 scopeの独立Implementation Review PASS。M/security/load/recoveryは未完了 |
| P2-001 credential overlap NULL | non-null overlap_untilの将来期限だけ有効 | CLOSED_BY_REVIEW | CredentialVersionServiceTest。独立Implementation Review PASS |
| P2-002 raw route template | candidate 11 fixed templates以外を拒否 | CLOSED_BY_REVIEW | ApiUsageBucketServiceTest。独立Implementation Review PASS |

## F1 Implementation Review follow-up

follow-up独立Implementation Reviewは固定Head `dff90b3961b647035436abd378a352b1fa000dd1` に対して
FAIL（P0=0、P1=4、P2=0）だった。下記はapproved F1 scope内で`5a2a023178433882bc1c5dcf92e19b5ecfa19db6`
へ反映したremediationであり、追加修正後の固定Headで独立Implementation Review PASSを受領した。

| Finding | 対応 | 状態 | 証跡 / 残る条件 |
|---|---|---|---|
| P1-FU-001 raw body/PII nested snapshot | `ExternalDtoSnapshot`を用途別allow-listのstructured objectへ限定し、public ID、date/date-time、status/resultCode、signature/processing status、error codeをfield固有pattern/enumで検証。changedFieldNames/skillTagCode、nested深度もboundedにし、許可field内のraw JSON/provider body scalarを拒否するnegative testを追加 | CLOSED_BY_REVIEW | `5a2a0231`、`96d6801c`、ApiDeliveryServiceTest。独立Implementation Review PASS。M scanは別gate |
| P1-FU-002 lease片側NULLのpurge | candidate queryとdelete CASを「両方NULL」または「両方non-NULLかつexpiry<=now」に限定し、期限欠落rowを実MySQLで検証 | CLOSED_BY_REVIEW | `5a2a0231`、IntegrationHubF1MySqlConcurrencyTest 5/5。独立再Reviewでクローズ |
| P1-FU-003 hold/purge lock順序 | hold acquire/release/purgeをcheckpoint→target→holdへ統一し、checkpoint初期化もupsert-firstへ変更。実MySQL hold/purge raceを追加 | CLOSED_BY_REVIEW | `5a2a0231`、IntegrationHubF1RetentionH2Test/MySQL。独立再Reviewでクローズ |
| P1-FU-004 MySQL multi-connection evidence | `IntegrationHubF1MySqlConcurrencyTest`をSpring経由の5テストへ拡張し、usage unique初期化、delivery CAS、hold/purge、malformed lease、inbound duplicateを実証 | CLOSED_BY_REVIEW | `5a2a0231`、5/5 PASS。独立再Reviewでクローズ。M/security/load/recoveryは未完了 |

再Review（`f4e3bf7f0c0a8c85d0ca22294471546313e5df1f`）はP0=0、P1=1、P2=0だった。FU-002〜004は独立検証でクローズ、
FU-001のみ許可nested scalarの迂回が残ったため、`96d6801c`でfield固有validatorとnegative testを追加した。現状態は
`0b52e3de7908d57c2dbac8b9ce1b0972c1be83c3`の独立Implementation ReviewでPASS（P0=0、P1=0、P2=0）となり、
F1実装Review gateを通過した。M/security/load/recovery/scan/runbookとF2以降は別gateであり、ここで公開可能とは扱わない。

今回のremediationでoutbox/CAS、candidate契約、metrics、retentionの仕様とF1実装境界を同期した。follow-upではsnapshot形状、
lease fail-closed、lock順序、MySQL競合証跡を追加したが、public endpoint、
外部送信、B1/B2/Mは未着手であり（F2はPASS、A1はremediation済みで独立再Review待ち、A2はN/A）、レビュー結果を自己PASSへ変更しない。

## Task 0R scope

完了範囲は以下のdocs-only変更に限定する。

- atomic outboxの原子commit、claim lease、外部HTTP、result CAS、crash/stale/replay要件。
- 非公開OpenAPI candidateのversion namespace、dedicated security boundary、read-only DTO allow-list、
  deny-list、stable error、correlation、cursor binding、default-deny command/export。
- metrics labelを有限集合へ限定し、client/correlation/request/resource/user/IP/provider IDを禁止。
- idempotency/inbound/outboundのraw secret/PII/provider body非永続化、succeeded 30日、failed/DLQ 90日、
  audit metadata 1年の承認済みretention、legal hold、purge/restore要件。
- rate/quota保存キー、nonce replay ledger、NF-05専用t_api_deliveryの分離、retention hold/checkpointの
  lock/CAS・restore再評価をR-NF05のP1 remediationとして追加。
- requirements、design、tasks、completion matrix、review ledgerのtrace更新。

## 実装範囲の残存ゲート

Owner承認とR-NF05 PLAN PASSにより、F1 persistence基盤の実装条件は確定した。F1初回実装は`a7654b44`、Review remediationは
`a184c1f4`、追加CAS修正は`d476614e`、follow-up remediationは`5a2a0231`、typed snapshot correctionは`96d6801c`であり、
固定Head `0b52e3de7908d57c2dbac8b9ce1b0972c1be83c3`の独立Implementation Review PASSを受領した。F2/A1/B1/B2/Mは
scope expansionで開発承認済みであり、Plan deltaは固定Head `ca27f45532bbf96d29da7b9ba87ca52b9cf96d8a`でPASSした。
F2はfixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`で独立Implementation Review PASS済み、A1は初回Review FAILをremediate済みで独立再Implementation Review待ちである。A2/command/exportは
NOT_APPLICABLE_UNDER_CURRENT_DECISIONで、production enablement、実顧客credential、実provider送信は禁止する。

## Handoff checkpoint

- Review baseline Head: 6e0f5067d9a6509775225278cc0dcfdc4d47643f
- Task 0R remediation commit: 48037c923224f684968dbaf3410cdb37307ed100
- Task 0R-D delta remediation commit: 11ee82c15a5cdf8f961b2a2d0518a52d81f4de71
- Owner Gate normalization commit: 2f91e5a584c5224989780cb323e40f33fda185b6
- R-NF05 Plan Review result: 257ffe60773d5c612c8b6ffcfeaf65ef30c2c5ec、PLAN FAIL（P0=0、P1=4）
- R-NF05 Plan remediation commit: b0151e7d8acc54da124c4464db1df263e4b3f716
- R-NF05 delta Plan Review result: 678eac3f09b7ed54419655fcf326e0b15c6d7d62、PLAN FAIL（P0=0、P1=2）
- R-NF05 residual remediation commit: a3b63d70f53bc799d1abcb6e26e34ad163aa9843
- R-NF05 state mapping cleanup commit: fdea4bb18db3d3ae6542dc0c534425783dd28a24
- R-NF05 final Plan Review: 1db3b2fc2657831b7c6c1e59217301302b7caa80、PLAN PASS（P0=0、P1=0、P2=2）
- Scope expansion Plan delta Review: 1547871caed049ba14d1e5e4a25ad50fa19771fc、PLAN FAIL（P0=0、P1=4、P2=2）
- Scope expansion Plan delta remediation commit: 8d25215b9b651e99433becf50d13498da3699d2a（docs-only、remoteへpush済み）
- Scope expansion Plan delta re-Review: 9cca2deec9ab1bd5417aaba98f859ed14210da13、PLAN FAIL（P0=0、P1=3、P2=0）
- Scope expansion Plan delta residual remediation commit: e18f0d589b63223bf864bb33c6910b56a59d940e（docs-only、remoteへpush済み）
- Scope expansion Plan delta PASS: ca27f45532bbf96d29da7b9ba87ca52b9cf96d8a（P0=0、P1=0、P2=0）
- F2 implementation remediation: `e47025b5`でconnector raw-target、typed effective scope、専用audit、strict IP、finite metrics、namespace rootを追加し、`a16cdcba`でtenant/legal entityとmapped CIDRを補正（fixed Head `d022e600`で独立Review PASS）
- F1 implementation commit: a7654b44、F1 targeted suite 23 tests PASS、MySQL V129 smoke PASS
- F1 implementation remediation commit: a184c1f4、F1 H2 targeted suite 31 tests PASS、MySQL multi-connection concurrency 3 tests PASS（当時点の証跡）
- delivery CAS generation predicate correction: d476614e、ApiDeliveryServiceTest/MySQL CAS test PASS
- 初回Implementation Review: b420911b63177763544edd1e02d663bf528d9dc1、FAIL（P0=0、P1=7、P2=2）
- follow-up Implementation Review: dff90b3961b647035436abd378a352b1fa000dd1、FAIL（P0=0、P1=4、P2=0）
- follow-up remediation: 5a2a023178433882bc1c5dcf92e19b5ecfa19db6、H2 31 tests / MySQL 5 tests PASS
- typed snapshot correction: 96d6801c37d4b952e2601a06cf7edc1bc1a1bef8、H2 31 tests / MySQL 5 tests再確認、独立Implementation Review PASS
- Final F1 Implementation Review: fixed Head 0b52e3de7908d57c2dbac8b9ce1b0972c1be83c3、PASS（P0=0、P1=0、P2=0）
- Final remote Head: この文書を含む最終handoff commitの外部通知で固定する。自己参照hashは記録しない。

## Task 0R delta対応

| Delta finding | 対応 | 状態 |
|---|---|---|
| engineer-availability countがinventoryを越える | candidate OpenAPIからcount pathを削除。inventoryのlist/detailと一致 | SPEC_ADDRESSED |
| client指定asOfがdesignと矛盾 | 全query parameterからasOfを削除。server受信時刻をresponse/cursorへ固定する契約へ統一 | SPEC_ADDRESSED |
| HTTP statusとerror codeの未固定 | statusごとの専用error schemaとcode enum、status/code map、scope外detailの404収束契約を追加 | SPEC_ADDRESSED |
| 成功/error responseのcorrelation header不統一 | 全GET success responseと共通error responseへX-Correlation-IDを追加 | SPEC_ADDRESSED |
| DG-05、scope、Owner、Base、auth、SLA、field inventory | approval-decision.mdと中央traceabilityへ正本化 | OWNER_APPROVED |

Task 0R-Dもdocs-onlyであり、OpenAPI implementation、contract test、security test、F1〜MをPASS扱いにしない。
Owner approvalはPLAN PASSまたはimplementation PASSを意味しない。

## A1 Implementation Review remediation（固定Head `111f4baa` → `874fface`）

初回A1独立Implementation ReviewはFAIL（P0=0、P1=2、P2=2）だった。以下はapproved A1 scope内で実装・テスト可能な修正であり、
`874fface3bfe90dd27b766ddf9aeff4e00eae591`へ反映しpush済みである。独立再Review受領まではA1をPASS扱いにせず、B1を開始しない。

| ID | Severity | Review finding | Remediation | Status |
|---|---|---|---|---|
| NF05-IMPL-A1-001 | P1 | invoice customer scope未適用、複数contractを単一publicContractIdとして返す | `invoiceIds × customerIds`をlist/detail/countの同一predicateへ適用し、contractCountが1の場合だけpublic IDを返す。mapper/service testでscope外customerとmulti-contractを固定 | REMEDIATED_REVIEW_PENDING |
| NF05-IMPL-A1-002 | P1 | cursorのasOfがvisible membership/public valueをページ間で固定しない | `t_api_read_snapshot`/itemへ初回allow-list DTOをmaterializeし、snapshot IDを暗号化cursorへbind。insert/update/delete/reparent integration testを追加 | REMEDIATED_REVIEW_PENDING |
| NF05-IMPL-A1-003 | P2 | noncanonical Base64URL unused bitsを受理 | paddingなしBase64URLのdecode後canonical再encode完全一致を要求し、unused bits tamper testを追加 | REMEDIATED_REVIEW_PENDING |
| NF05-IMPL-A1-004 | P2 | DTO/path/entity negative/non-enumeration/E2E crypto fixtureの証跡不足 | 4 DTO allow-list、11 GET-only path、entity negative、明示test key付きenabled E2E fixtureを追加 | REMEDIATED_REVIEW_PENDING |

### A1 remediation evidence

- code/test/migration commit: `874fface3bfe90dd27b766ddf9aeff4e00eae591`
- remediation focused suite: cursor 3、service 5、DTO 5、mapper 2、snapshot integration 1。failure/error/skipなし。
- enabled connector browser E2Eはcrypto fixture未設定を修正したが、Windows Tomcat loopback接続確立失敗でcontext起動前に停止。Linux再実行PASSを推測せず、独立Reviewへ環境制約としてhandoffする。
- production enablement、実顧客credential、実provider送信、A2 command/export、PR、mergeは引き続き禁止。
