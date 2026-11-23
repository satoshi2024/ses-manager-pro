# 機能スイッチ運用手順（NF-01 / NF-02 / NF-03 / NF-04 / NF-06 / NF-07 / NF-10、子スイッチ3件）

> **機能の開放への移行中（`.kiro/specs/feature-enablement-console/`。2026-11-23 の段階 A）**: prod では NF-01 本体・NF-02 本体・NF-02 顧客ポータル・NF-03 本体・NF-03 資格番号・NF-04・NF-10 の7件を、環境変数ではなく画面「機能の開放」の申請と別の管理者の承認で開く。これら7件の環境変数（`FEATURE_NF01_ENABLED` 等）と property の他の綴りを prod の配備の設定に置くと起動しない（`FeatureEnablementProductionValidator`。deploy-runbook §1.0 の「機能の開放」）。段階 B〜D で NF-01 二者確認・NF-06・NF-07 保持・保全・DSAR・NF-05 inbound・outbound も同じく移り、その開閉の変数（`FEATURE_NF01_DUAL_CONFIRMATION_ENABLED` 等）と、署名・設定へ移した承認の値（4.9 の Privacy 署名の参照 `FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF`、3.4・4.3.1 の公開範囲 `FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE`、`IMPORT_ADMISSION_*`・`PRIVACY_APPROVAL_*` 等）も、値に依らず prod の配備の設定に置くと起動しない（同 §1.0。段階 A2 の暫定の拒否は段階の整合で外した）。本書の表と手順は dev・test と残りの機能にだけ当てはまり、本書の書直しは段階 E（FE-E03）で行う。

- 対象: 初回本番リリース（新規構築・全停止デプロイ・専用Flyway job）で公開するS-NF機能。初回リリースで ON にする機能と OFF のままの機能は 3.1（2026-11-22 利用者決定）
- 位置づけ: **権限ではなく公開可否のスイッチ**。OFFの機能は管理者を含む全ロール・未認証利用者から見えなくなる
- 実装: `com.ses.config.feature`（`Feature` / `FeatureSwitchProperties` / `FeatureSwitches` / `FeatureSwitchFilter`）
- 対象外: NF-05・NF-08（既存の本番向けハードゲートで制御）、NF-09。NF-07は保持・保全（`NF07_RETENTION`）と DSAR（`NF07_DSAR`、`privacy.dsar.enabled`。A2a で登録）を親子にしない2つの最上位機能として登録する
- NF-06はスイッチ（公開可否）に加えて本番admission port（Q-5、`.kiro/specs/data-migration-import-center/a1a-ingress-operations.md` §10）でも制御する。apply系はスイッチと無関係に閉じている（同 §1）。ONの前提（行参照鍵・`import.metadata.manage` の付与・単一法人・LB idle timeout）は4.8

## 1. スイッチ一覧

| 機能 | 設定キー | 環境変数 | 既定値（application.yml / prod） | test profile |
|---|---|---|---|---|
| NF-01 ライフサイクル管理・退社ゲート | `app.features.nf01.enabled` | `FEATURE_NF01_ENABLED` | `false` | `true` |
| └ NF-01の子: 二者確認（DUAL_CONFIRMATION）の提出・確認・差戻し（W3-21a）。**ONの条件: Privacy署名（NF01細粒度epoch計画 §10・§12 O-8）の参照**（prodでは起動時に強制。4.9） | `app.features.nf01-dual-confirmation.enabled` | `FEATURE_NF01_DUAL_CONFIRMATION_ENABLED` | `false` | `true` |
| NF-02 カスタマーサクセス・サービスデスク（内部画面） | `app.features.nf02.enabled` | `FEATURE_NF02_ENABLED` | `false` | `true` |
| └ NF-02の子: 顧客ポータルの問い合わせ（起票・返信・CSAT・添付） | `app.features.nf02-customer-portal.enabled` | `FEATURE_NF02_CUSTOMER_PORTAL_ENABLED` | `false` | `true` |
| NF-03 資格・学習・スキルギャップ | `app.features.nf03.enabled` | `FEATURE_NF03_ENABLED` | `false` | `true` |
| └ NF-03の子: 資格番号（retention class `CERTIFICATION_PII`）の入力 | `app.features.nf03-certification-pii.enabled` | `FEATURE_NF03_CERTIFICATION_PII_ENABLED` | `false` | `true` |
| NF-04 要員PWAシェル（Service Worker・manifest） | `app.features.nf04.enabled` | `FEATURE_NF04_ENABLED` | `false` | `true` |
| NF-06 データ移行・取込センター（画面 `/import-center`・API。apply系は含まない）。**ONの条件: admission承認設定・行参照鍵・`import.metadata.manage` の付与（V193）・単一法人**（prodでは承認設定と鍵を起動時に強制。4.8） | `app.features.nf06.enabled` | `FEATURE_NF06_ENABLED` | `false` | `true` |
| NF-07 保持・保全（policy説明。catalog・holdは後続batchで追加）。**ONの条件: 決定ID束縛（D-01・D-09）の完了と、privacy-admin の有効な管理者2名以上**（prodでは起動時に強制。決定の署名日前・失効の間は起動を止めずOFFとして扱う。4.7） | `privacy.retention.enabled` | `PRIVACY_RETENTION_ENABLED` | `false` | `true` |
| NF-07 本人請求（DSAR。A2a で受付〜範囲確定の画面 `/privacy/dsar`・API・期限通知、A2b で検索・草案・承認・専用ダウンロード・交付記録）。NF-07 保持・保全とは親子にしない。**ONの条件: 決定ID束縛（D-01・D-06・D-09）と計画 §5.1 の追加前提（`due-days`・`package-max-age`・F2 provider 1件以上。4.7）** | `privacy.dsar.enabled` | `PRIVACY_DSAR_ENABLED` | `false` | `true` |
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
| NF-01の子 `nf01-dual-confirmation` | **URLは遮断しない**（二者確認のAPIは親NF-01の遮断表に属する）。`/api/lifecycle/tasks/{id}/dual-confirmation/**`・`/api/my/lifecycle/tasks/{id}/dual-confirmation/**`（照会・候補・提出・確認/差戻し）を `LifecycleDualConfirmationCoordinator` が400（message key `error.lifecycle.dualConfirmationDisabled`）で拒否する（lock・書込みの前。拒否eventは記録しない）。DUAL_CONFIRMATIONを含むtemplateの作成・改定は子スイッチに関係なく400のまま（W3-21bでスイッチ連動へ変更） | なし | 画面の提出・確認導線はW3-21bで追加する |
| NF-02 | `/service-desk/**`、`/customer-success/**`、`/api/service-desk/**`、`/api/customer-success/**`（親がOFFなら下の子の入口も遮断） | `ServiceSlaScheduler#runSlaMonitoring`（5分毎）、`CustomerHealthScheduler#runDailySnapshot`（日次）、`ServiceRequestAttachmentCompensationScheduler#retryDue`（5分毎） | menu `service-desk`・`customer-success`、契約更新カレンダーの顧客ヘルス値（＋子の埋込み表示） |
| NF-02の子 `nf02-customer-portal` | `/api/portal/customer/service-desk/**`（顧客ポータルの問い合わせAPI: 一覧・詳細・起票・返信・CSAT・添付upload/download）。ON中も公開範囲（`visible-since`）外の問い合わせは同じ404 | なし（schedulerは親NF-02に従う） | 顧客ポータル（`/portal/customer`）の「問い合わせ」タブ・画面・起票モーダル・詳細・返信モーダル・CSATモーダル・文言・scriptの参照。portal画面自体と検収・契約・請求・見積・注文請タブは止めない（`/portal/js/portal-service-desk.js` 自体のURLは遮断しないが、dataを含まない） |
| NF-03 | `/certification-learning-skill-gap/**`、`/my/certification-learning-skill-gap/**`、`/api/certification-learning-gap/**`、`/api/my/certification-learning-gap/**` | `CertificationExpiryNotificationScheduler#dispatchToday`（日次） | menu `certification-learning-skill-gap`・`myCertificationLearningGap` |
| NF-03の子 `nf03-certification-pii` | **URLは遮断しない**。資格申請（`POST /api/my/certification-learning-gap/certifications`）・再申請（`POST .../certifications/{id}/resubmit`）で資格番号が空欄でない要求を `EngineerCertificationServiceImpl` が400（message key `certification.record.numberEntryDisabled`）で拒否する（書込み・lock・暗号化の前。本人scope確認の読取りと再申請のversion照合は先に行うため、version不一致は409が先に返る） | なし | 本人画面（`/my/certification-learning-skill-gap`）の申請モーダルの資格番号入力欄、再申請ダイアログの資格番号入力（確認だけのダイアログになる） |
| NF-04 | `/service-worker.js`、`/manifest.webmanifest` のみ | なし（下記4.4参照） | manifestリンク、Service Worker登録（`common.js` が既存登録とshell cacheを解除） |
| NF-06 | `/import-center/**`（取込センター画面、W3-12）、`/api/import-center/**`（upload・job詳細・検証・preview・error export・source/mapping管理） | なし（`ImportStageReconcileScheduler` は照合台帳に残った取込原本の回収のためOFFでも止めない） | menu `import-center`（V193。管理者だけに付与） |
| NF-07 保持・保全 | `/api/privacy/policies/**`（W3-14）、`/api/privacy/catalog`（F2）。OFF中は filter と service 層の `PrivacyAccessGuard` が同じ本文の404にする。遮断表に無い `/api/privacy/<x>` には handler が無く guard も通らないため、privacy-admin 以外の管理者は403（`MenuPermissionFilter`）、privacy-admin は Spring の404になる（管理者以外のroleは `SecurityConfig` の403）。ONでも `privacy.*` の action 権限（privacy-admin）が別に要る | なし | menu `privacy-retention`（W3-14では sidebar 導線なし） |
| NF-10 | `/management-reports/**`、`/api/management-reports/**` | `ManagementReportScheduler#dispatchDue`（毎分。claim・生成・配布を開始しない） | menu `management-report` |

手動実行・テスト用の入口（`processSlaCheck`、`monitorSlaClocks`、`processDailySnapshot`、`dispatch(asOf)` 等）はスイッチの対象外。これらを呼ぶ本番画面・APIは上表のfilterで止まる（本番コードからの呼出し元は無い）。

## 3. ON / OFF の手順

### 3.1 初回デプロイと初回リリース

**初回リリースのスイッチ（決定済み（2026-11-22、利用者））**: 初回リリース（go-live。利用者への公開）の時点で、NF-01 本体・NF-01 二者確認・NF-02（子の顧客ポータルの問い合わせを含む）・NF-03 本体と資格番号・NF-04・NF-05（inbound・outbound）・NF-06・NF-07 保持・保全（hold の解除と policy の登録・有効化を含む）・NF-07 本人請求（DSAR）・NF-10 を ON にし、NF-08 は OFF のままにする。2026-11-17 からの「初回リリースは全スイッチOFF」の方針を変更した。

- NF-02 の子の顧客ポータルの問い合わせ（`FEATURE_NF02_CUSTOMER_PORTAL_ENABLED`）も初回リリースで ON にする（決定済み（2026-11-22、利用者）。B-NF02-2 の「初回は OFF（第二段階）」を変更した。3.4・4.3.1）。
- NF-07 の trust 文書には、解除の anchor と一緒に policy の登録・有効化（NF07-REG）の anchor も初回から入れる（決定済み（2026-11-22、利用者））。そのため D-02 の署名（保存期間等の値）も go-live の前提になる（4.7.6。**未取得**）。
- ON にする機能の署名・受理・設定値・staging の確認は、全て初回配備（go-live）の前提になる。一覧は `deploy-runbook.md` §1.7（どれも**未取得**。利用者が取得を手配する）。揃うまで go-live しない。
- NF-08 は B2c-2 の完了と独立 Review まで prod が起動を拒否する（対象外の機能。`deploy-runbook.md` §1.7 の NF08 の行）。初回リリースの後に B2c-2 を始めるが、着手の前に利用者の人手の承認が要る（**未取得**）。
- code と雛形（`infra/ecs/task-definition.prod.json`）の既定値は OFF のまま変えない。ON は、実際に登録する task definition に署名の結果どおり設定する。

手順:

