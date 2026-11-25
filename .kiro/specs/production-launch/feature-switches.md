# 機能の開放・機能スイッチ運用手順（全13開放単位: NF-01 / NF-02 / NF-03 / NF-04 / NF-05 / NF-06 / NF-07 / NF-10、子スイッチ3件を含む）

- 対象: S-NF 機能として公開可否を制御する全 13 件の開放単位（`NF01`・`NF01_DUAL_CONFIRMATION`・`NF02`・`NF02_CUSTOMER_PORTAL`・`NF03`・`NF03_CERTIFICATION_PII`・`NF04`・`NF05_INBOUND`・`NF05_OUTBOUND`・`NF06`・`NF07_RETENTION`・`NF07_DSAR`・`NF10`）。
- 位置づけ: **権限ではなく公開可否のゲート**。閉（OFF）の機能は管理者を含む全ロール・未認証利用者から 404 で遮断される。
- 実装: `com.ses.featureenablement`（`FeatureEnablementCatalog`・`DatabaseFeatureEnablementStateSource`・`FeatureEnablementService`・`FeatureSignOffService`・V203 の 9 表・画面 `/feature-enablement`・API `/api/feature-enablement/**`）および `com.ses.config.feature`（`Feature`・`FeatureSwitchProperties`・`FeatureSwitches`・`FeatureSwitchFilter`）。設計の正本は `.kiro/specs/feature-enablement-console/design.md`。
- **環境の源（`app.feature-enablement.source`）による違い**:
  - **prod（`source: database` 固定。他の値では起動しない）**: 全 13 件の開放単位は DB の開放状態（初期は行なし＝`CLOSED`）で出荷し、**画面「機能の開放」（`/feature-enablement`）での署名・受理の二人確認と、開放申請＋別の管理者の承認**によって機能ごとに開く（閉じる操作は管理者 1 名で即時）。旧い開閉の環境変数（`FEATURE_NF01_ENABLED`・`PRIVACY_RETENTION_ENABLED`・`INTEGRATION_HUB_PUBLIC_API_ENABLED` 等）や、署名・設定へ移した承認の値（`FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF`・`FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE`・`IMPORT_ADMISSION_*`・`PRIVACY_APPROVAL_*`・`PRIVACY_DSAR_DUE_DAYS`・`PRIVACY_DSAR_PACKAGE_MAX_AGE`・`INTEGRATION_HUB_*_DECISION_ID`・`*_APPROVAL_DIGEST`）を配備の設定（`environment`・`secrets`・`SPRING_APPLICATION_JSON`・system property）に置くと、値に依らず `FeatureEnablementProductionValidator` が起動を中止し、task definition 照合 `validate-task-definition.ps1` も両 phase で FAIL にする（FE-E01・FE-E02）。配備の環境変数に残るスイッチは緊急の総スイッチ `FEATURE_ENABLEMENT_ALLOWED`（既定 `true`）だけである。
  - **dev・test（既定 `source: properties`）**: 開発と単体・統合試験のため、従来どおり `application.yml`（全て `false`）・`application-test.yml`（NF-05 を除き `true`）の property / 環境変数から読む。ローカルや試験で画面 `/feature-enablement` の DB 管理を動かす場合は `app.feature-enablement.source=database`（`APP_FEATURE_ENABLEMENT_SOURCE=database`。prod では設定禁止）を指定する。
- 対象外: **NF-08**（B2c-2 の完了と独立 Review まで `AiProductionApprovalGate` が prod の起動を拒否し続けるため、目録に含めない）、**NF-09**（常時有効）。
- **NF-07** は保持・保全（`NF07_RETENTION`。副入口 `HOLD_START`・`REL`・`REG` を持つ）と本人請求（`NF07_DSAR`）を親子にしない 2 つの最上位の開放単位として扱う。
- **NF-06** は開放単位の公開可否に加えて本番 admission port（Q-5、`.kiro/specs/data-migration-import-center/a1a-ingress-operations.md` §10）でも制御する。apply 系は開閉と無関係に閉じている（同 §1）。

## 1. 開放単位・スイッチ一覧

| 開放単位（`Feature`） | 親 | prod の開閉と再起動（`source=database`） | dev・test の設定キー / 環境変数（`source=properties`。**prod では設定禁止**） | 既定値（prod / dev / test） |
|---|---|---|---|---|
| `NF01` ライフサイクル管理・退社ゲート | — | `/feature-enablement`（申請＋承認で即時開、1名で即時閉） | `app.features.nf01.enabled` / `FEATURE_NF01_ENABLED` | `CLOSED` / `false` / `true` |
| `NF01_DUAL_CONFIRMATION` 二者確認の提出・確認・差戻し（4.9） | `NF01` | 同上（親 `NF01` が開の間だけ実効開） | `app.features.nf01-dual-confirmation.enabled` / `FEATURE_NF01_DUAL_CONFIRMATION_ENABLED` | `CLOSED` / `false` / `true` |
| `NF02` カスタマーサクセス・サービスデスク（内部画面。4.3） | — | 同上 | `app.features.nf02.enabled` / `FEATURE_NF02_ENABLED` | `CLOSED` / `false` / `true` |
| `NF02_CUSTOMER_PORTAL` 顧客ポータルの問い合わせ（4.3.1） | `NF02` | 同上（親 `NF02` が開の間だけ実効開。公開範囲は「設定」タブ） | `app.features.nf02-customer-portal.enabled` / `FEATURE_NF02_CUSTOMER_PORTAL_ENABLED` | `CLOSED` / `false` / `true` |
| `NF03` 資格・学習・スキルギャップ（4.6） | — | 同上 | `app.features.nf03.enabled` / `FEATURE_NF03_ENABLED` | `CLOSED` / `false` / `true` |
| `NF03_CERTIFICATION_PII` 資格番号（`CERTIFICATION_PII`）の入力（4.6.1） | `NF03` | 同上（親 `NF03` が開の間だけ実効開） | `app.features.nf03-certification-pii.enabled` / `FEATURE_NF03_CERTIFICATION_PII_ENABLED` | `CLOSED` / `false` / `true` |
| `NF04` 要員 PWA シェル（Service Worker・manifest。4.4） | — | 同上 | `app.features.nf04.enabled` / `FEATURE_NF04_ENABLED` | `CLOSED` / `false` / `true` |
| `NF05_INBOUND` 公開 API（read API・inbound webhook。4.10） | — | `/feature-enablement`（**開く変更は次の再起動で反映**、閉じる変更は即時） | `integration.hub.public-api.enabled` / `INTEGRATION_HUB_PUBLIC_API_ENABLED` | `CLOSED` / `false` / `false` |
| `NF05_OUTBOUND` 外部 webhook 送信（outbound transport。4.10） | — | 同上（**開く変更は次の再起動で反映**、閉じる変更は即時） | `integration.hub.external-transport.enabled` / `INTEGRATION_HUB_EXTERNAL_TRANSPORT_ENABLED` | `CLOSED` / `false` / `false` |
| `NF06` データ移行・取込センター（apply 系は含まない。4.8） | — | `/feature-enablement`（申請＋承認で即時開、1名で即時閉） | `app.features.nf06.enabled` / `FEATURE_NF06_ENABLED` | `CLOSED` / `false` / `true` |
| `NF07_RETENTION` 保持・保全（policy 説明・catalog・hold。副入口 3 件。4.7） | — | 同上（行為者は `privacy-admin` のみ） | `privacy.retention.enabled` / `PRIVACY_RETENTION_ENABLED` | `CLOSED` / `false` / `true` |
| `NF07_DSAR` 本人請求（DSAR。4.7） | — | 同上（行為者は `privacy-admin` のみ） | `privacy.dsar.enabled` / `PRIVACY_DSAR_ENABLED` | `CLOSED` / `false` / `true` |
| `NF10` 定期管理レポート（4.5） | — | 同上 | `app.features.nf10.enabled` / `FEATURE_NF10_ENABLED` | `CLOSED` / `false` / `true` |

- **子単位（子スイッチ）**: 親機能の一部だけを後から公開するための開放単位。**実効値は「親が開 かつ 子が開」**で、親が閉なら子の状態に関係なく閉になる。DB の源では親が閉鎖（手動閉鎖・自動閉鎖）すると、開いていた子も同じ transaction で `AUTO_CLOSED(PARENT_CLOSED)` になる（親を開き直しても子は自動では開かず、改めて二人で開く）。テンプレートで使う識別子は従来どおり kebab-case（`disabledFeatures.contains('nf02-customer-portal')` 等）。
- **顧客ポータルの問い合わせの公開範囲（`nf02-customer-portal.visible-since`）**:
  - **prod（`source=database`）**: 画面 `/feature-enablement` の「設定」タブで変更を申請し、別の管理者が「申請」タブで承認すると再起動なしで次の判定から効く（未設定＝portal 起票のみ。時刻を入れる場合は CS Owner の署名 `NF02_PORTAL_VISIBLE_SINCE_DECISION` の構造化された値 `visibleSince` と同じ値だけを申請でき、社内起票分の棚卸しの証拠 `NF02_PORTAL_INVENTORY` が確認済みであることが条件）。環境変数 `FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE` は置くと起動しない。
  - **dev・test（`source=properties`）**: `app.features.nf02-customer-portal.visible-since`（環境変数 `FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE`、offset 付き ISO-8601。test profile は既存テストのため `2000-01-01T00:00:00+09:00`）。不正な値（offset 無し等）は起動失敗になる。詳細は 4.3.1。
