# 企業認証・セキュリティ棚卸し（T014 / 0）

## 1. TASK CONTRACT

- task / objective: T014（0）/ G1、脅威モデル、permission inventoryを確定し、T015以降の認証・認可・file実装を検証可能にする。
- requirements / acceptance: R1.1〜R1.5、R2.1〜R2.4、R3.1〜R3.4、R4.1〜R4.4、R5。
- 顧客が観測する効果: Entra OIDCを正規入口とし、break-glass以外のlocal loginを制限する。MFA/session失効/action permission/file scanの拒否境界を画面・API・export・downloadで一致させ、秘密値を監査へ出さない。
- 変更予定file: 本文書、`review-ledger.md`、`tasks.md`、中央`spec-execution-ledger.md`。
- 変更禁止file: production Java/SQL、`SecurityConfig.java`、共通認証ファイル。これらはT015〜T019で主AIが順次変更する。
- database/API/UI/event/cache/file契約: 外部subjectは`(issuer, subject)`で一意。emailだけでlinkしない。認可はmenu表示ではなくAPI/serviceのaction判定を正とする。未知file、scan未完了、scanner unavailableは拒否する。
- timezone/asOf/対象月/締め/履歴: 認証時刻はUTCのInstant、表示は既存アプリtimezone。sessionのidle/max lifetimeと失効時刻はInstantで保存する。T014では業務履歴のasOf契約を追加しない。
- NULL/未設定/不存在/fallback: issuer/subject/providerが解決できない、招待がない、actionが未定義、file参照元が未知の場合はfail-closed。legacy role fallbackはpermission group未設定時だけとする。
- concurrency/idempotency/transaction: external identityの一意制約、recovery codeの一回使用、session revokeのCAS/一意性、permission変更と監査の同一transactionをT015以降で実装する。
- backfill/reconciliation/rollback: legacy role→default permission groupのseedを行い、未設定groupはlegacy fallback。V63未適用DBへ認証機能を公開しない。rollbackはfeature flagでOIDC/local login入口を閉じ、DBはバックアップ復元を使用し、公開migrationを改変しない。
- test matrix: L0で文書・ID・migration・consumer整合性。T015以降でunique/tenant、OIDC異常、MFA replay、role/session、action matrix、file scannerのL1〜L3を実行する。
- Demo: T014ではsecurity review向けinventory確認。実Entra tenant/app登録、実ログイン、実break-glass訓練は外部環境gateとしてT016/T017/T020へ引き継ぐ。未実施を実施済みとは扱わない。

## 2. G1決定とEntra接続inventory

G1の正本は`gate-decisions-g1-g6.md`であり、ここで再決定しない。

| 項目 | 確定契約 | 実装/確認状態 | fail-closed条件 |
|---|---|---|---|
| IdP | Microsoft Entra ID | G1で確定。実tenant/app登録は本作業環境から未実施 | issuer不一致、metadata取得失敗、IdP timeout |
| protocol | OIDC Authorization Code | T016でSpring Security `oauth2Login`へ実装 | implicit flow、未検証nonce/state、code再利用 |
| external key | `(issuer, subject)` | V63の` t_user_external_identity`でunique化 | emailのみの自動link、subject空値 |
| provisioning | 招待済みemailまたは管理者承認 | T016で実装 | 未招待・未承認、IdP属性だけのrole付与 |
| MFA | Entra全内部user、管理者FIDO2/passkey優先 | IdP Conditional Accessを前提としT016/T017で状態を検証 | MFA claim/assurance不足、break-glass以外のlocal bypass |
| claim mapping | `iss`/`sub`をidentity key、emailはsnapshotと招待照合、nameは表示用、groupsは候補情報のみ | role/admin付与へ直接利用しない | issuer欠落、subject欠落、email衝突 |
| logout | local session invalidation、providerが対応する場合RP-initiated logout | T016で実装 | logout後session再利用、redirect URI未許可 |
| local fallback | 2つの個人非依存break-glass管理者だけ。TOTP必須 | T017で登録・回復・監査を実装 | 一般userのlocal login、MFAなしbreak-glass |

### Entra app登録チェックリスト（外部gate）

