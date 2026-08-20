# Requirements — JP PINTデジタルインボイス

## 前提

- G5は初期ProviderにファーストアカウンティングPeppol Access Point APIを採用する。自社でAccess Point認定/運用を行わず、provider adapterで差替可能にする。
- 実装開始時にデジタル庁の最新版JP PINT versionを再確認し、使用versionを送信runへ保存する。無検証の自動upgradeを禁止する。
- PDF請求を廃止せず、顧客ごとにPDF/email/Peppol delivery preferenceを持つ。

## R1. recipient/sender設定

1. THE 法人/顧客 SHALL Peppol participant ID、scheme、service provider、送受信可否、検証日を持つ。
2. THE participant ID SHALL provider directoryで検証し、未検証宛先へ送信しない。
3. THE 適格/非登録事業者区分 SHALL 対応するdocument profileを選ぶ。

## R2. invoice生成/検証

1. THE システム SHALL 既存請求をcanonical invoiceへ変換し、JP PINT XMLを生成する。
2. THE XML SHALL invoice番号、発行/支払日、通貨、売手/買手、登録番号、明細、税区分/税率/税額、合計、参照注文/契約を含む。
3. THE 送信前 SHALL schema、business rule、合計、必須IDをvalidatorで検査し、error/warningを画面表示する。
4. THE invoice金額 SHALL 既存Invoice/InvoiceItem/tax snapshotを唯一の正とし、JP PINT側で再計算して上書きしない。

## R3. 送受信/status

1. THE 送信 SHALL provider adapter/jobで冪等実行し、message ID、provider ID、送信version/statusを保存する。
2. THE status SHALL queued/sent/delivered/rejected/failed/**cancelled/revoked**をmappingし、webhook署名を検証する。
3. THE 受信invoice SHALL archiveへ原本XML/PDFを保存し、BP/注文/契約候補へ照合後、人が仕入登録を確定する。
4. THE duplicate SHALL message ID、supplier invoice number、hashで検知する。

## R4. 変更/監査

1. THE 送信済請求の訂正/取消 SHALL provider/JP PINTの許可方式に従い、旧messageを上書きしない。
2. THE XML、validation report、provider receipt、webhookをdocument/job監査へ関連付ける。

## R5. 受入

- provider公式test fixture/validatorに合格。
- 同一invoiceを再送してmessage 1件。
- webhook偽造/順序逆転/重複を安全に処理。
- 受信invoiceを自動支払確定せずreview待ちにする。