- **DB 状態の反映と鮮度（`source=database`）**:
  - 変更を行った node は transaction commit の直後（`afterCommit`）に snapshot を読み直すため、同じ node では承認・閉鎖の直後の要求から新しい状態になる。他 node（単一 instance 運用では予備）は世代（`t_feature_enablement_generation`）を `app.feature-enablement.refresh-interval`（既定 `5s`）ごとに確認して読み直す。
  - DB 障害等で最後の成功読取りから `app.feature-enablement.max-staleness`（既定 `60s`）を超えると、NF-05 以外の全開放単位は **fail-closed**（理由 `snapshot.stale`）で実効閉になる（DB が復旧して読み直せれば自動で元に戻る。NF-05 は起動時の読取りと runtime switch で判定する）。
- **dev・test（`source=properties`）の注意**:
  - ローカル起動（`mvn spring-boot:run`）で特定の NF 画面を簡単に試す場合は、対象の `FEATURE_NF0x_ENABLED=true` 等を設定して起動する（値を `ture` 等の解釈できない文字列にすると bind 失敗で起動しない）。
  - test profile（`src/test/resources/application-test.yml`）は既存テストの意味を保つため NF-05 以外を全て `true` にしており、OFF 時の遮断や DB 源の挙動は `com.ses.config.feature`・`com.ses.featureenablement` 配下のテストで検証する。

## 2. 閉（OFF）時に止めるもの（適用範囲）

共通の応答仕様:

- 画面・API・静的入口は Servlet filter（`FeatureSwitchFilter`、Spring Security より前。NF-05 の `/external-api/**` は `@Order(0)` の `ExternalApiSecurityConfig`）で **404** にする。
  - `/api/**` は `CustomErrorController` の 404 と同じ `ApiResult` JSON（`{"code":404,"message":"お探しのページは存在しないか、移動された可能性があります。","data":null}`）。
  - 画面・静的ファイルは `sendError(404)` で統一エラーページ（`error.html`）を表示する。
  - 認証・CSRF・`MenuPermissionFilter` より前に止めるので、管理者でも未認証でも同じ応答になる（管理者も迂回できない）。ただし画面 `/feature-enablement` と API `/api/feature-enablement/**` 自体はどの機能スイッチの対象でもなく、管理者（prod は OIDC＋MFA）が常に到達できる。
  - 遮断応答には `Cache-Control: no-store`（`Pragma`・`Expires` も同様）を付け、開放後に古い 404 が残らないようにする。Security chain を通らない API 応答にも、Spring Security 既定と同じ `X-Content-Type-Options: nosniff`・`X-Frame-Options: DENY`・`X-XSS-Protection: 0`（HTTPS 時は `Strict-Transport-Security`）を付ける。
  - `%` エンコード・`;` 付き path・連続スラッシュ等の表記ゆれ、静的ファイルの大文字小文字・末尾 `/` の違いでも照合する。
- **「存在しない URL」との見分けやすさ（正確な説明）**:
  - **認証済み利用者**: 存在しない URL の 404（例: 既存 API root 配下の未定義 path）と、status・JSON 本文・主要な security header が同じになる（`FeatureSwitchServletContainerIntegrationTest` で検証）。なお、存在しない **API root**（例: `/api/xxx`）は既存の `MenuPermissionFilter` が 403 を返すため、比較対象は「既存 root 配下の未定義 path」である。
  - **未認証利用者**: 通常の URL は Spring Security により 302（画面は login へ誘導）／401（API）になるが、閉の機能の path だけは 404 になる。**この差は残る**（HTTP Basic 認証との順序および認証不要の静的入口を確実に遮断するため、認証前に判定する方式を選んでいる）。
  - その他の残差: Security chain を通らないため、通常応答で付くことがある CSRF cookie（`XSRF-TOKEN`）の発行等は行わない。
- サイドバーは `GlobalControllerAdvice` が `allowedMenus` から対象機能の menu key を除外する（管理者も同じ）。ログイン後の着地先（`/`）も閉の機能のメニューを選ばない。
- `@Scheduled` 入口は各 scheduler の先頭で `FeatureSwitches.allows(...)` を確認し、閉なら何もせず戻る（ShedLock の注釈は変更しない。閉の間も lock 行の取得・更新だけは行われる）。

| 開放単位 | 遮断する画面・API・静的入口 | 止める scheduler・worker | 隠すメニュー・埋込み表示 |
|---|---|---|---|
| `NF01` | `/lifecycle/**`、`/my/lifecycle/**`、`/api/lifecycle/**`、`/api/my/lifecycle/**` | `LifecycleSlaScheduler#runSlaCheck`（毎朝8時） | menu `lifecycle`・`myLifecycle`、要員詳細画面のライフサイクル欄、ログイン後の着地先 |
| `NF01_DUAL_CONFIRMATION` | **URL は遮断しない**（親 `NF01` の遮断表に属する）。`/api/lifecycle/tasks/{id}/dual-confirmation/**`・`/api/my/lifecycle/tasks/{id}/dual-confirmation/**` を `LifecycleDualConfirmationCoordinator` が 400（`error.lifecycle.dualConfirmationDisabled`）で拒否する（lock・書込みの前）。`DUAL_CONFIRMATION` を含むテンプレートの作成・改定・有効化も 400 で拒否する | なし | テンプレート画面の証跡種別「二者確認」、案件詳細・本人画面の二者確認モーダルと `lifecycle-dual.js`（「二者確認は現在無効です」と表示） |
| `NF02` | `/service-desk/**`、`/customer-success/**`、`/api/service-desk/**`、`/api/customer-success/**`（親が閉なら子の入口も遮断） | `ServiceSlaScheduler#runSlaMonitoring`（5分毎）、`CustomerHealthScheduler#runDailySnapshot`（日次）、`ServiceRequestAttachmentCompensationScheduler#retryDue`（5分毎） | menu `service-desk`・`customer-success`、契約更新カレンダーの顧客ヘルス値（＋子の埋込み表示） |
| `NF02_CUSTOMER_PORTAL` | `/api/portal/customer/service-desk/**`（一覧・詳細・起票・返信・CSAT・添付 upload/download）。開の間も公開範囲（`visible-since`）外の問い合わせは同じ 404 | なし（scheduler は親 `NF02` に従う） | 顧客ポータル（`/portal/customer`）の「問い合わせ」タブ・起票/詳細/返信/CSAT モーダル・文言・script 参照（検収・契約・請求等の他タブは止めない） |
| `NF03` | `/certification-learning-skill-gap/**`、`/my/certification-learning-skill-gap/**`、`/api/certification-learning-gap/**`、`/api/my/certification-learning-gap/**` | `CertificationExpiryNotificationScheduler#dispatchToday`（日次） | menu `certification-learning-skill-gap`・`myCertificationLearningGap` |
| `NF03_CERTIFICATION_PII` | **URL は遮断しない**。資格申請・再申請で資格番号が空欄でない要求を `EngineerCertificationServiceImpl` が書込み・lock・暗号化の前に 400（`certification.record.numberEntryDisabled`）で拒否する | なし | 本人画面（`/my/certification-learning-skill-gap`）の申請モーダルの資格番号入力欄、再申請ダイアログの資格番号入力 |
| `NF04` | `/service-worker.js`、`/manifest.webmanifest` のみ | なし（4.4 参照） | manifest リンク、Service Worker 登録（`common.js` が既存登録と shell cache を解除） |
| `NF05_INBOUND` | `/external-api/**`（`ExternalApiSecurityConfig` および runtime switch が 404 JSON を返す。監査行は書かない。ただし起動時開→実行中閉の間で backup 静止中の要求だけは 503 `BACKUP_QUIESCED`。LB/WAF でも D-1 で遮断する） | 両方向が起動時から閉なら `IntegrationHubRetentionPurgeScheduler` を生成しない | —（内部管理画面は管理者専用のまま） |
| `NF05_OUTBOUND` | 外部 HTTP 送信を行わない（内部管理 API は止めない） | outbound dispatch・worker（実行中に閉じた場合は新規 dispatch を止め、処理中は `KILL_SWITCH`、確定閉鎖後の未送信行は DLQ `STARTUP_REVALIDATION_REQUIRED`） | — |
| `NF06` | `/import-center/**`、`/api/import-center/**`（upload・job 詳細・検証・preview・error export・source/mapping 管理） | なし（`ImportStageReconcileScheduler` は照合台帳回収のため閉でも止めない） | menu `import-center`（管理者のみ） |
| `NF07_RETENTION` | `/privacy/holds/**`、`/api/privacy/holds/**`、`/api/privacy/policies/**`、`/api/privacy/catalog`（filter と `PrivacyAccessGuard` が 404 にする。開でも `privacy-admin` 権限が要り、副入口 `HOLD_START`・`REL`・`REG` は各副入口の署名が揃うまで閉じる） | なし（破壊的経路 D1〜D10・D17 の hold gate と reconciler は閉でも動き、既存の hold を守る） | menu `privacy-retention` |
| `NF07_DSAR` | `/privacy/dsar/**`、`/api/privacy/dsar/**`（filter と `PrivacyAccessGuard` が 404） | `PrivacyDsarDeadlineScheduler`（通常の期限通知は止まり、代わりに未完了請求があれば日次で `FeatureEnablementEvaluationScheduler` が `privacy-admin` へ閉鎖中の注意通知を送る。開示 package の削除 `PrivacyExportPackagePurgeScheduler` は閉でも止めない） | menu `privacy-dsar` |
| `NF10` | `/management-reports/**`、`/api/management-reports/**` | `ManagementReportScheduler#dispatchDue`（毎分。claim・生成・配布を開始しない） | menu `management-report` |

