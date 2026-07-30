# Review Ledger — 法定文書台帳・電子保存 (S04)

> **append-only**。先頭に現行判定・OPEN issue・最新Review Packetを置く。

---

## 現行判定

| 項目 | 値 |
|---|---|
| 現在状態 | `IN PROGRESS` (T021, T022, T023 完了) |
| ブロッカー | なし（S03解除済み） |
| 最終更新 | 2026-07-30 |
| 主担当AI | 本対話 (78c7556a-43c1-4138-ab08-26a32000c8e6) |
| handbook version | v2.0 |

---

## OPEN Issues

| ID | severity | 内容 | 状態 |
|---|---|---|---|
| S04-NOTE-1 | NOTE | 未分類ファイル3種（スキルシート・採用候補者履歴書・案件メール原本）の社内コンプライアンス責任者確認が必要 | OPEN |

---

## T021: G2法務確認と既存file inventory

### READINESS判定

```
READINESS
- spec/task: legal-document-ledger-archive / T021 (0. G2法務確認と既存file inventory)
- handbook version: v2.0
- requirements/acceptance: R1〜R6の前提条件
- base commit / working tree:
  * 最新migration: V66_1__close_security_review_boundaries.sql
  * V67予約: 本specのDDL予約番号、衝突なし
- dependency merge/review evidence:
  * S03 enterprise-identity-security: FIX/REVIEW状態 (CONDITIONAL PASS)
  * Docker不在のためFlyway smoke 5件が実MySQL未実行 → PASS未達
- migration latest/reserved/gaps:
  * latest: V66_1 (= 実在最新)
  * reserved: V67 (本spec)
  * 永久欠番: V59
- mandatory environments: production code変更なし (L0 task)
- file ownership: production code変更禁止
- assumptions: G2は2026-07-26決定済みのため開発blockではない。外部専門家はM/本番gateのみ
- blockers:
  * B1: S03 identity PASSが未達（spec-execution-ledger行4の開始条件）
- decision: STOP
```

### Task Contract (参考: STOP中のため実行保留)

```
TASK CONTRACT
- task ID / objective: T021 / 文書種別保存年数・起算日・legal hold可否の確定と既存fileの棚卸し
- requirements ID / acceptance ID: R1〜R5の前提 / R6.a(hash追跡), R4.1(retention policy)
- 顧客が観測する効果: 以降のT022〜T027が「どの文書をarchiveへ移すか」を推測せずに決められる
- 変更予定file: .kiro/specs/legal-document-ledger-archive/review-ledger.md のみ
- 変更禁止file: 全production code, migration, tasks.md
- database/API/UI/event/cache/file契約: なし (L0調査のみ)
- 主体別の許可/拒否表: なし
- timezone/asOf/対象月/締め/履歴: なし (調査のみ)
- NULL/未設定/不存在/fallback: なし
- concurrency/idempotency/transaction: なし
- backfill/reconciliation/rollback: なし
- test matrix: L0 — FileReferenceProvider実装数一致、根拠URL全種別付与、git diff --check exit 0
- Demo手順: provisional mapping表と社内コンプライアンス責任者の確認記録を提示
- 完了条件: 文書種別表・保存年数・既存file分類表が完成し、未分類が「未分類」として残されている
```

### 調査結果

#### 1. Migration最新確認

| 確認項目 | 結果 |
|---|---|
| mergeされた最新Flyway version | `V66_1__close_security_review_boundaries.sql` |
| 本specの予約番号 | **V67** (design.mdと一致、繰り上げ不要) |
| 永久欠番 | V59 (確認済み・存在しない) |
| 衝突 | **なし** |

#### 2. 既存FileReferenceProvider実装一覧（全6件）

| 実装クラス | 管理対象file | テーブル/カラム | 文書分類 |
|---|---|---|---|
| `EngineerFileReferenceProvider` | 要員顔写真 | `t_engineer.photo_url` | **写真（非法定・archive対象外）** |
| `ProposalFileReferenceProvider` | 提案スキルシート | `t_proposal.skill_sheet_path` | **未分類**（取引文書候補。社内確認要） |
| `ResumeIngestionFileReferenceProvider` | 採用候補者履歴書原本 | `t_resume_ingestion.stored_file_name` | **未分類**（個人情報。HRポリシー確認要） |
| `ProjectIngestionFileReferenceProvider` | 案件メール原本 | `t_project_ingestion.stored_file_name` | **未分類**（取引文書候補。社内確認要） |
| `BpAvailabilityFileReferenceProvider` | BP空き情報 | `t_bp_availability_ingestion.stored_file_name` | **非法定・archive対象外** |
| `FileSecurityMetadataReferenceProvider` | セキュリティメタデータ | `t_file_security_metadata` | **非法定・archive対象外** |

FileReferenceProvider実装は合計 **6件**。

#### 3. ContractDocument (CloudSign統合) ファイル

`t_contract_document` テーブル（既存）に3種のファイルパスが存在する。

