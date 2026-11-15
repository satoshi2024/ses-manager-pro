# 機能スイッチ運用手順（NF-01 / NF-02 / NF-03 / NF-04 / NF-06 / NF-07 / NF-10、子スイッチ2件）

- 対象: 初回本番リリース（新規構築・全停止デプロイ・専用Flyway job）で段階公開するS-NF機能
- 位置づけ: **権限ではなく公開可否のスイッチ**。OFFの機能は管理者を含む全ロール・未認証利用者から見えなくなる
- 実装: `com.ses.config.feature`（`Feature` / `FeatureSwitchProperties` / `FeatureSwitches` / `FeatureSwitchFilter`）
- 対象外: NF-05・NF-08（既存の本番向けハードゲートで制御）、NF-09。NF-07は保持・保全（`NF07_RETENTION`）だけを登録し、DSAR（`privacy.dsar.enabled`）は入口を追加するA2aで登録する
- NF-06はスイッチ（公開可否）に加えて本番admission port（Q-5、`.kiro/specs/data-migration-import-center/a1a-ingress-operations.md` §10）でも制御する。apply系はスイッチと無関係に閉じている（同 §1）

## 1. スイッチ一覧

| 機能 | 設定キー | 環境変数 | 既定値（application.yml / prod） | test profile |
|---|---|---|---|---|
| NF-01 ライフサイクル管理・退社ゲート | `app.features.nf01.enabled` | `FEATURE_NF01_ENABLED` | `false` | `true` |
| NF-02 カスタマーサクセス・サービスデスク（内部画面） | `app.features.nf02.enabled` | `FEATURE_NF02_ENABLED` | `false` | `true` |
| └ NF-02の子: 顧客ポータルの問い合わせ（起票・返信・CSAT・添付） | `app.features.nf02-customer-portal.enabled` | `FEATURE_NF02_CUSTOMER_PORTAL_ENABLED` | `false` | `true` |
| NF-03 資格・学習・スキルギャップ | `app.features.nf03.enabled` | `FEATURE_NF03_ENABLED` | `false` | `true` |
| └ NF-03の子: 資格番号（retention class `CERTIFICATION_PII`）の入力 | `app.features.nf03-certification-pii.enabled` | `FEATURE_NF03_CERTIFICATION_PII_ENABLED` | `false` | `true` |
| NF-04 要員PWAシェル（Service Worker・manifest） | `app.features.nf04.enabled` | `FEATURE_NF04_ENABLED` | `false` | `true` |
| NF-06 データ移行・取込センター（API。画面はW3-12） | `app.features.nf06.enabled` | `FEATURE_NF06_ENABLED` | `false` | `true` |
| NF-07 保持・保全（policy説明。catalog・holdは後続batchで追加）。**ONの条件: 決定ID束縛（D-01・D-09）の完了と、privacy-admin の有効な管理者2名以上**（prodでは起動時に強制。決定の署名日前・失効の間は起動を止めずOFFとして扱う。4.7） | `privacy.retention.enabled` | `PRIVACY_RETENTION_ENABLED` | `false` | `true` |
| NF-10 定期管理レポート | `app.features.nf10.enabled` | `FEATURE_NF10_ENABLED` | `false` | `true` |

