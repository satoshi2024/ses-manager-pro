# Requirements — CRM複数担当者・商機管理

## R1. 顧客担当者

1. THE 顧客 SHALL 複数担当者を持ち、部署、役職、役割（決裁者/現場/調達/請求/契約）、連絡先、主担当、有効期間を管理する。
2. THE 既存`m_customer.contact_*` SHALL 初回contactへ移行し、移行後は互換表示だけにする。
3. THE 退職/異動担当者 SHALL 履歴を残し、新規メール宛先候補から除外する。
4. THE PII閲覧 SHALL permissionとdata scopeを適用し、exportにも同じmaskを使う。

## R2. lead/opportunity

1. THE システム SHALL lead（未取引候補）とopportunity（売上商機）を管理する。
2. THE opportunity SHALL 顧客、件名、stage、想定開始月、期間、必要人数、想定単価/金額、確度、担当営業、次action、競合、失注理由を持つ。
3. THE stage SHALL 見込→要件確認→提案準備→見積提出→交渉→受注/失注の状態機械とする。
4. WHEN 受注した時、THE opportunity SHALL project/quotationへ冪等変換できる。既存`Project`を商機として流用しない。

## R3. 活動/メール/日程

1. THE 既存sales activity SHALL customer/contact/opportunityへ関連付け、担当者、完了状態、次actionを持つ。
2. THE メール送信履歴 SHALL 宛先contactとopportunityへ関連付ける。
3. calendar/email同期 SHALL provider adapter境界だけ用意し、Gmail/Outlook本実装は資格情報と契約確定後の別laneとする。
4. THE 重複連絡先/lead SHALL email/phone/正規化会社名で候補表示し、自動mergeしない。

## R4. KPI

1. THE CRM SHALL stage金額、滞留日数、活動なし日数、担当別転換率、失注理由、source ROIを表示する。
2. THE forecast SHALL 既存提案加重売上とopportunity forecastを別系列で表示し、二重加算を防ぐ。

## R5. 受入

- 1顧客に決裁/現場/請求担当を別々に登録し帳票/メールで正しい宛先を選べる。
- opportunity受注再実行でproject/quotationが重複しない。
- 提案へ変換済み商機をforecastで二重計上しない。

