# Gate 0 開始準備調査報告書

> **履歴資料**: 本書は決定前のreadiness snapshotである。G1〜G6は2026-07-26に決定済みであり、
> 現在の正は`decision-log.md`と`gate-decisions-g1-g6.md`である。本書の「未決」記述を現在状態として使わない。

調査日: 2026-07-26

## 1. 調査範囲と結論

本報告書は、顧客視点プロダクト拡張の Gate 0 に限定した調査結果である。発注者のG0決定後、Java、HTML、JavaScript、CSS、SQL、設定ファイル、Flyway migrationは変更せず、G0の決定記録と仕様成果物だけを同期した。

現在の結論は次のとおり。

- G0は2026-07-26に発注者が「顧客ごとの独立DB」と決定した。G1〜G6は未決であり、該当する実装を開始できない。
- G7〜G10 は blocking ではない。調査時点では decision-log の推奨既定値を採用して先行設計へ進められるが、正式な状態更新は発注者の指示後に行う。
- 現行システムは単一 MySQL データベース、グローバル username、MyBatis-Plus の通常 interceptor のみであり、tenant context、tenant interceptor、tenant-aware な非HTTP経路は存在しない。
- `multi-company-tenant-isolation` はT001だけを完了した。T002/F1、DDL、V59作成、Flyway追加、V1変更、H2 schema変更は未着手であり、共有DB方式の正式再開まで延期する。
- V59は作成せず永久欠番として保持する。V60〜V77は実装前の計画値に過ぎず、現時点の最大番号はV58である（その後の実装でV61・V62は`organization-management-accounting`が使用済みであり、V63〜V77は後続specの予約値として順次繰り上げ済み。詳細は`README.md`の予約表を参照）。将来共有DBを再開する場合もV59を補完・再利用せず、その時点のFlyway最新番号`latest + 1`から再計画する。

## 2. G0〜G10 決定準備表

| ID | blocking | 工程だけで確定可能か | 現時点の判定 | 推奨既定値の扱い |
|---|---|---|---|---|
| G0 | yes | 発注者決定済み | 独立DBを現在の正式モードとし、共有DB全表tenant_id化を延期 | V59は作成せず永久欠番。将来は当時のlatest+1から再計画 |
| G1 | yes | 不可 | IdP契約、MFA運用、break-glass責任者が不明 | Entra ID OIDC + ローカルTOTPを候補として提示するだけ |
| G2 | yes | 不可 | 法務監修者、帳票、保存期間の承認資料がない | 専門家の承認を必須とする候補を提示するだけ |
| G3 | yes | 不可 | 公開ドメイン、利用規約、本人確認が未定 | 別サブドメイン・招待制を候補として提示するだけ |
| G4 | yes | 不可 | freee契約プラン、利用可能API、仕訳方針が未確認 | API + CSV fallbackを候補として提示するだけ |
| G5 | yes | 不可 | Peppol CSP契約が未定 | 認定事業者APIを候補として提示するだけ |
| G6 | yes | 不可 | 雇用勤怠の正となるソースが未定 | freeeまたは本システムの一方を固定する候補を提示するだけ |
| G7 | no | 業務責任者確認が望ましい | 現行コードには汎用承認閾値がない | 推奨既定を採用して設計開始可。正式決定は任意確認 |
| G8 | no | 業務公開範囲の確認が望ましい | 現行コードにはポータル公開経路がない | 推奨既定を採用して公開DTO設計開始可。正式決定は任意確認 |
| G9 | no | 業務会計方針の確認が望ましい | 要員経費の申請・承認機能は未実装 | 推奨既定を採用して設計開始可。正式決定は任意確認 |
| G10 | no | AI送信を開始しない限り不要 | AIは mock/rule が既定で、実送信許可は未設定 | mock/rule と送信禁止を採用して設計開始可。正式決定は任意確認 |

### G0 — 配備方式とtenant境界

- **blocking**: yes（決定済み）。
- **現在確定できるか**: 発注者決定により確定。現在の正式な配備方式は顧客ごとの独立DBであり、共有DB SaaSの全表tenant_id化は延期する。
- **証拠**: `src/main/resources/application.yml:43-48` は単一の datasource URL、`src/main/resources/application.yml:73-78` は単一DBへFlywayを適用する設定である。`src/main/java/com/ses/config/MyBatisPlusConfig.java:27-35` は pagination のみを登録し、tenant interceptor はない。`src/main/java/com/ses/config/CustomUserDetailsService.java:41-56` と `src/main/java/com/ses/mapper/SysUserMapper.java:26-27` は username だけでログインユーザーを検索する。
- **発注者への質問**: 回答済み。独立DBを現在の正式モードとし、共有DBの再開条件はSaaS販売方式、契約・法務・セキュリティ・移行・運用条件の正式承認と、発注者によるG0再開指示とする。
- **推奨案の採否**: 独立DB案を採用した。現行のURL、ログイン、ジョブ、ファイル、バックアップ運用を維持する。V59は作成せず永久欠番とし、将来のtenant実装は当時のFlyway最新番号`latest + 1`から再計画する。
- **その他の案と代償**: 共有DB SaaSは顧客追加・運用効率に有利だが、全業務表、全SQL、非HTTP経路、ファイル、キャッシュ、バックアップを一貫して分離する必要があり、V1/H2/Flyway/認証を同時に変更する大型リスクがある。顧客ごとDBを続ける案は分離が強い一方、DB数、migration運用、横断集計の運用費が増える。
- **影響するSpec**: 全17 spec。特に `multi-company-tenant-isolation`、`organization-management-accounting`、`enterprise-identity-security`、`external-customer-bp-portal`。
- **誤決定時の返工**: 共有DBを後から採用すると、既存全表の tenant_id 追加、複合UNIQUE/FK、LoginUser、全annotation SQL、scheduler、async、cache、file、export、notification、backupを再設計する。単独DB前提で先に全表tenant_id化すると、不要なDDLと全経路のscope修正が発生する。
- **決定後に同期したSpecファイル**: `decision-log.md`、`customer-product-expansion-2026/README.md`、`dependency-matrix.md`、`parallel-execution-plan.md`、`multi-company-tenant-isolation/requirements.md`、`design.md`、`tasks.md`、`tenant-inventory.md`。

