# G1〜G6正式決定（2026-07-26、G2開発gate改訂 2026-08-09、R19 decision delta候補 2026-08-11）

## 1. 決定原則

発注者はG1〜G6の推奨決定をCodexへ委任した。本書の決定により、各specの設計・基盤実装を開始できる。
ただし、AIは契約締結、法的助言、専門資格者の署名、外部サービスの本番利用承認を代行できないため、
それらはtask 0の開発blockerではなく、該当provider task、M task、または本番releaseの受入条件として残す。

## 2. G1 — Entra ID OIDCとMFA

- 内部ユーザーの第1IdPはMicrosoft Entra ID、protocolはOIDC Authorization Code flowとする。
- Entra利用者は全員MFA必須。管理者はConditional Accessでphishing-resistant authentication strength
  （FIDO2/passkeyを第1候補）を要求する。SAML2は初期版で実装しない。
- 外部主体は`(issuer, subject)`で一意に紐付け、emailだけの自動linkとIdP groupだけによる管理者付与を禁止する。
- JIT provisioningは招待済みemailまたは内部管理者承認を必須とする。
- アプリ側にはEntra障害・設定事故用として2つの個人非依存local break-glass管理者を保持する。local accountは
  TOTP、1回限りrecovery code、暗号化secret、利用時即時通知・監査、90日ごとのlogin演習を必須とし、通常利用を禁止する。
- 一般local loginはfeature flagで無効化する。service automationに人間userを使わない。

本決定はMicrosoftが2つ以上のemergency access account、phishing-resistant method、定期検証、通常policyからの
適切な除外を推奨する方針を、Entra側とアプリ側の双方へ適用したものである。

## 3. G2 — 法務・労務コンプライアンス統制

- 開発時の法令source of truthは国税庁、公正取引委員会、厚生労働省、デジタル庁の公式資料とし、
  URL、確認日、文書版をfield mappingへ保存する。
- 社内の責任者roleは「コンプライアンス責任者」とし、管理者がworkplace単位で指名する。法的適否をAIやruleが
  自動確定せず、append-only approval/review/status eventへactor、日時、対象hash、根拠資料を記録する。
- 「コンプライアンス責任者」はruntimeで管理者が指名・交代するroleであり、特定の自然人、氏名、user IDを
  開発時のspec、code、seedへ固定しない。指名には半開区間`[effective_from,effective_to)`を持たせ、承認event発生時にactor、role、日時、
  対象version/hash、根拠資料を監査snapshotとして保存する。
- mapping lifecycleは`DRAFT -> PROVISIONAL_REVIEWED -> ACTIVE -> SUPERSEDED`とする。task 0は公式資料に基づく
  provisional mapping、L0、独立Reviewで`PROVISIONAL_REVIEWED`へ到達すれば完了でき、後続の開発taskを開始できる。
  runtimeの社内責任者assignmentまたは実actor承認eventをtask 0の開発blockerにしない。
- `ACTIVE`化、法定帳票の本番交付および各specのM taskのPASSには、runtimeで指名されたworkplace責任者本人による
  対象mapping version/hash/review policy hashへの承認eventと、tenant画面で動的設定・freezeされた全requirement groupを
  満たす実在external reviewer Review/CLEAN evidenceを必須とする。reviewer typeの具体値をenum/CHECK/固定option/seedにしない。
  assignment、承認event、外部Reviewのいずれかが欠ける場合はfail-closedとし、`PROVISIONAL_REVIEWED`のまま
  本番交付しない。
- 税務取引文書は欠損金年度も考慮してdefault 10年保存とする。法的hold中は削除しない。
- 派遣元/派遣先管理台帳は派遣終了日を起算日として3年保存を法定baselineとし、別の税務文書categoryや
  legal holdへ該当する場合だけ延長する。個人情報を一律10年保持しない。
- 取適法は2026-01-01施行版、フリーランス法は取引条件の書面/電磁的方法による明示をbaselineとする。
- 法令・公式様式更新時は自動上書きせず、新しいmapping versionとしてReview後に有効化する。

### 3.1 R19-P1-01 decision delta候補

- 詳細正本候補は`dispatch-outsourcing-compliance-ledger/g2-gate-decision-delta-r19-p1-01.md`。
- mapping ACTIVEはtenant-level、assignment/approval/delivery authorizationはworkplace-levelである。ACTIVE化に使った
  workplace approvalは他workplaceへ流用しない。formal generateごとにprofile workplaceのcurrent gateを再評価する。
- mapping/source/policyはDRAFTだけ編集し、PROVISIONAL_REVIEWED以降freezeする。policyはgroup間AND、group内type OR、
  groupごとのminimum distinct reviewerで評価する。
- mapping/policy/gateの3 hash、event reducer、ACTIVE transaction、formal generate/preview、過去delivery download、
  `/compliance-gate` action permissionを同decision deltaで固定する。
- 本節は`PROPOSED_FOR_R10_REVIEW`である。R10が`ACCEPTED_FOR_IMPLEMENTATION`を明示するまでV102/DDL/code/testを変更しない。

## 4. G3 — 外部Portal境界

- 公開hostは設定可能な`portal.<base-domain>`とし、内部管理画面と別subdomain、別SecurityFilterChain、
  別session cookie name/domain、別CSP/Rate Limitを使用する。
