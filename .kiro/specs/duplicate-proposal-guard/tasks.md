# Tasks — 重複提案ガード（FR-03）

新規テーブル不要。小規模。

- [ ] A. 判定ロジック＋API
  - **Objective**: `findActiveDuplicates` と duplicate-check / proposal-history API。
  - **実装ガイダンス**: design 1〜2章。アクティブ状態集合を `StatusConstants` に定数化。`DataScopeService` 尊重。
  - **テスト要件**: `ProposalServiceTest#findActiveDuplicates`（同客先/終了状態/自身除外/別客先/スコープ外）。
  - **Demo**: curl で重複ヒット・非ヒットを確認。

- [ ] B. フロント（確認モーダル＋提案履歴）
  - **Objective**: 提案作成時の重複警告と要員詳細の提案履歴タブ。
  - **実装ガイダンス**: design 3章。SweetAlert2 確認、escapeHtml 遵守。
  - **テスト要件**: 既存提案フロー regression。
  - **Demo**: 提案済み要員×同客先で警告→続行可、要員詳細で履歴表示。

- [ ] M. i18n・仕上げ
  - **Objective**: 5ロケール、全量緑。

## 完了条件
- 同一 engineer×customer のアクティブ提案作成時に警告が出て続行できる。
- 要員詳細に提案履歴（客先/案件/結果）が表示される。
- 判定が1メソッドに集約され、終了状態・別客先・スコープ外を正しく除外するテストが緑。
</content>
