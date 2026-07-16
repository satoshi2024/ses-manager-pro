# Design — 金銭フロー整合性・契約ライフサイクル補完（money-flow-consistency）

対象コード全体像:

- 契約: `ContractApiController` / `ContractServiceImpl` / `contract.js` / `templates/contract/list.html`
- 集計: `DashboardServiceImpl` / `ExportApiController` / `SalesPerformanceServiceImpl` / （新規）`MonthlyRevenueCalcService`
- 勤怠・BP: `WorkRecordServiceImpl` / `WorkRecordMapper` / `BpPaymentMapper`
- 請求: `InvoiceMapper`
- DB: 新規 `V16__money_flow_consistency.sql`（コメント修正のみ。番号は実装時点の最新+1に読み替える）

既存挙動を変えるのは「集計口径（R2/R3）」のみ。請求・精算の確定済み金額
（`t_work_record.billing_amount` / `t_invoice.*`）には一切手を入れない。

---

## R1. 契約ライフサイクル UI

### バックエンド（変更なしで流用）

`PUT /api/contracts/{id}`（`updateWithBusinessRules`、status は無視して null 化済み）と
`PUT /api/contracts/{id}/status`（状態機械 `ALLOWED_STATUS_TRANSITIONS`）は実装済み。追加不要。
ただし R2 のため `changeStatus` のシグネチャ拡張あり（後述）。

### フロントエンド

`templates/contract/list.html` / `static/js/modules/contract.js`:

1. 一覧各行に「編集」「状態変更」ボタンを追加（既存の削除ボタン隣、`engineer.js` の編集パターン踏襲）。
2. **編集**: 既存 `#contractModal` を新規/編集共用化する。
   - `openEditContract(id)` が `GET /api/contracts/{id}` で全項目を取得しモーダルへプリセット
     （`ContractApiController` に detail エンドポイントが無ければ `GET /api/contracts/{id}` を追加。
     `ContractMapper.selectById` ベース、`ApiResult<Contract>`）。
   - `saveContract()` を hidden `#cont-id` の有無で POST/PUT 分岐。**PUT 時も全フィールドを送信**
     （`FieldStrategy.ALWAYS` 前提の維持。未入力 select は明示的に `null` を積む）。
   - status はペイロードから除外（サーバ側で無視される仕様に合わせ、そもそも送らない）。
3. **状態変更**: 行のステータスバッジ隣にドロップダウン。現ステータスから
   `準備中→[稼動中, 解約]` / `稼動中→[終了, 解約]` のみ表示（フロントでも絞るがサーバ検証が正）。
   - 「稼動中」「終了」は SweetAlert2 確認のみ → `PUT /{id}/status`。
   - 「解約」は R2 の解約日入力付き確認ダイアログ（`Swal.fire` + `<input type="date">`、既定値=今日）。
4. i18n: `contract.action.edit` / `contract.action.changeStatus` / `contract.cancel.datePrompt` 等を
   4言語（ja/en/zh_CN/ko）へ追加。

### 権限

既存 `m_menu.contract` の `api_prefix` 配下のため権限追加は不要。

## R2. 解約日の確定

`ContractService#changeStatus(Long id, String newStatus)` を
`changeStatus(Long id, String newStatus, LocalDate cancelDate)` へ拡張:

- `newStatus == "解約"` のとき `cancelDate` 必須（null なら `BusinessException.of("error.contract.cancelDateRequired")`）。
  `cancelDate < startDate` は `error.contract.cancelDateInvalid`。`end_date = cancelDate` で上書き。
- それ以外の遷移では `cancelDate` を無視（終了は現行どおり `end_date` 不変）。
- `StatusChangeRequest` に `cancelDate`（`LocalDate`、任意）を追加。
- 解約が `稼動中` からの場合の要員ステータス連動（`releaseIfIdle`）は現行ロジックを維持。

メッセージキー追加（4言語）: `error.contract.cancelDateRequired` / `error.contract.cancelDateInvalid`。

## R3. 集計口径統一

### 新規 `service/billing/MonthlyRevenueCalcService`（+ impl）

```java
MonthlyAmount calc(YearMonth month,
                   List<Contract> contracts,              // 呼び出し元が一括ロードして渡す
                   Map<Long, WorkRecord> confirmedByContractId) // 当月確定実績 (contract_id -> record)
```

- 契約単位フォールバック: 確定実績があれば `billing_amount`/`payment_amount`（null は0）、
  なければ `selling_price`/`cost_price`。
- 対象: `status != '準備中'` かつ `start_date <= 月末` かつ (`end_date IS NULL` or `end_date >= 月初`)。
  ステータス判定は `StatusConstants.CONTRACT_PREPARING` を使用（`SalesPerformanceServiceImpl.isActiveInMonth`
  と同一。将来の解約は R2 で `end_date` が正となるため個別除外は不要）。
- 戻り値 `MonthlyAmount { long sales; long profit; boolean hasActual; }`
  （`hasActual` = 当月に確定実績由来の金額が1件以上）。

### 呼び出し元の置換

1. `DashboardServiceImpl.calcMonthlyAmount` を削除し共通サービスへ委譲。
   - KPI「当月予想売上」「粗利率」も当月の `calc(...)` 結果を使用（稼動中契約単価合計をやめる）。
     トレンド%・チャート・KPI が同一値ソースになる。
   - 確定実績の月別一括ロード（`confirmedByMonth`）は現行の1クエリ方式を維持し、
     `groupingBy(WorkRecord::getWorkMonth)` → 月ごとに `toMap(contractId)` へ変換して渡す。
