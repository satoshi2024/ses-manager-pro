# Design — 顧客・BP外部ポータル

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V100）

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

## 6. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`。ここには本spec固有の行と逸脱だけを書く。

**本specは§2の認可母集団の既定解が適用できない唯一のspecである。** portal userは
`sys_user`ではなく、DataScope・組織scope・menu権限のいずれも持たない。母集団は
`portal_org → customer_id / bp_company_id` から**独立に**導出する。既存scope serviceを流用しない。

### 6.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| portal user | `t_portal_user.status` | 監査ログ | — | 現在値 | — |
| 招待token | `expires_at`未到来かつ`used_at IS NULL` | — | token_hashのみ保存 | 検証時刻 | `used_at IS NULL`＝**未使用**（有効とは限らない。期限を別途確認） |
| 利用規約同意 | 最新`terms_version`への同意 | `t_portal_terms_consent`（append-only） | 同意時のversion/ip_hash | 現在の掲示version | **未同意**。同意画面へ強制遷移 |
| 公開文書 | archive の`t_document` | 版はarchive側 | — | 現在値 | — |
| 検収 | order spec の`t_acceptance` | — | 提出時snapshot | 対象月 | — |
| BP口座変更申請 | approval spec の request | — | — | 現在値 | 承認前は**masterへ反映しない**（R3.4） |

- `used_at IS NULL`だけで招待を有効と判定しない。`expires_at`・email一致・組織一致の
  **4条件すべて**を検証する（R5）。§1.1の「NULLを有効の意味に使わない」に該当。
- 顧客担当者・BP担当者が内部側で退職/無効化されたら、**portal accessも失効**する（R1.5）。
  contact の`valid_to`到来を検知してportal userを停止するbatchを持つ。

### 6.2 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| portal user (顧客) | **自組織の`customer_id`に紐づくもののみ**。G8 allow-listの文書種別に限定 | 同左 | 自組織宛の公開/検収/差戻し | — |
| portal user (BP) | **自社の`bp_company_id`に紐づくもののみ**。発注/検収/請求/支払状況 | 同左 | 自社宛の公開/支払状態 | — |
| 内部 管理者 | portal組織/user/invitation/session/access logの全件 | 全件 | 停止/異常 | 招待期限、access log集約 |
| 内部 営業 | 自担当顧客のportal組織のみ（既存DataScope） | 同左 | 自担当の提出/検収 | — |
| 内部 HR / 要員 | 不可視 | — | — | — |
| scheduler principal | 全件 | — | 宛先はportal user個人 | 招待期限切れ、contact失効連動 |

- **連番IDだけで認可しない**（R4.3）。`/api/portal/**`の全endpointは
  `PortalAuthorizationService`で`target → customer_id / bp_company_id`を解決し、
  `PortalLoginUser`の組織と一致することを**query boundaryで**検証する。取得後checkにしない。
- 顧客A/顧客B/BPの3組織matrixを**全endpoint × 全HTTP method**でparameterized testにする。
  IDOR testは代表endpointだけでは不十分（R5）。
- 公開fieldはallow-list DTO。内部entityをそのままJSON化しない。
  原価・粗利・営業memo・他社情報・要員の個人情報を**構造的に**返せない形にする。
- rate limitはlogin/招待/download/upload/検収APIへ適用（R4.5）。

### 6.3 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| invitation 発行済 | →使用済 / →期限切れ | `used_at`の**CAS**（`WHERE used_at IS NULL`） | token同時使用 | 再発行（旧tokenは無効） |
| portal user 有効 | →停止 / →失効 | 状態CAS | 内部contact失効との競合 | 再招待 |
| terms 未同意 | →同意済 | `UNIQUE(user_id, terms_version)` | 二重同意 | — |
| 検収 提出済 | →検収済 / →差戻し | order spec の状態CAS＋`version` | **顧客portalと内部代行の同時操作** | 提出済へ |
| BP availability 提出 | →review待ち→有効 / →却下 | 状態CAS | 二重提出 | review待ちへ |
| BP口座変更 申請 | →承認待ち→承認済 / →却下 | approval engine | — | **承認前はmaster不変** |

- **招待tokenの一回性はDB CASで保証する**（`UPDATE ... WHERE used_at IS NULL`）。
  アプリ側の「存在チェック→更新」にすると同時使用で二重登録される。
- **検収の二重反映防止**（R5）: portal検収は`AcceptanceService`へ委譲し、
  order specの`UNIQUE(contract_id, work_month)`＋状態CASをそのまま使う。
  portal側で独自の検収テーブル・独自の状態機械を作らない。
  顧客portalと内部代行入力が同時に検収した場合、**先着1件が成功、2件目はCAS失敗**。
- portal uploadはarchiveのquarantine/scanを通す。**未scanファイルは内部にも公開しない**（R4.4）。
  scan結果不明時はfail-closed。
- portal session cookieは名前/path/CSRFを内部と**完全分離**。
  `PortalLoginUser`を内部`LoginUser`へ変換する経路を作らない（design §2）。

## 7. テスト

組織A/B matrix、招待、MFA/session、CSRF/rate limit、IDOR、DTO field allowlist、file scan、二重検収、
portal停止、terms version、mobile browser。