### G1 — 第1 IdP と MFA

- **blocking**: yes。
- **現在確定できるか**: 不可。現在はローカルのフォームログインのみで、OIDC、SAML、MFAの依存・設定・callbackは確認できない。
- **証拠**: `src/main/java/com/ses/config/SecurityConfig.java:84-108` は内部パスのrole認可、`src/main/java/com/ses/config/SecurityConfig.java:128-151` はフォームログインとCookie CSRF、`src/main/java/com/ses/config/SecurityConfig.java:186-201` はdev/testのNoOpとprodのBCryptを設定している。`src/main/java/com/ses/config/CustomUserDetailsService.java:41-56` はDB username認証だけを実装する。
- **発注者への質問**: 第1IdPはMicrosoft Entra IDで確定するか。MFAを全ユーザーへ必須にするか、管理者だけ先行するか。break-glass用ローカルTOTPの保管者、復旧手順、監査責任者は誰か。既存ローカルアカウントを段階移行するか。
- **推奨案**: Microsoft Entra ID OIDCを第1IdP、ローカルTOTPをbreak-glass専用とする。既存のローカルログインをいきなり廃止せず、管理者MFAと停止IdP時の復旧を先に検証する。
- **その他の案と代償**: ローカル認証のみは契約・実装が軽いが、MFA、退職者停止、組織連携を自前運用する。Google Workspace等の別IdPは顧客環境に合う可能性があるが、OIDC claim、グループ同期、監査を別設計する。
- **影響するSpec**: `enterprise-identity-security`、`external-customer-bp-portal`、`engineer-self-service-portal-v2`、tenantの認証境界。
- **誤決定時の返工**: provider固有のsubject、claim、session、権限同期、MFA recoveryを作り直す。ポータル招待 token と既存usernameの関係も再設計になる。
- **決定後に同期するSpecファイル**: `decision-log.md`、`enterprise-identity-security/requirements.md`、`design.md`、`tasks.md`、`external-customer-bp-portal/design.md`、`parallel-execution-plan.md`。

### G2 — 法務監修者

- **blocking**: yes。
- **現在確定できるか**: 不可。コードに法定項目や警告は一部あるが、法的な正しさ、保存期間、帳票の承認者はコードから確定できない。
- **証拠**: `decision-log.md:10` は社労士・弁護士・顧問税理士による承認を推奨している。`src/main/resources/db/migration/V53__labor_compliance_check.sql:8-9` は契約の `direct_command_flag` を追加するが、法的結論そのものを保存するものではない。`src/main/resources/db/migration/V45__bp_availability.sql:28-32` はPII抽出結果を保持する。
- **発注者への質問**: 派遣・準委任・請負・フリーランス・取適法の各項目、帳票様式、保存期間、訂正・削除手順を承認する法務監修者と資料は何か。判定不能時を「要確認」として止める運用でよいか。
- **推奨案**: 社労士、弁護士、顧問税理士のいずれかを責任者として明示し、帳票項目と保存期間を文書承認する。システムは法的結論を自動断定せず、不足項目と要確認を提示する。
- **その他の案と代償**: 社内判断だけで進めると初期費用は低いが、帳票・保存期間・警告の誤りを本番後に修正する高リスクがある。複数専門家の承認は安全だが、決定期間と運用コストが増える。
- **影響するSpec**: `bp-company-master-procurement-compliance`、`dispatch-outsourcing-compliance-ledger`、`attendance-leave-overtime-compliance`、`legal-document-ledger-archive`、`approval-workflow-internal-control`。
- **誤決定時の返工**: DDLの法定列、帳票 renderer、保存期間 scheduler、監査イベント、公開項目を再変更する。既に蓄積した帳票を再生成できない可能性がある。
- **決定後に同期するSpecファイル**: `decision-log.md`、上記5 specの requirements/design/tasks、`shared-standards.md` の保存・PII方針、必要なら `parallel-execution-plan.md`。

### G3 — 外部ポータルの公開境界

- **blocking**: yes。
- **現在確定できるか**: 不可。現行には専用の `/api/portal/**` 経路、外部組織、招待、本人確認、利用規約の実装がない。
- **証拠**: `shared-standards.md:31-33` は外部ポータルAPIを内部APIと分ける標準を定めている。`src/main/java/com/ses/config/SecurityConfig.java:84-119` は内部画面と要員向け経路だけを認可し、portal用の別chainを持たない。`src/main/java/com/ses/controller/api/FileApiController.java:18-24` はファイルAPIを認証済み共通機能として扱っている。
- **発注者への質問**: 公開ドメインとDNS/TLS管理者は誰か。顧客とBPを別組織として招待するか。本人確認をメール確認、法人管理者承認、外部IdPのどれで行うか。利用規約、プライバシー通知、公開可能な文書種別を確定できるか。
- **推奨案**: 内部画面と別サブドメイン、招待制、顧客/BP別組織、専用DTO・専用security boundaryとする。
- **その他の案と代償**: 同一ドメイン・同一ログインは導入が簡単だが、内部roleと外部roleの混同およびURL誤公開のリスクが高い。顧客ごとの独自ドメインはブランド性があるが、証明書・DNS・サポート運用が増える。
- **影響するSpec**: `external-customer-bp-portal`、`enterprise-identity-security`、`legal-document-ledger-archive`、`multi-company-tenant-isolation`。
- **誤決定時の返工**: portalのURL、token hash、session cookie、DTO、file scope、通知リンク、利用規約を作り直す。内部APIを公開済みの場合は互換性と脆弱性対応が必要になる。
- **決定後に同期するSpecファイル**: `decision-log.md`、`external-customer-bp-portal/requirements.md`、`design.md`、`tasks.md`、`enterprise-identity-security/design.md`、`shared-standards.md`。