- [ ] test tenant、app registration、tenant ID、client IDを発注者管理下で登録。
- [ ] redirect URIをHTTPSの許可済み値だけに固定し、localhostは開発profileだけに限定。
- [ ] issuer/discovery URLをtenant固定で登録し、複数issuerを自動受入しない。
- [ ] client secretはDB平文・ログへ保存せず、version付き暗号secret refへ格納。
- [ ] Conditional Accessで全内部userのMFA、管理者のphishing-resistant authentication strengthを有効化。
- [ ] groups claimはallow-listと照合するが、admin/roleをgroupsだけから自動付与しない。
- [ ] logout redirect、session lifetime、監査通知、break-glass除外理由をsecurity ownerが確認。

## 3. 認証flowと境界

1. 未認証userが`/login`からOIDC Authorization Codeへ遷移する。
2. callbackでissuer、state、nonce、code、signature、必要なMFA assuranceを検証する。
3. `(issuer, subject)`で外部identityを検索する。既存linkがなければ招待済みemailまたは管理者承認を検証する。
4. email衝突だけでは既存userへlinkせず、拒否して管理者の明示操作を要求する。
5. roleは既存`sys_user.role`/承認済みpermission groupから解決し、IdP claimから直接昇格しない。
6. login成功、失敗、拒否理由の分類、session発行を監査する。token、code、secret、TOTP値は監査・通常ログへ出さない。
7. logoutではlocal sessionを失効し、provider logoutを使う場合も固定済みredirectのみ許可する。
8. IdP停止・issuer不正・timeout時は一般local loginへfallbackせず、登録済みbreak-glassだけをTOTP付きで許可する。

## 4. Action permission inventory

### 4.1 共通action

| action key | 意味 | 主要consumer | 高リスク/field |
|---|---|---|---|
| `auth.login` | OIDC/local認証開始・完了 | `/login`、OIDC callback | 認証回避 |
| `auth.logout` | sessionとprovider logout | `/logout` | session再利用 |
| `auth.mfa.setup` | TOTP/passkey登録 | MFA管理画面/API | secret |
| `auth.mfa.recover` | recovery code使用・再発行 | recovery API | recovery hash |
| `auth.session.view` | 自分のsession一覧 | session管理 | IP/UA metadata |
| `auth.session.revoke` | 自分/管理対象session失効 | session API | 全session失効 |
| `user.role.change` | role/group変更 | user管理API | 自己昇格、権限喪失 |
| `permission.group.assign` | group/action割当 | permission管理API | 権限変更監査 |
| `file.upload` | upload受付 | 各upload endpoint | PII/malware |
| `file.download` | file配布 | download endpoint | 未参照/感染file |
| `file.scan.retry` | 再scan | 管理scan endpoint/job | quarantine解除 |
| `audit.security.view` | security監査閲覧 | audit API/page | token/secret masking |

### 4.2 業務高リスクaction

| action key | 例 | 制御必須事項 |
|---|---|---|
| `invoice.view` / `invoice.update` / `invoice.export` | 請求書一覧・更新・export | DataScope/OrganizationScope、CSRF、監査、CSV同一母集団 |
| `contract.cost.view` | 原価・粗利閲覧 | role/groupとfield masking、組織scope |
| `payroll.view` | 給与・給与関連 | 管理者/HRの最小権限、field masking、監査 |
| `bank-account.view` | 口座情報閲覧 | DTO allow-list、画面/API/export非表示整合 |
| `personal-contact.view` | 個人連絡先閲覧 | HR/許可groupのみ、AI送信禁止/マスキング |
| `work-record.approve` / `work-record.reopen` | 勤怠確定・再開 | role、組織scope、状態CAS、CSRF、監査 |
| `organization.update` / `organization.assign` | 組織・所属変更 | manager境界、version CAS、session失効連携 |
| `user.disable` / `user.delete` | user無効化・削除 | 自分自身のlockout防止、assignment close、session失効 |

移行規則は「permission groupが存在する場合はgroup actionを正とし、未設定groupのみlegacy role fallback」とする。menuだけを表示してもaction/API/serviceで拒否されるため、sidebarを認可実装とはみなさない。

## 5. 脅威モデル