1. 全スイッチOFF（環境変数未設定）のまま、全停止デプロイと専用Flyway jobを実施し、初回の起動（bootstrap mode。`deploy-runbook.md` §4・§4.2）を行う。機能スイッチはFlywayに影響しない（OFFでもNFのテーブル・seedは作成される）。初回の起動の task definition に機能スイッチの環境変数があると、照合（`validate-task-definition.ps1` の `-Phase Initial`）が値に関係なく FAIL にする。NF-05 outbound のため、この起動から以後の全ての起動で同じ `INTEGRATION_HUB_BOUND_TENANT_ID`（機能スイッチではない）を設定する（`deploy-runbook.md` §1.7「ON の起動までの順序と準備」の1）。
2. 起動ログで `[機能スイッチ] 起動時の状態: NF01=OFF, NF01_DUAL_CONFIRMATION=OFF, NF02=OFF, NF02_CUSTOMER_PORTAL=OFF, NF03=OFF, NF03_CERTIFICATION_PII=OFF, NF04=OFF, NF06=OFF, NF07_RETENTION=OFF, NF07_DSAR=OFF, NF10=OFF` を確認する（子スイッチは実効値を表示する。並びと値は `FeatureSwitchDefaultsTest` が固定する）。
3. 各NFの代表URL（例: `/api/lifecycle/cases`）が404になり、サイドバーに表示されないことを確認する。
4. bootstrap を終えた後（`deploy-runbook.md` §5.5）、ON の起動の前の準備（NF-07 の Journal instance と専用の Flyway job、NF-05 outbound の OFF の記録（全ての起動で同じ `INTEGRATION_HUB_BOUND_TENANT_ID` を設定し、ON の起動の前の最後の OFF の起動の後に状態の行と監査 `NF05_OUTBOUND_OFF` を確かめる）、privacy-admin の割当（4.7.4 の2）、法人を持つ組織（4.8.2）、全社共通カレンダー（4.3）。owner と状態は `deploy-runbook.md` §1.7「ON の起動までの順序と準備」。利用者が DBA・SRE を手配する。**未取得**）を揃え、ON にする環境変数と ON の時に要る値を、署名の結果どおり task definition に設定する。値の所在は `deploy-runbook.md` §1.7「初回リリースのスイッチと、ONの起動時検査が要る値の所在」と §1.0「ONにした時だけ必須になる設定」。照合は `-Phase Steady` で行う（ON/OFF の組合せは拒否しない。名前・置き場所等だけを見る。ON の時に要る値は app の起動時検査が見る）。
5. 全停止で再起動し、起動ログの `[機能スイッチ] 起動時の状態` が `NF01=ON, NF01_DUAL_CONFIRMATION=ON, NF02=ON, NF02_CUSTOMER_PORTAL=ON, NF03=ON, NF03_CERTIFICATION_PII=ON, NF04=ON, NF06=ON, NF07_RETENTION=ON, NF07_DSAR=ON, NF10=ON` になることと、各機能の起動時の確認の log（4.7.4 の5、4.9.3 の3 等）を確かめる。NF-07 は決定の署名日前・失効日以降だと起動を止めずに OFF として扱う（4.7.2.1）ため、ここで ON を確かめる。NF-05 はこの log の対象外で、DG-05 草案 §4 の確認を行う。
6. 各機能の画面・API が権限どおりに表示され、各節の ON の確認が済んでから公開する。

### 3.2 1機能ずつONにする

初回リリースの後に機能を ON にするとき（3.3 で OFF にした機能を再び ON にするとき等）の手順である。初回リリースで ON にする機能は 3.1 の手順で ON にする。

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

### 3.4 初回リリースの設定値（2026-11-13 決定 B-NF02-2 / B-NF03-1 / B-NF07-1。2026-11-22 利用者決定で一部を変更）

2026-11-22 の利用者決定（3.1）で、NF-01 二者確認・NF-02 の顧客ポータルの問い合わせ・NF-03 資格番号・NF-07 の2つのスイッチは初回リリースで **ON** にする（B-NF02-2・B-NF03-1・B-NF07-1 の「初回は OFF」と、二者確認の「初回は OFF」を変更した）。初回リリースで ON にする行は、右端の「ONにする条件」が go-live の前提になる（どれも**未取得**。`deploy-runbook.md` §1.7）。

| 環境変数 | 初回リリース | 根拠（決定記録） | ONにする条件（NF-02/NF-03は**実装側の提案。未決定**。NF-07は計画 §5.1 の条件で、prodでは起動時に強制する） |
|---|---|---|---|
| `PRIVACY_RETENTION_ENABLED` | `true`（ON。2026-11-22 利用者決定。条件は go-live の前提で**未取得**） | B-NF07-1: production bridge（W-1〜W-12）を全て接続してから公開する。Q-8: 署名前は値UNKNOWN・flag OFF・未知は拒否のままマージする。2026-11-22 利用者決定で初回リリースで ON に変更（hold の解除を含む） | 決定ID束縛（D-01・D-09 の決定ID・署名日・失効日、承認tenant、承認capability `retention`、approval digest）の完了と、privacy-admin の有効な管理者2名以上（break-glass口座を除く）。満たさなければ `PrivacyProductionConfigurationValidator` が起動を止める。決定の署名日前・失効だけなら起動は止めず、その間（実行中に失効日を迎えた日以降を含む）NF07はOFFとして扱われる（4.7.2.1） |
| `PRIVACY_DSAR_ENABLED` | `true`（ON。2026-11-22 利用者決定。条件は go-live の前提で**未取得**） | 同上（2026-11-22 利用者決定で初回リリースで ON に変更）。A2a で入口（`/privacy/dsar`・`/api/privacy/dsar`）と期限通知を `Feature.NF07_DSAR` に登録した。OFF の間は入口が404、期限通知の scheduler も止まる | prodでONにするには、上記に加えて D-06 の決定ID束縛と計画 §5.1 の追加前提（`PRIVACY_DSAR_DUE_DAYS`・`PRIVACY_DSAR_PACKAGE_MAX_AGE`・F2 provider 1件以上）が要る（A2b で実検査に置換。4.7 の表の5） |
| `PRIVACY_DSAR_DUE_DAYS` | D-06 の署名値（初回リリースで DSAR を ON にするため必須。**未取得**。未設定なら期限日を計算せず、DSAR の ON の起動は `dsar.dueDaysUnset` で止まる） | D-06（DSAR SOP）の期限規則。法的な値のため既定値を持たない（I-1） | D-06 の署名後に、署名された日数（受付日 JST からの暦日数、1〜366）を人が設定する。D-01・D-06 の決定が無効な間は設定しても使わない |
| `PRIVACY_DSAR_PACKAGE_MAX_AGE` | D-06 の署名値（初回リリースで DSAR を ON にするため必須。**未取得**。未設定なら草案 package を作らず、DSAR の ON の起動は `dsar.packageMaxAgeUnset` で止まる） | D-06 で決める開示 package の最長保存期間（A2b）。法的な値のため既定値を持たない（I-1）。期限超過・交付記録・case の終了で package は PURGED になり実体も消える（削除の scheduler は flag OFF でも止めない） | D-06 の署名後に人が設定する（1時間〜366日。単位の無い数値は日数、`PT12H` 等も可）。範囲外は未設定と同じ |
| `PRIVACY_DSAR_DEADLINE_WARNING_DAYS` | 7 | 期限の何日前から privacy-admin へ通知するか（運用上の値。法的な期限ではない） | 運用者が 1〜90 の範囲で決める（範囲外は丸める） |
| `FEATURE_NF02_CUSTOMER_PORTAL_ENABLED` | `true`（ON。決定済み（2026-11-22、利用者）。条件は go-live の前提で**未取得**） | B-NF02-2: 初回は内部サービスデスクだけを公開し、顧客ポータルからの起票・返信・CSATは第二段階とする。2026-11-22 利用者決定で初回リリースで ON に変更（第二段階を設けない） | 公開の Owner 承認、DG-02-A/C（portal対象・公開commentの扱い）の署名、`visible-since` を設定するかの決定（4.3.1）。親の `FEATURE_NF02_ENABLED` も `true` |
| `FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE` | 未設定（portal起票のみ。設定するかは CS Owner が決める。**未決定**） | 2026-11-13 決定: portal を開いても、portal を開く前に社内で登録した問い合わせは顧客へ出さない（初回リリースで ON にするため、未設定なら社内で登録した問い合わせはどれも出ない） | 社内で登録した問い合わせも出す必要がある場合だけ、CS Ownerが公開開始時刻を決めて設定する（設定する前の棚卸しは 4.3.1） |
| `FEATURE_NF01_DUAL_CONFIRMATION_ENABLED` | `true`（ON。2026-11-22 利用者決定。条件は go-live の前提で**未取得**） | NF01細粒度epoch計画 §7.8 開始条件（統括の判断: commentを実際に書き込む＝子スイッチON より前にPrivacy署名が要る）・§12 O-8。2026-11-22 利用者決定で初回リリースで ON に変更（署名が先であることは変わらない） | Privacy署名（comment 2列の保存期間と削除・匿名化経路）、W3-21（W3-21a・W3-21b）のPASS。prodでは署名の参照 `FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF` が無いと `LifecycleDualConfirmationReleaseGate` が起動を止める（4.9） |
| `FEATURE_NF03_CERTIFICATION_PII_ENABLED` | `true`（ON。2026-11-22 利用者決定。条件は go-live の前提で**未取得**） | B-NF03-1: 初回は非PII部分（資格の取得日・期限・証憑、学習計画、skill gap）だけを公開し、資格番号はDG-07確定まで本番で入力しない。2026-11-22 利用者決定で初回リリースで ON に変更（DG-07 の確定が go-live の前提になる） | DG-07（`CERTIFICATION_PII` の保持年数・破棄）の承認。NF-07の本番破棄経路の接続を条件に加えるかはPrivacy/Ownerが判断する |

- 起動ログで `NF02=ON, NF02_CUSTOMER_PORTAL=ON`、`NF03=ON, NF03_CERTIFICATION_PII=ON` となることを確認する（初回リリース。3.1 の5）。
- 確認URL（NF-02 と顧客ポータルが ON の時）: `/api/service-desk/requests` は権限どおり、顧客ポータル `/portal/customer` に「問い合わせ」タブが出て、portal 側の権限（`service-desk.view` / `service-desk.create`）どおりに起票・返信・CSAT ができる（4.3.1）。顧客ポータルの子を OFF にすると、`/api/portal/customer/service-desk/requests` が404になり「問い合わせ」タブが出ない。
- 確認画面（NF-03 の子 ON 時）: 要員本人の `/my/certification-learning-skill-gap` の「資格を申請」に資格番号欄が出る。子が OFF のときは欄が出ず、API へ資格番号を付けて送ると400になる（4.6.1）。

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

- **ON前の必須確認（2026-11-14 DG-02-B3実装方針）**: 当年度を含む全社共通カレンダーを事前登録する。法人既定が無い場合のfallbackと週末出勤日・祝日を確認し（法人既定を登録した法人は、その有効期間に全社共通を参照しないため、法人既定に祝日・会社休日が含まれていることも確認する）、missing_calendar WARN（tenant・法人ID・問い合わせID・相関ID）が無いことを監視する。正式なDG-02署名はCS Ownerが行う。

- ON期間中に作成された問い合わせ添付は文書台帳に登録済みのため、OFF中も既存の文書API（顧客DataScopeを持つ利用者）から参照できる。権限は変わらない（新たな公開にはならない）。
- 添付補償の再試行（`RETRY` 台帳）はOFF中は止まり、ON復帰後の次回実行で再開する。台帳は削除されない。添付ファイル自体は文書台帳・file security metadataに登録済みのため、cleanupで削除されない。
- SLA違反監視はON復帰後の初回実行で、その時点の時刻を基準に判定する（OFF期間中の違反はON復帰後にまとめて検出される）。
- 顧客ヘルスの月次snapshotはOFF期間中に生成されない（その月のsnapshotが欠ける場合がある）。
- 契約更新カレンダーはOFF中、顧客ヘルスの値を埋め込まない（画面の「顧客ヘルス」欄は「-」表示になる）。
- NF-02/NF-03の所有者修復API（`/api/admin/nf02-nf03/ownership-repair`、管理者専用）はデータ修復のため常時有効とする。
- 顧客ポータルは問い合わせタブだけが消え、検収・契約・請求等の他タブは従来どおり使える（NF-02本体OFF・子スイッチOFFのどちらでも同じ）。
- 既存の請求の問い合わせメモ（`t_invoice.portal_inquiry`、請求タブ）はNF-02の機能ではないため、子スイッチOFFでも使える。

