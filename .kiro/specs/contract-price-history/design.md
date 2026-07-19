# Design — 契約単価の改定履歴（contract-price-history）

対象コード: 新 `ContractPriceHistory` エンティティ一式 / 新 `ContractPriceResolver`（`service/billing/`）/
`ContractService(Impl)`（改定登録） / `WorkRecordServiceImpl.saveHours`（リゾルバ適用） /
`MonthlyRevenueCalcServiceImpl.resolveContractAmount`（リゾルバ適用） /
`contract.js`・`contract/list.html`（改定UI）

**前提**: engineer-self-service-timesheet(P1) 完了後に着手（`saveHours` 交差のため）。

## 0. スパイク（タスク1）: 単価読み取り箇所の棚卸し

`sellingPrice|costPrice|selling_price|cost_price` の全参照を分類し、以下の想定を検証する:

| 分類 | 箇所（2026-07-17 時点の想定） | 対応 |
|---|---|---|
| 精算計算 | `WorkRecordServiceImpl.saveHours` | リゾルバ適用（R2-2） |
| 集計フォールバック | `MonthlyRevenueCalcServiceImpl.resolveContractAmount` | リゾルバ適用（R2-3）——**引数に対象月が必要になるためシグネチャ拡張**（下記2章） |
| 表示・転記 | 契約一覧/編集・Excel出力・ドラフト生成（提案/見積/更新）・ガント | 現在単価のまま（R1-5, R3-2） |
| 粗利分析 | `DashboardServiceImpl.getProfitAnalysis` | 現在単価のまま（契約単位の静的分析。註記を画面に追加） |

想定外の参照（例: AI マッチング・通知文言）が見つかった場合は本表へ追記し、
扱いを決めてから次タスクへ進む。**スパイクの成果物は本章の表の確定版**（コミットに含める）。

## 1. マイグレーション（`V33__contract_price_history.sql`※）

※ 番号は実装時点の最新+1。全体調整は `customer-feature-proposals/README.md` 参照。

```sql
CREATE TABLE t_contract_price_history (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  contract_id      BIGINT NOT NULL,
  apply_from_month CHAR(7) NOT NULL COMMENT '適用開始月(YYYY-MM)',
  selling_price    DECIMAL(10,0) NOT NULL,
  cost_price       DECIMAL(10,0) NOT NULL,
  reason           VARCHAR(300) COMMENT '改定理由',
  created_by       BIGINT,
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_cph (contract_id, apply_from_month),
  FOREIGN KEY (contract_id) REFERENCES t_contract(id) ON DELETE CASCADE
) COMMENT='契約単価改定履歴';
```

H2 同期: engineer-schema-h2 追加＋リプレイ用 `sql/schema-price-history-h2.sql`。

## 2. リゾルバ（新 `service/billing/ContractPriceResolver`）

```java
public interface ContractPriceResolver {
  /** 対象月に有効な(売上,原価)。履歴なしは契約の現在単価。純ロジック(履歴リストは呼び出し元が渡す版も用意しテスト容易性を確保) */
  ResolvedPrice resolve(Contract contract, YearMonth month);
  class ResolvedPrice { BigDecimal sellingPrice; BigDecimal costPrice; boolean fromHistory; }
}
```

- 実装: `apply_from_month <= month` の最大行を1クエリ（`ORDER BY apply_from_month DESC LIMIT 1`）。
  呼び出し頻度が高い集計向けに、契約ID一括版 `resolveBatch(List<Contract>, YearMonth)`
  （履歴を contract_id IN で一括ロードして Java 側で解決）を提供し、
  **月ループの N+1 を作らない**。
