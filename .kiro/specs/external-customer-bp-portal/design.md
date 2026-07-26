# Design — 顧客・BP外部ポータル

## 1. DDL（予約V71）

- `m_portal_organization(id, tenant_id, type CUSTOMER/BP, customer_id, bp_company_id, status)`。
- `t_portal_user(id, portal_org_id, email, display_name, status, mfa_policy, last_login_at, version)`。
- `t_portal_invitation(portal_org_id, email, token_hash, role, expires_at, used_at, invited_by)`。
- `t_portal_user_permission(user_id, permission_key)`。
- `t_portal_terms_consent(user_id, terms_version, consented_at, ip_hash)`。
- `t_portal_message/attachment`は初期版では問い合わせthreadが必須と確定した場合のみ。通常は既存comment/taskを利用。

## 2. Security boundary

- `/portal/**`, `/api/portal/**`専用`SecurityFilterChain`とprincipal `PortalLoginUser`。
- 内部`LoginUser`へ変換しない。内部service呼出時は`PortalAuthorizationService`がtarget→customer/BP IDを検証。
- 招待tokenは256bit random、DBはSHA-256 hash、URL log/mailerでtokenをmask。
- portal session cookie名/pathを内部と分離。CSRFも専用cookie/header。

## 3. Public DTO/adapters

- `PortalDocumentService`, `PortalAcceptanceService`, `PortalBpSubmissionService`。
- 内部entityをJSON返却せず、金額/原価/営業memo/個人情報をallowlist DTOへ変換。
- 検収はAcceptanceService、空き要員はingestion review、口座はapproval requestへ委譲。

## 4. UI

- portal専用layout（内部sidebar/CDN管理を流用しすぎない）。顧客/BP dashboard、document、acceptance、invoice/payment、submission。
- モバイル優先、accessible、session expiry明示、問い合わせ先表示。

## 5. 通知/運用

- email linkはlogin後に目的画面へ戻る安全なrelative return URL。
- 管理画面で組織/user/invitation/session/access log。
- provider outage時も内部業務を止めず、portal操作はretry可能。

## 6. テスト

組織A/B matrix、招待、MFA/session、CSRF/rate limit、IDOR、DTO field allowlist、file scan、二重検収、
portal停止、terms version、mobile browser。

