# Design Document — スキルシート取込（履歴書自動取込・要員化）

対象読者は実装担当AI。既存コードの命名・規約に合わせる。参照した既存資産：
`FileStorageServiceImpl`・`FileApiController`・`FileKind.SKILL_SHEET`・`FileCleanupServiceImpl`・
`GeminiService`/`AiConfig`・`Engineer`/`EngineerSkill`/`EngineerCareer`・`m_skill_tag`・
`CandidateServiceImpl`・`EngineerCareerApiController`/`EngineerSkillApiController`。

## 0. 全体像（データフロー）

```
[アップロード] ── POST /api/resume-ingestions (multipart)
     │  FileStorageService.store(file, SKILL_SHEET) → stored_file_name
     ▼
 t_resume_ingestion (status=取込待ち)
     │  @Async parse()
     ├─ ResumeTextExtractor.extract(storedFile) → 抽出テキスト (status=抽出中)
     ├─ ResumeParseService.parse(text) → ParsedResumeDto(JSON) (status=要確認)
     │        └ 失敗時 status=失敗 + error_message
     ▼
[レビュー画面] /resume-ingestion/{id}
     │  parsed_json を編集フォームへ展開／原本を別タブ表示／スキル名オートコンプリート
     ▼
[確定] POST /api/resume-ingestions/{id}/confirm  (ReviewedResumeDto)
     │  @Transactional：Engineer + EngineerSkill(名称→skill_id解決) + EngineerCareer を一括生成
     ▼
 t_resume_ingestion (status=確定済, converted_engineer_id=新要員ID)
        └ ※候補者紐付けがあれば CandidateService.linkConvertedEngineer
```

## 1. DDL（`db/migration/V43__resume_ingestion.sql`）

現行の最新は V42（V19/V23/V41 は欠番）。本テーブルはベースライン後の新規追加なので**自身のマイグレーションで
新設**する（V1 の CREATE TABLE には触らない。CLAUDE.md「新規テーブルは own migration」）。

```sql
CREATE TABLE t_resume_ingestion (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  original_file_name   VARCHAR(255) NOT NULL COMMENT 'アップロード時の元ファイル名',
  stored_file_name     VARCHAR(120) NOT NULL COMMENT '保存名(UUID.ext)。/api/files で参照',
  file_ext             VARCHAR(10)  NOT NULL COMMENT '拡張子(pdf/xlsx/docx)',
  status               VARCHAR(20)  NOT NULL DEFAULT '取込待ち'
                         COMMENT '取込待ち/抽出中/要確認/確定済/却下/失敗',
  extracted_text       LONGTEXT     NULL COMMENT '抽出したプレーンテキスト(PII)',
  parsed_json          LONGTEXT     NULL COMMENT 'AI構造化結果 + レビュー編集後の内容(JSON)',
  ai_provider          VARCHAR(30)  NULL COMMENT '解析に使ったプロバイダ(gemini/mock等)',
  ai_model             VARCHAR(60)  NULL COMMENT '解析に使ったモデル',
  error_message        VARCHAR(500) NULL COMMENT '失敗理由(サニタイズ済み)',
  converted_engineer_id BIGINT      NULL COMMENT '確定で生成した t_engineer.id',
  candidate_id         BIGINT       NULL COMMENT '候補者起点の場合の t_candidate.id(任意)',
  review_note          VARCHAR(500) NULL COMMENT 'レビュー担当メモ',
  created_at           DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag         TINYINT      NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  created_by           BIGINT       NULL COMMENT '取込実行ユーザー',
  INDEX idx_ri_status (status),
  INDEX idx_ri_converted (converted_engineer_id),
  INDEX idx_ri_candidate (candidate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='スキルシート取込ジョブ';

-- メニュー登録（候補者 V16 に倣う。sort_order は候補者(67)付近の空き番へ）
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('resume-ingestion', 'スキルシート取込', '/resume-ingestion', '/api/resume-ingestions', 68);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'resume-ingestion'
UNION ALL SELECT 'HR', id FROM m_menu WHERE menu_key = 'resume-ingestion';
-- ※営業にも配る場合は行を追加（requirements 前提の※要確認）
```