- **子スイッチ**: 親機能の一部だけを後から公開するためのスイッチ。**実効値は「親がON かつ 子がON」**で、親がOFFなら子の設定値に関係なくOFFになる（子だけ `true` にしても何も公開されない）。子だけ `true` で親がOFFの設定は起動を止めず、起動ログに `[機能スイッチ] NF02_CUSTOMER_PORTAL は設定上ONですが、親機能 NF02 がOFFのため無効です` のWARNを出す。
- 子スイッチの設定キーは小文字・kebab-case（`nf02-customer-portal`）、環境変数は大文字・snake_case（`FEATURE_NF02_CUSTOMER_PORTAL_ENABLED`）。テンプレートで使う識別子は設定キーと同じ（`disabledFeatures.contains('nf02-customer-portal')`）。
- **顧客ポータルの問い合わせの公開範囲**（ON/OFFとは別の設定）: `app.features.nf02-customer-portal.visible-since`（環境変数 `FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE`、offset付きISO-8601。例: `2027-04-01T00:00:00+09:00`）。**未設定（既定）ならportalから起票された問い合わせだけ**をportalへ出す。設定すると、その時刻以降に作成された問い合わせも起票経路にかかわらず出す。不正な値（offsetなし等）は起動失敗になる。test profileは既存テストのため `2000-01-01T00:00:00+09:00`。詳細は4.3.1。
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
| NF-02の子 `nf02-customer-portal` | `/api/portal/customer/service-desk/**`（顧客ポータルの問い合わせAPI: 一覧・詳細・起票・返信・CSAT・添付upload/download）。ON中も公開範囲（`visible-since`）外の問い合わせは同じ404 | なし（schedulerは親NF-02に従う） | 顧客ポータル（`/portal/customer`）の「問い合わせ」タブ・画面・起票モーダル・CSATモーダル・scriptの参照。portal画面自体と検収・契約・請求・見積・注文請タブは止めない（`/portal/js/portal-service-desk.js` 自体のURLは遮断しないが、dataを含まない） |
| NF-03 | `/certification-learning-skill-gap/**`、`/my/certification-learning-skill-gap/**`、`/api/certification-learning-gap/**`、`/api/my/certification-learning-gap/**` | `CertificationExpiryNotificationScheduler#dispatchToday`（日次） | menu `certification-learning-skill-gap`・`myCertificationLearningGap` |
| NF-03の子 `nf03-certification-pii` | **URLは遮断しない**。資格申請（`POST /api/my/certification-learning-gap/certifications`）・再申請（`POST .../certifications/{id}/resubmit`）で資格番号が空欄でない要求を `EngineerCertificationServiceImpl` が400（message key `certification.record.numberEntryDisabled`）で拒否する（書込み・lock・暗号化の前。本人scope確認の読取りと再申請のversion照合は先に行うため、version不一致は409が先に返る） | なし | 本人画面（`/my/certification-learning-skill-gap`）の申請モーダルの資格番号入力欄、再申請ダイアログの資格番号入力（確認だけのダイアログになる） |
| NF-04 | `/service-worker.js`、`/manifest.webmanifest` のみ | なし（下記4.4参照） | manifestリンク、Service Worker登録（`common.js` が既存登録とshell cacheを解除） |
| NF-06 | `/api/import-center/**`（upload・job詳細・検証・preview・error export・source/mapping管理） | なし（`ImportStageReconcileScheduler` は照合台帳に残った取込原本の回収のためOFFでも止めない） | なし（画面・menuはW3-12で追加する。追加時に画面prefixとmenu keyを本表と `Feature.NF06` へ登録する） |
| NF-07 保持・保全 | `/api/privacy/policies/**`（W3-14）。OFF中は filter と service 層の `PrivacyAccessGuard` が同じ本文の404にする。遮断表に無い `/api/privacy/<x>` には handler が無く guard も通らないため、privacy-admin 以外の管理者は403（`MenuPermissionFilter`）、privacy-admin は Spring の404になる（管理者以外のroleは `SecurityConfig` の403）。ONでも `privacy.*` の action 権限（privacy-admin）が別に要る | なし | menu `privacy-retention`（W3-14では sidebar 導線なし） |
| NF-10 | `/management-reports/**`、`/api/management-reports/**` | `ManagementReportScheduler#dispatchDue`（毎分。claim・生成・配布を開始しない） | menu `management-report` |

手動実行・テスト用の入口（`processSlaCheck`、`monitorSlaClocks`、`processDailySnapshot`、`dispatch(asOf)` 等）はスイッチの対象外。これらを呼ぶ本番画面・APIは上表のfilterで止まる（本番コードからの呼出し元は無い）。

## 3. ON / OFF の手順

### 3.1 初回デプロイ

1. 全スイッチOFF（環境変数未設定）のまま、全停止デプロイと専用Flyway jobを実施する。機能スイッチはFlywayに影響しない（OFFでもNFのテーブル・seedは作成される）。
2. 起動ログで `[機能スイッチ] 起動時の状態: NF01=OFF, NF02=OFF, NF02_CUSTOMER_PORTAL=OFF, NF03=OFF, NF03_CERTIFICATION_PII=OFF, NF04=OFF, NF07_RETENTION=OFF, NF10=OFF` を確認する（子スイッチは実効値を表示する）。
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

### 3.4 初回リリースの設定値（2026-11-13 決定 B-NF02-2 / B-NF03-1 / B-NF07-1）

初回リリースでは、親機能をONにする場合でも次の2つの子スイッチは**設定しない（既定のOFFのまま）**。NF-07の2つのスイッチも初回は**設定しない（OFF）**。

| 環境変数 | 初回リリース | 根拠（決定記録） | ONにする条件（NF-02/NF-03は**実装側の提案。未決定**。NF-07は計画 §5.1 の条件で、prodでは起動時に強制する） |
|---|---|---|---|
| `PRIVACY_RETENTION_ENABLED` | 未設定（OFF） | B-NF07-1: production bridge（W-1〜W-12）を全て接続してから公開する。Q-8: 署名前は値UNKNOWN・flag OFF・未知は拒否のままマージする | 決定ID束縛（D-01・D-09 の決定ID・署名日・失効日、承認tenant、承認capability `retention`、approval digest）の完了と、privacy-admin の有効な管理者2名以上（break-glass口座を除く）。満たさなければ `PrivacyProductionConfigurationValidator` が起動を止める。決定の署名日前・失効だけなら起動は止めず、その間（実行中に失効日を迎えた日以降を含む）NF07はOFFとして扱われる（4.7.2.1） |
| `PRIVACY_DSAR_ENABLED` | 未設定（OFF） | 同上。DSAR の入口と前提検査（D-06 の値・`privacy.dsar.*`・F2 provider）は A2a で追加する | A2a のマージまでは、prodでONにすると前提未実装（`dsar.prerequisitesNotImplemented`）として起動しない。A2a 以降は上記に加えて D-06 の決定ID束縛と計画 §5.1 の追加前提 |
| `FEATURE_NF02_CUSTOMER_PORTAL_ENABLED` | 未設定（OFF） | B-NF02-2: 初回は内部サービスデスクだけを公開し、顧客ポータルからの起票・返信・CSATは第二段階とする | 第二段階のOwner承認、DG-02-A/C（portal対象・公開commentの扱い）の署名、`visible-since` の値の決定（4.3.1） |
| `FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE` | 未設定（portal起票のみ） | 2026-11-13 決定: 第二段階でportalを開いても、初回リリース中に社内で登録した問い合わせは顧客へ出さない | 第二段階で過去分も出す必要がある場合だけ、CS Ownerが公開開始時刻を決めて設定する |
| `FEATURE_NF03_CERTIFICATION_PII_ENABLED` | 未設定（OFF） | B-NF03-1: 初回は非PII部分（資格の取得日・期限・証憑、学習計画、skill gap）だけを公開し、資格番号はDG-07確定まで本番で入力しない | DG-07（`CERTIFICATION_PII` の保持年数・破棄）の承認。NF-07の本番破棄経路の接続を条件に加えるかはPrivacy/Ownerが判断する |

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
- OFFでもprod起動時の必須secret検証は変わらない（例: `PWA_CONTEXT_SECRET`、資格番号の暗号鍵）。OFFを理由にsecret供給を省略できない。
  - **現状**: 資格番号の子スイッチ（`nf03-certification-pii`）をOFFにしても、prodでは `certification.number-crypto.*` の鍵が無いと起動しない（`CertificationNumberKeyProviderImpl` のfail-fast）。
  - **提案（未実装・Security承認待ち。現状の運用には影響しない）**: 子スイッチOFFの間は鍵なしでも起動させる設計案を `.kiro/specs/certification-learning-skill-gap/first-release-scope-confirmation.md` 2.4 に記録している。
