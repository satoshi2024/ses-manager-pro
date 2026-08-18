# Requirements — 会計・支払連携

## 前提

- G4はfreeeを会計確定の正、公式OAuth/APIとCSV fallback、legal entity/product/company ID別connectionとする。
  開発時のマスタ定義・WireMockは公式仕様準拠の `PROVISIONAL` とし、実契約plan、会社ID、本番マスタIDは本番Release Gateとして管理する。
- 本システムはSES業務明細の正、freeeは会計帳簿/支払確定の正とし、総勘定元帳を自作しない。
- 既存給与/入金freee接続をadapter化し、単一connection前提を解消する。

## R1. connection/mapping

1. THE システム SHALL tenant/legal entity/provider/product別connectionを管理し、`legal_entity_key` と `active_slot` により NULL 一意性および soft-delete 後の安全な再作成を保証する。
2. THE システム SHALL 顧客/BP/勘定科目(売上/仕入/経費)/税区分(売上/仕入/経費)/部門/cost center の外部ID mappingを持つ。
3. THE 外部マスタ検証 SHALL 全10種別の正規識別子 (`id` / 数値 `tax_code`) を実在照合し、未知種別は fail-closed (`return false`) で拒否し、検証時の canonical snapshot を保存する。
4. THE 401トークンリフレッシュ SHALL multi-node 環境において DB トランザクション外で HTTP を呼ぶ 3段階リース・CAS 状態機械により直列化し、他ノードは新トークンを再利用する。

## R2. 売上連携・取消

1. THE 送付済/承認済請求 SHALL freeeの対応APIへ取引/請求情報を冪等送信できる。
2. THE ジョブ登録 SHALL 送信内容の完全な不変バイト列を `payload_snapshot` として保持し、その SHA-256 ハッシュを `payload_hash` とする。
3. THE 請求取消/訂正 SHALL enqueue 時点で `externalDealId`, `cancelReasonCode` を snapshot に固定し、Worker は実行時点の別ジョブではなく snapshot の対象のみを取り消す。

## R3. 仕入/経費/支払

1. THE 承認済BP支払/経費 SHALL 仕入/経費として冪等連携できる。
2. THE BP仕入 canonical SHALL 業務日付（テナントタイムゾーンにおける `work_month` 末日および支払期日）から算出して固定し、翌日再実行でもハッシュ不変とする。
3. THE 支払予定/実績 SHALL freee等から同期し、内部`paid`更新は外部IDと金額/日付の双方非NULL厳格照合後だけ行う。
4. THE 経費同期 SHALL 連携成功時に内部ステータスを CAS (`承認済` -> `会計連携済`) で更新し、競合時はジョブを `FAILED (CAS_CONFLICT)` とする。
5. THE 振込データ出力を行う場合 SHALL 承認済支払だけを対象とし、口座変更承認と二重支払guardを持つ。

## R4. job/障害・リース・補償

1. THE 外部連携 SHALL outbox/jobで非同期実行し、DB transaction内でHTTPを呼ばない。
2. THE job SHALL `lease_token` (UUID) と `lease_expires_at` を持ち、claim 時点および完了 CAS で lease を検証する。
3. THE job取消 SHALL `SALES_INVOICE_SYNC` および `PAYMENT_SYNC` のみ `RUNNING` 状態からの取消を許可し、`SALES_INVOICE_SYNC` の HTTP 実行中に取消され外部取引が作成された場合は `CANCELLED_EXTERNALLY_CREATED` イベントを同一トランザクションで記録して補償取消ジョブ (`SALES_INVOICE_CANCEL`) を自動 enqueue する。BP仕入・経費の `RUNNING` 取消は 400 で拒否する。
4. THE stale回収 SHALL 個別 CAS (`WHERE id=? AND status='RUNNING' AND lease_token=?`) で `RETRYABLE` に戻し、event を同一トランザクションで記録する。再送前には外部取引照合を行い二重登録を防止する。
5. THE エラー情報 SHALL 生の外部レスポンスや例外メッセージを保存・ログ出力せず、定型エラーコードと局所化テンプレートキーのみを保存する。

## R5. 月次照合・スコープ

1. THE システム SHALL 内部売上/仕入/入金/経費の4母集団（売上 `t_invoice`, 仕入 `t_bp_payment`, 入金 `t_invoice_payment` の `amount + fee`, 経費 `t_expense_request`）と外部取引・決済データを月次照合し、未送信/不一致/外部のみを表示する。
2. THE 月次照合 SHALL 外部取引を pagination (全ページ走査) で取得し、接続なし・トークンなし・API障害・50 ページ上限到達時は `externalFetchFailed=true`, `readyForClosing=false` (fail-closed) とする。
3. THE 月次照合 SHALL SUCCEEDED ジョブに対しても外部実金額を突合し、外部側での直接変更を `AMOUNT_MISMATCH` として検知する。
4. THE データスコープ SHALL マネージャーロールの参照範囲を実在する組織導出ルール（原価部門 `m_cost_center.organization_id` または契約/要員所属 `t_user_organization`）に基づき SQL 境界で厳格に限定し、許可組織が空集合の場合は DB レベルで 0 件を返却する。

## R6. 受入

- 同一請求/jobを10回再実行して外部1件。
- 401/429/timeout後に安全に復旧し、validation errorは無限retryしない。
- 内部合計=外部合計の照合と差異drilldown（4母集団対応）。
- 4言語 (ja/en/zh/ko) による画面・操作の完全国際化。