手動実行・テスト用の service メソッド（`processSlaCheck`、`monitorSlaClocks`、`processDailySnapshot`、`dispatch(asOf)` 等）はスイッチの対象外（本番画面・API の入口は上表の filter で止まる）。

## 3. 画面「機能の開放」（`/feature-enablement`）による開閉手順（prod 正本）

### 3.1 操作できる主体と初回配備（go-live）の位置づけ

- **操作できる主体（`FeatureEnablementActorPolicy`）**:
  - prod では **OIDC でログインし MFA を済ませた有効な `管理者`** に限る。break-glass の口座・break-glass incident の session・初回 bootstrap（再開を含む）の session では 403 になる。
  - **`NF07_RETENTION`・`NF07_DSAR` の申請・承認・却下・取下げ・閉鎖と、NF07 の署名種別・組 `NF07_DECISION_BINDING` の登録・確認・却下・取消し・延長**は、上記に加えて有効な **`privacy-admin` group の所属者**に限る（最低 2 名、推奨は `deploy-runbook.md` §5.0 の OIDC 管理者 C〜E の 3 名全員）。
- **初回配備（go-live）との関係（`deploy-runbook.md` §1.7）**:
  - 初回配備と初回 bootstrap（同 §3〜§5）は全 13 件の開放単位が `CLOSED` のまま完了し、**go-live（利用者への公開）の必須前提は「基盤・中核機能・常時有効の部分」だけ**である。
  - 各 S-NF 機能は go-live の時点で開いている必要は無く、それぞれの署名・受理・基盤準備が揃った機能から、以下の手順で 1 機能ずつ system の中で開く（ただし `INTEGRATION_HUB_BOUND_TENANT_ID` は将来の `NF05_OUTBOUND` 開放に備え、初回の起動から全ての起動で同じ値を設定し続ける）。

### 3.2 1つの開放単位を開く手順

1. **基盤・配備設定の準備（必要な機能のみ）**:
   - 常時必須の鍵（`PWA_CONTEXT_SECRET`・`CERTIFICATION_NUMBER_CRYPTO_*` 等）は初回配備から入っている。
   - 機能固有の配備設定・DB 状態が要る開放単位（NF-02 の全社共通カレンダー、NF-05 の鍵・pepper・truststore・許可一覧と outbound の `NF05_OUTBOUND_OFF` 記録、NF-06 の `IMPORT_ROW_REFERENCE_HMAC_KEY` と法人を持つ組織 1 件、NF-07 の `privacy-admin` 2 名以上・Journal 接続 `PRIVACY_JOURNAL_*`、副入口 REL/REG を使うなら `PRIVACY_TRUST_KMS_*`）を先に整える。task definition を変えた場合は全停止で再起動しておく。
2. **署名・受理（決定 `[決]` と証拠 `[証]`）の登録と二人確認**:
   - 管理者 A（NF-07 は `privacy-admin` A）が `/feature-enablement` の「署名・受理」タブから、対象開放単位が要する署名種別（4章の各節）ごとに署名文書（PDF、ClamAV 検査を通過するもの）・署名者役割・署名者氏名・参照（決定 ID 等）・署名日・失効日（署名日より後、最長 366 日。未来の署名日はその日まで確認不可）・構造化された値を入力して登録する（`REGISTERED`）。NF-07 の D-01・(D-06)・D-09 は「署名の組を登録」から組 `NF07_DECISION_BINDING` として 1 回で登録する。
   - 登録者 A とは**別の**管理者 B（NF-07 は `privacy-admin` B）が、元の決定書・受入記録と PDF の SHA-256・参照・期間・構造化された値（digest を含む種別は正規形から自分で再計算した値）を照合し、「確認」を押す（`CONFIRMED`）。A 本人には確認ボタンが出ず、API も 403 で拒否する。
3. **条件の評価と開放申請（管理者 A）**:
   - 「開放単位」タブで対象行の「詳細」または「再評価」（開放単位ごとに 1 分に 1 回まで）を押し、不足している条件（`UNSATISFIED` / `UNVERIFIABLE`）が 0 件であることを確かめる（子単位を開く場合は先に親単位を開いておく）。
   - 管理者 A が「開放を申請」を押し、必要なら理由を書いて確定する。その時点の条件の指紋（`condition_fingerprint`）を束縛した申請（`PENDING`、有効期限 7 日）が作られる。
4. **別の管理者による承認（管理者 B）**:
   - 申請者 A とは**別の**管理者 B が、**直近に管理者の role を付与・復帰された口座が承認者になっていないこと**（全管理者への通知と監査で確認。`deploy-runbook.md` §5.6）と、「詳細」の条件・署名一覧を確かめたうえで、「申請」タブの「承認」を押す。
   - 承認時に条件を再評価し、申請時の `condition_fingerprint` と一致しなければ 409 で拒否される（署名や設定が申請後に変わった場合は取り下げて申請し直す）。
5. **開放の反映と確認**:
   - **NF-05 以外の 11 単位**: 承認と同時に `OPEN`（実効値「開」）になり、再起動なしで画面・API・メニューが開き、scheduler も次回実行から動く。
   - **`NF05_INBOUND`・`NF05_OUTBOUND`**: 承認すると `OPEN_PENDING_RESTART`（実効値はまだ「閉（この起動では閉）」）になる。全停止で再起動すると起動前の読取りと `FeatureEnablementStartupEvaluator` により `OPEN` になる（outbound は `QUARANTINING` → 旧行 0 件で `ON`）。`NF05_INBOUND` を開いた起動を確認した後、LB/WAF の `/external-api/**` 遮断（SRE-2）を承認済み送信元 CIDR だけを通す rule に切り替える。

### 3.3 問題発生時に 1 つの開放単位だけ閉じる手順（管理者 1 名・即時）

1. 管理者 1 名（NF-07 は `privacy-admin` 1 名）が `/feature-enablement` の「開放単位」タブで対象行の「閉じる」を押し、理由区分（`MANUAL_SECURITY_INCIDENT`・`MANUAL_OWNER_DECISION`・`MANUAL_CONDITION_DOUBT`・`MANUAL_OPERATION_ERROR`・`MANUAL_VERIFICATION`・`MANUAL_OTHER`）を選んで確定する（NF-01 二者確認で有効な DUAL テンプレートが残っている場合は確認ダイアログに件数の警告が出る）。
2. **再起動なしで即時に** `MANUALLY_CLOSED`（実効値「閉」）になり、対象 URL が 404 に戻る（親を閉じると開いていた子も同時に `AUTO_CLOSED(PARENT_CLOSED)` になる。`NF05_INBOUND` を閉じた場合はあわせて LB/WAF の `/external-api/**` 遮断を戻す）。
3. 4 章の「閉の間も動き続けるもの」を確認し、必要なら追加の運用対処を行う。再び開くときは原因解消後に 3.2 の二人承認で行う。

### 3.4 緊急の総スイッチ（`FEATURE_ENABLEMENT_ALLOWED=false`）と誤設定の代償