#### 4.3.1 顧客ポータルの子スイッチ（`nf02-customer-portal`）

- **初回リリースで ON にする（決定済み（2026-11-22、利用者）。B-NF02-2 の「初回は OFF（第二段階）」を変更した）**。下の「ON前の確認」（以前の「第二段階」）は go-live の前提である（**未取得**）。以下の OFF 中の説明は、問題時に子だけを OFF にした場合（3.3）に当たる。
- OFF中は、顧客からの起票・返信・CSAT回答・添付upload/downloadが全て404になる。問い合わせは社内担当がメール・電話等で受け、内部サービスデスク（`/service-desk`）へ代理起票する運用になる。
- CSATはportalからしか回答できないため、OFF中はCSATが集まらない。顧客ヘルスのCSAT要因は「missing input」となり減点されない（`CustomerHealthServiceImpl`。欠損を普通点で埋めない）。
- **初回応答時刻（first response）の記録条件（現行実装）**: 社内担当が状態を `IN_PROGRESS`（内部画面の「対応着手」「対応再開」）へ変更したとき、または `PORTAL_VISIBLE` の返信を登録したときの早い方で記録される（`ServiceRequestServiceImpl#changeStatus` / `#addComment`）。`INTERNAL` メモだけでは記録されない。したがって子が OFF の間は、**受付後に「対応着手」を押せば初回応答SLAを計測できる**。初回応答SLA上の「初回応答」を「対応着手」とみなしてよいかはDG-02-C2（CS Ownerの判断事項）。
- **内部画面の返信の既定は「顧客ポータル公開返信」（`visibility=PORTAL_VISIBLE`）である**。子が OFF の間は顧客から見えないが、データとしては公開扱いで保存される。**子が OFF の間は、顧客への回答内容（メール・電話で伝えた内容の記録）も「社内限定メモ（`INTERNAL`）」で記録することを推奨する**（再び ON にした時の公開対象を増やさない）。子が ON の間は、範囲内の問い合わせの `PORTAL_VISIBLE` の返信は顧客に見える。
- **ONにしたときに顧客へ出る範囲（2026-11-13 決定により実装済み）**: portalの一覧・詳細・返信・CSAT・添付upload/downloadは、次のいずれかを満たす問い合わせだけを扱う（`PortalServiceRequestVisibilityPolicy`。範囲外は他社の問い合わせと同じ404）。
  1. portalから起票された問い合わせ（`channel='PORTAL'` かつ `portal_user_id` あり。社内起票でchannelに「PORTAL」を選んでも該当しない）
  2. 公開開始時刻 `FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE` 以降に作成された問い合わせ（未設定なら該当なし）
  - 既定（未設定）では、**社内で登録した問い合わせとその返信・添付は顧客へ出ない**（portal から起票した問い合わせだけが出る。初回リリースで ON にするため、社内で登録した問い合わせは登録の時期によらず出ない）。件名・本文は返信と異なり公開範囲を個別に指定できないため、この時刻による区切りが件名・本文を守る唯一の手段である。
  - 公開開始時刻を設定すると、その時刻以降に社内で登録した問い合わせは件名・本文・`PORTAL_VISIBLE` の返信・公開添付が顧客に見える（顧客portal利用者は招待時に `service-desk.view` を自動付与される）。設定する場合は、その時刻以降に社内で登録する問い合わせの件名・本文に顧客へ見せない内容を書かない運用とすること。
  - 内部サービスデスク（`/service-desk`、`/api/service-desk/**`）の一覧・詳細・返信・状態変更・添付は、この範囲の影響を受けない。
- **ON前の確認（初回リリースで ON にするため go-live の前提。以前の「第二段階」。どれも未取得）**: (1) `visible-since` を設定するかと、その値をCS Ownerが決める。(2) 設定する場合は、その時刻以降に作成された社内登録分の件名・本文・`PORTAL_VISIBLE` 返信・公開添付を棚卸しする（内部画面の一覧・詳細は、問い合わせごとに「顧客公開対象」か「社内限定」かをportalと同じ判定で表示する。RB-NF02）。(3) portalの「詳細・返信」は、以前は page controller の無い `/portal/customer/service-desk/requests/{id}` を指して404になっていた。RB-I18N（P3-N2-9）で、同じ顧客ポータル画面の詳細・返信モーダル（`#portalRequestDetailModal`。既存の詳細API・返信APIを使い、子スイッチOFF中は描画しない）に改め、参照先の無い `templates/portal/customer/service-desk/{list,detail}.html` を削除した（新しいpage routeは追加していない）。(4) 顧客ポータルの全画面（`templates/portal/**` の6画面）はBootstrapを実在しない `/lib/bootstrap/css/…`・`/lib/bootstrap/js/…` で参照しており、CSS・JSとも404でモーダルと `d-none` が効かなかった。RB-I18N で内部画面と同じ `/lib/bootstrap/bootstrap.min.css`・`/lib/bootstrap/bootstrap.bundle.min.js` へ直し、template・静的HTMLが参照する同梱資源の実在を `StaticAssetLocalityTest` で検査するようにした（修正済み）。この修正は本スイッチと関係なく**初回配備の時点で**顧客ポータルの全画面（login・招待受諾・規約・customer・bp）の見た目と動作を変えるため、実ブラウザの確認は**初回配備の前提**である（`deploy-runbook.md` §1.7「初回配備から有効になる部分」の表、未取得）。子を初回リリースで ON にするため、go-live の前に、加えて問い合わせの起票・CSAT・詳細・返信のモーダルを実ブラウザで開いて確かめる（未取得）。
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

#### 4.5.1 ONにする前の署名・受理（2026-11-17、RB-NF10。全て未取得）

`deploy-runbook.md` §1.7 のNF10の行の詳細である。NF-10 は初回リリースで ON にする（3.1。2026-11-22 利用者決定）ため、下の全てが go-live の前提になる（揃うまで go-live しない）。

| # | 要るもの | 決める人・署名者 | 状態 | 正本 |
|---|---|---|---|---|
| 1 | Report Owner の署名（NF10をONにすること） | Report Owner | 未取得 | `scheduled-management-reporting/scheduler-claim-fence-plan.md` §14 |
| 2 | 確定版の配布方針（月次cronは通常は締め前に動くため定期配布はほぼ速報になる。確定版を定期運用に含めるか、手動生成・手動配布で扱うか） | Report Owner | 未決 | 同 §14.4 |
| 3 | 「12回」の数え方の確認: 同一論理runの中の、初回を含む連続失敗で、completionに至らずlease期限を超えた試行（引き継ぎ）も1回と数える（RB-NF10、review P3-6）。成功で0へ戻り、月を跨いで累積しない。単一instanceでは実行中の処理は引き継がれない。P3-5の数え方（再試行12回か失敗12回か）と合わせる | Report Owner | 未取得 | 同 §14.2・§15 |
| 3a | 生成・配布の所要時間の見込み。正常な実行が30分（lease）を超え得るなら、数え方の定義ではなく B-NF10-2（lease延長はしない）を見直す（lease更新またはより長いlease） | Report Owner（見直す場合の設計は技術責任者） | 未取得 | 同 §14.2 |
| 4 | alert rule の閾値・評価窓・重要度・通知経路の設定。`RETRY_EXHAUSTED_DISABLED` の業務側の宛先と受けた後の対応手順 | SRE（設定）、Report Owner（業務側の宛先と対応） | 未決（§10.6はプレースホルダの草案だけ） | 同 §10.6 |
| 5 | NTP同期の前提と、許容する本node・DBの時刻差（gauge `ses_report_schedule_clock_skew_seconds` の閾値。WARNのコード既定5秒は決定ではない） | SRE | 未決 | 同 §11（P2-2） |
| 6 | tenant timezone を変えるときの方針（再計算・移行手順を用意するか、変更を止めるか。決まるまでの暫定の扱いを含む） | Report Owner（運用手順はSREと合意） | 未決 | 同 §11（P2-3） |
| 7 | 排水・検出SQL・復旧の手順草案の承認 | SRE／DBA | 未取得 | 同 §10.1〜§10.4 |
| 8 | 新旧versionを同時に動かさない（rolling禁止） | SRE | 配備の前提（`deploy-runbook.md` SRE-5） | 同 §10.1 |

### 4.6 NF-03

- 資格期限通知は実行日を基準に閾値を判定するため、OFF期間中に到来した閾値の通知はON後に送られない場合がある。

#### 4.6.1 資格番号の子スイッチ（`nf03-certification-pii`）

- 初回リリースでは ON にする（3.1・3.4。2026-11-22 利用者決定。B-NF03-1 の「初回は OFF」を変更した）。DG-07 の承認と、証憑文書についての Privacy の確認（下の4つ目）は go-live の前提（**未取得**）。
- OFF中も、資格の申請・取得日・有効期限・証憑upload・HR確認（verify/reject）・訂正・取消・更新、学習計画、skill gapは従来どおり使える。止めるのは**資格番号の入力だけ**である。
- 資格番号の入力経路は本人の申請と再申請の2つだけ（管理側API・CSV取込・他画面に資格番号の入力経路は無い。入口の一覧は `.kiro/specs/certification-learning-skill-gap/first-release-scope-confirmation.md` 2.1）。OFF中にAPIへ資格番号を付けて送ると、書込み・lock・暗号化の前に400で拒否する（黙って捨てない）。本人scope確認の読取りと再申請のversion照合は先に行うため、version不一致の再申請は409が先に返る。400の message key `certification.record.numberEntryDisabled` は4つのbundleに登録済みで、資格番号を空欄にして申請するよう案内する文言が表示される（正常な画面は資格番号を送らないため、直接API呼出しか、子スイッチON時に開いた古い画面だけが該当する）。
- 新規構築でOFFのまま運用した期間は資格番号の暗号文が作られないため、一覧・詳細は「未登録」表示になる。
- 証憑（資格証の画像・PDF）は資格番号や氏名を含み得るが、`CERTIFICATION_EVIDENCE` 文書として既存の文書台帳・retention/legal hold・scanに従う（本スイッチの対象外）。Privacyの確認事項として残す。
- ONにするには `FEATURE_NF03_ENABLED=true` と `FEATURE_NF03_CERTIFICATION_PII_ENABLED=true` の両方が必要。OFFへ戻しても既に保存した暗号文は削除されない（表示はmaskのまま、`certification.pii.view` 権限者は従来どおり復号表示できる）。

### 4.7 NF-07（W3-14b: 決定ID束縛と開放の条件。機能の開放 FE-C01〜C06 の後）

計画: `.kiro/specs/privacy-retention-dsar/production-bridge-phased-plan.md` §3.1.1・§5.1・§5.2・§7。初回リリースでは保持・保全（hold の解除と policy の登録・有効化を含む。trust 文書には両方の anchor を入れる）と本人請求（DSAR）の両方を ON にする（3.1・3.4。2026-11-22 利用者決定）。go-live の前提は `deploy-runbook.md` §1.7 の NF07 の2行（Journal（同 §3.6）と trust（同 §4.6）を含む。どれも**未取得**）。

#### 4.7.1 OFFのときの応答（正確な説明）

- 登録済みの入口（W3-14 の `/api/privacy/policies/**`、F2 の `/api/privacy/catalog`）は `FeatureSwitchFilter` と service 層の `PrivacyAccessGuard` が同じ本文の404にし、policy source を読まない。
- 遮断表に無い `/api/privacy/<x>` には handler が無く `PrivacyAccessGuard` も通らない。応答は、管理者以外のroleが403（`SecurityConfig`）、privacy-admin 以外の管理者が403（`MenuPermissionFilter` の action 層）、privacy-admin が Spring の404である（「guard が404にする」という以前の説明は誤り）。入口を追加する batch は必ず `Feature.NF07_RETENTION`（DSAR は A2a の `NF07_DSAR`）へ prefix を登録し、controller から guard を通す。
- flag に関係なく、prodの起動時には `ProductionSecurityConfigurationValidator` が「`'*'` を持つ group（privacy-admin 以外）に `privacy.*` の拒否行がある」ことを常に検査する（deploy-runbook.md）。

#### 4.7.2 開放の条件（画面「機能の開放」の評価。prod でも起動は止めない）

