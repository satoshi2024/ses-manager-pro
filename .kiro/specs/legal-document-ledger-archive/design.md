# Design — 法定文書台帳・電子保存

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

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

## 6. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`。ここには本spec固有の行と逸脱だけを書く。

### 6.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| 相手先名称 | 顧客/BP masterの現在名 | — | `t_document.counterparty_name_snapshot` | **常にsnapshot** | 相手先なし（社内文書） |
| 文書本体 | 最新`version_no` | `t_document_version`（append-only） | 各versionがsnapshot | version_no指定、無指定は最新 | — |
| 金額/取引日 | `t_document`列 | 訂正versionで新documentを作らず版で表現 | 版ごとに保持 | 版の`created_at`基準 | 金額なし文書（契約書等） |
| retention_until | 導出値 | — | 確定時に固定 | `m_document_type.retention_start_rule`＋取引日 | 未確定（下書き）。**廃棄候補に入れない** |
| legal_hold | `t_document.legal_hold_flag` | 監査ログ | — | 現在値のみ | holdなし |
| scan_status | version単位 | — | — | 現在値のみ | **未scan扱い＝閲覧不可**（NULLをcleanとみなさない） |

`retention_until IS NULL` と `retention_until <= today` を同一視しない。前者は「起算日未確定」であり
廃棄候補から**除外**する。§1.1のNULL/不存在区別に該当する。

### 6.2 主体 × 操作 × 可見母集団

文書の母集団は`t_document_link`が指す業務entityのscopeから導出する。document固有のACLを別に作らない。

| 主体 | list/detail/count | export(ZIP)/download | notification | scheduler/async |
|---|---|---|---|---|
| 管理者 | 全件 | 全件 | 廃棄承認依頼 | retention判定・廃棄候補抽出 |
| マネージャー | link先業務entityの組織scope ∩ DataScope | 同左 | 自組織の期限文書 | — |
| 営業 | link先の担当顧客/契約/要員（既存DataScope）。組織で追加制限しない | 同左 | 自担当の文書公開/差戻し | — |
| HR | 要員関連文書のみ | 同左 | — | — |
| 要員 | **本specでは非公開**（S14で本人scopeとして開放） | — | — | — |
| portal user | **本specでは非公開**（S13で公開allow-listとして開放） | — | — | — |
| scheduler principal | 全件（retention/整合検証） | 生成のみ、配布しない | 完了通知は依頼者へ | hash検証・orphan cleanup |

- link先が複数ある文書は**いずれか1つでも可視なら可視**（和集合）。積集合にすると契約と請求に紐づく
  文書が営業から見えなくなる。
- ZIP exportとdownloadは一覧と同じSQL母集団を通す。job化しても母集団は**job作成時のrequester**で固定する。
- 逸脱: 廃棄承認は組織scopeではなく**管理者固定**。理由: 物理削除は組織横断で不可逆（R4.3）。

### 6.3 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| `t_document` 下書き | →確定 | 状態CAS | 同一文書の同時確定 | 下書きへ戻す |
| 確定 | →訂正済 / →取消 / →廃棄済 | 状態CAS＋`version` | 訂正と廃棄の同時要求 | **物理削除しない。訂正/取消versionで表現** |
| 廃棄済 | 終端 | — | — | backupからのみ復元 |
| `t_document_version` | append-only（遷移なし） | `UNIQUE(document_id, version_no)` | 同時addVersion | 版を作らず失敗させる |
| disposal 候補 | →承認待ち→承認済→廃棄実行済 / →却下 | 状態CAS | 二重承認 | 承認取消は却下で表現 |

- 生成系の冪等キー: `UNIQUE(source_type, business_key, version_discriminator)`。
  同じ請求書の再生成・CloudSign再同期で2件目を作らない（R2.2 / R2.3）。
- storage put 成功後にDB commitが失敗した場合、**storage側をorphanとして残す**。
  補償で消すのは`cleanup-safety-hours`経過後。DB rollbackに合わせて即delete しない
  （§3.1のrollbackでcacheを進めない、と同じ理由）。
- `verifyIntegrity`はread-only。hash不一致で自動修復・自動削除を**しない**。findingとして提示する。

## 7. テスト

version不変、hash改ざん、search境界、ZIP再読込、ACL、tenant scope、large stream、storage失敗補償、
legal hold/disposal approval、CloudSign fixture。