- 画面 `/feature-enablement` や DB の書込み経路自体に問題があって画面から閉じられない緊急時、および **PITR（backup からの復元）の後の最初の起動**（`ops/backup/runbooks/restore-cutover.md` §4.7）にだけ、task definition の `FEATURE_ENABLEMENT_ALLOWED=false`（`deploy-runbook.md` §1.0 の 42）で全停止・再起動する。
- 起動時に**全 13 件の開放単位が一括で `AUTO_CLOSED(MASTER_SWITCH_CLOSED)`** になり、未決の申請は全て `CANCELLED`、NF-05 outbound の barrier も `OFF` に書き直される。`false` の間は開放・設定変更の申請と承認が全て 409 で拒否される（閲覧と署名・組の登録・確認・取消しは可能）。
- **誤って `false` にして起動したときの代償**: `FEATURE_ENABLEMENT_ALLOWED=true` に戻して再起動しても、**閉じた開放単位は 1 件も自動では開かない**。開いていた全ての開放単位について二人承認（申請＋承認）をやり直す必要があり、NF-05 は承認の後にもう 1 回の再起動と outbound の隔離確認も要る。平時の task definition 更新では `FEATURE_ENABLEMENT_ALLOWED=true` を変えないこと。

## 4. 注意事項と開放単位ごとの条件・署名種別

署名種別の `[決]` は決定（`SignOffKindClass.DECISION`）、`[証]` は証拠（`SignOffKindClass.EVIDENCE`。期間延長 `extend` の申請・二人承認が可能）を表す。有効期間はいずれも署名日より後・最長 366 日で、失効日（JST 0:00）を迎えると snapshot の `openUntil` により即時に実効閉になり、日の始まりの評価で `AUTO_CLOSED(SIGN_OFF_EXPIRED)` に遷移する。

### 4.1 共通

- **再起動の要否**: NF-05 以外の 11 単位と NF-02 の公開範囲設定は再起動なしで切り替わる。`NF05_INBOUND`・`NF05_OUTBOUND` だけは「開く変更」に全停止での再起動が要る（閉じる変更は即時）。rolling 更新で新旧の状態・設定を混在させないこと（単一 instance 運用、SRE-5）。
- **全 scheduler 一括停止（`APP_SCHEDULING_ENABLED=false` / `app.scheduling.enabled=false`）の重要な制限**:
  - 業務の `@Scheduled` だけでなく、機能の開放の **`FeatureEnablementEvaluationScheduler`（定期の条件評価・日の始まりの評価・署名失効の接近通知・DSAR 閉鎖中の未完了請求通知）と `FeatureSignOffPurgeScheduler`（終わった署名の 3 年後の消去 D17）も止まる**。
  - その間も、署名の失効日（JST 0:00）による遮断は snapshot の `openUntil` が時計を直接見て即時に効き、画面の申請・承認・手動の「再評価」も動くが、DB の状態や外部接続の変化（例: `privacy-admin` 人数の変化、Journal の不達、法人の未解決）による自動閉鎖と日次通知は走らない。drain 以外の常態では `APP_SCHEDULING_ENABLED=true` で運用する。
- **自動閉鎖の DB 書込み失敗時（`FEATURE_AUTO_CLOSE_PENDING`）**: 定期評価で条件不成立を検出したのに DB への `AUTO_CLOSED` の書込みに失敗した場合、その node はメモリ上の overlay（`overlay.<reason>`）で即時に実効閉にし、WARN `errorCode=FEATURE_AUTO_CLOSE_PENDING` を出して次周期に書込みを再試行する。**この WARN が出ている間（対応する `FEATURE_AUTO_CLOSED` が出る前）は再起動しない**（再起動するとメモリ overlay が消え、次の評価まで一瞬開き得る。やむを得ず再起動した場合は `deploy-runbook.md` §5.5.1 に従い直ちに画面で状態を確認・手動閉鎖する）。
- 機能スイッチは権限ではない。開放後のアクセス制御は従来どおり `SecurityConfig`・`MenuPermissionFilter`・action 権限で行う。
- 404 遮断は Spring Security より前で行うため、監査ログ（`ApiAuditFilter`）には記録されない（調査時は `logging.level.com.ses.config.feature=DEBUG`）。一方、開閉・申請・承認・署名操作そのものは全て `t_feature_enablement_event`・必須監査 `t_audit_log`（`method='FEAT_EN'`）・構造化ログ `FEATURE_ENABLEMENT_EVENT` に残る。
- 閉の間も、prod 起動時の必須 secret 検査（`PWA_CONTEXT_SECRET`、資格番号の暗号鍵 `CERTIFICATION_NUMBER_CRYPTO_*` 等。`deploy-runbook.md` §1.0 の 1〜26）は変わらない。閉を理由に secret 供給を省略できない。
- 閉→開の補完は保証しない。閉の期間中に到来した期限・閾値を開放後にさかのぼって処理するかは各機能の既存ロジックに従う。

### 4.2 NF-01（`NF01`）

- **必要な署名（7件）**: `[決]` `NF01_PLAN_SECURITY`（`IDENTITY_SECURITY`）・`NF01_PLAN_SRE`（`SRE`）・`NF01_PLAN_DOCUMENT_OWNER`（`DOCUMENT_OWNER`）・`NF01_PLAN_HR`（`HR`）・`NF01_PLAN_DBA`（`DBA`）、`[証]` `NF01_STAGING_VERIFICATION`（`PRODUCTION_OWNER`。lifecycle runbook §6.4・§6.6 の実測と確認）・`NF01_RUNNER_EVIDENCE`（`TECH_LEAD`。M4・M6・M10 等の実測）。公開前の read-only preflight（`scripts/nf01-readonly-preflight.ps1`）と 4.9.5 の確認 SQL（0件）も確認する。
- 承認画面（`/approval`・`/api/approval/**`）は NF-01 の入口ではないため、開の期間中に起票されたライフサイクル例外承認の未決分は、閉の間も承認画面から決裁でき、決裁時にライフサイクル task へ反映される（`ApprovalSlaScheduler` も動く）。閉じる前に未決の承認依頼を確認するか、決裁を保留する運用にすること。
- 資産返却（NF-09）は NF-09 側の常時有効機能であり、`NF01` では止めない。ライフサイクル SLA 監視は開放後の実行日を基準に判定する。

### 4.3 NF-02（`NF02`）

- **必要な署名と条件**: `[決]` `NF02_DG02`（`CS_OWNER`。DG-02 パッケージ、B3 休日の H-1・H-2 を含む）、および開放の時だけの条件 `calendar.companyWide`（`WorkCalendarConditionEvaluator` が当年度 JST を含む有効な全社共通カレンダーの登録を確認する。D-9 により、開いた後に年度替わり等でカレンダーが欠けても自動閉鎖はせず `missing_calendar` WARN を出す。照会失敗 `calendar.companyWide.unverifiable` も開放中は閉じず、申請・承認だけ 409 にする）。法人既定を登録した法人はその有効期間に全社共通を参照しないため、法人既定に祝日・会社休日が含まれていることも確認する。
- 開の期間中に作成された問い合わせ添付は文書台帳に登録済みのため、閉の間も既存の文書 API（顧客 DataScope を持つ利用者）から参照できる。添付補償の再試行（`RETRY` 台帳）は閉の間は止まり、再開後の次回実行で再開する。
- SLA 違反監視は再開後の初回実行でその時点の時刻を基準に判定する。顧客ヘルスの月次 snapshot は閉の期間中に生成されない。契約更新カレンダーは閉の間、顧客ヘルス欄を「-」表示にする。
- 所有者修復 API（`/api/admin/nf02-nf03/ownership-repair`、管理者専用）と既存の請求問い合わせメモ（`t_invoice.portal_inquiry`）は常時有効。

#### 4.3.1 顧客ポータルの子単位（`NF02_CUSTOMER_PORTAL`）と公開範囲設定

