# Database & Flyway Migration Review (Commit: 54edfd2b)

---

## 1. Flyway マイグレーション網羅性 (V1 〜 V108_3)

- 全 112 スクリプトの整合性・順序性を検証。
- `V1__create_tables.sql` のベースライン化に伴い、`V3` と `V8` は `SELECT 1;` の No-op スクリプトとして安全に維持。
- 後半のマイグレーション (`V106_1`, `V106_2`, `V107_1`, `V108_2`, `V108_3`) では `information_schema` による動的 SQL 冪等ガードが適用されており、実 MySQL 8.0 での再実行・修復テストに合格。

---

## 2. 論理削除 (Soft-Delete) と一意制約の設計

| テーブル | 制約名 | 実装方式 | 評価 | 備考 |
|---|---|---|---|---|
| `sys_user` | `uk_sys_user_username` | 単純 UNIQUE 列 | **LEGACY** | 論理削除後の同名再登録が DB レベルでエラーになる。 |
| `t_contract` | `uk_contract_no` | 単純 UNIQUE 列 | **LEGACY** | 論理削除後の契約番号再利用が不可。 |
| `t_bp_payment` | `uk_bp_payment_active_layer` | VIRTUAL 生成列 (削除時NULL) | **MODERN** | 論理削除後の再作成が安全に許容される。 |
| `m_customer_contact` | `uk_customer_contact_active_primary` | VIRTUAL 生成列 (削除時NULL) | **MODERN** | 主担当者の履歴管理を安全に実現。 |
| `t_digital_invoice` | `uk_digital_invoice_send` | STORED 生成列 (取消時NULL) | **MODERN** | 送信取消後の再キューイングが可能。 |
| `m_integration_connection`| `uk_conn_tenant_product_company_active` | STORED 生成列 (削除時NULL) | **MODERN** | 連携先再登録が可能。 |

- **改善推奨 [ACC-DB-P2-001]**: レガシーテーブル (`sys_user`, `t_contract`) についても、VIRTUAL 生成列スロット方式へ順次移行することを推奨。

---

## 3. 財務集計と明細の整合性検証

- 月次締め (`t_monthly_closing_record`) において、対象月の作業報告書 (`t_work_record`)、請求データ (`t_invoice`)、BP 支払データ (`t_bp_payment`) の集計値が JSON スナップショットと明細テーブル間で 1 円の齟齬もなく整合することを確認。
