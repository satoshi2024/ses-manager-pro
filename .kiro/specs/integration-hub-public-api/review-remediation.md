# NF-05 Review Remediation（Task 0R）

## 判定の扱い

この文書はReview指摘のうち、実装AIがdocs/architectureで解消可能な範囲を追跡する。
SPEC_ADDRESSEDは仕様上の不足を補ったことを示すだけで、実装PASS、security PASS、公開許可を意味しない。
Owner承認済みのscopeはF1 persistence基盤までであり、Plan ReviewのPLAN PASSと実装Reviewは別ゲートである。
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
| 実装・検証証拠なし | P1 | F1 approved persistence基盤を`a7654b44`で実装し、F1 targeted testとMySQL migration smokeの証跡を追加 | IMPLEMENTED_PENDING_REVIEW | 独立Implementation Review、F2〜Mの実装・検証 |
| metrics cardinality不足 | P2 | finite label set、禁止label、scrape/cardinality test、safe trace/audit方針を追加 | SPEC_ADDRESSED | F2/Mの実装・scrape証拠 |
| payload retention不足 | P2 | digest/hash/allow-list snapshot、承認retention、legal hold、purge/restore testを追加 | SPEC_ADDRESSED | F1/B1/B2/M実装 |
| handoff commit系列不足 | P2 | Review Head 6e0f5067を基点として記録し、remediation commitと最終Headをcommit series＋外部handoffで追跡 | SPEC_ADDRESSED | remediation push後の最終Head通知 |
| R-NF05 rate/quota保存キー不一致 | P1 | t_api_usage_bucketをclient×scope×tenant×route templateの論理キーへ固定。IP/raw pathを除外し、minute/day/burstのDB unique・条件付きincrementを定義 | SPEC_ADDRESSED | R-NF05再ReviewでPLAN PASS |
| R-NF05 nonce replay ledger不足 | P1 | t_api_nonce_replay、client+nonce hash atomic unique、rotation跨ぎ再利用拒否、TTL/purge、raw nonce非永続化を定義 | SPEC_ADDRESSED | R-NF05再ReviewでPLAN PASS |
| R-NF05 第二outbox/t_api_delivery方針不明 | P1 | 第二の汎用outboxを禁止し、既存notification outbox/Accounting IntegrationJobをreuse・二重書込みせず、t_api_deliveryをNF-05専用ledgerとして分離 | SPEC_ADDRESSED | R-NF05再ReviewでPLAN PASS |
| R-NF05 retention/legal hold契約不足 | P1 | retention class/expiry、t_api_retention_hold、lock/CAS競合、active lease、部分失敗、restore epoch後全件再評価を保存モデルへ固定 | SPEC_ADDRESSED | R-NF05再ReviewでPLAN PASS |
| R-NF05 burst algorithm不足 | P1 | capacity 20、初期token 20、3秒ごとに1 token refill、minute/dayと同一transactionのatomic predicate、clock rollback、Retry-Afterを固定 | SPEC_ADDRESSED | R-NF05再ReviewでPLAN PASS |
| R-NF05 canonical state/terminal mapping不足 | P1 | idempotency/delivery/inboundのcanonical enum、遷移、非terminal/terminal、30/90日classと起算点、alias/逆遷移拒否を固定 | SPEC_ADDRESSED | R-NF05再ReviewでPLAN PASS |

## F1 Implementation Review remediation

初回Implementation Reviewは固定Head `b420911b63177763544edd1e02d663bf528d9dc1` に対してFAIL（P0=0、
P1=7、P2=2）だった。以下は実装・テストへ反映したが、独立再Reviewを受けるまでIMPLEMENTATION PASSではない。

| Finding | 対応 | 状態 | 証跡 / 残る条件 |
|---|---|---|---|
| P1-001 snapshot保存境界・generic CRUD迂回 | typed ExternalDtoSnapshotの構造allow-list、payload/canonicalPayloadのfield-specific object、changedFieldNamesのbounded array、用途別service API。IService/ServiceImpl継承を除去 | IMPLEMENTED_PENDING_REVIEW | `a184c1f4`、`5a2a0231`、H2 targeted。再Review、M scan |
| P1-002 inbound DuplicateKey conflict | provider event FOR UPDATE後にCONFLICTをversion CAS保存 | IMPLEMENTED_PENDING_REVIEW | InboundEventServiceTest 2件、`5a2a0231`の実MySQL inbound race。再Review |
| P1-003 purge starvation | active hold除外、hold acquire/release reset、checkpoint→target→holdの共通lock順序、keyset末尾reset | IMPLEMENTED_PENDING_REVIEW | IntegrationHubF1RetentionH2Testのhold/lease-cursor境界、`5a2a0231`のMySQL hold/purge race。再Review |
| P1-004 purge lease/version CAS | lock後のdelete predicateへversion、terminal、expiry、lease token/expiryのstrict NULL組合せを含める | IMPLEMENTED_PENDING_REVIEW | H2 + `5a2a0231`のIntegrationHubF1MySqlConcurrencyTest |
| P1-005 idempotency CONFLICT | mismatchを固定409/90日retentionへ永続化後に拒否 | IMPLEMENTED_PENDING_REVIEW | ApiIdempotencyServiceTest、mapper CAS。再Review |
| P1-006 delivery result CAS | provider key、payload hash、lease、row version、generation由来キーをCAS要求し、`d476614e`でSQL predicateにもdelivery_generationを追加 | IMPLEMENTED_PENDING_REVIEW | H2 + MySQL CAS test。B1外部provider実装は未着手 |
| P1-007 F1 evidence不足 | H2 31 tests、実service/mapperを使うMySQL multi-connection 5 tests、shard inventoryを追加 | IMPLEMENTED_PENDING_REVIEW | `5a2a0231`、全境界網羅、M/security/load/recoveryは未完了 |
| P2-001 credential overlap NULL | non-null overlap_untilの将来期限だけ有効 | IMPLEMENTED_PENDING_REVIEW | CredentialVersionServiceTest |
| P2-002 raw route template | candidate 11 fixed templates以外を拒否 | IMPLEMENTED_PENDING_REVIEW | ApiUsageBucketServiceTest |