### G4 — freee契約、API、仕訳方針

- **blocking**: yes。
- **現在確定できるか**: 不可。freee連携のコードとOAuth scopeは存在するが、契約プランで使えるAPI、会計・人事の正、仕訳ルール、法人単位の接続方式は環境・契約情報が必要である。
- **証拠**: `src/main/java/com/ses/service/impl/FreeeIntegrationServiceImpl.java:51-64` はclient、redirect URI、API URL、暗号鍵を外部設定から読む。`src/main/java/com/ses/service/impl/FreeeIntegrationServiceImpl.java:92-99` は `read:hr employees:read payrolls:read` scope、`src/main/java/com/ses/service/impl/FreeeIntegrationServiceImpl.java:273-276` は会計 deals APIを使う。`src/main/resources/db/migration/V21__freee_payroll_integration.sql:1-11` は接続を単一の `t_freee_connection` に保存する。
- **発注者への質問**: freeeの製品・契約プラン、利用可能な給与/人事/会計API、対象法人数、仕訳の正となるシステム、CSV fallbackの許容範囲、API rate limitとsandbox有無は何か。
- **推奨案**: 契約で利用できるAPIだけを実装し、CSV fallbackを残す。会計確定の責任システムを先に一つに固定し、外部ID・冪等キー・相関IDを法人/tenant単位にする。
- **その他の案と代償**: API全面依存は自動化しやすいが、プラン変更・API停止に弱い。CSV全面運用は導入しやすいが、手作業・二重登録・監査負荷が増える。
- **影響するSpec**: `accounting-payment-integration`、`engineer-self-service-portal-v2`、`jp-pint-digital-invoice`、tenant/legal entity。
- **誤決定時の返工**: canonical accounting model、outbox、retry、同期ジョブ、外部ID制約を作り直す。単一接続前提の既存 `t_freee_connection` も法人単位へ再設計が必要になる。
- **決定後に同期するSpecファイル**: `decision-log.md`、`accounting-payment-integration/requirements.md`、`design.md`、`tasks.md`、`jp-pint-digital-invoice/design.md`、`organization-management-accounting/design.md`。

### G5 — Peppol Certified Service Provider

- **blocking**: yes。
- **現在確定できるか**: 不可。現行コードにPeppol、JP PINT、Access Point、CSPの実装や契約情報はない。
- **証拠**: `customer-product-expansion-2026/README.md:46-48` はJP PINTをV74として未実装の仕様済み機能に分類し、`decision-log.md:13` は認定事業者APIを推奨している。現在の外部HTTP共通設定は `src/main/java/com/ses/config/AppConfig.java:37-58` の汎用RestTemplateだけである。
- **発注者への質問**: 採用するCSP、送受信国・文書種別、sandbox、認証方式、送信主体となる法人、失敗時の再送・受信保管・訂正手順を確定できるか。
- **推奨案**: 自前Access Pointを構築せず、認定事業者APIを利用する。provider adapterとcanonical invoiceを分離し、受信・送信イベントを冪等化する。
- **その他の案と代償**: 自前Access Pointは制御性が高いが、認定、運用、証明書、接続試験、監視の負担が大きい。別CSPへの抽象化は将来性があるが、初期の共通モデル設計が増える。
- **影響するSpec**: `jp-pint-digital-invoice`、`accounting-payment-integration`、`legal-document-ledger-archive`、`organization-management-accounting`。
- **誤決定時の返工**: invoice canonical model、署名・送信・受信 event、保存証跡、retry、外部ID制約をCSP仕様に合わせて作り直す。
- **決定後に同期するSpecファイル**: `decision-log.md`、`jp-pint-digital-invoice/requirements.md`、`design.md`、`tasks.md`、`accounting-payment-integration/design.md`、`shared-standards.md`。

### G6 — 雇用勤怠の正

- **blocking**: yes。
- **現在確定できるか**: 不可。現行の工数は契約に紐づく作業報告であり、雇用勤怠の法定原簿とは確定できない。freee側には給与明細取得があるが、雇用勤怠の正として使う契約合意はない。
- **証拠**: `src/main/resources/db/migration/V5__create_work_record_billing.sql:1-15` は `t_work_record` を契約・月単位の請求作業実績として定義する。`src/main/resources/db/migration/V32__engineer_self_service.sql:23-35` は日次作業報告を追加する。`src/main/java/com/ses/service/impl/FreeeIntegrationServiceImpl.java:228-254` は給与明細を取得するが、出退勤・休暇・36協定の原簿ではない。
- **発注者への質問**: 自社社員の雇用勤怠の正をfreeeと本システムのどちらに固定するか。客先工数を雇用勤怠から分離するか。BP、要員、社員の対象範囲、修正・締め・給与連携の責任者は誰か。
- **推奨案**: freeeまたは本システムの一方を雇用勤怠の正に固定し、既存の客先工数・請求作業報告とは別モデルで保持する。
- **その他の案と代償**: 両方を正にすると二重入力と差分調整が常態化する。客先工数をそのまま雇用勤怠に使うと、法定労働時間・休暇・36協定の母集団を誤る。
- **影響するSpec**: `attendance-leave-overtime-compliance`、`engineer-self-service-portal-v2`、`staffing-capacity-planning`、`order-acceptance-workflow`、`accounting-payment-integration`。
- **誤決定時の返工**: 勤怠テーブル、締め処理、計算機、freee adapter、通知、帳票、既存 `t_work_record` との境界を再設計する。既存請求集計へ法定勤怠を混入させる回帰が起きる。
- **決定後に同期するSpecファイル**: `decision-log.md`、`attendance-leave-overtime-compliance/requirements.md`、`design.md`、`tasks.md`、`engineer-self-service-portal-v2/design.md`、`shared-standards.md`。

