# NF-05 Review Remediation（Task 0R / B1 closed / B2再Review待ち）

## 判定の扱い

この文書はReview指摘のうち、実装AIがdocs/architecture/code/testで解消可能な範囲を追跡する。
SPEC_ADDRESSEDは仕様上の不足を補ったことを示すだけで、実装PASS、security PASS、公開許可を意味しない。
scope expansion DecisionはF2/A1/B1/B2/Mの開発を承認しているが、Plan ReviewのPLAN PASSと各実装Reviewは
別ゲートである。production enablement、実顧客credential、実provider送信は常に禁止する。B1は固定Head
`f897d748cb93ade26c41d6ba4cb1a88efb29a29d`で独立Implementation Review PASS（P0/P1/P2=0/0/0）を受領した。B2は初回実装
`122c7c3b`後、`cc468e4f`でReview指摘をremediate済み・独立再Implementation Review待ちである。
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
canonical Base64URL、4 DTO/11 path/entity/E2E証跡をremediateした。初回remediation focused suiteは16 tests、failure/error/skipなしでPASSした。

| 対象 | 状態 | Review境界 |
|---|---|---|
| F2 | IMPLEMENTATION_PASS | fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`、P0/P1/P2=0/0/0 |
| A1 | IMPLEMENTATION_PASS | fixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`、P0/P1/P2=0/0/0 |

