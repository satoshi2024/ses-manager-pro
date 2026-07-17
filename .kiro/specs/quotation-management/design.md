# Design — 見積書発行（quotation-management）

対象コード: 新 `Quotation` エンティティ一式 / 新 `QuotationService(Impl)` / 新 `QuotationApiController` /
新 `QuotationPageController` / 新 `QuotationPdfService` / `ContractService(Impl)`（ドラフト生成の共通化） /
`proposal-kanban.js`（見積作成ボタン） / 新 `templates/quotation/list.html` / 新 `static/js/modules/quotation.js`

## 1. マイグレーション（`V29__quotation.sql`※）

※ 番号は実装時点の最新+1。全体調整は `customer-feature-proposals/README.md` 参照。

```sql
CREATE TABLE t_quotation (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  quotation_no          VARCHAR(30) NOT NULL UNIQUE COMMENT '見積番号(Q-YYYYMM-NNNN)',
  customer_id           BIGINT NOT NULL,
  project_id            BIGINT NULL,
  engineer_id           BIGINT NULL,
  proposal_id           BIGINT NULL COMMENT '任意紐付け(参照のみ)',
  title                 VARCHAR(200) NOT NULL COMMENT '件名',
  unit_price            DECIMAL(10,0) NOT NULL COMMENT '単価(円/月)',
  settlement_hours_min  DECIMAL(5,1) NULL,
  settlement_hours_max  DECIMAL(5,1) NULL,
  valid_until           DATE NULL COMMENT '有効期限',
  status                ENUM('下書き','提出済','受注','失注') NOT NULL DEFAULT '下書き',
  remarks               VARCHAR(500),
  created_by            BIGINT,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT DEFAULT 0
) COMMENT='見積';

ALTER TABLE t_contract ADD COLUMN quotation_id BIGINT NULL COMMENT '生成元見積(見積受注からのドラフトのみ)';

INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) VALUES
  ('quotation', '見積管理', '/quotation', '/api/quotations', <既存最大+1>);
INSERT IGNORE INTO t_role_menu (role, menu_id)
  SELECT r.role, m.id FROM (SELECT '管理者' role UNION SELECT '営業' UNION SELECT 'マネージャー') r,
         m_menu m WHERE m.menu_key='quotation';
```

- H2 同期: `engineer-schema-h2.sql` に `t_quotation`＋`t_contract.quotation_id` 追加、
  リプレイ用 `sql/schema-quotation-h2.sql`（IF NOT EXISTS）を `application-test.yml` へ登録。
- `Contract` エンティティに `quotationId`（通常戦略。NULL クリア不要のため ALWAYS 不要）。

## 2. サービス

```java
interface QuotationService extends IService<Quotation> {
  String generateQuotationNo(LocalDate baseDate);            // Q-YYYYMM-NNNN、リトライは save 側
  void saveWithBusinessRules(Quotation q);                   // 採番リトライ+検証(R1)
  void updateWithBusinessRules(Quotation q);                 // 受注/失注後は編集拒否(備考のみ許可)
  void changeStatus(Long id, String newStatus);              // 状態機械
  Contract createDraftFromQuotation(Long quotationId);       // R3(冪等)
}
```

- 状態機械: `Map.of("下書き", Set.of("提出済"), "提出済", Set.of("受注","失注"), ...)` —
  契約/提案と同じ実装パターン。削除は 下書き のみ（`removeById` オーバーライド）。
- 検証: customer 実在 / project があれば customer 一致（契約 validate と同基準）/
  精算幅 min≦max / 単価≧0。
- **ドラフト生成の共通化（本設計の要）**: `createDraftFromProposal` の後半（既定値設定〜
  主担当フォールバック〜`saveWithBusinessRules`）を `ContractServiceImpl` 内の
  `buildAndSaveDraft(DraftSource src)` として抽出し、提案経由・見積経由の両方から呼ぶ。
  `DraftSource` は (engineerId, projectId, customerId, sellingPrice, settlementMin/Max,
  remarks, proposalId?, quotationId?) を持つ小さな値オブジェクト。
  **既定値規約（原価0・開始=翌月1日・準備中・主担当フォールバック）を一箇所に保つ**。
  冪等性: quotation_id で既存ドラフト検索（proposal と同型の LIMIT 1）。
