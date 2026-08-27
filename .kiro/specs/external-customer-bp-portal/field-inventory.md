# 公開Field Inventory — 顧客・BP外部ポータル（S13 T081）

> 本ファイルは `external-customer-bp-portal` specのT081（0. G3/G8と公開field inventory）の成果物である。
> 以後のtask（T082〜T087）は、ここに定めた公開可否matrixを正としてDTO・API・画面を実装する。
> 本specは `platform-invariants.md` §2（認可母集団）の既定解が適用できない**唯一のspec**であり、
> portal userの母集団は `portal_org → customer_id / bp_company_id` から独立に導出する（design.md §6.2）。

## 1. Decision記録

### 1.1 G3（決定済み 2026-07-26、gate-decisions-g1-g6.md §4）

- 公開hostは設定可能な `portal.<base-domain>`。内部管理画面と別subdomain・別SecurityFilterChain・
  別session cookie name/domain・別CSP/Rate Limit。
- 初期版は**招待制のみ**（一般公開signup・social loginは実装しない）。
- 顧客組織/BP組織、portal user、内部`sys_user`は別table/identity境界。内部role/menu/APIを再利用しない。
- 招待tokenは72時間有効・1回限り・hash保存・指定email/組織/権限へ固定。初回組織管理者は内部管理者が招待、
  2人目以降は当該組織の管理者の承認を必要とする。
- 全portal userにTOTP MFA必須、recovery codeは1回限りhash保存。password reset / MFA reset / 組織停止時は全session失効。
- 利用規約・privacy noticeはversion管理し、初回loginと重要改定時に再同意を要求。
- support accessは申請・期限・理由・監査付き（impersonation禁止）。

### 1.2 G8（2026-08-16、推奨既定を採用。decision-log.md 参照）

公開文書種別のallow-list（この一覧以外を「たぶん見せてよい」で追加しない）:

| 主体 | 公開文書種別 |
|---|---|
| 顧客portal user | 見積 / 注文請 / 契約 / **作業報告** / 検収 / 請求 / **サービスリクエスト添付 (PORTAL_VISIBLE)** |
| BP portal user | 発注 / 検収 / BP請求 / 支払状況 |

- 顧客の「作業報告」はrequirements R2.1が明示するため、G8推奨既定（顧客=見積/注文請/契約/検収/請求）へ
  **要件が明示する1行だけを追加**した。
- 顧客の「サービスリクエスト添付」は NF-02 (customer-success-service-desk) に基づき、`visibility = 'PORTAL_VISIBLE'` かつ自社スコープに限定して公開する。
- allow-list外の例（構造的に公開しない）: 原価・粗利・仕入単価・営業memo・社内評価・他社（他BP/他顧客）情報・
  要員個人情報（氏名以外の連絡先・住所・年齢・給与等）・監査ログ内部詳細・社内通知・INTERNALメモ/INTERNAL添付。

## 2. 主体 × 画面 × 操作一覧

### 2.1 顧客portal（CUSTOMER org type）

| # | 画面/機能 | 操作 | 備考 |
|---|---|---|---|
| C-1 | ログイン/招待受諾 | 招待token受諾→パスワード/表示名設定→MFA設定→規約同意→login | 全user TOTP必須（G3） |
| C-2 | Dashboard | 自組織の契約数・検収待ち・請求額合計等の要約 | 集計は自組織分のみ |
| C-3 | 見積一覧/詳細 | 閲覧、PDF download | 状態: 下書き/提出済/受注/失注 |
| C-4 | 注文請一覧/詳細 | 閲覧、PDF download | t_sales_order（注文請提出済） |
| C-5 | 契約一覧/詳細 | 閲覧 | 稼動中/終了等の状態表示 |
| C-6 | 作業報告・検収一覧 | 閲覧、**検収/差戻し**、comment、添付 | 月次作業報告=work record確定分。検収は`AcceptanceService`へ委譲（独自状態機械を作らない） |
| C-7 | 請求一覧/詳細 | 閲覧、PDF download、**受領確認**、**支払予定日登録**、**問い合わせ登録** | 入金済状態の直接変更APIは**存在させない**（R2.3） |
| C-8 | 電子署名遷移 | CloudSign等の外部署名URLへ遷移（ポータルが署名を代行しない） | R2.4 |
| C-9 | サービスデスク（問い合わせ） | **起票**、**一覧/詳細閲覧**、**返信コメント**、**添付download**、**CSAT回答** | NF-02 顧客サービスデスク。自社スコープ・内部メモ/内部添付完全除外 |

