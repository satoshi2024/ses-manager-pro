# Requirements — 顧客・BP外部ポータル

## 前提/境界

- 内部`sys_user`/role/menu/APIを外部へ流用しない。外部専用identity、DTO、URL、security chainを使う。
- G3は`portal.<base-domain>`の別host/chain、招待制、内部と別identity、全portal user TOTP MFA、
  version付き利用規約を採用する。公開文書種別はG8のallow-listだけを使用する。
- 初期版は招待制。一般公開signup、決済、チャットbotは対象外。

## R1. 外部組織/ユーザー

1. THE システム SHALL 顧客組織/BP組織とportal userを管理し、内部customer/BP companyへ紐付ける。
2. THE 招待 SHALL 期限付き1回token（hash保存）、指定email、組織、権限を持つ。
3. THE portal user SHALL 自組織の許可された文書/契約/検収/請求だけを閲覧する。
4. THE portal SHALL 全user必須TOTP MFA、1回限りrecovery code、利用規約同意版、最終login、停止、session失効を持つ。
5. THE 顧客担当者/BP担当者の退職/無効化 SHALL portal accessを失効する。

## R2. 顧客ポータル

1. THE 顧客 SHALL 見積、注文請、契約、作業報告、検収、請求を閲覧/downloadできる。
2. THE 顧客 SHALL 月次作業報告を検収/差戻しし、commentと添付を残せる。
3. THE 顧客 SHALL 請求書の受領確認、支払予定日/問い合わせを登録できるが、入金済状態を直接変更できない。
4. THE 電子署名 SHALL CloudSign等の外部署名URLへ安全に遷移し、ポータルが署名を代行しない。

## R3. BPポータル

1. THE BP SHALL 自社の空き要員を登録/更新/停止し、内部営業のreview後に有効化する。
2. THE BP SHALL 発注条件/注文書を確認し、受領確認、請求書/作業報告書を提出できる。
3. THE BP SHALL 自社請求の受領/承認/支払予定/支払済状態を参照できるが、金額/支払状態を変更できない。
4. THE 口座変更 SHALL portalから申請のみ可能で、内部承認後にmasterへ反映する。

## R4. 通知/監査/安全

1. THE portal SHALL email通知設定を持ち、文書公開、検収、差戻し、支払状態を通知する。
2. THE download/検収/提出/口座変更 SHALL 外部user/組織/IP/時刻を監査する。
3. THE URL SHALL 連番IDだけで認可せず、組織scopeを必ず検証する。
4. THE upload SHALL archive quarantine/scanを通り、未検査fileを内部にも公開しない。
5. THE rate limit SHALL login、招待、download、upload、検収APIに適用する。

## R5. 受入

- 顧客Aが顧客B/BPのID・URL・fileを一切取得できない。
- 招待token再利用/期限切れ/email不一致を拒否。
- 顧客検収が内部acceptanceへ1回だけ反映。
- BP口座変更が承認前に支払先へ反映されない。
