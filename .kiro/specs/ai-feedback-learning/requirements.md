# Requirements — AI推薦フィードバック・評価ループ

## 前提

- AIは提案を生成するだけで、契約/提案/メール送信/人事判断を自動確定しない。
- mock/rule providerを既定として維持し、G10/DPA/PII方針確定前に実データを外部AIへ送らない。
- 雇用差別につながる属性をmatching特徴量へ使わない。

## R1. 実行記録

1. THE AI/rule推薦 SHALL use case、provider、model、prompt/rule version、input feature hash、候補、score、説明、実行者、時刻を記録する。
2. THE 記録 SHALL raw PII promptを既定保存せず、マスク済summaryとhashを保持する。
3. THE model/prompt/rule SHALL version registryを持ち、同じversionを再現可能にする。

## R2. feedback/outcome

1. THE ユーザー SHALL 推薦ごとに採用/却下/保留と理由category/commentを登録できる。
2. THE システム SHALL 提案作成、面談、成約、失注、契約継続、早期離場をoutcomeとして推薦へ関連付ける。
3. THE outcome SHALL 相関でありAIの功績/原因と断定しない。
4. THE 人手修正 SHALL 推薦値と最終値の差分を記録する。

## R3. evaluation

1. THE 管理者 SHALL version別採用率、面談率、成約率、precision@k相当、理由分布、latency、costを表示する。
2. THE offline evaluation SHALL 固定匿名datasetで新versionと現行versionを比較し、基準未達なら有効化を拒否する。
3. THE segment SHALL skill/単価/勤務地等を用いるが、少数groupや機微属性を表示しない。
4. THE rollback SHALL active versionを即時に前version/mock/ruleへ戻せる。

## R4. privacy/安全

1. THE AI gateway SHALL allowlist fieldだけを外部送信し、氏名、連絡先、住所、口座、自由記述PIIをmaskする。
2. THE 管理者 SHALL providerごとの送信項目、保存期間、region、opt-outを確認できる。
3. THE prompt injection SHALL 取込原文を命令ではなくuntrusted dataとして分離し、tool/action権限を与えない。

## R5. 受入

- 推薦→採用→提案→面談→成約が同一traceで追跡できる。
- version rollback後の新実行だけが旧versionを使い、過去記録は不変。
- PII canaryがprovider request/log/DB summaryに出ない。