2. `ExportApiController.buildMonthlyRevenueRows` を削除し共通サービスへ委譲
   （月ループと DTO 詰めのみ残す）。区分列は `hasActual ? "実績" : "見込み"`。
3. `SalesPerformanceServiceImpl` は営業別内訳が必要なため契約ループは維持するが、
   1契約分の金額決定（実績 or 契約単価）を共通サービスの契約単位メソッド
   `resolveContractAmount(Contract, WorkRecord)` として切り出し共用する。

### テスト

`MonthlyRevenueCalcServiceTest`（純粋ロジックのためモック不要）:
実績あり月の実績なし契約計上 / 準備中除外 / 期間境界（月初・月末・end_date null）/
実績優先 / payment null 時 profit=sales。
既存 `DashboardServiceImplTest` は期待値を新口径へ更新。

## R4. reopen の手動BP階層保護

`WorkRecordServiceImpl.reopenMonth`:

- 削除前に対象 `work_record_id` 群の BP支払を取得し、
  `layer_order > 1 || parent_payment_id != null` の未払行が存在すれば
  `BusinessException.of("error.workRecord.manualBpDelete", 件数)` で拒否
  （支払済チェックの直後、削除の前。トランザクション内なので状態変更なし）。
- 自動生成1階層目のみなら現行どおり削除。

`confirmMonth` 側（受け入れ基準3）: 既存BP支払が存在して金額が `payment_amount` と不一致の場合、
1階層目（`parent_payment_id IS NULL` かつ `layer_order = 1`、未払に限る）の `amount` を最新値へ更新する。
支払済の1階層目が不一致の場合は更新せず warn ログ + 通知（`NotificationService.publish`）に留める。

メッセージキー: `error.workRecord.manualBpDelete`（4言語）。

## R5. 営業成績の未帰属行

`SalesPerformanceServiceImpl.calculateMonthlyPerformance`:

- 契約ループで `salesUserId == null` を `continue` せず、`salesUserId = null` キー
  （または定数 `UNATTRIBUTED_ID = -1L`）で同じ集計 Map に積む（売上・粗利・稼動契約数のみ。
  成約数・成約率・担当要員数・インセンティブは対象外）。
- DTO に `unattributed` フラグ（または `salesUserId == null`）を追加し、
  `sales-performance.js` が最終行として「未帰属」を表示、契約一覧
  （`/contract/list?salesUserId=none` — 契約検索に「未設定」オプション追加）へのリンクを張る。
- i18n: `salesPerformance.unattributed`（4言語）。

## R6. fraction_rule 注記

- `templates/work-record/list.html`（グリッド上部）と契約モーダルの端数ルール入力欄下に
  `<small class="text-muted">` で注記（i18n キー `contract.fractionRule.note`、4言語）:
  「精算計算は常に1円未満切り捨て。この欄はメモであり計算には適用されません」。
- コード変更なし。

## R7. 細部整備

1. `WorkRecordMapper.selectMonthlyGrid`:
   `c.start_date <= LAST_DAY(CONCAT(#{workMonth}, '-01'))` へ変更。
   H2 は MySQL 互換モードで `LAST_DAY` をサポートするが、`engineer-schema-h2.sql` 利用テストで
   要動作確認（NG の場合は Java 側で月末日文字列を組み立ててパラメータ `#{monthEnd}` を渡す方式に切替。
   こちらは方言非依存のため第一候補としてよい）。
2. `InvoiceMapper.selectUnbilledWorkRecords`: `AND c.deleted_flag = 0` を追加。
3. 新規 `V16__money_flow_consistency.sql`:
   `ALTER TABLE t_proposal MODIFY proposed_unit_price DECIMAL(10,0) COMMENT '提案単価(円)';` 等
   コメント修正4本（`t_engineer.expected_unit_price` / `t_project.unit_price_min/max`）。
   ※ MODIFY は型・NULL 制約を現行どおり明記すること。V1 ベースライン側のコメントも
   同一 PR で修正してよい（CREATE TABLE の COMMENT 変更は既存DBに影響しない、
   Flyway チェックサムは V1 が baseline 対象のため要注意 → **V1 は変更せず V16 のみ**とする）。
4. `contract.js` の `¥${...}円` → `¥${...}`（2箇所）。
5. `templates/system-config/list.html`: 設定説明の文言に単位（小数/百分率）を追記
   （`m_system_config.description` はシード値のため、V16 で `UPDATE` するか画面側注記のどちらかに統一。
   → 画面側 i18n 注記とする。DB の description は触らない）。

## 実装レーン分割（並行可能性）

- **レーンA（契約UI + 解約日）**: R1 + R2。担当: `ContractApiController` / `ContractService(Impl)` /
  `StatusChangeRequest` / `contract.js` / `contract/list.html` / i18n。
- **レーンB（集計口径）**: R3。担当: 新規 `MonthlyRevenueCalcService` / `DashboardServiceImpl` /
  `ExportApiController` / `SalesPerformanceServiceImpl` / 各テスト。
- **レーンC（勤怠・BP・請求・細部）**: R4 + R6 + R7。担当: `WorkRecordServiceImpl` /
  `WorkRecordMapper` / `InvoiceMapper` / `work-record/list.html` / V16 / i18n。
- **レーンD（営業成績未帰属）**: R5。担当: `SalesPerformanceServiceImpl`（レーンBと同ファイルのため
  **B完了後に着手**）/ `sales-performance.js` / i18n。

A/C は並行可。B→D は逐次。R2 の解約日が R3 の口径テストの前提になるため、
テスト都合上 A→B の順が安全（並行させる場合は B のテストで解約契約ケースを A マージ後に追加）。
