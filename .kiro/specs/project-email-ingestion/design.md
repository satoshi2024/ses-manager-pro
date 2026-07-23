# Design — 案件メール自動取込（FR-01）

親: ロードマップ FR-01。姉妹実装: `.kiro/specs/skillsheet-ingestion/`（同型。命名・状態機械・テストDB二重維持の作法をすべて踏襲）。

## 1. 取込基盤の一般化（推奨・先行）

`skillsheet-ingestion` の `ResumeTextExtractor`／取込ジョブ状態機械／`FileReferenceProvider` は本機能と共通。可能なら次を先に行うと本機能が「パーサ＋レビュー画面だけ」で載る:
- `ResumeTextExtractor` → `DocumentTextExtractor`（pdf/docx/xlsx/txt/eml対応）へ一般化。emlは `jakarta.mail`（`MimeMessage`）または簡易パースで本文抽出。
- 取込ジョブの共通抽象（状態・CAS・失敗記録）を `AbstractIngestionService` に抽出。
一般化しない場合は本機能内に `ProjectTextExtractor` を新設してもよい（txt/eml のみで軽い）。

## 2. DDL（`db/migration/V44__project_ingestion.sql`）

`t_resume_ingestion`（V43）と同型。最新番号を確認して採番（V43の次）。

```sql
CREATE TABLE t_project_ingestion (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_type         VARCHAR(10) NOT NULL COMMENT 'PASTE / EML',
  original_file_name  VARCHAR(255) NULL COMMENT 'emlの場合の元名',
  stored_file_name    VARCHAR(120) NULL COMMENT 'emlの保存名',
  raw_text            LONGTEXT NULL COMMENT '貼付/抽出した本文',
  status              VARCHAR(20) NOT NULL DEFAULT '取込待ち',
  parsed_json         LONGTEXT NULL,
  ai_provider         VARCHAR(30) NULL,
  ai_model            VARCHAR(60) NULL,
  error_message       VARCHAR(500) NULL,
  converted_project_id BIGINT NULL,
  review_note         VARCHAR(500) NULL,
  created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag        TINYINT NOT NULL DEFAULT 0,
  created_by          BIGINT NULL,
  INDEX idx_pi_status (status),
  INDEX idx_pi_converted (converted_project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='案件メール取込ジョブ';

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('project-ingestion', '案件メール取込', '/project-ingestion', '/api/project-ingestions', 69);
INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key='project-ingestion'
UNION ALL SELECT '営業', id FROM m_menu WHERE menu_key='project-ingestion'
UNION ALL SELECT 'マネージャー', id FROM m_menu WHERE menu_key='project-ingestion';
```

> テストDB二重維持（CLAUDE.md）: `engineer-schema-h2.sql` に本テーブル追記、`application-test.yml` の schema-locations に追加、`FlywayMigrationSmokeTest` 反映。

## 3. バックエンド

- `entity/ProjectIngestion` / `mapper/ProjectIngestionMapper`（`selectAllStoredFileNames` 清理用）。
- `dto/projectingestion/ParsedProjectDto`（案件項目＋skills[]＋warnings[]）、`ReviewedProjectDto`。
- `service/ai/ProjectParseService` + impl:
  - `GeminiProjectParseServiceImpl`（`@ConditionalOnProperty ai.provider=gemini`）: `AiTextService.generate` で厳格JSON抽出。単価は円単位・共通フォーマッタ規約（A7-24）。
  - `MockProjectParseServiceImpl`（`@ConditionalOnExpression("!'gemini'.equals('${ai.provider:mock}')")`）: 正規表現抽出（単価「〜万円」→円換算、スキル語、勤務地キーワード）。
  - ※`skillsheet-ingestion` の mock 条件式パターンに一致させる（Bean一意解決の不変条件）。
- `service/ProjectIngestionService` + impl（`skillsheet-ingestion` の `ResumeIngestionServiceImpl` を雛形に）:
  - `createJob(source, textOrFile)` → 保存＋ジョブ作成＋`selfProvider`経由 `@Async parseAsync`（**自己呼び出し禁止**＝ObjectProvider注入。R8-02の教訓）。
  - `parseAsync` 抽出→解析→状態CAS。
  - `confirm(id, dto)` `@Transactional`: `Project` 生成（既存 `ProjectService`）＋案件スキル（`ProjectSkill`、`SkillTagResolver` で名称→id）＋状態CAS＋`converted_project_id`。二重確定409。
  - `reject(id, reason)` 状態ガード付きCAS（確定済不可）。
- `controller/api/ProjectIngestionApiController`（`/api/project-ingestions`: upload/paste・list・get・review・confirm・reject・reparse。`PageUtils.safePage`）。
- `controller/page/ProjectIngestionPageController`（`/project-ingestion`）。
- 清理: `ProjectIngestionFileReferenceProvider`（eml保存名を参照集合へ）。

## 4. フロント
- `templates/project-ingestion/list.html` ＋ `review.html`（左=案件フォーム/右=原本テキスト）、`static/js/modules/project-ingestion.js`（貼付/emlアップロード・saveReview・confirm・reject・ポーリング・スキルオートコンプリート）、`sidebar.html` に `project-ingestion` の `<li>`。
- 確定完了時、`/api/ai/matching/project/{projectId}`（既存）でBench要員候補を表示し、提案作成へ導線。

## 5. i18n・監査
- `messages*` 5ロケールにラベル/エラーキー（`error.projectIngestion.*`）追加。取込・確定・却下は `ApiAuditFilter` で記録。

## 6. テスト
- `ProjectIngestionServiceImplTest`（confirm 正常/二重確定409/却下ガード、mock解析→要確認、空→失敗）。**baseMapper は `ReflectionTestUtils.setField` で注入**（R8-01の教訓）。
- `MockProjectParseServiceImplTest`（既知メールから案件名・単価・スキル抽出、万円→円換算）。
- `ai.provider=gemini` でBean一意解決の起動テスト（R8-03の教訓）。
</content>