### G7 — 承認金額閾値と承認者

- **blocking**: no。
- **現在確定できるか**: 業務上の最終値は不明。現行コードはrole単位の認可であり、金額閾値を持つ汎用承認経路はない。
- **証拠**: `decision-log.md:15` は「組織上長→財務/管理者、閾値は設定画面」を推奨している。`src/main/java/com/ses/config/SecurityConfig.java:95-108` は管理者専用パスをroleで制御するが、金額による承認者選択はない。
- **発注者への質問**: 推奨既定値以外の閾値、代理承認、休日・不在時の上位承認、自己承認禁止に例外があるか。**推奨既定を採用する場合、現時点の追加回答は不要**。
- **推奨既定**: 組織上長から財務/管理者へ段階承認し、閾値は設定画面で管理する。実装時には申請者と承認者の職務分離、CAS、履歴を必須にする。
- **その他の案と代償**: 固定承認者は単純だが、組織変更に弱い。金額ごとの多段承認は統制が強いが、代理・SLA・差戻しが増える。
- **影響するSpec**: `approval-workflow-internal-control`、`organization-management-accounting`、`order-acceptance-workflow`、`accounting-payment-integration`。
- **誤決定時の返工**: approval route、権限、通知、組織snapshot、監査履歴を再作成する。
- **決定後に同期するSpecファイル**: 正式決定時は `decision-log.md`、`approval-workflow-internal-control/requirements.md`、`design.md`、`tasks.md`。推奨既定のままなら、実装前に該当designへ根拠を記録する。

### G8 — 顧客/BPポータルの公開文書

- **blocking**: no。
- **現在確定できるか**: 現行実装からは確定不可。ただし、推奨既定で設計を開始できる。
- **証拠**: `decision-log.md:16` は顧客=見積/注文請/契約/検収/請求、BP=発注/検収/BP請求/支払状況を推奨する。`shared-standards.md:32-33` はexport/downloadと同じscope、外部portal専用DTOを要求する。`src/main/java/com/ses/controller/api/FileApiController.java:42-58` は現行ファイルdownloadが内部認証済みユーザー向けであり、外部公開仕様ではない。
- **発注者への質問**: 推奨既定以外の文書を公開するか、閲覧と提出を分けるか、顧客/BPのどちらがどの状態遷移を実行できるか。**推奨既定を採用する場合、現時点の追加回答は不要**。
- **推奨既定**: decision-log記載の文書集合だけを専用portal DTOで公開し、fileはDB参照があるものだけを許可する。内部画面・内部API・外部portalの認可母集団を分離する。
- **その他の案と代償**: 文書を広く公開するとメール往復は減るが、誤公開・訂正・保存義務の範囲が広がる。文書を最小化すると安全だが、手作業の受渡しが残る。
- **影響するSpec**: `external-customer-bp-portal`、`legal-document-ledger-archive`、`approval-workflow-internal-control`。
- **誤決定時の返工**: portal DTO、menu、file scope、download token、通知、利用規約、テストfixtureを修正する。
- **決定後に同期するSpecファイル**: `decision-log.md`、`external-customer-bp-portal/requirements.md`、`design.md`、`tasks.md`、`legal-document-ledger-archive/design.md`。

### G9 — 要員経費の精算先

- **blocking**: no。
- **現在確定できるか**: 現行コードには要員経費の申請・承認・仕訳機能がないため確定不可。ただし、推奨既定で設計を開始できる。
- **証拠**: `decision-log.md:17` は本システムで申請・承認し、会計確定はfreeeを推奨する。`src/main/java/com/ses/service/impl/FreeeIntegrationServiceImpl.java:228-254` は給与明細取得、`src/main/resources/db/migration/V21__freee_payroll_integration.sql:1-11` は給与/従業員連携を定義するが、経費テーブルはない。
- **発注者への質問**: 推奨既定以外に、freee側で申請・承認する経費種別、立替精算、証憑保管、法人別勘定科目、給与控除を採用するか。**推奨既定を採用する場合、現時点の追加回答は不要**。
- **推奨既定**: 本システムで申請・承認・証憑scopeを管理し、freeeで会計確定する。送信は冪等キーと相関IDを持ち、失敗時に再実行する。
- **その他の案と代償**: freee一元化は会計整合が強いが、要員ポータルの操作性と内部承認差分が増える。本システム一元化はUXがよいが、会計連携と監査の責任が増える。
- **影響するSpec**: `engineer-self-service-portal-v2`、`accounting-payment-integration`、`legal-document-ledger-archive`。
- **誤決定時の返工**: expense state、証憑保管、approval、freee adapter、会計確定タイミングを再設計する。
- **決定後に同期するSpecファイル**: `decision-log.md`、`engineer-self-service-portal-v2/requirements.md`、`design.md`、`tasks.md`、`accounting-payment-integration/design.md`。