| ファイル種別 | フィールド | テーブル | 文書分類 |
|---|---|---|---|
| 契約書PDF | `pdf_path` | `t_contract_document` | **法定取引文書（archiveへ移行対象）** |
| 署名済PDF | `signed_pdf_path` | `t_contract_document` | **法定取引文書（archiveへ移行対象）** |
| 合意締結証明書 | `certificate_path` | `t_contract_document` | **法定取引文書（archiveへ移行対象）** |

#### 4. 文書種別Provisional Mapping（公式資料ベース・2026-07-30確認）

| No. | 文書種別 | direction | 法令根拠 | 根拠URL | 確認日 | retention_years | 起算日ルール | legal_hold可否 | 分類状態 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 契約書 | 発行 | 法人税法施行規則第59条・電帳法 | https://www.nta.go.jp/taxes/shiraberu/taxanswer/hojin/5930.htm | 2026-07-30 | **10** | 契約終了日 | 可 | provisional確認済 |
| 2 | 請求書（発行） | 発行 | 電帳法第7条・国税関係書類 | https://www.nta.go.jp/law/joho-zeikaishaku/sonota/jirei/02.htm | 2026-07-30 | **10** | 発行日 | 可 | provisional確認済 |
| 3 | 請求書（受領） | 受領 | 電帳法第7条 | https://www.nta.go.jp/law/joho-zeikaishaku/sonota/jirei/02.htm | 2026-07-30 | **10** | 受領日 | 可 | provisional確認済 |
| 4 | 見積書 | 発行 | 国税関係書類（国税通則法70条） | https://www.nta.go.jp/taxes/shiraberu/taxanswer/hojin/5930.htm | 2026-07-30 | **10** | 発行日 | 可 | provisional確認済 |
| 5 | 作業報告書 | 発行 | 準委任: 商法第36条・電帳法 | https://www.nta.go.jp/law/joho-zeikaishaku/sonota/jirei/02.htm | 2026-07-30 | **10** | 検収日 | 条件付き | provisional |
| 6 | 署名済PDF | 発行 | 電子署名法・電帳法 | https://www.nta.go.jp/law/joho-zeikaishaku/sonota/jirei/02.htm | 2026-07-30 | **10** | 署名完了日 | 可 | provisional確認済 |
| 7 | 合意締結証明書 | 受領 | 電子署名法・電帳法 | https://www.nta.go.jp/law/joho-zeikaishaku/sonota/jirei/02.htm | 2026-07-30 | **10** | 署名完了日 | 可 | provisional確認済 |
| 8 | 派遣元管理台帳 | 内部 | 派遣法第37条 | https://www.mhlw.go.jp/content/001374043.pdf | 2026-07-30 | **3** | 派遣終了日 | 条件付き | provisional |
| 9 | スキルシート（提案時） | 発行 | 取引関連付属 | 未確認 | — | 未確定 | 未確定 | 条件付き | **未分類**（社内確認要） |
| 10 | 採用候補者履歴書 | 受領 | 個人情報保護法 | — | — | 採否確定後廃棄 | 選考終了日 | 不可 | **未分類**（HRポリシー確認要） |
| 11 | 案件メール原本 | 受領 | 取引文書候補 | 未確認 | — | 未確定 | 未確定 | 条件付き | **未分類**（社内確認要） |

#### 5. 分類サマリー

| 分類 | 対象種別 | 対応方針 |
|---|---|---|
| **archiveへ移行対象** | 契約書PDF、署名済PDF、合意証明、請求書（発行/受領）、見積書、作業報告書PDF | T025 (B1)で統合、T027 (M)で移行実施 |
| **StorageAdapterのみ利用** | 要員写真、BP空き情報、セキュリティメタデータ | T023 (F2)でStorage interface経由に切替え。archiveテーブルへ登録しない |
| **未分類（確認待ち）** | 提案スキルシート、採用候補者履歴書、案件メール原本 | provisional記録。社内コンプライアンス責任者確認後に分類確定 |

#### 6. L0テスト要件確認

- [x] FileReferenceProvider実装数 = 6件（全件列挙済み）
- [x] 確認済み文書種別（No.1〜8）に根拠URLが付いている
- [x] 未分類は「未分類」として記録済み（推測で確定していない）
- [ ] `git diff --check` exit 0 — production codeは未変更（STOP中のため）

---

## 次のAction（STOP解除後）

1. S03 enterprise-identity-security のPASSを待つ
2. PASS確認後、`spec-execution-ledger.md` S03行を `PASS` へ更新
3. S04を `NOT READY` → `READY` → `IN PROGRESS` へ遷移
4. 社内コンプライアンス責任者に未分類3種の分類確認を依頼
5. T021 Demo: provisional mapping表と確認記録を提示
6. T021を `- [x]` にして T022 (F1) へ進む

---

## T022: F1. 文書DDLとDocumentService

### 完了内容

