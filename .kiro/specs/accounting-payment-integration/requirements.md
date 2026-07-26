# Requirements — 会計・支払連携

## 前提

- G4はfreeeを会計確定の正、公式OAuth/APIとCSV fallback、legal entity/product/company ID別connectionとする。
  実契約plan、会社ID、勘定科目/税区分/部門/取引先mappingはT094で確認し、未確認項目は本番送信を止める。
- 本システムはSES業務明細の正、freeeは会計帳簿/支払確定の正とし、総勘定元帳を自作しない。
- 既存給与/入金freee接続をadapter化し、単一connection前提を解消する。

## R1. connection/mapping

1. THE システム SHALL tenant/legal entity/provider/product別connectionを管理し、tokenを暗号化/rotationする。
2. THE システム SHALL 顧客/BP/法人/勘定科目/税区分/部門/cost centerの外部ID mappingを持つ。
3. THE mapping不足 SHALL 送信前validationで止め、外部に不完全伝票を作らない。

## R2. 売上連携

1. THE 送付済/承認済請求 SHALL freeeの対応APIへ取引/請求情報を冪等送信できる。
2. THE 外部ID、request ID、payload hash、送信時刻、状態を保存する。
3. THE 請求取消/訂正 SHALL 外部側の状態を確認し、取消/差額処理を明示し、物理削除しない。

## R3. 仕入/経費/支払

1. THE 承認済BP支払/経費 SHALL 仕入/経費として冪等連携できる。
2. THE 支払予定/実績 SHALL freee等から同期し、内部`paid`更新は外部IDと金額/日付照合後だけ行う。
3. THE 手数料/源泉/税区分 SHALL BP/個人区分とmapping ruleで計算し、人がpreview確認する。
4. THE 振込データ出力を行う場合 SHALL 承認済支払だけを対象とし、口座変更承認と二重支払guardを持つ。

## R4. job/障害

1. THE 外部連携 SHALL outbox/jobで非同期実行し、DB transaction内でHTTPを呼ばない。
2. THE job SHALL pending/running/succeeded/retryable/failed/cancelled、attempt、next retry、相関IDを持つ。
3. THE 401 SHALL token refreshを1回、429/5xx/timeout SHALL backoff、4xx validation SHALL 人手修正待ち。
4. THE 再実行 SHALL payload hash/idempotency keyで外部二重登録を防ぐ。

## R5. 月次照合

1. THE システム SHALL 内部売上/仕入/入金/支払と外部取引を月次照合し、未送信/不一致/外部のみを表示する。
2. THE 月次締め SHALL 重大不一致がある場合警告し、法務/財務設定により締めを阻止できる。

## R6. 受入

- 同一請求/jobを10回再実行して外部1件。
- 401/429/timeout後に安全に復旧し、validation errorは無限retryしない。
- 内部合計=外部合計の照合と差異drilldown。