機能の開放（feature-enablement-console。FE-C05）の後、prod の NF07 保持・保全（`NF07_RETENTION`）と本人請求（`NF07_DSAR`）は DB の開放状態で開閉する（`PRIVACY_RETENTION_ENABLED`・`PRIVACY_DSAR_ENABLED` は prod では使わない。DB 管理にした開放単位の旧い変数として `FeatureEnablementProductionValidator` が起動を拒否する）。`PrivacyProductionConfigurationValidator` は次を**開放単位の条件の評価器**として検査し（申請・承認・起動時の評価・定期の評価）、満たさなければ開けない・開いていれば自動閉鎖する。起動時の `validate` は開いている開放単位の条件を検査して WARN `NF07_PRIVACY_STOPPED_AT_STARTUP`（違反の固定コード）を出すだけで、**prod でも起動を止めない**（以前は欠ければ Tomcat の port を開く前に止めた。開いている開放単位を閉じるのは起動時の評価 `FeatureEnablementStartupEvaluator`）。条件は開放単位ごとに判定し、DSAR の条件の不足は保持・保全を閉じない（D-4）。

| # | 条件（欠ければ開けない・開いていれば自動閉鎖） | 欠けたときの違反コード（例） |
|---|---|---|
| 1 | 決定ID束縛（`PrivacyDecisionBinding`。値は確認済みの組「NF07 の決定の束縛」の D-01・D-09・(D-06) と D-09 の構造化された値。`PrivacyApprovalSource`。4.7.3）に不足が無い: 承認tenantが設定tenant（`OIDC_TENANT_ID`、未設定なら `default`）と完全一致、ONのcapabilityが全て承認capabilityに含まれる、必要な決定（retention は D-01・D-09、dsar は D-01・D-06・D-09）のID・署名日・失効日が NF08 と同じ規則（形式・placeholder・ID重複・署名日より後の失効日・最長366日）を満たす、approval digest が一致 | `decisionBinding.tenantId.missing` / `decisionBinding.tenantId.mismatch` / `decisionBinding.capabilities.retention.notApproved` / `decisionBinding.d01.decisionId.placeholder` / `decisionBinding.d09.expiresOn.tooLong` / `decisionBinding.decisionIds.notDistinct` / `decisionBinding.approvalDigest.mismatch` |
| 2 | `privacy-admin` group が有効・未削除で `privacy.*` を許可し、`'*'`・`privacy.` 接頭辞の拒否行を持たない | `privacyAdminGroup.notGrantingPrivacy` |
| 3 | `privacy-admin` の有効な管理者が2名以上。「有効」= 所属行が未削除、設定tenantの管理者roleで `status=1`・未削除、**OIDCでログインできる**（設定tenant・`OIDC_ISSUER_URI` の有効なOIDC providerに APPROVED の外部identity link を持つ。`ProductionSecurityConfigurationValidator` のOIDC管理者の数え方と同じ）、`'*'`・`privacy.` 接頭辞の拒否行を持つ有効なgroupに所属していない、break-glass username（`BREAK_GLASS_USERNAMES`。大文字小文字を区別しない）ではない。**アカウントロック中の管理者も数える**（下記） | `privacyAdmins.insufficient[current=1;required=2]` / `privacyAdmins.oidcProviderUnavailable` |
| 4 | `privacy-admin` 以外の有効なgroupが `privacy.` 接頭辞の許可行（`privacy.*`・`privacy.<x>`）を持たない（W3-14 review P3-2。`'*'` だけの検査では privacy-admin を複製したgroupを見落とすため） | `privacyAllow.outsidePrivacyAdmin[<group key>;…]` |
| 5 | DSAR（A2b）: §5.1 の追加前提。D-06 の決定ID束縛は表の1が DSAR に要求する。加えて D-06 の署名の構造化された値 `dueDays` が1〜366、`packageMaxAge` が1時間〜366日（§18 J-15。以前の `PRIVACY_DSAR_DUE_DAYS`・`PRIVACY_DSAR_PACKAGE_MAX_AGE`）、F2 provider が1件以上登録済み | `dsar.dueDaysUnset` / `dsar.packageMaxAgeUnset` / `dsar.providersUnregistered` |
| 6 | 保持・保全: Journal の開放の条件の結果が appender の権限で適合（`PrivacyJournalStartupVerifier.enablementResult`。申請・承認の前（lock の外）と定期の評価・reconciler の実行ごとに再検査する。間隔の下限 `app.feature-enablement.journal-reverify-interval`、既定10分）。hold の効力の結果（reader でも可）は hold gate が読み、開閉に影響されない（FE-C03） | `journal.notConfigured` / `journal.privilege.missing:pj_head.INSERT` / `journal.unreachable`（評価できない類） |

- **DSAR を開く前の人の確認（条件の評価では見ない。A2b review P3-6・D14 意見）**: (a) D-06 の `dueDays`・`packageMaxAge` は approval digest に入らないため、画面に出す D-06 の署名の構造化された値を D-06 の決定書と照合する（組の確認者が確かめる）。(b) hold を使う tenant で DSAR を ON にする前に、deploy-runbook §4.4 の受理事項 (8)（D14 の開示 package は hold で止めない）を D-05 owner と D-06 owner が受理している。(c) D-06 の redaction 規則（自動交付の field）を設定するのは、承認者による承認前の草案の確認（A2b review P2-3）が使える状態にしてから。(d) 承認者は承認の前に草案の全内容を取得できる（計画 §1 の I-10）ことを Privacy が書面で受理している。(e) SELF 交付区分（WORK_REPORT・RECEIPT は第三者の情報を含み得る）を D-06 owner が確認している。(a)〜(e) はいずれも未実施・未取得で、一覧は deploy-runbook §1.7 の NF07 本人請求の行（RB-NF07-HOLD）。
- 違反には固定の違反コード・group key・人数だけを出し、決定ID・digest・username・例外本文は出さない。照会に失敗した場合は `*.unverifiable`（評価できない類）で、一度の失敗では閉じず、評価の間隔の3倍続けば自動閉鎖する（J-10）。
- **アカウントロックは起動時の人数に影響しない**（W3-14b review P2-1）。ロックは未認証者でもlogin失敗5回で30分起こせる一時的な状態なので、起動条件にすると外部からアプリ全体の再起動を止められてしまう。ロック中の管理者は、実行時に privacy の各要求で拒否される（`SecurityContextCallerContextProvider` が要求ごとに `locked_until` を確認する）。
- **表の3の拒否行の扱いは実行時の判定と同じ**（A1H-1 delta review P3-N3・P3-N4）: 無効（`enabled = 0`）にした group の拒否行は、実行時も起動時の人数でも効かない。**拒否 group を無効にしても privacy から外れない**（外すなら privacy-admin の所属を外す）。action key は大文字小文字を区別しない（`PRIVACY.*` の拒否行も両側で拒否として効く）。`privacy.<x>` の一部だけの拒否行（例: `privacy.dsar.view`）を持つ管理者は、実行時はその action 以外を使えるが、起動時の人数には数えない（少なく数える側。人数が足りなければ起動が止まる）。
- privacy-admin が2名を下回ると、開いている NF07 の開放単位は次の評価で自動閉鎖する（起動は止めない）。管理者の継続性 guard（`AdministratorContinuityGuard`）は、保持・保全か DSAR が開いているか未決の申請がある間だけ、privacy-admin を2名未満にする利用者の管理の変更を拒否する（閉じている間は判定しない。開放状態を読めなければ全て拒否。design §14・J-17）。所属者を無効化する前に後任を割り当てる。**privacy-admin は3名（deploy-runbook.md §5.0 のOIDC管理者 C〜E 全員）を推奨する**。NF07 の申請・承認・閉鎖と、NF07 の署名・組の登録・確認・取消しの行為者も privacy-admin に限る（J-05）。
- `*.unverifiable` が続く場合は、DB接続とschemaを先に確認する（schema が artifact と一致しない場合にもこのコードになる）。

##### 4.7.2.1 署名日前・失効（時間要因）の扱い（NF-06・NF-08と同じ。2026-11-14 決定）

- 期間（署名日前 `decisionBinding.dNN.signedOn.future`・失効日以降 `decisionBinding.dNN.expiresOn.expired`）は、評価する開放単位が要る決定だけで判定する（保持・保全は D-01・D-09、DSAR は D-01・D-06・D-09。review P1-2）。D-06 の失効は DSAR だけを閉じ、保持・保全は開いたまま（D-06 の内容は D-09 の digest に入るが、組の member の状態は束縛の入力に影響しない。review P2-N1）。失効した決定を要する開放単位は、snapshot の `openUntil` で失効日から閉じ、日の始まりの評価で `AUTO_CLOSED` になる（開き直すには再承認の組を確認してから申請と二人の承認が要る。D-4）。
- 「OFFとして扱う」の範囲: `PrivacyDecisionWindowMonitor` が機能スイッチの実行時条件（`FeatureRuntimeCondition`）として `FeatureSwitches#isEnabled(NF07_RETENTION)` を false にする。したがって `FeatureSwitchFilter` の404（OFFと同じ本文・全role同じ応答）、`PrivacyAccessGuard` の404、policy source を読まないこと、menu `privacy-retention` を隠すこと、起動ログの `[機能スイッチ] 起動時の状態` が `NF07_RETENTION=OFF` になること（加えて `… は設定上ONですが、実行時条件…を満たさないため無効です` のWARN）が、全てOFFと同じになる。DSAR（A2a）は `PrivacyDsarDecisionWindowCondition` が同じ判定を `FeatureSwitches#isEnabled(NF07_DSAR)` へ掛けるため、`/privacy/dsar`・`/api/privacy/dsar` の404、menu `privacy-dsar` の非表示、期限通知の停止も同じ日から OFF と同じになる。
- **実行中に失効日を迎えた場合**: 判定は入口・metric取得のたびに JST 業務日で行う（同じ日の結果は再利用）。失効日（`expires-on`）の **JST 0:00 から**入口はOFFと同じ404になり、その日の最初の判定でWARN `NF07_PRIVACY_DECISION_STATE: NF07の決定ID束縛の状態=EXPIRED …` を1回出す。署名日前の署名では開かない（署名日が未来の署名は確認できず、署名日に自動で開くこともしない。署名日以降に組を確認し、申請と二人の承認で開く。D-8・FE-AC-004-08）。process の再起動は不要で、再承認の組（4.7.5）は確認の直後から効く（判定の再利用の key は JST の業務日・開放状態の世代・開いている capability。FE-C02）。
- 監視: metric `ses_privacy_decision_binding_state{state="VALID|EXPIRING|EXPIRED|NOT_YET_VALID|INCOMPLETE|DISABLED"}`（現在の状態だけ1）と `ses_privacy_decision_binding_days_remaining`（最も早い失効日までのJST暦日数）。失効の警告の日数（prod の DB の源では `/system-config` の `feature-enablement.expiry-warning-days`。署名の失効の通知と同じ値。dev・test の property の源では `privacy.approval.expiry-warning-days`（`PRIVACY_APPROVAL_EXPIRY_WARNING_DAYS`）。既定30日、1〜366）日前から `EXPIRING` としてWARNを日ごとに1回出す（公開は続く）。prod で `PRIVACY_APPROVAL_EXPIRY_WARNING_DAYS` を置くと旧い変数として起動しない（FE-C05 の review rb-fe-c P3-4）。`EXPIRING`・`EXPIRED`・`NOT_YET_VALID`・`INCOMPLETE` をアラート対象にする。tag に決定ID・tenant・PII は載せない。
- 失効日だけを書き換えた値は digest 不一致（構造的な不足）になるため、組の確認で 400 `bundleInconsistent` になる（4.7.3）。

#### 4.7.3 決定ID束縛の値（prod は署名の組。dev・test は property）

値は署名済み決定書の転記で、秘密値を置かない。**AI・実装者は値を登録・設定しない**（署名後に人が登録する）。

- **prod（機能の開放。FE-C01）**: 画面「機能の開放」の署名の組「NF07 の決定の束縛」（`NF07_DECISION_BINDING`）として、D-01（参照 = 決定ID、署名日・失効日）・D-09（参照 = 決定ID、構造化された値 `tenantId`・`capabilities`（`retention` / `dsar` のカンマ区切り）・`approvalDigest`）と、D-09 が `dsar` を承認する時は D-06（参照 = 決定ID、構造化された値 `dueDays`・`packageMaxAge`）を1回の要求で登録し、登録者と別の privacy-admin が組として1回で確認する（組に属する種別は1件ずつ確認できない）。確認の時に組の D-01・(D-06)・D-09 から下の正規形で計算した digest が D-09 の `approvalDigest` と一致し、tenant が設定 tenant と一致し、D-06 の有無が `dsar` の承認と合うことを確かめ、合わなければ 400 `bundleInconsistent`（何も変えない）。D-02・D-03・D-05 は組に属さず、1件ずつ登録・確認する（副入口の条件）。値は呼出しごとに確認済みの組・署名から読む（`PrivacyApprovalSource`）ため、差替えに再起動は要らない。prod で下の `PRIVACY_APPROVAL_*`（`PRIVACY_APPROVAL_EXPIRY_WARNING_DAYS` を含む。警告の日数は `/system-config` の `feature-enablement.expiry-warning-days` で設定する）・`PRIVACY_DSAR_DUE_DAYS`・`PRIVACY_DSAR_PACKAGE_MAX_AGE` を配備の設定に置くと起動しない（旧い変数。`FeatureEnablementProductionValidator`。NF07 の2件のどちらかが DB 管理なら拒否する）。
- **dev・test（property の源）**: 次の設定キーを読む。既定は全て未設定。