- OFF→ONの補完は保証しない。OFF期間中に到来した期限・閾値をON後にさかのぼって処理するかどうかは、各機能の既存ロジック（実行日基準の判定等）に従う（4.2〜4.5参照）。

### 4.2 NF-01

- 承認画面（`/approval`・`/api/approval/**`）はNF-01の入口ではないため、ON期間中に起票されたライフサイクル例外承認（requestType `LIFECYCLE_EXCEPTION` / `lifecycle.exception` / `lifecycle.waive`）の未決分は、OFF中でも承認画面から決裁でき、決裁時にライフサイクルtaskへ反映される。承認SLAの督促通知（`ApprovalSlaScheduler`）もOFF中に送られ得る。OFFにする前に未決の承認依頼が無いことを確認するか、決裁を保留する運用にすること（新規構築でONにしたことが無ければ該当データは無い）。
- 資産返却（NF-09）の処理はNF-01のデータを参照するが、NF-09側の機能でありNF-01スイッチでは止めない。
- ライフサイクルSLA監視はON後の実行日を基準に判定する。

### 4.3 NF-02

- **ON前の必須確認（2026-11-14 DG-02-B3実装方針）**: 当年度を含む全社共通カレンダーを事前登録する。法人既定が無い場合のfallbackと週末出勤日・祝日を確認し、missing_calendar WARN（tenant・法人ID・問い合わせID・相関ID）が無いことを監視する。正式なDG-02署名はCS Ownerが行う。

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
- **初回応答時刻（first response）の記録条件（現行実装）**: 社内担当が状態を `IN_PROGRESS`（内部画面の「対応着手」「対応再開」）へ変更したとき、または `PORTAL_VISIBLE` の返信を登録したときの早い方で記録される（`ServiceRequestServiceImpl#changeStatus` / `#addComment`）。`INTERNAL` メモだけでは記録されない。したがって初回リリース中は、**受付後に「対応着手」を押せば初回応答SLAを計測できる**。初回応答SLA上の「初回応答」を「対応着手」とみなしてよいかはDG-02-C2（CS Ownerの判断事項）。
- **内部画面の返信の既定は「顧客ポータル公開返信」（`visibility=PORTAL_VISIBLE`）である**。初回リリース中は顧客から見えないが、データとしては公開扱いで保存される。**初回リリース中は、顧客への回答内容（メール・電話で伝えた内容の記録）も「社内限定メモ（`INTERNAL`）」で記録することを推奨する**（第二段階の公開対象を増やさない）。
- **第二段階でONにしたときに顧客へ出る範囲（2026-11-13 決定により実装済み）**: portalの一覧・詳細・返信・CSAT・添付upload/downloadは、次のいずれかを満たす問い合わせだけを扱う（`PortalServiceRequestVisibilityPolicy`。範囲外は他社の問い合わせと同じ404）。
  1. portalから起票された問い合わせ（`channel='PORTAL'` かつ `portal_user_id` あり。社内起票でchannelに「PORTAL」を選んでも該当しない）
  2. 公開開始時刻 `FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE` 以降に作成された問い合わせ（未設定なら該当なし）
  - 既定（未設定）では、**初回リリース中に社内で登録した問い合わせとその返信・添付は、ON後も顧客へ出ない**。件名・本文は返信と異なり公開範囲を個別に指定できないため、この時刻による区切りが件名・本文を守る唯一の手段である。
  - 公開開始時刻を設定すると、その時刻以降に社内で登録した問い合わせは件名・本文・`PORTAL_VISIBLE` の返信・公開添付が顧客に見える（顧客portal利用者は招待時に `service-desk.view` を自動付与される）。設定する場合は、その時刻以降に社内で登録する問い合わせの件名・本文に顧客へ見せない内容を書かない運用とすること。
  - 内部サービスデスク（`/service-desk`、`/api/service-desk/**`）の一覧・詳細・返信・状態変更・添付は、この範囲の影響を受けない。
