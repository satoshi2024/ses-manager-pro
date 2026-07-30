# Implementation Plan — AI推薦フィードバック・評価ループ

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T109〜T114はL0〜L3の定向test・直接回帰、T115でL4全量を実行する。
> provider/PII/evaluationの対象fixtureをTask単位で行い、全adapter・全量安全性回帰はMへ集約する。

- [ ] 0. G10/use case/PII/metric確定
  - **Objective**: 0. G10/use case/PII/metric確定 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **成果物**: provider DPA、field allowlist、mask規則、保存期間、成功metric、禁止属性。
  - **Demo**: security/HR/product owner承認。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
  - **テスト要件**: 成果物を該当requirements ID、権限、境界値、失敗時挙動、後方互換の観点でレビューし、判断根拠を記録する。

- [ ] F1. version/run/item/feedback/outcome/evaluation DDL
  - **Objective**: F1. version/run/item/feedback/outcome/evaluation DDL を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: V80/V1/H2/smoke、legacy移行方針。
  - **テスト要件**: active一意、trace、tenant、保存期限。
  - **Demo**: F1. version/run/item/feedback/outcome/evaluation DDL の成果を対象利用者またはレビュー担当者へ提示し、受入条件との対応を確認する。

- [ ] F2. AiExecutionGateway/PII mask
  - **Objective**: F2. AiExecutionGateway/PII mask を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: 全AI呼出をgatewayへ、schema validation、raw prompt停止。
  - **テスト要件**: canary/prompt injection/provider error/log capture。
  - **Demo**: 送信payload inspectionでPII 0。

- [ ] B1. feedback/outcome連携
  - **Objective**: B1. feedback/outcome連携 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: matching画面採否、proposal/contract event、冪等trace。
  - **テスト要件**: 採用/却下/面談/成約/失注/重複event。
  - **Demo**: 推薦から成約までtimeline。

- [ ] B2. offline evaluation/version promotion
  - **Objective**: B2. offline evaluation/version promotion を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: dataset version、baseline比較、threshold、shadow/rollback。
  - **テスト要件**: metric/gate/rollback/過去不変。
  - **Demo**: 基準未達version拒否→ruleへrollback。

- [ ] A1. evaluation dashboard
  - **Objective**: A1. evaluation dashboard を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **実装ガイダンス**: funnel/reason/latency/cost/segment privacy。
  - **テスト要件**: scope/少数非表示/金額単位。
  - **Demo**: 2version比較。

- [ ] M. 回帰/安全性
  - **Objective**: M. 回帰/安全性 を完了し、requirementsに定義した利用者効果を検証可能にする。
  - **テスト要件**: 全test/MySQL、mock/rule/Gemini adapter、PII scan。
  - **Demo**: mock既定の既存機能回帰と実provider opt-in。
  - **実装ガイダンス**: requirements.md、design.md、全体shared-standards.mdの境界と既存資産再利用規約に従い、未決事項を黙って補完しない。