- 要員未設定の受注→ドラフト生成は `error.quotation.engineerRequired`。
- 受注遷移とドラフト生成は別 API（受注だけしてドラフトは後で、を許すため）。UI では
  受注遷移の確認ダイアログに「続けてドラフトを生成する」チェック（既定 ON）を置く。

## 3. PDF（新 `service/QuotationPdfService`）

- `InvoicePdfServiceImpl` と同じ OpenPDF/フォント（`PdfProperties`）。レイアウト:
  ヘッダ（見積書・見積番号・発行日・有効期限）/ 宛先（顧客名 御中）/ 会社情報ブロック
  （`company.name/address/invoice-registration-number` — 登録番号は**未設定なら省略**、
  invoice-compliance の既存分岐と同じ扱い）/ 明細表（イニシャル・件名・単価・精算幅）/
  備考 / 注記「本見積は税抜表記です。消費税は請求時の税率を適用します」。
- エンドポイント: `GET /api/quotations/{id}/pdf`（`Content-Disposition: attachment`、
  ファイル名 `見積書_{quotation_no}.pdf` — Excel 出力と同じ UTF-8 エンコード方式）。

## 4. API / ページ / フロント

- `QuotationApiController`（`/api/quotations`）: page（status/customerId/keyword 検索）/
  get / post / put / delete / `PUT /{id}/status` / `POST /{id}/create-draft` / `GET /{id}/pdf`。
- `QuotationPageController`: `GET /quotation` → `quotation/list`。
- `quotation.js` + `list.html`: 標準 CRUD 規約（一覧・検索・モーダル・Toast・SweetAlert2）。
  顧客/案件/要員セレクトは **size=1000 明示**（money-flow F2 の教訓）。
  状態遷移はサーバ状態機械のミラー定数＋相互参照コメント（contract.js と同型）。
  有効期限超過は行に「期限切れ」バッジ（status とは独立）。
- `proposal-kanban.js`: カードメニューに「見積作成」→ `/quotation?fromProposal={id}` へ遷移し、
  クエリがあれば `GET /api/proposals/{id}` 相当の既存データでモーダルをプリセット
  （提案側 API に不足があれば kanban DTO の既存フィールドで足りる範囲に留める）。

## 5. i18n

`menu.quotation` / `quotation.*`（一覧・モーダル・状態・PDF項目名）/
`error.quotation.statusTransitionInvalid / editAfterClosed / deleteNonDraft / engineerRequired /
numberGenerateFailed` — 4言語。PDF 内の日本語固定文言は messages を介さず日本語直書きで可
（客先提出物は日本語固定。invoice PDF の前例に従う）。

## 6. レーン分割

- **レーンF（基盤）**: V29・エンティティ・Mapper・Service（状態機械・採番・検証）・
  `buildAndSaveDraft` 抽出（`ContractServiceImpl` を触るのはこのレーンだけ）。
- **レーンA（画面）**: API・ページ・quotation.js・カンバン連携。F 完了後。
- **レーンB（PDF）**: QuotationPdfService・pdf エンドポイント。F 完了後、A と並行可。
- 他 spec との競合: `ContractServiceImpl`（F レーン）が lifecycle 系と同一ファイル——
  本 spec 着手時点で先行ブランチがマージ済みであることを確認してから始める。

## 7. テスト方針

| 対象 | ケース |
|---|---|
| 採番 | 初回0001 / 連番 / 論理削除済み最大値の次 / 衝突リトライ（invoice の既存テストと同型） |
| 状態機械 | 許可遷移4本 / 不許可（下書き→受注 等）/ 受注後編集拒否（備考のみ可）/ 提出済の削除拒否 |
| ドラフト生成 | 引き継ぎ値（単価→selling・原価0・翌月1日・準備中）/ 主担当フォールバック（在職/退職）/ 冪等 / 要員なし拒否 / quotation_id 設定 |
| buildAndSaveDraft 抽出 | **既存 `createDraftFromProposal` の全テストがグリーンのまま**（リファクタの回帰確認） |
| PDF | 生成できてサイズ>0 / 登録番号未設定で省略（invoice PDF テストの前例に従う） |
