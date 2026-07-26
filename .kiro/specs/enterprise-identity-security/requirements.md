# Requirements — 企業認証・セキュリティ

参照: `customer-product-expansion-2026/research-sources.md` のIPA、PPC、Spring Security公式資料。

## G1決定

- 内部IdPはMicrosoft Entra ID OIDC Authorization Code flow、全内部user MFA、管理者はFIDO2/passkeyを第1候補とする。
- アプリ側local loginは2つのTOTP break-glass管理者だけに限定し、一般local loginとSAML2初期実装は行わない。
- 詳細は`customer-product-expansion-2026/gate-decisions-g1-g6.md`を正とする。

## R1. 外部IdP/SSO

1. THE システム SHALL OIDC Authorization Code flowでMicrosoft Entra ID等の企業IdPへ接続できる。
2. THE 外部主体 SHALL `(tenant, provider, subject)`で一意にuserへ紐付け、emailだけの自動紐付けを禁止する。
3. THE 初回provisioning SHALL 招待済みemailまたは管理者承認を必須とし、IdP属性だけで管理者roleを付与しない。
4. THE ローカルログイン SHALL feature flagで残し、break-glass管理者以外をSSO必須にできる。
5. SAML2 SHALL 初期版の対象外とし、OIDC非対応顧客との契約が成立した場合だけ別specで追加する。

## R2. MFAとsession

1. THE Entra user SHALL IdP側MFA必須、THE 2つのlocal break-glass管理者 SHALL TOTP MFA必須とする。
2. THE recovery code SHALL 1回限り、hash保存、再発行時旧code無効化。
3. THE session SHALL 一覧、現在以外の強制失効、全session失効、idle/max lifetime、同時session上限を持つ。
4. WHEN user無効化/role変更/tenant停止/MFA reset時、THE システム SHALL 対象sessionを失効する。

## R3. 権限モデル

1. THE 固定role SHALL 後方互換を維持しつつpermission groupへ段階移行する。
2. THE permission SHALL menuだけでなくaction（閲覧/登録/更新/削除/承認/export/PII閲覧）を表現する。
3. THE 高機密項目（給与、口座、原価、個人連絡先） SHALL DTO/field単位で非表示にできる。
4. THE 権限変更 SHALL 申請者自身の権限昇格を禁止し監査する。

## R4. ファイル/秘密/監査

1. THE upload SHALL magic bytes、許可MIME、サイズ、malware scan状態を検証し、未検査/感染fileを配布しない。
2. THE 未参照file SHALL default denyとする。
3. THE OAuth token/TOTP/API key SHALL version付き暗号鍵で暗号化しrotationできる。
4. THE security event SHALL login成功/失敗、MFA、session失効、権限変更、file拒否を監査し、秘密値を記録しない。

## R5. 受入

- OIDC login/logout、IdP停止時break-glass、MFA recovery、role変更session失効が再現できる。
- 権限なしuserが画面/API/export/fileの全経路で同じ結果になる。
- EICAR等の安全な試験fixtureを感染扱いにし、download不可を確認する。