- 初期版は招待制だけとし、一般公開signupとsocial loginを実装しない。
- 顧客組織とBP組織、portal user、内部`sys_user`を別table/identity境界とし、内部role/menu/APIを再利用しない。
- 招待tokenは72時間有効、1回限り、hash保存、指定email・組織・権限へ固定する。初回組織管理者は内部管理者、
  2人目以降は当該組織管理者の承認を必要とする。
- 全portal userへTOTP MFAを必須とし、recovery codeは1回限りhash保存する。password reset、MFA reset、
  組織停止時は全sessionを失効する。
- 利用規約とprivacy noticeはversion管理し、初回loginと重要改定時に再同意を要求する。
- 問い合わせ担当による無監査impersonationを禁止する。support accessは申請、期限、理由、監査付きとする。

## 5. G4 — freee会計連携

- 会計確定後の総勘定元帳・支払確定のsystem of recordはfreee会計、本システムはSES業務明細、承認前data、
  外部送信job、reconciliationのsystem of recordとする。
- freee Public APIのOAuth 2.0 Authorization Code flowを使用し、redirect URIはHTTPS、stateを必須とする。
- connectionはlegal entity × freee product × `company_id`単位とし、tokenを暗号化・rotationする。給与/人事と
  会計のscopeおよびconnectionを混同しない。
- 取引、支払、取引先、部門、税区分等、契約planで利用可能な公式APIだけを使う。利用不可機能はCSV
  import/exportへfallbackし、画面scrapingを禁止する。
- 外部送信はoutbox/jobでtransaction外に実行し、idempotency key、request ID、payload hash、external ID、
  retry/backoff、rate limit、取消/訂正、月次reconciliationを保持する。
- 実契約plan、対象company ID、production credential、勘定科目/税/部門mappingはT094で確認する。未入手でも
  official contract fixtureとWireMockでF1/F2を進められるが、B1/B2/B3およびMの本番受入前には実環境確認を必須とする。

## 6. G5 — JP PINT/Peppol Provider

- 自社でPeppol Access Pointの認定・運用を行わない。
- 初期Providerは、デジタル庁のCertified Service Provider一覧に掲載され、Peppol Access PointをAPI提供する
  ファーストアカウンティング株式会社とする。
- provider依存を`DigitalInvoiceProvider` adapterへ閉じ込め、CanonicalInvoice、JP PINT renderer/validator、
  event ledgerをprovider非依存とする。PDF/email deliveryは廃止しない。
- 実装開始時にJapan Peppol Authorityの最新版JP PINTと変更履歴を確認し、送信runへ使用versionを保存する。
  versionを無検証で自動upgradeしない。
- 契約、料金、SLA、sandbox、participant ID、webhook署名、再送・取消仕様はT102で確認する。契約前でもF1/F2は
  official fixture/mockで進められるが、B1/B2/Mはprovider sandboxの証跡なしにPASSとしない。
- 商用条件またはAPI要件を満たせない場合は発注者承認の上で、デジタル庁掲載CSPへadapterを差し替える。

## 7. G6 — 雇用勤怠のsystem of record

- 直接雇用する社員の雇用勤怠、休暇残数、月次締め、36協定計算のsystem of recordは本システムとする。
- 既存`t_work_record`/`t_work_record_daily`は顧客向け作業実績・請求工数の正であり、雇用勤怠へ流用しない。
  両者は差異比較だけを行う。
- BP要員、個人事業主、フリーランスは直接雇用でない限り雇用勤怠の対象外とする。
- freeeは給与/人事のdownstreamおよび照合先とし、freeeから雇用勤怠を上書きしない。利用可能APIがある場合は
  承認/締め済みdataを冪等送信し、利用できない場合はCSV出力と照合reportを使う。
- 手動修正は理由、申請/承認、version、監査を必須とする。客先工数差異から雇用勤怠を自動修正しない。
- 法定上限の標準calculatorは実装できるが、各法人の36協定期間、特別条項、就業規則、丸め、休暇付与ruleは
  configurationとし、HR/社労士確認済み設定がない法人を本番締め可能にしない。

## 8. 公式根拠

- Microsoft Entra emergency access:
  https://learn.microsoft.com/en-us/entra/identity/role-based-access-control/security-emergency-access
- Microsoft Entra Conditional Access:
  https://learn.microsoft.com/en-us/entra/identity/conditional-access/
- 国税庁「帳簿書類等の保存期間」:
  https://www.nta.go.jp/taxes/shiraberu/taxanswer/hojin/5930.htm
- 国税庁「電子帳簿保存法の概要」:
  https://www.nta.go.jp/law/joho-zeikaishaku/sonota/jirei/02.htm
- 公正取引委員会「取適法」:
  https://www.jftc.go.jp/toriteki/
- 公正取引委員会「フリーランス法」:
  https://www.jftc.go.jp/freelancelaw_2025/
- 厚生労働省「労働者派遣事業を適正に実施するために」:
  https://www.mhlw.go.jp/content/001374043.pdf
- freee API共通リファレンス:
  https://developer.freee.co.jp/reference/
- freee会計API:
  https://developer.freee.co.jp/reference/accounting/reference/
- デジタル庁「JP PINT」:
  https://www.digital.go.jp/policies/electronic_invoice
- デジタル庁「日本のPeppol Certified Service Provider一覧」:
  https://www.digital.go.jp/policies/electronic_invoice/list-japanese
- ファーストアカウンティング Peppol Access Point API:
  https://www.fastaccounting.jp/service/