- `MonthlyRevenueCalcService` の変更: `resolveContractAmount(Contract, WorkRecord)` に
  対象月を追加した新シグネチャ `resolveContractAmount(Contract, WorkRecord, YearMonth,
  Map<Long, List<ContractPriceHistory>> histories)` へ拡張（histories は `calc` が一括ロードして
  受け渡し。**実績がある契約は従来どおり実績金額を使い、リゾルバはフォールバック経路のみ**）。
  呼び出し元3箇所（Dashboard/Export/SalesPerformance）の追随を含む。
- `saveHours`: `SettlementCalculator.calc` へ渡す単価を `contract.getSellingPrice()` から
  `resolver.resolve(contract, ym).sellingPrice` へ差し替え（cost 同様）。

## 3. 改定登録（`ContractService` 拡張）

```java
void revisePrice(Long contractId, String applyFromMonth, BigDecimal selling, BigDecimal cost, String reason);
List<ContractPriceHistory> priceHistory(Long contractId);
void deleteFuturePriceRevision(Long contractId, String applyFromMonth); // 将来予約のみ削除可
```

- `revisePrice`: 検証（selling/cost ≧0・applyFromMonth 形式・契約開始月以降）→
  初回改定なら初期履歴の自動補完（R1-3: 契約開始月・現行単価）→ upsert →
  **`t_contract` の現在単価を「当月時点で有効な履歴」で再計算して更新**する
  （新履歴そのものではなくリゾルバで解決する——過去遡及の登録時に、より新しい適用済み履歴や
  将来予約に引きずられないため。updateById、ALWAYS 不要——単価は NOT NULL）→
  過去遡及かつ確定済み実績ありなら戻り値/レスポンスに警告フラグ。
- **契約編集モーダルの単価欄の扱い**: 履歴が存在する契約は単価入力を読み取り専用にし
  「単価改定」ボタン（専用ダイアログ: 適用開始月・新単価・理由）経由に一本化する。
  履歴が無い契約は従来どおり直接編集可（編集＝現在単価の訂正であり改定ではない、という
  区別。訂正か改定かを迷わせないための二段構え）。
- API: `GET/POST /api/contracts/{id}/price-revisions`・`DELETE /{id}/price-revisions/{month}`。

## 4. フロントエンド

- 契約編集モーダル: 履歴ありのとき単価欄 readonly＋「単価改定」ボタン→ `#priceRevisionModal`
  （履歴テーブル・新規改定フォーム・将来予約の削除・過去遡及時の警告表示）。
- 一覧の単価列は現在単価のまま（変更なし）。将来予約がある契約に小バッジ「改定予約あり」。

## 5. i18n

`contract.priceRevision.*`（ダイアログ一式・警告文言）/
`error.contract.priceRevision.invalidMonth / beforeStart / pastLocked` — 4言語。

## 6. レーン分割

逐次実施を推奨（1スパイク → 2基盤+リゾルバ → 3精算・集計適用 → 4UI）。
並行分割するほどの独立領域がなく、リゾルバ導入が全段の前提のため。
**競合**: `WorkRecordServiceImpl`（P1）・`MonthlyRevenueCalcServiceImpl`（安定済み）・
`contract.js`（P4 レーンA が触らないことを確認済み——P4 は quotation.js 側）。

## 7. テスト方針

| 対象 | ケース |
|---|---|
| リゾルバ | 適用開始月ちょうど / 前月は旧単価 / 履歴なし=現在単価 / 複数履歴の最新選択 / 将来予約は対象月前なら不適用 / batch 版と単発版の一致 |
| revisePrice | 初回改定で初期履歴自動補完 / 将来予約は現在単価不変 / 当月適用で現在単価更新 / 開始月前拒否 / 過去遡及＋確定実績で警告 / 将来予約の削除・過去行の削除拒否 |
| saveHours | 改定前月 reopen→旧単価で精算 / 改定月→新単価（統合テスト） |
| 集計 | 実績なし将来月の見込みに予約改定が反映 / 実績あり月は実績優先で不変（既存回帰） / Dashboard・Export・SalesPerformance の3呼び出し元グリーン維持 |
