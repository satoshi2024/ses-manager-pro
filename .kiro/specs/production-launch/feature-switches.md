# 機能スイッチ運用手順（NF-01 / NF-02 / NF-03 / NF-04 / NF-10）

- 対象: 初回本番リリース（新規構築・全停止デプロイ・専用Flyway job）で段階公開するS-NF機能
- 位置づけ: **権限ではなく公開可否のスイッチ**。OFFの機能は管理者を含む全ロール・未認証利用者から見えなくなる
- 実装: `com.ses.config.feature`（`Feature` / `FeatureSwitchProperties` / `FeatureSwitches` / `FeatureSwitchFilter`）
- 対象外: NF-05・NF-06・NF-08（既存の本番向けハードゲートで制御）、NF-07（本番経路未接続）、NF-09

## 1. スイッチ一覧

| 機能 | 設定キー | 環境変数 | 既定値（application.yml / prod） | test profile |
|---|---|---|---|---|
| NF-01 ライフサイクル管理・退社ゲート | `app.features.nf01.enabled` | `FEATURE_NF01_ENABLED` | `false` | `true` |
| NF-02 カスタマーサクセス・サービスデスク | `app.features.nf02.enabled` | `FEATURE_NF02_ENABLED` | `false` | `true` |
| NF-03 資格・学習・スキルギャップ | `app.features.nf03.enabled` | `FEATURE_NF03_ENABLED` | `false` | `true` |
| NF-04 要員PWAシェル・オフライン下書き復元 | `app.features.nf04.enabled` | `FEATURE_NF04_ENABLED` | `false` | `true` |
| NF-10 定期管理レポート | `app.features.nf10.enabled` | `FEATURE_NF10_ENABLED` | `false` | `true` |

- `application.yml` と `application-prod.yml` は同じプレースホルダ（`${FEATURE_NF0x_ENABLED:false}`）を持つ。環境変数を設定しない限り本番もOFF。
- dev profileは既定のOFFのまま。ローカルで確認する場合は上記環境変数を `true` にして起動する。
- test profile（`src/test/resources/application-test.yml`）は既存テストの意味を保つため全てON。OFF時の挙動は `com.ses.config.feature` 配下のテストで検証する。
- 値は起動時に1回だけ読む。**切替には再起動が必要**（実行中の切替APIは無い）。
- Spring標準の環境変数名（`APP_FEATURES_NF01_ENABLED`）でも上書きできてしまうが、運用では `FEATURE_NF0x_ENABLED` だけを使い、両方を同時に設定しないこと。

## 2. OFF時に止めるもの（適用範囲）

共通の応答仕様:

- 画面・API・静的入口はServlet filter（`FeatureSwitchFilter`、Spring Securityより前）で **404** にする。
  - `/api/**` は `CustomErrorController` の404と同じ `ApiResult` JSON（`{"code":404,"message":"お探しのページは存在しないか、移動された可能性があります。","data":null}`）。
  - 画面・静的ファイルは `sendError(404)` で統一エラーページ（`error.html`）を表示する。
  - 403ではなく404にする理由: 存在しないURLと同じ応答にして、OFFの機能が存在するかどうかを外部から区別させないため。認証・CSRF・`MenuPermissionFilter` より前に止めるので、管理者でも未認証でも同じ応答になる。
  - 遮断応答には `Cache-Control: no-store` を付け、ON切替後に古い404が残らないようにする。
  - `%` エンコード・`;` 付きpath・連続スラッシュ等の表記ゆれでも照合する（復号後と生のpathの両方で判定）。
- サイドバーは `GlobalControllerAdvice` が `allowedMenus` から対象機能のmenu keyを除外する（管理者も同じ）。
- `@Scheduled` 入口は各schedulerの先頭で `FeatureSwitches.allows(...)` を確認し、OFFなら何もせず戻る（ShedLockの注釈は変更しない）。

