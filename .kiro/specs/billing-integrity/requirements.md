# Requirements — 請求・実績データ整合性(billing-integrity)

月次実績(勤怠)→精算→請求→入金・BP支払の資金フローにおけるデータ破壊・不整合バグの修正。
対象コード: `WorkRecordServiceImpl` / `WorkRecordApiController` / `InvoiceServiceImpl` / `InvoiceApiController` / `InvoiceMapper` / `invoice.js` / `work-record.js`。

## R1. 月次解除(reopen)は支払済のBP支払を破壊しない

現状: `WorkRecordServiceImpl.reopenMonth()` が該当月の `t_bp_payment` を **status を問わず物理削除**しており、
`支払済`(paid_date あり)の支払実績まで消える。

受入基準:
1. 対象月の実績に `支払済` のBP支払が1件でも紐づく場合、reopen は `BusinessException` で拒否され、メッセージに支払済み件数が含まれる。
2. `未払` のBP支払のみが紐づく場合、reopen は成功し、`未払` のBP支払だけが削除される。
3. 拒否された場合、実績のステータス・BP支払とも一切変更されない(トランザクション内で先にチェック)。

## R2. 請求書に計上済みの実績は reopen できない

現状: `t_invoice_item.work_record_id` が参照している確定実績も reopen で `入力中` に戻せてしまい、
その後 `saveHours` で金額を変えても発行済み請求書には反映されず、かつ
`selectUnbilledWorkRecords` の `NOT IN (SELECT work_record_id FROM t_invoice_item)` により再請求も永久に不可能になる。

受入基準:
1. 対象月の確定実績のうち1件でも **取消されていない請求書**(`t_invoice.deleted_flag = 0`)の明細に含まれる場合、
   reopen は `BusinessException` で拒否され、メッセージに該当請求書番号(複数なら列挙)が含まれる。
2. 取消済み請求書(R4)の明細のみに紐づく実績は reopen を妨げない。

## R3. 請求済み実績の工数編集ガード(縦深防御)

受入基準:
1. `saveHours` は、対象実績が取消されていない請求書の明細に含まれる場合、ステータスに関わらず
   `BusinessException`「請求書(INV-...)に計上済みの実績は編集できません」で拒否する。
   (R2 が守られていれば通常到達しないが、防御層として実装する。)

## R4. 請求書の取消(void)機能

現状: 請求書には取消・削除の手段が一切なく、誤発行するとその実績は永久に請求不能になる。

受入基準:
1. `PUT /api/invoices/{id}/void` で請求書を取消できる。
2. `入金済` の請求書は取消できない(`BusinessException`)。先に状態を `送付済` へ戻す(R6)必要がある。
3. 取消処理は同一トランザクションで: (a) `t_invoice_item` を**物理削除**して実績を解放
   (`work_record_id` の UNIQUE 制約があるため物理削除が必須)、(b) `t_invoice` を**論理削除**(`deleted_flag=1`)して
   番号と金額の履歴を保持する。
4. 取消後、同じ顧客×同月で `generate` を実行すると、解放された実績が再び請求対象になる。
5. 取消済み請求書の番号は再利用されない(R5)。
6. 一覧画面(invoice.js)に取消ボタンがあり、SweetAlert2 で確認後に実行、成功で Toast 表示・一覧再読込。
   取消済みは一覧に表示されない(論理削除により自動)。

## R5. 請求書番号採番の論理削除対応

現状: `generateInvoiceNo` は MyBatis-Plus 経由(論理削除自動フィルタ)で最大番号を取るため、
取消(論理削除)された請求書の番号が見えず番号を再利用 → UNIQUE 衝突 → リトライも同値を再生成して3回全滅する。

受入基準:
1. 採番は **deleted_flag を問わず**その月プレフィックスの最大番号+1 を返す(注釈 `@Select` で実装)。
2. 取消→再生成を行うと、取消された番号の次の番号が採番される(例: INV-202607-0002 取消後の再生成は 0003)。

## R6. 請求書ステータスの状態機械

現状: `changeStatus` は任意の文字列遷移を無条件に受け付け、`入金済→未送付` の逆行や `paid_date` の残留が起きる。

受入基準(許可される遷移とその副作用):
1. `未送付 → 送付済`: 許可。
2. `送付済 → 入金済`: 許可。`paidDate` 必須(未指定は `BusinessException`)。`paid_date` に設定。
3. `送付済 → 未送付`(差戻し): 許可。
4. `入金済 → 送付済`(入金取消): 許可。`paid_date` を **null にクリア**する。
5. 上記以外の遷移(例: `未送付→入金済`、`入金済→未送付`、未知の値)はすべて
   `BusinessException`「「{現状態}」から「{新状態}」へは変更できません」で拒否する。

## R7. BP支払ステータス更新の堅牢化

現状: `InvoiceApiController.updateBpPaymentStatus` は id 不存在で黙って成功を返し、
`支払済→未払` に戻しても `paid_date` が残る。ロジックがコントローラーに直書きされている。

受入基準:
1. id 不存在は `BusinessException`「BP支払が見つかりません」。
2. `未払 → 支払済`: 許可。`paidDate` 未指定なら当日を設定。
3. `支払済 → 未払`: 許可。`paid_date` を null にクリア。
4. 上記以外(未知の値)は拒否。
5. ロジックは `InvoiceService`(または専用サービス)へ移し、コントローラーは委譲のみにする。

## R8. 実績入力(saveHours)の検証と並行安全性

現状: リクエストが `Map<String,Object>` で受けられ、フィールド欠落で NPE(500)。
`actualHours` の範囲検証なし。`uk_work_record(contract_id, work_month)` があるのに
「先読み→INSERT」の競合時 `DuplicateKeyException` が生のまま 500 になる。

受入基準:
1. リクエストは専用 DTO(`WorkRecordSaveRequest`)+ Bean Validation で受ける:
   `contractId` 必須 / `workMonth` 必須かつ `YYYY-MM` 形式 / `actualHours` 必須かつ 0〜999.9 / `remarks` 500字以内。
   違反は `GlobalExceptionHandler` 経由で日本語メッセージの 400 になる。
2. 同一(契約×月)への同時 INSERT で `DuplicateKeyException` が発生した場合、
   `BusinessException`「他のユーザーが同じ実績を登録しました。再読み込みしてください」に変換する(500 を出さない)。
3. 既存のフロント(work-record.js)は `ApiResult.message` を Toast 表示する既存挙動のままで動作する。
