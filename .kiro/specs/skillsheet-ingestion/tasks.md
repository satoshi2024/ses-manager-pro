# Implementation Plan — スキルシート取込（履歴書自動取込・要員化）

構成は **P(前提基盤=A8対応) → F(本機能基盤) → A/B/C(並行可) → D(候補者連携・任意) → M(統合)**。
P は本機能の依存先であり、第8回監査 A8 の恒久対応を兼ねる（先行 or 同一PR）。
共有資産（i18n・V43・H2スキーマ・エンティティ・Mapper・Service IF）は F で凍結し、A〜C は参照のみ。
不足を見つけたら自分で共有ファイルを直さず、末尾「F 層ギャップメモ」に記録して M で一括対応する。

各タスクは `design.md` の該当章を参照。**テストDBの二重維持**（`engineer-schema-h2.sql` /
`application-test.yml` / `FlywayMigrationSmokeTest`）は F 内で必ず実施すること（CLAUDE.md）。

---

## P. 前提基盤（A8対応。本機能の依存先）

- [ ] P1. AIテキストサービス抽象（A8-01）
  - **Objective**: 設定駆動・サーバー側キー・タイムアウト付きで対話/バッチ双方から呼べるAI基盤。
  - **実装ガイダンス**: design 2.1。`AiTextService`(IF) + `GeminiTextServiceImpl`
    (`AiConfig.apiUrl/model/maxTokens` 使用、キーは `AiConfig.apiKey`、外部SaaS用タイムアウト
    RestTemplate を `AppConfig` に追加) + `MockAiTextServiceImpl`(`@ConditionalOnProperty` mock)。
    例外はスタック保持＋PII/キーのマスク（A8-09）。`AiApiController.checkAiEnabled` を
    `BusinessException.of("error.ai.disabled")` へ、偽 `AiSkillSheetServiceImpl` と
    `/api/ai/skill-sheet/generate` を削除（A8-02）。
  - **テスト要件**: `ai.provider` で実装が切替わる／`ai.model`・`ai.api-url` が呼び先へ反映される。
  - **Demo**: `ai.enabled=true`＋サーバー側キーで `AiTextService.generate` が応答（またはモックで決定的応答）。

- [ ] P2. スキル名→skill_id 解決（A8-07）
  - **Objective**: マスタ未登録名も扱えるスキル登録の共通処理。
  - **実装ガイダンス**: design 2.2。`SkillTagResolver.resolveOrCreate(name)`：正規化して
    `m_skill_tag` 突合、無ければ `category='未分類'` で作成。`SkillTag` は `updated_at`/`deleted_flag`
    非対応（`@TableField(exist=false)`）に注意。
  - **テスト要件**: 既存一致・大文字小文字/全半角ゆれ一致・未登録の自動作成。
  - **Demo**: 「react」「AWS」等で既存/新規どちらも `skill_id` が返る。

- [ ] P3. 孤児清理の参照登録（A8-03）
  - **Objective**: 取込原本が清理バッチで消えないようにする。
  - **実装ガイダンス**: design 2.3。最小=`FileCleanupServiceImpl.collectReferencedFileNames` に
    `resumeIngestionMapper.selectAllStoredFileNames()` を追加。望ましくは `FileReferenceProvider` 化。
  - **テスト要件**: 取込ジョブの `stored_file_name` が参照集合に含まれ削除対象外になる。
  - **Demo**: `cleanupOrphanFiles` を走らせても取込原本が残る。

- [ ] P4. 要員DTOの値域補強（A8-08）
  - **Objective**: 手入力・AI取込どちらでも要員マスタの値域を保証。
  - **実装ガイダンス**: `EngineerSaveDto` の gender/employmentType/japaneseLevel に `@Pattern` allowlist、
    桁 `@Size` を DB に合わせる。
  - **テスト要件**: 不正値でバリデーション 400。
  - **Demo**: 不正 gender を POST すると弾かれる。