- **必要な署名**: 親 `NF02` が開であることに加え、`[決]` `NF02_PORTAL_PUBLICATION`（`PUBLICATION_OWNER`）・`NF02_PORTAL_DG02_AC`（`CS_OWNER`。DG-02-A/C）・`NF02_PORTAL_VISIBLE_SINCE_DECISION`（`CS_OWNER`。構造化された値 `visibleSince` は空または公開開始時刻）、`[証]` `NF02_PORTAL_BROWSER_CHECK`（`PRODUCTION_OWNER`。`g2r-browser-manual-acceptance.md`「第二段階」と起票・CSAT・詳細・返信モーダルの実ブラウザ確認）。さらに公開範囲 `nf02-customer-portal.visible-since` に時刻を設定している場合は、`[証]` `NF02_PORTAL_INVENTORY`（`CS_OWNER`。その時刻以降の社内登録分の件名・本文・公開返信・公開添付の棚卸し）も必須になる。
- 閉の間は、顧客からの起票・返信・CSAT 回答・添付 upload/download が全て 404 になり、社内担当が内部サービスデスク（`/service-desk`）へ代理起票する運用になる。CSAT は missing input となり減点されない。
- **初回応答時刻の記録条件**: 社内担当が状態を `IN_PROGRESS`（「対応着手」「対応再開」）へ変更したとき、または `PORTAL_VISIBLE` の返信を登録したときの早い方で記録される（`INTERNAL` メモだけでは記録されない）。子が閉の間は受付後に「対応着手」を押せば計測でき、回答内容の記録は再び開いた時の公開対象を増やさないよう **`INTERNAL`（社内限定メモ）を推奨**する。
- **開いたときに顧客へ出る範囲（`PortalServiceRequestVisibilityPolicy`）**: 次のいずれかを満たす問い合わせだけを扱う（範囲外は 404）。
  1. portal から起票された問い合わせ（`channel='PORTAL'` かつ `portal_user_id` あり）
  2. 設定 `nf02-customer-portal.visible-since`（dev・test は `FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE`）以降に作成された問い合わせ（未設定なら該当なし）
  - 既定（未設定）では、**社内で登録した問い合わせとその返信・添付は顧客へ一切出ない**。公開開始時刻を設定する場合は、まず `NF02_PORTAL_VISIBLE_SINCE_DECISION`（`visibleSince` に同じ時刻）と棚卸しの証拠 `NF02_PORTAL_INVENTORY` を登録・二人確認してから、画面 `/feature-enablement` の「設定」タブで申請し、別の管理者が承認する（承認の直後から効く）。
  - なお、顧客ポータル全画面の Bootstrap 参照修正（RB-I18N）は `NF02_CUSTOMER_PORTAL` の開閉と無関係に初回配備から効くため、portal 共通画面の実ブラウザ確認は go-live 自体の前提である（`deploy-runbook.md` §1.7「初回配備から有効になる部分」）。

### 4.4 NF-04（`NF04`）

- **必要な署名（5件）**: `[決]` `NF04_BUSINESS_OWNER`（`BUSINESS_OWNER`。利用者向け文言）・`NF04_SECURITY`（`SECURITY` または `BUSINESS_OWNER`。B-NF04-4・P1-N4-1 案 A・P1-W309-1 (a)）、`[証]` `NF04_SRE`（`SRE`。単一 instance / sticky session）・`NF04_PRIVACY`（`PRIVACY`。端末内 30 日保存・P3-13）・`NF04_DEVICE_ACCEPTANCE`（`ACCEPTANCE_OWNER`。真機受入 S-01〜S-15）。
- `NF04` の閉で止まるのは **PWA shell（`/service-worker.js`・`/manifest.webmanifest` と manifest リンク・SW 登録）だけ** である（閉の間は `common.js` が既存の SW 登録と `ses-pwa-shell-*` cache を解除し、オフライン中に画面を開き直せない）。
- **次は `NF04` が閉でも止めない（初回配備から常時有効。署名・真機受入は go-live の前提）**:
  - 要員の日次入力の保存経路（`/api/my/pwa/{attendance,timesheet,expenses,change-requests}`、`/api/my/session-context`、`/js/pwa-queue.js`）。
  - ロック済み下書きの復元（`/offline.html` → `/api/my/pwa/restore/{reauthenticate,confirm}`。ローカルパスワードで login した session のみ有効で、本番の OIDC session では入口を隠して 403 で拒否する）。
  - server 側の idempotency ledger 清掃（`PwaClientMutationCleanupScheduler`）と session 破棄時の復元失効処理（`PwaRestoreSessionListener`）。

### 4.5 NF-10（`NF10`）

- **必要な署名（4件）**:
  - `[決]` `NF10_REPORT_OWNER`（`REPORT_OWNER`）: Report Owner の開放署名、確定版の配布方針（§14.4）、「12回」の連続失敗（lease 期限超過の引き継ぎを含む）の数え方と所要時間見込み（30分 lease との関係、§14.2）、`RETRY_EXHAUSTED_DISABLED` の業務側宛先と対応手順。
  - `[決]` `NF10_SRE`（`SRE`）: alert rule の閾値・評価窓・重要度・通知経路（§10.6）、NTP 同期と許容する本 node・DB 時刻差（`ses_report_schedule_clock_skew_seconds`、§11 P2-2）。
  - `[決]` `NF10_TZ_POLICY`（`REPORT_OWNER` または `SRE`）: tenant timezone 変更時の方針と暫定扱い（§11 P2-3）。
  - `[決]` `NF10_DRAIN_APPROVAL`（`SRE` または `DBA`）: 排水・検出 SQL・復旧手順草案の承認（§10.1〜§10.4）。
  - `APP_SCHEDULING_ENABLED=false` の間は `SchedulingConditionEvaluator` が警告（`ADVISORY`）を出す（開放自体は妨げないが、有効化するまで定期配布は動かない）。
- 閉で止まるのは画面・API・`dispatchDue` である。閉じる時点で既に通知 outbox へ投入済みの配信通知は `NotificationOutboxScheduler` により送信され得るため、即時停止が要る場合は閉じる前に該当配信を取り消すこと。再開後の溜まった schedule は既存の `next_run_at` 計算に従う。

### 4.6 NF-03（`NF03`）と資格番号の子単位（`NF03_CERTIFICATION_PII`）

- **`NF03` 本体の必要な署名（1件）**: `[決]` `NF03_BUSINESS_OWNER`（`BUSINESS_OWNER`）。資格期限通知は実行日基準で閾値を判定するため、閉の期間中に到来した閾値の通知は開放後に送られない場合がある。
- **`NF03_CERTIFICATION_PII` の必要な署名（2件）**: 親 `NF03` が開であることに加え、`[決]` `NF03_DG07`（`PRIVACY` または `BUSINESS_OWNER`。`CERTIFICATION_PII` の保持年数・破棄方針）、`[証]` `NF03_EVIDENCE_PRIVACY`（`PRIVACY`。`CERTIFICATION_EVIDENCE` 証憑文書の扱い確認。`first-release-scope-confirmation.md` §0・§2.1）。
- 子が閉の間も資格申請・取得日・有効期限・証憑 upload・HR 確認・学習計画・skill gap は使え、止まるのは本人の申請・再申請での資格番号の入力だけである（API に資格番号を付けて送ると 400 `certification.record.numberEntryDisabled`）。閉へ戻しても保存済みの暗号文は消えず、`certification.pii.view` 権限者は復号表示できる。資格番号の暗号鍵（`CERTIFICATION_NUMBER_CRYPTO_*`）は閉でも prod 起動時に必須である。

### 4.7 NF-07（`NF07_RETENTION`・`NF07_DSAR`・副入口 3 件）

計画: `.kiro/specs/privacy-retention-dsar/production-bridge-phased-plan.md`。prod では `NF07_RETENTION` と `NF07_DSAR` のどちらも DB の開放状態で開閉し、操作は有効な `privacy-admin` に限る（`PRIVACY_RETENTION_ENABLED`・`PRIVACY_DSAR_ENABLED`・`PRIVACY_APPROVAL_*`・`PRIVACY_DSAR_DUE_DAYS`・`PRIVACY_DSAR_PACKAGE_MAX_AGE` を prod に置くと起動しない）。

#### 4.7.1 閉のときの応答と常時動く保護

- 登録済みの入口（`/privacy/holds/**`・`/api/privacy/holds/**`・`/api/privacy/policies/**`・`/api/privacy/catalog`、DSAR は `/privacy/dsar/**`・`/api/privacy/dsar/**`）は `FeatureSwitchFilter` と `PrivacyAccessGuard` が同じ本文の 404 にする。遮断表に無い `/api/privacy/<x>` は非管理者が 403、一般管理者が 403、`privacy-admin` が Spring の 404 になる。
- 開閉に関係なく、prod の起動時には `ProductionSecurityConfigurationValidator` が「`'*'` を持つ group（`privacy-admin` 以外）に `privacy.*` の拒否行がある」ことを常に検査し、破壊的経路（D1〜D10・D17）の `PrivacyHoldPathGuard` は掛けた hold を守り続ける。
- **DSAR が閉じている間の法定・運用期限の扱い**: `NF07_DSAR` を閉じても、受付済みの未完了請求（`t_privacy_request`）の `due_date` は止まらず進み続ける。通常の期限通知（`PrivacyDsarDeadlineScheduler`）は閉の間止まるため、代わりに日の始まりの評価（`FeatureEnablementEvaluationScheduler`）が未完了請求の件数と最も近い `due_date` を `privacy-admin` 全員へ毎日通知する（リンク先は `/feature-enablement`。「開き直すか、期限の管理を手で行ってください」と案内する）。

#### 4.7.2 開放の条件と署名・組・副入口（prod でも起動は止めない）

