# Requirements — 法定文書台帳・電子保存

## 前提

- 電帳法要件を意識した保存支援であり、法令適合を製品だけで保証しない。G2は公式資料+社内責任者で開発し、
  外部税理士/法務ReviewをM taskと本番交付のgateとする。税務取引文書default 10年、legal hold中は削除しない。
- 既存の見積PDF、契約PDF/署名済PDF、作業報告書、請求書、取込原本を段階的に統合する。
- 原本binaryをDBへ格納せず、metadataとhashをDB、binaryをStorage adapterへ保存する。

## R1. 文書と版

1. THE システム SHALL `document`を文書種別、相手先、取引日、金額、通貨、発行/受領、保存区分、状態で管理する。
2. THE 文書 SHALL 1件以上のversionを持ち、original file名、MIME、size、SHA-256、作成者、取得経路、scan状態を記録する。
3. WHEN versionを差替える時、THE システム SHALL 旧版を削除せず、理由、差分、操作者、時刻を保持する。
4. THE 原本確定後 SHALL 通常UIから上書き/物理削除不可とし、取消/訂正versionで処理する。

## R2. 業務との関連

1. THE 文書 SHALL 顧客、BP会社、要員、案件、提案、見積、注文、契約、勤怠、検収、請求、入金、BP支払へ複数関連付けできる。
2. THE 既存PDF生成 SHALL 生成直後のbyte/hashをversion登録し、同じ操作の再送で重複文書を作らない。
3. THE CloudSign同期 SHALL 署名済PDFと合意締結証明書を別document type/versionとして保存し、外部document IDを記録する。

## R3. 真実性・可視性・検索

1. THE システム SHALL 日付、金額範囲、相手先、文書種別、番号、発行/受領、関連業務IDで検索できる。
2. THE システム SHALL 訂正削除履歴、version hash、download/export履歴を提示できる。
3. THE 文書 SHALL 権限を持つ利用者が画面表示またはdownloadでき、税務調査用に検索結果と原本をZIP/manifestでexportできる。
4. THE manifest SHALL 文書ID、version、hash、日付、金額、相手先、元ファイル名をUTF-8 CSVで含む。

## R4. 保存/廃棄

1. THE 文書種別 SHALL retention policy（年数、起算日、法的hold可否）を持つ。
2. THE legal hold中 SHALL 自動廃棄しない。
3. THE 廃棄 SHALL 事前候補→承認→storage削除→廃棄証跡の順とし、単独管理者の即時物理削除を禁止する。
4. THE backup SHALL DB metadataとbinaryの同一時点整合を検証できる。

## R5. Storage/安全性

1. THE Storage SHALL localとS3互換adapterを持ち、業務コードがpathを直接扱わない。
2. THE download SHALL document ACL/tenant/data scopeを通り、未知fileを拒否する。
3. THE upload SHALL quarantine/scan完了後にのみ閲覧可能とする。
4. THE object key SHALL 推測困難かつtenant分離され、元ファイル名をpathに使わない。

## R6. 受入

- 同一請求書の生成、送付、訂正、取消の全版とhashが追跡できる。
- 日付/金額/相手先検索と税務exportが再読込可能。
- storage binary改ざん時にhash不一致を検知する。
- legal hold中の廃棄が拒否される。
