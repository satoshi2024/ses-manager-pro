# Design — 要員フォロー・定着リスク管理（FR-11）

親: ロードマップ FR-11。再利用: `EngineerSales`（担当営業）・`Engineer`（status=Bench）・`Contract`（Bench期間導出）・`NotificationService`・`DataScopeService`・要員詳細画面。

## 1. DDL（`db/migration/V4x__engineer_followup.sql`）

```sql
CREATE TABLE t_engineer_followup (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id   BIGINT NOT NULL,
  followup_type VARCHAR(20) NOT NULL COMMENT '1on1/面談/連絡',
  followup_date DATE NOT NULL,
  satisfaction  TINYINT NULL COMMENT '満足度 1-5',
  topic         VARCHAR(200) NULL,
  content       TEXT NULL,
  next_date     DATE NULL COMMENT '次回フォロー予定',
  created_by    BIGINT NULL,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag  TINYINT NOT NULL DEFAULT 0,
  INDEX idx_ef_engineer (engineer_id),
  INDEX idx_ef_next (next_date),
  CONSTRAINT fk_ef_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='要員フォロー記録';
-- メニュー不要（要員詳細内。api_prefix は engineer 配下 /api/engineers/{id}/followups を流用）
```
> テストDB二重維持（`engineer-schema-h2.sql`／`application-test.yml`／`FlywayMigrationSmokeTest`）。

## 2. バックエンド

- `entity/EngineerFollowup` / `mapper` / `service`。
- API `/api/engineers/{engineerId}/followups`（CRUD、`DataScopeService.assertAllowedEngineer`。IDOR防止は `EngineerCareerApiController` の `findOwnedOrThrow` 方式を踏襲）。
- 定着リスク `RetentionRiskService.score(engineerId)`:
  - Bench期間（`Engineer.status=Bench` の継続日数 or 直近契約 endDate からの経過）＋ 直近満足度（低いほど加点）＋ フォロー間隔超過（最終フォローからの日数）を合成した簡易スコア。閾値は `m_system_config`。
- 通知: 日次スケジューラで `next_date < 今日` の未実施フォローを担当営業（`EngineerSales` 主担当）へ `NotificationService`（dedupe キー=engineer×next_date）。

## 3. フロント
- 要員詳細に「フォロー履歴」カード（登録モーダル・履歴リスト・次回期日）。要員一覧に定着リスクバッジ/フィルタ。
- `SES.escapeHtml`／`SES.toast.*`／SweetAlert2 規約遵守。

## 4. テスト
- `EngineerFollowupServiceTest`（CRUD・スコープ・IDOR）。
- `RetentionRiskServiceTest`（Bench長期/低満足度/間隔超過で高スコア、閾値）。
- 期日超過通知の生成・dedupe。
</content>
