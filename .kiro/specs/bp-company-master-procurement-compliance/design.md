# Design — BP会社マスタ・発注コンプライアンス

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V70, V71）

- **Migration**: 予約V70 (`db/migration/V70__bp_company_master_and_compliance.sql`), V71 (`db/migration/V71__bp_company_fix_and_procurement.sql`)
- **Migration 分割・べき等性設計方針**:
  - **State A (新規DB/空DB)**: `V1__create_tables.sql` に consolidative baseline として全テーブル・追加カラム (`t_contract` の発注コンプライアンス 6 列 `contract_date`, `job_description`, `work_location`, `inspection_due_date`, `payment_due_date`, `payment_method` 等) およびインデックスが含まれる。
  - **State B (既存DB/原V70適用済みDB)**: `V71` 内で MySQL の `information_schema` 判定付きストアドプロシージャを定義し、不足しているカラム、インデックス (`uk_bp_company_normalized`, `uk_affiliation_eng_from`)、および `m_system_config` の seed を安全に「無ければ追加（If Not Exists）」する。
  - **State C (復元済みV70)**: `V70` はコミット `a36b8cd` のチェックサムをそのまま維持する。
- **DB 権限・Migration 運用要件 (R4-P2-04 / R4-P2-05)**:
  - `V71` で動的ストアドプロシージャを作成・削除するため、DB Migration 実行ユーザーには `CREATE ROUTINE`, `ALTER ROUTINE`, `DROP ROUTINE` 権限が必要です。
  - MySQL DDL は非トランザクションのため、仮に Migration 途中で構文エラー等が発生して中断した場合は、`flyway_schema_history` に `success=0` の失敗レコードが残ります。この場合のリカバリルート:
    1. 不整合の原因（SQL文等）を修正
    2. `mvn flyway:repair` (または `./mvnw flyway:repair`) を実行して失敗レコードを消去
    3. アプリケーションを再起動して `V71` を正常再適用させる。
- `m_bp_company(id, tenant_id, legal_name, name_kana, normalized_name, entity_type, corporate_number,
  invoice_registration_number, capital_band, employee_band, address, representative, status,
  suspension_reason, suspension_start_date, suspension_end_date, suspension_approved_by, rating,
  primary_sales_user_id, compliance_applicability, applicability_checked_by/at, applicability_note, version)`。
- `t_bp_contact(bp_company_id, name, department, role, email, phone, primary_flag)`。
- `t_bp_bank_account(bp_company_id, encrypted_bank/branch/account, masked_label, valid_from/to, approval_status)`。
- `t_bp_terms(bp_company_id, effective_from/to, closing_day, payment_month_offset, payment_day,
  fee_bearer, payment_method, fee_bearer_exception_reason, fee_bearer_approved_by/at, max_payment_days, version)`。
- `t_engineer_bp_affiliation(engineer_id, bp_company_id, valid_from/to)`。
- `t_bp_evaluation(bp_company_id, period, scores..., comment, evaluated_by)`。
- `t_bp_price_negotiation(bp_company_id, requested_at, responded_at, status, requested_amount,
  agreed_amount, summary, document_id)`。
- `t_contract(contract_date, job_description, work_location, inspection_due_date, payment_due_date, payment_method)`。
- `BpAvailability.bp_company_id`, `BpPayment.bp_company_id`、表示snapshot列。

## 2. 移行

- 正規化: 法人格、全半角、空白を除く。ただし同一候補を自動mergeしない。
- `DISTINCT bp_company/payee_company_name`から仮BPを生成し、exact normalizationだけ自動link。
- 複数候補/空値は`migration_exception` CSVと管理画面で人が解決。
- 2リリースは旧文字列read fallback、writeはID必須。完了後fallback削除を別taskにする。

## 3. Service/API/UI

- `BpCompanyService`, `BpComplianceService`, `BpTermsResolver`。
- `/bp-company`, `/api/bp-companies`。detail tabs: 基本/連絡先/口座/条件/文書/要員/評価/価格協議/支払。
- bank DTOは末尾のみ返し、復号値を通常APIで返さない。
- Autocomplete/ingestionは候補score+理由を返し、confirm時にID必須。

## 4. 法令rule

- `ProcurementComplianceFinding(code,severity,message,field,sourceUrl)`を都度導出。
- ruleはconfig version付き。適用対象は人が確定、システムは不足/期限/不整合だけを検査。
- sourceはmaster roadmapのresearch-sourcesをUI helpへ静的link。

## 5. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`。ここには本spec固有の行と逸脱だけを書く。

### 5.1 時間・asOf