### G10 — AIプロバイダとデータ送信許可

- **blocking**: no。
- **現在確定できるか**: mock/ruleを既定に維持することは確認できる。実AIの利用許可、DPA、送信項目、モデル版は未確認。
- **証拠**: `src/main/resources/application.yml:173-180` は `ai.enabled: true` だが `ai.provider: mock` とし、実API設定をコメントアウトしている。`src/main/java/com/ses/service/ai/impl/MockResumeParseServiceImpl.java:17-23` はmock実装を条件登録する。`shared-standards.md:37-42` はPII masking、監査、未知ファイル拒否を要求する。
- **発注者への質問**: 実AIへ送信する場合のprovider、DPA、送信可能項目、保存・学習利用の禁止、モデル/プロンプト版、データ所在を確定するか。**mock/ruleと実送信禁止を採用する場合、現時点の追加回答は不要**。
- **推奨既定**: mock/ruleを維持し、実AI送信はPIIマスキングとDPA承認が終わるまで禁止する。推薦結果が業務状態を自動変更しない境界を維持する。
- **その他の案と代償**: 実AIを早期利用すると精度評価を進めやすいが、PII、越境、再現性、provider lock-inのリスクがある。完全ruleは安全だが、候補数・自然言語処理の柔軟性が下がる。
- **影響するSpec**: `ai-feedback-learning`、`enterprise-identity-security`、`legal-document-ledger-archive`、候補者・提案・staffingの成果母集団。
- **誤決定時の返工**: canonical input/output、masking、監査、評価データ、provider adapter、モデル版管理を再設計する。
- **決定後に同期するSpecファイル**: `decision-log.md`、`ai-feedback-learning/requirements.md`、`design.md`、`tasks.md`、`shared-standards.md`。

## 3. tenant範囲の読み取り専用inventory

### 3.1 テーブルとEntity

Flywayから確認できる業務・マスタ表は次の44表である。`shedlock` だけはEntity/Mapperがなく、ShedLockが直接利用する。

```text
sys_user, m_customer, t_engineer, t_engineer_career, m_skill_tag,
t_engineer_skill, t_project, t_project_skill, t_proposal, t_proposal_history,
t_contract, t_ai_log, m_email_template, m_system_config, m_menu, t_role_menu,
t_notification, t_notification_read, t_work_record, t_invoice, t_invoice_item,
t_bp_payment, t_sales_activity, t_audit_log, t_engineer_sales,
t_candidate, t_candidate_activity, m_contract_template, t_contract_document,
t_freee_connection, t_freee_employee_link, t_mail_delivery, t_invoice_payment,
t_quotation, t_engineer_account_link, t_work_record_daily,
t_contract_price_history, t_resume_ingestion, t_project_ingestion,
t_bp_availability, t_bp_availability_ingestion, t_bank_deposit,
t_engineer_followup, shedlock
```

根拠は `src/main/resources/db/migration/V1__create_tables.sql:40-419` と後続migrationの各 `CREATE TABLE` である。V1の冒頭コメントは「全14テーブル」と記載するが、同ファイルは実際には16表を作成している（`V1__create_tables.sql:5`、`40-419`）。これはDDL内容を変更する問題ではないが、文書上の不一致である。

Entityは `src/main/java/com/ses/entity` の `@TableName` で43表に対応し、`shedlock` を除いてmigration表と一致する。代表的な対応は `SysUser.java:24-25`、`Customer.java:21-22`、`Engineer.java:23-24`、`Contract.java:22-23`、`Notification.java:11-12`、`Invoice.java:16-17`、`BankDeposit.java:17-18`、`ContractDocument.java:3` である。Mapperは全Entityについて `BaseMapper<Entity>` を継承し、例えば `SysUserMapper.java:17`、`ContractMapper.java:21`、`NotificationMapper.java:14`、`WorkRecordMapper.java:13` に確認できる。

### 3.2 Mapperとannotation SQL

XML mapperはなく、`application.yml:115-122` はMyBatis-Plusの設定だけである。annotation SQLの全対象ファイルと主な用途は次のとおり。現在のSQLにはtenant条件はない。

