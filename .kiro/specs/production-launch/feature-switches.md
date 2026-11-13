# 機能スイッチ運用手順（NF-01 / NF-02 / NF-03 / NF-04 / NF-10、子スイッチ2件）

- 対象: 初回本番リリース（新規構築・全停止デプロイ・専用Flyway job）で段階公開するS-NF機能
- 位置づけ: **権限ではなく公開可否のスイッチ**。OFFの機能は管理者を含む全ロール・未認証利用者から見えなくなる
- 実装: `com.ses.config.feature`（`Feature` / `FeatureSwitchProperties` / `FeatureSwitches` / `FeatureSwitchFilter`）
- 対象外: NF-05・NF-06・NF-08（既存の本番向けハードゲートで制御）、NF-07（本番経路未接続）、NF-09

## 1. スイッチ一覧

| 機能 | 設定キー | 環境変数 | 既定値（application.yml / prod） | test profile |
|---|---|---|---|---|
| NF-01 ライフサイクル管理・退社ゲート | `app.features.nf01.enabled` | `FEATURE_NF01_ENABLED` | `false` | `true` |
| NF-02 カスタマーサクセス・サービスデスク（内部画面） | `app.features.nf02.enabled` | `FEATURE_NF02_ENABLED` | `false` | `true` |
| └ NF-02の子: 顧客ポータルの問い合わせ（起票・返信・CSAT・添付） | `app.features.nf02-customer-portal.enabled` | `FEATURE_NF02_CUSTOMER_PORTAL_ENABLED` | `false` | `true` |
| NF-03 資格・学習・スキルギャップ | `app.features.nf03.enabled` | `FEATURE_NF03_ENABLED` | `false` | `true` |
| └ NF-03の子: 資格番号（retention class `CERTIFICATION_PII`）の入力 | `app.features.nf03-certification-pii.enabled` | `FEATURE_NF03_CERTIFICATION_PII_ENABLED` | `false` | `true` |
| NF-04 要員PWAシェル（Service Worker・manifest） | `app.features.nf04.enabled` | `FEATURE_NF04_ENABLED` | `false` | `true` |
| NF-10 定期管理レポート | `app.features.nf10.enabled` | `FEATURE_NF10_ENABLED` | `false` | `true` |

- **子スイッチ**: 親機能の一部だけを後から公開するためのスイッチ。**実効値は「親がON かつ 子がON」**で、親がOFFなら子の設定値に関係なくOFFになる（子だけ `true` にしても何も公開されない）。子だけ `true` で親がOFFの設定は起動を止めず、起動ログに `[機能スイッチ] NF02_CUSTOMER_PORTAL は設定上ONですが、親機能 NF02 がOFFのため無効です` のWARNを出す。
- 子スイッチの設定キーは小文字・kebab-case（`nf02-customer-portal`）、環境変数は大文字・snake_case（`FEATURE_NF02_CUSTOMER_PORTAL_ENABLED`）。テンプレートで使う識別子は設定キーと同じ（`disabledFeatures.contains('nf02-customer-portal')`）。
- `application.yml` と `application-prod.yml` は同じプレースホルダ（`${FEATURE_NF0x_ENABLED:false}`、子は `${FEATURE_NF02_CUSTOMER_PORTAL_ENABLED:false}` 等）を持つ。環境変数を設定しない限り本番もOFF。
- 値は `true` / `false` で指定する。**真偽値として解釈できない値（例: `ture`）はOFFにはならず、起動時のbindで失敗してアプリが起動しない**（設定誤りを起動失敗で検出する）。環境変数そのものが無い場合はOFF。
- dev profileも既定はOFF。ローカル起動（`mvn spring-boot:run`）や各specの `tasks.md` にあるNF画面のDemo手順を行う場合は、先に対象の `FEATURE_NF0x_ENABLED=true` を設定して起動すること（`CLAUDE.md` には未記載）。
- test profile（`src/test/resources/application-test.yml`）は既存テストの意味を保つため全てON。OFF時の挙動は `com.ses.config.feature` 配下のテストで検証する。
- 値は起動時に1回だけ読む。**切替には再起動が必要**（実行中の切替APIは無い）。
- Spring標準の環境変数名（例: `APP_FEATURES_NF01_ENABLED`）でも上書きできてしまうが、運用では上表の `FEATURE_*_ENABLED` だけを使い、両方を同時に設定しないこと。

