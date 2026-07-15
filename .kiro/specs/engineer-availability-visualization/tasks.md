# Implementation Plan — 稼働率カレンダー/ガントチャート可視化

新規追加のみ(既存メソッド非変更)。逐次構成。

- [x] 1. タイムラインデータAPI実装
  - **Objective**: `getAvailabilityTimeline()`と新規GETエンドポイントを実装。
  - **実装ガイダンス**: design.md 2章。「まもなく空き」判定は`notification-center`の`CONTRACT_END`条件と同一にする(踩坑点参照)。
  - **テスト要件**: `AnalyticsServiceImplTest`で帯生成・期間未定・後続契約除外の3系統、および`CONTRACT_END`判定との一致テスト。
  - **Demo**: `mvn test`グリーン。curlでレスポンスJSONを確認。

- [x] 2. 画面実装
  - **Objective**: `availability-calendar.html`とJSでタイムライン描画、フィルタUI、デフォルト絞り込みを実装。
  - **実装ガイダンス**: design.md 3章。Chart.jsのindexAxis:'y' barで試作し、表現力不足なら軽量ライブラリ導入を検討(判断根拠をdesign.mdに追記)。
  - **テスト要件**: 手動UI確認。
  - **Demo**: ブラウザで実データを表示し、契約帯・空き区間・「まもなく空き」強調表示を確認。

- [x] 3. 詳細画面への遷移リンク
  - **Objective**: タイムライン上の帯クリックでエンジニア詳細へ遷移。
  - **実装ガイダンス**: 既存のengineer詳細URLパターンを使用。
  - **Demo**: クリックして遷移することを確認。