| 機能 | 遮断する画面・API・静的入口 | 止めるscheduler | 隠すメニュー・埋込み表示 |
|---|---|---|---|
| NF-01 | `/lifecycle/**`、`/my/lifecycle/**`、`/api/lifecycle/**`、`/api/my/lifecycle/**` | `LifecycleSlaScheduler#runSlaCheck`（毎朝8時） | menu `lifecycle`・`myLifecycle`、要員詳細画面のライフサイクル欄 |
| NF-02 | `/service-desk/**`、`/customer-success/**`、`/api/service-desk/**`、`/api/customer-success/**`、`/api/portal/customer/service-desk/**`（顧客ポータルの問い合わせAPI） | `ServiceSlaScheduler#runSlaMonitoring`（5分毎）、`CustomerHealthScheduler#runDailySnapshot`（日次）、`ServiceRequestAttachmentCompensationScheduler#retryDue`（5分毎） | menu `service-desk`・`customer-success`、顧客ポータルの「問い合わせ」タブ・画面・モーダル・script、契約更新カレンダーの顧客ヘルス表示 |
| NF-03 | `/certification-learning-skill-gap/**`、`/my/certification-learning-skill-gap/**`、`/api/certification-learning-gap/**`、`/api/my/certification-learning-gap/**` | `CertificationExpiryNotificationScheduler#dispatchToday`（日次） | menu `certification-learning-skill-gap`・`myCertificationLearningGap` |
| NF-04 | `/service-worker.js`、`/manifest.webmanifest`、`/offline.html`、`/api/my/pwa/restore/**`（下書き復元の再認証） | なし（下記4.4参照） | manifestリンク、Service Worker登録（`common.js` が既存登録とshell cacheを解除）、同期パネルの「下書きを復元」リンク |
| NF-10 | `/management-reports/**`、`/api/management-reports/**` | `ManagementReportScheduler#dispatchDue`（毎分。claim・生成・配布を開始しない） | menu `management-report` |

手動実行・テスト用の入口（`processSlaCheck`、`monitorSlaClocks`、`processDailySnapshot`、`dispatch(asOf)` 等）はスイッチの対象外。これらを呼ぶ本番画面・APIは上表のfilterで止まる。

## 3. ON / OFF の手順

### 3.1 初回デプロイ

1. 全スイッチOFF（環境変数未設定）のまま、全停止デプロイと専用Flyway jobを実施する。機能スイッチはFlywayに影響しない（OFFでもNFのテーブル・seedは作成される）。
2. 起動ログで `[機能スイッチ] 起動時の状態: NF01=OFF, NF02=OFF, NF03=OFF, NF04=OFF, NF10=OFF` を確認する。
3. 各NFの代表URL（例: `/api/lifecycle/cases`）が404になり、サイドバーに表示されないことを確認する。

### 3.2 1機能ずつONにする

1. 対象NFの業務Owner承認・前提（下記4章の注意事項）を確認する。
2. 対象の環境変数だけを `true` に設定する（例: `FEATURE_NF02_ENABLED=true`）。複数instanceの場合は**全instanceで同じ値**にする。
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

## 4. 注意事項

### 4.1 共通

- **再起動が必要**。切替は「環境変数変更＋再起動」で行う。rolling更新で旧値と新値のinstanceを混在させないこと（全停止で切替える）。
- 全scheduler一括停止は既存の `app.scheduling.enabled=false` で行う。機能スイッチは特定NFだけを止めるためのもの。
- 機能スイッチは権限ではない。ON後のアクセス制御は従来どおり `SecurityConfig`・`MenuPermissionFilter`・action権限で行う。
- 遮断はSpring Securityより前で行うため、監査ログ（`ApiAuditFilter`）には記録されない。調査時は `logging.level.com.ses.config.feature=DEBUG` で `[機能スイッチ] OFFの機能への要求を404で遮断しました` を出力できる。
- OFF中も、既に送信済みの通知に含まれるNF画面へのリンクは404になる。
- OFFでもprod起動時の必須secret検証は変わらない（例: `PWA_CONTEXT_SECRET`、資格番号の暗号鍵）。OFFを理由にsecret供給を省略できない。

### 4.2 NF-01

- 承認画面（`/approval`・`/api/approval/**`）はNF-01の入口ではないため、ON期間中に起票されたライフサイクル例外承認（requestType `LIFECYCLE_EXCEPTION` / `lifecycle.exception` / `lifecycle.waive`）の未決分は、OFF中でも承認画面から決裁でき、決裁時にライフサイクルtaskへ反映される。OFFにする前に未決の承認依頼が無いことを確認するか、決裁を保留する運用にすること。
- 資産返却（NF-09）の処理はNF-01のデータを参照するが、NF-09側の機能でありNF-01スイッチでは止めない。

### 4.3 NF-02