## 2. OFF時に止めるもの（適用範囲）

共通の応答仕様:

- 画面・API・静的入口はServlet filter（`FeatureSwitchFilter`、Spring Securityより前）で **404** にする。
  - `/api/**` は `CustomErrorController` の404と同じ `ApiResult` JSON（`{"code":404,"message":"お探しのページは存在しないか、移動された可能性があります。","data":null}`）。
  - 画面・静的ファイルは `sendError(404)` で統一エラーページ（`error.html`）を表示する。
  - 認証・CSRF・`MenuPermissionFilter` より前に止めるので、管理者でも未認証でも同じ応答になる（管理者も迂回できない）。
  - 遮断応答には `Cache-Control: no-store`（`Pragma`・`Expires` も同様）を付け、ON切替後に古い404が残らないようにする。Security chainを通らないAPI応答にも、Spring Security既定と同じ `X-Content-Type-Options: nosniff`・`X-Frame-Options: DENY`・`X-XSS-Protection: 0`（HTTPS時は `Strict-Transport-Security`）を付ける。
  - `%` エンコード・`;` 付きpath・連続スラッシュ等の表記ゆれ、静的ファイルの大文字小文字・末尾 `/` の違いでも照合する。
- **「存在しないURL」との見分けやすさ（正確な説明）**:
  - **認証済み利用者**: 存在しないURLの404（例: 既存API root配下の未定義path）と、status・JSON本文・主要なsecurity headerが同じになる（実Servlet containerのテスト `FeatureSwitchServletContainerIntegrationTest` で検証する）。なお、存在しない**API root**（例: `/api/xxx`）は既存の `MenuPermissionFilter` が403を返すため、比較対象は「既存root配下の未定義path」である。
  - **未認証利用者**: 通常のURLはSpring Securityにより302（画面はloginへ誘導）／401（API）になるが、OFFの機能のpathだけは404になる。**この差は残る**（OFFの機能pathであることは未認証でも推測できる。応答本文・データは何も出さない）。認証後に判定すれば差は消えるが、本アプリではHTTP Basic認証が `MenuPermissionFilter` より後段で処理されるため、認証後判定にすると遮断を迂回できる経路ができる。また静的入口（Service Worker等）は認証不要の公開ファイルのため、認証状態にかかわらず止める必要がある。以上から認証前に止める方式を選んでいる。
  - その他の残差: Security chainを通らないため、通常応答で付くことがあるCSRF cookie（`XSRF-TOKEN`）の発行等は行わない。
- サイドバーは `GlobalControllerAdvice` が `allowedMenus` から対象機能のmenu keyを除外する（管理者も同じ）。ログイン後の着地先（`/`）もOFFの機能のメニューを選ばない。
- `@Scheduled` 入口は各schedulerの先頭で `FeatureSwitches.allows(...)` を確認し、OFFなら何もせず戻る（ShedLockの注釈は変更しない。OFF中もlock行の取得・更新だけは行われる）。

