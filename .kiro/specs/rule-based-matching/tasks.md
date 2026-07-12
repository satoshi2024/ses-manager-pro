# Implementation Plan — ルールベースマッチング(P4)

前提: P1(engineer-skill-career)完了。

- [ ] 1. `MatchScoreCalculator` の実装(テスト駆動)
  - **Objective**: 採点ロジックを純粋関数として固める。
  - **実装ガイダンス**: design.md 2章。先に `MatchScoreCalculatorTest` で配点表・null 扱い・除外境界(必須充足50%)を網羅してから実装。
  - **テスト要件**: 満点/必須不足/単価乖離減点/日付遅延/null 5系統。
  - **Demo**: `mvn test -Dtest=MatchScoreCalculatorTest` パス。

- [ ] 2. プロバイダ切替の導入
  - **Objective**: `ai.provider` で mock / rule を切り替えられる構造にする。
  - **実装ガイダンス**: 既存 `AiMatchingServiceImpl` に `@ConditionalOnProperty(havingValue="mock", matchIfMissing=true)`、`RuleMatchingServiceImpl` を `havingValue="rule"` で追加。interface に `findMatchingEngineers` を追加し mock 側にも固定実装。
  - **テスト要件**: `@SpringBootTest` + プロパティ切替で Bean が入れ替わること。
  - **Demo**: `ai.provider: rule` で起動しマッチング API が実データを返す。mock に戻すと従来挙動。

- [ ] 3. `findMatchingProjects` の実装
  - **Objective**: 要員→案件マッチングの実データ化 + AIログ記録。
  - **実装ガイダンス**: design.md 3章。要求スキルは in 句 1 クエリで取得(N+1 禁止)。reason/sellingPoints の日本語組み立て。`AiLog` insert。通知 publish は `ObjectProvider` で任意依存。
  - **テスト要件**: H2 シードで順位・除外・ログ記録を検証。
  - **Demo**: マッチング画面で実在案件がスコア順に表示され、`t_ai_log` に行が増える。

- [ ] 4. マッチング画面から提案作成
  - **Objective**: 結果カード→提案作成モーダル→カンバンの導線。
  - **実装ガイダンス**: `ai-matching.js` に「この案件に提案」ボタン。P2 導入済みなら提案作成時に要員が「提案中」になることを確認。
  - **Demo**: マッチング結果から提案を作成し、カンバンの「書類選考中」列に出現する。

- [ ] 5. 案件→要員の逆方向推薦
  - **Objective**: 案件一覧から候補要員を探せるようにする。
  - **実装ガイダンス**: `findMatchingEngineers` + `GET /api/ai/matching/project/{projectId}` + `project.js` の `#matchingResultModal`。
  - **テスト要件**: H2 で Bench/提案中のみが対象になること。
  - **Demo**: 案件行の「候補要員」→ スコア順の要員リスト → そのまま提案作成できる。
