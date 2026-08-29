# NF-05 Review Remediation（Task 0R）

## 判定の扱い

この文書はReview指摘のうち、実装AIがdocs/architectureで解消可能な範囲を追跡する。
SPEC_ADDRESSEDは仕様上の不足を補ったことを示すだけで、実装PASS、security PASS、公開許可を意味しない。
DG-05、approved scope、Owner、Baseの承認はOWNER_GATEとして残す。

## Finding対応表

| Finding | 種別 | 対応 | 状態 | 残る条件 |
|---|---|---|---|---|
| DG-05 / scope / Owner / Base / auth / SLA / field | P1 | review-ledger、requirements、design、tasksへ未承認gateを明記 | OWNER_GATE | repository外の承認記録 |
| remote Headなし | P1 | Discovery seriesをorigin/codex/integration-hub-public-apiへpushし、local/remote一致を確認 | SPEC_ADDRESSED | remediation後の最終Headを再固定 |
| outbox insert after-commit | P1 | 業務stateとoutbox rowを同一DB transactionでatomic commitする設計へ修正。claim/HTTP/CASを分離 | SPEC_ADDRESSED | F1/B1実装とcrash/stale/replay test |
| 外部契約なし | P1 | 非公開・未承認のopenapi-candidate.yamlを追加。read-only allow-list、status/error/cursor/securityを固定 | SPEC_ADDRESSED | Ownerのresource/field/SLA承認、A1実装 |
| 実装・検証証拠なし | P1 | F1〜Mを未着手のまま維持。Task 0Rのdocs-only検証だけを記録 | OPEN | F1〜Mの実装・独立test |
| metrics cardinality不足 | P2 | finite label set、禁止label、scrape/cardinality test、safe trace/audit方針を追加 | SPEC_ADDRESSED | F2/Mの実装・scrape証拠 |
| payload retention不足 | P2 | digest/hash/allow-list snapshot、candidate retention、legal hold、purge/restore testを追加 | SPEC_ADDRESSED | Owner承認、F1/B1/B2/M実装 |
| handoff commit系列不足 | P2 | Review Head 6e0f5067を基点として記録し、remediation commitと最終Headをcommit series＋外部handoffで追跡 | SPEC_ADDRESSED | remediation push後の最終Head通知 |

## Task 0R scope

完了範囲は以下のdocs-only変更に限定する。

- atomic outboxの原子commit、claim lease、外部HTTP、result CAS、crash/stale/replay要件。
- 非公開OpenAPI candidateのversion namespace、dedicated security boundary、read-only DTO allow-list、
  deny-list、stable error、correlation、cursor binding、default-deny command/export。
- metrics labelを有限集合へ限定し、client/correlation/request/resource/user/IP/provider IDを禁止。
- idempotency/inbound/outboundのraw secret/PII/provider body非永続化、succeeded 30日、failed/DLQ 90日、
  audit metadata 1年のcandidate、legal hold、purge/restore要件。
- requirements、design、tasks、completion matrix、review ledgerのtrace更新。

## 未解消のOwner Gate

承認されるまで、候補OpenAPIを公開せず、認証方式・provider・rate/quota・IP boundary・SLA・version retirement・
usage/billing・field/resource/command・webhook signature/retry/DLQ retentionを実装上の既定値にしない。

## Handoff checkpoint

- Review baseline Head: 6e0f5067d9a6509775225278cc0dcfdc4d47643f
- Task 0R remediation commit: 48037c923224f684968dbaf3410cdb37307ed100
- Task 0R-D delta remediation commit: 11ee82c15a5cdf8f961b2a2d0518a52d81f4de71
- Final remote Head: この文書を含む最終handoff commitの外部通知で固定する。自己参照hashは記録しない。

## Task 0R delta対応

| Delta finding | 対応 | 状態 |
|---|---|---|
| engineer-availability countがinventoryを越える | candidate OpenAPIからcount pathを削除。inventoryのlist/detailと一致 | SPEC_ADDRESSED |
| client指定asOfがdesignと矛盾 | 全query parameterからasOfを削除。server受信時刻をresponse/cursorへ固定する候補に統一 | SPEC_ADDRESSED |
| HTTP statusとerror codeの未固定 | statusごとの専用error schemaとcode enum、status/code map、scope外detailの404収束候補を追加 | SPEC_ADDRESSED |
| 成功/error responseのcorrelation header不統一 | 全GET success responseと共通error responseへX-Correlation-IDを追加 | SPEC_ADDRESSED |
| DG-05、scope、Owner、Base、auth、SLA、field inventory | 承認記録なしのまま維持 | OWNER_GATE |

Task 0R-Dもdocs-onlyであり、OpenAPI implementation、contract test、security test、F1〜MをPASS扱いにしない。
