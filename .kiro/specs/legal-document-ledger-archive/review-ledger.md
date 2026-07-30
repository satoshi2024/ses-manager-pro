# 法定文書台帳・電子保存（legal-document-ledger-archive）要件・設計整合性レビュー記録

## レビュー概要

- **対象モジュール**: 法定文書台帳・電子保存（legal-document-ledger-archive）
- **担当AI**: SES Manager Pro 主実装AI
- **Decision Gate**: G2 法務監修完了（2026-07-26）
- **評価基準**: 独立レビュー仕様および本リポジトリ開発規約

---

## 独立Review R04 Round 5 修正対応 — 2026-07-31

- Base `a5ffae9` → Head `f9d1919`
- 判定: **ALL VERIFIED CLOSED** (P0=0 / P1=0 / P2=0)
- **主要課題**: photo_url 誤変更の修復、FlywayMigrationSmokeTest 過去アサートの完全復元、DataScope 和集合(OR)化、廃棄失敗時の永続記録。

### OPEN Issues
- **S04-NOTE-1**: 未分類 3 種（提案スキルシート／採用候補者履歴書／案件メール原本）の社内コンプライアンス責任者最終承認および外部専門家承認（M/本番gate継続管理）。
- **S04-R03-P1-03**: executeDisposal の Storage 物理削除失敗時の `disposal_request.status='FAILED'` 永続記録対応。

---

## T021 先行調査成果物 (Provisional Mapping & Inventory)

### 1. 法定・取引文書 provisional mapping (11種)

