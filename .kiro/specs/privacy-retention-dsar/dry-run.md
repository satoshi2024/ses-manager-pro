# no-write dry-run 契約と実行結果

## 1. 安全境界

`tools/privacy-retention-dsar/read-only-dry-run.ps1` は、redacted JSON fixtureをローカルで分類するだけである。

- DB/JDBC、filesystemの探索・削除、backup/replica、HTTP、外部AI、CloudSign、freee、mail providerを呼び出さない。
- `-Apply`、`-Delete`、`-Anonymize`、`-Restrict`、connection string、URL、output file optionを持たない。
- `asOf`を入力必須にし、現在時刻による結果変動を避ける。
- raw PIIを示すproperty name（email、phone、address、name、body、content、raw、prompt、token、secret、password等）がfixtureにあればfailする。`dataElementId`などの識別子は値ではないため許可する。
- outputはstdoutのJSONだけ。candidateKey/dataElementId/reason/status/providerCallCountを返し、入力のraw値をechoしない。

`CANDIDATE` は処分許可・法的判断・自動実行ではない。approved policyと期限、identity、scope、hold/audit/business blockerが入力上クリアに見える候補を表示するだけで、実際のactionは存在しない。

## 2. 入力の判定要件

各recordに以下を要求する。

| 入力 | `CANDIDATE`条件 | `BLOCKED`条件 | `UNKNOWN`条件 |
|---|---|---|---|
| identityResolution | `VERIFIED` | `AMBIGUOUS` / same-name unresolved | 欠落/`UNVERIFIED` |
| scopeStatus/providerScope | `IN_SCOPE` | `OUT_OF_SCOPE` | 欠落 |
| owner/purpose/trigger | `CONFIRMED` | — | 欠落/`UNKNOWN` |
| policyState | `APPROVED` | — | `PROVISIONAL`/`UNKNOWN` |
| retentionUntil | `asOf`以前 | — | 欠落、形式不正、期限未到来 |
| holdStatus | `NONE` | `ACTIVE` | `UNKNOWN` |
| legalRetentionStatus | `CLEAR` | `BLOCKED` | `UNKNOWN` |
| auditStatus | `CLEAR` | `PROTECTED` | `UNKNOWN` |
| activeBusinessBlocker | false | true | 欠落/不正 |
| dispositionMethod | 非空の既知ラベル | — | 欠落 |

既知blockerが一つでもある場合は、未確定項目が同時にあっても`BLOCKED`を優先し、全理由を出す。blockerがなく必須状態が未確定なら`UNKNOWN`とする。

## 3. fixtureケース

`dry-run-fixture.json` は次を含む（raw PIIなし）。

| case | 想定status | 確認対象 |
|---|---|---|
| `fixture-candidate-001` | CANDIDATE | verified、in-scope、approved、期限到来、hold/audit/business blockerなし |
| `fixture-hold-001` | BLOCKED | active legal hold。policyがapprovedでも候補化しない |
| `fixture-unknown-001` | UNKNOWN | owner/purpose/trigger/policy/retention/hold/auditが未確定 |
| `fixture-same-name-001` | BLOCKED | 同姓同名/複数候補。自動統合せずhuman resolution待ち |
| `fixture-scope-out-001` | BLOCKED | scope外provider。providerCallCount=0を確認 |
| `fixture-audit-001` | BLOCKED | immutable audit protection |
| `fixture-legal-retention-001` | BLOCKED | 法定保存のblocker |
| `fixture-business-001` | BLOCKED | active business blocker |
| `fixture-not-due-001` | UNKNOWN | 期限未到来。処分候補ではない |

## 4. 実行記録

実行コマンド:

```powershell
pwsh -NoProfile -File .\tools\privacy-retention-dsar\read-only-dry-run.ps1 `
  -InputPath .\tools\privacy-retention-dsar\dry-run-fixture.json
```

期待結果:

```text
exit code: 0
summary.candidate: 1
summary.blocked: 6
summary.unknown: 2
summary.providerCallCount: 0
summary.writeCount: 0
```

fixtureのSHA-256を実行前後で比較する。scriptはsource fileを更新しないため、hashは一致しなければならない。実行結果はcommit時に `completion-matrix.md` へ記録する。
