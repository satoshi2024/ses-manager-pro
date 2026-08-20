# Review Ledger — ai-feedback-learning (S17)

## 1. 現行判定

| 項目 | 値 |
|---|---|
| spec | `ai-feedback-learning` |
| handbook | `v2.0` |
| state | `IN PROGRESS` |
| base | `9d2c229b6fe5033f1cc8275da116f65e98f2cd59`（S16 Head `07ae187c` を祖先に含む） |
| head | T109 未commit working tree |
| merge | unmerged |
| latest review | 未開始 |
| verdict | 実装中（自己PASSなし） |
| issue count | P0=0 / P1=0 / P2=0 / NOTE=0 |
| next action | T110 F1 DDL（V108）。独立Reviewは全task後 |

## 2. OPEN Issue Register

| issue ID | severity | AC | file:line | reproduction | impact | minimum fix | regression scope | state | fix commit | verified by |
|---|---|---|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — | — | — | — |

## 3. Closed/Deferred Issue

| issue ID | final state | root cause | fix commit | verification evidence | closed round | reopen condition |
|---|---|---|---|---|---|---|
| GATE-S17-G10-PROD | DEFERRED | 実provider DPA/region/署名は本番gate | — | T109がmock/rule既定を記録 | T109 | 発注者がDPAと署名を閉じたとき |

owner: 発注者 / security / HR / product owner。期限: 本番release前。T115 Mの本番PASSを阻害する。開発task T110〜T114は阻害しない。

## 4. 最新Review Packet

```text
- handbook version: v2.0
- spec/tasks: T109 完了（checkbox）。T110〜T115 未完了
- base/head/merge status: Base 07ae187c / Head 未commit / unmerged
- changed files by task: 下記 Task log
- requirements/acceptance trace: 下記 §5
- migration state: V108 未作成。common latest V107.3。dev V100。prod R__ のみ
- test evidence: AiG10AllowlistDocumentTest（L0）
- Demo evidence: G10推奨既定の文書記録。人間署名は GATE-S17-G10-PROD
- skipped/unverified: 実DPA、実Gemini送信、browser Demo（T109対象外）
- known issue IDs: GATE-S17-G10-PROD
- out-of-scope: 本番Java/SQL、V108、gateway実装
- rollback: T109文書とL0 testをrevert
- requested verdict: intermediate（T109のみ）
```

## 5. Requirements Trace

| requirement/AC | customer effect | implementation | automatic test | Demo | unverified | verdict |
|---|---|---|---|---|---|---|
| 前提 mock/rule | 実データが外部AIへ出ない | G10決定、`productionExternalSend=false` | `AiG10AllowlistDocumentTest` | 決定記録 | 実provider未使用 | T109 implemented |
| R4.1 allowlist/mask | 氏名・連絡先等が送信されない | `g10-pii-allowlist.md` §4-6 | 非交差assert | 文書 | T111でgateway実装 | T109 documented |
| R4.2 provider確認 | 送信項目・保存期間・region・opt-outが見える | §7 表 | provider retention assert | 文書 | DPA未締結 | T109 documented |
| R3.1 metric | version別採用率等が定義される | §8 | JSON metrics | 文書 | T113/T114で計算実装 | T109 documented |
| R3.3 segment | 少数・機微属性を出さない | min-segment=5、禁止属性非交差 | segment軸assert | 文書 | T114で非表示実装 | T109 documented |
| R5 canary | canaryがrequest/log/DBに出ない | canary文字列を予約 | markdownに固定 | 文書 | T111/T115で実行 | T109 reserved |
| design §5.2 禁止属性 | matching/segmentに使わない | 決め直しなし。allowlistが非交差 | L0 test | 文書 | — | T109 documented |

## 6. 横断契約

T109は本番schema/APIを変えない。T110以降は `design.md` 決定表と本allowlistに従う。

### 6.1 Scope consumer inventory

T109対象外。T114で作成する。

### 6.2 Temporal/NULL matrix

`design.md` §5.1 を採用。逸脱なし。未判断feedbackは却下ではない。

### 6.3 Transaction/cache matrix

T109対象外。

### 6.4 Migration matrix

T109はDDLなし。予約V108維持。

## 7. Test Evidence

| level | command | environment | tests | failures | errors | skipped | exit | commit | executor |
|---|---|---|---:|---:|---:|---:|---:|---|---|
| L0 | `mvn test -Dtest=AiG10AllowlistDocumentTest,SpecDispatchConsistencyTest` | JDK17 / 作業tree | 16 | 0 | 0 | 0 | 0 | 未commit | implementer |
| L0 | `git diff --check`（T109対象path） | 作業tree | — | 0 | 0 | 0 | 0 | 未commit | implementer |

次のL4 checkpoint: T115。

除外: 全量test、MySQL smoke、browser。理由: T109は文書/静的L0。昇格条件なし。

## 8. Demo Evidence

| Demo ID | role/data | viewport/environment | steps | expected | actual | evidence | verdict |
|---|---|---|---|---|---|---|---|
| T109-G10 | 主実装が推奨既定を記録 | spec文書 | allowlist・DPA表・metricを正本化 | mock/rule既定、禁止属性と非交差、provider別保存期間 | `g10-pii-allowlist.md` / `decision-log.md` | 本ledger | PASS（開発baseline） |
| GATE-S17-G10-PROD | security/HR/PO署名 | 本番前 | DPA・region・opt-out承認 | 実送信許可 | 未実施 | — | 本番gate |

## 9. Round履歴

未実施。

## 10. 転記用最終結論

```text
ai-feedback-learning / T109 only / handbook v2.0:
P0=0, P1=0, P2=0, NOTE=0。
必須test=L0 AllowlistDocument + git diff --check、skip=L4、Demo=G10文書記録、release gate=GATE-S17-G10-PROD。
判定=実装中（独立Review未開始）。
次task=T110 V108 DDL。次spec=開始しない。
```

## Task log

| task | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|
| T109 | 前提, R3.1, R3.3, R4.1, R4.2, R5 | `g10-pii-allowlist.md`, `g10-allowlist.json`, `design.md`（ポインタ）, `tasks.md`, `decision-log.md`, `spec-execution-ledger.md`, expansion `README.md`, `review-ledger.md`, `AiG10AllowlistDocumentTest.java` | L0 AllowlistDocument, `git diff --check` | G10推奨既定記録 | 未commit | 実DPA未取得。既存Gemini経路のdescription/fullName leakはT111まで残る。本番送信はG10で禁止 |
