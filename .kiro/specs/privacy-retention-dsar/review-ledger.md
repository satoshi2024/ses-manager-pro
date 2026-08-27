# review ledger

このledgerは、独立Reviewの結果を実装AIが自己承認するためのものではない。Review担当者がremote Headを固定して追記する。

| item | status | evidence / blocker | reviewer |
|---|---|---|---|
| PLAN | PENDING | approved scope/owner/base placeholders未置換。NF-07 CANDIDATE、DG-07未完 | 未割当 |
| IMPLEMENTATION | PENDING | offline inventory/dry-runのみ。削除/匿名化/provider/migrationなし | 未割当 |
| DG-07 | BLOCKED | retention/legal/HR/tax owner、hold authority/release、二者承認、request deadline未確定 | 未割当 |
| external expert gate | BLOCKED | legal documentのunclassified retention、外部専門家承認未完 | 未割当 |
| internal responsible gate | BLOCKED | Privacy ownerが`<OWNER>`のまま | 未割当 |
| backup/recovery | BLOCKED | production/representative restore evidence未完。DB+binary same-time検証未完 | 未割当 |
| enterprise identity/security | BLOCKED | external IdP/MFA/break-glass/owner gate未完 | 未割当 |
| audit | CONDITIONAL | append-only/technical loggingはinventory済み。法的保持/DSAR表示policyは未確定 | 未割当 |
| recruiting retention | BLOCKED | candidate/resume/rejected/activity retention未確定 | 未割当 |
| AI G10 | BLOCKED | external provider DPA/region/training opt-out/owner gate未完。external send false | 未割当 |

## append-only notes

- 2026-08-27: main implementation AIはDG-07/外部/社内gate未完を確認し、0/D0で停止する方針を採用。
- 2026-08-27: このincrementで法的判断、処分許可、本人同定、保持期間の承認は行っていない。