| 設定キー | 環境変数 | 内容 |
|---|---|---|
| `privacy.approval.tenant-id` | `PRIVACY_APPROVAL_TENANT_ID` | 承認tenant（`OIDC_TENANT_ID` と同じ値） |
| `privacy.approval.capabilities` | `PRIVACY_APPROVAL_CAPABILITIES` | D-09 で開放を承認した capability（`retention` / `dsar` のカンマ区切り） |
| `privacy.approval.d01.decision-id` / `signed-on` / `expires-on` | `PRIVACY_APPROVAL_D01_DECISION_ID` / `_SIGNED_ON` / `_EXPIRES_ON` | D-01 正式 Privacy Owner（日付は `yyyy-MM-dd`、JST業務日。失効日当日から無効。有効期間は最長366日） |
| `privacy.approval.d06.*` | `PRIVACY_APPROVAL_D06_*` | D-06 DSAR SOP（`dsar` を承認する場合だけ必要） |
| `privacy.approval.d09.*` | `PRIVACY_APPROVAL_D09_*` | D-09 本番 flag 開放 |
| `privacy.approval.approval-digest` | `PRIVACY_APPROVAL_DIGEST` | 決定書（D-09）に記載された束縛内容のSHA-256（小文字hex 64桁。大文字も可） |
| `privacy.approval.expiry-warning-days` | `PRIVACY_APPROVAL_EXPIRY_WARNING_DAYS` | 失効の何日前から `EXPIRING` のWARN・metricを出すか（既定30、1〜366に丸める）。承認内容ではないため digest に含めない。dev・test の property の源だけが読む（prod の DB の源は `/system-config` の `feature-enablement.expiry-warning-days`。prod では旧い変数として起動しない） |

決定IDの規則は NF-08（`ai.external-send-approval.*`）と同じ: 英数字で始まる4〜64文字（`A-Z a-z 0-9 . _ : -`）、`TBD`・`TODO`・`PENDING`・`DRAFT`・`UNKNOWN`・`XXXX` 等の断片や `NONE`・`NULL`・`N-A` は拒否、必要な決定の間で同じIDは拒否（大文字小文字を区別しない）。規則の一致は `PrivacyDecisionBindingTest` が NF-08 との比較で固定している。

**digestの正規形**: UTF-8、1行目が版 `nf07-privacy-decision-binding-v1`、以降が `tenant-id`、`capabilities`（小文字・昇順・カンマ区切り）、承認capabilityが必要とする決定を `d01`→`d06`→`d09` の順に `dNN.decision-id` / `dNN.signed-on` / `dNN.expires-on`。全ての行をLFで終える（最終行も）。不要な決定（retention だけなら D-06）は含めない。算出例（合成値。`PrivacyDecisionBindingTest` の固定値と同じ）:

```sh
printf 'nf07-privacy-decision-binding-v1\ntenant-id=tenant-a\ncapabilities=retention\nd01.decision-id=D01-NF07-2026-0001\nd01.signed-on=2026-11-01\nd01.expires-on=2027-10-31\nd09.decision-id=D09-NF07-2026-0009\nd09.signed-on=2026-11-10\nd09.expires-on=2027-05-31\n' | sha256sum
# a4ce05f19b45525b88857b66b6b03d8d2a793f60f3805d616c5719c7c6503404
```

#### 4.7.4 開く手順（画面「機能の開放」。再起動は要らない）

1. **署名の確認**: D-01・D-09（DSAR は D-06 も）の決定書（決定ID・署名日・失効日・承認tenant・承認capability・digest、D-06 の期限規則と保存期間）が揃っていることを Owner が確認する。B-NF07-1 により、本番公開は production bridge（W-1〜W-12）の接続後である（署名 `NF07_BRIDGE`）。
2. **privacy-admin を割り当てる**（最低2名。deploy-runbook.md §5.0 のOIDC管理者 C〜E の**3名全員を推奨**。OIDCでログインできない管理者は数えられない。break-glass口座 A・B は大文字小文字の表記揺れも含めて400で拒否される）。
   - 割当は `PUT /api/permission-groups/users/{id}` で、対象者を `privacy-admin` **だけ**に割り当てる（`role-admin` の代わり。`'*'` を持つので管理権限は減らない。`role-admin` 等との同時割当は409）。対象者のsessionは失効し、再ログインが必要。
   - **最初の割当には既存の privacy-admin がいない**（W3-14 review P3-4）。この場合の通知は、対象者以外の有効な管理者全員へ送られる（`PRIVACY_ADMIN_CHANGED`）。ただし通知は事後の知らせにすぎないため、最初の2名（推奨3名）の割当は **Owner と Security の二者が対象者を事前に確認し、割当後に監査（`t_audit_log.application_code='PRIVACY_ADMIN_GROUP_CHANGED'`、`before_state`/`after_state` に group key）を二者で照合する**。G-ID の署名で割当規則が置き換わるまでの暫定規則である。
   - 確認SQL（DBA。評価器 `UserPermissionGroupMapper#selectEffectivePrivacyAdminUsernames` と同じ条件。`:tenant` に `OIDC_TENANT_ID`、`:issuer` に `OIDC_ISSUER_URI`、`:bg1`・`:bg2` に `BREAK_GLASS_USERNAMES` の2件を小文字で入れる。2以上（推奨3）であること。ロックの有無は見ない）:
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
3. **Journal の接続**（基盤の準備。保持・保全の開放の条件）: `PRIVACY_JOURNAL_*` を配備の設定に置き、appender の principal で起動する（接続は起動時に要るため、設定の追加は再起動を要する。deploy-runbook §3.6）。起動 log で `[privacy-journal] Journal の起動時検査に適合しました（hold の効力・開放の条件とも）` を確かめる。reader の principal だと WARN `NF07_PRIVACY_JOURNAL_APPENDER_UNAVAILABLE` で、保持・保全は開けない（hold の効力の確認は動く）。
4. **署名の組の登録と確認**: privacy-admin A が組「NF07 の決定の束縛」（D-01・D-09。DSAR も開くなら `capabilities=dsar,retention` の D-09 と D-06）と証拠の署名（`NF07_BRIDGE` 等）を登録し、A とは別の privacy-admin B が digest を二者で正規形（4.7.3）から作って照合してから確認する。
5. **申請と承認**: privacy-admin A が `NF07_RETENTION`（DSAR は `NF07_DSAR`）の開放を申請し、A とは別の privacy-admin B が承認する。承認の直後から入口が開く（同じ JST の日の中でも翌日を待たない。FE-AC-009-01）。全停止・再起動は要らない。
6. 確認: 画面の実効値が開、`[機能スイッチ] 状態` の log の `NF07_RETENTION=ON`。privacy-admin で `GET /api/privacy/policies/evaluate?...` が200（`decisionStatus=POLICY_UNKNOWN`。NF07-REG・TRUST の前は `APPROVED_ACTIVE` にならない）、privacy-admin 以外の管理者は403。開けない（409）時は画面の条件の code（4.7.2）を確かめる。

#### 4.7.5 失効・再承認（更新）・閉鎖

- **失効前（推奨）**: 署名の失効の接近の警告（画面・通知・WARN `FEATURE_SIGN_OFF_EXPIRING`、metric `state="EXPIRING"`）を受けたら、失効する決定の署名者から新しい決定を得て、**新しい組**を登録・確認する（D-01 を更新するなら新しい D-01 と、その digest を記載した新しい D-09。D-06 は引き継げる）。確認の直後から新しい値で判定し、開いたままになる（再起動は要らない。digest が組と合わなければ 400 で何も変えない）。新しい署名日が未来の署名は、その日まで確認できない。
- **失効後**: 失効した決定を要する開放単位は失効日（JST）から閉じ、`AUTO_CLOSED` になる。新しい組を確認してから、申請と二人の承認で開き直す（D-4）。D-06 の失効・取消しは DSAR だけを閉じる。
- **副入口の署名**: hold 開始（D-05・`NF07_D05_OWNER_ACCEPTANCE`）・REL（D-03・D-05・`NF07_TRUST_KMS`・`NF07_TRUST_STAGING`）・REG（D-01・D-02・D-03）の署名の取消し・失効は、その副入口だけを閉じる（保持・保全は開いたまま。hold 開始は 409 `error.privacy.decisionUnknown`、REL・REG は404）。入口の gate は要求ごとに確認済みの署名を読むため、新しい署名を privacy-admin の二人で確認した直後から自動で開く（D-4 の例外。REL・REG は 2026-11-23 に利用者が確認した。hold 開始は J-03 の推奨（独立 review で同意）で、利用者の確認の範囲外。review rb-fe-c P2-1）。
- **閉鎖**: privacy-admin が画面で閉じる（即時。開き直しは申請と二人の承認）。privacy-admin の割当と hold は残り、hold は閉じた後も既存の hold を守り続ける（文書の処分・purge・孤児 file の掃除の hold gate は開閉に影響されない。FE-C03）。DSAR が閉じている間に未完了の請求があれば、日の始まりに privacy-admin へ件数と最も近い期限日を通知する（期限の通知の scheduler は閉じている間止まるため）。通知の link は画面「機能の開放」で、本文は「画面『機能の開放』で開き直すか、期限の管理を手で行ってください。」（閉じている間の `/privacy/dsar` は404のため。review rb-fe-c P2-5）。
#### 4.7.6 policy の登録・有効化（NF07-REG。開放は D-01・D-02・D-03 の署名と NF07-TRUST の後）

計画: `production-bridge-phased-plan.md` §4 の NF07-REG 実装記録。保持・保全（`NF07_RETENTION`）の副入口 REG で、保持・保全を開いても下の前提が揃うまで開かない。

**初回リリースで開ける（決定済み（2026-11-22、利用者））**: trust 文書には、hold の解除の anchor と一緒に policy の登録・有効化の anchor も初回から入れる。そのため下の前提（D-01・D-02・D-03 の署名。D-02 は保存期間等の値）は go-live の前提になる（D-02・D-03 は**未取得**。`deploy-runbook.md` §1.7 の NF07 の行）。

