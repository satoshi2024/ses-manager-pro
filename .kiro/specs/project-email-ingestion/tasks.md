# Tasks — 案件メール自動取込（FR-01）

構成: P(前提) → F(基盤) → A/B/C(並行) → M(統合)。`skillsheet-ingestion` の実装を雛形に踏襲。
共有資産は F で凍結、A〜C は参照のみ。テストDB二重維持は F で必須。

- [ ] P1. 取込基盤の一般化（推奨・任意）
  - **Objective**: `ResumeTextExtractor`→`DocumentTextExtractor`（+eml/txt）、取込ジョブ共通抽象化。
  - **実装ガイダンス**: design 1章。一般化しない場合は本specの `ProjectTextExtractor`（txt/eml）で代替可。
  - **Demo**: eml/貼付テキストから本文が取れる。

- [ ] F1. 基盤層（V44・entity・mapper・DTO・IF・i18n・H2）
  - **Objective**: A〜C が使う共有資産の確定。
  - **実装ガイダンス**: design 2〜3章。`t_project_ingestion`、`ProjectIngestion`、`ProjectIngestionMapper`、`Parsed/ReviewedProjectDto`、サービスIF、i18nキー、`engineer-schema-h2.sql`＋`application-test.yml`＋`FlywayMigrationSmokeTest` 反映。
  - **テスト要件**: `mvn test` 緑（H2起動）。
  - **Demo**: 空DBからV44適用、`project-ingestion` メニューが営業/管理者/マネージャーに表示。

- [ ] A. 抽出＋AI解析【担当: ProjectParseService impl / DTO】
  - **Objective**: メール本文→`ParsedProjectDto`（厳格JSON）＋mock。
  - **実装ガイダンス**: design 3章。Gemini/Mock を `@ConditionalOnExpression` パターンで（Bean一意）。単価円換算。
  - **テスト要件**: `MockProjectParseServiceImplTest`。
  - **Demo**: `ai.provider=mock` で取込が「要確認」まで進む。

- [ ] B. 取込サービス【担当: ProjectIngestionServiceImpl】
  - **Objective**: ジョブ生成・非同期解析・確定/却下。
  - **実装ガイダンス**: design 3章。`@Async` は ObjectProvider 自己注入（自己呼び出し禁止）。confirm は `@Transactional`＋二重確定409、reject は状態ガードCAS。
  - **テスト要件**: `ProjectIngestionServiceImplTest`（confirm正常/二重確定/却下ガード）。baseMapper は `ReflectionTestUtils.setField` 注入。
  - **Demo**: 貼付→数秒で要確認→確定でProject生成。

- [ ] C. API・画面【担当: *Controller / templates/project-ingestion / project-ingestion.js / sidebar】
  - **Objective**: 貼付/emlアップロード・一覧・レビュー・確定完了時のマッチ候補提示。
  - **実装ガイダンス**: design 4章。`PageUtils.safePage`、確定後に `/api/ai/matching/project/{id}` でBench候補表示、提案作成へ導線。
  - **テスト要件**: 権限外403、size=-1で全件返さない。
  - **Demo**: ブラウザで案件メール貼付→レビュー→確定→マッチ候補→提案作成。

- [ ] M. 統合・仕上げ
  - **Objective**: F層ギャップ解消、清理プロバイダ登録、`ai.provider=gemini` 起動テスト、ドキュメント追随。
  - **Demo**: 全量 `mvn test` 緑。原本が清理されない。

## 完了条件
- メール貼付/eml から案件が「レビュー→確定」でProject化され、案件スキルも登録される。
- 二重確定409・却下ガード・非同期解析（プール上）・provider=gemini起動 のテストが緑。
- 確定完了画面にBench要員のマッチ候補が出て提案へ繋がる。
</content>