| 機能 | 遮断する画面・API・静的入口 | 止めるscheduler | 隠すメニュー・埋込み表示 |
|---|---|---|---|
| NF-01 | `/lifecycle/**`、`/my/lifecycle/**`、`/api/lifecycle/**`、`/api/my/lifecycle/**` | `LifecycleSlaScheduler#runSlaCheck`（毎朝8時） | menu `lifecycle`・`myLifecycle`、要員詳細画面のライフサイクル欄、ログイン後の着地先 |
| NF-02 | `/service-desk/**`、`/customer-success/**`、`/api/service-desk/**`、`/api/customer-success/**`（親がOFFなら下の子の入口も遮断） | `ServiceSlaScheduler#runSlaMonitoring`（5分毎）、`CustomerHealthScheduler#runDailySnapshot`（日次）、`ServiceRequestAttachmentCompensationScheduler#retryDue`（5分毎） | menu `service-desk`・`customer-success`、契約更新カレンダーの顧客ヘルス値（＋子の埋込み表示） |
| NF-02の子 `nf02-customer-portal` | `/api/portal/customer/service-desk/**`（顧客ポータルの問い合わせAPI: 一覧・詳細・起票・返信・CSAT・添付upload/download） | なし（schedulerは親NF-02に従う） | 顧客ポータル（`/portal/customer`）の「問い合わせ」タブ・画面・起票モーダル・CSATモーダル・script。portal画面自体と検収・契約・請求・見積・注文請タブは止めない |
| NF-03 | `/certification-learning-skill-gap/**`、`/my/certification-learning-skill-gap/**`、`/api/certification-learning-gap/**`、`/api/my/certification-learning-gap/**` | `CertificationExpiryNotificationScheduler#dispatchToday`（日次） | menu `certification-learning-skill-gap`・`myCertificationLearningGap` |
| NF-03の子 `nf03-certification-pii` | **URLは遮断しない**。資格申請（`POST /api/my/certification-learning-gap/certifications`）・再申請（`POST .../certifications/{id}/resubmit`）で資格番号が空欄でない要求を `EngineerCertificationServiceImpl` が400（message key `certification.record.numberEntryDisabled`）で拒否する（DB・暗号化に触れる前） | なし | 本人画面（`/my/certification-learning-skill-gap`）の申請モーダルの資格番号入力欄、再申請ダイアログの資格番号入力（確認だけのダイアログになる） |
| NF-04 | `/service-worker.js`、`/manifest.webmanifest` のみ | なし（下記4.4参照） | manifestリンク、Service Worker登録（`common.js` が既存登録とshell cacheを解除） |
| NF-10 | `/management-reports/**`、`/api/management-reports/**` | `ManagementReportScheduler#dispatchDue`（毎分。claim・生成・配布を開始しない） | menu `management-report` |

手動実行・テスト用の入口（`processSlaCheck`、`monitorSlaClocks`、`processDailySnapshot`、`dispatch(asOf)` 等）はスイッチの対象外。これらを呼ぶ本番画面・APIは上表のfilterで止まる（本番コードからの呼出し元は無い）。

## 3. ON / OFF の手順

### 3.1 初回デプロイ

1. 全スイッチOFF（環境変数未設定）のまま、全停止デプロイと専用Flyway jobを実施する。機能スイッチはFlywayに影響しない（OFFでもNFのテーブル・seedは作成される）。
2. 起動ログで `[機能スイッチ] 起動時の状態: NF01=OFF, NF02=OFF, NF02_CUSTOMER_PORTAL=OFF, NF03=OFF, NF03_CERTIFICATION_PII=OFF, NF04=OFF, NF10=OFF` を確認する（子スイッチは実効値を表示する）。
3. 各NFの代表URL（例: `/api/lifecycle/cases`）が404になり、サイドバーに表示されないことを確認する。

### 3.2 1機能ずつONにする

1. 対象NFの業務Owner承認・前提（下記4章の注意事項）を確認する。
2. 対象の環境変数だけを `true` に設定する（例: `FEATURE_NF02_ENABLED=true`）。子スイッチをONにする場合は親の環境変数も `true` であることを確認する。複数instanceの場合は**全instanceで同じ値**にする。値の綴り誤りは起動失敗になるので、起動できない場合はまず値を確認する。
3. アプリを再起動する（in-memory sessionのため利用者は再ログインになる）。
4. 起動ログの `[機能スイッチ] 起動時の状態` で対象だけがONであることを確認する。
5. 対象NFの画面・APIが権限どおりに表示されること、他のOFFの機能が引き続き404であることを確認する。
6. schedulerを持つ機能は、次回実行時刻のログ（例: NF-10は毎分の配布処理ログ）とエラー有無を監視する。

### 3.3 問題発生時に1機能だけOFFにする