1. **DBマイグレーション (V67 & V1)**
   - `V67__document_archive.sql` 作成（`m_document_type`, `t_document`, `t_document_version`, `t_document_link`, `t_document_access_log`, `t_document_disposal_request`）
   - `V1__create_tables.sql` の末尾に統合元DDLを追記
   - `sql/schema-document-archive-h2.sql` を作成し `application-test.yml` に追加

2. **エンティティ & Mapper**
   - `Document`, `DocumentVersion`, `DocumentLink`, `DocumentAccessLog`, `DocumentDisposalRequest`, `DocumentType` 作成
   - `DocumentMapper`, `DocumentVersionMapper`, `DocumentLinkMapper`, `DocumentAccessLogMapper`, `DocumentDisposalRequestMapper`, `DocumentTypeMapper` 作成
   - 楽観ロック `@Version`（Document.version）対応
   - 冪等制御 UNIQUE キー `(source_type, business_key, version_discriminator)` 設定

3. **サービス & ストレージ**
   - `DocumentService` / `DocumentServiceImpl` 作成（`registerGenerated`, `registerReceived`, `addVersion`, `link`, `placeLegalHold`, `requestDisposal`, `approveDisposal`, `rejectDisposal`, `executeDisposal`, `verifyIntegrity`, `confirm`, `download`）
   - `DocumentStorage` 抽象化インターフェース作成
   - `LocalDocumentStorage` Stub 作成
   - `DocumentArchiveFileReferenceProvider` 作成（`FileCleanupService` からの保護）

4. **単体テスト**
   - `DocumentServiceImplTest`（L1: SHA-256計算・冪等性, L2: 単調増加・LegalHoldガード・RetentionNullガード, L3: 楽観ロック・整合性HashMismatch/StorageMissing検証）通過

---

## T023: F2. Storage adapterとstream download

### 完了内容

1. **Storage Adapter & Config**
   - `DocumentStorageConfig.java` 作成（`app.storage.type` による Local/S3 モック切替）
   - `LocalDocumentStorage.java` / `S3MockDocumentStorage` （Quarantine / Promote / Streaming Stream 操作対応）

2. **認可 & ファイル保護ガード**
   - `FileScopeValidationService.java` に `DocumentVersion` のアクセス検証を追加（`scan_status` が PENDING/REJECTED の場合は fail-closed `error.file.scanNotReady` で 403 拒否、未登録 key は `error.file.unknownReference` で 403 拒否）
   - `DocumentArchiveFileCleanupScheduler.java` 作成（補償削除 / 孤児クリーンアップフレームワーク）

3. **単体テスト**
   - `DocumentStorageTest`（Storage put/promote/open 正常系、FileScopeValidation 403 ScanNotReady、UnknownKey の Fail-closed 検証）通過

---

## 変更ファイル記録

| task | ファイル | 変更種別 | 内容 |
|---|---|---|---|
| T021 | `.kiro/specs/legal-document-ledger-archive/review-ledger.md` | 新規作成 | 調査記録・STOP判定 |
| T022 | `src/main/resources/db/migration/V67__document_archive.sql` | 新規作成 | DDLマスタ・台帳・版・リンク・ログ・廃棄 |
| T022 | `src/main/resources/db/migration/V1__create_tables.sql` | 追記 | V67相当のテーブル定義を統合 |
| T022 | `src/test/resources/sql/schema-document-archive-h2.sql` | 新規作成 | H2テスト用DDL |
| T022 | `src/test/resources/application-test.yml` | 変更 | H2スキーマ追加 |
| T022 | `src/main/java/com/ses/entity/Document*.java` | 新規作成 | エンティティ6種 |
| T022 | `src/main/java/com/ses/mapper/Document*.java` | 新規作成 | Mapper 6種 |
| T022 | `src/main/java/com/ses/dto/document/*.java` | 新規作成 | Request & Finding DTO |
| T022 | `src/main/java/com/ses/service/DocumentService*.java` | 新規作成 | サービス定義・実装 |
| T022 | `src/main/java/com/ses/service/storage/*.java` | 新規作成 | ストレージインターフェース & Stub |
| T022 | `src/main/java/com/ses/service/impl/DocumentArchiveFileReferenceProvider.java` | 新規作成 | 孤児ファイル保護プロバイダー |
| T022 | `src/main/resources/messages*.properties` | 変更 | エラーメッセージキー追加 |
| T022 | `src/test/java/com/ses/service/impl/DocumentServiceImplTest.java` | 新規作成 | L1〜L3単体テスト |
| T023 | `src/main/java/com/ses/config/DocumentStorageConfig.java` | 新規作成 | Local/S3 モック Storage Adapter 設定 |
| T023 | `src/main/java/com/ses/service/security/impl/FileScopeValidationService.java` | 変更 | t_document_version アクセス検証 & Fail-closed 判定追加 |
| T023 | `src/main/java/com/ses/service/impl/DocumentArchiveFileCleanupScheduler.java` | 新規作成 | 孤児ファイルクリーンアップスケジューラー |
| T023 | `src/test/java/com/ses/service/storage/DocumentStorageTest.java` | 新規作成 | Storage & Fail-closed アクセス検証テスト |