> 注意（CLAUDE.md テストDBの二重維持）：本テーブルを追加したら **必ず** ①`sql/engineer-schema-h2.sql`
> に同等のCREATE TABLEを追記、②`application-test.yml` の `spring.sql.init.schema-locations` が
> V43 を拾うなら追加（拾わないなら H2 スキーマ側だけで可）、③`FlywayMigrationSmokeTest` の期待スキーマ
> に反映。これを怠ると `@SpringBootTest` のコンテキスト初期化や MyBatis-Plus の SELECT が
> 「Unknown column / table」で落ちる。`m_menu`/`t_role_menu` を持たないテストスライス
> （`MobileResponsiveLayoutTest` 等）は従来どおり fail-open で影響しない。

## 2. 依存基盤の改修（A8-01 / A8-03 / A8-07）

本機能の実装前（または同一PR）に次を用意する。これらは監査 A8 の恒久対応でもある。

### 2.1 AIテキストサービス抽象（A8-01）

- `service/ai/AiTextService`（interface）: `String generate(String prompt)`。
- `service/ai/impl/GeminiTextServiceImpl`（`@ConditionalOnProperty(name="ai.provider", havingValue="gemini")`）:
  既存 `GeminiService` を土台に、**`AiConfig.apiUrl`（未設定時のみ既定URL）・`model`・`maxTokens` を使用**し、
  **APIキーは `AiConfig.apiKey`（サーバー側）**、**タイムアウト付き RestTemplate**（`AppConfig` に
  外部SaaS用 bean を追加：接続5s/読取60s）を注入する。例外はスタックを残しつつPII/キーをマスクする（A8-09）。
- `service/ai/impl/MockAiTextServiceImpl`（`havingValue="mock", matchIfMissing=true`）: 決定的ダミー応答
  （テスト・AI無効時用）。`AiMatchingServiceImpl` の切替パターンに一致させる。

### 2.2 スキル名→skill_id 解決（A8-07）

- `service/SkillTagResolver`（または `EngineerSkillService` に統合）: `Long resolveOrCreate(String skillName)`。
  正規化（trim・全半角・大文字小文字）して `m_skill_tag.skill_name` と突合。無ければ
  `category='未分類'` で新規作成してIDを返す。`m_skill_tag` は `updated_at`/`deleted_flag` を持たない
  （`SkillTag` エンティティが `@TableField(exist=false)` で除外済み）ので保存時に注意。

### 2.3 孤児清理への登録（A8-03）

- 最小対応：`FileCleanupServiceImpl.collectReferencedFileNames` に
  `resumeIngestionMapper.selectAllStoredFileNames()`（`status != 却下` かつ論理削除除外）を追加。
- 望ましい対応：`FileReferenceProvider` インターフェースを定義し、各機能（要員写真・提案スキルシート・
  本取込）が実装、`FileCleanupServiceImpl` が全 provider を集約する構造にして今後の足し忘れを防ぐ。

## 3. バックエンド構成

### 3.1 エンティティ / Mapper / DTO

- `entity/ResumeIngestion`（`@TableName("t_resume_ingestion")`, `extends BaseEntity`, `created_by` は
  `@TableField(fill=FieldFill.INSERT)`）。
- `mapper/ResumeIngestionMapper extends BaseMapper<ResumeIngestion>`。
  `@Select` で `selectAllStoredFileNames()`（清理用）。
- `dto/resume/ParsedResumeDto`：AIの構造化結果。
  ```
  ParsedResumeDto {
    EngineerPart engineer;        // fullName, fullNameKana, gender, birthDate,
                                  // nationality, nearestStation, prefecture, railwayCompany,
                                  // experienceYears, japaneseLevel, expectedUnitPrice, resumeSummary
    List<SkillPart> skills;       // name, proficiency(初級/中級/上級), experienceYears
    List<CareerPart> careers;     // periodFrom, periodTo, projectName, clientIndustry, role,
                                  // techStack, description, teamSize
    List<String> warnings;        // 抽出時の注意(単価単位不明・日付曖昧 等)
  }
  ```
- `dto/resume/ReviewedResumeDto`：確定APIの入力。`ParsedResumeDto` と同型だが、要員項目は
  `EngineerSaveDto` のバリデーション（A8-08 補強後）を満たすこと。

### 3.2 テキスト抽出 `service/impl/ResumeTextExtractorImpl`

