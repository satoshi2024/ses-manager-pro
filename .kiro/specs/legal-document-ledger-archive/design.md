# Design — 法定文書台帳・電子保存

## 1. DDL（予約V64）

- `m_document_type(code, name, direction, retention_years, retention_start_rule, legal_hold_supported)`。
- `t_document(id, tenant_id, legal_entity_id, document_type, document_no, title, counterparty_type/id/name_snapshot,
  transaction_date, amount, currency, direction, status, retention_until, legal_hold_flag, version)`。
- `t_document_version(id, document_id, version_no, storage_key, original_name, content_type, size_bytes,
  sha256, source_type, external_id, scan_status, change_reason, created_by/at)`。
- `t_document_link(document_id, target_type, target_id)`。
- `t_document_access_log(document_id, version_id, action, user_id, ip_hash, occurred_at)`。
- `t_document_disposal_request(document_id, requested_by, approved_by, status, reason, disposed_at)`。

`counterparty_name_snapshot`は検索/証跡用。現在の顧客/BP名称変更で過去文書の相手先表示を変えない。

## 2. Storage abstraction

- `DocumentStorage`: put(InputStream), open(key), delete(key), exists(key), checksum(key)。
- `LocalDocumentStorage`（既存移行用）と`S3DocumentStorage`（multipart、SSE、bucket versioningは運用設定）。
- 保存順: quarantine put→scan→hash→DB tx metadata→promote。DB失敗時はorphan cleanup対象。
- binary downloadはcontrollerからstreamし、全byte[]保持を避ける。

## 3. Document service

- `registerGenerated`, `registerReceived`, `addVersion`, `link`, `placeLegalHold`, `requestDisposal`, `verifyIntegrity`。
- `(source_type, external/business key, version discriminator)`で冪等。
- PDF serviceは既存戻り値を壊さず、呼出側でdocument登録するadapterから段階移行。

## 4. 検索/export

- `/document-archive`、`/api/documents`。
- DB検索は日付/金額/相手先/種別index。binary全文検索は初期対象外。
- ZIP exportは件数上限、非同期job、期限付きdownload、完了通知。manifestとhash verification resultを含む。

## 5. 既存file移行

`t_contract_document`, proposal skill sheet, engineer photo, resume/project/BP ingestion原本をinventory化。
法定/取引文書だけをarchiveへ移行し、写真等は共通storage adapterのみ利用。移行はcopy→hash検証→参照切替→
旧file保留の順で、即削除しない。

## 6. テスト

version不変、hash改ざん、search境界、ZIP再読込、ACL、tenant scope、large stream、storage失敗補償、
legal hold/disposal approval、CloudSign fixture。