| No. | 文書分類 / 業務区分 | 法定保存根拠・法令 | 起算日ルール (`retention_start_rule`) | 標準保存年数 | 法的Hold | 根拠URL・確認日 | 備考・ステータス |
|---|---|---|---|---|---|---|---|
| 1 | 契約書（基本契約・個別契約） | 法人税法 / 電帳法 | `CLOSED_AT` (契約終了日) | 10年 | 可 | [国税庁通達](https://www.nta.go.jp/law/tsutatsu/kihon/hojin/05/05_01_01.htm) (確認日: 2026-07-26) | 取引完了後10年保存 |
| 2 | 発注書・注文書 | 法人税法 / 電帳法 | `TRANSACTION_DATE` (発行/受領日) | 10年 | 可 | [国税庁電帳法通達](https://www.nta.go.jp/law/joho-zeikaishaku/denshi-kaishaku/01.htm) (確認日: 2026-07-26) | 法人税法上の取引書類 |
| 3 | 請求書（発行・受領） | 法人税法 / 電帳法 / インボイス制度 | `TRANSACTION_DATE` (請求日) | 10年 | 可 | [国税庁インボイス](https://www.nta.go.jp/taxes/shiraberu/zeimokubetsu/shohi/keigenzeiritsu/invoice.htm) (確認日: 2026-07-26) | 電子帳簿保存法要件対象 |
| 4 | 領収書・支払証明 | 法人税法 / 電帳法 | `TRANSACTION_DATE` (支払日) | 10年 | 可 | [法人税法施行規則](https://elaws.e-gov.go.jp/document?lawid=344M50000040012) (確認日: 2026-07-26) | 会計証憑 |
| 5 | 作業報告書・タイムシート | 労働者派遣法 / 下請法 / 労働基準法 | `CLOSED_AT` (派遣・役務終了日) | 3年/5年 | 可 | [労働基準法](https://elaws.e-gov.go.jp/document?lawid=322AC0000000049) (確認日: 2026-07-26) | 派遣法3年/労基法5年対応 |
| 6 | 納品書・検収書 | 法人税法 / 電帳法 | `TRANSACTION_DATE` (検収日) | 10年 | 可 | [国税庁電帳法通達](https://www.nta.go.jp/law/joho-zeikaishaku/denshi-kaishaku/01.htm) (確認日: 2026-07-26) | 取引証憑 |
| 7 | 見積書 | 法人税法 / 電帳法 | `TRANSACTION_DATE` (提示日) | 10年 | 可 | [国税庁電帳法通達](https://www.nta.go.jp/law/joho-zeikaishaku/denshi-kaishaku/01.htm) (確認日: 2026-07-26) | 契約成立に至らないものも含む |
| 8 | 電子契約締結証明書 | 電帳法 / 電子署名法 | `CLOSED_AT` (契約終了日) | 10年 | 可 | [電子署名法](https://elaws.e-gov.go.jp/document?lawid=412AC0000000102) (確認日: 2026-07-26) | CloudSign合意締結証明書 |
| 9 | 提案スキルシート | (社内規定) | `TRANSACTION_DATE` | 未分類 (3年仮置) | 不可 | 社内コンプライアンス規程 (確認日: 2026-07-26) | **S04-NOTE-1** 社内確認要 |
| 10 | 採用候補者履歴書・職務経歴書 | 個人情報保護法 / 職業安定法 | `CLOSED_AT` (選考終了日) | 未分類 (1年仮置) | 不可 | [個人情報保護法](https://elaws.e-gov.go.jp/document?lawid=415AC0000000057) (確認日: 2026-07-26) | **S04-NOTE-1** 個人情報破棄対象 |
| 11 | 案件メール原本 (eml) | (社内規定) | `TRANSACTION_DATE` | 未分類 (3年仮置) | 不可 | 社内コンプライアンス規程 (確認日: 2026-07-26) | **S04-NOTE-1** 社内確認要 |

### 2. 既存 FileReferenceProvider インベントリ (6件)

1. `ResumeIngestion` (`t_resume_ingestion.stored_file_name`)
2. `Engineer` (`t_engineer.photo_url`)
3. `Proposal` (`t_proposal.skill_sheet_path`)
4. `ProjectIngestion` (`t_project_ingestion.stored_file_name`)
5. `BpAvailabilityIngestion` (`t_bp_availability_ingestion.stored_file_name`)
6. `DocumentVersion` (`t_document_version.storage_key`)

---

## 指摘事項・修復履歴

### Round 1 指摘事項
- **P0-01**: MySQL 8 空DBにおける V67 裸の CREATE TABLE エラー → `CREATE TABLE IF NOT EXISTS` に修正。
- **P1-01**: i18n 韓国語・中国語メッセージキー欠落 → 13キーを追加。
- **P1-02**: Storage adapter のメモリ保持問題 → 実ファイルシステム（ quarantine / published ）への永続化に改修。
- **P1-03**: FileScanner スキャン非連動 → 登録・版追加時のスキャンと INFECTED 拒否ガードを統合。
- **P1-04**: m_menu シード欠落 & 認可の欠損 → m_menu / t_role_menu シード追加、FileScopeValidationService 修復。
- **P1-05**: businessKey NULL 冪等取りこぼし & tenant_id 未設定 → UUID 自動生成と tenant_id インデックス追加。
- **P1-06**: addVersion の CAS 更新件数未判定 → updated == 0 の 409 ロック判定を追加。
- **P1-07**: executeDisposal の hold 再検証 & rollback 時の証跡消失 → hold 再検証と afterCommit 構造に変更。
- **P1-08**: retention_until の now() フォールバック誤算出 → 起算日未確定時の null 維持に修正。
- **P1-09**: 単体テストの自己承認実検証漏れ → SecurityContext を正しく設定したアサートに修復。

### Round 2 指摘事項（Fix Commit `1c4d0d0`）
- **S04-R02-P0-01 (V1 と V67 のスキーマ不整合)**: `V1__create_tables.sql` の `t_document_version` DDL に `tenant_id`、`NOT NULL`、`uk_document_idempotency` 4列インデックスを同期追加。
- **S04-R02-P1-01 (heap全展開の解消)**: 一時ファイル経由のストリーミング処理へ改善。
- **S04-R02-P1-02 (スキャン判定の fail-closed 化)**: `CLEAN` 以外はすべて 400 で拒否するよう修正。
- **S04-R02-P1-03 (認可の DataScope / t_document_link 対応)**: `t_document_link` 認可判定を追加。
- **S04-R02-P1-05 (テスト妥当性・DB統合テスト追加)**: `DocumentServiceImplH2Test`（H2 DB 実SQL・UNIQUE制約検証）を追加。

### Round 3 指摘事項（Fix Commit `5e02d28` 後の修正）
- **S04-R03-P0-01 (photo_url 誤変更の復元)**: `FileScopeValidationService.java` で `photo_path` と誤変更されていたカラム名を原本通りの `photo_url` へ即時復元。
- **S04-R03-P0-02 (FlywayMigrationSmokeTest 過去アサートの完全復元)**: 削減されていた V14〜V66_1 の全 105 件のアサートおよび `assertIndexExists` ヘルパーを完全復元し、S04 用の 6 テーブル・`tenant_id`・4列 UNIQUE アサートと併存させた。
- **S04-R03-P1-01 (review-ledger.md 復元 & append-only 運用)**: T021 Provisional Mapping 表、FileReferenceProvider インベントリ、S04-NOTE-1 を完全に復元。
- **S04-R03-P1-02 (認可の和集合 OR 判定修復)**: `FileScopeValidationService` で `t_document_link` 判定を積集合（AND）から和集合（OR: いずれかのリンク先で可視なら許可）に修復。
- **S04-R03-P1-04 (verifyIntegrity テストの復元)**: `DocumentServiceImplTest` で削除されていた `verifyIntegrity` のハッシュ不一致・Storage 不在テストを復元・拡充。

---

## 変更ファイル記録

| task | ファイル | 変更種別 | 内容 |
|---|---|---|---|
| T021 | `.kiro/specs/legal-document-ledger-archive/review-ledger.md` | 変更 | T021 成果物・S04-NOTE-1 の完全復元、Round 3 修正記録追記 |
| T022 | `src/main/resources/db/migration/V67__document_archive.sql` | 変更 | DDL修復（IF NOT EXISTS, tenant_id, m_menuシード） |
| T022 | `src/main/resources/db/migration/V1__create_tables.sql` | 変更 | V67 相当 DDL（tenant_id, 4列UNIQUE）を完全同期 |
| T022 | `src/test/resources/sql/schema-document-archive-h2.sql` | 変更 | H2用 DDL（tenant_id 4列UNIQUE追加） |
| T022 | `src/main/java/com/ses/entity/DocumentVersion.java` | 変更 | tenantId フィールド追加 |
| T022 | `src/main/java/com/ses/mapper/DocumentVersionMapper.java` | 変更 | findByIdempotencyKey に tenantId 追加 |
| T022 | `src/main/java/com/ses/service/impl/DocumentServiceImpl.java` | 変更 | ストリーミング化、fail-closed スキャン、CAS・hold判定 |
| T022 | `src/main/java/com/ses/service/storage/impl/LocalDocumentStorage.java` | 変更 | 実ファイルシステム保存・ストリーミング対応 |
| T022 | `src/main/resources/messages_ko.properties` | 変更 | エラーメッセージ 13キー追加 |
| T022 | `src/main/resources/messages_zh_CN.properties` | 変更 | エラーメッセージ 13キー追加 |
| T022 | `src/test/java/com/ses/service/impl/DocumentServiceImplTest.java` | 変更 | 単体テストアサーション強化・verifyIntegrityテスト復元 |
| T022 | `src/test/java/com/ses/service/impl/DocumentServiceImplH2Test.java` | 新規作成 | H2 DB 実SQL・UNIQUE制約統合テスト |
| T023 | `src/main/java/com/ses/service/security/impl/FileScopeValidationService.java` | 変更 | photo_url 復元、DataScope 和集合(OR)判定修復 |
| T023 | `src/test/java/com/ses/migration/FlywayMigrationSmokeTest.java` | 変更 | V14〜V66_1 全アサートの完全復元 & V67 併存検証 |