### 2.2 BP portal（BP org type）

| # | 画面/機能 | 操作 | 備考 |
|---|---|---|---|
| B-1 | ログイン/招待受諾 | 顧客と同じ | |
| B-2 | Dashboard | 自社の発注・検収・支払の要約 | 自社分のみ |
| B-3 | 空き要員 | **登録/更新/停止** | 内部営業review後に有効化（ingestion reviewへ委譲） |
| B-4 | 発注（注文書/発注条件）一覧/詳細 | 閲覧、**受領確認** | t_bp_payment行＋t_bp_terms（発注条件） |
| B-5 | 検収（作業実績） | 閲覧 | work recordの確定状況（検収）参照 |
| B-6 | 請求書/作業報告書 | **提出**（upload） | archiveのquarantine/scanを通す（R4.4） |
| B-7 | 支払状況 | 参照（受領/承認/支払予定/支払済） | 金額・支払状態の**変更APIは存在させない**（R3.3） |
| B-8 | 口座変更 | **申請のみ** | 内部承認（approval request）後にmaster（t_bp_bank_account）へ反映（R3.4） |

### 2.3 内部 管理者（portal管理）

| # | 画面/機能 | 操作 |
|---|---|---|
| A-1 | portal組織一覧/CRUD | 顧客/BP組織の登録・停止、customer_id/bp_company_id紐付け |
| A-2 | portal user一覧/CRUD | 招待発行、停止/失効、role変更、session失効 |
| A-3 | 招待一覧/再発行 | 期限切れの再発行（旧tokenは無効化） |
| A-4 | access log | 外部user/組織/IP/時刻の監査表示（R4.2） |
| A-5 | 利用規約管理 | version発行、再同意要求のトリガー |
| A-6 | 空き要員review | ingestion review（承認/却下） |
| A-7 | 口座変更の承認 | approval workflowで承認後masterへ反映 |

### 2.4 内部 営業

- 自担当顧客（DataScope）のportal組織のみ閲覧可。提出/検収の通知を受ける。
- HR/要員ロールはportal管理画面へ**不可視**。

## 3. 文書種別 × 公開field matrix（allow-list DTO）

> 実装原則: 内部entityをJSON返却しない。allow-list DTOへ**必要な項目だけ**をコピーし、
> 非公開項目はDTOのクラス構造自体に含めない（「nullにする」ではなく「構造的に存在しない」）。

### 3.1 顧客向け

#### 見積（t_quotation）
| 公開 | 非公開（構造的に排除） |
|---|---|
| quotationNo, title, status, unitPrice, settlementHoursMin/Max, validUntil, remarks, createdAt | projectId内部ID※1, engineerId※1, proposalId, sourceOpportunityId, createdBy, updatedAt, cost情報一切 |

※1 契約詳細画面では「案件名」「要員表示名」として解決済みの表示名のみ渡す。IDは渡さない（ID推測の攻撃面を減らす）。

#### 注文請（t_sales_order の注文請提出済み行）
| 公開 | 非公開 |
|---|---|
| orderNo, customerPoNo, orderDate, 期間, 金額（受領確認で固定されたsnapshot）, 支払条件snapshot, status, 注文請PDF | tenantId, legalEntityId, quotationId, contactId, createdBy, 内部workflow列 |

#### 契約（t_contract）
| 公開 | 非公開 |
|---|---|
| contractNo, contractType, startDate, endDate, contractDate, jobDescription, workLocation, inspectionDueDate, paymentDueDate, paymentMethod, settlementHoursMin/Max, status, acceptanceRequired | **sellingPrice/costPrice（原価・粗利）**, fractionRule, salesUserId, commission*, renewedFromContractId, costCenterId, directCommandFlag, projectId/engineerIdの内部ID※1 |

→ 契約画面に金額を出さない。金額の受け渡しは検収（amountSnapshot）・請求（total）でのみ行う。

#### 作業報告（t_work_record）※検収コンテキストでの表示
| 公開 | 非公開 |
|---|---|
| workMonth, actualHours, billingAmount, status（入力中/提出済/差戻し/確定）, remarks, rejectComment, 日次内訳（t_work_record_daily: workDate, startTime, endTime, breakMinutes, workedHours, remarks） | paymentAmount（原価側）, organizationId, costCenterId, accountingDimensionFrozen, createdBy |