| 脅威 | 影響 | 防御境界 | 失敗時動作 | 検証Task |
|---|---|---|---|---|
| issuer/tenant偽装 | 他tenant userとしてlogin | issuer固定、`(issuer,subject)` unique、signature/nonce/state検証 | login拒否、秘密非出力、監査 | T016/T020 |
| email衝突・JIT昇格 | 他人account link/admin化 | email単独link禁止、招待/承認、role claim禁止 | 403/認証拒否 | T016 |
| IdP timeout/停止 | 認証不能、危険なlocal fallback | local fallbackをbreak-glass限定 | 一般local login拒否 | T016/T020 |
| token/code/recovery漏洩 | takeover | secret ref/hash/encryption、ログmasking、一回使用 | code/session失効、監査 | T015/T017/T020 |
| MFA replay | MFA bypass | step/use marker、時刻窓、一回限りrecovery | 拒否、監査、rate limit | T017 |
| role/group自己昇格 | 高権限操作 | self-change禁止、承認/監査、session失効 | 403/409、transaction rollback | T018 |
| IDOR/scope漏洩 | 他組織PII/財務情報 | service/query boundary、field masking | 403/404、0件 | T018/T020 |
| menu-only bypass | hidden APIへ直接アクセス | AuthorizationServiceをcontroller/serviceへ適用 | 403/監査 | T018 |
| 未知/感染file配布 | malware/PII漏洩 | reference allow-list、quarantine、scan status | download拒否 | T019 |
| scanner停止 | 未検査file配布 | scanner unavailable fail-closed | upload quarantine継続 | T019 |
| CSRF | 権限操作の強制実行 | Cookie token→header、更新API保護 | 403、監査対象 | T016/T018/T019 |
| session残存 | role変更後の権限継続 | persistent session revoke、max/idle、全session失効 | 401/再認証 | T017 |

## 6. PII/秘密分類

| 分類 | 例 | 保存/表示 | ログ・監査 |
|---|---|---|---|
| S0 認証秘密 | client secret、OAuth token、TOTP secret、recovery code原文 | version付き暗号化またはhash。原文再表示不可 | 値を記録しない。存在/結果/IDのみ |
| S1 高機密個人情報 | 給与、口座、個人番号、個人連絡先 | DTO field allow-list、action permission、組織scope | 値を記録しない。対象IDとaction/resultのみ |
| S2 業務機密 | 原価、粗利、契約金額、添付原本 | role/group、DataScope、FileScope、export同一境界 | 金額の必要最小限、秘密値なし |
| S3 個人関連metadata | email、氏名、IP hash、UA、subject snapshot | 目的限定、retention/アクセス制限 | IP/UAはhashまたは必要最小限 |
| S4 公開可能情報 | menu、状態、表示名の一部 | 通常UI/API | 通常の監査対象 |

## 7. Break-glass責任・復旧手順

- アカウント: 個人名ではなく`BG-01`/`BG-02`の2アカウントを構成上の識別子とし、実ユーザー名・連絡先は本書へ記録しない。
- 責任者: G1で確定したbreak-glass責任者が所有者。日常運用者、承認者、監査閲覧者を分離する。
- 保管: TOTP seedはversion付き暗号化、recovery codeはhashのみ。2人の責任者が分離保管し、同一人物が2アカウントを単独管理しない。
- 利用開始: Entra障害/設定事故をincidentとして起票し、責任者2名の承認、理由、開始時刻、対象操作、correlation IDを記録する。
- 認証: local login feature flagが有効な場合もTOTPを必須とし、recovery codeは1回だけ使用する。通常業務・権限変更・データexport目的の常用は禁止する。
- 利用中: security eventを即時通知し、全操作を監査する。token、TOTP、recovery code、passwordはログへ出さない。
- 終了: incident復旧後にlogoutと全session失効、必要ならsecret/recovery code再発行、local flag無効化、責任者が事後レビューする。
- 演習: 90日ごとにlogin→限定操作→logout/session revoke→recovery code無効化を訓練し、結果と未達を監査へ保存する。
- 失敗時: 責任者不在、2名承認なし、TOTP/recovery不一致、監査不能、IdP障害未確認の場合は利用を拒否する。

## 8. T014の未実施外部gateと次Taskへの引継ぎ

- 未実施: Entra test tenant/app registration、実Conditional Access、実OIDC login/logout、実break-glass訓練。
- 実装可能な代替: G1正式決定、OIDC/WireMock fixture、Spring Security test principal、固定時刻TOTP、fail-closed単体/MVCテスト。
- 本番gate: 実tenant/app・責任者の確認・90日演習記録はT020/本番release前に必須。未確認をPASS根拠にしない。
- T015開始条件: 本inventoryのissuer/subject、secret/hash、session、permission、scan状態の契約を固定し、V63でDDLを実装する。
- ロールバック: T015以降のfeature flagを無効化し、V63適用DBはバックアップ復元。V61/V62および既存公開migrationは変更しない。