- **ON前の確認（第二段階）**: (1) `visible-since` を設定するかと、その値をCS Ownerが決める。(2) 設定する場合は、その時刻以降に作成された社内登録分の件名・本文・`PORTAL_VISIBLE` 返信・公開添付を棚卸しする。(3) portalの問い合わせ詳細画面への画面リンク（`portal-service-desk.js` の「詳細・返信」が指す `/portal/customer/service-desk/requests/{id}`）には対応するpage controllerが無く404になる既存の不具合があるため、第二段階のON前に修正する。
- ONにするには `FEATURE_NF02_ENABLED=true` と `FEATURE_NF02_CUSTOMER_PORTAL_ENABLED=true` の両方が必要。portal側の権限（`service-desk.view` / `service-desk.create`）・招待・規約は既存portalの正本に従う。

### 4.4 NF-04

- NF-04のOFFで止まるのは **PWA shell（Service Worker登録とインストール用manifest）だけ** である。
  - OFF中は新しいService Workerを登録しない（`/service-worker.js`・`/manifest.webmanifest` は404、manifestリンクも出さない）。ON期間にインストール済みの端末は、次回画面表示時に `common.js` が本アプリの `/service-worker.js` 登録と `ses-pwa-shell-*` cacheを解除する（制御中のタブを全て閉じた後に完全に外れる）。
  - Service Workerが無いため、オフライン中に画面を開き直すことはできない（オフラインの汎用ページは表示されない）。
- **次はOFFでも止めない（常時有効）**:
  - 日次入力の保存経路: 要員の勤怠・作業報告・経費下書き・変更申請下書きは `/api/my/pwa/{attendance,timesheet,expenses,change-requests}`、`/api/my/session-context`、`/js/pwa-queue.js` を通常の保存経路として使っている。止めると要員の日次入力そのものが停止する。
  - ロック済み下書きの復元: 通信不安定・サーバー5xx・オフライン時に保存した入力は端末に残り、画面を移動・再読み込みすると「ロック中の下書き」になる。その唯一の復元経路が同期パネルの「下書きを復元」→ `/offline.html` → `/api/my/pwa/restore/{reauthenticate,confirm}`（パスワード再認証が必須で、権限は広がらない）であるため、OFFでも有効にしておく。止めると、OFF期間中（新規構築の既定状態を含む）に生じたロック済み下書きは送信も復元もできず、logout時に端末から削除されてしまう。
    - ただし復元できるのは**ローカルパスワードでloginしたsessionだけ**である。OIDC（本番の要員の通常login）のsessionでは、復元APIがパスワード照合・失敗回数加算より前に拒否し、画面は「復元できません」と表示して入口を隠す（NF04 P1-N4-1 方案A、2026-11-14。`mobile-pwa-self-service/first-release-user-and-operations-notes.md` 1.4・2.4）。この扱いもスイッチに関係しない。
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
- 資格番号の入力経路は本人の申請と再申請の2つだけ（管理側API・CSV取込・他画面に資格番号の入力経路は無い。入口の一覧は `.kiro/specs/certification-learning-skill-gap/first-release-scope-confirmation.md` 2.1）。OFF中にAPIへ資格番号を付けて送ると、書込み・lock・暗号化の前に400で拒否する（黙って捨てない）。本人scope確認の読取りと再申請のversion照合は先に行うため、version不一致の再申請は409が先に返る。message key `certification.record.numberEntryDisabled` はbundle未登録のため、登録されるまでは400の汎用文言「入力内容を確認してください。」が表示される（正常な画面は資格番号を送らないため、直接API呼出しか、子スイッチON時に開いた古い画面だけが該当する）。
- 新規構築でOFFのまま運用した期間は資格番号の暗号文が作られないため、一覧・詳細は「未登録」表示になる。
- 証憑（資格証の画像・PDF）は資格番号や氏名を含み得るが、`CERTIFICATION_EVIDENCE` 文書として既存の文書台帳・retention/legal hold・scanに従う（本スイッチの対象外）。Privacyの確認事項として残す。
- ONにするには `FEATURE_NF03_ENABLED=true` と `FEATURE_NF03_CERTIFICATION_PII_ENABLED=true` の両方が必要。OFFへ戻しても既に保存した暗号文は削除されない（表示はmaskのまま、`certification.pii.view` 権限者は従来どおり復号表示できる）。

### 4.7 NF-07（W3-14b: 決定ID束縛とONの起動検査）

計画: `.kiro/specs/privacy-retention-dsar/production-bridge-phased-plan.md` §3.1.1・§5.1・§5.2・§7。初回リリースはOFF（3.4）。

#### 4.7.1 OFFのときの応答（正確な説明）

- 登録済みの入口（W3-14 は `/api/privacy/policies/**`）は `FeatureSwitchFilter` と service 層の `PrivacyAccessGuard` が同じ本文の404にし、policy source を読まない。
- 遮断表に無い `/api/privacy/<x>` には handler が無く `PrivacyAccessGuard` も通らない。応答は、管理者以外のroleが403（`SecurityConfig`）、privacy-admin 以外の管理者が403（`MenuPermissionFilter` の action 層）、privacy-admin が Spring の404である（「guard が404にする」という以前の説明は誤り）。入口を追加する batch は必ず `Feature.NF07_RETENTION`（DSAR は A2a の `NF07_DSAR`）へ prefix を登録し、controller から guard を通す。
- flag に関係なく、prodの起動時には `ProductionSecurityConfigurationValidator` が「`'*'` を持つ group（privacy-admin 以外）に `privacy.*` の拒否行がある」ことを常に検査する（deploy-runbook.md）。