- 入口: `POST /api/privacy/policies`（本文 `{"envelope":"<canonical bytes の base64>","signature":"<Ed25519 署名の base64>"}`。issuer が D-03 の鍵で署名した envelope をそのまま渡す。system は鍵を持たない）と `POST /api/privacy/policies/{id}/activate`（本文 `{"expectedCurrentId":<確認した current の行 ID。無ければ null>}`）、`POST /api/privacy/policies/{id}/withdraw`（本文なし。有効化されたことの無い `CANDIDATE` の取下げ）。action はどれも `privacy.policy.manage`（privacy-admin だけ）。
- **開放の前提（要求ごとに判定。揃うまで存在しない URL と同じ404で、OFF と同じ本文）**: (1) D-01・D-02・D-03 の決定ID が有効（`privacy.approval.d02.decision-id` / `signed-on` / `expires-on` = `PRIVACY_APPROVAL_D02_DECISION_ID` / `_SIGNED_ON` / `_EXPIRES_ON`、D-03 は `PRIVACY_APPROVAL_D03_*`。`application.yml` の `privacy.approval.d02`・`d03`・`d05` に既定が空の placeholder がある。規則は 4.7.3 と同じ。D-02・D-03 は approval digest に入れない）、(2) prod profile、(3) trust anchor が1件以上登録され、失効 provider がそのうち1件以上を ACTIVE と答える（key が `authorityId:keyId` と違う anchor・Ed25519 以外の鍵の anchor・判定の時刻に有効期間外の anchor・授権 tenant（`OIDC_TENANT_ID`）を範囲に含まない anchor は数えない。anchor と失効状態は判定のたびに読むため、anchor を消せば再起動せずに閉じる）。**第1期は trust anchor registry が空で失効 provider が常に UNKNOWN のため、決定ID を設定しても開かない**（trust 文書（4.7.7）を設定するまでは開かない。D-03 方式の adapter は RB-NF07-TRUST-KMS で実装済み。deploy-runbook §1.7・§4.6）。
- 手順: privacy-admin A が登録する（`CANDIDATE`）→ A とは別の privacy-admin B が、D-02 の決定書に記載された envelope の digest と一覧・詳細の digest（`envelopeDigest`）を照合し（一覧・詳細は envelope の法的な値を返さないため、B が確かめられる内容は policy ID・revision・digest）、現在の current の行 ID を `expectedCurrentId` にして有効化する（`CURRENT`。旧 current は同じ transaction で `SUPERSEDED`）→ `GET /api/privacy/policies/evaluate` で `APPROVED_ACTIVE` と digest を確認する。A 自身の有効化は403。
- revision の順序: current があるとき、有効化する envelope の発行日時（署名の範囲内）が current の envelope の発行日時より後でなければ409 `policyRevisionRollback`（同じ時刻も拒否）。revision の文字列は比較しない。古い内容に戻すには、内容を新しい revision として issuer が署名し直して登録する（D-02 owner が別の運用を望む場合は再検討する）。
- 取下げ: 誤って登録した `CANDIDATE` は、登録者本人か別の privacy-admin が取り下げる（`REVOKED`。`activated_by` は空のまま）。取り下げた行は同じ対象の別 policy ID の登録を409で止めなくなる。取り下げた revision は再利用できない（新しい revision で登録し直す）。`CURRENT`・`SUPERSEDED` は取り下げられない（409 `policyNotCandidate`。current を止めるのは失効 provider の `REVOKED`）。
- 応答: 400 `policyInvalidRequest`（base64・長さ・ID の文字種）、403 `policyEnvelopeRejected`（trust anchor 不明・署名不正・失効状態が ACTIVE でない・期間外・envelope の申請者と承認者が同じ等。理由 code は監査にだけ残る）、403 `policyActivationSeparation`、409 `policyDataElementUnknown`・`policyKeyConflict`（同じ対象に別の policy ID）・`policyRevisionConflict`（同じ policy・revision の別内容）・`policyNotCandidate`・`policyCurrentChanged`（確認した current と違う。再読込して確認し直す）・`policyRevisionRollback`（current より前に署名された revision）・`policyConcurrentUpdate`（並行の有効化・登録）。同じ内容の再送は元の結果（`replayed=true`）。
- 監査: `t_audit_log.method='PRIV_POL'`、`application_code` が `NF07_POLICY_REGISTERED` / `ACTIVATED` / `SUPERSEDED` / `WITHDRAWN` / `REJECTED`。本文は行 ID・policy ID・revision・digest の先頭・authority・key・理由 code だけで、envelope の bytes・署名・法的な値は入れない。
- 有効化された行の `REVOKED` への遷移と、処分を含む policy の D-04 の確認は本 batch に無い（失効は失効 provider、D-04 は B1 の処分の入口が確かめる。`APPROVED_ACTIVE` は処分の承認ではない）。

#### 4.7.7 trust（D-03 = AWS KMS 非対称署名。NF07-TRUST）

計画: `production-bridge-phased-plan.md` §4 の NF07-TRUST 行と実装記録。手順・文書の形式・署名の手順の正本は deploy-runbook §4.6。D-03 の方式は決まった（2026-11-18）が、**D-03 の署名は未取得**で、実装は開放ではない。

- 選択: `PRIVACY_TRUST_KMS_ANCHOR_FILE`（`privacy.trust.kms.anchor-file`）が空（既定）なら trust は第1期と同じ（anchor 0件・失効状態は常に UNKNOWN、policy の登録・有効化と hold 解除の入口は404、説明照会は `APPROVED_ACTIVE` にならない）。設定すると `KmsPrivacyTrustSource` がその file の trust 文書を判定のたびに読む（最終更新日時・大きさが変われば次の判定で読み直す。変わらなくても `PRIVACY_TRUST_KMS_RELOAD_INTERVAL`、既定30秒で読み直す）。application は AWS を呼ばない。文書は EFS の volume に置き、application は読取り専用で mount する（2026-11-18 利用者決定。mount の path の正本は deploy-runbook §1.6 の 11。NFS の属性 cache による失効の反映の遅れと Fargate の mount option は deploy-runbook §4.6 の SRE 確認項目）。EFS に届かないときも、trust の判定は1回あたり最長0.3秒しか待たず、最後に file を確かめた時刻から `RELOAD_INTERVAL` の内は直前の内容、外は閉じる（anchor 0件・UNKNOWN）。閉じるまでは最長でおよそ `RELOAD_INTERVAL` の2倍（既定で約60秒）の見込み。
- 失効状態: 文書が読めて鮮度の上限（`PRIVACY_TRUST_KMS_MAX_DOCUMENT_AGE`、5分〜31日。既定なし）の内にあり、鍵（`authorityId:keyId`）・envelope の参照が文書に `ACTIVE` と書かれているときだけ ACTIVE。文書に無い・読めない・壊れた・古すぎる・作成日時が未来・鮮度の上限が未設定なら UNKNOWN で、読めない・壊れた・古すぎる・作成日時が未来・鮮度の上限が未設定のときは anchor も0件にする。鍵・envelope を `REVOKED` にした文書を置けば、次の照会から `POLICY_REVOKED`（再起動は要らない）。
- 名前空間: envelope の `revocationReference` は `:` を含めない。`:` を含む参照は鍵の ID と区別できないため、文書は受け付けず、本番の verifier は失効状態を UNKNOWN として拒否する（anchor ID と同じ参照の envelope が鍵の状態で ACTIVE にならない）。
- 署名: 本番の verifier は policy envelope の canonical bytes の digest 声明（100 bytes）への純粋な Ed25519 署名だけを受け付ける（canonical bytes そのもの・Ed25519ph・digest だけの署名は拒否）。hold 解除の claim は §4.5 の「署名の対象」をそのまま署名する。
- anchor の `tenants` には `OIDC_TENANT_ID`（未設定なら `default`）を含める（含めないと gate が開かない）。
- 起動時（prod）の扱い（FE-C04）: 機能の開閉に関係なく、trust 文書を設定した起動では常に検査する。設定・文書の**構造的な不足**（path が絶対でない、鮮度の上限が未設定・解釈できない・範囲外、再読込の間隔が解釈できない・範囲外、file が読めない・1MiB 超、形式の違反: 未知の field、KMS の key spec・署名算法の違い（Ed25519ph を含む）、Ed25519 以外の公開鍵、alias の ARN、anchor ID・KMS key・公開鍵の重複、`:` を含む envelope の参照等）は `PrivacyTrustStartupValidator` が固定の違反コード（`trust.kms.*`）を ERROR `NF07_TRUST_STRUCTURE_INVALID` で出し、**起動は止めない**。その間、副入口 REL（hold 解除）と REG（policy の登録・有効化）は404のまま閉じ（`PrivacyTrustStructureState`）、保持・保全の開放単位と hold の効力は閉じない。構造の不足は配備の設定・文書を直して再起動すれば直る。副入口の成否の変化は履歴 `SUBGATE_CLOSED`・`SUBGATE_OPENED` と通知に出る（技術の条件が戻れば自動で開く。D-4 の例外で、2026-11-23 に利用者が確認した）。**時間要因**（鮮度の上限を過ぎた・作成日時が未来・有効期間内で ACTIVE の anchor が無い）だけなら WARN `NF07_TRUST_NOT_READY_AT_STARTUP` を出して起動し、入口は閉じたまま（文書を置き直せば再起動せずに開く）。理由: 構造的な不足は設定の誤りで直すまで副入口を開けられないが、機能の条件で app 全体を止めない（feature-enablement-console design §4.4）。期限と鮮度は決定の失効日と同じく運用中に出入りするため、要求ごとの判定で閉じる。時間の値は文字列で受け取るため、解釈できない値でも bind は失敗しない（review P3-6）。
- 実行中に文書が壊れた・消えた・巻き戻った（同じ process で読んだ文書より前の作成日時）・鮮度の上限を過ぎた場合は止めず、anchor 0件・UNKNOWN（fail-closed）として扱い、WARN `NF07_TRUST_DOCUMENT_STATE` を状態が変わったときに1回出す。
- 開放の gate（4.7.6 の (3)、hold 解除の組）は、verifier と同じ clock で有効期間外の anchor を除き、授権 tenant を範囲に含む anchor だけを数える（RB-NF07-REG delta review P3-1）。唯一の anchor・組が期限切れ・tenant 外なら開かない。

### 4.8 NF-06（W3-12: 取込センター画面とONの前提）

正本: `.kiro/specs/data-migration-import-center/a1a-ingress-operations.md`（§10 admission、§11 検証・preview・error export、§13 画面）。初回の起動（bootstrap）は OFF で行い、初回リリース（go-live）では ON にする（3.1。2026-11-22 利用者決定。4.8.2 は go-live の前提で、**未取得**）。ONにしても apply 系（apply・restart・rollback・compensation）は含まない（B-NF06-3）。

#### 4.8.1 OFFのときの応答

- 画面 `/import-center` とAPI `/api/import-center/**` は管理者を含め404になり、menu `import-center` はサイドバーから消える。
- 照合台帳の回収batch（`ImportStageReconcileScheduler`）はOFFでも止めない（2章）。
- apply 系の入口はスイッチと無関係に閉じている（route不在・`ActionPermissionResolver` のnull解決・admission port）。

#### 4.8.2 ONにする前の確認（全て満たすこと）

