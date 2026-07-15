# Design Document — 稼働率カレンダー/ガントチャート可視化

## 1. データソース(読み取り専用)

- `t_engineer`: `id`, `name`, `status`, `availableDate`
- `t_contract`: `engineerId`, `startDate`, `endDate`, `status`, `renewedFromContractId`(後続契約の有無判定)
- 既存`AnalyticsServiceImpl.benchList()`と同じ`Engineer`/`Contract`結合クエリパターンを流用可能。

## 2. バックエンド(新規追加のみ、既存メソッド変更なし)

### 2.1 新規

- `AnalyticsApiController`に新規GETエンドポイント追加のみ(既存メソッドは触らない)。
- `service/impl/AnalyticsServiceImpl`に新規メソッド`getAvailabilityTimeline(fromMonth, toMonth, filters)`追加。
- 「まもなく空き」判定ロジックは`notification-center`の`CONTRACT_END`判定条件（`status='稼動中'` かつ `end_date`が[today, +30日]、後続契約なし）と同一の閾値を用いる。共有化する場合は`service/notification/NotificationGenerateService`から判定メソッドをpackage-privateで切り出し、双方から呼び出す。

### 2.2 API一覧

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/analytics/availability-timeline?from=YYYY-MM&to=YYYY-MM&skillId=&salesUserId=` | エンジニア×月のタイムラインデータ |

レスポンス例:
```json
{
  "engineers": [
    { "id": 1, "name": "山田太郎", "bars": [
      { "start": "2026-07-01", "end": "2026-09-30", "type": "contracted", "contractId": 55 },
      { "start": "2026-10-01", "end": null, "type": "available" }
    ], "endingSoon": true }
  ]
}
```

## 3. 画面

- 新規`templates/analytics/availability-calendar.html`（`analytics`メニューの`api_prefix`を再利用、権限周りの新規配線不要）。
- 新規`static/js/modules/availability-calendar.js`: Chart.js `indexAxis: 'y'`のbarチャート、または表現力不足時は軽量タイムラインライブラリ導入（要design判断ログ）。
- デフォルトフィルタ: 「今後30日以内に空くエンジニア」で絞り込み表示（大量データ時の性能配慮、design.md注意点参照）。

## 4. テスト

- `AnalyticsServiceImplTest`: 契約期間の帯生成、期間未定契約(`endDate=null`)の描画データ、後続契約ありのエンジニアが「まもなく空き」から除外されることの検証。
- 既存`CONTRACT_END`通知ロジックとの判定基準一致を確認するテスト（同一データセットで両ロジックの結果が一致することをアサーション）。

## 5. リスク・確定口径(注意点)

- **通知ロジックとの基準ズレ**: requirements.md記載の通り、`CONTRACT_END`と本specの「まもなく空き」判定は必ず同一条件にする。実装時に別々にハードコードしないこと。
- **期間未定契約の表現**: `endDate=null`のケースをUIでどう見せるか（点線/「継続中」ラベル等）をタスク着手前に画面モックで確認してから実装する。
- **パフォーマンス**: 全エンジニア×全契約を毎回JOINするとエンジニア数増加時に重くなるため、デフォルトで絞り込みクエリを効かせる（`WHERE end_date BETWEEN ...`等）。