- **開放単位に必要な組と署名**:
  - **組 `NF07_DECISION_BINDING`**（必須 member: `[決]` `NF07_D01`（`PRIVACY_OWNER`）・`NF07_D09`（`D09_SIGNER`。構造化された値 `tenantId`・`capabilities`・`approvalDigest`）、任意 member: `[決]` `NF07_D06`（`D06_OWNER`。構造化された値 `dueDays`・`packageMaxAge`。`D-09` が `dsar` を含む時は必須））。登録者と別の `privacy-admin` が組として 1 回で確認する（確認時に下の正規形の digest・tenant・`D-06` の有無を検査し、不一致なら 400 `bundleInconsistent`）。
  - **`NF07_RETENTION` の追加署名と鍵**: `[証]` `NF07_BRIDGE`（`TECH_LEAD`。production bridge W-1〜W-12 接続確認）、および配備設定の Journal 接続 4 項目（`PRIVACY_JOURNAL_DATASOURCE_URL`・`_USERNAME`・`_PASSWORD`・`PRIVACY_JOURNAL_JOURNALID`）。
  - **`NF07_DSAR` の追加署名**: 上の組（`capabilities` に `dsar` を含み `NF07_D06` を含むもの）に加え、`[証]` `NF07_DSAR_HOLD_ACCEPTANCE`（`D05_OWNER` / `D06_OWNER`。D14・D15 の受理）・`NF07_DSAR_SELF_DELIVERY`（`D06_OWNER`。SELF 交付区分）・`NF07_DSAR_I10`（`PRIVACY`。草案全内容取得の不変条件）・`NF07_DSAR_REDACTION`（`D06_OWNER`。redaction 規則の確認前提）。
  - **`NF07_RETENTION` の副入口（開放単位とは独立に要求ごとに判定。不足しても `NF07_RETENTION` 自体は閉じず、その副入口だけが閉じる）**:
    - `HOLD_START`（hold 開始）: `[決]` `NF07_D05`（`D05_OWNER`）、`[証]` `NF07_D05_OWNER_ACCEPTANCE`（`D05_OWNER`。`deploy-runbook.md` §4.4 末尾と §4.5 の残余受理）。不足時は 409 `error.privacy.decisionUnknown`。
    - `REL`（hold 解除 `POST /api/privacy/holds/{holdId}/release`）: `[決]` `NF07_D03`（`D03_OWNER`）・`NF07_D05`、`[証]` `NF07_TRUST_KMS`（`SECURITY` / `D03_OWNER`）・`NF07_TRUST_STAGING`（`SRE`）、および有効な trust 文書（4.7.7）。不足時は 404。
    - `REG`（policy 登録・有効化 `POST /api/privacy/policies` 等）: `[決]` `NF07_D01`・`NF07_D02`（`D02_OWNER`）・`NF07_D03`、および有効な trust 文書（4.7.6・4.7.7）。不足時は 404。
- **評価器 `PrivacyProductionConfigurationValidator` が検査する条件**（不成立なら開けない・開いていれば自動閉鎖。起動時は WARN `NF07_PRIVACY_STOPPED_AT_STARTUP` を出すだけで **prod でも起動を止めない**。DSAR の不足は保持・保全を閉じない）:

| # | 条件 | 欠けたときの違反コード（例） |
|---|---|---|
| 1 | 決定 ID 束縛（`PrivacyDecisionBinding`。確認済みの組 `NF07_DECISION_BINDING` から読む）に不足が無く、承認 tenant が設定 tenant と一致し、capability が含まれ、必要な決定（retention は D-01・D-09、dsar は D-01・D-06・D-09）の ID・期間と `approvalDigest` が一致 | `decisionBinding.tenantId.mismatch` / `decisionBinding.capabilities.retention.notApproved` / `decisionBinding.approvalDigest.mismatch` |
| 2 | `privacy-admin` group が有効・未削除で `privacy.*` を許可し、`'*'`・`privacy.` 接頭辞の拒否行を持たない | `privacyAdminGroup.notGrantingPrivacy` |
| 3 | `privacy-admin` の有効な OIDC 管理者（break-glass 口座と `'*'`/`privacy.` 拒否 group 所属者を除く。一時的なアカウントロック中も数える）が **2 名以上**（推奨 3 名）。`AdministratorContinuityGuard` は `NF07_RETENTION`・`NF07_DSAR` のどちらかが開いているか未決の申請がある間だけ 2 名未満への変更を拒否する（状態表を読めなければ 409 で拒否） | `privacyAdmins.insufficient[current=1;required=2]` / `privacyAdmins.oidcProviderUnavailable` |
| 4 | `privacy-admin` 以外の有効な group が `privacy.` 接頭辞の許可行を持たない | `privacyAllow.outsidePrivacyAdmin[<group key>;…]` |
| 5 | DSAR: `NF07_D06` の `dueDays`（1〜366）・`packageMaxAge`（1時間〜366日）が有効で、F2 provider が 1 件以上登録済み | `dsar.dueDaysUnset` / `dsar.packageMaxAgeUnset` / `dsar.providersUnregistered` |
| 6 | 保持・保全: Journal の開放条件（`PrivacyJournalStartupVerifier.enablementResult`、appender 権限）が適合（最低 `app.feature-enablement.journal-reverify-interval`＝既定 10 分間隔で再検査） | `journal.notConfigured` / `journal.privilege.missing:pj_head.INSERT` / `journal.unreachable`（評価できない類） |

- **時間要因と監視**: 失効日（JST 0:00）を迎えた決定を要する開放単位は `openUntil` と `PrivacyDecisionWindowMonitor` / `PrivacyDsarDecisionWindowCondition` により同日から 404 になり、日の始まりの評価で `AUTO_CLOSED` になる。失効の警告日数は prod では `/system-config` の `feature-enablement.expiry-warning-days`（既定 30 日、1〜366。dev・test は `privacy.approval.expiry-warning-days`）で、metric `ses_privacy_decision_binding_state`・`ses_privacy_decision_binding_days_remaining` を監視する。

#### 4.7.3 決定 ID 束縛の digest 正規形と golden vector（`PrivacyDecisionBindingTest` で固定）

- 決定 ID の規則は NF-08 と同じ（英数字で始まる 4〜64 文字、`TBD`・`TODO`・`PENDING`・`DRAFT`・`UNKNOWN`・`XXXX`・`NONE`・`NULL`・`N-A` 等は拒否、必要な決定間で重複不可）。
- **digest の正規形**: UTF-8、1 行目が `nf07-privacy-decision-binding-v1`、以降が `tenant-id`、`capabilities`（小文字・昇順・カンマ区切り）、承認 capability が要する決定を `d01`→`d06`→`d09` の順に `dNN.decision-id` / `dNN.signed-on` / `dNN.expires-on`（全行 LF 終端、不要な D-06 は含めない）。`D-06` の `dueDays`・`packageMaxAge` は digest に入らないため、組の確認者が決定書と目視照合する。

```sh
printf 'nf07-privacy-decision-binding-v1\ntenant-id=tenant-a\ncapabilities=retention\nd01.decision-id=D01-NF07-2026-0001\nd01.signed-on=2026-11-01\nd01.expires-on=2027-10-31\nd09.decision-id=D09-NF07-2026-0009\nd09.signed-on=2026-11-10\nd09.expires-on=2027-05-31\n' | sha256sum
# a4ce05f19b45525b88857b66b6b03d8d2a793f60f3805d616c5719c7c6503404
```

- **dev・test（`source=properties`）で読む設定キー**: `privacy.approval.tenant-id`（`PRIVACY_APPROVAL_TENANT_ID`）、`privacy.approval.capabilities`（`PRIVACY_APPROVAL_CAPABILITIES`）、`privacy.approval.d01.*` / `d06.*` / `d09.*` / `d02.*` / `d03.*` / `d05.*`、`privacy.approval.approval-digest`（`PRIVACY_APPROVAL_DIGEST`）、`privacy.approval.expiry-warning-days`、`privacy.dsar.due-days`、`privacy.dsar.package-max-age`（prod では全て設定禁止。`PRIVACY_DSAR_DEADLINE_WARNING_DAYS` だけは運用値として prod でも設定可）。

#### 4.7.4 開く手順と `privacy-admin` 確認 SQL

1. **privacy-admin の割当**（最低 2 名、推奨 3 名。最初の割当は Owner と Security が対象者を事前確認し、割当後に監査 `PRIVACY_ADMIN_GROUP_CHANGED` を二者で照合する。`PUT /api/permission-groups/users/{id}` で `privacy-admin` **だけ**を割り当てる）:
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
2. **Journal の接続**: `PRIVACY_JOURNAL_*` を `nf07_journal_appender` で設定して起動し、`[privacy-journal] Journal の起動時検査に適合しました（hold の効力・開放の条件とも）` を確認する（`deploy-runbook.md` §3.6）。
3. **組・署名の登録と確認**: `privacy-admin` A が組 `NF07_DECISION_BINDING` と必要な証拠署名（副入口も開けるなら `NF07_D02`・`NF07_D03`・`NF07_D05` 等も）を登録し、別の `privacy-admin` B が正規形から再計算した digest と決定書を照合して確認する。失効前の更新では変わらない署名を「引き継ぐ」で組に含められる（確認の直後から新しい期間が効く）。
4. **申請と承認**: `privacy-admin` A が `NF07_RETENTION` / `NF07_DSAR` を申請し、別の `privacy-admin` B が承認する（即時開、再起動不要）。