| ファイル | 行 | 用途・tenant確認事項 |
|---|---:|---|
| `SysUserMapper.java` | 26, 33, 42-60, 65-78 | username認証、lock更新、削除済みユーザー、重複username。G0共有DBではusername検索が最重要境界 |
| `WorkRecordMapper.java` | 14-104 | FOR UPDATE、請求・支払集計、雇用形態参照。raw `SELECT`/`UPDATE` の全件分類が必要 |
| `ContractMapper.java` | 23-134 | 契約期間・採番・更新ドラフト・一覧JOIN・集計。sales/user/customerの横断scopeが必要 |
| `InvoiceMapper.java` | 16-91 | 請求採番、customer JOIN、一覧・集計。invoice/customerのtenant一致が必要 |
| `NotificationMapper.java` | 15-52 | 通知可視性、read INSERT、role/menu JOIN。通知と受信者のtenantを同時検証する必要 |
| `RoleMenuMapper.java` | 22-28 | role-menu JOIN、全menu取得。global menuかtenant overrideかをG0後に固定 |
| `EngineerSalesMapper.java` | 22-74 | 要員・担当営業JOIN、現任/履歴、一覧集計。要員と営業ユーザーの同一tenantが必要 |
| `ProposalMapper.java` | 17-60 | 要員・案件・顧客JOIN、FOR UPDATE、skill-sheet path一覧。file scopeと同時に検証 |
| `ProjectMapper.java` | 20-56 | 案件一覧と顧客名JOIN。customer scopeが必要 |
| `BpPaymentMapper.java` | 15-74 | BP支払一覧、layer集計、contract/work_record JOIN。tenantを全親子へ伝播 |
| `InvoiceItemMapper.java` | 14-31 | invoice/work_recordの一括検索。別tenant ID混入を拒否 |
| `EngineerSkillMapper.java` | 15-32 | engineer/skill JOIN。skillをglobal masterにするかtenant化するか決定が必要 |
| `ProjectSkillMapper.java` | 13-18 | project/skill JOIN。同上 |
| `EngineerMapper.java` | 18-26 | 作成日時、photo path、FOR UPDATE。photo fileのtenant scopeが必要 |
| `EngineerAccountLinkMapper.java` | 12, 15 | sys_userとengineerの紐付け。tenant一致が必要 |
| `FreeeConnectionMapper.java` | 10 | 最新接続のFOR UPDATE。現在は全体で1接続の設計 |
| `FreeeEmployeeLinkMapper.java` | 11-14 | hard deleteと削除済み衝突除去。外部IDをtenant/legal entity単位にする必要 |
| `QuotationMapper.java` | 13, 16 | 見積採番、FOR UPDATE。番号uniqueのtenant化が必要 |
| `SystemConfigMapper.java` | 14 | config keyのFOR UPDATE。global設定とtenant設定の分類が必要 |
| `BpAvailabilityIngestionMapper.java` | 19-20 | stored file参照集合。file pathとtenant scopeが必要 |
| `ProjectIngestionMapper.java` | 16 | stored file参照集合。同上 |
| `ResumeIngestionMapper.java` | 19-20 | stored file参照集合。PII保持・tenant削除範囲が必要 |

MyBatis-Plus wrapperによる主なカスタム query の入口は、`CustomerApiController.java:46-199`、`EngineerApiController.java:30-96`、`ContractApiController.java:41-86`、`ExportApiController.java:95-342`、`CsvApiController.java:72-236`、`NotificationGenerateService.java:90-260`、`DashboardServiceImpl.java:60-70`、`SalesPerformanceServiceImpl.java`、`InvoiceServiceImpl.java:194-625`、`RuleMatchingServiceImpl.java:65-212`、`FileScopeValidationService.java:43-87` である。`inSql`/`apply`/`last` を使う箇所（例: `EngineerApiController.java:68-83`、`CsvApiController.java:91-99`、`CsvApiController.java:118-121`）は、tenant interceptorだけに依存せずSQL単位のテストが必要である。

### 3.3 UNIQUE、索引、FK

現行の主な一意制約は次のとおり。共有DB採用時は原則としてtenant_idを先頭に含める必要がある。

- `sys_user.username` — `V1__create_tables.sql:40-53`。
- `m_skill_tag.skill_name` — `V1__create_tables.sql:144-151`。
- `t_engineer_skill(engineer_id, skill_id)` と `t_project_skill(project_id, skill_id)` — `V1__create_tables.sql:157-171`、`217-231`。
- `t_contract.contract_no` — `V1__create_tables.sql:299-301`。
- `m_menu.menu_key` と `t_role_menu(role, menu_id)` — `V1__create_tables.sql:396-418`。
- `t_notification.dedupe_key` と `t_notification_read(notification_id, user_id)` — `V4__create_notification.sql:1-20`。
- `t_work_record(contract_id, work_month)`、`t_invoice.invoice_no`、`t_invoice_item.work_record_id`、`t_bp_payment(work_record_id, layer_order)` — `V5__create_work_record_billing.sql:1-64`。BPの旧uniqueは `V10__fix_bp_payment_unique_key.sql:1-6` で変更され、V17はno-opである。
- `t_freee_employee_link.engineer_id`、`freee_employee_id` — `V21__freee_payroll_integration.sql:7-11`。
- 生成列による有効レコード制約 `t_bp_payment`、`t_contract` — `V18__add_active_relation_unique_keys.sql:17-47`。
- `t_engineer_sales` の現任担当/主担当、現役提案の要員×案件 — `V24__engineer_sales_active_unique_keys.sql:1-25`。
- `t_engineer_account_link.engineer_id`、`sys_user_id`、`t_work_record_daily(work_record_id, work_date)` — `V32__engineer_self_service.sql:13-35`。
- `t_contract_price_history(contract_id, apply_from_month)` — `V33__contract_price_history.sql:7-18`。
- `t_bank_deposit.freee_deposit_id` — `V52__payment_reconciliation.sql:10-24`。
- `t_quotation.quotation_no` — `V29__quotation.sql:7-24`。

主要FKは `V1__create_tables.sql:112-114`（engineer.created_by）、`135-137`（career→engineer）、`166-171`（engineer/skill）、`205-210`（project→customer/user）、`226-231`（project skill）、`260-268`（proposal）、`287-292`（proposal history）、`329-340`（contract）および `V4__create_notification.sql:18-20`、`V5__create_work_record_billing.sql:13-15,34-46,62-64`、`V6__create_sales_activity.sql:14-17`、`V14__engineer_sales_and_commission.sql:20-34`、`V16__create_candidate_tables.sql:25-43`、`V20__contract_document_esign.sql:1-2`、`V21__freee_payroll_integration.sql:10-11`、`V28__ar_management.sql:8-18`、`V32__engineer_self_service.sql:19-35`、`V33__contract_price_history.sql:17-18`、`V52__payment_reconciliation.sql:22-24`、`V54__engineer_followup.sql:17-19` にある。

