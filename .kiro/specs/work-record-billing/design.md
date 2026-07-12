# Design Document — 月次実績工数・請求・支払(P5)

## 1. DDL(`sql/005_create_work_record_billing.sql`)

```sql
CREATE TABLE t_work_record (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  contract_id    BIGINT NOT NULL COMMENT '契約ID',
  work_month     CHAR(7) NOT NULL COMMENT '対象月(YYYY-MM)',
  actual_hours   DECIMAL(5,1) NOT NULL COMMENT '実績時間(h)',
  billing_amount DECIMAL(12,0) COMMENT '請求金額(円・精算計算済)',
  payment_amount DECIMAL(12,0) COMMENT '支払金額(円・BP原価側)',
  status         ENUM('入力中','確定') DEFAULT '入力中' COMMENT 'ステータス',
  remarks        VARCHAR(500) COMMENT '備考',
  created_by     BIGINT COMMENT '登録者ID',
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_work_record (contract_id, work_month),
  INDEX idx_work_record_month (work_month),
  CONSTRAINT fk_workrecord_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='月次実績工数';

CREATE TABLE t_invoice (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  invoice_no    VARCHAR(30) NOT NULL UNIQUE COMMENT '請求書番号(INV-YYYYMM-NNNN)',
  customer_id   BIGINT NOT NULL COMMENT '顧客ID',
  billing_month CHAR(7) NOT NULL COMMENT '対象月(YYYY-MM)',
  subtotal      DECIMAL(12,0) NOT NULL COMMENT '小計(円)',
  tax           DECIMAL(12,0) NOT NULL COMMENT '消費税(円)',
  total         DECIMAL(12,0) NOT NULL COMMENT '合計(円)',
  status        ENUM('未送付','送付済','入金済') DEFAULT '未送付' COMMENT 'ステータス',
  issued_date   DATE COMMENT '発行日',
  paid_date     DATE COMMENT '入金日',
  remarks       VARCHAR(500),
  created_by    BIGINT,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag  TINYINT DEFAULT 0,
  INDEX idx_invoice_customer (customer_id),
  INDEX idx_invoice_month (billing_month),
  CONSTRAINT fk_invoice_customer FOREIGN KEY (customer_id) REFERENCES m_customer(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='請求書';

CREATE TABLE t_invoice_item (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  invoice_id     BIGINT NOT NULL,
  work_record_id BIGINT NOT NULL UNIQUE COMMENT '二重請求防止(1実績=1明細)',
  description    VARCHAR(300) COMMENT '摘要(要員名・案件名など)',
  amount         DECIMAL(12,0) NOT NULL COMMENT '金額(円)',
  FOREIGN KEY (invoice_id) REFERENCES t_invoice(id) ON DELETE CASCADE,
  FOREIGN KEY (work_record_id) REFERENCES t_work_record(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='請求書明細';

CREATE TABLE t_bp_payment (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  work_record_id BIGINT NOT NULL UNIQUE COMMENT '対象実績',
  amount         DECIMAL(12,0) NOT NULL COMMENT '支払金額(円)',
  status         ENUM('未払','支払済') DEFAULT '未払',
  paid_date      DATE COMMENT '支払日',
  remarks        VARCHAR(500),
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (work_record_id) REFERENCES t_work_record(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP支払';

-- メニュー登録
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) VALUES
 ('work-record', '勤怠・実績', '/work-record', '/api/work-records', 55),
 ('invoice',     '請求・支払', '/invoice',     '/api/invoices',     56);
INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key IN ('work-record','invoice')
UNION ALL SELECT '営業', id FROM m_menu WHERE menu_key IN ('work-record','invoice')
UNION ALL SELECT 'マネージャー', id FROM m_menu WHERE menu_key IN ('work-record','invoice')
UNION ALL SELECT 'HR', id FROM m_menu WHERE menu_key = 'work-record';
```

※ `/api/bp-payments` は `invoice` メニューの管理下に置くため `api_prefix` を追加登録するか、
`/api/invoices/bp-payments` 配下に寄せる(実装時は後者を推奨: メニュー1行で済む)。

## 2. 精算計算(`service/billing/SettlementCalculator.java`)

純粋関数(P4 の `MatchScoreCalculator` と同じ流儀):

```java
public final class SettlementCalculator {
    /**
     * @param unitPriceMan 単価(万円, 売上 or 原価)
     * @param hoursMin/hoursMax 精算下限/上限(h, null=固定)
     * @param actualHours 実績時間
     * @return 精算後金額(円, 1円未満切り捨て)
     */
    public static BigDecimal calc(BigDecimal unitPriceMan, BigDecimal hoursMin,
                                  BigDecimal hoursMax, BigDecimal actualHours) {
        BigDecimal base = unitPriceMan.multiply(BigDecimal.valueOf(10000));
        if (hoursMin == null || hoursMax == null) return base;                    // 固定
        if (actualHours.compareTo(hoursMax) > 0) {                                // 超過
            BigDecimal over = base.divide(hoursMax, 10, RoundingMode.HALF_UP);    // 超過単価 = 売上/上限
            return base.add(actualHours.subtract(hoursMax).multiply(over)).setScale(0, RoundingMode.DOWN);
        }
        if (actualHours.compareTo(hoursMin) < 0) {                                // 控除
            BigDecimal under = base.divide(hoursMin, 10, RoundingMode.HALF_UP);   // 控除単価 = 売上/下限
            return base.subtract(hoursMin.subtract(actualHours).multiply(under)).setScale(0, RoundingMode.DOWN);
        }
        return base;
    }
}
```