remediation focused/integration suiteは24/24 PASS、V131を含むMySQL 8 Flyway smokeは2/2 PASS（空DBおよびlegacy baseline経路）だった。Windows browser profileのconnector E2EはUTC fixture修正後もloopback接続確立失敗でHTTP assertion前に停止したため、この環境制約をA1 PASS根拠にはしない。fixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`で独立Review PASS（P0/P1/P2=0/0/0）を受領した。

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
| P1-006 delivery result CAS | provider key、payload hash、lease、row version、generation由来キーをCAS要求し、`d476614e`でSQL predicateにもdelivery_generationを追加 | CLOSED_BY_REVIEW | H2 + MySQL CAS test。独立Implementation Review PASS。B1外部provider実装はF1時点では未着手（現在は`971c17d7`でdevelopment/test境界のみ実装） |
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
外部送信、B2/Mは未着手であり（F2はPASS、A1はfixed Head `69f857d3`で独立Implementation Review PASS、B1は初回Review FAILを`30199db8`、再Review P1-006/P1-007を`2684ff8f`、追加P1-007を`5c94367c` → `0618d983`、NF05-IMPL-B1-008を`c2cbfb99`でremediate済み・独立再Review待ち、A2はN/A）、レビュー結果を自己PASSへ変更しない。

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
F2はfixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`で独立Implementation Review PASS済み、A1はfixed Head
`69f857d3ac7d513b66265b02871688b28d2e7e5d`で独立Implementation Review PASS済みである。B1は初回Review FAILを`30199db8`、再Review P1-006/P1-007を`2684ff8f`、追加remediationを`5c94367c` → `0618d983` → `c2cbfb99`で実施済み・独立再Review待ちである。A2/command/exportは
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
`874fface3bfe90dd27b766ddf9aeff4e00eae591`へ反映しpush済みである。追加remediation後のfixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`で
独立Implementation Review PASSを受領し、B1を開始した。

| ID | Severity | Review finding | Remediation | Status |
|---|---|---|---|---|
| NF05-IMPL-A1-001 | P1 | invoice customer scope未適用、複数contractを単一publicContractIdとして返す | `invoiceIds × customerIds`をlist/detail/countの同一predicateへ適用し、contractCountが1の場合だけpublic IDを返す。mapper/service testでscope外customerとmulti-contractを固定 | CLOSED_BY_REVIEW |
| NF05-IMPL-A1-002 | P1 | cursorのasOfがvisible membership/public valueをページ間で固定しない | `t_api_read_snapshot`/itemへ初回allow-list DTOをmaterializeし、snapshot IDを暗号化cursorへbind。insert/update/delete/reparent integration testを追加 | CLOSED_BY_REVIEW |
| NF05-IMPL-A1-003 | P2 | noncanonical Base64URL unused bitsを受理 | paddingなしBase64URLのdecode後canonical再encode完全一致を要求し、unused bits tamper testを追加 | CLOSED_BY_REVIEW |
| NF05-IMPL-A1-004 | P2 | DTO/path/entity negative/non-enumeration/E2E crypto fixtureの証跡不足 | 4 DTO allow-list、11 GET-only path、entity negative、明示test key付きenabled E2E fixtureを追加 | CLOSED_BY_REVIEW |
| NF05-IMPL-A1-005 | P1 | snapshot purgeが公開request依存かつ非bounded | expiry index順の最大32 headerを独立schedulerが短いtransactionで削除し、FK cascadeでitemを削除。read pathからpurgeを除去し、bounded/cascade/retry/read非DELETE testを追加 | CLOSED_BY_REVIEW |
| NF05-IMPL-A1-006 | P2 | cursor page間でfractional asOf精度が変化 | 初回からUTC epoch secondsへ正規化し、snapshot/cursor/後続responseを同じ秒精度に固定。fractional clock testを追加 | CLOSED_BY_REVIEW |
| NF05-IMPL-A1-007 | P2 | connector E2E fixtureのDATETIME timezone変換で認証時刻が未来化 | fixtureをUTC `LocalDateTime`で登録。Windows loopbackは環境制約、Linux実connectorで401/200を再検証する | CLOSED_BY_REVIEW |

### A1 remediation evidence

- code/test/migration commit: `874fface3bfe90dd27b766ddf9aeff4e00eae591`、追加remediationは次のfixed Headへpushする。
- entity serialization negative strengthening follow-up: `9ed77cf3056d1bd3f913e461115f4ca732639519`
- remediation focused/integration suite: cursor 3、service 6、DTO 5、mapper 2、snapshot integration 1、purge integration 2。計23 tests、failure/error/skipなし。
- enabled connector browser E2EはUTC `LocalDateTime` fixtureへ修正したが、Windows Tomcat loopback接続確立失敗でcontext起動前に停止。Linux再実行PASSを推測せず、独立Reviewで401/200を確認する。
- production enablement、実顧客credential、実provider送信、A2 command/export、PR、mergeは引き続き禁止。

## B1 Implementation Review handoff（初回FAIL remediation済み・独立再Review待ち）

`971c17d7`でB1 approved scopeを実装した。NF-05専用`t_api_delivery`を唯一のoutbound delivery ledgerとして再利用し、業務stateとのatomic insert、
claim/lease transaction、DB transaction外のHTTP、provider idempotency key・payload hash・generation・lease tokenを用いる結果CASを分離した。
固定framing HMAC-SHA256署名、credential version/key ID、correlation、最大8回のtimeout/429/5xx retry、その他4xx no-retry、DLQ、new-generation replay audit、
MOCK/STUB無接続、LOOPBACK strict literal/peer検証・redirect/proxy/DNSなしを含む。V132 migration、H2 schema、focused 28 tests（failure/error/skipなし）、
実loopback test server、署名golden vector、設定fail-closedを確認済みである。B1の固定remote Headを同じR-NF05へ独立Implementation Reviewとしてhandoffし、PASS受領までB2を開始しない。

## B1 Implementation Review remediation（fixed Head `0f1a9297` → `30199db8`）

初回B1独立Implementation ReviewはFAIL（P0=0、P1=4、P2=1）だった。以下はproduction enablementや実provider送信を行わず、
approved B1 scope内で実装・テスト可能な修正である。状態は独立再ReviewまでSPEC_ADDRESSEDとし、IMPLEMENTATION PASSへ自己昇格させない。

| ID | Severity | Finding | Remediation / evidence | Status |
|---|---|---|---|---|
| NF05-IMPL-B1-001 | P1 | outbound署名fieldとevent envelope/ledger bindingが未完結 | `IntegrationHubWebhookSigner`の固定canonical framingへcredential version/provider idempotency keyを追加し、`ExternalDtoSnapshot.requireOutboundEnvelope`でevent ID/type/schema/createdAt/correlation/resource/payloadをledgerと一致検証。golden vector・field tamper negativeを追加 | SPEC_ADDRESSED（独立再Review待ち） |
| NF05-IMPL-B1-002 | P1 | manual replayのpermission/current scope再検証不足 | `IntegrationHubWebhookReplayAuthorizationService`が`integration.webhook.replay`、active client/subscription、permission、client/permission/subscription scope intersection、tenant/legal entity、payload membershipをDBから再取得。revoked・scope縮小・resource除外を検証 | SPEC_ADDRESSED（独立再Review待ち） |
| NF05-IMPL-B1-003 | P1 | replay auditがdelivery payload purgeを阻害 | V133でdelivery FKを`ON DELETE SET NULL`へ変更し、audit metadataの`retention_expires_at`と独立bounded purgeを追加。H2/MySQLでreplay後にpayload 30/90日とaudit 1年を別々にpurge | SPEC_ADDRESSED（独立再Review待ち） |
| NF05-IMPL-B1-004 | P1 | batch開始時刻の再利用でlease/backoffが過去化 | claim直前・HTTP完了後にclockを再取得し、retryをHTTP完了時刻基準へ変更。provider timeoutを上回るlease制約、slow transport、CAS failure recoveryを追加 | SPEC_ADDRESSED（独立再Review待ち） |
| NF05-IMPL-B1-005 | P2 | failure/concurrency/attempt 8の実DB証跡不足 | timeout/5xx、attempt 8/DLQ、provider成功直後CAS障害、stale recovery、同時claim、atomic rollback、replay後purgeをworker/H2/MySQLへ追加 | SPEC_ADDRESSED（独立再Review待ち） |

実装commitは`30199db8`。focused unit、H2 retention/schema、MySQL 8 concurrency/retentionはfailure/error/skipなしでPASSした。
独立再Review、B2、M、production enablement、実顧客credential、実provider送信、PR/mergeは未完了・禁止である。

## B1再Review remediation（fixed Head `29d749bb` → `2684ff8f`）

同一R-NF05の独立再ReviewはFAIL（P0=0、P1=2、P2=0）だった。F1/F2/A1のPASSと初回B1 remediationは再オープンしない。

| ID | Severity | Finding | Remediation / evidence | Status |
|---|---|---|---|---|
| NF05-IMPL-B1-006 | P1 | replay操作主体のadmin permissionが未検証で、呼出側operatorRefを信頼し得る | `IntegrationHubWebhookDeliveryReplayService`からoperatorRef入力を除去し、`IntegrationHubWebhookReplayAuthorizationService`のservice boundaryで認証済み内部`LoginUser`、有効/非ロック、`ROLE_管理者`、`integration.webhook.replay` action permissionを検証。principalから導出した`sys-user:<id>`だけをauditへ渡す。未認証、非admin、permission拒否、偽装operator入力のnegative testを追加 | SPEC_ADDRESSED（`2684ff8f`、独立再Review待ち） |
| NF05-IMPL-B1-007 | P1 | current numeric scopeとopaque public IDを直接比較し、実契約のID bindingが不足 | client/permission/subscriptionのcurrent intersectionを再計算し、正のnumeric内部resource IDごとに`ExternalApiPublicIdCodec`のclient/tenant/resource-bound HMACを再計算してenvelope/payloadのopaque IDを照合。resource dimension消失、tenant reparent、削除、scope縮小、実HMAC IDをtestし、resource scopeなしを拒否 | SPEC_ADDRESSED（`2684ff8f`、独立再Review待ち） |

`2684ff8f1303b6d0cc6550882601405d3d78f3b2`で実装をpushした。focused B1対象はreplay authorization 10、replay generation 2、signer 2、worker 8、public-ID 1をfailure/error/skipなしでPASSした。
独立再Review、B2、M、production enablement、実顧客credential、実provider送信、PR/mergeは未完了・禁止である。

## B1 P1-007残存指摘の追加remediation（fixed Head `1c3efc30` → code `5c94367c` → `0618d983` → docs trace）

独立再Reviewは、`publicResourceId`を全resource dimensionへ誤適用し、scope JSONだけでは現行のsoft-delete/reparentを検出できないとして
FAIL（P0=0、P1=1、P2=0）を返した。F1/F2/A1、P1-006、Owner Gate、Plan PASSは再オープンしない。

| Finding | 対応 | Status |
|---|---|---|
| primary/secondary opaque binding | V134で`t_api_delivery`へ`primary_resource_type/id`を追加し、新enqueue/replayをprimaryへbind。`publicResourceId`とpayload primary fieldはprimary内部IDからHMAC再計算し、project/customer、invoice/customer/contract等のsecondaryは各専用public field/codecで独立照合 | SPEC_ADDRESSED（独立再Review待ち） |
| current relation revalidation | `IntegrationHubWebhookResourceScopeMapper`が一次resourceを`deleted_flag=0`、active customer/project/contract、invoice item/work recordの現行parent relation付きで再照会。client/permission/subscription intersection、tenant/legal singleton、numeric scope、current membershipをimmutable populationとして再評価し、scope据置のsoft-delete/reparent/contract付替えを拒否 | SPEC_ADDRESSED（独立再Review待ち） |
| evidence | project×customer、invoice×customer×contract正常系、同一tenant reparent、soft-delete、invoice item contract付替え、legacy binding拒否、実H2 mapper/service/replay test、migration/H2/MySQL gateを追加 | SPEC_ADDRESSED（独立再Review待ち） |

code remediation `5c94367c` → `0618d983`をpush済み。実装検証はfocused/H2 44/44、MySQL 8/8、failure/error/skipなし、`git diff --check` PASS。docs trace commit後に最終remote Headを外部handoffで固定する。独立再Review受領まではB1 PASSへ昇格せず、B2/M/production enablement、実顧客credential、実provider送信、PR/mergeは行わない。

## B1追加P1-008 remediation（初回送信前primary binding、code `c2cbfb99`）

B1独立Implementation Reviewは、初回enqueue時およびworkerの外部HTTP直前に、delivery ledgerのprimary type/内部IDとsnapshotのopaque public IDを結合検証していないとしてFAIL（P0=0、P1=1、P2=0）を返した。F1/F2/A1、P1-006、P1-007は再オープンしない。以下はapproved B1 scope内で実装可能なremediationであり、状態は独立再ReviewまでSPEC_ADDRESSEDとする。

| Finding | 対応 | Status |
|---|---|---|
| 初回enqueueのprimary identity binding不足 | `IntegrationHubWebhookDeliveryBindingValidator`がclient bindingからprimary type/内部IDのHMAC opaque IDを再計算し、snapshot envelopeの`publicResourceId`とprimary DTO fieldの双方を検証する。`ApiDeliveryServiceImpl.enqueue`は保存前に検証し、任意文字列のpublic IDやtype/ID不一致を拒否する | SPEC_ADDRESSED（独立再Review待ち） |
| worker送信前のfail-closed不足 | `IntegrationHubWebhookDeliveryWorker`がsnapshot/envelope検証後、署名計算・外部HTTP前に同じvalidatorでledger/client bindingを再検証し、不一致を`PRIMARY_BINDING_INVALID`でFAILEDへ収束させ、transportを呼び出さない | SPEC_ADDRESSED（独立再Review待ち） |
| DuplicateKey収束時のidentity比較不足 | `DuplicateKeyException`再読込経路でpayload hashだけでなくprimary resource typeとprimary内部IDを同時比較し、同payload・別primaryおよびtype/ID不一致を拒否する | SPEC_ADDRESSED（独立再Review待ち） |
| evidence | `ApiDeliveryServiceTest` 7/7、`IntegrationHubWebhookDeliveryWorkerTest` 10/10、F1 retention H2 7/7、B1 focused/H2 failure/error/skipなし、MySQL 8/8。primary ID/type不一致、同payload別primary、worker transport未実行を追加検証 | SPEC_ADDRESSED（独立再Review待ち） |

code `c2cbfb99133d0df3f8d5eee285be340163747e31`をpush済み。docs trace commit後の固定remote Headを同じR-NF05へ独立再Implementation Reviewとしてhandoffする。B1 PASS受領までB2/M、production enablement、実顧客credential、実provider送信、PR/mergeは行わない。

## B1 independent Implementation Review closure

| Finding | Review result | Evidence |
|---|---|---|
| NF05-IMPL-B1-001〜008 | CLOSED_BY_REVIEW | fixed Head `f897d748cb93ade26c41d6ba4cb1a88efb29a29d`、P0/P1/P2=0/0/0。署名/envelope、admin replay authorization、primary/secondary/current membership、初回送信前binding、retention、fresh clock/CAS、実DB証跡を確認 |

## B2 implementation evidence（独立Implementation Review待ち）

固定Head `122c7c3bb5653eb788d58040c6defc816ff67013`でB2 approved scopeを実装した。これは独立ReviewのPASSを意味しない。

| Finding / contract | 対応 | Status |
|---|---|---|
| inbound authentication boundary | `POST /external-api/v1/webhooks/{provider}`をexisting HMAC chainへ接続し、provider/event ID、content type、strict JSON、allow-listを検証。raw bodyはmemoryのみ | SPEC_ADDRESSED（Review待ち） |
| duplicate / conflict / processing | client×provider×event ID unique、same hash duplicate、hash違い409、RECEIVED→PROCESSING→PROCESSED/DLQのclaim/terminal CAS、B2 local no-op | SPEC_ADDRESSED（Review待ち） |
| DLQ / replay authorization | ROLE_管理者＋`integration.webhook.replay`、derived operator ref、generation/reason、current client/subscription/permission/scope/membership再検証。元DLQを逆遷移しない | SPEC_ADDRESSED（Review待ち） |
| retention / safe admin UI | V135の独立replay metadata、FK `ON DELETE SET NULL`、AUDIT_METADATA_1Y bounded purge、safe projection/page。raw body/hash、snapshot、secret、PII非表示 | SPEC_ADDRESSED（Review待ち） |
| evidence | parser/route/migration contract、H2 real mapper、Linux Tomcat connector E2E、MySQL 8 Flyway smoke | SPEC_ADDRESSED（Review待ち） |

今回のB2実装でproduction receive enablement、実credential、実provider送信、business command/export、PR/mergeは行っていない。独立B2
Implementation Reviewの判定を受領するまで、B2をPASSへ昇格しない。

## B2 implementation review remediation

独立Reviewの固定Head `0514e00a1cd27fdedba8d15b5bc87d2fd02d706c` はP0=0、P1=4、P2=1でFAILだった。実装修正を
`cc468e4f`へ固定した。

| Finding | remediation evidence | Status |
|---|---|---|
| unknown provider / subscription外event | `IntegrationHubInboundProviderCatalog`、provider-aware active subscription query、receive permission/scope検証をINSERT前へ配置 | SPEC_ADDRESSED（独立再Review待ち） |
| replay current resource membership | `InboundEventBindingValidator`とresource scope mapperでopaque primary/secondary ID、tenant/legal、deleted/reparent、scope intersectionをreceipt/replay時に再検証 | SPEC_ADDRESSED（独立再Review待ち） |
| replay subject | 有効・非ロック`LoginUser`、ROLE_管理者、`integration.webhook.replay`を必須化し、operator referenceをprincipalから導出 | SPEC_ADDRESSED（独立再Review待ち） |
| internal DB ID | admin DTO、DOM、replay URLをopaque admin referenceへ置換 | SPEC_ADDRESSED（独立再Review待ち） |
| Content-Type prefix | strict parserで`application/json`と許可charsetだけを許可し、jsonp/combined/malformedを拒否 | SPEC_ADDRESSED（独立再Review待ち） |

検証はH2 focused 15 tests、MySQL 8 Flyway V136 smoke 2 tests。Windowsの実Tomcat connectorはOS loopback接続確立前に
errorとなったため、成功証跡へ算入しない。Linux実connectorで手動attributeなしのHTTP経路を独立再ReviewするまでB2は
IMPLEMENTATION_REVIEW_PENDINGとする。production receive enablement、実credential、実provider送信、PR/mergeは禁止する。
