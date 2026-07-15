# Design Document — 多段階BP(協力会社)構造管理

## 1. DDL（V1のCREATE TABLE `t_bp_payment` に追記。新規ALTERマイグレーションは作らない）

```sql
CREATE TABLE t_bp_payment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_record_id BIGINT NOT NULL,
    layer_order INT NOT NULL DEFAULT 1 COMMENT '階層番号(1=技術者に最も近い一次請)',
    payee_company_name VARCHAR(200) COMMENT '支払先協力会社名',
    parent_payment_id BIGINT COMMENT '上位階層への自己参照(同一work_record_id内)',
    amount DECIMAL(12,0) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT '未払',
    paid_date DATE,
    remarks VARCHAR(500),
    deleted_flag TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_work_record_layer (work_record_id, layer_order),
    CONSTRAINT fk_bp_payment_parent FOREIGN KEY (parent_payment_id) REFERENCES t_bp_payment(id)
);
```
既存の `work_record_id UNIQUE` は `uk_work_record_layer (work_record_id, layer_order)` に置き換える。

H2テスト用スキーマ（`src/test/resources/sql/engineer-schema-h2.sql`等、`t_bp_payment`を含むもの）にも同一カラムを追記すること。

## 2. バックエンド

### 2.1 変更
- `entity/BpPayment.java`: `layerOrder`(Integer, デフォルト1), `payeeCompanyName`(String), `parentPaymentId`(Long) を追加。
- `service/impl/BpPaymentServiceImpl`: 新規階層追加時に同一work_record内での`layerOrder`重複チェック、`parentPaymentId`が同一`workRecordId`配下かの検証を追加。
- `mapper/BpPaymentMapper`: work_record_id単位で階層ツリーを取得する`selectByWorkRecordIdOrderByLayer`を追加（`@Select`アノテーション、既存の「XMLマッパーなし」方針を踏襲）。

### 2.2 API一覧

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/work-records/{id}/bp-payments` | 階層一覧取得(layer_order昇順) |
| POST | `/api/work-records/{id}/bp-payments` | 階層追加 `{layerOrder, payeeCompanyName, parentPaymentId, amount, remarks}` |
| PUT | `/api/bp-payments/{id}` | 階層更新(金額/ステータス/支払日) |
| DELETE | `/api/bp-payments/{id}` | 階層削除(論理削除、他階層の`parent_payment_id`参照が壊れないよう子階層がある場合は拒否) |

既存の `/api/bp-payments`（一覧・支払確認）はそのまま維持し、後方互換を保つ。

## 3. 集計・マージン計算

- `BpPaymentServiceImpl.calculateMargin(workRecordId)`: 各階層の`amount`から直下階層合計を引いた差分をDTO化して返す。ロジックは新規追加のみで、`SettlementCalculator`（売上/原価精算）には一切触れない。
- `dto/bp/BpPaymentTreeDto`: 階層構造をツリー形式でシリアライズするための新規DTO。

## 4. 画面

- `templates/bp-payment/list.html`: work_recordの階層が2件以上ある場合のみ、行をインデント表示（`th:if="${payment.layerOrder} > 1"`でCSSインデント）。1件のみの場合は既存の見た目を維持。
- `static/js/modules/bp-payment.js`: 階層追加モーダル（`#bpPaymentLayerModal`）を新設。既存の`saveBpPayment()`パターンを踏襲。

## 5. テスト

- `BpPaymentServiceImplTest`: 階層重複拒否、親子整合性チェック、マージン計算、既存単層データ（`layerOrder=1`固定）の後方互換動作。
- `FlywayMigrationSmokeTest`（Testcontainers）で新DDLがMySQL 8に対して問題なく適用されることを確認。

## 6. リスク・確定口径（注意点）

- **UNIQUE制約の移行順序**: 本番データがある環境では、`work_record_id UNIQUE`→複合UNIQUEへの切替時に、既存行は自動的に`layer_order=1`になるためデフォルト値でクリアできるが、**MySQLのオンラインDDL中に一瞬でも制約が緩む区間がある**ため、必ずメンテナンスウィンドウ中に適用する運用注意をREADME等に明記する。
- **循環参照防止**: `parent_payment_id`が自分自身や下位階層を指す循環参照はDB制約では防げないため、サービス層で`layerOrder`の大小関係（親は必ず自分より小さいlayer_orderを持つ）を検証する。
- **既存の粗利計算(`DashboardServiceImpl`)との整合**: 多階層支払の合計とwork_record原価の差異が生じても、既存の粗利集計ロジックは`work_record.paymentAmount`を単一の原価として使い続ける設計とし、**本specでは粗利計算ロジックへの影響はゼロ**にすることを明記（混同して両方に手を入れるとダブルカウントのバグを生みやすい）。