- ON期間中に作成された問い合わせ添付は文書台帳に登録済みのため、OFF中も既存の文書API（顧客DataScopeを持つ利用者）から参照できる。権限は変わらない（新たな公開にはならない）。
- 添付補償の再試行（`RETRY` 台帳）はOFF中は止まり、ON復帰後の次回実行で再開する。台帳は削除されない。
- SLA違反監視はON復帰後の初回実行で、その時点の時刻を基準に判定する（OFF期間中の違反はON復帰後にまとめて検出される）。
- NF-02/NF-03の所有者修復API（`/api/admin/nf02-nf03/ownership-repair`、管理者専用）はデータ修復のため常時有効とする。
- 顧客ポータルは問い合わせタブだけが消え、検収・契約・請求等の他タブは従来どおり使える。

### 4.4 NF-04

- **日次入力の保存経路は止めない。** 要員の勤怠・作業報告・経費下書き・変更申請下書きは `/api/my/pwa/{attendance,timesheet,expenses,change-requests}` と `/api/my/session-context`、`/js/pwa-queue.js` を通常の保存経路として使っているため、OFFでも有効のままにする（止めると要員の日次入力そのものが停止する）。
- そのためOFFで止まるのは「PWA shell」（インストール用manifest、Service Worker、offlineページ）と「ロック済み下書きの復元（再認証）」だけである。
  - OFF中は新しいService Workerを登録しない。ON期間にインストール済みの端末は、次回画面表示時に `common.js` が本アプリの `/service-worker.js` 登録と `ses-pwa-shell-*` cacheを解除する（制御中のタブを全て閉じた後に完全に外れる）。
  - OFF中はロック済み下書きを復元できない。下書きは端末内に残り、ログアウトで削除される。同期パネルの文言は復元を案内する場合があるが、復元リンクは表示しない。
- server側のidempotency ledger掃除（`PwaClientMutationCleanupScheduler`）は、常時有効な保存経路が書き込むため**OFF中も止めない**（止めるとledgerが保持期限を超えて増え続ける）。
- logout・session破棄時のPWA復元失効処理（`PwaRestoreSessionListener`）は失効側の処理なので常時有効。

### 4.5 NF-10

- OFFで止まるのは画面・API・`dispatchDue`（scheduleのclaim・生成・配布の開始）である。OFF中はprocessing leaseにも触れない。
- OFFにする時点で既に通知outboxへ投入済みの配信通知は、汎用の通知outbox送信（`NotificationOutboxScheduler`）により送信され、配信状態も同期され得る（リンク先はOFF中404）。配信の即時停止が必要な場合は、OFF前に該当の配信を取消すこと。配信専用スイッチの分離は後続作業（A-NF10-6）で検討する。
- ON復帰後、OFF中に期限を迎えたscheduleは次回のdispatchで通常どおり処理される。

## 5. 新しい入口を追加するとき

1. `Feature` の該当NFへ画面prefix・API prefix・静的path・menu keyを追加する。
2. NF配下の新しい `@Scheduled` は入口で `FeatureSwitches.allows(featureSwitches, Feature.NFxx)` を確認し、`FeatureSwitchRouteCoverageTest` の `GATED_SCHEDULERS` へ登録する。止めない場合は理由付きで `UNGATED_SCHEDULERS` へ登録する。
3. 意図的にスイッチ対象外とするAPIは理由付きで `UNGATED_ROUTE_PREFIXES` へ登録する。
4. `FeatureSwitchRouteCoverageTest` は、NF service packageへ依存するcontroller、またはNFのaction resourceへ解決されるAPI root・画面rootが遮断表に無い場合に失敗する。

## 6. 検証テスト（Docker不要）

| テスト | 内容 |
|---|---|
| `FeatureSwitchDefaultsTest` | 既定OFF（application.yml / prod）、環境変数で指定した1機能だけON、test profileは全ON |
| `FeatureSwitchFilterTest` | OFF時のAPI 404 JSON・画面/静的404、管理者・未認証とも迂回不可、表記ゆれ、ON時の素通し、機能間の独立性、Spring Securityより前の登録順 |
| `FeatureSwitchMenuAndViewTest` | サイドバー非表示（管理者・一般ロール）、テンプレート用OFF一覧、契約更新カレンダーの顧客ヘルス抑止 |
| `FeatureSwitchSchedulerGateTest` | 6 schedulerの `@Scheduled` 入口がOFFでno-op、ONで従来どおり実行 |
| `FeatureSwitchRouteCoverageTest` | NF入口・scheduler・サイドバー導線の登録漏れ検出、prefixの重複・古い登録の検出 |
