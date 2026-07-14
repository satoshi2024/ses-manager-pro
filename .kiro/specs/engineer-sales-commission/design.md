# Design Document — 要員担当営業・営業成績/インセンティブ管理

## 1. DDL(`db/migration/V14__engineer_sales_and_commission.sql`)

### 1.1 要員担当営業テーブル

履歴は `released_at` で表現する(論理削除は使わない)。`deleted_flag` は MyBatis-Plus の
`@TableLogic` で全クエリから除外されるため、履歴 UI が成立しない。現任 = `released_at IS NULL`。
`deleted_flag` は誤登録の削除用に残す。

```sql
CREATE TABLE t_engineer_sales (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id   BIGINT NOT NULL COMMENT '要員ID',
  sales_user_id BIGINT NOT NULL COMMENT '担当営業ユーザーID',
  primary_flag  TINYINT NOT NULL DEFAULT 0 COMMENT '主担当フラグ(1:主担当)',
  assigned_at   DATE NOT NULL COMMENT '担当開始日',
  released_at   DATE NULL COMMENT '担当解除日(NULL=現任)',
  remarks       VARCHAR(500) COMMENT '備考',
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag  TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  INDEX idx_engsales_engineer (engineer_id),
  INDEX idx_engsales_sales_user (sales_user_id),
  CONSTRAINT fk_engsales_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id),
  CONSTRAINT fk_engsales_sales_user FOREIGN KEY (sales_user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='要員担当営業';
```

「現任主担当は要員あたり1名」は MySQL に部分ユニーク制約がないためサービス層で保証する
(同時実行で二重主担当が生じ得るが、主担当変更操作で自己修復。v1 許容)。

### 1.2 インセンティブ既定規則(`m_system_config` 再利用、新テーブルなし)

管理者限定の編集 UI(`/system-config`)・キャッシュ付き型アクセサ(`SystemConfigService`)が既存のため再利用。

```sql
INSERT INTO m_system_config (config_key, config_value, description) VALUES
  ('commission.base-type', '粗利', 'インセンティブ計算基準（粗利 または 売上）'),
  ('commission.rate', '5.0', 'インセンティブ既定率（%）');
```

### 1.3 契約テーブル拡張

```sql
ALTER TABLE t_contract
  ADD COLUMN sales_user_id BIGINT NULL COMMENT '成約担当営業ID' AFTER customer_id,
  ADD COLUMN commission_base_type VARCHAR(10) NULL COMMENT 'インセンティブ基準上書き(粗利/売上、NULL=既定)',
  ADD COLUMN commission_rate DECIMAL(5,2) NULL COMMENT 'インセンティブ率上書き(%、NULL=既定)';
ALTER TABLE t_contract
  ADD INDEX idx_contract_sales_user (sales_user_id),
  ADD CONSTRAINT fk_contract_sales_user FOREIGN KEY (sales_user_id) REFERENCES sys_user(id);
```

### 1.4 メニュー seed(V7 の analytics パターン踏襲)

```sql
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('sales-performance', '営業成績', '/sales-performance', '/api/sales-performance', 66);

INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id FROM m_menu m
JOIN (SELECT '管理者' AS role UNION ALL SELECT '営業' UNION ALL SELECT 'マネージャー') r
WHERE m.menu_key = 'sales-performance';
```

`SecurityConfig` 変更不要(`MenuPermissionFilter` が api_prefix で 403 制御、
規則編集は既存の管理者限定 `/api/system-configs` に相乗り)。

## 2. バックエンド

### 2.1 新規

- `entity/EngineerSales.java` — `@TableName("t_engineer_sales")`、`BaseEntity` 継承。
  `engineerId` / `salesUserId` / `primaryFlag`(Integer) / `assignedAt` / `releasedAt`(LocalDate) / `remarks`
- `mapper/EngineerSalesMapper.java` — `BaseMapper<EngineerSales>` + `@Select`(sys_user join のため):
  - `selectActiveWithNames(engineerId)` / `selectHistoryWithNames(engineerId)` → `List<EngineerSalesDto>`
  - `selectActivePrimaryByEngineerIds(List<Long>)` → `List<EngineerPrimarySalesDto>`(一覧系の一括取得)
  - `countActivePrimaryGroupBySalesUser()` → 営業別担当要員数(t_engineer の deleted_flag=0 と join)
- `service/EngineerSalesService` + `impl/EngineerSalesServiceImpl`(`IService<EngineerSales>`):
  - `assign(engineerId, salesUserId, primaryFlag, remarks)` — `@Transactional`。役割検証/重複拒否/初回強制主担当/主担当降格
  - `setPrimary(engineerId, assignmentId)` / `release(engineerId, assignmentId)`(released_at 設定、主担当解除ガード)
  - `findPrimarySalesUserId(engineerId)` / `mapPrimaryByEngineerIds(List<Long>)`
- `service/SalesPerformanceService` + impl — 3章の集計・計算
- DTO: `dto/engineersales/`(EngineerSalesDto / EngineerPrimarySalesDto / SalesUserOptionDto)、
  `dto/salesperformance/`(SalesPerformanceDto / CommissionRuleDto)、
  `dto/engineer/EngineerListDto`(Engineer 継承 + primarySalesUserId / primarySalesUserName)
- `controller/api/EngineerSalesApiController`(`@RequestMapping("/api/engineers")`)
- `controller/api/SalesPerformanceApiController` / `controller/page/SalesPerformancePageController`

### 2.2 変更

- `entity/Contract.java` — `salesUserId`(Long) / `commissionBaseType`(String) / `commissionRate`(BigDecimal) 追加
- `ContractServiceImpl.createDraftFromProposal()` — `EngineerSalesService` 注入、
  `contract.setSalesUserId(engineerSalesService.findPrimarySalesUserId(proposal.getEngineerId()))`
