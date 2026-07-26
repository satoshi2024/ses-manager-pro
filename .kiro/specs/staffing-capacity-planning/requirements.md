# Requirements — 要員配置・需給計画

## R1. 募集枠/ポジション

1. THE 案件 SHALL required countだけでなく、役割、必須/歓迎skill、単価帯、開始/終了、勤務地、稼働率を持つ複数positionを管理する。
2. THE position SHALL 募集中→候補選定→充足/保留/取消の状態機械を持つ。
3. THE 提案/契約 SHALL positionへ紐付き、充足人数を自動計算する。

## R2. 配置計画

1. THE システム SHALL 要員の期間別allocation（案件/社内/待機、配賦率）を計画できる。
2. THE 同一期間の合計 SHALL 100%超を原則拒否し、例外は理由/承認を必須にする。
3. THE 実契約 SHALL actual allocationとして表示し、計画と実績を区別する。
4. THE 休暇、退職予定、契約終了、更新decision、available date SHALL capacityへ反映する。

## R3. 需給/シナリオ

1. THE システム SHALL 月別にskill/role/location別需要、供給、不足、余剰、bench costを表示する。
2. THE manager SHALL 仮配置scenarioを作り、本データを変更せずKPI差を比較できる。
3. THE scenario SHALL 保存者と共有範囲を持ち、契約/提案を自動作成しない。

## R4. 推薦連携

1. THE position SHALL rule/AI matchingの入力となるが、推薦採用は人が確定する。
2. THE 推薦 SHALL skill、時期、単価、勤務地、配賦率競合の説明を返す。

## R5. 受入

- 兼務50%+50%を許可、60%+50%を警告/拒否。
- 退職/休暇/更新済契約をcapacityへ正しく反映。
- scenario変更が実契約/提案へ影響しない。