---

## F. 本機能の基盤層

- [x] F1. spec ドキュメント（本ディレクトリ requirements / design / tasks）
  - **Demo**: レビュー可能な状態でコミットされている。

- [ ] F2. データ層・共有資産（V43・エンティティ・Mapper・DTO・IF・i18n・H2）
  - **Objective**: A〜C が消費する全共有資産を確定。
  - **実装ガイダンス**: design 1章の `V43__resume_ingestion.sql`（テーブル＋m_menu＋t_role_menu）、
    `ResumeIngestion` エンティティ、`ResumeIngestionMapper`（`selectAllStoredFileNames`）、
    `ParsedResumeDto`/`ReviewedResumeDto`、`ResumeIngestionService`/`ResumeTextExtractor`/
    `ResumeParseService` の IF、i18n 全キー先行投入、`engineer-schema-h2.sql` へ同テーブル追記、
    `application-test.yml`/`FlywayMigrationSmokeTest` 反映。
  - **テスト要件**: `mvn test` が緑（H2 コンテキスト初期化が通る）。
  - **Demo**: 空DBから Flyway で V43 が適用され、`resume-ingestion` メニューが 管理者/HR に出る。

---

## A. テキスト抽出＋AI解析（並行可・担当: ResumeTextExtractorImpl / ResumeParseService impl / dto/resume）

- [ ] A1. テキスト抽出
  - **Objective**: pdf/docx/xlsx からプレーンテキスト抽出。
  - **実装ガイダンス**: design 3.2。OpenPDF `PdfTextExtractor`・POI `XWPFWordExtractor`/`XSSFWorkbook`。
    空抽出は空文字、巨大テキストは上限切り詰め。
  - **テスト要件**: `ResumeTextExtractorImplTest`（各形式サンプル、画像PDFで空文字）。
  - **Demo**: サンプル職務経歴書からテキストが取れる。

- [ ] A2. AI構造化解析（＋mock）
  - **Objective**: 抽出テキスト→`ParsedResumeDto`（厳格JSON）。
  - **実装ガイダンス**: design 3.3。P1 の `AiTextService` を使用。厳格JSONプロンプト・コードフェンス除去・
    1回だけ再出力リトライ。`MockResumeParseServiceImpl`（正規表現）で AI 無効時も要確認到達。
    希望単価の単位は A7-24/A8-01 の共通フォーマッタ規約に従う。
  - **テスト要件**: mock で既知テキストから氏名・スキル抽出。JSON不正時のリトライ→失敗。
  - **Demo**: `ai.provider=mock` で取込が「要確認」まで進む。

---

## B. 取込サービス（並行可・担当: ResumeIngestionServiceImpl / 状態遷移・confirm）

- [ ] B1. ジョブ生成と非同期解析
  - **Objective**: アップロード→ジョブ作成→`@Async` 抽出＋解析→状態遷移。
  - **実装ガイダンス**: design 3.4。`createJob`（`FileStorageService.store`）、`parseAsync`
    （抽出中→要確認／失敗＋error_message、状態はCAS）、`reparse`、`saveReview`。
    `@Async` は既存 `AsyncConfig` プール（MailService の同期/非同期不一致 A7-19 を踏襲しない）。
  - **テスト要件**: 成功→要確認、空抽出→失敗、AI例外→失敗＋error_message。
  - **Demo**: アップロード直後に一覧が「抽出中」→数秒後「要確認」。

- [ ] B2. 確定・却下（要員一括生成）
  - **Objective**: レビュー確定で 要員＋スキル＋経歴を1トランザクション生成。
  - **実装ガイダンス**: design 3.4 の `confirm` 手順（要員=Bench・`protectForCreate`、
    `SkillTagResolver`→`replaceSkills`、経歴 period 検証→save、状態CAS更新＋converted_engineer_id）。
    `reject` で却下。二重確定は 409。
  - **テスト要件**: `ResumeIngestionServiceImplTest`（確定で3種生成／未登録スキル自動作成／
    二重確定409／途中例外で全ロールバック）。
  - **Demo**: レビュー→確定で要員が生成され、詳細にスキル・経歴が入る。