次の列は参照先をコメントや命名で示すが、migration上のFKがないため、tenant対応時にservice検証または複合FKの対象として明示的に分類する必要がある。

- `t_quotation.customer_id/project_id/engineer_id/proposal_id/created_by` — `V29__quotation.sql:7-24`。
- `t_resume_ingestion.converted_engineer_id/candidate_id/created_by` — `V43__resume_ingestion.sql:4-25`。
- `t_project_ingestion.converted_project_id/created_by` — `V44__project_ingestion.sql:1-20`。
- `t_bp_availability.promoted_engineer_id/created_by`、`t_bp_availability_ingestion.converted_availability_id/created_by` — `V45__bp_availability.sql:4-41`。
- `t_mail_delivery.invoice_id` — `V26__create_mail_delivery.sql:1-14`、`V38__add_invoice_id_to_mail_delivery.sql:1-2`。
- `t_audit_log` の actor/対象ID、`t_notification.recipient_user_id`、各監査・通知・外部連携ID。

### 3.4 Scheduler、Async、Cache

- Schedulerは `SesManagerApplication.java:14-16` で有効化されている。契約単価同期 `ContractPriceSyncService.java:32-34`、レジュメPII清理 `ResumeRetentionCleanupServiceImpl.java:37-39`、更新エスカレーション `RenewalEscalationScheduler.java:20-22`、通知生成 `NotificationScheduler.java:14-17`、ファイル清理 `FileCleanupScheduler.java:20-23`、契約更新ドラフト `ContractRenewalScheduler.java:19-22` がある。
- ShedLockは `SchedulerLockConfig.java:24-35` と `V58__shedlock.sql:8-13` でDB全体に一つの名前を持つ。名前は `contractPriceSyncDaily` 等でtenantを含まないため、共有DBでは「1ジョブが全tenantをloop」するか、tenant suffix方式を決定する必要がある。
- Asyncは `AsyncConfig.java:20-37` の共通poolを使う。取込解析は `ResumeIngestionServiceImpl.java:94-95`、`ProjectIngestionServiceImpl.java:92-93`、`BpAvailabilityIngestionServiceImpl.java:83-84`、メールは `MailServiceImpl.java:102-104`、Webhookは `WebhookNotifier.java:44-45`。TaskDecoratorによるtenant/user/locale伝播はなく、context未解決時fail-closedもない。
- Cacheは `CacheConfig.java:31-75` のCaffeineで、DashboardとUtilizationForecastをキャッシュする。`DashboardServiceImpl.java:63-65`、`UtilizationForecastServiceImpl.java:59-63` が利用し、キーはdata scopeとuser IDを含むがtenant IDを含まない。共有DB採用時はtenantを必ずキーへ追加し、tenant切替・停止時のinvalidateを設計する。

### 3.5 ファイルパスとdownload scope

- 共通保存先は `application.yml:157-159` の `UPLOAD_BASE_PATH`、既定 `./uploads`。`FileStorageServiceImpl.java:33-68,75-124` はUUIDファイル名を同一base直下へ保存・読込する。
- DB参照によるfile scopeは `FileScopeValidationService.java:43-87` で、resume原本、engineer写真、proposal skill sheet、project ingestion原本、BP availability原本を判定する。ただし該当なしを許可するフォールスルーが `FileScopeValidationService.java:87` にある。
- 契約PDFは共通FileStorageを通らず、`ContractDocumentServiceImpl.java:30-31,66-84` の `uploads/contracts/{contractId}` に保存する。署名PDF・証明書も同一サービスの `safePath`（同ファイル:174-202）で扱う。tenant prefixがないため、共有DBでは契約IDだけで境界を作れない。
- file reference providerは engineer、proposal、resume ingestion、project ingestion、BP availability ingestion に分散する。新しい保存経路を加える場合、`FileReferenceProvider`、download scope、cleanup対象を同時に追加する必要がある。

### 3.6 Export、通知、バックアップ、復元

- Excel exportは `ExportApiController.java:95-257` の要員、契約、月次売上、`CsvApiController.java:72-182` の要員・案件CSV。キーセットbatchと行数上限はあるが、共有DBではtenant/data scopeを同一条件で適用する必要がある。契約export内の全契約取得は `ExportApiController.java:331-335` にあり、tenant漏洩テストの優先対象である。
- 通知APIは `NotificationApiController.java:18-20`。通知のDB dedupeは `V4__create_notification.sql:1-20`、可視性SQLは `NotificationMapper.java:15-52`。dedupe keyは現在tenantを含まず、通知生成のキーも `NotificationGenerateService.java:196-260` などでID・日付中心である。
- SMTPは `application.yml:102-113` と `MailServiceImpl.java:24-26,102-135`。Webhookは `SystemConfigApiController.java:24-56` と `WebhookNotifier.java:34-61` で全体設定として扱う。freee接続、CloudSign token、Webhook URL、メール宛先はtenant/legal entity境界と監査を別途設計する必要がある。
- 全体バックアップは `ops/backup/backup-full.sh:4-14` でMySQL全DBと `/app/uploads` を一体でrestic保存する。binlogは `archive-binlog.sh:3-5`、`snapshot-binlog.sh:3-6`、復元は `restore.sh:3-10`、運用手順は `ops/backup/README.md:3-5` である。tenant単位export/restore、tenant単位削除、tenant単位PITRの手順は存在しない。

## 4. 依存・採番・実行順の問題