1. 対象の環境変数を `false` にする（または削除する）。他の機能の値は変えない。
2. アプリを再起動し、起動ログで対象だけがOFFになったことを確認する。
3. 対象NFのURLが404になり、サイドバーから消えたことを確認する。
4. 下記4章の「OFF中も動き続けるもの」を確認し、必要なら追加の運用対処を行う。
5. 原因修正後、3.2の手順で再度ONにする。

### 3.4 初回リリースの設定値（2026-11-13 決定 B-NF02-2 / B-NF03-1）

初回リリースでは、親機能をONにする場合でも次の2つの子スイッチは**設定しない（既定のOFFのまま）**。

| 環境変数 | 初回リリース | 根拠 | ONにする条件 |
|---|---|---|---|
| `FEATURE_NF02_CUSTOMER_PORTAL_ENABLED` | 未設定（OFF） | B-NF02-2: 初回は内部サービスデスクだけを公開し、顧客ポータルからの起票・返信・CSATは第二段階とする | 第二段階のOwner承認、DG-02-A/C（portal対象・公開commentの扱い）の署名、4.3の「ON前の確認」の完了 |
| `FEATURE_NF03_CERTIFICATION_PII_ENABLED` | 未設定（OFF） | B-NF03-1: 初回は非PII部分（資格の取得日・期限・証憑、学習計画、skill gap）だけを公開し、資格番号は本番で入力しない | DG-07（`CERTIFICATION_PII` の保持年数・破棄）の承認と、NF-07の本番破棄経路の接続 |

- 起動ログで `NF02=ON, NF02_CUSTOMER_PORTAL=OFF`、`NF03=ON, NF03_CERTIFICATION_PII=OFF` となることを確認する（NF-02/NF-03をONにした場合）。
- 確認URL（NF-02 ON時）: `/api/portal/customer/service-desk/requests` が404、`/api/service-desk/requests` は権限どおり、顧客ポータル `/portal/customer` に「問い合わせ」タブが出ない。
- 確認画面（NF-03 ON時）: 要員本人の `/my/certification-learning-skill-gap` の「資格を申請」に資格番号欄が出ない。API へ資格番号を付けて送ると400になる。

## 4. 注意事項

### 4.1 共通

- **再起動が必要**。切替は「環境変数変更＋再起動」で行う。rolling更新で旧値と新値のinstanceを混在させないこと（全停止で切替える）。
- 全scheduler一括停止は既存の `app.scheduling.enabled=false` で行う。機能スイッチは特定NFだけを止めるためのもの。
- 機能スイッチは権限ではない。ON後のアクセス制御は従来どおり `SecurityConfig`・`MenuPermissionFilter`・action権限で行う。
- 遮断はSpring Securityより前で行うため、監査ログ（`ApiAuditFilter`）には記録されない。調査時は `logging.level.com.ses.config.feature=DEBUG` で `[機能スイッチ] OFFの機能への要求を404で遮断しました` を出力できる。
- OFF中も、既に送信済みの通知に含まれるNF画面へのリンクは404になる。
- OFFでもprod起動時の必須secret検証は変わらない（例: `PWA_CONTEXT_SECRET`、資格番号の暗号鍵）。OFFを理由にsecret供給を省略できない。資格番号の子スイッチ（`nf03-certification-pii`）をOFFにしても、prodでは `certification.number-crypto.*` の鍵が無いと起動しない（`CertificationNumberKeyProviderImpl` のfail-fast。現状は変更していない）。鍵を不要にする設計案は `.kiro/specs/certification-learning-skill-gap/first-release-scope-confirmation.md` 2.4 を参照（Security承認待ち・未実装）。
- OFF→ONの補完は保証しない。OFF期間中に到来した期限・閾値をON後にさかのぼって処理するかどうかは、各機能の既存ロジック（実行日基準の判定等）に従う（4.2〜4.5参照）。

### 4.2 NF-01

