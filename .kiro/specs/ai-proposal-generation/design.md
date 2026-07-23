# Design — AI提案文生成＋実マッチング接続（FR-02）

親: ロードマップ FR-02。前提基盤: `AiTextService`（`service/ai/AiTextService`・`GeminiTextServiceImpl`・`MockAiTextServiceImpl`）。

## 1. 実マッチング接続

- 既存: `AiMatchingService`（IF）、`AiMatchingServiceImpl`（mock）、`RuleMatchingServiceImpl`（ルール）、`MatchScoreCalculator`。
- 追加: `GeminiMatchingServiceImpl implements AiMatchingService`（`@ConditionalOnProperty(name="ai.provider", havingValue="gemini")`）。
  - `MatchScoreCalculator` でルールスコアと一致/不一致要素を算出 → それを根拠としてプロンプトに載せ、`AiTextService.generate` で `reason`／`sellingPoints`／最終`score` を得る。
  - 出力は既存 `MatchResultDto`（projectId/projectName/score/reason/sellingPoints）に詰める。
- **Bean一意解決の不変条件**（R8-03）: gemini時に `AiMatchingService` 実装が必ず1つになるよう条件を設計する。
  - 案: `GeminiMatchingServiceImpl`=`havingValue=gemini`、`RuleMatchingServiceImpl`=`havingValue=rule`、`AiMatchingServiceImpl`(mock)=`@ConditionalOnExpression("!'gemini'.equals('${ai.provider:mock}') && !'rule'.equals('${ai.provider:mock}')")`。gemini/rule/mock いずれでも一意。**起動テストで担保**。

## 2. 提案生成

- `dto/ai/ProposalDraftDto`（emailText, matchReason, sellingPoints, matchScore）。
- `service/ai/ProposalDraftService` + impl:
  - 入力 engineerId×projectId。`DataScopeService` で参照可否検証。`Engineer`（initialName・経験・スキル概要・希望単価）と `Project`（案件名・単価幅・スキル・リモート等）を取得。
  - 共通金額フォーマッタ（円・カンマ・null時「未設定」）で整形（A7-24。無ければ本specで新設し、AiRestController等の既存プロンプトも移行）。
  - `AiTextService.generate(prompt)` で下書き生成。プロンプトは「日本語の提案メール本文＋マッチ理由＋セールスポイント＋0-100スコアをJSONで」。氏名はイニシャルのみ。
  - 失敗は分類済み `BusinessException`（内部情報非露出）。
- `controller/api/AiProposalController`（`/api/ai/proposal-draft`, POST）: 生成し、必要なら `Proposal.proposalEmailText/matchReason/aiMatchScore` に保存（既存 `ProposalService` で更新）。
- 既存 `AiRestController`/`AiApiController` の `/api/ai` 責務分担に合わせ、対話系と別メソッドとして追加（A8-02 のコメント方針を踏襲）。

## 3. フロント

- 提案カンバン（`static/js/modules/proposal-kanban.js`）／提案作成モーダルに「AIで下書き生成」ボタン。
- 生成結果を提案フォームの `proposalEmailText`／`matchReason`／`aiMatchScore` へ流し込み、営業が編集して保存。
- 生成中スピナー、失敗時は汎用メッセージ＋手動入力継続。`SES.escapeHtml`／`SES.toast.*` の規約遵守。

## 4. i18n・テスト
- `messages*` に `error.ai.*`（既存 `error.ai.disabled` 等に追随）と生成UIラベルを5ロケール追加。
- テスト:
  - `ai.provider=gemini` で `AiMatchingService` Bean が一意に解決しコンテキスト起動（R8-03）。
  - `ProposalDraftService`（mock provider で決定的出力・PII非混入=氏名がプロンプトに出ない・金額フォーマット）。
  - 生成結果が `Proposal` に保存される。
</content>