#### 4.7.5 policy の登録・有効化（副入口 `REG`）と trust 文書（`REL`・`REG` 共用）

- **副入口 `REG`（`POST /api/privacy/policies`・`/{id}/activate`・`/{id}/withdraw`）**: `NF07_RETENTION` が開いており、確認済み署名 `NF07_D01`・`NF07_D02`・`NF07_D03` が有効で、trust 文書に授権 tenant を含む有効期間内の Ed25519 `ACTIVE` anchor が 1 件以上ある間だけ開く（不足時は 404。新しい署名を二人で確認した直後から自動で開く）。登録者 A と有効化者 B は別の `privacy-admin`。
- **trust 文書（`PRIVACY_TRUST_KMS_ANCHOR_FILE`・`PRIVACY_TRUST_KMS_MAX_DOCUMENT_AGE`・任意 `PRIVACY_TRUST_KMS_RELOAD_INTERVAL`）**: EFS の読取り専用 mount（例: `/app/privacy-trust`、`deploy-runbook.md` §1.6 の 11・§4.6。mount 先に `/`・`/app`・`/app/config*`・`/app/uploads*` は使えない）に置く。設定・文書の構造的不備は起動時に ERROR `NF07_TRUST_STRUCTURE_INVALID` を出して副入口 `REL`・`REG` だけを閉じ、**prod の起動自体は止めない**（FE-C04。成否の変化は履歴 `SUBGATE_CLOSED`・`SUBGATE_OPENED` と通知に出る）。

### 4.8 NF-06（`NF06`: 取込センター画面と開放の前提）

正本: `.kiro/specs/data-migration-import-center/a1a-ingress-operations.md`。開いても apply 系は含まない（B-NF06-3）。

- **必要な署名（5件）と鍵**:
  - 鍵: `IMPORT_ROW_REFERENCE_HMAC_KEY`（secret store から注入。Base64 で復号後 32 byte 以上。開発用・test 用の鍵は不可。差し替えると過去のエラー CSV の行参照と照合できなくなる）。
  - `[決]` `NF06_ADMISSION`（`DATA_OWNER`。参照＝決定 ID、構造化された値 `decisionId`・`tenantId`・`sourceCodes`・`approvalDigest`。prod では `IMPORT_ADMISSION_*` を置くと起動しない）。
  - `[証]` `NF06_PRIVACY_RAW_CSV`（`PRIVACY`。raw CSV の保持期間と preview マスク分類の書面受容）・`NF06_SRE_LB`（`SRE`。LB idle timeout 130 秒以上・SRE-4・SRE-5）・`NF06_DBA_ACCOUNT`（`DBA`。全 instance が同じアプリ DB user 名を使い alternating-users rotation をしないこと）・`NF06_STAGING_CHECK`（`PRODUCTION_OWNER`。LB timeout 後の回復と行参照鍵欠落時の 503 表示確認）。
- **評価器 `ImportCenterEnablementConditions` が検査する条件**（不成立でも **prod の起動は止めず**、開けない・開いていれば自動閉鎖する）:
  1. `NF06_ADMISSION` の承認内容と `approvalDigest` が正規形と一致（source code は小文字化して **ASCII 順 `LC_ALL=C sort`** で並べる）。
  2. `IMPORT_ROW_REFERENCE_HMAC_KEY` が有効で本番用。
  3. V193 の `import.metadata.manage` 明示許可（`role-admin`・`privacy-admin` に許可、他 group に拒否）があり、操作者が拒否 group に属さない。
  4. **単一法人前提**: `SELECT COUNT(DISTINCT legal_entity_id) FROM m_organization_unit WHERE legal_entity_id IS NOT NULL AND deleted_flag = 0;` が `1`（新規導入は V60 の `LEGACY` のみで `0` のため、開く前に法人を持つ組織を登録する）。
  5. 透過再接続・複数 host の JDBC URL 禁止（`ImportJdbcReconnectGuard` は常時起動時に検査）。
- **`NF06_ADMISSION` の golden vector**（合成値。`ImportAdmissionPortTest` で固定）:

```sh
printf 'nf06-import-admission-approval-v1\ndecision-id=DO-NF06-2026-001\ntenant-id=default\nsigned-on=2026-12-01\nexpires-on=2027-11-30\nentity=CUSTOMER\nschema-version=ses-import-v1\nsource-codes=crm,crm-2,crm2,crm_2\n' | sha256sum
# c5dc5f03efdb4fd22c23cdf86c7d248f22af96d187880a87f07664a97b173550
```

- **エラー CSV の最終行 `#END`**: 最終行 `#END,<code行の件数>,<REJECTED行の件数>` はデータ行ではないため、Excel や script で集計する前に件数一致を確かめてから除外する（最終行が無い・件数が合わない CSV は途中切断のため再出力する）。

### 4.9 NF-01 の子単位: 二者確認（`NF01_DUAL_CONFIRMATION`）

- **必要な署名（5件）**: 親 `NF01` が開であることに加え、`[決]` `NF01_DUAL_PRIVACY`（`PRIVACY_OWNER`。comment 2 列の保存期間・削除/匿名化経路・role 級の表示範囲・営業代理提出時の差戻し理由伝達。参照は英字と数字の両方を含む 4〜128 文字で placeholder 不可。prod では旧い `FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF` を置くと起動しない）・`NF01_DUAL_BREAK_GLASS_DECISION`（`SECURITY`。break-glass scope の扱い）、`[証]` `NF01_DUAL_MANUAL_CHECK`（`PRODUCTION_OWNER`。`deploy-runbook.md` §1.7.1 の手動確認 9 項目）・`NF01_DUAL_RUNNER_EVIDENCE`（`TECH_LEAD`。M1・M8 実測）・`NF01_DUAL_REVIEW_PASS`（`TECH_LEAD`。W3-21a・W3-21b）。
- **評価器 `LifecycleDualConfirmationEnablementConditions`**（不成立でも **prod の起動は止めない**）: `NF01_DUAL_PRIVACY` の参照形式、および下の確認 SQL (1)・(2)（許可値外の証跡種別が 0 件）を検査する。
- **閉じる時の注意**: 閉に戻しても、開の間に作った有効な `DUAL_CONFIRMATION` テンプレートは残る（画面の閉鎖確認ダイアログが件数を警告する）。閉の間は該当テンプレートを無効化するか、`DUAL_CONFIRMATION` を含まない版へ改定すること（下の SQL (3) で確認）。

#### 4.9.1 証跡種別の確認 SQL（DDL なし）

```sql
-- (1) テンプレートのタスク定義で、許可値一覧の外にある証跡種別（0件であること）
SELECT t.id AS template_id, t.template_type, t.version_no, t.status,
       tt.id AS template_task_id, tt.task_code, tt.evidence_type, HEX(tt.evidence_type) AS evidence_type_hex
  FROM m_lifecycle_template_task tt
  JOIN m_lifecycle_template t ON t.id = tt.template_id
 WHERE tt.deleted_flag = 0 AND t.deleted_flag = 0
   AND CAST(tt.evidence_type AS BINARY) NOT IN
       ('NONE', 'COMMENT', 'SELF_DECLARATION', 'DOCUMENT_LINK', 'SYSTEM_CHECK', 'DUAL_CONFIRMATION');

-- (2) 案件のタスクで、許可値一覧の外にある証跡種別（0件であること）
SELECT id AS task_id, case_id, task_code, status, evidence_type, HEX(evidence_type) AS evidence_type_hex
  FROM t_lifecycle_task
 WHERE deleted_flag = 0
   AND CAST(evidence_type AS BINARY) NOT IN
       ('NONE', 'COMMENT', 'SELF_DECLARATION', 'DOCUMENT_LINK', 'SYSTEM_CHECK', 'DUAL_CONFIRMATION');

-- (3) 子が閉の間に有効なまま残っている DUAL テンプレート（閉の間は0件にする）
SELECT t.id AS template_id, t.template_type, t.version_no, t.valid_from, t.valid_to
  FROM m_lifecycle_template t
 WHERE t.deleted_flag = 0 AND t.status = 'ACTIVE'
   AND EXISTS (SELECT 1 FROM m_lifecycle_template_task tt
                WHERE tt.template_id = t.id AND tt.deleted_flag = 0
                  AND CAST(tt.evidence_type AS BINARY) = 'DUAL_CONFIRMATION');
```

### 4.10 NF-05 公開 API（`NF05_INBOUND`・`NF05_OUTBOUND`）

