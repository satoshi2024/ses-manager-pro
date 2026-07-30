# 法定文書台帳・電子保存（legal-document-ledger-archive）要件・設計整合性レビュー記録

## レビュー概要

- **対象モジュール**: 法定文書台帳・電子保存（legal-document-ledger-archive）
- **担当AI**: SES Manager Pro 主実装AI
- **Decision Gate**: G2 法務監修完了（2026-07-26）
- **評価基準**: 独立レビュー仕様および本リポジトリ開発規約

---

## 指摘事項・修復履歴

### Round 1 指摘事項
- **P0-01**: MySQL 8 空DBにおける V67 裸の CREATE TABLE エラー → `CREATE TABLE IF NOT EXISTS` に修正。
- **P1-01**: i18n 韓国語・中国語メッセージキー欠落 → 13キーを追加。
- **P1-02**: Storage adapter のメモリ保持問題 → 実ファイルシステム（ quarentine / published ）への永続化に改修。
- **P1-03**: FileScanner スキャン非連動 → 登録・版追加時のスキャンと INFECTED 拒否ガードを統合。
- **P1-04**: m_menu シード欠落 & 認可の欠損 → m_menu / t_role_menu シード追加、FileScopeValidationService 修復。
- **P1-05**: businessKey NULL 冪等取りこぼし & tenant_id 未設定 → UUID 自動生成と tenant_id インデックス追加。
- **P1-06**: addVersion の CAS 更新件数未判定 → updated == 0 の 409 ロック判定を追加。
- **P1-07**: executeDisposal の hold 再検証 & rollback 時の証跡消失 → hold 再検証と afterCommit 構造に変更。
- **P1-08**: retention_until の now() フォールバック誤算出 → 起算日未確定時の null 維持に修正。
- **P1-09**: 単体テストの自己承認実検証漏れ → SecurityContext を正しく設定したアサートに修復。

### Round 2 指摘事項（Fix Commit `1c4d0d0`）
- **S04-R02-P0-01 (V1 と V67 のスキーマ不整合)**: `V1__create_tables.sql` の `t_document_version` DDL に `tenant_id`、`NOT NULL`、`uk_document_idempotency` 4列インデックスを同期追加。`FlywayMigrationSmokeTest` に V67 テーブルおよび `tenant_id`・4列インデックスのアサーションを追加・検証。
- **S04-R02-P1-01 (heap全展開の解消)**: `DocumentServiceImpl` で `InputStream` を直接 `DocumentStorage` へ渡し、一時ファイル経由のストリーミング処理へ改善。
- **S04-R02-P1-02 (スキャン判定の fail-closed 化)**: `FileScanner` のスキャン結果が `CLEAN` 以外（`INFECTED`, `UNAVAILABLE`, 例外）の場合はすべて 400 `error.file.scanRejected` または fail-closed で拒否するよう修正。
- **S04-R02-P1-03 (認可の DataScope / t_document_link 対応)**: `FileScopeValidationService` で `t_document_link` 経由の顧客/エンジニア DataScope 判定を追加。
- **S04-R02-P1-05 (テスト妥当性・DB統合テスト追加)**: `DocumentServiceImplTest` の条件アサートを修復し、負ケーステストを追加。さらに `DocumentServiceImplH2Test`（H2 DB 実SQL・UNIQUE制約検証）を追加。

---

## 変更ファイル記録

| task | ファイル | 変更種別 | 内容 |
|---|---|---|---|
| T021 | `.kiro/specs/legal-document-ledger-archive/review-ledger.md` | 変更 | レビュー履歴追記・フォーマット整形 |
| T022 | `src/main/resources/db/migration/V67__document_archive.sql` | 変更 | DDL修復（IF NOT EXISTS, tenant_id, m_menuシード） |
| T022 | `src/main/resources/db/migration/V1__create_tables.sql` | 変更 | V67 相当 DDL（tenant_id, 4列UNIQUE）を完全同期 |
| T022 | `src/test/resources/sql/schema-document-archive-h2.sql` | 変更 | H2用 DDL（tenant_id 4列UNIQUE追加） |
| T022 | `src/main/java/com/ses/entity/DocumentVersion.java` | 変更 | tenantId フィールド追加 |
| T022 | `src/main/java/com/ses/mapper/DocumentVersionMapper.java` | 変更 | findByIdempotencyKey に tenantId 追加 |
| T022 | `src/main/java/com/ses/service/impl/DocumentServiceImpl.java` | 変更 | ストリーミング化、fail-closed スキャン、CAS・hold判定 |
| T022 | `src/main/java/com/ses/service/storage/impl/LocalDocumentStorage.java` | 変更 | 実ファイルシステム保存・ストリーミング対応 |
| T022 | `src/main/resources/messages_ko.properties` | 変更 | エラーメッセージ 13キー追加 |
| T022 | `src/main/resources/messages_zh_CN.properties` | 変更 | エラーメッセージ 13キー追加 |
| T022 | `src/test/java/com/ses/service/impl/DocumentServiceImplTest.java` | 変更 | 単体テストアサーション強化・負ケース追加 |
| T022 | `src/test/java/com/ses/service/impl/DocumentServiceImplH2Test.java` | 新規作成 | H2 DB 実SQL・UNIQUE制約統合テスト |
| T023 | `src/main/java/com/ses/service/security/impl/FileScopeValidationService.java` | 変更 | CLEAN限定 fail-closed & DataScope 判定 |
| T023 | `src/test/java/com/ses/migration/FlywayMigrationSmokeTest.java` | 変更 | V67 6テーブル・tenant_id・4列インデックス検証追加 |
