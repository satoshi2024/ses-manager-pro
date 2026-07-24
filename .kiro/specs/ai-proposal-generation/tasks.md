# Tasks — AI提案文生成＋実マッチング接続（FR-02）

前提: `AiTextService`（A8-01基盤）が利用可能・provider切替でBean一意（R8-03修正済）。

- [x] F1. 共通金額フォーマッタ（A7-24決着）
  - **Objective**: 円単位・カンマ・null時「未設定」の単一フォーマッタ。既存AIプロンプト（AiRestController等）も移行。
  - **テスト要件**: 円/未設定/大きな額のフォーマット。
  - **Demo**: 80万円が「800,000円」で渡る（万円連結の再発なし）。

- [x] A. 実マッチング接続【担当: GeminiMatchingServiceImpl / 条件式】
  - **Objective**: gemini時に根拠付きマッチ結果。
  - **実装ガイダンス**: design 1章。`MatchScoreCalculator` を根拠にプロンプト化、`MatchResultDto` へ。3実装(gemini/rule/mock)の条件式でBean一意。
  - **テスト要件**: `ai.provider=gemini/rule/mock` それぞれで単一Bean・コンテキスト起動。
  - **Demo**: provider切替でアプリ起動、gemini時に理由文が生成される（実キー時）。

- [x] B. 提案生成サービス＋API【担当: ProposalDraftService / AiProposalController】
  - **Objective**: engineerId×projectId→下書き＋理由＋SP＋score、Proposalへ保存。
  - **実装ガイダンス**: design 2章。DataScope検証、initialName使用、金額フォーマッタ適用、失敗分類。
  - **テスト要件**: `ProposalDraftServiceTest`（mockで決定的・氏名非混入・保存）。
  - **Demo**: `POST /api/ai/proposal-draft` が下書きを返し Proposal に保存される。

- [x] C. フロント【担当: proposal-kanban.js / 提案モーダル / i18n】
  - **Objective**: 「AIで下書き生成」ボタンと編集保存。
  - **実装ガイダンス**: design 3章。escapeHtml/toast規約、失敗時フォールバック。
  - **テスト要件**: 既存提案フロー regression。
  - **Demo**: カンバンから生成→編集→保存→送付導線。

- [x] M. 統合
  - **Objective**: i18n 5ロケール、ドキュメント（CLAUDE.md AI節）追随、全量緑。

## 完了条件
- `ai.provider` を gemini/rule/mock に切り替えても起動し、AiMatchingService が一意。
- 提案生成が Proposal の既存カラム(proposalEmailText/matchReason/aiMatchScore)に保存される。
- プロンプトに氏名が出ず単価が円で渡る（PII・単位の固定テスト緑）。
</content>