- 承認画面（`/approval`・`/api/approval/**`）はNF-01の入口ではないため、ON期間中に起票されたライフサイクル例外承認（requestType `LIFECYCLE_EXCEPTION` / `lifecycle.exception` / `lifecycle.waive`）の未決分は、OFF中でも承認画面から決裁でき、決裁時にライフサイクルtaskへ反映される。承認SLAの督促通知（`ApprovalSlaScheduler`）もOFF中に送られ得る。OFFにする前に未決の承認依頼が無いことを確認するか、決裁を保留する運用にすること（新規構築でONにしたことが無ければ該当データは無い）。
- 資産返却（NF-09）の処理はNF-01のデータを参照するが、NF-09側の機能でありNF-01スイッチでは止めない。
- ライフサイクルSLA監視はON後の実行日を基準に判定する。

### 4.3 NF-02

- ON期間中に作成された問い合わせ添付は文書台帳に登録済みのため、OFF中も既存の文書API（顧客DataScopeを持つ利用者）から参照できる。権限は変わらない（新たな公開にはならない）。
- 添付補償の再試行（`RETRY` 台帳）はOFF中は止まり、ON復帰後の次回実行で再開する。台帳は削除されない。添付ファイル自体は文書台帳・file security metadataに登録済みのため、cleanupで削除されない。
- SLA違反監視はON復帰後の初回実行で、その時点の時刻を基準に判定する（OFF期間中の違反はON復帰後にまとめて検出される）。
- 顧客ヘルスの月次snapshotはOFF期間中に生成されない（その月のsnapshotが欠ける場合がある）。
- 契約更新カレンダーはOFF中、顧客ヘルスの値を埋め込まない（画面の「顧客ヘルス」欄は「-」表示になる）。
- NF-02/NF-03の所有者修復API（`/api/admin/nf02-nf03/ownership-repair`、管理者専用）はデータ修復のため常時有効とする。
- 顧客ポータルは問い合わせタブだけが消え、検収・契約・請求等の他タブは従来どおり使える（NF-02本体OFF・子スイッチOFFのどちらでも同じ）。
- 既存の請求の問い合わせメモ（`t_invoice.portal_inquiry`、請求タブ）はNF-02の機能ではないため、子スイッチOFFでも使える。

#### 4.3.1 顧客ポータルの子スイッチ（`nf02-customer-portal`）

- OFF中（初回リリース）は、顧客からの起票・返信・CSAT回答・添付upload/downloadが全て404になる。問い合わせは社内担当がメール・電話等で受け、内部サービスデスク（`/service-desk`）へ代理起票する運用になる。
- CSATはportalからしか回答できないため、OFF中はCSATが集まらない。顧客ヘルスのCSAT要因は「missing input」となり減点されない（`CustomerHealthServiceImpl`。欠損を普通点で埋めない）。
- **内部画面の返信の既定は「顧客ポータル公開返信」（`visibility=PORTAL_VISIBLE`）である**。OFF中は顧客から見えないが、データとしては公開扱いで保存される。また、初回応答時刻（first response）は内部担当が `PORTAL_VISIBLE` の返信を登録したときだけ記録される（`INTERNAL` メモでは記録されない）。初回応答SLAを計測するため、OFF中も「顧客へ回答した内容」は `PORTAL_VISIBLE` で記録する運用とし、顧客に見せない内容は必ず「社内限定メモ」を選ぶこと。
- **ON前の確認（第二段階）**: portalの一覧は顧客IDで絞り込むため、ONにした時点で、OFF期間中に社内で代理起票された当該顧客の問い合わせと、その `PORTAL_VISIBLE` 返信・添付が全て顧客から見えるようになる。ONにする前に、対象顧客の既存問い合わせの公開範囲（`PORTAL_VISIBLE` の返信内容・添付）をCS Ownerが棚卸しし、顧客へ見せない内容が公開扱いで残っていないことを確認すること。
- ONにするには `FEATURE_NF02_ENABLED=true` と `FEATURE_NF02_CUSTOMER_PORTAL_ENABLED=true` の両方が必要。portal側の権限（`service-desk.view` / `service-desk.create`）・招待・規約は既存portalの正本に従う。

### 4.4 NF-04