- `String extract(String storedFileName, String ext)`：
  - `pdf`：OpenPDF `PdfReader` + `com.lowagie.text.pdf.parser.PdfTextExtractor`（既存依存）。
  - `docx`：POI `XWPFDocument` + `XWPFWordExtractor`。
  - `xlsx`：POI `XSSFWorkbook` を走査しセル文字列を連結。
- 抽出結果が空白のみ → 空文字を返す（呼び出し側が「失敗」判定）。巨大テキストは上限（例 60,000 字）で
  切り詰め、AIトークン超過を防ぐ。抽出中の例外は握り潰さず失敗理由に要約を残す。

### 3.3 解析 `service/ai/ResumeParseService` + impl

- `ParsedResumeDto parse(String extractedText)`：抽出テキストから**厳格JSON**を得る。
  - プロンプトは「日本のスキルシート/職務経歴書を解析し、**指定JSONスキーマだけ**を返す。不明は null、
    値を捏造しない。和暦/西暦の期間は YYYY-MM-DD に正規化。スキルは正式名称（例: 'React','AWS'）で列挙。
    希望単価は**円単位の数値**（※単位規約は A7-24/A8-01 で確定した共通フォーマッタに従う）」を指示。
  - 応答はコードフェンス除去 → `ObjectMapper` で `ParsedResumeDto` へ。パース不能時は1度だけ
    「JSONのみで再出力」を促すリトライ、なお失敗なら例外。
- `MockResumeParseServiceImpl`（`ai.provider=mock`）：正規表現で氏名候補・スキル語・年数を拾う簡易抽出。
  機能が AI 無効でも「要確認」まで進む（requirements 2-6）。

### 3.4 取込サービス `service/ResumeIngestionService` + impl

| メソッド | 役割 |
|---|---|
| `ResumeIngestion createJob(MultipartFile file)` | 検証→`FileStorageService.store`→ジョブ作成（取込待ち）→`parseAsync` を起動 |
| `@Async void parseAsync(Long id)` | 抽出→AI解析→`parsed_json` 保存＋状態遷移。失敗は「失敗」＋`error_message`。**状態はCASで更新** |
| `void reparse(Long id)` | 「要確認/失敗」のジョブを再解析（`parseAsync` 再実行） |
| `void saveReview(Long id, ReviewedResumeDto dto)` | レビュー中間保存（`parsed_json` 更新。状態は要確認のまま） |
| `Long confirm(Long id, ReviewedResumeDto dto)` | **@Transactional**。要員＋スキル＋経歴を一括生成。状態を「確定済」へCAS更新し `converted_engineer_id` 記録。候補者があれば `linkConvertedEngineer`。既に確定済なら 409 |
| `void reject(Long id, String reason)` | 状態を「却下」へ |

- `confirm` の流れ：
  1. ジョブを取得し `status='要確認'` を CAS 前提に検証（`converted_engineer_id` 非NULLなら 409 `error.resume.alreadyConfirmed`）。
  2. `ReviewedResumeDto.engineer` → `Engineer`（`status=Bench`, `EntityProtectUtil.protectForCreate`）を `EngineerService.save`。
  3. `skills` を `SkillTagResolver.resolveOrCreate` で `skill_id` 化 → `EngineerSkillService.replaceSkills(engineerId, list)`。
  4. `careers` を `periodTo >= periodFrom` 検証（`EngineerCareerApiController.validatePeriod` と同条件）して
     `EngineerCareerService.save` を件数分。
  5. ジョブ状態を「確定済」に更新（`UPDATE ... SET status='確定済', converted_engineer_id=? WHERE id=? AND status='要確認'`。0件なら 409）。
  6. `candidate_id` があれば `CandidateService.linkConvertedEngineer(candidateId, engineerId)`。
- 途中失敗は全ロールバック（要員も作られない。requirements 4-4）。

### 3.5 コントローラー

- `controller/api/ResumeIngestionApiController`（`/api/resume-ingestions`）：
  - `POST /`（multipart `file`）= `createJob`
  - `GET /`（ページャ：`PageUtils.safePage`、`status` 絞り込み）
  - `GET /{id}`（`parsed_json` を展開して返す）
  - `POST /{id}/reparse`・`PUT /{id}/review`・`POST /{id}/confirm`・`POST /{id}/reject`・`DELETE /{id}`
  - 全 `size` は `PageUtils.safePage`（A7-11規約）。