| # | 項目 | 確認方法 | 満たさない場合 |
|---|---|---|---|
| 1 | admission 承認設定（`IMPORT_ADMISSION_*` の6項目）が署名済み Data Owner 決定書の転記で、承認 digest が一致する | a1a §10 の正規形から二者がそれぞれ digest を再計算し、決定書の値と照合する。source code は小文字化して **ASCII 順（`LC_ALL=C sort`）** に並べる（locale 依存の並べ方では一致しない）。手順の確認には下の golden vector を使い、二者とも同じ値が出ることを先に確かめる | 構造的な不備は起動失敗（`ImportAdmissionStartupValidator`）。署名日前・失効だけなら起動するが upload は403 |
| 2 | 行参照の HMAC 鍵 `IMPORT_ROW_REFERENCE_HMAC_KEY` を secret store から注入する（Base64、復号後32 byte以上。例: `openssl rand -base64 32`）。`application.yml` の開発用既定値と `application-test.yml` のtest用の値は使えない（復号後のbyte列で比べるため、`=` の省略やURL-safe表記にしても拒否される）。全instanceで同じ値にする | 起動ログ `[取込] NF-06行参照鍵を確認しました: nf06Enabled=true` | 起動失敗（`ImportRowReferenceKeyStartupValidator`。messageは `state=MISSING` / `INVALID` / `DEVELOPMENT_KEY_IN_PRODUCTION` だけで、鍵の値は出さない） |
| 3 | `import.metadata.manage` の明示許可（V193）: 管理者専用group `role-admin`・`privacy-admin` に許可行があり、他の全groupに拒否行がある | `deploy-runbook.md` §3.4 の V193 照会 | 管理者でも取込の全操作が403（service層は全体baseline `'*'` を許可根拠に数えない。W3-05 review P2-3(a)） |
| 4 | 取込を行う管理者が `role-admin` または `privacy-admin` に所属し、`*`・`import.*`・`import.metadata.manage` の拒否行を持つgroupへ所属していない | `SELECT g.group_key FROM t_user_permission_group u JOIN m_permission_group g ON g.id = u.group_id WHERE u.user_id = ? AND u.deleted_flag = 0;` | 403（拒否が許可より優先） |
| 5 | **単一法人前提**: 論理削除されていない組織（`deleted_flag = 0`）が持つ法人IDが、ちょうど1種類である。判定は `LegalEntityContextService` と同じ条件で、組織の状態（status）と有効期間は見ない（無効・期間外の組織の法人IDも数える）。**新規導入では V60 の移行組織 LEGACY（法人ID NULL）しか無いため0件**で、ONの前に法人IDを持つ組織を登録する | `SELECT COUNT(DISTINCT legal_entity_id) FROM m_organization_unit WHERE legal_entity_id IS NOT NULL AND deleted_flag = 0;` が 1 | 0件でも2件以上でも、管理者の法人contextが決まらず取込の全操作が403（`LEGAL_ENTITY_CONTEXT_REQUIRED`） |
| 6 | LB / reverse proxy の idle timeout が130秒以上である（検証は同期要求で、最長120秒＋αの間1 byteも返さない） | SREが LB 設定を確認する | 応答が504等で途切れる。画面は `GET /jobs/{id}` で状態の確認を続けるため結果は失われないが、利用者が「開く」で確認し直す場面が増える |
| 7 | 透過再接続・接続先の切替えをしない: JDBC URL の `autoReconnect=true` / `autoReconnectForPools=true` 禁止（百分率符号化した値も）、**multi-host・failover・loadbalance・replication 形の JDBC URL 禁止**（`h1,h2`・`[h1,h2]`・`address=(...)` / `(host=...)`・`jdbc:mysql:loadbalance://`・`jdbc:mysql:replication://`・`jdbc:mysql+srv://`・`jdbc:mysqlx://`、MariaDB の `failover`・`sequential`・`loadbalance`・`replication`・`aurora`。DB の切替えは DNS・LB 側で行い、URL には単一 host の `jdbc:mysql://host:port/db` だけを書く）、**data-source-properties で接続の型・接続先・切替えを変える key を置かない**（`dnsSrv`・`host`・`port`・`failOverReadOnly`・`ha.loadBalanceStrategy`・`loadBalance*` 等。一覧は a1a §5）、**URL の query にも切替え系の key（`failOverReadOnly`・`maxReconnects` 等）と `useConfigs`（同梱の `clusterBase`・`coldFusion` は autoReconnect を有効にする）・`propertiesTransform` を書かない**（driver が展開した後の有効な属性も検査する。RB-HYG）、透過再接続 proxy（ProxySQL・MySQL Router 等）無し、connection pool は Hikari（`spring.datasource.type` を変えない） | a1a §5、`ImportJdbcReconnectGuard`（スイッチと関係なく常に検査する） | `autoReconnect` と複数 host の URL、JDBC driver（Connector/J）が単一接続と解析しない・解析できない URL、接続の型・接続先・切替えを変える data-source-properties の key は起動失敗（property 名と `(multiHost)` / `(unparsable)` / `(connectionTopology)` / `(effectiveAutoReconnect)` の印だけを出す。URL の解析には driver へ渡る data-source-properties も渡し、driver が `useConfigs`・`propertiesTransform` を展開した後の有効な属性で autoReconnect 系と切替え系の key も判定する）。proxy と pool の種類は DBA/SRE が確認する。staging で本番と同じ形式の `DB_URL` で起動し、guard が通ることを一度確かめる |
| 8 | raw CSV（`IMPORT` 文書）の保持期間と、previewのマスク分類（外部キー・会社名・メールdomainを平文で表示）を Privacy が書面で受容している | 決定記録 | ―（運用判断。W3-05 review P3-8） |
| 9 | 単一instance、または sticky session で運用する（照合台帳は instance local） | a1a §6 | 他instanceの台帳が次にlockを取るまで残る |
| 10 | **全 instance が同じアプリ DB account（同じ user 名）を使う**。Secrets Manager の alternating-users 方式（2つの user を交互に切り替える rotation）は使わない（password だけを替える single-user rotation は可）。PROCESS 権限は渡さない（RB-NF05 review P3-2） | DBA が account と rotation の方式を確認する（`deploy-runbook.md` §1.2。**未取得**） | 照合は結果不明の COMMIT を送った接続を `information_schema.PROCESSLIST` で確かめるが、PROCESS 権限の無い account には同じ user 名の thread しか見えない。別 user の接続は「実行していない」と見え、COMMIT の確定を待たずに時間だけで判定する（a1a §6） |

- **#1 の golden vector**（合成値。`ImportAdmissionPortTest` の固定値と同じ。RB-NF05、W3-05 review P3-7）: 決定ID `DO-NF06-2026-001`、tenant `default`、署名日 `2026-12-01`、失効日 `2027-11-30`、source code `CRM_2`・`crm-2`・`Crm`・`crm2`・`crm` の場合。

  ```sh
  printf 'nf06-import-admission-approval-v1\ndecision-id=DO-NF06-2026-001\ntenant-id=default\nsigned-on=2026-12-01\nexpires-on=2027-11-30\nentity=CUSTOMER\nschema-version=ses-import-v1\nsource-codes=crm,crm-2,crm2,crm_2\n' | sha256sum
  # c5dc5f03efdb4fd22c23cdf86c7d248f22af96d187880a87f07664a97b173550
  ```

  source code の並びは `crm,crm-2,crm2,crm_2`（`-` ＜数字＜`_`）。`printf 'crm\ncrm-2\ncrm2\ncrm_2\n' | LC_ALL=C sort` で同じ順になることを確かめる（`en_US.UTF-8` 等では `crm,crm_2,crm2,crm-2` になり digest が一致しない）。
- **エラーCSVの最終行 `#END` の扱い**（RB-NF05 review P3-7）: 全 page を書き終えたCSVの最後の行は `#END,<code行の件数>,<REJECTED行の件数>` で、**データ行ではない**。列の位置は header と同じ3列のため、件数の2つが `sourceRowHash` 列と `code` 列に入る。
  - Excel で filter・並べ替え・pivot を使う前に、先頭の cell が `#END` の行を削除する（残すと code が数字の行として数えられる）。
  - script で集計する場合は、最終行が `#END` で始まることと、件数が本文の行数（code 行の数と、異なる行番号の数）と一致することを確かめてから、その行を除いて処理する。最終行が無い・件数が合わない CSV は途中で切れているため使わない（もう一度出力する）。
  - 行番号は `sourceRowNumber` 列の数字だけ（`#` で始まらない）なので、`#END` の行は先頭の cell で見分けられる。
- 単一法人前提の理由（W3-05 review P2-3(b)を本バッチでは前提の明記で扱う）: 管理者の法人contextは共通の `LegalEntityContextService`（書込み境界の全writerが使う）が `m_organization_unit` の全法人IDから一意に決める。同表の `tenant_id` は将来用の BIGINT 列で、現行は独立DB運用のため値を持たず、tenantで絞っても結果は変わらない。複数法人の企業で取込を使うには、管理者に法人を選ばせる設計変更（共通serviceの変更）が必要で、Owner判断とする。
- 法人が2つ以上ある環境でONにしても、情報が漏れる方向には働かない（全操作が403で閉じる）。

#### 4.8.3 ONにする手順（`FEATURE_NF06_ENABLED`）

1. 4.8.2 の1〜10を確認する。
2. `FEATURE_NF06_ENABLED=true`、`IMPORT_ROW_REFERENCE_HMAC_KEY`、`IMPORT_ADMISSION_*` を全instanceに同じ値で設定し、全停止で再起動する。
3. 起動ログで `[機能スイッチ] 起動時の状態` の `NF06=ON`、`[取込] NF-06 admission承認設定を確認しました`、`[取込] NF-06行参照鍵を確認しました` を確認する。
4. 管理者でログインし、サイドバーの「取込センター」を開く。「取込元・mapping」タブで承認済み source code の取込元と mapping 版を登録し、合成CSVで upload → 検証 → preview → エラーCSV を確認する。エラーCSVの最終行が `#END,<code行の件数>,<REJECTED行の件数>` で、件数が本文の行数と一致することを確かめる（この行が無いCSVは途中で切れている。a1a §11）。
5. 管理者以外のroleではmenuが出ず、`/import-center` が403になることを確認する。

#### 4.8.4 OFF・鍵の差替え

- 問題時は `FEATURE_NF06_ENABLED` を外して再起動する（OFFでは admission と行参照鍵の検査で起動を止めない）。
- 行参照の鍵を差し替えると、previewとエラーCSVの行参照は全て変わり、以前に出力したエラーCSVとは照合できなくなる。保存済みの検証行（内部の SHA-256）は変わらないため、再検証は不要。

### 4.9 NF-01の子: 二者確認（`nf01-dual-confirmation`、W3-21a）

二者確認は提出・確認の業務comment（`t_lifecycle_dual_submission.business_comment`・`t_lifecycle_dual_confirmation.decision_comment`）を追記専用表（V195。trigger と `AppendOnlyMapper` で更新・削除不可）へ保存する。これらの保存期間と削除・匿名化の経路はPrivacyの署名で決まる（NF01細粒度epoch計画 §10・§12 O-8）。実装は署名前に出荷し、署名までは子スイッチをOFFのままにする。初回リリースでは ON にする（3.1。2026-11-22 利用者決定）ため、4.9.2 の条件と `deploy-runbook.md` §1.7.1 の手動確認は go-live の前提になる（**未取得**）。

#### 4.9.1 OFFのときの応答

- 親NF-01がOFF: 二者確認のAPIも親の遮断表で404（2章）。
- 親ONで子OFF: 照会・候補・提出・確認/差戻しの全routeが400 `error.lifecycle.dualConfirmationDisabled`。拒否event（`t_lifecycle_dual_event`）は記録しない。既存のタスク完了API（`/api/lifecycle/tasks/{id}/complete` 等）はDUALタスクに対して子スイッチに関係なく409のまま。
- 親ONで子OFF（テンプレート・画面、W3-21b）: DUAL_CONFIRMATIONを含むテンプレートの作成・改定は400 `error.lifecycle.dualConfirmationDisabled`（ONなら受け付け、表記揺れは `DUAL_CONFIRMATION` にそろえて保存する）。テンプレート画面の証跡種別に「二者確認」を出さない。案件詳細・本人画面は二者確認のモーダルとscript（`/js/modules/lifecycle-dual.js`）を描画せず、DUALタスクの行には「二者確認は現在無効です」とだけ出す。DUALを含むテンプレートの有効化（`toggle-status` で `ACTIVE`）も400 `error.lifecycle.dualConfirmationDisabled`（無効化はいつでも可。W3-21b review P3-1）。
- 証跡種別の許可値一覧（スイッチに関係なく常時、W3-21b review P2-1）: テンプレートの作成・改定は証跡種別を `NONE`・`COMMENT`・`SELF_DECLARATION`・`DOCUMENT_LINK`・`SYSTEM_CHECK`・`DUAL_CONFIRMATION` のいずれかに限る。前後のUnicode空白（全角空白等）を除き大文字にそろえて保存し、ASCII以外（NBSP・ゼロ幅文字・全角英字等）を含む値・空文字・一覧外の値は400 `error.lifecycle.invalidEvidenceType`（未指定はNONE）。完了経路・二者確認のAPI・画面は完全一致で判定するため、揺れた表記は見た目が同じまま二者確認やDOCUMENT_LINKの証跡必須をすり抜ける。

#### 4.9.2 ONの条件（prodでは起動時に強制）

- Privacyの署名（comment 2列の保存期間・削除/匿名化の経路・**表示範囲**）と、W3-21（W3-21a・W3-21b）のPASS。
  - 表示範囲の既定（W3-21b、最小公開。署名で確定または緩和する）: 提出commentと確認/差戻しcommentの本文は、確認者（管理者・HR）とその提出の提出者本人にだけ返す。範囲内の他の営業・マネージャーと、営業が代理提出したときの要員本人には「コメントあり」（記入の有無）だけを返す。判定はAPI（照会A1・A5）の応答側で行い、画面で隠すだけではない（`DualConfirmationCommentDisclosure`）。緩和はコード変更で行う（設定項目は無い）。
  - 署名で明記する事項（W3-21b review §3-1）: (a) 範囲は**role級**である。本文を読める確認者は「その提出を実際に確認・差戻しした人」ではなく、案件を閲覧できる全ての管理者・HRである（確認前の提出には確認者がまだいないため、人で限ると確認者が提出の説明を読めない。再提出を別のHRが確認する場合も前回の差戻し理由が要る）。(b) 営業が代理提出した場合、要員本人にはHRの差戻し理由の本文が見えない（「コメントあり」だけ）。差戻し理由は提出者の営業が要員へ伝える運用とし、HRの手順に含める（Privacy・HRの署名）。
  - 「確認待ち」の通知は案件のtenantの有効な管理者・HRとタスクの担当者だけへ個別に送る（W3-21a review P3-7）。通知に業務comment・案件title・文書titleは載せない。