1. **採番の現状**: 本報告書作成時点（2026-07-26）の現行migrationはV1〜V58のうち19、23、41、47を欠番として持ち、V59以降は未作成であった。V59は永久欠番とし、V60〜V77の計画値も実装前に当時のFlyway最新番号`latest + 1`から振り直す方針とした。V59の補完・再利用、過去migrationの編集・out-of-order適用は禁止する。（その後`organization-management-accounting`がV61・V62を実装済みで、V63〜V77は後続specの予約値として繰り上げ済み。現在の正は`README.md`の予約表。）
2. **実行順の文書表記**: 旧版READMEの一覧表示にあったapprovalとCRMの順序問題は、当時CRM → approvalへ修正した。`parallel-execution-plan.md` と `dependency-matrix.md` を含め、BP → CRM → approvalの順序で一致している。番号自体は実装前の計画値であり、実際の採番はV59永久欠番を除外した当時のlatest+1規則に従う（現在の具体的な予約番号はBP=V66、CRM=V67、approval=V68。`README.md`の予約表が正）。
3. **V1説明の不一致**: `V1__create_tables.sql:5` の「全14テーブル」と実際の16表が一致しない。DDLを今変更せず、Gate 0後にコメントまたは棚卸し資料だけを同期する。
4. **V1/H2/Flywayの三系統**: `application-test.yml:11-38` はV1/V2/V4/V22/V5〜V9と複数H2 schemaを直接replayし、`application-test.yml:46-49` でFlywayを無効化する。一方 `FlywayMigrationSmokeTest.java:15-46` は実MySQLの空DBへ全migrationを通す。共有DBのDDL変更時はこの三系統とentityを同一taskで同期する必要がある。
5. **Docker smokeの環境不足**: Docker CLIはあるがdaemonが起動しておらず、TestcontainersのMySQL smokeは現環境では実行できない。通常の `mvn test` がDockerなしでskipする前提は `FlywayMigrationSmokeTest.java:26-27` にある。Docker起動またはCI実行環境をT002/F1開始条件へ追加する。

## 5. 直ちに開始できる作業

- G1〜G6へ回答するための事業・契約・法務・外部サービス資料を収集する。
- `tenant-inventory.md` の各annotation SQL、raw wrapper、scheduler、async、cache、file、export、notificationへのテストID割当をレビューする。
- 独立DBモードの既存回帰と、将来共有DB用のA/B fixture、件数・金額reconciliation、file/export/notification漏洩の受入matrixを設計する。DDLやproduction codeは作らない。
- migration採番とWave順の文書不一致を、G0決定を変えずに最小修正する案を確定する。V59は補完・再利用しない。
- Dockerを起動できるCIまたは環境を用意し、MySQL migration smokeを実行可能にする。

## 6. まだblockingされている作業

- `multi-company-tenant-isolation` の現在の実装taskはない。共有DBを再承認した場合に限り、T002/F1相当のtenant実装を新たに再計画する。V59、V1最終形、H2、smokeを現在は開始しない。
- TenantContext、MyBatis tenant interceptor、LoginUser/host resolver、共有DBのusername制約。
- annotation SQL、集計、scheduler、async、cache、export、notification、file、backupのtenant適用。
- G1に依存するOIDC/MFA、G2に依存する法定帳票・保存期間、G3に依存する外部portal、G4/G5に依存する外部会計・JP PINT、G6に依存する雇用勤怠。
- Wave 0のorganization、identity、archive、productivity。`parallel-execution-plan.md:53-64,71-77` の依存順を満たすまで並行実装しない。

## 7. 推奨する次の開工指示

次の指示を推奨する。

> 「G1〜G6の未決事項を確定し、G7〜G10は採用する推奨既定値を明記する。G0は顧客ごとの独立DBとして維持し、tenant inventoryと採番・Wave順の文書整合を確認する。現在はtenant実装taskを開始しない。共有DB SaaS販売方式、契約・法務・セキュリティ・移行・運用条件を正式承認し、発注者がG0再開を明示した場合に限り、V59を永久欠番として除外し、当時のFlyway最新番号`latest + 1`からtenant実装を新規再計画する。」

## 8. 本調査で変更したファイル

今回のG0決定反映で変更・作成した成果物は次のとおりである。

- `.kiro/specs/customer-product-expansion-2026/decision-log.md`
- `.kiro/specs/customer-product-expansion-2026/README.md`
- `.kiro/specs/customer-product-expansion-2026/dependency-matrix.md`
- `.kiro/specs/customer-product-expansion-2026/parallel-execution-plan.md`
- `.kiro/specs/customer-product-expansion-2026/gate-0-readiness-report.md`
- `.kiro/specs/multi-company-tenant-isolation/requirements.md`
- `.kiro/specs/multi-company-tenant-isolation/design.md`
- `.kiro/specs/multi-company-tenant-isolation/tasks.md`
- `.kiro/specs/multi-company-tenant-isolation/tenant-inventory.md`

Java、HTML、JavaScript、CSS、SQL、設定ファイル、Flyway migrationは変更していない。作業開始前から存在したgit worktreeの変更は保持し、今回の文書変更と混在させていない。

## 9. 調査実行記録

- Java: 17.0.19。
- Bundled Maven: 3.9.6。
- MySQL: `localhost:3306` はTCP接続可能。サービス `MySQL84` は稼働中。
- Docker: CLI 29.6.1は存在するが、Docker Desktop Linux daemonへ接続できない。
- `MigrationScriptIntegrityTest`: 成功。重複versionと空migrationは検出されなかった。
- 通常テスト環境: H2 MySQL mode、Flyway無効、`application-test.yml` のschema replay方式。実MySQLのmigration smokeはDocker利用可能なCIで実行する必要がある。