---

## C. API・画面・フロント（並行可・担当: ResumeIngestion*Controller / templates/resume-ingestion / resume-ingestion.js / sidebar）

- [ ] C1. コントローラー
  - **Objective**: `/api/resume-ingestions` CRUD + confirm/reject/reparse、`/resume-ingestion` ページ。
  - **実装ガイダンス**: design 3.5。全一覧に `PageUtils.safePage`。静的制約＋メニューで 管理者/HR 限定。
  - **テスト要件**: 権限外ロールが 403、size=-1 で全件返さない。
  - **Demo**: 管理者/HR で一覧・詳細・確定が curl で通る。

- [ ] C2. 一覧＋レビュー画面
  - **Objective**: アップロード・状態一覧・原本併置のレビューフォーム。
  - **実装ガイダンス**: design 4章。`list.html`＋`review.html`（左フォーム/右原本 iframe）、
    `resume-ingestion.js`（アップロード・saveReview・confirm・reject・抽出中ポーリング）、
    スキル名オートコンプリート（`/api/skill-tags`）、`sidebar.html` に `<li>` 追加。
  - **テスト要件**: 既存フロント regression（`SES.toast.*` 正しい呼び出し、`escapeHtml` 適用）。
  - **Demo**: ブラウザでアップロード→レビュー修正→確定→要員詳細へ遷移。

---

## D. 候補者連携（任意・第2フェーズ・※要確認）

- [ ] D1. 候補者からの取込起動と確定時リンク
  - **Objective**: `t_candidate` 起点の取込と確定時 `linkConvertedEngineer`。
  - **実装ガイダンス**: design 4.1。候補者詳細に「スキルシートから要員化」、`candidate_id` を伴い起動、
    確定で候補者→要員変換まで通す。
  - **テスト要件**: 候補者連携ジョブの確定で `converted_engineer_id` が候補者にも反映。
  - **Demo**: 内定候補者からスキルシート取込→確定で候補者が要員化される。

---

## M. 統合・仕上げ

- [ ] M1. F 層ギャップメモの解消（A〜D で記録した共有ファイル不足を一括対応）。
- [ ] M2. PII保持ポリシー（A8-05）：`app.resume.retention-days` 設定＋却下ジョブ自動パージを清理バッチへ。
- [ ] M3. ファイルダウンロードのアクセス制御（A8-04）：取込原本・スキルシートのスコープ検証、`nosniff`。
- [ ] M4. ドキュメント追随：CLAUDE.md の「主要モジュール一覧」に本機能とAI基盤の実状を追記
    （A8-02/A8-01 の変更を反映。CLAUDE.md 追随を完了条件に含める）。
- [ ] M5. 全量 `mvn test` 緑・第8回監査「完了条件」6項の確認。

## F 層ギャップメモ

（A〜D 実施時、共有ファイル（V43/H2/i18n/エンティティ/Mapper/IF）の不足をここに追記し M で対応）

---

## 完了時に達成される効果（発注者向けサマリ）

- **入力工数**: 新規要員登録が「全項目手入力 15〜30分」→「レビュー修正 数分」に短縮。
- **品質**: 転記ミス減、スキルが `m_skill_tag` に正規化されて登録され、集計/マッチングの精度が上がる。
- **一気通貫**: （D 実施時）候補者→要員化までスキルシート起点で完結。
- **基盤の副次改善**: AI連携が設定駆動・サーバー側キー・タイムアウト付きになり（A8-01）、
  偽AI機能の除去（A8-02）、ファイル清理の構造化（A8-03）、PIIアクセス制御（A8-04/05）が同時に片付く。
</content>
