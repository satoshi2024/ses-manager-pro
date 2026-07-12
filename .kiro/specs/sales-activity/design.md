# Design Document — 営業活動管理(P6)

## 1. DDL(`sql/006_create_sales_activity.sql`)

```sql
CREATE TABLE t_sales_activity (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  customer_id      BIGINT NOT NULL COMMENT '顧客ID',
  activity_type    ENUM('商談','訪問','電話','メール','その他') NOT NULL COMMENT '活動種別',
  activity_date    DATE NOT NULL COMMENT '活動日',
  title            VARCHAR(200) NOT NULL COMMENT 'タイトル',
  content          TEXT COMMENT '内容',
  next_action_date DATE COMMENT '次回アクション予定日',
  completed_flag   TINYINT DEFAULT 0 COMMENT '完了フラグ',
  created_by       BIGINT COMMENT '登録者ID',
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag     TINYINT DEFAULT 0 COMMENT '論理削除フラグ',
  INDEX idx_activity_customer (customer_id),
  INDEX idx_activity_next_action (next_action_date),
  CONSTRAINT fk_activity_customer FOREIGN KEY (customer_id) REFERENCES m_customer(id) ON DELETE RESTRICT,
  CONSTRAINT fk_activity_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='営業活動';
```

`deleted_flag` あり → エンティティに `deletedFlag` を持たせ論理削除を効かせる(既存エンティティと同様)。

## 2. エンティティ / サービス / API

- `entity/SalesActivity.java` + `SalesActivityMapper`(BaseMapper のみ)+ `SalesActivityService`(IService、純CRUD)。
- 顧客実績サマリは `dto/customer/CustomerSummaryDto.java`(projectCount / proposalCount / wonCount / winRate / activeContractCount / pendingFollowUpCount)。
  `CustomerApiController` に集計メソッドを追加し、`LambdaQueryWrapper` の `count()` を組み合わせて算出
  (成約率 = 成約 ÷ (成約+見送り)、分母0なら null → 画面で「—」表示)。

### API(すべて既存 `/api/customers` 配下 → メニュー権限そのまま)

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/customers/{id}/summary` | 実績サマリ |
| GET | `/api/customers/{id}/activities?current=&size=&type=` | 活動一覧(activity_date 降順、Page) |
| POST | `/api/customers/{id}/activities` | 登録(created_by = `SecurityUtils.currentUserId()`) |
| PUT | `/api/customers/{id}/activities/{activityId}` | 更新 |
| PUT | `/api/customers/{id}/activities/{activityId}/complete` | 完了化 |
| DELETE | `/api/customers/{id}/activities/{activityId}` | 論理削除 |
| GET | `/api/customers/follow-ups` | 全顧客の要フォロー一覧(next_action_date <= 今日 AND completed_flag=0) |

コントローラーは `controller/api/SalesActivityApiController`(`@RequestMapping("/api/customers")`)として分離。

## 3. 画面

- `controller/page/CustomerPageController` に `GET /customer/{id}` → `customer/detail` を追加。
- `templates/customer/detail.html` + `static/js/modules/customer-detail.js`:
  - 上段: 基本情報カード + サマリ KPI(案件数/提案数/成約率/稼動中契約)
  - 下段: 活動タイムライン(種別アイコン: 商談=bi-people, 訪問=bi-geo-alt, 電話=bi-telephone, メール=bi-envelope)+ `#activityModal`(既存 CRUD モーダルパターン)
  - 要フォロー活動は行を警告色表示 + 「完了」ボタン
- `customer/list.html`: 行に詳細リンク + 要フォロー件数バッジ(`follow-ups` を一覧ロード時に集計)。

## 4. P3 連携(任意依存)

`NotificationGenerateService` に `followUpDue()` ルールを追加(P3 実装済みの場合):
`SELECT` 期限到来・未完了活動 → `publish("FOLLOW_UP", "【フォロー】" + 顧客名, title, "/customer/{id}", "FOLLOW_UP:{id}:{date}")`。
P6 を P3 より先に実装する場合はこのルール追加を P3 側のタスクに繰り越す。

## 5. テスト

- `SalesActivityApiControllerTest`(H2): CRUD・完了化・論理削除(削除後一覧に出ない)・created_by 自動設定。
- サマリ集計テスト: 成約率の分母0ケース。
- H2 スキーマに `t_sales_activity` 追加。