- NF-04のOFFで止まるのは **PWA shell（Service Worker登録とインストール用manifest）だけ** である。
  - OFF中は新しいService Workerを登録しない（`/service-worker.js`・`/manifest.webmanifest` は404、manifestリンクも出さない）。ON期間にインストール済みの端末は、次回画面表示時に `common.js` が本アプリの `/service-worker.js` 登録と `ses-pwa-shell-*` cacheを解除する（制御中のタブを全て閉じた後に完全に外れる）。
  - Service Workerが無いため、オフライン中に画面を開き直すことはできない（オフラインの汎用ページは表示されない）。
- **次はOFFでも止めない（常時有効）**:
  - 日次入力の保存経路: 要員の勤怠・作業報告・経費下書き・変更申請下書きは `/api/my/pwa/{attendance,timesheet,expenses,change-requests}`、`/api/my/session-context`、`/js/pwa-queue.js` を通常の保存経路として使っている。止めると要員の日次入力そのものが停止する。
  - ロック済み下書きの復元: 通信不安定・サーバー5xx・オフライン時に保存した入力は端末に残り、画面を移動・再読み込みすると「ロック中の下書き」になる。その唯一の復元経路が同期パネルの「下書きを復元」→ `/offline.html` → `/api/my/pwa/restore/{reauthenticate,confirm}`（パスワード再認証が必須で、権限は広がらない）であるため、OFFでも有効にしておく。止めると、OFF期間中（新規構築の既定状態を含む）に生じたロック済み下書きは送信も復元もできず、logout時に端末から削除されてしまう。
  - server側のidempotency ledger掃除（`PwaClientMutationCleanupScheduler`）: 常時有効な保存経路が書き込むため止めない（止めるとledgerが保持期限を超えて増え続ける）。
  - logout・session破棄時のPWA復元失効処理（`PwaRestoreSessionListener`）: 失効側の処理なので常時有効。

### 4.5 NF-10

- OFFで止まるのは画面・API・`dispatchDue`（scheduleのclaim・生成・配布の開始）である。OFF中はprocessing leaseにも触れない。
- OFFにする時点で既に通知outboxへ投入済みの配信通知は、汎用の通知outbox送信（`NotificationOutboxScheduler`）により送信され、配信状態も同期され得る（リンク先はOFF中404）。配信の即時停止が必要な場合は、OFF前に該当の配信を取消すこと。配信専用スイッチの分離は後続作業（A-NF10-6）で検討する。
- ON復帰後、OFF中に期限を迎えたscheduleは保留中の次回実行時刻（`next_run_at`）から既存のschedule計算に従って処理される。複数回分の実行が溜まった場合の扱いは既存ロジックに従い、本スイッチでは補完を保証しない。

### 4.6 NF-03

- 資格期限通知は実行日を基準に閾値を判定するため、OFF期間中に到来した閾値の通知はON後に送られない場合がある。

#### 4.6.1 資格番号の子スイッチ（`nf03-certification-pii`）

- OFF中（初回リリース）も、資格の申請・取得日・有効期限・証憑upload・HR確認（verify/reject）・訂正・取消・更新、学習計画、skill gapは従来どおり使える。止めるのは**資格番号の入力だけ**である。
- 資格番号の入力経路は本人の申請と再申請の2つだけ（管理側API・CSV取込・他画面に資格番号の入力経路は無い。入口の一覧は `.kiro/specs/certification-learning-skill-gap/first-release-scope-confirmation.md` 2.1）。OFF中にAPIへ資格番号を付けて送ると、DB・暗号化に触れる前に400で拒否する（黙って捨てない）。
- 新規構築でOFFのまま運用した期間は資格番号の暗号文が作られないため、一覧・詳細は「未登録」表示になる。
- 証憑（資格証の画像・PDF）は資格番号や氏名を含み得るが、`CERTIFICATION_EVIDENCE` 文書として既存の文書台帳・retention/legal hold・scanに従う（本スイッチの対象外）。Privacyの確認事項として残す。
- ONにするには `FEATURE_NF03_ENABLED=true` と `FEATURE_NF03_CERTIFICATION_PII_ENABLED=true` の両方が必要。OFFへ戻しても既に保存した暗号文は削除されない（表示はmaskのまま、`certification.pii.view` 権限者は従来どおり復号表示できる）。

## 5. 新しい入口を追加するとき

