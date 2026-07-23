# Requirements — AI提案文生成＋実マッチング接続（FR-02）

## Introduction

親: `.kiro/roadmap/2026-07-24-ses-feature-roadmap.md`（FR-02）。

`AiMatchingServiceImpl` は現在モック（"大手金融…"固定データ）。一方 `Proposal` には `aiMatchScore`／`matchReason`／`proposalEmailText` の**受け皿カラムが既に存在するのに未活用**。本機能は (1) マッチングを `ai.provider=gemini` 経路で実接続し、(2) 要員×案件から**マッチ理由・セールスポイント・提案メール下書き**をAIで生成して提案に保存する。営業の「提案を書く時間」を消す。

前提: A8-01 のAI基盤（`AiTextService`）が実接続可能であること（R8-03 の修正で gemini 起動不能は解消済み）。`ai.provider=mock` の間は決定的なmock生成でフォールバックする。

### 確定済みの設計判断
- 既存 `MatchScoreCalculator`（ルールスコア）を廃止せず、**AIプロンプトの根拠**として渡す（スコアの説明責任を保つ）。
- PII保護: AIへ渡す要員識別子は氏名でなく `Engineer.initialName`（既存）を用いる。
- 金額は円単位・共通フォーマッタ規約（A7-24）に従い、プロンプトの単位ズレを再発させない。
- 生成物は必ず提案（`Proposal`）に保存し、営業が編集して送る（AI出力を無編集送信しない）。

## Requirements

### Requirement 1: 実マッチング接続
1. WHEN `ai.provider=gemini` の場合、THE `AiMatchingService`（engineer→projects / project→engineers）SHALL 実AIで根拠付き結果（score・reason・sellingPoints）を返す。
2. THE 実装 SHALL `MatchScoreCalculator` のルールスコアと候補理由をプロンプト根拠として渡す。
3. WHEN `ai.provider` が gemini 以外の場合、THE システム SHALL 既存のルール/モック実装にフォールバックし、コンテキストは常に一意に起動する（R8-03 の不変条件）。

### Requirement 2: 提案文の自動生成
1. THE システム SHALL engineerId×projectId から提案メール下書き・マッチ理由・セールスポイント・マッチスコアを生成するAPIを提供する（例 `POST /api/ai/proposal-draft`）。
2. WHEN 生成された場合、THE システム SHALL 結果を `Proposal.proposalEmailText`／`matchReason`／`aiMatchScore` に保存する（新規カラム不要）。
3. THE 提案カンバン/提案作成UI SHALL 「AIで下書き生成」を起動でき、生成結果を編集して保存・送付できる。
4. THE AIへの入力 SHALL 氏名を含めず `initialName` を用い、単価は円単位・共通フォーマッタで整形する。
5. WHEN AI無効/失敗の場合、THE システム SHALL 生成を安全に失敗させ（4xx/5xxを分類し内部情報を露出しない）、手動入力にフォールバックできる。

### Requirement 3: データスコープ・権限
1. THE 生成API SHALL 既存 `DataScopeService.assertAllowedEngineer/assertAllowedProject` を通す。
2. THE 機能 SHALL 既存 `ai` メニュー権限に従う。

## Out of Scope
- 生成メールの自動送信（本フェーズは下書き保存まで。送信は既存メール機能／人の操作）。
- 実マッチングAIの学習・チューニング基盤（プロンプトエンジニアリングの範囲に留める）。
</content>
