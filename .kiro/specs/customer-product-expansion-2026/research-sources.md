# 外部調査資料（2026-07-26確認）

本書は実装上の根拠を整理するもので、個別案件の法的助言ではない。G2は公式資料で基盤開発を開始し、法定帳票、
適用対象、保存期間、支払条件の外部専門家ReviewをM/本番gateとする。外部仕様は実装開始時にも再確認し、確認日と版をdesignへ記録する。

## 1. 派遣・請負・労務

- 厚生労働省「労働者派遣・請負を適正に行うためのガイド」
  - https://www.mhlw.go.jp/stf/seisakunitsuite/bunya/0000077386_00020.html
  - 指揮命令を受ける形態と請負の区分、偽装請負リスクの根拠。
- 厚生労働省/労働局「派遣事業運営にかかる様式例・記載例」
  - https://jsite.mhlw.go.jp/fukushima-roudoukyoku/hourei_seido_tetsuzuki/roudousha_haken_00003.html
  - 派遣契約、就業条件明示、派遣先通知、派遣元管理台帳等の帳票項目確認用。
- 厚生労働省「労働者派遣事業を適正に実施するために」
  - https://www.mhlw.go.jp/content/001374043.pdf
  - 派遣元管理台帳の作成項目と派遣終了日起算3年保存の確認元。
- 厚生労働省「時間外労働の上限規制」
  - https://hatarakikatakaikaku.mhlw.go.jp/overtime.html
  - 原則月45時間/年360時間、特別条項でも年720時間、複数月平均80時間、月100時間未満等。

## 2. BP/フリーランス/取適法

- 公正取引委員会「フリーランス法」
  - https://www.jftc.go.jp/fllaw.html
  - 取引条件の書面/電磁的方法による明示、報酬額、支払期日等。
- 公正取引委員会「取適法」
  - https://www.jftc.go.jp/toriteki/
  - 2026-01-01施行。適用範囲、発注内容明示、支払、書類保存の確認元。
- 公正取引委員会「取適法Q&A」
  - https://www.jftc.go.jp/toriteki/toriteki_qa.html
  - 支払期日の特定、受領から60日以内、商社経由支払等の具体判断。

## 3. 電子文書・電子署名・個人情報

- 国税庁「電子帳簿保存法の概要」
  - https://www.nta.go.jp/law/joho-zeikaishaku/sonota/jirei/02.htm
- 国税庁「電子取引の適用要件」
  - https://www.nta.go.jp/law/joho-zeikaishaku/sonota/jirei/07denshi/02.htm
  - 真実性、可視性、日付/金額/相手先検索、見読可能性の設計根拠。
- デジタル庁「電子署名」
  - https://www.digital.go.jp/policies/digitalsign
- クラウドサイン Web API
  - https://help.cloudsign.jp/ja/articles/936884
- 個人情報保護委員会 ガイドライン（通則編）
  - https://www.ppc.go.jp/personalinfo/legal/guidelines_tsusoku/
  - 最小アクセス、識別認証、不正アクセス防止、ログ分析等。

## 4. セキュリティ・認証

- IPA「中小企業の情報セキュリティ対策ガイドライン 第4.0版」
  - https://www.ipa.go.jp/security/guide/sme/about.html
  - MFA、バックアップ、アクセス制御、インシデント対応の基準。
- Spring Security OAuth2/OIDC Login
  - https://docs.spring.io/spring-security/reference/servlet/oauth2/login/index.html
- Spring Security SAML2 Login
  - https://docs.spring.io/spring-security/reference/7.0/servlet/saml2/login/overview.html
- Microsoft Entra「Manage emergency access admin accounts」
  - https://learn.microsoft.com/en-us/entra/identity/role-based-access-control/security-emergency-access
  - 2つ以上のemergency account、phishing-resistant認証、定期検証の根拠。
- Microsoft Entra Conditional Access
  - https://learn.microsoft.com/en-us/entra/identity/conditional-access/

## 5. 会計・デジタルインボイス

- freee API共通リファレンス
  - https://developer.freee.co.jp/reference/
- freee会計API
  - https://developer.freee.co.jp/reference/accounting/reference/
- freee販売API
  - https://developer.freee.co.jp/reference/sm/reference/
  - プラン別API、OAuth2、呼出上限、`X-Freee-Request-ID`、請求/販売/発注APIの確認元。
- デジタル庁「JP PINT」
  - https://www.digital.go.jp/policies/electronic_invoice
  - 実装時に最新版仕様と認定Service Provider一覧を再確認する。
- デジタル庁「日本のPeppol Certified Service Provider一覧」
  - https://www.digital.go.jp/policies/electronic_invoice/list-japanese
- ファーストアカウンティング「Peppol Access Point API」
  - https://www.fastaccounting.jp/service/