#### 4.7.2 ONの条件（prodでは起動時に強制）

`PRIVACY_RETENTION_ENABLED` か `PRIVACY_DSAR_ENABLED` を1つでも `true` にしたprodの起動では、`PrivacyProductionConfigurationValidator` が次を全て検査し、1つでも欠ければ起動しない（Tomcatのportを開く前）。全てOFFなら何も検査せず、起動も止めない。決定の署名日前・失効（時間の経過だけで生じる不足）は起動を止めない（4.7.2.1）。

| # | 条件（欠ければ起動しない） | 欠けたときの違反コード（例） |
|---|---|---|
| 1 | 決定ID束縛 `privacy.approval.*`（`PrivacyDecisionBinding`）に構造的な不足が無い: 承認tenantが設定tenant（`OIDC_TENANT_ID`、未設定なら `default`）と完全一致、ONのcapabilityが全て承認capabilityに含まれる、必要な決定（retention は D-01・D-09、dsar は D-01・D-06・D-09）のID・署名日・失効日が NF08 と同じ規則（形式・placeholder・ID重複・署名日より後の失効日・最長366日）を満たす、approval digest が一致 | `decisionBinding.tenantId.missing` / `decisionBinding.tenantId.mismatch` / `decisionBinding.capabilities.retention.notApproved` / `decisionBinding.d01.decisionId.placeholder` / `decisionBinding.d09.expiresOn.tooLong` / `decisionBinding.decisionIds.notDistinct` / `decisionBinding.approvalDigest.mismatch` |
| 2 | `privacy-admin` group が有効・未削除で `privacy.*` を許可し、`'*'`・`privacy.` 接頭辞の拒否行を持たない | `privacyAdminGroup.notGrantingPrivacy` |
| 3 | `privacy-admin` の有効な管理者が2名以上。「有効」= 所属行が未削除、設定tenantの管理者roleで `status=1`・未削除、**OIDCでログインできる**（設定tenant・`OIDC_ISSUER_URI` の有効なOIDC providerに APPROVED の外部identity link を持つ。`ProductionSecurityConfigurationValidator` のOIDC管理者の数え方と同じ）、`'*'`・`privacy.` 接頭辞の拒否行を持つ有効なgroupに所属していない、break-glass username（`BREAK_GLASS_USERNAMES`。大文字小文字を区別しない）ではない。**アカウントロック中の管理者も数える**（下記） | `privacyAdmins.insufficient[current=1;required=2]` / `privacyAdmins.oidcProviderUnavailable` |
| 4 | `privacy-admin` 以外の有効なgroupが `privacy.` 接頭辞の許可行（`privacy.*`・`privacy.<x>`）を持たない（W3-14 review P3-2。`'*'` だけの検査では privacy-admin を複製したgroupを見落とすため） | `privacyAllow.outsidePrivacyAdmin[<group key>;…]` |
| 5 | `PRIVACY_DSAR_ENABLED` は A2a のマージまでONにできない（入口と §5.1 の追加前提の検査が未実装） | `dsar.prerequisitesNotImplemented` |

- 例外messageには固定の違反コード・flag名・group key・人数だけを出し、決定ID・digest・username・例外本文は出さない。照会に失敗した場合は `*.unverifiable` として起動を止める（fail-closed）。
- **アカウントロックは起動時の人数に影響しない**（W3-14b review P2-1）。ロックは未認証者でもlogin失敗5回で30分起こせる一時的な状態なので、起動条件にすると外部からアプリ全体の再起動を止められてしまう。ロック中の管理者は、実行時に privacy の各要求で拒否される（`SecurityContextCallerContextProvider` が要求ごとに `locked_until` を確認する）。
- privacy-admin が2名を下回った状態（無効化・退職・role変更・OIDC link の解除）でも動作中のprocessは止めないが、次回の起動は失敗する。所属者を無効化する前に後任を割り当てる。**privacy-admin は3名（deploy-runbook.md §5.0 のOIDC管理者 C〜E 全員）を推奨する**。2名だと1名の退職・無効化で次回の起動が止まり、ロック中の1名しか実際に操作できない時間も生じる。
- `*.unverifiable` で止まった場合は、DB接続とschemaを先に確認する（この検査は `ProductionSchemaVersionGuard` より先に動くことがあるため、schemaが artifact と一致しない場合にもこのコードになる）。

##### 4.7.2.1 署名日前・失効（時間要因）の扱い（NF-06・NF-08と同じ。2026-11-14 決定）