1. `Feature` の該当NFへ画面prefix・API prefix・静的path・menu keyを追加する。親機能の一部だけを後から公開する場合は、親を第1引数に取る子スイッチを `Feature` に追加し、その入口を親の遮断表から子へ移す（同じpathを親子の両方に登録しない）。`FeatureSwitchProperties` にfieldと `toggle(Feature)` のcaseを、3つのymlに設定キーを追加し、`FeatureSwitchRouteCoverageTest` の `CHILD_FEATURE_CONTROLLERS` に一致してよいcontrollerを登録する。
2. NF配下の新しい `@Scheduled` は入口で `FeatureSwitches.allows(featureSwitches, Feature.NFxx)` を確認し、`FeatureSwitchRouteCoverageTest` の `GATED_SCHEDULERS` へ登録する。止めない場合は理由付きで `UNGATED_SCHEDULERS` へ登録する。
3. 意図的にスイッチ対象外とするAPIは理由付きで `UNGATED_ROUTE_PREFIXES`、静的ファイルは `UNGATED_STATIC_PATHS` へ登録する。
4. `FeatureSwitchRouteCoverageTest` は次の場合に失敗する。
   - NF service packageへ依存するcontroller、またはNFのaction resourceへ解決されるAPI root・画面rootが遮断表に無い（登録漏れ）。
   - 遮断表に一致するcontroller入口がその機能に属していない、または意図しない静的ファイルが遮断表に一致する（誤遮断）。
5. 検出の限界: NF service packageを経由せずmapper・entityだけに依存する入口、`@EventListener`・`HttpSessionListener`・outboxのtype別処理、既存画面のJSから新たにNF APIを呼ぶ導線は自動検出しない。追加時はレビューで確認する。

## 6. 検証テスト

| テスト | 実行環境 | 内容 |
|---|---|---|
| `FeatureSwitchDefaultsTest` | Docker不要 | 既定OFF（application.yml / prod）、環境変数で指定した1機能（子は親と共に）だけON、子だけ指定・親だけ指定では子はOFF、不正な真偽値は起動失敗、test profileは全ON、子スイッチの命名 |
| `FeatureSwitchFilterTest` | Docker不要 | OFF時のAPI 404 JSON・画面/静的404、管理者・未認証とも迂回不可、表記ゆれ、遮断応答のheader、ON時の素通し、機能間の独立性（親OFFで子も遮断）、NF-02 ON・子OFFでportalの問い合わせAPIだけ404、資格番号の子はURLを遮断しない、NF-04の常時有効経路 |
| `FeatureSwitchMenuAndViewTest` | Docker不要 | サイドバー非表示（管理者・一般ロール）、ログイン後の着地先、テンプレート用OFF一覧（親OFFで子の識別子も含む）、契約更新カレンダーの顧客ヘルス抑止 |
| `FeatureSwitchSchedulerGateTest` | Docker不要 | 6 schedulerの `@Scheduled` 入口がOFFでno-op、ONで従来どおり実行 |
| `FeatureSwitchRouteCoverageTest` | Docker不要 | 登録漏れ・誤遮断の検出（controller・静的ファイル・scheduler・サイドバー導線）、prefixの重複・古い登録の検出、子スイッチが一致するcontrollerの固定とportal配下のNF-02入口が全て子スイッチに属すること、portal問い合わせタブ・資格番号入力欄の埋込み判定 |
| `EngineerCertificationNumberEntrySwitchTest` | Docker不要 | 資格番号の子スイッチOFF時に申請・再申請をDB・暗号化前に400で拒否、空欄は受付、ON時は従来どおり暗号化保存 |
| `FeatureSwitchServletContainerIntegrationTest` | Docker不要・実Tomcat起動（Windowsでは **PowerShell環境** で実行） | NF-01をproperty形式、NF-04を環境変数形式でOFFにした実context。未認証の画面404（login誘導されない・no-store）、管理者と未認証で同一の404 JSON、存在しないURLの404との同一性、ONの機能・中核APIはSpring Securityへ届くこと、PWA静的入口の遮断範囲、filter登録順 |
