# NF06 / NF07 Plan Review 第2回

- Reviewer: `astra_review_baseline` / gpt-6-astra。read-only、試験なし、総括による日本語転記。
- NF06 Head: `c56282626889ff30c99fadab1dbda910409af39a`
- NF07案 Head: control `777db53d`
- 両方 **CONDITIONAL PASS**。実source・Legal/Privacy・production承認なし。

## NF06

前回3件の核心（5 adapter契約、cross-job registry/row原子性、restartでbusiness結果不変）は概ね補強済み。
ただし旧specと実行契約の整合が未完了である。

| 優先度 | 位置 | 補正条件 |
|---|---|---|
| P1 | design.md:114 | mapping hashをjob identityに含み別jobを許す旧記述を除去。source identity唯一＋mapping不一致409に統一 |
| P1 | tasks.md:125、design crash window | chunk単位でcheckpoint/counterまで同transactionとする旧記述を除去。row target/registry/id-map/result/audit/outboxを原子境界、checkpoint/counterを再構築projectionとする |
| P2 | execution-contract.md:45、design.md:259 | validated_accepted_totalを不変母数、apply_pendingを未処理件数とし、phaseをまたぐaccepted_pendingの曖昧さを除く。physical=source+empty_skippedも正本に明記 |

lease検証は同一transactionのlock/CASによりtakeoverと排他にする。通常SELECTを前後2回行うだけでは不可。
上記補正の技術Plan PASS後は、実legacy/Ownerの未決定とは分離して全synthetic W1へ進める。
現時点ではW0文書、schema manifest、fixture、read-only validator契約まで。

## NF07

隔離temporary環境/test-only authority、policy/caseの署名分離、独立journal/watermarkの方向は補強済み。
処分worker開始前にfeature requirements/design/tasksへ以下を同期する。

| 優先度 | 問題 | 必須契約・試験 |
|---|---|---|
| P1 | 外部実行とholdの線形化点なし | 共通fence下のdurable dispatch authorizationを明示。holdがその前なら送信禁止、後ならin-flightとして記録し後続を停止。実送信直前まで取消可能と主張するにはprovider側fencing/cancelが必要。認可commit〜HTTP間にholdを挿入する試験 |
| P1 | receiptのみでは外部成功後journal前crashを検出不能 | 不可逆操作より先にintent/action/idempotency/target version hashを独立journalへdurable保存しACKを得る。intentのみはrestore後quarantineで確認/冪等回復。holdとjournalの順序も明示し、外部成功〜receipt前とlocal hold〜journal ACK前のcrashを試験 |

型、純状態機械、署名interface、拒否、隔離harness、synthetic fixtureは準備可能。
worker/処分/journal復旧は追加protocolと遷移表の技術Review後に実装する。
synthetic基盤のPASSをDG07/NF07全体のPASSへ変換しない。
