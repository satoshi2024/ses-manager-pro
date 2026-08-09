# Requirements — 注文・注文請・月次検収

## R1. 受注/発注書

1. THE システム SHALL 顧客から受領する注文書と、自社が返す注文請書を管理する。
2. THE 注文 SHALL 注文番号、顧客PO番号、`legal_entity_id`（自社法人）、顧客/担当者、見積、案件、契約、注文日、期間、金額/単価、精算幅、支払条件、状態を持つ。注文請書PDFの発行者情報（社名・住所・インボイス登録番号）は`legal_entity_id`から動的に解決して印字する。
3. THE 注文明細 SHALL 1要員1明細を初期単位とし、複数要員注文を複数明細で表現できる。
4. THE 注文書原本/注文請書 SHALL document archiveへ保存し、相手先/日付/金額で検索できる。注文請書PDF発行時はdocument archive登録成功後のみ状態を注文請提出へ進める（fail-closed）。専用ダウンロードAPIも通常の文書ダウンロードと同等のaction permission (`file.download`)・scope・監査ログ記録を適用する。
5. THE 状態 SHALL 下書き→受領確認→注文請提出→契約化→完了/取消。許可外遷移を拒否する。

## R2. 見積/契約連携

1. THE 見積から注文draft SHALL 顧客、要員、案件、単価、精算幅を引き継ぐ。
2. THE 注文から契約draft SHALL 条件を引き継ぎ、source order IDで冪等生成する。
3. WHEN 注文条件と見積/契約が異なる時、THE システム SHALL 差分を表示し承認対象とする。
4. THE 顧客PO番号 SHALL tenant+customer内で重複警告し、同じ原本hashの二重登録は `tenant_id` + `document_type` + `file_hash` の DB UNIQUE / atomic claim により並行アップロード時も含めて拒否する。

## R3. 月次検収

1. THE システム SHALL 契約×月の検収を管理し、対象work record、提出日、顧客確認者、確認日、結果、差戻し理由、原本を持つ。提出処理は確定済みwork recordをロックおよびバージョン検証し、`reopenMonth`との並行競合時は一方が安全に失敗・ロールバックする。検収文書の権限範囲（scope）は対象月末時点（as-of）の契約母集団で判定する。
2. THE 状態 SHALL 未提出→提出済→検収済/差戻し。差戻し後は再提出できる。
3. THE 請求生成 SHALL 原則検収済work recordだけを対象とし、検収不要契約は `acceptance_required=0` かつ非空の免除理由（`acceptance_exemption_reason`）が存在する場合のみ許可し、DB CHECK制約および請求抽出SQLの両系でfail-closedに制御する。
4. THE 検収済みwork recordの再open/金額変更 SHALL 既存月次締め/請求guardに加え、検収取消承認を必要とする。承認適用時は対象のバージョンおよび状態を保持ロック下で再確認する。
5. THE 顧客ポータル SHALL 後続specで提出/検収を行い、本specは内部代行入力も提供する。

## R4. 通知/KPI

1. THE システム SHALL 注文未受領、注文請未返送、月次検収未提出/期限超過/差戻しを通知する。HR（人事）ロールは注文・検収・文書archive・未検収件数・dashboard集計の全経路で参照不可（0件/403）とする。
2. THE 月次締め SHALL 未検収件数をchecklistへ追加する（HR除外）。
3. THE dashboard SHALL 未検収売上、検収平均日数を表示する（HR除外）。

## R5. 受入

- 見積→注文→PO原本→注文請PDF/archive→契約→勤怠→検収→請求をIDで追跡できる実閉ループをMySQL環境で検証する。
- 注文/検収の二重clickで重複契約/検収ができない。
- 未検収契約から請求不可、検収不要契約は理由付きで可能。
- archive検索、`file.download` 権限、GET download監査、desktop/390px、アクセシビリティ（キーボード操作・label・aria-live）を満たす。