`fraction_rule` 列に個別ルールが入っている場合の解釈は本フェーズでは行わず(自由記述のため)、既定の切り捨てで統一。備考として画面に表示するに留める。

## 3. エンティティ / サービス

- `entity/WorkRecord` / `Invoice` / `InvoiceItem` / `BpPayment` + 各 Mapper(`BaseMapper`)。
- `WorkRecordMapper.selectMonthlyGrid(String workMonth)`: 注釈 `@Select` で
  当月稼動中契約 × 要員名 × 案件名 × 既入力実績を LEFT JOIN で一括取得(`WorkRecordGridDto`)。
  「当月稼動中」= `start_date <= 月末 AND (end_date IS NULL OR end_date >= 月初) AND status IN ('稼動中','終了')`。

```java
public interface WorkRecordService extends IService<WorkRecord> {
    List<WorkRecordGridDto> monthlyGrid(String workMonth);
    WorkRecord saveHours(Long contractId, String workMonth, BigDecimal actualHours, String remarks); // 精算計算込み upsert
    void confirmMonth(String workMonth);      // 当月の入力中→確定 + BP契約分の t_bp_payment 生成
    void reopenMonth(String workMonth);       // 管理者のみ(Controller 側で role チェック)
}
public interface InvoiceService extends IService<Invoice> {
    Invoice generate(Long customerId, String billingMonth);  // @Transactional
    String generateInvoiceNo(String billingMonth);           // INV-YYYYMM-NNNN(P2 の契約採番と同方式)
    void changeStatus(Long id, String status, LocalDate paidDate);
    InvoiceDetailDto detail(Long id);                        // 印刷ビュー用(顧客・明細込み)
}
```

`saveHours`: 契約取得 → `SettlementCalculator.calc`(selling / cost 両方)→ upsert。確定済みなら `BusinessException("確定済みの月は編集できません")`。
`generate`: 対象 = 顧客の確定済み実績のうち `t_invoice_item` 未紐付け(`work_record_id NOT IN ...`)。0件なら `BusinessException`。小計→税(10%, 切り捨て)→合計。

## 4. API

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/work-records/grid?month=YYYY-MM` | 月次グリッド |
| PUT | `/api/work-records` | 実績保存(body: contractId, workMonth, actualHours, remarks) |
| POST | `/api/work-records/confirm?month=` | 月次確定 |
| POST | `/api/work-records/reopen?month=` | 確定解除(管理者のみ — `SecurityConfig` で制限) |
| GET | `/api/invoices` | 請求書一覧(月・顧客・状態フィルタ、Page) |
| POST | `/api/invoices/generate` | 請求書生成(body: customerId, billingMonth) |
| GET | `/api/invoices/{id}` | 印刷ビュー用詳細 |
| PUT | `/api/invoices/{id}/status` | 状態変更(送付済/入金済 + paid_date) |
| GET | `/api/invoices/bp-payments?month=&status=` | BP支払一覧 |
| PUT | `/api/invoices/bp-payments/{id}` | 支払済化(paid_date) |

## 5. 画面

- `templates/work-record/list.html` + `modules/work-record.js` + `WorkRecordPageController(/work-record)`:
  月セレクタ(既定=当月)+ グリッド(要員/案件/契約No/精算幅/実績入力欄/請求額/支払額)。行ごと保存(blur or 保存ボタン)。「月次確定」ボタン(SweetAlert2 確認)。確定後は入力欄 readonly。
- `templates/invoice/list.html` + `modules/invoice.js` + `InvoicePageController(/invoice)`:
  タブ2枚 — ①請求書(一覧・生成モーダル・状態変更)②BP支払(一覧・支払済化)。
- `templates/invoice/print.html`(`/invoice/{id}/print`): `layout/base` を**継承しない**自己完結 A4 印刷ビュー(error.html と同じ方針)。宛名・番号・明細表・小計/税/合計・振込先(P8 のシステム設定から取得、未設定なら空欄)。「印刷」ボタンで `window.print()`。
- ダッシュボード: `DashboardServiceImpl` の月次集計で、当月に確定実績があれば `SUM(billing_amount)` / `SUM(billing_amount - payment_amount)` を採用、無ければ従来ロジック。グラフのツールチップに「実績/見込み」を表示。

## 6. テスト

- `SettlementCalculatorTest`: 範囲内/超過/控除/固定/境界(ちょうど上限・下限)/端数切り捨て。
- `WorkRecordServiceImplTest`(H2): upsert・確定後編集ロック・確定時の BP支払生成(BP契約のみ)。
- `InvoiceServiceImplTest`(H2): 生成(集計・税・採番)・二重請求拒否・0件エラー。
- H2 スキーマに 4 テーブル追加。