- 署名の参照を `FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF`（設定キー `app.features.nf01-dual-confirmation.privacy-sign-off-ref`）へ設定する。値は英数字で始まる4〜128文字（英数字と `._:/#-`）で**英字と数字の両方を含む**review文書ID・ticket番号等（例: `PRIV-REVIEW-2026-11-20`）。placeholderとみなす値は受け付けない: `todo`・`tbd`・`xxx`・`placeholder`・`changeme`（`change-me`・`change_me`）・`dummy`・`fixme`・`sample`・`example`・`unsigned`・`replace`・`your-` を一部にでも含む値、`test`・`pending`・`tmp`・`temp`・`none`・`null`・`n/a`・`dev`・`local` で始まる値（大文字小文字を区別しない。例: `TODO-1`・`TBD2026`・`test-ref-1` は不可）。
- prod profileでは `LifecycleDualConfirmationReleaseGate` が、子スイッチの実効値がONなのに参照が無い・形式不正・placeholderの場合に起動を止める（固定コード `NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_MISSING`）。参照値は秘密情報ではないため、起動ログ `[二者確認] Privacy署名の参照を確認して公開します: privacySignOffRef=...` に出す（監査で署名と配備を突き合わせる）。
- gateは「署名を記録したこと」を強制するだけで、署名の内容は検証しない。参照の値は署名者（Privacy）が発行したものを配備担当が転記する。
- NF07 の PII inventory・data element catalog への B0 の7表と comment 2列の登録は V198 で登録済み（`m_pii_data_element` の `LIFECYCLE_DUAL_*` 9件と `privacy-retention-dsar/pii-inventory.md` の DB-141〜DB-147。provider は未登録、分類・owner・inventory 状態は UNKNOWN で、保持期間は持たない）。保存期間等の値は D-02 の署名で確定する（署名は未取得）。分類が UNKNOWN のため、NF07 hold 画面の「未分類の data element」と `PrivacyCoverageReport` の未分類件数（`unknownAttributionCount`）にもこの9件が加わり、D-02 で分類を決めるまで0にならない（回帰ではない）。ONの前に browser の手動確認9項目（`deploy-runbook.md` §1.7.1）を済ませる。ONの前提の一覧は同 §1.7。

#### 4.9.3 ONにする手順

1. `FEATURE_NF01_ENABLED=true` であることを確認する（親がOFFなら子の値は無効）。4.9.5 の確認SQLが全て0件であることを確認する。
2. `FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF` に署名の参照を、`FEATURE_NF01_DUAL_CONFIRMATION_ENABLED=true` を設定して全停止で再起動する。
3. 起動ログで `NF01=ON, NF01_DUAL_CONFIRMATION=ON` と上記の署名参照ログを確認する。
4. 監視: 再試行の予算超過（409 `error.lifecycle.concurrentUpdateRetryExhausted`、WARN `[二者確認] 再試行の予算を使い切りました`）と拒否eventの `reason_code` の発生率を確認する。`reason_code` は 400 の状態の不成立（DUAL以外・案件が ACTIVE でない・タスクが IN_PROGRESS でない）を `INVALID_STATE`、409 の世代不一致・決定済みを `SUBMISSION_STALE`、確認時に提出者の述語が成り立たないのに認可epochが変わっていない場合を `SUBMITTER_PREDICATE_LOST` として分ける（RB-NF01。actor epoch の変化 `AUTHORIZATION_CHANGED_ACTOR` と混ぜない）。提出者に見えない文書（文書ACL外・案件の要員に紐付かない・SKILL_SHEET以外・それらの削除済み）は存在しない文書と同じ400 `error.document.notFound` で、`reason_code` は `SCOPE_DENIED`・`NOT_FOUND_OR_FOREIGN`（応答では区別しない）。

#### 4.9.4 OFF

- 問題時は `FEATURE_NF01_DUAL_CONFIRMATION_ENABLED` を外して再起動する。保存済みの提出・確認・拒否eventは追記専用のため残る（OFFで消えない）。未確認の提出はON後も世代・epochの判定に従う。
- OFFに戻しても、ONの間に作ったDUAL_CONFIRMATIONを含む有効なテンプレートは残り、新しい案件へDUALタスクが展開される。OFFの間それらのタスクは提出・確認できず免除だけで進む。OFFに戻すときは、該当テンプレートを無効化するか、DUALを含まない版へ改定する（DUALを含まない改定はOFFでも受け付ける）。OFFの間はDUALを含むテンプレートを有効に戻せない（4.9.1）。残っている有効なDUALテンプレートは 4.9.5 の (3) で確かめる。

#### 4.9.5 証跡種別の確認SQL（DDLなし、W3-21b review P2-1・P3-1）

許可値一覧（4.9.1）より前に保存された行や、手作業で入った行を検出する。空DB新規導入では全て0件の想定。`CAST(... AS BINARY)` でバイト単位に比べる（照合順序 `utf8mb4_unicode_ci` は大文字小文字・一部の空白やゼロ幅文字を同一視するため、通常の比較では揺れを見落とす）。`HEX` で不可視文字を確認できる。

```sql
-- (1) テンプレートのタスク定義で、許可値一覧の外にある証跡種別
SELECT t.id AS template_id, t.template_type, t.version_no, t.status,
       tt.id AS template_task_id, tt.task_code, tt.evidence_type, HEX(tt.evidence_type) AS evidence_type_hex
  FROM m_lifecycle_template_task tt
  JOIN m_lifecycle_template t ON t.id = tt.template_id
 WHERE tt.deleted_flag = 0 AND t.deleted_flag = 0
   AND CAST(tt.evidence_type AS BINARY) NOT IN
       ('NONE', 'COMMENT', 'SELF_DECLARATION', 'DOCUMENT_LINK', 'SYSTEM_CHECK', 'DUAL_CONFIRMATION');

-- (2) 案件のタスク（テンプレートから複写された値）で、許可値一覧の外にある証跡種別
SELECT id AS task_id, case_id, task_code, status, evidence_type, HEX(evidence_type) AS evidence_type_hex
  FROM t_lifecycle_task
 WHERE deleted_flag = 0
   AND CAST(evidence_type AS BINARY) NOT IN
       ('NONE', 'COMMENT', 'SELF_DECLARATION', 'DOCUMENT_LINK', 'SYSTEM_CHECK', 'DUAL_CONFIRMATION');

-- (3) 子スイッチOFFで運用する間に有効なまま残っているDUALテンプレート（OFFの間は0件にする）
SELECT t.id AS template_id, t.template_type, t.version_no, t.valid_from, t.valid_to
  FROM m_lifecycle_template t
 WHERE t.deleted_flag = 0 AND t.status = 'ACTIVE'
   AND EXISTS (SELECT 1 FROM m_lifecycle_template_task tt
                WHERE tt.template_id = t.id AND tt.deleted_flag = 0
                  AND CAST(tt.evidence_type AS BINARY) = 'DUAL_CONFIRMATION');
```

- 検出した場合の対処: DBを手作業で更新しない（本番DBの手作業変更は禁止。NF01細粒度epoch計画 §9 RR-10）。(1) はテンプレート画面・APIで改定して正規の表記にする（改定は許可値一覧で検査・正規化される）か、無効化する。(2) は案件の担当者と確認し、証跡の要否に応じて免除（承認経由）か再起票で扱う。(3) は無効化する。
- (1)・(2) が0件でない状態で子スイッチやNF-01をONにしない（4.9.3）。

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
| `FeatureSwitchChildTemplateRenderingTest` | Docker不要 | 実templateをThymeleafで描画し、子スイッチOFFでportalの問い合わせ導線・本人画面の資格番号欄・二者確認のモーダル/script/テンプレートの選択肢が出ないこと（NF-01本体OFFでも同じ） |
| `LifecycleTemplateDualPolicyTest` | Docker不要（H2） | 二者確認の子スイッチOFF（または親NF-01 OFF）でDUAL_CONFIRMATIONを含むテンプレートの作成・改定・有効化が400で1件も書かれず、ONでは受け付けて表記揺れを正規の表記で保存する。証跡種別の許可値一覧（NBSP・ゼロ幅文字・全角英字・一覧外・空文字・30字超は400、全角空白・小文字は正規化） |
| `LifecycleTemplateDualSwitchUnavailableTest` | Docker不要 | 機能スイッチを取得できない（providerがnull・値がnull）場合、DUALを含む作成・改定・有効化を書込み前に400（fail-closed） |
| `LifecycleDualConfirmationScreenTest` / `LifecycleDualConfirmationScriptTest` | Docker不要（後者はNode必須） | 二者確認の画面: 社内詳細に提出・確認・差戻し、本人詳細に提出だけ、4言語の翻訳、画面が参照するkeyの存在、Idempotency-Keyの再利用・二重送信防止・版IDの無い提出の抑止・403/409のmessage表示・comment本文の非表示 |
| `PortalServiceRequestVisibilityPolicyTest` | Docker不要 | portal公開範囲の判定（portal起票・公開開始時刻・timezone・不正値の起動失敗・一覧条件） |
| `PortalServiceRequestVisibilityIntegrationTest` | Docker不要（H2） | 公開開始時刻未設定の実context。portalの一覧・詳細・返信・CSAT・添付uploadが社内登録分を404/非表示にし、内部サービスデスクは従来どおり |
| `FeatureSwitchServletContainerIntegrationTest` | Docker不要・実Tomcat起動（Windowsでは **PowerShell環境** で実行） | NF-01をproperty形式、NF-04を環境変数形式でOFFにした実context。未認証の画面404（login誘導されない・no-store）、管理者と未認証で同一の404 JSON、存在しないURLの404との同一性、ONの機能・中核APIはSpring Securityへ届くこと、PWA静的入口の遮断範囲、filter登録順 |
| `PrivacyDecisionBindingTest` | Docker不要 | NF-07の決定ID束縛: 正規形とdigest、各項目の欠落、capabilityごとの必要な決定、未承認capability、tenant不一致、失効・署名日前、digest改竄、ID重複、決定ID・日付の判定がNF-08と一致すること |
| `PrivacyProductionConfigurationValidatorTest` | Docker不要（H2） | NF-07 flag ONのprod起動検査: 全OFFは無検査、各欠項・tenant不一致・digest改竄・capability未承認で起動失敗、失効・署名日前は起動してWARNとOFF扱い、時間要因と構造的不足の同時は起動失敗、privacy-admin 1名／無効・削除・他role・他tenant・OIDC link無し・`'*'`/`privacy.<x>` 拒否group所属・break-glass（大文字小文字違い含む）の除外、ロック中は数える、OIDC provider無し、privacy-admin groupの許可欠落・拒否行、他groupの `privacy.` 許可行、DSAR未実装、照会失敗、messageに値を出さないこと |
| `PrivacyDecisionWindowMonitorTest` | Docker不要 | NF-07の有効期間監視: 固定・可変Clockで、有効・EXPIRING・失効・署名日前・不完全の状態とmetric・日付ごとのWARN、実行中に失効日（JST 0:00）を迎えると `FeatureSwitches`・`FeatureSwitchFilter`・`PrivacyAccessGuard` がOFFと同じ404になること、署名日に自動で開くこと、実行時条件の例外はOFF |
| `FeatureSwitchRuntimeConditionTest` | Docker不要 | 機能スイッチの実行時条件: 設定ONでも条件を満たさない間は `isEnabled`・`blockingFeature`・`disabledKeys`・`visibleMenuKeys` がOFFと同じ、起動ログの `NF07_RETENTION=OFF` と理由のWARN、条件の無い機能・`new` は従来どおり |
| `PrivacyProductionConfigurationTest` | Docker不要 | prod の policy source はスイッチONかつ決定ID束縛の有効期間内だけ読む（OFFなら束縛を見ない、判定部品が無い・例外なら読まない） |
| `ImportCenterPageControllerTest` | Docker不要（H2） | NF-06 ONの実context。管理者は取込センター画面とサイドバー導線（menu `import-center`、V193相当のH2 seed）を表示でき、管理者以外のroleは403、未認証はlogin誘導 |
| `ImportCenterPageFeatureOffTest` | Docker不要（H2） | NF-06 OFFの実context。管理者でも画面404（統一エラーページ）・APIは404 JSON |
| `ImportSourceRowReferenceTest` | Docker不要 | 行参照のHMAC（domain・tenant・job・保存hash）、鍵の形式検査とprodでの開発用鍵の拒否、NF-06 ONのprod起動検査（値をmessageへ出さない） |
| `FlywayNf06ImportCenterMenuMigrationTest` | Docker必須（mysql shard 3） | V193 を実MySQLで適用し、menu・明示許可・拒否の最終状態と既存行の補正を確認する |