## F1 Implementation Review follow-up

follow-up独立Implementation Reviewは固定Head `dff90b3961b647035436abd378a352b1fa000dd1` に対して
FAIL（P0=0、P1=4、P2=0）だった。下記はapproved F1 scope内で`5a2a023178433882bc1c5dcf92e19b5ecfa19db6`
へ反映したremediationであり、再Review受領までIMPLEMENTATION PASSではない。

| Finding | 対応 | 状態 | 証跡 / 残る条件 |
|---|---|---|---|
| P1-FU-001 raw body/PII nested snapshot | `ExternalDtoSnapshot`を用途別allow-listのstructured objectへ限定し、public ID、date/date-time、status/resultCode、signature/processing status、error codeをfield固有pattern/enumで検証。changedFieldNames/skillTagCode、nested深度もboundedにし、許可field内のraw JSON/provider body scalarを拒否するnegative testを追加 | IMPLEMENTED_PENDING_REVIEW | `5a2a0231`、`96d6801c`、ApiDeliveryServiceTest。再Review、M scan |
| P1-FU-002 lease片側NULLのpurge | candidate queryとdelete CASを「両方NULL」または「両方non-NULLかつexpiry<=now」に限定し、期限欠落rowを実MySQLで検証 | CLOSED_BY_REVIEW | `5a2a0231`、IntegrationHubF1MySqlConcurrencyTest 5/5。独立再Reviewでクローズ |
| P1-FU-003 hold/purge lock順序 | hold acquire/release/purgeをcheckpoint→target→holdへ統一し、checkpoint初期化もupsert-firstへ変更。実MySQL hold/purge raceを追加 | CLOSED_BY_REVIEW | `5a2a0231`、IntegrationHubF1RetentionH2Test/MySQL。独立再Reviewでクローズ |
| P1-FU-004 MySQL multi-connection evidence | `IntegrationHubF1MySqlConcurrencyTest`をSpring経由の5テストへ拡張し、usage unique初期化、delivery CAS、hold/purge、malformed lease、inbound duplicateを実証 | CLOSED_BY_REVIEW | `5a2a0231`、5/5 PASS。独立再Reviewでクローズ。M/security/load/recoveryは未完了 |

再Review（`f4e3bf7f0c0a8c85d0ca22294471546313e5df1f`）はP0=0、P1=1、P2=0だった。FU-002〜004は独立検証でクローズ、
FU-001のみ許可nested scalarの迂回が残ったため、`96d6801c`でfield固有validatorとnegative testを追加した。現状態は
IMPLEMENTED_PENDING_REVIEWであり、自己判定でIMPLEMENTATION PASSへ変更しない。

今回のremediationでoutbox/CAS、candidate契約、metrics、retentionの仕様とF1実装境界を同期した。follow-upではsnapshot形状、
lease fail-closed、lock順序、MySQL競合証跡を追加したが、public endpoint、
外部送信、F2/A1/A2/B1/B2/Mは未着手であり、レビュー結果を自己PASSへ変更しない。

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
`a184c1f4`、追加CAS修正は`d476614e`、follow-up remediationは`5a2a0231`であるが、独立Implementation Review PASSは未取得である。public endpoint、外部送信、A1/A2/B1/B2、production enablement、
command、exportは引き続きこのimplementation scope外である。

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
- F1 implementation commit: a7654b44、F1 targeted suite 23 tests PASS、MySQL V129 smoke PASS
- F1 implementation remediation commit: a184c1f4、F1 H2 targeted suite 31 tests PASS、MySQL multi-connection concurrency 3 tests PASS（当時点の証跡）
- delivery CAS generation predicate correction: d476614e、ApiDeliveryServiceTest/MySQL CAS test PASS
- 初回Implementation Review: b420911b63177763544edd1e02d663bf528d9dc1、FAIL（P0=0、P1=7、P2=2）
- follow-up Implementation Review: dff90b3961b647035436abd378a352b1fa000dd1、FAIL（P0=0、P1=4、P2=0）
- follow-up remediation: 5a2a023178433882bc1c5dcf92e19b5ecfa19db6、H2 31 tests / MySQL 5 tests PASS
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
