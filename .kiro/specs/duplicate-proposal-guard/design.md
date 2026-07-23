# Design — 重複提案ガード（FR-03）

親: ロードマップ FR-03。新規テーブル不要（既存 `Proposal`/`Project`/`Customer` のみ）。

## 1. 判定ロジック

- `Proposal` は `engineerId`/`projectId`/`status`/`proposedAt`/`closedAt` を持つ。`Project` 経由で `customerId` を解決。
- アクティブ状態集合を `StatusConstants` に定数化（例: 提案中/一次面談/最終面談/結果待ち 等。成約/見送り/却下 を除外）。既存の提案ステータス定義に合わせる。
- `ProposalService.findActiveDuplicates(engineerId, customerId, excludeProposalId)`:
  - `Proposal` を engineerId で引き、`Project.customerId == customerId` かつ status ∈ アクティブ集合、自分自身を除外して返す。
  - `DataScopeService` のスコープを尊重（担当外は除外/拒否）。
- 単価やprojectId違いでも同一客先ならヒットさせる（客先目線の重複が本質）。

## 2. API

- `GET /api/proposals/duplicate-check?engineerId=&customerId=[&excludeId=]` → アクティブ重複の一覧（客先名・案件名・状態・提案日）。
- `GET /api/engineers/{id}/proposal-history` → 要員の提案履歴（客先/案件/日付/結果/単価）。`DataScopeService.assertAllowedEngineer`。
- 提案作成/移動API（既存 `ProposalApiController`）で、保存前に重複件数を返すか、フロントが事前に duplicate-check を呼ぶ設計いずれか（推奨: フロント事前呼び出しで確認モーダル）。

## 3. フロント

- 提案作成モーダル/カンバン（`proposal-kanban.js`）: 要員×案件選択時に `duplicate-check` を呼び、ヒット時は SweetAlert2 で「◯◯社に提案済み（案件/状態/日付）。続行しますか？」を表示、続行で保存。
- 要員詳細（`engineer` 画面）: 「提案履歴」タブ/カードを追加（`proposal-history` を描画。`SES.escapeHtml` 遵守）。

## 4. i18n・テスト
- `messages*` 5ロケールに `proposal.duplicate.*` ラベル追加。
- テスト: `ProposalServiceTest#findActiveDuplicates`（同客先アクティブ=ヒット、終了状態=非ヒット、自身除外、別客先=非ヒット、スコープ外除外）。
</content>