BP支払は「過去の支払先表示が後から変わらない」ことが要件（R2.3）。S02の月次帰属snapshotと同じ構造である。

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| BP会社名 | `m_bp_company.legal_name` | 変更は監査ログ | `BpPayment`の表示snapshot列 | **過去支払は常にsnapshot** | — |
| 支払条件 | 最新`t_bp_terms` | `effective_from/to`で版管理 | `BpPayment`の条件snapshot | **支払確定日**時点の有効版 | 条件未設定＝発注不可 |
| 要員のBP所属 | `valid_to IS NULL`の行 | `t_engineer_bp_affiliation` | — | 対象日を含む区間 | 区間なし＝自社要員 |
| 銀行口座 | `valid_to IS NULL`かつ承認済 | `valid_from/to` | 支払実行時に口座snapshot | **支払実行日**時点の承認済口座 | 口座未登録＝支払不可 |
| 適用区分 | `compliance_applicability` | 確認者/確認日を保持 | — | 現在値のみ | **未確認**（「非該当」ではない） |
| 評価 | 最新期間 | `t_bp_evaluation.period` | — | 期間指定 | 未評価 |

- `compliance_applicability IS NULL` を「適用対象外」として扱わない。**未確認**であり、
  R3.3の発注確定拒否またはR4.3のdashboard警告の対象になる。§1.1に該当する典型例。
- `t_bp_terms`の版切替は`effective_from`基準。**支払確定日**で解決し、画面表示時刻で解決しない。
- 逸脱: `m_bp_company`本体は履歴テーブルを持たず、監査ログ＋支払側snapshotで要件を満たす。
  根拠: 過去時点の会社属性を横断照会する要件（R1〜R5）が無く、S02の`t_organization_relation_history`
  相当を作るとコストに見合わない。**将来「過去時点のBP属性で集計」要件が出たら履歴テーブルが必要**になる。

### 5.2 期間代数（要員のBP所属）

`t_engineer_bp_affiliation`はS02の`t_user_organization`と同型なので、§1.2の全caseを適用する。
特にBP乗り換え（A社→B社）で次を確認する。

| case | 期待 |
|---|---|
| 同日乗換 | A社を前日で閉じ、B社を当日開始。**同日重複を作らない** |
| 未来の乗換予約 | 現在の所属を切らず、未来行を追加 |
| 遡及登録 | 既存区間を分割し、重複区間を作らない |
| 所属なし期間 | 空白期間を許容（自社要員化・離脱）。**直前の所属へfallbackしない** |

### 5.3 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 管理者 | 全件。口座は**マスク表示**（復号は承認経路のみ） | 全件（口座は末尾のみ） | 全リスク | 期限/評価batch |
| マネージャー | 組織scope ∩ DataScope | 同左 | 自組織のBPリスク | — |
| 営業 | 既存DataScope（担当BP）。**組織で追加制限しない** | 同左。**口座列を含めない** | 自担当BPの期限/評価 | — |
| HR | BP要員の所属のみ。会社の金銭条件は不可視 | 同左 | — | — |
| 要員 | 不可視 | — | — | — |
| portal user (BP) | **本specでは非公開**（S13で自社分のみ開放） | — | — | — |
| scheduler principal | 全件 | — | 宛先は担当営業・管理者に限定 | 文書期限/支払期日/未確認区分 |

- 銀行口座の復号値は**通常APIで返さない**。一覧・detail・exportすべて`masked_label`のみ。
  復号は支払実行と口座変更承認の経路だけ。
- `status = 取引停止`のBPは提案候補・発注候補の**SQLから除外**する。取得後filterにしない（R4.2）。
- 逸脱: 適用区分の確認操作は法務/管理者に限定し、組織scopeを適用しない。
  根拠: 法令確認は組織単位ではなく会社単位（R3.1）。

### 5.4 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| BP 下書き | →有効 | 状態CAS | — | 下書きへ |
| 有効 | →取引停止 / →統合済 | 状態CAS＋`version` | 停止と発注の競合 | 停止解除は理由必須 |
| 取引停止 | →有効（承認付き） | 状態CAS | — | — |
| 統合済 | 終端 | — | — | **自動統合しない**ため手動のみ |
| 口座 申請中 | →承認済 / →却下 | 状態CAS | 二重承認 | 承認前は支払先へ**反映しない** |
| 価格協議 要請中 | →回答済→合意 / →不成立 | 状態CAS | — | — |

- 重複候補（法人番号・登録番号・正規化名称・口座一致）は**警告のみ。自動mergeしない**（R1.5）。
  警告を出したうえで登録を通す。ブロックしない。
- 移行の冪等: 仮BP生成は`UNIQUE(tenant_id, normalized_name)`。再実行で重複仮BPを作らない。
- 移行期間中の`read fallback / write ID必須`は、writeパスに**旧文字列列へのINSERTが無いこと**を
  testで固定する。fallback削除taskの前提条件になる。
- コンプライアンスfindingは**都度導出**し永続化しない（既存`LaborComplianceService`と同方針）。
  ack/対応状態だけを永続化する。

## 6. テスト

重複候補、affiliation期間、terms resolver、snapshot、60日境界、具体日、fee bearer、bank masking、
migration reconciliation、data scope/tenant、取引停止選択除外。