- `ContractRenewalServiceImpl` — 更新ドラフトに新3カラムを引き継ぐ
- `ContractMapper.selectPageWithNames` — `LEFT JOIN sys_user` で `salesUserName`、任意 `salesUserId` 絞り込み追加。
  `ContractListDto` / `ContractApiController.page` も追随
- `StatusConstants` — `COMMISSION_BASE_PROFIT="粗利"` / `COMMISSION_BASE_SALES="売上"`

### 2.3 API 一覧(すべて `ApiResult<T>`)

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/engineers/{id}/sales-reps` | 現任担当一覧 |
| GET | `/api/engineers/{id}/sales-reps/history` | 履歴含む一覧 |
| POST | `/api/engineers/{id}/sales-reps` | 割当 `{salesUserId, primaryFlag, remarks}` |
| PUT | `/api/engineers/{id}/sales-reps/{aid}/primary` | 主担当変更 |
| DELETE | `/api/engineers/{id}/sales-reps/{aid}` | 解除(released_at 設定) |
| GET | `/api/engineers/sales-user-options` | 営業ユーザー選択肢(role=営業, status=1) |
| GET | `/api/sales-performance?month=YYYY-MM` | 営業別成績(month 省略時=当月) |
| GET | `/api/sales-performance/commission-rule` | 既定規則の読み取り(編集は /system-config) |

## 3. 集計・インセンティブ計算(`SalesPerformanceServiceImpl`)

リアルタイム計算・台帳なし。対象月 `ym` につき一括ロード(契約 / 当月確定 work_record /
proposed_by 別提案集計 / 主担当数)→ メモリ内グルーピング(`DashboardServiceImpl` と同スタイル)。

- 行 = `role='営業'` 全ユーザー ∪ 契約の `sales_user_id` 出現ユーザー
- 担当要員数 = `countActivePrimaryGroupBySalesUser()`(as-of-now)
- 成約件数 = 契約 `created_at∈ym` かつ `renewed_from_contract_id IS NULL`
- 成約率 = 提案 `proposed_by`/`closed_at∈ym` の 成約÷(成約+見送り)、分母0→null
- 売上/粗利 = 契約ごとに: 確定 work_record あり→ `billing_amount` / `billing - payment`、
  なし→ `selling_price` / `selling - cost`(契約単位フォールバック。ダッシュボードとの口径差は仕様)
- インセンティブ = 契約ごとに
  `base = (基準==売上 ? 月売上 : 月粗利)`、`rate = 契約上書き ?? commission.rate`、
  `baseType = 契約上書き ?? commission.base-type`、`amount = max(0, floor(base×rate/100))` → ユーザー別合算

## 4. 画面

- **要員詳細**(`engineer/detail.html` + `engineer-detail.js`): 「担当営業」カード
  (現任表 + 追加モーダル `#salesRepModal` + 主担当変更/解除 + 履歴トグル)。
  割当は独立ライフサイクルのため編集モーダルではなく詳細画面に置く。
- **要員一覧**(`engineer/list.html` + `engineer.js`): 主担当営業列 + 絞り込み select。
  `EngineerApiController.page` に `salesUserId` パラメータ(既存 skillIds と同じ `inSql` 方式)。
- **契約**(`contract/list.html` + `contract.js`): モーダルに担当営業 select
  (新規時、要員選択で主担当を自動プリセット)+ 折りたたみ「インセンティブ個別設定」
  (基準 select: 既定/粗利/売上、率 input)。一覧に担当営業列 + 絞り込み。
- **待機一覧**(`analytics/index.html` + `analytics.js`): `BenchEngineerDto` に主担当2フィールド追加、
  `benchList()` で一括取得。列追加 + クライアントサイド絞り込み(API 署名不変)。
- **営業成績**(新規 `sales-performance/list.html` + `sales-performance.js`):
  `<input type="month">` + 成績テーブル(営業名/担当要員数/成約件数/成約率/稼動中契約数/売上/粗利/インセンティブ)
  + 既定規則表示と上書き注記。`layout/sidebar.html` に `allowedMenus.contains('sales-performance')` の `<li>`。
- i18n: `menu.salesPerformance` / `salesPerf.*` / `engineerSales.*` / `error.engineerSales.*` を4ファイルに追加。

## 5. テスト

- 既存修正(必須): `src/test/resources/sql/engineer-schema-h2.sql`(t_contract 3カラム + t_engineer_sales)、
  `AnalyticsServiceImplTest`(コンストラクタ引数追加)、`ContractServiceImplTest`(@Mock EngineerSalesService + 既定値ケース)
- 新規: `EngineerSalesServiceImplTest`(初回主担当/降格/重複拒否/役割拒否/解除ガード/released_at)、
  `SalesPerformanceServiceImplTest`(既定規則/上書き/売上基準/work_record 優先/月窓/分母0/切捨て/マイナス0)

## 6. リスク・確定口径

1. 成約率(提案口径 proposed_by)と成約件数(契約口径 sales_user_id)の帰属基準差 → 仕様として注記
2. 成約件数は契約更新を除外
3. マイナス粗利月のインセンティブは 0 円、円未満切捨て
4. 主担当唯一性はサービス層のみで保証(v1 許容)
5. 契約単位フォールバックとダッシュボード月一括フォールバックの口径差 → 円単位一致は保証しない
6. 契約 CSV/Excel 出力への担当営業追加は v1 対象外
7. `/api/engineers/sales-user-options` は契約画面からも呼ばれるため、
   engineer メニュー権限を持たないカスタム役割設定では選択肢が出ない(既定 seed では全役割付与のため実害なし)
