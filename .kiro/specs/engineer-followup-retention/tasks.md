# Tasks — 要員フォロー・定着リスク管理（FR-11）

- [x] F1. 基盤層（V4x・entity・mapper・IF・i18n・H2）
  - **Objective**: `t_engineer_followup` ＋ エンティティ/Mapper/Service、テストDB二重維持、i18n。
  - **Demo**: 空DBから適用、要員詳細にフォローカードの器が出る。
  - `db/migration/V54__engineer_followup.sql`（main側でV50〜V53が先行使用されたためmain統合時にV54へ採番変更）、`entity/EngineerFollowup`、`mapper/EngineerFollowupMapper`、`service/EngineerFollowupService(Impl)` を追加。`engineer-schema-h2.sql`／`schema-engineer-followup-h2.sql`／`application-test.yml`／`FlywayMigrationSmokeTest` を更新。

- [x] A. フォローCRUD＋API
  - **Objective**: `/api/engineers/{id}/followups` CRUD。
  - **実装ガイダンス**: design 2章。`DataScopeService`＋`findOwnedOrThrow`（IDOR防止）。
  - **テスト要件**: `EngineerFollowupServiceTest`（CRUD/スコープ/IDOR）。
  - **Demo**: 要員にフォロー登録・履歴表示。
  - `EngineerFollowupApiController` を `EngineerCareerApiController` と同じ IDOR 防止パターンで実装。`EngineerFollowupServiceTest`（3ケース: CRUD/IDOR）追加、緑。

- [x] B. 定着リスク＋通知
  - **Objective**: `RetentionRiskService.score` と期日超過通知。
  - **実装ガイダンス**: design 2章。Bench期間＋満足度＋間隔の合成、閾値設定、担当営業へ通知（dedupe）。
  - **テスト要件**: `RetentionRiskServiceTest`、期日超過通知生成/dedupe。
  - **Demo**: 長期Bench＋低満足度でリスク高、期日超過で担当営業へ通知。
  - `RetentionRiskService(Impl)` + `RetentionRiskApiController`（`/api/engineers/{id}/retention-risk`）、`NotificationGenerateService.followupOverdue()`（dedupe=`FOLLOWUP_OVERDUE:{engineerId}:{nextDate}`、`generateAll()`から呼び出し）を追加。閾値は `m_system_config`（`retention.risk.*`）。`RetentionRiskServiceImplTest`（4ケース）、`FollowupOverdueNotificationTest`（dedupe検証）、`NotificationGenerateServiceTest` にユニットテスト追加、いずれも緑。

- [x] C. フロント（履歴カード・リスクバッジ・フィルタ）
  - **Objective**: 要員詳細のフォロー履歴、要員一覧の定着リスクバッジ/フィルタ。
  - **Demo**: リスク高要員を一覧で絞り込み。
  - `engineer/detail.html` にフォロー履歴カード＋登録モーダル（`engineer-detail.js`）、`engineer/list.html` に定着リスク列・絞り込みセレクト（`engineer.js`、`EngineerApiController` の `riskLevel=high` フィルタ）を追加。

- [x] M. i18n・仕上げ（5ロケール、全量緑）。
  - `messages*.properties`（ja/en/zh_CN/ko/zh の5ファイル）に `engineerFollowup.*`／`error.engineerFollowup.*`／`notification.msg.FOLLOWUP_OVERDUE` 等27キーを追加、`MessageBundleConsistencyTest` 緑。`mvn test` 全641件成功（Docker依存の `FlywayMigrationSmokeTest` 含む3件はスキップのみ、失敗ゼロ）。

## 完了条件
- 要員にフォロー記録を残せ、次回期日超過で担当営業へ通知される。
- 定着リスクスコアで高リスク要員を絞り込めるテストが緑。
</content>