#### 検収（t_acceptance）
| 公開 | 非公開 |
|---|---|
| workMonth, status, submittedAt, acceptedAt, rejectComment, hoursSnapshot, amountSnapshot, customerContactNameSnapshot, 検収書document download | customerContactId, workRecordId, documentId（内部ID）, version, createdBy, contractId※1 |

#### 請求（t_invoice）
| 公開 | 非公開 |
|---|---|
| invoiceNo, billingMonth, subtotal, tax, total, taxRate, status, issuedDate, dueDate, **支払予定日（portal登録値）**, **受領確認日時（portal登録値）**, **問い合わせ（portal登録値）**, PDF download | costCenterId, paidDate（入金済日。portalへ出さない）, createdBy, version, 消込/督促内部情報 |

- R2.3: 顧客は受領確認・支払予定日・問い合わせを**登録**できる。入金済状態の変更APIを存在させない。
- 請求書PDFは既存のinvoice PDF生成物を利用する。

### 3.2 BP向け

#### 発注（t_bp_payment 行 = 注文書）+ 発注条件（t_bp_terms）
| 公開 | 非公開 |
|---|---|
| payeeCompanyName（自社）, layerOrder, amount（自社分）, status（未払/支払済）, paidDate, termsSnapshotJsonの**自社関連項目**, t_bp_terms: effectiveFrom, closingDay, paymentMonthOffset, paymentDay, feeBearer, paymentMethod, **受領確認日時（portal登録値）** | parentPaymentId（他階層=他BPの情報）, bpCompanyId内部ID, workRecordId, costCenterId, remarks（社内）, createdBy, 全BPに共通でない社内評価・リスク情報 |

- 発注一覧はwork record × 月単位。BPが自社の階層行だけを見る（parent/childに他社が居る場合は自社行の金額のみ公開）。
- 受領確認: `received_confirmed_at`（新規列、V104）をCAS（status=未払かつNULL）で一度だけ設定。

#### 検収（作業実績の確定状況）
| 公開 | 非公開 |
|---|---|
| workMonth, actualHours, billingAmount（自社作業分）, status（入力中/提出済/差戻し/確定）, rejectComment | paymentAmount, organizationId, costCenterId, createdBy |

#### 請求書/作業報告書の提出（upload）
| 公開 | 非公開 |
|---|---|
| 提出物（archive scan通過後のみ登録）, 提出日時, 提出物のdownload（CLEAN後のみ） | 他社分の情報。scan未完了/異常はfail-closed（R4.4） |

#### 支払状況（t_bp_payment の参照）
| 公開 | 非公開 |
|---|---|
| 自社分の status（未払/支払済）, paidDate, 支払予定（t_bp_termsから導出） | 金額変更・状態変更APIは存在させない（R3.3）。他BPの金額・層構造 |

#### 口座変更申請
| 公開 | 非公開 |
|---|---|
| 申請時に入力した新口座情報（表示）, 申請状態（承認待ち/承認済/却下）, 却下理由 | **承認前はt_bp_bank_accountへ反映しない**（R3.4）。承認者の社内コメント |

### 3.3 空き要員（BP提出 → ingestion review）

| 公開 | 非公開 |
|---|---|
| 自社提出分: 氏名（表示名）, スキル/経歴概要（提出内容）, 状態（review待ち/有効/却下）, 却下理由 | 他社要員情報。review前のavailabilityが内部候補に出ない（内部側の有効化後だけ候補に含める） |

→ 既存 `t_bp_availability`（V45）の母集団と整合させ、review前の行を内部候補から除外する（R3.2）。

## 4. 認可とscope（query boundary）

- 全 `/api/portal/**` endpointは `PortalAuthorizationService` で `target → customer_id / bp_company_id` を
  **SQL条件として**解決し、`PortalLoginUser` の組織と一致する場合のみ行が返る。取得後checkにしない（R4.3）。