- `controller/page/ResumeIngestionPageController`（`/resume-ingestion` 一覧、`/resume-ingestion/{id}` レビュー）。

## 4. フロントエンド（既存モジュール規約に準拠）

- `templates/resume-ingestion/list.html`：アップロードボタン（`FileKind=SKILL_SHEET`）＋状態フィルタ＋
  一覧テーブル（状態バッジ・ファイル名・登録者・日時・確定済なら要員リンク）。`layout/base.html` 継承、
  `layout:fragment="content"/"page-js"`。
- `templates/resume-ingestion/review.html`（または一覧内モーダル）：左＝抽出フォーム（要員/スキル表/経歴表・
  編集可）、右＝原本表示（`<iframe src="/api/files/{storedName}">` またはリンク）。スキル名は
  `/api/skill-tags`（既存 `SkillTagApiController`）でオートコンプリート、未登録名は「新規」バッジ。
- `static/js/modules/resume-ingestion.js`：一覧`$.ajax`、アップロード、`saveReview()`（中間保存）、
  `confirmIngestion()`（確定→`res.code===200`でトースト＋要員詳細へ遷移）、`rejectIngestion()`
  （SweetAlert2 確認）、「抽出中」ジョブのポーリング更新。既存 `engineer.js`/`candidate.js` の作法に合わせる。
- `layout/sidebar.html`：`th:if="${allowedMenus.contains('resume-ingestion')}"` の `<li>` を追加。

### 4.1 候補者連携（※要確認・第2フェーズ）

`templates/candidate/detail.html` に「スキルシートから要員化」を追加し、`candidate_id` を伴って取込を起動。
確定時に `linkConvertedEngineer` まで通す。要員化フロー（A8-06）の本命だが、単体機能を先に完成させ、
連携は後付けできる設計にする。

## 5. i18n・監査・エラーキー

- `messages*.properties`（5ロケール：ja/en/ko/zh_CN ＋ 既存の全ロケール）へ本機能のラベル・トースト・
  エラーキーを追加：`error.resume.notFound`/`error.resume.alreadyConfirmed`/`error.resume.extractFailed`/
  `error.resume.aiFailed`/`error.resume.invalidStatus`、および A8-02 の `error.ai.disabled`。
  キー欠落は既存の i18n テスト（全ロケール整合）で検出される想定。
- 取込・確定・却下は `ApiAuditFilter`（`/api/**`）で自動記録される。PII（抽出テキスト）はログに出さない。

## 6. 非機能・エッジケース

- **AIコスト/多重実行**：解析はアップロード時1回＋手動再解析のみ。自動リトライは最大1回。
- **並行**：状態遷移は全てCAS（`WHERE status=?`）。確定の二重押しは 409。
- **大容量**：抽出テキストは上限で切り詰め。一覧はページャ。
- **degrade**：`ai.enabled=false`/`mock` でも mock 解析器で「要確認」まで到達（機能停止しない）。
- **PII保持**（A8-05）：確定/却下後の `extracted_text` 保持可否と却下ジョブの自動パージ期間を
  `application.yml`（例 `app.resume.retention-days`）で設定可能にし、清理バッチで実施。
- **重複要員**：確定前に同名＋生年月日の既存要員を警告（ブロックしない）。

## 7. テスト方針

- `ResumeTextExtractorImplTest`：pdf/docx/xlsx の各サンプルからテキスト抽出、空PDFで空文字。
- `MockResumeParseServiceImplTest`：既知テキストから氏名・スキルを抽出。
- `ResumeIngestionServiceImplTest`：
  - `parseAsync` 成功→要確認、抽出0字→失敗、AI例外→失敗＋error_message。
  - `confirm` 成功で 要員＋スキル（未登録名の自動作成含む）＋経歴が生成、状態=確定済。
  - 二重 `confirm` は 409、途中例外で全ロールバック（要員が残らない）。
  - `SkillTagResolver` の正規化・自動作成。
- `AiTextService` 切替：`ai.provider=gemini` で `AiConfig.apiUrl/model` を使う（A8-01完了条件）、
  `mock` で `MockAiTextServiceImpl` が選択される。
- H2 スキーマ同期後、既存 Engineer 系 `@SpringBootTest` が緑のまま。
- 孤児清理：取込ジョブの `stored_file_name` が参照集合に含まれ、清理されないテスト（A8-03完了条件）。
</content>