- 上表の構造的な不足が無く、**いずれかの決定の署名日前（`decisionBinding.dNN.signedOn.future`）・失効日以降（`decisionBinding.dNN.expiresOn.expired`）だけ**が理由なら、起動は止めない。起動時にWARN `NF07_PRIVACY_STOPPED_AT_STARTUP: NF07の決定ID束縛が有効期間外のため、NF07をOFFとして扱って起動します…` を出し、NF07 を **OFFとして扱う**。
- 「OFFとして扱う」の範囲: `PrivacyDecisionWindowMonitor` が機能スイッチの実行時条件（`FeatureRuntimeCondition`）として `FeatureSwitches#isEnabled(NF07_RETENTION)` を false にする。したがって `FeatureSwitchFilter` の404（OFFと同じ本文・全role同じ応答）、`PrivacyAccessGuard` の404、policy source を読まないこと、menu `privacy-retention` を隠すこと、起動ログの `[機能スイッチ] 起動時の状態` が `NF07_RETENTION=OFF` になること（加えて `… は設定上ONですが、実行時条件…を満たさないため無効です` のWARN）が、全てOFFと同じになる。
- **実行中に失効日を迎えた場合**: 判定は入口・metric取得のたびに JST 業務日で行う（同じ日の結果は再利用）。失効日（`expires-on`）の **JST 0:00 から**入口はOFFと同じ404になり、その日の最初の判定でWARN `NF07_PRIVACY_DECISION_STATE: NF07の決定ID束縛の状態=EXPIRED …` を1回出す。署名日前で起動した場合は、署名日の JST 0:00 から自動で開く。process の再起動は不要だが、**再承認の値は再起動しないと反映されない**（設定は起動時に1回だけ読む）。
- 監視: metric `ses_privacy_decision_binding_state{state="VALID|EXPIRING|EXPIRED|NOT_YET_VALID|INCOMPLETE|DISABLED"}`（現在の状態だけ1）と `ses_privacy_decision_binding_days_remaining`（最も早い失効日までのJST暦日数）。失効の `privacy.approval.expiry-warning-days`（`PRIVACY_APPROVAL_EXPIRY_WARNING_DAYS`、既定30日、1〜366）日前から `EXPIRING` としてWARNを日ごとに1回出す（公開は続く）。`EXPIRING`・`EXPIRED`・`NOT_YET_VALID`・`INCOMPLETE` をアラート対象にする。tag に決定ID・tenant・PII は載せない。
- 構造的な不足と同時なら起動を止める。失効日だけを書き換えた設定は digest 不一致（構造的な不足）になるため、「時間要因だけ」とはみなされない。

#### 4.7.3 決定ID束縛の設定項目

値は署名済み決定書の転記で、秘密値を置かない。**AI・実装者は値を設定しない**（署名後に人が設定する）。既定は全て未設定。

| 設定キー | 環境変数 | 内容 |
|---|---|---|
| `privacy.approval.tenant-id` | `PRIVACY_APPROVAL_TENANT_ID` | 承認tenant（`OIDC_TENANT_ID` と同じ値） |
| `privacy.approval.capabilities` | `PRIVACY_APPROVAL_CAPABILITIES` | D-09 で開放を承認した capability（`retention` / `dsar` のカンマ区切り） |
| `privacy.approval.d01.decision-id` / `signed-on` / `expires-on` | `PRIVACY_APPROVAL_D01_DECISION_ID` / `_SIGNED_ON` / `_EXPIRES_ON` | D-01 正式 Privacy Owner（日付は `yyyy-MM-dd`、JST業務日。失効日当日から無効。有効期間は最長366日） |
| `privacy.approval.d06.*` | `PRIVACY_APPROVAL_D06_*` | D-06 DSAR SOP（`dsar` を承認する場合だけ必要） |
| `privacy.approval.d09.*` | `PRIVACY_APPROVAL_D09_*` | D-09 本番 flag 開放 |
| `privacy.approval.approval-digest` | `PRIVACY_APPROVAL_DIGEST` | 決定書（D-09）に記載された束縛内容のSHA-256（小文字hex 64桁。大文字も可） |
| `privacy.approval.expiry-warning-days` | `PRIVACY_APPROVAL_EXPIRY_WARNING_DAYS` | 失効の何日前から `EXPIRING` のWARN・metricを出すか（既定30、1〜366に丸める）。承認内容ではないため digest に含めない |

決定IDの規則は NF-08（`ai.external-send-approval.*`）と同じ: 英数字で始まる4〜64文字（`A-Z a-z 0-9 . _ : -`）、`TBD`・`TODO`・`PENDING`・`DRAFT`・`UNKNOWN`・`XXXX` 等の断片や `NONE`・`NULL`・`N-A` は拒否、必要な決定の間で同じIDは拒否（大文字小文字を区別しない）。規則の一致は `PrivacyDecisionBindingTest` が NF-08 との比較で固定している。

**digestの正規形**: UTF-8、1行目が版 `nf07-privacy-decision-binding-v1`、以降が `tenant-id`、`capabilities`（小文字・昇順・カンマ区切り）、承認capabilityが必要とする決定を `d01`→`d06`→`d09` の順に `dNN.decision-id` / `dNN.signed-on` / `dNN.expires-on`。全ての行をLFで終える（最終行も）。不要な決定（retention だけなら D-06）は含めない。算出例（合成値。`PrivacyDecisionBindingTest` の固定値と同じ）:

```sh
printf 'nf07-privacy-decision-binding-v1\ntenant-id=tenant-a\ncapabilities=retention\nd01.decision-id=D01-NF07-2026-0001\nd01.signed-on=2026-11-01\nd01.expires-on=2027-10-31\nd09.decision-id=D09-NF07-2026-0009\nd09.signed-on=2026-11-10\nd09.expires-on=2027-05-31\n' | sha256sum
# a4ce05f19b45525b88857b66b6b03d8d2a793f60f3805d616c5719c7c6503404
```

#### 4.7.4 ONにする手順（`PRIVACY_RETENTION_ENABLED`）

1. **署名の確認**: D-01・D-09 の決定書（決定ID・署名日・失効日・承認tenant・承認capability・digest）が揃っていることを Owner が確認する。B-NF07-1 により、本番公開は production bridge（W-1〜W-12）の接続後である。
2. **privacy-admin を割り当てる**（最低2名。deploy-runbook.md §5.0 のOIDC管理者 C〜E の**3名全員を推奨**。OIDCでログインできない管理者は数えられない。break-glass口座 A・B は大文字小文字の表記揺れも含めて400で拒否される）。
   - 割当は `PUT /api/permission-groups/users/{id}` で、対象者を `privacy-admin` **だけ**に割り当てる（`role-admin` の代わり。`'*'` を持つので管理権限は減らない。`role-admin` 等との同時割当は409）。対象者のsessionは失効し、再ログインが必要。
   - **最初の割当には既存の privacy-admin がいない**（W3-14 review P3-4）。この場合の通知は、対象者以外の有効な管理者全員へ送られる（`PRIVACY_ADMIN_CHANGED`）。ただし通知は事後の知らせにすぎないため、最初の2名（推奨3名）の割当は **Owner と Security の二者が対象者を事前に確認し、割当後に監査（`t_audit_log.application_code='PRIVACY_ADMIN_GROUP_CHANGED'`、`before_state`/`after_state` に group key）を二者で照合する**。G-ID の署名で割当規則が置き換わるまでの暫定規則である。
   - 確認SQL（DBA。起動検査 `UserPermissionGroupMapper#selectEffectivePrivacyAdminUsernames` と同じ条件。`:tenant` に `OIDC_TENANT_ID`、`:issuer` に `OIDC_ISSUER_URI`、`:bg1`・`:bg2` に `BREAK_GLASS_USERNAMES` の2件を小文字で入れる。2以上（推奨3）であること。ロックの有無は見ない）:
     ```sql
     SELECT COUNT(DISTINCT u.id) FROM t_user_permission_group upg
       JOIN m_permission_group pg ON pg.id = upg.group_id
       JOIN sys_user u ON u.id = upg.user_id
      WHERE upg.tenant_id = :tenant AND upg.deleted_flag = 0
        AND pg.tenant_id = :tenant AND pg.group_key = 'privacy-admin' AND pg.enabled = 1 AND pg.deleted_flag = 0
        AND u.tenant_id = :tenant AND u.role = '管理者' AND u.status = 1 AND u.deleted_flag = 0
        AND LOWER(TRIM(u.username)) NOT IN (:bg1, :bg2)
        AND EXISTS (SELECT 1 FROM t_user_external_identity x
                      JOIN m_identity_provider p ON p.id = x.provider_id
                     WHERE x.tenant_id = :tenant AND x.user_id = u.id AND x.review_status = 'APPROVED'
                       AND x.deleted_flag = 0 AND p.tenant_id = :tenant AND p.issuer_uri = :issuer
                       AND p.provider_type = 'OIDC' AND p.enabled = 1 AND p.deleted_flag = 0)
        AND NOT EXISTS (SELECT 1 FROM t_user_permission_group o
                          JOIN m_permission_group og ON og.id = o.group_id
                          JOIN t_permission_group_action d ON d.group_id = og.id
                         WHERE o.tenant_id = :tenant AND o.user_id = u.id AND o.deleted_flag = 0
                           AND og.tenant_id = :tenant AND og.enabled = 1 AND og.deleted_flag = 0
                           AND d.tenant_id = :tenant AND d.deny_flag = 1 AND d.deleted_flag = 0
                           AND (d.action_key = '*' OR d.action_key LIKE 'privacy.%'));
     ```
3. **束縛値の設定**: 4.7.3 の環境変数を全instanceに同じ値で設定する。digest は決定書の値から二者がそれぞれ正規形を作り、同じ値になることを確認してから転記する。
4. `PRIVACY_RETENTION_ENABLED=true` にして**全停止で再起動**する（rolling 更新で旧値のinstanceと混在させない）。
5. 起動ログで `[privacy] NF07のflag ONの前提を確認しました: flags=privacy.retention.enabled decisionIds=[…] privacyAdmins=3`（2名以上）と、`[機能スイッチ] 起動時の状態` の `NF07_RETENTION=ON` を確認する。起動に失敗した場合は例外messageの違反コード（4.7.2）を確認し、直すまでは `PRIVACY_RETENTION_ENABLED` を外して起動する。代わりに `NF07_PRIVACY_STOPPED_AT_STARTUP` のWARNが出て `NF07_RETENTION=OFF` になった場合は、署名日前か失効である（4.7.2.1。署名日前なら署名日に自動で開く）。
6. 動作確認: privacy-admin で `GET /api/privacy/policies/evaluate?...` が200（`decisionStatus=POLICY_UNKNOWN`。NF07-REG・TRUST の前は `APPROVED_ACTIVE` にならない）、privacy-admin 以外の管理者は403。

