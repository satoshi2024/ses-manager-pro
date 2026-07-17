# Implementation Plan — 売上着地予測（revenue-forecast）

単一レーン逐次（1→2）。**競合注意**: `DashboardServiceImpl`/`dashboard.js` を触るため、
`dashboard-improvements` spec と同時に走らせないこと。詳細は design.md 参照。

- [ ] 0. spec ドキュメント
  - **Objective**: 本ディレクトリ3ファイル。
  - **Demo**: レビュー可能な状態でコミットされている。

- [ ] 1. 予測計算とAPI
  - **Objective**: config シード・純関数の加重計算・`getSummary` への forecast 系列追加（後方互換）。
  - **実装ガイダンス**: design.md 1〜3章。計算は `computePipelinePerMonth` に純関数化、
    開始月仮定はドラフト規約（翌月1日）と同一、提案ごと円未満切り捨て。
    `forecast.enabled=false` で null。
  - **テスト要件**: design.md 7章の 計算4＋getSummary 3＋後方互換1ケース。
  - **Demo**: オープン提案（単価あり）を作り、curl で summary の forecast が翌月以降にのみ
    乗ること・設定 false で消えることを確認。

- [ ] 2. チャート表示と設定注記
  - **Objective**: 点線系列・内訳 tooltip・仮定注記・設定画面の百分率注記。
  - **実装ガイダンス**: design.md 4〜5章。i18n 2キー×4言語。
  - **テスト要件**: 既存テストグリーン維持（表示は Demo 検証）。
  - **Demo**: ブラウザでダッシュボードに点線が重なり、tooltip に内訳、下部に注記。
    設定画面で確率を変えると再読込で予測が変わる。

- [ ] M. 統合回帰
  - **Objective**: 回帰確認と文書更新。
  - **テスト要件**: `mvn test` 全緑。
  - **Demo**: 提案を成約させると予測系列が減り確定見込みへ移ることを確認。
    `.kiro/specs/README.md` の状態列を更新。
