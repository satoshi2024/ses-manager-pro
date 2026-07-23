# Design — 要員空き状況メール取込（FR-08）

親: ロードマップ FR-08。実装共通部は `skillsheet-ingestion` / FR-01（`project-email-ingestion`）と大半重複。取込基盤（一般化した `DocumentTextExtractor`／取込ジョブ抽象／`AiTextService`／`FileReferenceProvider`）を再利用。

## 1. DDL（`db/migration/V4x__bp_availability.sql`）

取込ジョブ `t_bp_availability_ingestion`（`t_resume_ingestion` と同型）＋ 在庫 `t_bp_availability`。

```sql
CREATE TABLE t_bp_availability (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  initial_name       VARCHAR(50) NULL COMMENT 'イニシャル',
  bp_company         VARCHAR(120) NULL COMMENT '所属BP',
  skills_json        LONGTEXT NULL COMMENT 'スキル配列(JSON)',
  unit_price         BIGINT NULL COMMENT '単価(円)',
  available_from     DATE NULL COMMENT '稼働開始可能日',
  experience_years   INT NULL,
  status             VARCHAR(20) NOT NULL DEFAULT '提案可能' COMMENT '提案可能/失効/要員化済',
  promoted_engineer_id BIGINT NULL COMMENT '昇格先 t_engineer.id',
  remarks            VARCHAR(500) NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  created_by BIGINT NULL,
  INDEX idx_bpa_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部要員在庫';
-- t_bp_availability_ingestion は t_resume_ingestion / t_project_ingestion と同型（converted_availability_id を持つ）
-- メニュー: bp-availability（営業/マネージャー/管理者）
```
> テストDB二重維持（`engineer-schema-h2.sql`／`application-test.yml`／`FlywayMigrationSmokeTest`）。

## 2. バックエンド
- パーサ `BpAvailabilityParseService`（Gemini/Mock。条件式でBean一意）。
- `BpAvailabilityIngestionService`（`skillsheet-ingestion` 雛形。`@Async` は ObjectProvider 自己注入、confirm=Transactional+二重確定409、reject状態ガード）。confirm で `t_bp_availability` 生成。
- 昇格API `POST /api/bp-availabilities/{id}/promote` → `t_engineer`(employmentType=BP) 生成＋`promoted_engineer_id` 記録。
- スキル名→id は `SkillTagResolver` 再利用（在庫は skills_json 保持、要員化時に `t_engineer_skill` へ展開）。
- 失効: `available_from` 経過や手動で `status=失効`（任意でスケジューラ）。
- 清理: `FileReferenceProvider` 追加。

## 3. マッチング連携
- FR-01/FR-02 のマッチング候補取得を「自社Bench＋`t_bp_availability`(提案可能)」の横断に拡張。

## 4. テスト
- `BpAvailabilityIngestionServiceImplTest`（confirm/二重確定/却下ガード、baseMapper は ReflectionTestUtils 注入）。
- `MockBpAvailabilityParseServiceImplTest`。
- provider=gemini 起動テスト。
</content>