- 連番IDだけで認可しない。list/detail/download/count 全て同一の母集団（portal_org → customer_id/bp_company_id）。
- 内部API（/api/**）とportal API（/api/portal/**）は**別SecurityFilterChain**（T083）。portal userは内部URLへ403。
- portal userは `t_portal_user.status` が有効でないと認証されない。停止・失効・組織停止時はsession無効化。
- ファイルdownloadは `FileScopeValidationService` 相当のportal版を必ず通す（T083）。scan CLEAN以外は403（fail-closed）。

## 5. Threat model（最低限セット。T083〜T087でtest化）

| # | 脅威 | 対策 | 対応task |
|---|---|---|---|
| T-1 | IDOR: 顧客Aが顧客B/BPのID・URL・fileを取得 | 全endpoint×全methodで3組織matrix test。query boundary scope | F2/A1/A2 |
| T-2 | 招待token再利用・期限切れ・email不一致・組織不一致 | 4条件検証＋`UPDATE ... WHERE used_at IS NULL`のDB CAS（一回性） | F1 |
| T-3 | token平文漏洩（DB/log/mail） | SHA-256 hashのみ保存。URL log/mailerでmask | F1 |
| T-4 | portal userによる内部URL/内部API到達 | 別chain・別cookie・`anyRequest`分離・403 | F2 |
| T-5 | 公開DTO経由の内部情報漏洩（原価/粗利/営業memo/PII） | field allow-list DTO（クラス構造に含めない）+ field allowlist test | F2/A1/A2 |
| T-6 | file経由の漏洩・未知file | archive quarantine/scan。CLEAN以外は公開しない（fail-closed） | A1/A2 |
| T-7 | 二重検収・顧客portalと内部代行の同時操作 | order specのUNIQUE(contract_id, work_month)＋状態CASへ委譲。先着1件 | A1 |
| T-8 | 口座変更の未承認反映 | approval request。承認前はmaster不変 | A2 |
| T-9 | MFA回避・recovery code再使用 | 全user TOTP必須。recovery code 1回限り（hash） | F1 |
| T-10 | login/招待/download/upload/検収APIのbrute force | rate limit（login/招待/download/upload/検収） | F2 |
| T-11 | CSRF/session fixation | 内部と分離したcookie名/CSRF。logoutでsession破棄 | F2 |
| T-12 | open redirect（email linkのreturn URL） | return URLは**相対のみ**。外部URL拒否 | B1 |
| T-13 | 退職/無効化した担当者のportal残存 | contact `valid_to` 到来→portal user停止batch（R1.5） | B1 |
| T-14 | 規約改定後の未同意運用 | terms version管理。未同意は同意画面へ強制遷移 | B1 |
| T-15 | 監査欠落 | download/検収/提出/口座変更を外部user/組織/IP/時刻で監査（R4.2） | B1 |

## 6. 実装上の決定（後続taskへ引き継ぐ契約）

1. **V104**（F1）で以下のportal専用テーブルを作成: `m_portal_organization` / `t_portal_user` /
   `t_portal_invitation` / `t_portal_user_permission` / `t_portal_terms_consent`、
   および `t_bp_payment.received_confirmed_at`（BP受領確認、§3.2）。
   `t_portal_message/attachment`は初期版では作成しない（design.md §1。問い合わせは既存comment/メール運用）。
2. 問い合わせ（C-7）は既存メール/連絡先表示による運用とし、初期版で独立thread tableを作らない。
3. portal専用`m_system_config`キー: `portal.base-domain`（公開host）、`portal.terms.current-version`、
   `portal.invite-ttl-hours`（既定72、G3）、`portal.rate-limit.*`。
4. portal user権限（`t_portal_user_permission`）は初期版では組織種別（CUSTOMER/BP）を既定として、
   per-user追加権限は管理者が設定できる形にする（design.md F1のpermission_key）。
5. 内部customer/BP contactとの紐付け（R1.5）: 顧客は `t_customer_contact` のemail・valid_to、
   BPは `t_bp_contact` を監視対象とし、失効検知batchはB1で実装。
6. 電子署名（C-8）: CloudSign等の署名URLは既存の契約文書機能（HFP-02 CloudSign連携済み）が生成する
   external URLへ遷移させる。ポータルは署名を代行せず、遷移URLだけを表示する。
7. 非公開の決定は今後もこのmatrixを更新したうえで行う。matrixにない論点は推測実装せず、spec具体化の提案と共に停止する。

## 7. L0検証

- `git diff --check` exit 0。
- 全画面（C-1〜C-8、B-1〜B-8、A-1〜A-7）に公開/非公開の区別がある（§2、§3）。
- 公開文書種別がG8 allow-list＋R2.1（作業報告）と一致する（§1.2）。
