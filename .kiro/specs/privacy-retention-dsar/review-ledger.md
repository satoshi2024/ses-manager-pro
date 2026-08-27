# review ledger

このledgerは、独立Reviewの結果を実装AIが自己承認するためのものではない。Review担当者がremote Headを固定して追記する。

| item | status | evidence / blocker | reviewer |
|---|---|---|---|
| PLAN | PENDING | 実装branchはReview verdictを記録しない。approved scope/owner/Base branch/SHA、NF-07/DG-07の外部証跡を独立Review側で確認する | 未割当 |
| IMPLEMENTATION | PENDING | 実装branchはReview verdictを記録しない。offline evidenceは別packetにあり、独立Review側で判定する | 未割当 |
| DG-07 | BLOCKED | retention/legal/HR/tax owner、hold authority/release、二者承認、request deadline未確定 | 未割当 |
| external expert gate | BLOCKED | legal documentのunclassified retention、外部専門家承認未完 | 未割当 |
| internal responsible gate | BLOCKED | Privacy ownerが`<OWNER>`のまま | 未割当 |
| backup/recovery | BLOCKED | production/representative restore evidence未完。DB+binary same-time検証未完 | 未割当 |
| enterprise identity/security | BLOCKED | external IdP/MFA/break-glass/owner gate未完 | 未割当 |
| audit | BLOCKED | append-only/technical loggingはinventory済みだが、法的保持/DSAR表示policyは未確定 | 未割当 |
| recruiting retention | BLOCKED | candidate/resume/rejected/activity retention未確定 | 未割当 |
| AI G10 | BLOCKED | external provider DPA/region/training opt-out/owner gate未完。external send false | 未割当 |

## append-only notes

- 2026-08-27: main implementation AIはDG-07/外部/社内gate未完を確認し、0/D0で停止する方針を採用。
- 2026-08-27: このincrementで法的判断、処分許可、本人同定、保持期間の承認は行っていない。
- 2026-08-27: 実装AIは外部Reviewの判定・reviewer名・finding/verdictをこのledgerへ記録しない。external Review側の証跡で、reviewed Head、reviewer/task ID、timestamp、finding ID、verdictを相互にbindする。