#### 4.7.5 失効・再承認（更新）・OFF

- **失効前（推奨）**: `EXPIRING` のWARN・metric（既定で失効30日前から）を受けたら、次の手順で再承認する。
  1. 失効する決定（最も早い失効日の決定。D-01 か D-09）の署名者から新しい決定（新しい決定ID・署名日・失効日）を得る。D-09 を再署名する場合は、新しい束縛内容の digest を決定書に記載してもらう（決定ID・日付が変わると digest も変わる）。
  2. 4.7.4 の3（二者で正規形から digest を作って照合し、全instanceに同じ値を設定）を行う。新しい署名日が未来の場合は、その日まで NF07 は閉じる（`NOT_YET_VALID`）ので、旧決定の失効日より前の日付で署名してもらう。
  3. 全停止で再起動し、`[privacy] NF07のflag ONの前提を確認しました` と `NF07_RETENTION=ON`、metric `state="VALID"` と `days_remaining` が新しい失効日に合うことを確認する。
- **失効後**: 入口は失効日（JST）から既にOFFと同じ404で、process はそのまま動く。上の1〜3で再承認して再起動すれば開く。再承認を待たない場合は `PRIVACY_RETENTION_ENABLED` を外して再起動する（WARNとmetricの `EXPIRED` を止める）。
- 問題時は `PRIVACY_RETENTION_ENABLED` を外して再起動する（OFFでは決定ID束縛と privacy-admin の検査を行わない）。privacy-admin の割当は残る（OFF中は入口が404のため使われない）。

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
| `EngineerCertificationNumberEntrySwitchTest` | Docker不要 | 資格番号の子スイッチOFF時に申請・再申請を書込み・暗号化前に400で拒否、空欄は受付、ON時は従来どおり暗号化保存 |
| `MyCertificationNumberEntryDisabledApiTest` | Docker不要 | 子スイッチOFF時、本人API（controller → 本人service → 資格service）の申請・再申請がHTTP 400の `ApiResult` になる |
| `FeatureSwitchChildTemplateRenderingTest` | Docker不要 | 実templateをThymeleafで描画し、子スイッチOFFでportalの問い合わせ導線・本人画面の資格番号欄が出ないこと |
| `PortalServiceRequestVisibilityPolicyTest` | Docker不要 | portal公開範囲の判定（portal起票・公開開始時刻・timezone・不正値の起動失敗・一覧条件） |
| `PortalServiceRequestVisibilityIntegrationTest` | Docker不要（H2） | 公開開始時刻未設定の実context。portalの一覧・詳細・返信・CSAT・添付uploadが社内登録分を404/非表示にし、内部サービスデスクは従来どおり |
| `FeatureSwitchServletContainerIntegrationTest` | Docker不要・実Tomcat起動（Windowsでは **PowerShell環境** で実行） | NF-01をproperty形式、NF-04を環境変数形式でOFFにした実context。未認証の画面404（login誘導されない・no-store）、管理者と未認証で同一の404 JSON、存在しないURLの404との同一性、ONの機能・中核APIはSpring Securityへ届くこと、PWA静的入口の遮断範囲、filter登録順 |
| `PrivacyDecisionBindingTest` | Docker不要 | NF-07の決定ID束縛: 正規形とdigest、各項目の欠落、capabilityごとの必要な決定、未承認capability、tenant不一致、失効・署名日前、digest改竄、ID重複、決定ID・日付の判定がNF-08と一致すること |
| `PrivacyProductionConfigurationValidatorTest` | Docker不要（H2） | NF-07 flag ONのprod起動検査: 全OFFは無検査、各欠項・tenant不一致・digest改竄・capability未承認で起動失敗、失効・署名日前は起動してWARNとOFF扱い、時間要因と構造的不足の同時は起動失敗、privacy-admin 1名／無効・削除・他role・他tenant・OIDC link無し・`'*'`/`privacy.<x>` 拒否group所属・break-glass（大文字小文字違い含む）の除外、ロック中は数える、OIDC provider無し、privacy-admin groupの許可欠落・拒否行、他groupの `privacy.` 許可行、DSAR未実装、照会失敗、messageに値を出さないこと |
| `PrivacyDecisionWindowMonitorTest` | Docker不要 | NF-07の有効期間監視: 固定・可変Clockで、有効・EXPIRING・失効・署名日前・不完全の状態とmetric・日付ごとのWARN、実行中に失効日（JST 0:00）を迎えると `FeatureSwitches`・`FeatureSwitchFilter`・`PrivacyAccessGuard` がOFFと同じ404になること、署名日に自動で開くこと、実行時条件の例外はOFF |
| `FeatureSwitchRuntimeConditionTest` | Docker不要 | 機能スイッチの実行時条件: 設定ONでも条件を満たさない間は `isEnabled`・`blockingFeature`・`disabledKeys`・`visibleMenuKeys` がOFFと同じ、起動ログの `NF07_RETENTION=OFF` と理由のWARN、条件の無い機能・`new` は従来どおり |
| `PrivacyProductionConfigurationTest` | Docker不要 | prod の policy source はスイッチONかつ決定ID束縛の有効期間内だけ読む（OFFなら束縛を見ない、判定部品が無い・例外なら読まない） |