正本: `.kiro/specs/integration-hub-public-api/dg-05-enablement-decision-draft.md`、`ops/security/runbooks/integration-hub-public-api.md`。inbound と outbound は独立した最上位の開放単位である。

- **必要な鍵・配備設定と署名**:
  - 共通の鍵・設定: `INTEGRATION_HUB_PUBLIC_ID_KEY`・`INTEGRATION_HUB_CRYPTO_CURRENT_KEY_VERSION`（と鍵）・`INTEGRATION_HUB_CRYPTO_CREDENTIAL_HASH_PEPPER`・初回の起動から一貫した `INTEGRATION_HUB_BOUND_TENANT_ID`・承認内容（CIDR・送信先・rate 等）。outbound はさらに `INTEGRATION_HUB_TRUSTSTORE_PATH`・`INTEGRATION_HUB_TRUSTSTORE_PASSWORD`・`integration.hub.provider.mode=HTTPS`。
  - `NF05_INBOUND` の署名（7件）: `[決]` `NF05_DG05_IN`（`NF05_OWNER`。参照＝`DG-05-IN-*`、構造化された値 `approvalDigest`）・`NF05_DIGEST_RULE_DECISION`（`SECURITY` / `NF05_OWNER`。正規形 v2 判断）、`[証]` `NF05_IN_SRE_WAF`（`SRE`。LB/WAF と XFF）・`NF05_SRE_LOAD`（`SRE`。負荷実測）・`NF05_STAGING_WIRE`（`SRE`。実ネットワーク TLS/XFF/WAF）・`NF05_QUIESCE_ACCEPTANCE`（`NF05_OWNER`。静止中の 503 受理）・`NF05_CLIENT_NOTICE`（`NF05_OWNER`。client 周知）。
  - `NF05_OUTBOUND` の署名（6件）と遷移前提: `[決]` `NF05_DG05_OUT`（`NF05_OWNER`。参照＝`DG-05-OUT-*`、構造化された値 `approvalDigest`）・`NF05_DIGEST_RULE_DECISION`、`[証]` `NF05_OUT_G3R`（`NF05_OWNER`。OUT-7・OUT-9）・`NF05_OUT_CHANGE_ACCEPTANCE`（`NF05_OWNER` / `SRE`。truststore 等の変更が再署名を要することの受理）・`NF05_SRE_LOAD`・`NF05_STAGING_WIRE`、および開放の遷移の前提 `barrier.offRecorded`（`m_system_config` の `v1|OFF|0|<指紋>` と監査 `NF05_OUTBOUND_OFF`）。
- **開は再起動で反映、閉は即時反映**:
  - 申請・二人承認すると `OPEN_PENDING_RESTART` になり、全停止での次の再起動で Spring context 生成前の JDBC 読取り（`IntegrationHubEnablementEnvironmentPostProcessor`）と `FeatureEnablementStartupEvaluator` が条件と法人未解決を検査して `OPEN` に遷移させる（条件不成立や法人未解決があれば app は止めず `AUTO_CLOSED` にする）。
  - 閉じる操作・署名取消し・自動閉鎖は `IntegrationHubRuntimeSwitch` により再起動なしで即時に効き、確定閉鎖後の未送信 outbound 行は DLQ `STARTUP_REVALIDATION_REQUIRED` へ移る（再開後は bulk replay で再検証して戻せる）。
- **truststore や digest 対象の配備設定を変えるとき（再起動 2 回）**: 新しい設定・truststore を入れた task definition で **1 回目の再起動**（旧署名と合わないため NF05 は閉で立ち上がり、新設定が process に読まれる）→ 画面で新署名（`NF05_DG05_IN` / `NF05_DG05_OUT`）の登録・二人確認と開放申請・二人承認（`OPEN_PENDING_RESTART`）→ **2 回目の再起動**で `OPEN` にする（`deploy-runbook.md` §5.5.1）。

## 5. 新しい入口・開放単位を追加するとき

1. 既存の開放単位へ入口を足す場合: `Feature` の該当単位へ画面 prefix・API prefix・静的 path・menu key を追加し、`FeatureSwitchRouteCoverageTest` の対象（`@Scheduled` は `GATED_SCHEDULERS` または理由付き `UNGATED_SCHEDULERS`）を更新する。
2. 新しい開放単位を足す場合:
   - `Feature`・`FeatureSwitchProperties`・`application.yml`・`application-test.yml` に定義を足す（`application-prod.yml` にはスイッチの placeholder を足さない）。
   - `FeatureEnablementCatalog.standard()` に開放単位・必要な `SignOffKind`・`KeyRequirement`・固有評価器の要否を登録する。
   - 承認設定を署名・設定へ移す場合は `FeatureEnablementProductionValidator.LEGACY_SETTING_RULES` と `infra/ecs/offline/validate-task-definition.ps1` の `$legacySwitchKeys` / `$legacySettingPrefixes` の両方を更新する（`FeatureEnablementLegacySettingTest` が両方の一致を検査する）。
   - 本書と `deploy-runbook.md` §1.7 の署名対応表を更新する。
3. 検出の限界: NF service package を経由せず mapper・entity だけに依存する入口、`@EventListener`・`HttpSessionListener`・outbox の type 別処理、既存画面の JS から新たに NF API を呼ぶ導線は `FeatureSwitchRouteCoverageTest` が自動検出しないため、レビューで確認する。

## 6. 検証テスト

| テスト | 実行環境 | 内容 |
|---|---|---|
| `FeatureSwitchDefaultsTest` | Docker不要 | dev 既定 OFF、property 源での単一機能・親子 ON/OFF と不正真偽値の bind 失敗、test profile の既定、`application-prod.yml` が `source: database` で旧 placeholder を持たず prod で全 13 単位の旧変数が拒否されること |
| `FeatureEnablementCatalogCoverageTest` | Docker不要 | 全 13 `Feature` の目録登録、署名種別・組・鍵・評価器の一貫性 |
| `FeatureEnablementProductionValidatorTest` / `FeatureEnablementLegacySettingTest` | Docker不要 | prod での総スイッチ・源・運用値上書き・全旧変数と移した承認設定の拒否、Java と `validate-task-definition.ps1` の拒否一覧の完全一致 |
| `ProductionTaskDefinitionTemplateTest` | Docker不要 | `task-definition.prod.json` と `validate-task-definition.ps1` の契約（`FEATURE_ENABLEMENT_ALLOWED`、旧変数拒否、`privacy-trust` mount 制限） |
| `FeatureEnablementServiceH2Test` / `FeatureSignOffServiceH2Test` / `FeatureSignOffBundleH2Test` / `FeatureSignOffExtensionH2Test` | Docker不要（H2） | 申請・別管理者承認・自己承認拒否・指紋不一致・手動閉鎖・親閉鎖による子の連動閉鎖・署名の登録/二人確認/取消し/延長・組 `NF07_DECISION_BINDING` の digest 検査 |
| `FeatureEnablementEasyUnitsH2Test` / `FeatureEnablementNf02CalendarH2Test` | Docker不要（H2） | 7 単位および NF02 カレンダー条件・日の始まりの評価・失効自動閉鎖 |
| `LifecycleDualConfirmationEnablementConditionsTest` / `ImportCenterEnablementConditionsTest` / `PrivacyProductionConfigurationValidatorTest` / `IntegrationHubEnablementConditionsTest` | Docker不要（H2） | NF01 二者確認・NF06・NF07・NF05 の各評価器と署名/組からの承認値の読取り（prod 起動を止めないこと） |
| `FeatureSwitchFilterTest` / `FeatureSwitchMenuAndViewTest` / `FeatureSwitchSchedulerGateTest` / `FeatureSwitchRouteCoverageTest` / `FeatureSwitchChildTemplateRenderingTest` | Docker不要 | 404 遮断・メニュー非表示・scheduler no-op・登録漏れ/誤遮断の検出・子単位 OFF 時のテンプレート描画 |
| `FeatureSwitchServletContainerIntegrationTest` | Docker不要・実Tomcat | 実 Servlet container 上での未認証/管理者 404 同一性・header・filter 順 |
| `FeatureEnablementSchemaMySqlTest` / `IntegrationHubEnablementRestartMySqlTest` / `FlywayNf06ImportCenterMenuMigrationTest` | Docker必須（`-Pmysql-tests`） | V203 の生成列・複合 FK・追記専用 trigger、NF05 の再起動開放・即時閉鎖・未送信行隔離、V193 の権限 seed |
| `FeatureEnablementScreenBrowserTest` | 実Chrome（`-Pbrowser-tests`） | `/feature-enablement` の一覧・署名 PDF 登録・別管理者確認・開放申請・自己承認抑止・別管理者承認・手動閉鎖・履歴・375px 検索フォーム折りたたみ・console error 0 件 |
