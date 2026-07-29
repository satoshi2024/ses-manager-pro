# Review Ledger標準template v2.0

本templateは各specの`review-ledger.md`先頭へ適用する。既存の過去記録は削除せず、本sectionより下へappend-onlyで保持する。

## 1. 現行判定

| 項目 | 値 |
|---|---|
| spec | `<spec>` |
| handbook | `v2.0` |
| state | `IN PROGRESS / REVIEW / FIX / PASS / CONDITIONAL PASS` |
| base | `<commit>` |
| head | `<commit>` |
| merge | `unmerged / merged <commit>` |
| latest review | `Rxx round n / date` |
| verdict | `PASS / CONDITIONAL PASS / FAIL / NOT REVIEWABLE` |
| issue count | `P0=n / P1=n / P2=n / NOTE=n` |
| next action | `<one concrete action>` |

古いroundにPASSがあっても、本表がFAIL/FIXなら古いPASSを現行判断に使わない。

## 2. OPEN Issue Register

| issue ID | severity | AC | file:line | reproduction | impact | minimum fix | regression scope | state | fix commit | verified by |
|---|---|---|---|---|---|---|---|---|---|---|
| `<spec>-R01-P1-001` | P1 | `R1.AC2` | `path:line` | role/data/time | customer impact | minimum contract | files/tests | OPEN | — | — |

stateは`OPEN / FIXED_BY_IMPLEMENTER / VERIFIED_CLOSED / REJECTED / DEFERRED`だけを使う。
VERIFIED_CLOSED行は次回更新時にClosed表へ移し、IDを再利用しない。

## 3. Closed/Deferred Issue

| issue ID | final state | root cause | fix commit | verification evidence | closed round | reopen condition |
|---|---|---|---|---|---|---|

P2/NOTEのDEFERREDにはownerと期限または対象spec/backlogを記載する。

## 4. 最新Review Packet

```text
- handbook version:
- spec/tasks:
- base/head/merge status:
- changed files by task:
- requirements/AC trace:
- migration state:
- test evidence:
- Demo evidence:
- skipped/unverified:
- known issue IDs:
- out-of-scope:
- rollback:
- requested verdict:
```

## 5. Requirements Trace

| requirement/AC | customer effect | implementation | automatic test | Demo | unverified | verdict |
|---|---|---|---|---|---|---|

`implemented`だけの判定は禁止し、testとDemoを別列で示す。

## 6. 横断契約

### 6.1 Scope consumer inventory

| consumer | endpoint/job | population source | DataScope | organization | tenant | empty-set | test |
|---|---|---|---|---|---|---|---|

### 6.2 Temporal/NULL matrix

| field/concept | current | history | snapshot | explicit NULL | missing history | asOf rule | boundary test |
|---|---|---|---|---|---|---|---|

### 6.3 Transaction/cache matrix

| mutation | CAS/UNIQUE | transaction | cache event | commit | rollback | concurrent test |
|---|---|---|---|---|---|---|

### 6.4 Migration matrix

| shape | source version | command/test | assertions | result | commit |
|---|---|---|---|---|---|
| fresh | V1 | | | | |
| legacy | published latest | | | | |
| partial | object exists/missing/old | | | | |
| backfill | null/orphan/duplicate/historyless | | | | |
| repair | interrupted | | | | |

## 7. Test Evidence

S03〜S17では各行にtest level（L0〜L5）を記載し、通常Taskは除外suiteと次のL4 checkpointを残す。

| level | command | environment | tests | failures | errors | skipped | exit | commit | executor |
|---|---|---|---:|---:|---:|---:|---:|---|---|

skipは別表でtest名、理由、blocker、release gate、owner、期限を記録する。

## 8. Demo Evidence

| Demo ID | role/data | viewport/environment | steps | expected | actual | evidence | verdict |
|---|---|---|---|---|---|---|---|

## 9. Round履歴

各roundは次の順でappendする。

```text
### Round n — date — reviewer
- base/head:
- scope: full / delta
- reviewed issue IDs:
- new issue IDs:
- independently executed tests:
- verdict:
- ledger/central synchronization:
```

Round 2以降で新規P0/P1を出す場合は、`original headでの見落とし`または`fix deltaで導入`を必ず記載する。

## 10. 転記用最終結論

```text
<spec> / merge head <commit> / handbook v2.0:
P0=<n>, P1=<n>, P2=<n>, NOTE=<n>。
必須test=<result>、skip=<IDs>、Demo=<result>、release gate=<IDs>。
判定=<PASS|CONDITIONAL PASS|FAIL|NOT REVIEWABLE>。
次spec=<開始可|開始不可>。理由=<one sentence>。
```
