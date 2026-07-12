# Design Document — 稼動分析・帳票出力(P7)

## 1. 依存追加(`pom.xml`)

```xml
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.2.5</version>
</dependency>
```

## 2. 稼動分析サービス(`service/AnalyticsService` + impl)

```java
public interface AnalyticsService {
    List<UtilizationPointDto> utilizationTrend(int months);  // 直近N月
    List<BenchEngineerDto> benchList();
}
```

- `utilizationTrend`: 月末日ごとに
  - 稼動要員数 = `t_contract` で `status='稼動中'相当の期間判定`(start_date <= 月末 AND (end_date IS NULL OR end_date >= 月末) AND status IN ('稼動中','終了'))の distinct engineer_id 数
  - 在籍要員数 = その時点で登録済み(created_at <= 月末)かつ未削除の要員数
  - 稼動率 = 稼動 ÷ 在籍(分母0は null)
  実装は要員・契約を各1クエリで取得しメモリ集計(既存 `DashboardServiceImpl` の集計スタイルに合わせる)。
- `benchList`: status='Bench' の要員に対し、最終契約終了日(無ければ created_at)から経過日数を算出。
  契約は engineer_id IN (...) の1クエリで取得し `Map` 化(N+1 禁止)。
  `BenchEngineerDto`: engineerId / fullName / benchDays / expectedUnitPrice / availableDate / skillNames(P1 導入済みなら列挙、未導入なら空)。

## 3. Excel 出力(`service/export/ExcelExportService`)

```java
@Service
public class ExcelExportService {
    public byte[] exportEngineers(List<Engineer> rows);          // 要員一覧
    public byte[] exportContracts(List<ContractExportDto> rows); // 契約一覧(要員名・案件名解決済み)
    public byte[] exportMonthlyRevenue(int fiscalYear, List<MonthlyRevenueDto> rows);
}
```

- `SXSSFWorkbook`(ストリーミング)を使用。共通ヘルパー `createHeaderRow(Sheet, String...)` /
  日付 `yyyy/mm/dd`・金額 `#,##0` の `CellStyle` を1箇所で定義。
- 列幅は `setColumnWidth` 固定値(`autoSizeColumn` は日本語で崩れやすい & SXSSF 非対応のため使わない)。

### API(`controller/api/ExportApiController`)

| メソッド | パス | 権限(既存メニューの api_prefix に追従) |
|---|---|---|
| GET | `/api/engineers/export?fullName=&status=&employmentType=&skillIds=` | engineer |
| GET | `/api/contracts/export?status=...` | contract |
| GET | `/api/dashboard/revenue-export?fiscalYear=` | dashboard |

エンドポイントを既存 prefix 配下に置くことでメニュー権限がそのまま効く。実装は各既存 ApiController に追記でもよいが、
POI 依存を1箇所に集めるため `ExportApiController`(`@RequestMapping` をメソッド単位でフルパス指定)を推奨。

```java
return ResponseEntity.ok()
    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    .header(HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename*=UTF-8''" + URLEncoder.encode("要員一覧_20260712.xlsx", StandardCharsets.UTF_8))
    .body(bytes);
```

※ ダウンロードは `$.ajax` ではなく `window.location.href = url + '?' + $.param(currentFilters)` で発火
(`common.js` のセッション切れ検知 ajaxSetup と干渉しないよう注意)。

## 4. 画面

- `controller/page/AnalyticsPageController`(`GET /analytics` → `analytics/index`)+
  `templates/analytics/index.html` + `modules/analytics.js`:
  - 上段: 稼動率推移グラフ(Chart.js 折れ線 + 棒の複合、base.html で読込済み)
  - 下段: Bench 一覧テーブル(30日超=警告色 / 60日超=危険色、行アクション: 詳細・マッチング)
- 各一覧画面(engineer / contract / dashboard)に「Excel出力」ボタンを追加。
- メニューシード(006 か専用 007 に含める。P6 と同時なら 006 に同梱):

```sql
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('analytics', '稼動分析', '/analytics', '/api/analytics', 65);
-- t_role_menu: 管理者/営業/マネージャー
```

- `GET /api/analytics/utilization-trend?months=12`、`GET /api/analytics/bench` を `AnalyticsApiController` に。

## 5. テスト

- `AnalyticsServiceImplTest`(H2): 月末境界(月末開始・月末終了の契約)、分母0、Bench 経過日数(契約あり/なし)。
- `ExcelExportServiceTest`: 生成した bytes を `XSSFWorkbook` で読み戻しヘッダー・行数・セル値を検証。
- グラフは dataviz 上の手動確認(Demo)で担保。
