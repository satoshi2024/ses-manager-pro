# Tasks — 要員フォロー・定着リスク管理（FR-11）

- [ ] F1. 基盤層（V4x・entity・mapper・IF・i18n・H2）
  - **Objective**: `t_engineer_followup` ＋ エンティティ/Mapper/Service、テストDB二重維持、i18n。
  - **Demo**: 空DBから適用、要員詳細にフォローカードの器が出る。

- [ ] A. フォローCRUD＋API
  - **Objective**: `/api/engineers/{id}/followups` CRUD。
  - **実装ガイダンス**: design 2章。`DataScopeService`＋`findOwnedOrThrow`（IDOR防止）。
  - **テスト要件**: `EngineerFollowupServiceTest`（CRUD/スコープ/IDOR）。
  - **Demo**: 要員にフォロー登録・履歴表示。

- [ ] B. 定着リスク＋通知
  - **Objective**: `RetentionRiskService.score` と期日超過通知。
  - **実装ガイダンス**: design 2章。Bench期間＋満足度＋間隔の合成、閾値設定、担当営業へ通知（dedupe）。
  - **テスト要件**: `RetentionRiskServiceTest`、期日超過通知生成/dedupe。
  - **Demo**: 長期Bench＋低満足度でリスク高、期日超過で担当営業へ通知。

- [ ] C. フロント（履歴カード・リスクバッジ・フィルタ）
  - **Objective**: 要員詳細のフォロー履歴、要員一覧の定着リスクバッジ/フィルタ。
  - **Demo**: リスク高要員を一覧で絞り込み。

- [ ] M. i18n・仕上げ（5ロケール、全量緑）。

## 完了条件
- 要員にフォロー記録を残せ、次回期日超過で担当営業へ通知される。
- 定着リスクスコアで高リスク要員を絞り込めるテストが緑。
</content>
